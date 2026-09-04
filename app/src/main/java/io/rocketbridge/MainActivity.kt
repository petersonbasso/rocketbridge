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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import io.rocketbridge.data.PreferencesManager
import io.rocketbridge.service.RocketWebSocketService
import io.rocketbridge.theme.RocketBridgeTheme
import io.rocketbridge.ui.setup.ServerSetupScreen
import io.rocketbridge.ui.webview.RocketBridgeWebView

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                if (prefs.hasCredentials) {
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

        checkNotificationPermission()
        checkBatteryOptimization()

        // Se já tem credenciais salvas, garante que o serviço de background está rodando
        if (prefs.hasCredentials && prefs.isServiceEnabled) {
            RocketWebSocketService.start(this)
        }

        setContent {
            RocketBridgeTheme {
                MainAppContent()
            }
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
                            Text(
                                text = cleanDomain(serverUrl),
                                maxLines = 1
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(),
                        actions = {
                            IconButton(onClick = {
                                RocketWebSocketService.reconnect(this@MainActivity)
                                Toast.makeText(this@MainActivity, "Reconectando serviço...", Toast.LENGTH_SHORT).show()
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
