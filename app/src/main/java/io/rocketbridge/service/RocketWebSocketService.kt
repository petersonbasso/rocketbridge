package io.rocketbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.rocketbridge.MainActivity
import io.rocketbridge.R
import io.rocketbridge.data.PreferencesManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

class RocketWebSocketService : Service() {

    companion object {
        private const val TAG = "RocketWebSocketService"
        const val CHANNEL_SERVICE = "rocketbridge_service_channel"
        const val CHANNEL_MESSAGES = "rocketbridge_messages_channel"
        private const val NOTIFICATION_ID_SERVICE = 1001

        const val ACTION_START = "io.rocketbridge.START"
        const val ACTION_STOP = "io.rocketbridge.STOP"
        const val ACTION_RECONNECT = "io.rocketbridge.RECONNECT"

        fun start(context: Context) {
            val intent = Intent(context, RocketWebSocketService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RocketWebSocketService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun reconnect(context: Context) {
            val intent = Intent(context, RocketWebSocketService::class.java).apply {
                action = ACTION_RECONNECT
            }
            context.startService(intent)
        }
    }

    private lateinit var prefs: PreferencesManager
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isLoggedIn = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0
    private var isReconnecting = false

    private val client by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private var connectivityManager: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Rede disponível. Verificando conexão WebSocket...")
            if (!isConnected && !isReconnecting) {
                mainHandler.post { connectWebSocket() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(this)
        createNotificationChannels()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Parando serviço RocketWebSocketService")
                disconnectWebSocket()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                reconnectAttempt = 0
                disconnectWebSocket()
                connectWebSocket()
            }
            else -> {
                startForeground(NOTIFICATION_ID_SERVICE, createServiceNotification("Iniciando conexão com Rocket.Chat..."))
                connectWebSocket()
            }
        }
        return START_STICKY
    }

    private fun connectWebSocket() {
        if (isConnected || isReconnecting) return

        val serverUrl = prefs.serverUrl
        val token = prefs.authToken
        val userId = prefs.userId

        if (serverUrl.isBlank() || token.isNullOrBlank() || userId.isNullOrBlank()) {
            Log.w(TAG, "Configurações incompletas. Aguardando login na tela do app.")
            updateServiceNotification("Aguardando login no aplicativo...")
            return
        }

        isReconnecting = true
        val wsUrl = buildWebSocketUrl(serverUrl)
        Log.i(TAG, "Conectando ao WebSocket: $wsUrl")
        updateServiceNotification("Conectando a $serverUrl...")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket conectado! Enviando DDP connect handshake...")
                isConnected = true
                isReconnecting = false
                reconnectAttempt = 0

                val connectMsg = JSONObject().apply {
                    put("msg", "connect")
                    put("version", "1")
                    put("support", JSONArray().put("1"))
                }
                webSocket.send(connectMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleDdpMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket fechando: $code / $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket fechado: $code / $reason")
                isConnected = false
                isLoggedIn = false
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Falha na conexão WebSocket: ${t.message}", t)
                isConnected = false
                isLoggedIn = false
                scheduleReconnect()
            }
        })
    }

    private fun handleDdpMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            val msgType = json.optString("msg")

            when (msgType) {
                "connected" -> {
                    Log.i(TAG, "DDP Conectado. Autenticando com token...")
                    sendDdpLogin()
                }
                "ping" -> {
                    // Responde o ping do servidor imediatamente para manter o canal ativo
                    webSocket?.send(JSONObject().put("msg", "pong").toString())
                }
                "result" -> {
                    val id = json.optString("id")
                    if (id == "login_cmd") {
                        if (json.has("error")) {
                            val err = json.optJSONObject("error")?.optString("message")
                            Log.e(TAG, "Erro de autenticação DDP: $err")
                            updateServiceNotification("Erro de autenticação. Abra o app novamente.")
                        } else {
                            Log.i(TAG, "Login DDP bem-sucedido! Inscrevendo no stream de notificações...")
                            isLoggedIn = true
                            updateServiceNotification("Conectado e monitorando mensagens")
                            subscribeToNotifications()
                        }
                    }
                }
                "changed" -> {
                    val collection = json.optString("collection")
                    if (collection == "stream-notify-user") {
                        val fields = json.optJSONObject("fields")
                        val args = fields?.optJSONArray("args")
                        if (args != null && args.length() > 0) {
                            val payload = args.optJSONObject(0)
                            if (payload != null) {
                                onIncomingNotification(payload)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar mensagem DDP: ${e.message}", e)
        }
    }

    private fun sendDdpLogin() {
        val token = prefs.authToken ?: return
        val loginMsg = JSONObject().apply {
            put("msg", "method")
            put("method", "login")
            put("id", "login_cmd")
            put("params", JSONArray().put(JSONObject().apply {
                put("resume", token)
            }))
        }
        webSocket?.send(loginMsg.toString())
    }

    private fun subscribeToNotifications() {
        val userId = prefs.userId ?: return
        val subMsg = JSONObject().apply {
            put("msg", "sub")
            put("id", "sub_notifications")
            put("name", "stream-notify-user")
            put("params", JSONArray().apply {
                put("$userId/notification")
                put(false)
            })
        }
        webSocket?.send(subMsg.toString())
        Log.i(TAG, "Subscrição de notificações enviada para $userId/notification")
    }

    private fun onIncomingNotification(payload: JSONObject) {
        val title = payload.optString("title", "Nova mensagem no Rocket.Chat")
        val text = payload.optString("text", "Toque para abrir a conversa")
        val innerPayload = payload.optJSONObject("payload")
        val rid = innerPayload?.optString("rid", "") ?: ""

        Log.i(TAG, "NOVA NOTIFICAÇÃO RECEBIDA: $title - $text (rid: $rid)")

        // Breve wakelock para garantir que a notificação apareça com a tela apagada
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RocketBridge:NotifyWakeLock")
        wakeLock?.acquire(2000)

        showNativeMessageNotification(title, text, rid)
    }

    private fun showNativeMessageNotification(title: String, text: String, rid: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("ROOM_ID", rid)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            Random.nextInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.rocket_cyan))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permissão de notificação ausente: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        if (!prefs.isServiceEnabled || prefs.authToken.isNullOrBlank()) return

        isReconnecting = false
        reconnectAttempt++
        // Backoff exponencial com jitter (entre 2s e 30s)
        val baseDelay = min(30, 2 shl (min(reconnectAttempt, 4)))
        val jitter = Random.nextInt(1, 4)
        val delaySeconds = baseDelay + jitter

        Log.w(TAG, "Reconectando em $delaySeconds segundos (tentativa $reconnectAttempt)...")
        updateServiceNotification("Reconectando em ${delaySeconds}s...")

        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            connectWebSocket()
        }, delaySeconds * 1000L)
    }

    private fun disconnectWebSocket() {
        try {
            webSocket?.close(1000, "Service stopped")
        } catch (ignored: Exception) {}
        webSocket = null
        isConnected = false
        isLoggedIn = false
        isReconnecting = false
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun buildWebSocketUrl(serverUrl: String): String {
        var base = serverUrl.trim().trimEnd('/')
        base = if (base.startsWith("https://", ignoreCase = true)) {
            base.replaceFirst("https://", "wss://", ignoreCase = true)
        } else if (base.startsWith("http://", ignoreCase = true)) {
            base.replaceFirst("http://", "ws://", ignoreCase = true)
        } else {
            "wss://$base"
        }
        return "$base/websocket"
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // Canal do serviço (Silencioso/Discreto)
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Status do Serviço RocketBridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Exibe o status da conexão em segundo plano com o Rocket.Chat"
                setShowBadge(false)
            }

            // Canal de mensagens (Alta prioridade / Som / Vibração)
            val messagesChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Mensagens do Rocket.Chat",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificações de mensagens e menções recebidas"
                enableVibration(true)
                setShowBadge(true)
            }

            nm.createNotificationChannel(serviceChannel)
            nm.createNotificationChannel(messagesChannel)
        }
    }

    private fun createServiceNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.rocket_cyan))
            .setContentTitle("RocketBridge")
            .setContentText(statusText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateServiceNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID_SERVICE, createServiceNotification(statusText))
    }

    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao registrar NetworkCallback: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectWebSocket()
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (ignored: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
