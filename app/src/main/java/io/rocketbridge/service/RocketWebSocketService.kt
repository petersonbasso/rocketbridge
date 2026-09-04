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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

enum class ServiceState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    RECONNECTING,
    WAITING_FOR_LOGIN,
    NO_NETWORK
}

class RocketWebSocketService : Service() {

    companion object {
        private const val TAG = "RocketWebSocketService"
        const val CHANNEL_SERVICE = "rocketbridge_service_channel"
        const val CHANNEL_MESSAGES = "rocketbridge_messages_channel"
        const val CHANNEL_ALERTS = "rocketbridge_alerts_channel"
        private const val NOTIFICATION_ID_SERVICE = 1001
        private const val NOTIFICATION_ID_ALERT = 1002

        const val ACTION_START = "io.rocketbridge.START"
        const val ACTION_STOP = "io.rocketbridge.STOP"
        const val ACTION_RECONNECT = "io.rocketbridge.RECONNECT"

        const val EXTRA_TARGET_URL = "io.rocketbridge.TARGET_URL"
        const val EXTRA_ROOM_ID = "io.rocketbridge.ROOM_ID"
        const val EXTRA_MESSAGE_ID = "io.rocketbridge.MESSAGE_ID"

        private val _connectionState = MutableStateFlow(ServiceState.DISCONNECTED)
        val connectionState = _connectionState.asStateFlow()

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
    private var connectedToken: String? = null
    private var connectedUserId: String? = null
    private var currentState: ServiceState = ServiceState.DISCONNECTED

    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0
    private var isReconnecting = false

    private val connectionTimeoutRunnable = Runnable {
        if (!isLoggedIn) {
            Log.w(TAG, "Timeout na conexão/autenticação WebSocket/DDP (15s excedidos). Reconectando...")
            disconnectWebSocket()
            scheduleReconnect()
        }
    }

    private fun armConnectionTimeout() {
        disarmConnectionTimeout()
        mainHandler.postDelayed(connectionTimeoutRunnable, 15000L)
    }

    private fun disarmConnectionTimeout() {
        mainHandler.removeCallbacks(connectionTimeoutRunnable)
    }

    private fun updateState(newState: ServiceState, extraInfo: String? = null) {
        currentState = newState
        _connectionState.value = newState
        updateServiceNotification(newState, extraInfo)
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(60, TimeUnit.SECONDS) // Ping de 60s otimizado para economia máxima de bateria
            .retryOnConnectionFailure(true)
            .build()
    }

    private var connectivityManager: ConnectivityManager? = null
    private var lastNetwork: Network? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Rede disponível. Verificando conexão WebSocket...")
            val isDifferentNetwork = lastNetwork != null && lastNetwork != network
            lastNetwork = network

            if (!isConnected && !isReconnecting) {
                mainHandler.post { connectWebSocket() }
            } else if (isDifferentNetwork && isConnected) {
                // Alternância de rede (ex: Wi-Fi <-> Dados móveis) - reconecta na nova interface ativa
                Log.i(TAG, "Troca de interface de rede detectada. Reconectando na nova rede...")
                mainHandler.post {
                    disconnectWebSocket()
                    connectWebSocket()
                }
            }
        }

