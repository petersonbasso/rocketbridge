package io.rocketbridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import io.rocketbridge.data.PreferencesManager
import io.rocketbridge.service.RocketWebSocketService
import io.rocketbridge.service.ServiceState
import io.rocketbridge.theme.RocketBridgeTheme
import io.rocketbridge.ui.setup.ServerSetupScreen
import io.rocketbridge.ui.webview.RocketBridgeWebView

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager
    private var pendingTargetUrl by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                if (prefs.hasCredentials && RocketWebSocketService.connectionState.value != ServiceState.CONNECTED) {
                    RocketWebSocketService.start(this)
                }
            } else {
                Toast.makeText(this, "Permissão de notificação é necessária para receber mensagens.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = PreferencesManager(this)

        handleIncomingIntent(intent)

        checkNotificationPermission()
        checkBatteryOptimization()

        // Se já tem credenciais salvas e o serviço ainda não está conectado, inicia o serviço de background
        if (prefs.hasCredentials && prefs.isServiceEnabled) {
            if (RocketWebSocketService.connectionState.value != ServiceState.CONNECTED) {
                RocketWebSocketService.start(this)
            }
        }

        setContent {
            RocketBridgeTheme {
                MainAppContent()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val targetUrl = intent?.getStringExtra(RocketWebSocketService.EXTRA_TARGET_URL)
            ?: intent?.getStringExtra("TARGET_URL")
        if (!targetUrl.isNullOrBlank()) {
            pendingTargetUrl = targetUrl
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (ignored: Exception) {}
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainAppContent() {
        var serverUrl by remember { mutableStateOf(prefs.serverUrl) }
        var showMenu by remember { mutableStateOf(false) }
        val serviceState by RocketWebSocketService.connectionState.collectAsStateWithLifecycle()
        var pendingReconnectConfirmation by remember { mutableStateOf(false) }

        LaunchedEffect(serviceState) {
            if (pendingReconnectConfirmation) {
                if (serviceState == ServiceState.CONNECTED) {
                    Toast.makeText(this@MainActivity, "Conectado ao Rocket.Chat com sucesso!", Toast.LENGTH_SHORT).show()
                    pendingReconnectConfirmation = false
                } else if (serviceState == ServiceState.NO_NETWORK) {
                    Toast.makeText(this@MainActivity, "Sem conexão com a internet.", Toast.LENGTH_SHORT).show()
                    pendingReconnectConfirmation = false
                }
            }
        }

        if (serverUrl.isBlank()) {
            ServerSetupScreen(
                currentUrl = serverUrl,
                onServerConfigured = { newUrl ->
                    prefs.serverUrl = newUrl
                    serverUrl = newUrl
                }
            )
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize()
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Tarja fina condicional no topo (exibida apenas quando não conectado)
                        AnimatedVisibility(
                            visible = serviceState != ServiceState.CONNECTED,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            ConnectionStatusBanner(
                                state = serviceState,
                                onReconnect = {
                                    pendingReconnectConfirmation = true
                                    RocketWebSocketService.reconnect(this@MainActivity)
                                    Toast.makeText(this@MainActivity, "Reconectando ao servidor...", Toast.LENGTH_SHORT).show()
                                },
                                onOpenMenu = { showMenu = true }
                            )
                        }

                        // WebView em tela cheia ocupando 100% do espaço restante
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            RocketBridgeWebView(
                                url = serverUrl,
                                targetUrl = pendingTargetUrl,
                                onTargetUrlConsumed = {
                                    pendingTargetUrl = null
                                },
                                onSessionCaptured = { token, userId ->
                                    if (prefs.authToken != token || prefs.userId != userId) {
                                        prefs.authToken = token
                                        prefs.userId = userId
                                        RocketWebSocketService.start(this@MainActivity)
                                    }
                                }
                            )
                        }
                    }

                    // Botão flutuante minimalista para configurações (arrastável verticalmente)
                    FloatingSettingsButton(
                        serverUrl = serverUrl,
                        serviceState = serviceState,
                        showMenu = showMenu,
                        onToggleMenu = { showMenu = it },
                        onReconnect = {
                            pendingReconnectConfirmation = true
                            RocketWebSocketService.reconnect(this@MainActivity)
                            Toast.makeText(this@MainActivity, "Reconectando ao servidor...", Toast.LENGTH_SHORT).show()
                        },
                        onSwitchServer = {
                            RocketWebSocketService.stop(this@MainActivity)
                            prefs.clearAll()
                            serverUrl = ""
                        },
                        onLogout = {
                            RocketWebSocketService.stop(this@MainActivity)
                            prefs.clearSession()
                            Toast.makeText(this@MainActivity, "Sessão reiniciada", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun ConnectionStatusBanner(
        state: ServiceState,
        onReconnect: () -> Unit,
        onOpenMenu: () -> Unit
    ) {
        val (bgColor, contentColor, statusText) = when (state) {
            ServiceState.CONNECTING -> Triple(Color(0xFFFFF3E0), Color(0xFFE65100), "Conectando ao Rocket.Chat...")
            ServiceState.AUTHENTICATING -> Triple(Color(0xFFFFF3E0), Color(0xFFE65100), "Autenticando sessão...")
            ServiceState.RECONNECTING -> Triple(Color(0xFFFFF3E0), Color(0xFFE65100), "Reconectando ao servidor...")
            ServiceState.WAITING_FOR_LOGIN -> Triple(Color(0xFFF5F5F5), Color(0xFF616161), "Aguardando login no aplicativo")
            ServiceState.NO_NETWORK -> Triple(Color(0xFFFFEBEE), Color(0xFFC62828), "Sem conexão com a internet")
            ServiceState.DISCONNECTED -> Triple(Color(0xFFF5F5F5), Color(0xFF616161), "Serviço desconectado")
            ServiceState.CONNECTED -> Triple(Color.Transparent, Color.Transparent, "")
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isBusy = state == ServiceState.CONNECTING ||
                    state == ServiceState.AUTHENTICATING ||
                    state == ServiceState.RECONNECTING

            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = contentColor,
                    strokeWidth = 2.dp
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(contentColor)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            IconButton(
                onClick = onReconnect,
                modifier = Modifier.size(28.dp)
            ) {
                Text("🔄", style = MaterialTheme.typography.labelSmall)
            }

            IconButton(
                onClick = onOpenMenu,
                modifier = Modifier.size(28.dp)
            ) {
                Text("⚙️", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    @Composable
    private fun BoxScope.FloatingSettingsButton(
        serverUrl: String,
        serviceState: ServiceState,
        showMenu: Boolean,
        onToggleMenu: (Boolean) -> Unit,
        onReconnect: () -> Unit,
        onSwitchServer: () -> Unit,
        onLogout: () -> Unit
    ) {
        val density = LocalDensity.current
        // A barra de conversa do Rocket.Chat possui ~56dp de altura.
        // 68dp posiciona o botão logo abaixo da barra (área do quadrado azul).
        val defaultOffsetPx = with(density) { 68.dp.toPx() }
        val minOffsetPx = with(density) { 58.dp.toPx() } // Impede de sobrepor os botões de ligação/kebab do Rocket.Chat
        val maxOffsetPx = with(density) { 650.dp.toPx() }

        val savedY = prefs.floatingButtonY
        var offsetY by remember {
            mutableFloatStateOf(if (savedY > 0f) savedY.coerceIn(minOffsetPx, maxOffsetPx) else defaultOffsetPx)
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(0, offsetY.roundToInt()) }
                .align(Alignment.TopEnd)
                .padding(end = 8.dp)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        val newY = (offsetY + delta).coerceIn(minOffsetPx, maxOffsetPx)
                        offsetY = newY
                        prefs.floatingButtonY = newY
                    }
                )
        ) {
            Surface(
                onClick = { onToggleMenu(true) },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                tonalElevation = 2.dp,
                shadowElevation = 3.dp,
                modifier = Modifier.size(34.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text("⚙️", style = MaterialTheme.typography.labelMedium)
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { onToggleMenu(false) }
            ) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = cleanDomain(serverUrl),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val (dotColor, label) = when (serviceState) {
                                    ServiceState.CONNECTED -> Pair(Color(0xFF4CAF50), "Conectado")
                                    ServiceState.CONNECTING -> Pair(Color(0xFFFFB300), "Conectando...")
                                    ServiceState.AUTHENTICATING -> Pair(Color(0xFFFF9800), "Autenticando...")
                                    ServiceState.RECONNECTING -> Pair(Color(0xFFFFB300), "Reconectando...")
                                    ServiceState.WAITING_FOR_LOGIN -> Pair(Color(0xFF9E9E9E), "Aguardando login")
                                    ServiceState.NO_NETWORK -> Pair(Color(0xFFF44336), "Sem internet")
                                    ServiceState.DISCONNECTED -> Pair(Color(0xFF9E9E9E), "Desconectado")
                                }
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = {},
                    enabled = false
                )

                HorizontalDivider()

                DropdownMenuItem(
                    text = { Text("🔄 Forçar Reconexão") },
                    onClick = {
                        onToggleMenu(false)
                        onReconnect()
                    }
                )

                DropdownMenuItem(
                    text = { Text("🌐 Trocar de Servidor") },
                    onClick = {
                        onToggleMenu(false)
                        onSwitchServer()
                    }
                )

                DropdownMenuItem(
                    text = { Text("🚪 Limpar Sessão / Logout") },
                    onClick = {
                        onToggleMenu(false)
                        onLogout()
                    }
                )
            }
        }
    }

    private fun cleanDomain(url: String): String {
        return url.replace("https://", "")
            .replace("http://", "")
            .trimEnd('/')
    }
}
