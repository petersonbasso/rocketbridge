package io.rocketbridge.ui.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

class RocketBridgeJsInterface(
    private val onSessionDetected: (token: String, userId: String) -> Unit
) {
    @JavascriptInterface
    fun onSession(token: String?, userId: String?) {
        if (!token.isNullOrBlank() && !userId.isNullOrBlank()) {
            Log.d("RocketBridgeJsInterface", "Sessão capturada do WebView: userId=$userId")
            onSessionDetected(token, userId)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RocketBridgeWebView(
    url: String,
    targetUrl: String? = null,
    onTargetUrlConsumed: () -> Unit = {},
    onSessionCaptured: (token: String, userId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    var initialLoadedUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(targetUrl) {
        val dest = targetUrl
        if (!dest.isNullOrBlank()) {
            if (dest != initialLoadedUrl) {
                webViewInstance?.let { view ->
                    navigateTargetUrl(view, dest)
                }
            }
            onTargetUrlConsumed()
        }
    }

    BackHandler(enabled = canGoBack) {
        webViewInstance?.let {
            if (it.canGoBack()) {
                it.goBack()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        builtInZoomControls = false
                        displayZoomControls = false
                        userAgentString = settings.userAgentString + " RocketBridge/1.0"
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }
                    }

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    addJavascriptInterface(
                        RocketBridgeJsInterface(onSessionCaptured),
                        "RocketBridgeNative"
                    )

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress / 100f
                            isLoading = newProgress < 100
                        }

                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            Log.v("WebViewConsole", "${consoleMessage?.message()}")
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val requestUrl = request?.url?.toString() ?: return false
                            if (requestUrl.startsWith("http://") || requestUrl.startsWith("https://")) {
                                return false
                            }
                            // Links externos (mailto, tel, etc.)
                            return try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(requestUrl))
                                ctx.startActivity(intent)
                                true
                            } catch (e: Exception) {
                                false
                            }
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            canGoBack = view?.canGoBack() == true
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            canGoBack = view?.canGoBack() == true

                            // Injeta script monitorador de sessão Meteor
                            val injectJs = """
                                (function() {
                                    function checkMeteorSession() {
                                        try {
                                            var token = localStorage.getItem('Meteor.loginToken');
                                            var userId = localStorage.getItem('Meteor.userId');
                                            if (token && userId && window.RocketBridgeNative) {
                                                window.RocketBridgeNative.onSession(token, userId);
                                            }
                                        } catch(e) {}
                                    }
                                    if (!window.__rocketBridgeInterval) {
                                        window.__rocketBridgeInterval = setInterval(checkMeteorSession, 2000);
                                    }
                                    checkMeteorSession();
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(injectJs, null)
                        }
                    }

                    val startUrl = if (!targetUrl.isNullOrBlank()) targetUrl else url
                    initialLoadedUrl = startUrl
                    loadUrl(startUrl)
                    webViewInstance = this
                }
            },
            update = { view ->
                // Se a URL base do servidor mudou
                val baseUrl = url.trimEnd('/')
                if (baseUrl.isNotBlank() && view.url?.startsWith(baseUrl) != true) {
                    view.loadUrl(baseUrl)
                }
            }
        )

        if (isLoading && progress < 0.85f) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

private fun navigateTargetUrl(view: WebView, targetUrl: String) {
    val currentUrl = view.url
    if (currentUrl.isNullOrBlank() || !currentUrl.startsWith("http")) {
        view.loadUrl(targetUrl)
        return
    }

    val safeUrl = targetUrl.replace("\\", "\\\\").replace("'", "\\'")

    val js = """
        (function() {
            var target = '$safeUrl';
            try {
                var urlObj = new URL(target);
                var pathAndQuery = urlObj.pathname + urlObj.search + urlObj.hash;

                // 1. Tenta FlowRouter do Meteor / Rocket.Chat
                if (window.FlowRouter && typeof window.FlowRouter.go === 'function') {
                    window.FlowRouter.go(pathAndQuery);
                    return;
                }

                // 2. Tenta History API com PopStateEvent (React Router)
                if (window.history && typeof window.history.pushState === 'function') {
                    window.history.pushState({}, '', pathAndQuery);
                    window.dispatchEvent(new PopStateEvent('popstate'));
                }
            } catch(e) {}

            // 3. Fallback: navegação direta via window.location
            setTimeout(function() {
                if (window.location.href !== target) {
                    window.location.href = target;
                }
            }, 300);
        })();
    """.trimIndent()
    view.evaluateJavascript(js, null)
}