        override fun onLost(network: Network) {
            Log.w(TAG, "Conexão de rede perdida.")
            disarmConnectionTimeout()
            mainHandler.post {
                isConnected = false
                isLoggedIn = false
                isReconnecting = false
                updateState(ServiceState.NO_NETWORK)
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
                updateState(ServiceState.DISCONNECTED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                Log.d(TAG, "Solicitação de reconexão forçada")
                reconnectAttempt = 0
                disconnectWebSocket()
                connectWebSocket()
            }
            else -> {
                val currentToken = prefs.authToken
                val currentUserId = prefs.userId

                // Se já estiver conectado com sucesso e as credenciais não mudaram, mantém o status CONECTADO
                if (isConnected && isLoggedIn && currentState == ServiceState.CONNECTED) {
                    if (connectedToken == currentToken && connectedUserId == currentUserId) {
                        Log.d(TAG, "Serviço já conectado e sincronizado com credenciais ativas. Mantendo status conectado.")
                        startForeground(NOTIFICATION_ID_SERVICE, createServiceNotification(ServiceState.CONNECTED))
                        return START_STICKY
                    }
                }

                // Se as credenciais mudaram enquanto conectado, desconecta para autenticar com novo token
                if (isConnected && (connectedToken != currentToken || connectedUserId != currentUserId)) {
                    Log.i(TAG, "Credenciais atualizadas detectadas. Reiniciando conexão com novo token...")
                    disconnectWebSocket()
                }

                val initialStatus = if (!prefs.hasCredentials) {
                    ServiceState.WAITING_FOR_LOGIN
                } else {
                    ServiceState.CONNECTING
                }
                startForeground(NOTIFICATION_ID_SERVICE, createServiceNotification(initialStatus))
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
            updateState(ServiceState.WAITING_FOR_LOGIN)
            return
        }

        isReconnecting = true
        val domain = cleanDomainForDisplay(serverUrl)
        updateState(ServiceState.CONNECTING, "Servidor: $domain")

        val wsUrl = buildWebSocketUrl(serverUrl)
        Log.i(TAG, "Conectando ao WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        armConnectionTimeout()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket conectado! Enviando DDP connect handshake...")
                isConnected = true
                isReconnecting = false
                reconnectAttempt = 0

                mainHandler.post {
                    updateState(ServiceState.AUTHENTICATING)
                }

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
                disarmConnectionTimeout()
                isConnected = false
                isLoggedIn = false
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Falha na conexão WebSocket: ${t.message}", t)
                disarmConnectionTimeout()
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
                "failed" -> {
                    Log.e(TAG, "DDP Falhou (versão não suportada pelo servidor)")
                    disarmConnectionTimeout()
                    isConnected = false
                    isLoggedIn = false
                    scheduleReconnect()
                }
                "ping" -> {
                    // Responde o ping do servidor imediatamente para manter o canal ativo
                    webSocket?.send(JSONObject().put("msg", "pong").toString())
                }
                "result" -> {
                    val id = json.optString("id")
                    if (id == "login_cmd") {
                        disarmConnectionTimeout()
                        if (json.has("error")) {
                            val err = json.optJSONObject("error")?.optString("message")
                            Log.e(TAG, "Erro de autenticação DDP: $err")
                            isLoggedIn = false
                            showConnectionAlertNotification(prefs.serverUrl, "Sessão expirada ou credenciais inválidas. Abra o aplicativo para verificar.")
                            mainHandler.post {
                                updateState(ServiceState.WAITING_FOR_LOGIN, "Sessão expirada. Faça login novamente.")
                            }
                        } else {
                            Log.i(TAG, "Login DDP bem-sucedido! Inscrevendo no stream de notificações...")
                            isLoggedIn = true
                            reconnectAttempt = 0
                            connectedToken = prefs.authToken
                            connectedUserId = prefs.userId
                            dismissConnectionAlertNotification()
                            mainHandler.post {
                                updateState(ServiceState.CONNECTED)
                            }
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
        val messageId = innerPayload?.optString("_id")?.ifBlank { null }
            ?: innerPayload?.optJSONObject("message")?.optString("_id") ?: ""

        val targetUrl = buildTargetUrl(prefs.serverUrl, innerPayload, rid)

        Log.i(TAG, "NOVA NOTIFICAÇÃO RECEBIDA: $title - $text (rid: $rid, targetUrl: $targetUrl)")

        // Breve wakelock para garantir que a notificação apareça com a tela apagada
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RocketBridge:NotifyWakeLock")
        wakeLock?.acquire(2000)

        showNativeMessageNotification(title, text, targetUrl, rid, messageId)
    }

    private fun buildTargetUrl(serverUrl: String, innerPayload: JSONObject?, fallbackRid: String): String {
        val baseUrl = serverUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) return ""
        if (innerPayload == null) {
            return if (fallbackRid.isNotBlank()) "$baseUrl/channel/$fallbackRid" else baseUrl
        }

        val type = innerPayload.optString("type").ifBlank { innerPayload.optString("t") }
        val name = innerPayload.optString("name")
        val senderUsername = innerPayload.optJSONObject("sender")?.optString("username")
        val messageId = innerPayload.optString("_id").ifBlank {
            innerPayload.optJSONObject("message")?.optString("_id") ?: ""
        }
        val rid = innerPayload.optString("rid").ifBlank { fallbackRid }

        val path = when (type) {
            "d" -> {
                val target = senderUsername?.ifBlank { null } ?: rid
                if (target.isNotBlank()) "direct/$target" else null
            }
            "c" -> {
                val target = name.ifBlank { rid }
                if (target.isNotBlank()) "channel/$target" else null
            }
            "p" -> {
                val target = name.ifBlank { rid }
                if (target.isNotBlank()) "group/$target" else null
            }
            "l" -> {
                if (rid.isNotBlank()) "live/$rid" else null
            }
            else -> {
                when {
                    name.isNotBlank() -> "channel/$name"
                    rid.isNotBlank() -> "channel/$rid"
                    else -> null
                }
            }
        }

        return if (path != null) {
            if (messageId.isNotBlank()) {
                "$baseUrl/$path?msg=$messageId"
            } else {
                "$baseUrl/$path"
            }
        } else {
            baseUrl
        }
    }

    private fun showNativeMessageNotification(
        title: String,
        text: String,
        targetUrl: String,
        rid: String,
        messageId: String
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TARGET_URL, targetUrl)
            putExtra(EXTRA_ROOM_ID, rid)
            putExtra(EXTRA_MESSAGE_ID, messageId)
            putExtra("TARGET_URL", targetUrl)
            putExtra("ROOM_ID", rid)
            putExtra("MESSAGE_ID", messageId)
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
        if (!prefs.isServiceEnabled || prefs.authToken.isNullOrBlank()) {
            mainHandler.post {
                updateState(ServiceState.WAITING_FOR_LOGIN)
            }
            return
        }

        isReconnecting = false
        reconnectAttempt++

        // Notifica o usuário com um alerta caso haja falha persistente de conexão (3 ou mais tentativas)
        if (reconnectAttempt >= 3) {
            showConnectionAlertNotification(
                prefs.serverUrl,
                "Não foi possível conectar ao servidor após $reconnectAttempt tentativas. Toque para verificar."
            )
        }

        // Backoff exponencial econômico para bateria: 5s, 15s, 30s, até máx 60s
        val baseDelay = when {
            reconnectAttempt <= 1 -> 5
            reconnectAttempt == 2 -> 15
            reconnectAttempt == 3 -> 30
            else -> 60
        }
        val jitter = Random.nextInt(1, 4)
        val delaySeconds = baseDelay + jitter

        Log.w(TAG, "Reconectando em $delaySeconds segundos (tentativa $reconnectAttempt)...")
        mainHandler.post {
            updateState(
                ServiceState.RECONNECTING,
                "Aguardando reconexão (${delaySeconds}s)"
            )
        }

        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            connectWebSocket()
        }, delaySeconds * 1000L)
    }

