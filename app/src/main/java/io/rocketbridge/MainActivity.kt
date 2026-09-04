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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = cleanDomain(serverUrl),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val (statusColor, statusText) = when (serviceState) {
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
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(statusColor)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(),
                        actions = {
                            IconButton(onClick = {
                                pendingReconnectConfirmation = true
                                RocketWebSocketService.reconnect(this@MainActivity)
                                Toast.makeText(this@MainActivity, "Reconectando ao servidor...", Toast.LENGTH_SHORT).show()
                            }) {
                                Text("🔄", style = MaterialTheme.typography.titleMedium)
                            }

                            IconButton(onClick = { showMenu = true }) {
                                Text("⚙️", style = MaterialTheme.typography.titleMedium)
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Trocar de Servidor") },
                                    onClick = {
                                        showMenu = false
                                        RocketWebSocketService.stop(this@MainActivity)
                                        prefs.clearAll()
                                        serverUrl = ""
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Limpar Sessão / Logout") },
                                    onClick = {
                                        showMenu = false
                                        RocketWebSocketService.stop(this@MainActivity)
                                        prefs.clearSession()
                                        Toast.makeText(this@MainActivity, "Sessão reiniciada", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
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
        }
    }

    private fun cleanDomain(url: String): String {
        return url.replace("https://", "")
            .replace("http://", "")
            .trimEnd('/')
    }
}
