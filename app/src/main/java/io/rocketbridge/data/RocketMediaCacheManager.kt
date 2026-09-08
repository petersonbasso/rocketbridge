package io.rocketbridge.data

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RocketMediaCacheManager(private val context: Context) {

    companion object {
        private const val TAG = "RocketMediaCache"
        private const val CACHE_DIR_NAME = "rocket_media_cache"
        private const val MAX_CACHE_SIZE_BYTES = 250L * 1024L * 1024L // 250 MB
        private const val PRUNE_TARGET_BYTES = 200L * 1024L * 1024L // Reduz para 200 MB quando atinge o limite

        @Volatile
        private var instance: RocketMediaCacheManager? = null

        fun getInstance(context: Context): RocketMediaCacheManager {
            return instance ?: synchronized(this) {
                instance ?: RocketMediaCacheManager(context.applicationContext).also { instance = it }
            }
        }

        fun shouldIntercept(url: String): Boolean {
            val cleanUrl = url.substringBefore('?').substringBefore('#').lowercase()
            val isHttp = cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")
            if (!isHttp) return false

            // Uploads, avatares e arquivos do Rocket.Chat
            if (cleanUrl.contains("/file-upload/") ||
                cleanUrl.contains("/avatar/") ||
                cleanUrl.contains("/ufs/")
            ) {
                return true
            }

            // Extensões de mídia de imagem estática
            val mediaExtensions = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".bmp")
            if (mediaExtensions.any { cleanUrl.endsWith(it) }) {
                return true
            }

            return false
        }
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // Mapa de travas em memória para evitar downloads duplicados simultâneos da mesma URL
    private val activeDownloadLocks = ConcurrentHashMap<String, Any>()

    fun shouldIntercept(url: String): Boolean = Companion.shouldIntercept(url)

    fun interceptRequest(
        request: WebResourceRequest,
        authToken: String?,
        userId: String?
    ): WebResourceResponse? {
        val url = request.url.toString()
        if (!shouldIntercept(url)) {
            return null
        }

        val cacheKey = hashUrl(url)
        val dataFile = File(cacheDir, "$cacheKey.dat")
        val metaFile = File(cacheDir, "$cacheKey.meta")

        // 1. Tenta servir do cache em disco local se já existir e não estiver vazio
        if (dataFile.exists() && dataFile.length() > 0 && metaFile.exists()) {
            try {
                dataFile.setLastModified(System.currentTimeMillis())
                val mimeType = metaFile.readText().trim().ifBlank { guessMimeType(url) }
                val encoding = if (mimeType.contains("svg", ignoreCase = true)) "UTF-8" else null

                val headers = createResponseHeaders(mimeType)
                return WebResourceResponse(
                    mimeType,
                    encoding,
                    200,
                    "OK",
                    headers,
                    FileInputStream(dataFile)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao ler cache local de $url: ${e.message}")
            }
        }

        // 2. Não está em cache ou falhou a leitura: efetua o download e grava no cache
        val lock = activeDownloadLocks.computeIfAbsent(cacheKey) { Any() }
        synchronized(lock) {
            // Verifica novamente sob lock (double-checked locking)
            if (dataFile.exists() && dataFile.length() > 0 && metaFile.exists()) {
                try {
                    dataFile.setLastModified(System.currentTimeMillis())
                    val mimeType = metaFile.readText().trim().ifBlank { guessMimeType(url) }
                    val encoding = if (mimeType.contains("svg", ignoreCase = true)) "UTF-8" else null
                    return WebResourceResponse(
                        mimeType,
                        encoding,
                        200,
                        "OK",
                        createResponseHeaders(mimeType),
                        FileInputStream(dataFile)
                    )
                } catch (ignored: Exception) {}
            }

            try {
                val okHttpRequestBuilder = Request.Builder().url(url)

                // Repassa headers relevantes da requisição original do WebView
                request.requestHeaders?.forEach { (headerName, headerValue) ->
                    if (!headerName.equals("Cookie", ignoreCase = true)) {
                        okHttpRequestBuilder.addHeader(headerName, headerValue)
                    }
                }

                // Configura cookies de autenticação do WebView
                val cookieManager = CookieManager.getInstance()
                var cookieString = cookieManager.getCookie(url) ?: ""
                if (!authToken.isNullOrBlank() && !userId.isNullOrBlank()) {
                    if (!cookieString.contains("rc_token")) {
                        cookieString = if (cookieString.isNotBlank()) {
                            "$cookieString; rc_token=$authToken; rc_uid=$userId"
                        } else {
                            "rc_token=$authToken; rc_uid=$userId"
                        }
                    }
                    okHttpRequestBuilder.addHeader("X-Auth-Token", authToken)
                    okHttpRequestBuilder.addHeader("X-User-Id", userId)
                }

                if (cookieString.isNotBlank()) {
                    okHttpRequestBuilder.header("Cookie", cookieString)
                }

                val response = httpClient.newCall(okHttpRequestBuilder.build()).execute()
                if (!response.isSuccessful) {
                    response.close()
                    return null
                }

                val body = response.body ?: run {
                    response.close()
                    return null
                }

                val rawContentType = body.contentType()?.toString()
                val mimeType = rawContentType?.split(";")?.get(0)?.trim()?.ifBlank { null }
                    ?: guessMimeType(url)

                val tempFile = File(cacheDir, "$cacheKey.tmp")
                FileOutputStream(tempFile).use { fos ->
                    body.byteStream().use { input ->
                        input.copyTo(fos)
                    }
                }
                response.close()

                if (tempFile.length() > 0) {
                    if (dataFile.exists()) dataFile.delete()
                    if (tempFile.renameTo(dataFile)) {
                        metaFile.writeText(mimeType)
                        dataFile.setLastModified(System.currentTimeMillis())
                        pruneCacheIfNeeded()

                        val encoding = if (mimeType.contains("svg", ignoreCase = true)) "UTF-8" else null
                        return WebResourceResponse(
                            mimeType,
                            encoding,
                            200,
                            "OK",
                            createResponseHeaders(mimeType),
                            FileInputStream(dataFile)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao baixar/cachear imagem de $url: ${e.message}")
            } finally {
                activeDownloadLocks.remove(cacheKey)
            }
        }

        return null
    }

    private fun createResponseHeaders(mimeType: String): Map<String, String> {
        return mapOf(
            "Content-Type" to mimeType,
            "Cache-Control" to "public, max-age=31536000, immutable",
            "Access-Control-Allow-Origin" to "*",
            "Accept-Ranges" to "bytes"
        )
    }

    private fun guessMimeType(url: String): String {
        val cleanUrl = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            cleanUrl.endsWith(".png") -> "image/png"
            cleanUrl.endsWith(".jpg") || cleanUrl.endsWith(".jpeg") -> "image/jpeg"
            cleanUrl.endsWith(".gif") -> "image/gif"
            cleanUrl.endsWith(".webp") -> "image/webp"
            cleanUrl.endsWith(".svg") -> "image/svg+xml"
            cleanUrl.endsWith(".bmp") -> "image/bmp"
            cleanUrl.endsWith(".ico") -> "image/x-icon"
            cleanUrl.contains("/avatar/") -> "image/jpeg"
            else -> "image/jpeg"
        }
    }

    private fun hashUrl(url: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(url.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun pruneCacheIfNeeded() {
        val totalSize = getCacheSizeBytes()
        if (totalSize <= MAX_CACHE_SIZE_BYTES) return

        Thread {
            try {
                val files = cacheDir.listFiles() ?: return@Thread
                val sortedFiles = files.filter { it.isFile && it.name.endsWith(".dat") }
                    .sortedBy { it.lastModified() }

                var currentSize = totalSize
                for (datFile in sortedFiles) {
                    if (currentSize <= PRUNE_TARGET_BYTES) break
                    val fileSize = datFile.length()
                    val baseName = datFile.nameWithoutExtension
                    val metaFile = File(cacheDir, "$baseName.meta")
                    if (datFile.delete()) {
                        currentSize -= fileSize
                        metaFile.delete()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao podar cache LRU: ${e.message}")
            }
        }.start()
    }

    fun getCacheSizeBytes(): Long {
        return try {
            cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (e: Exception) {
            0L
        }
    }

    fun getFormattedCacheSize(): String {
        val bytes = getCacheSizeBytes()
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    fun clearCache(): Long {
        val bytesFreed = getCacheSizeBytes()
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile) file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao limpar diretório de cache: ${e.message}", e)
        }
        return bytesFreed
    }
}
