package com.evolute.telemetrypro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.evolute.telemetrypro.data.KeystoreTokenStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val hasSession = runCatching { KeystoreTokenStore(context).get() != null }.getOrDefault(false)
        if (hasSession) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CollectorService::class.java))
            }
        }
    }
}