    private fun disconnectWebSocket() {
        disarmConnectionTimeout()
        try {
            webSocket?.close(1000, "Service stopped or reconnecting")
        } catch (ignored: Exception) {}
        webSocket = null
        isConnected = false
        isLoggedIn = false
        isReconnecting = false
        connectedToken = null
        connectedUserId = null
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

            // Canal do serviço (Silencioso/Discreto em segundo plano)
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Status do Serviço RocketBridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Exibe o status discreto da sincronização em segundo plano"
                setShowBadge(false)
            }

            // Canal de mensagens (Alta prioridade / Som / Vibração / Banners)
            val messagesChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Mensagens do Rocket.Chat",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificações de mensagens e menções recebidas em tempo real"
                enableVibration(true)
                setShowBadge(true)
            }

            // Canal de alerta de conexão (Apenas se o serviço perder contato persistente com o servidor)
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Alertas de Conexão do Servidor",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos quando há perda prolongada de conexão com o Rocket.Chat"
                enableVibration(true)
                setShowBadge(true)
            }

            nm.createNotificationChannel(serviceChannel)
            nm.createNotificationChannel(messagesChannel)
            nm.createNotificationChannel(alertsChannel)
        }
    }

    private fun createServiceNotification(state: ServiceState, extraInfo: String? = null): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (statusText, subText) = when (state) {
            ServiceState.CONNECTED -> {
                Pair("Conectado ao Rocket.Chat", "Sincronização ativa e monitorando mensagens")
            }
            ServiceState.CONNECTING -> {
                Pair("Conectando ao Rocket.Chat...", extraInfo ?: "Estabelecendo conexão segura")
            }
            ServiceState.AUTHENTICATING -> {
                Pair("Autenticando sessão...", "Validando credenciais do usuário")
            }
            ServiceState.RECONNECTING -> {
                Pair(extraInfo ?: "Aguardando reconexão...", "A conexão será restaurada em instantes")
            }
            ServiceState.WAITING_FOR_LOGIN -> {
                Pair("Aguardando login no aplicativo", extraInfo ?: "Acesse sua conta para sincronizar")
            }
            ServiceState.NO_NETWORK -> {
                Pair("Sem conexão com a internet", "Aguardando rede para restabelecer sincronização")
            }
            ServiceState.DISCONNECTED -> {
                Pair("Serviço desconectado", "")
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.rocket_cyan))
            .setContentTitle("RocketBridge")
            .setContentText(statusText)
            .setOngoing(state != ServiceState.DISCONNECTED)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        }

        if (subText.isNotBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText("$statusText\n$subText"))
        }

        return builder.build()
    }

    private fun showConnectionAlertNotification(serverUrl: String, detailMessage: String) {
        val domain = cleanDomainForDisplay(serverUrl)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.rocket_cyan))
            .setContentTitle("Sem conexão com o Rocket.Chat")
            .setContentText(detailMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$detailMessage\nServidor: $domain"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID_ALERT, notification)
    }

    private fun dismissConnectionAlertNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID_ALERT)
    }

    private fun updateServiceNotification(state: ServiceState, extraInfo: String? = null) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID_SERVICE, createServiceNotification(state, extraInfo))
    }

    private fun cleanDomainForDisplay(url: String): String {
        return url.replace("https://", "")
            .replace("http://", "")
            .trimEnd('/')
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
        disarmConnectionTimeout()
        dismissConnectionAlertNotification()
        disconnectWebSocket()
        _connectionState.value = ServiceState.DISCONNECTED
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (ignored: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
