package io.rocketbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.rocketbridge.data.PreferencesManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (Intent.ACTION_BOOT_COMPLETED == action || Intent.ACTION_MY_PACKAGE_REPLACED == action) {
            val prefs = PreferencesManager(context)
            if (prefs.isServiceEnabled && prefs.hasCredentials) {
                Log.i("BootReceiver", "Inicializando RocketWebSocketService após inicialização do sistema.")
                RocketWebSocketService.start(context)
            }
        }
    }
}
