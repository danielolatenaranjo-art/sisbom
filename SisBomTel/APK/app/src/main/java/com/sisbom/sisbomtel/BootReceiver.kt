package com.sisbom.sisbomtel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = context.getSharedPreferences("SisBomTelPrefs", Context.MODE_PRIVATE)
            val isLicenseValid = prefs.getString("saas_license_key", "")?.isNotEmpty() == true
            val isComandanteAuth = prefs.getString("auth_comandante_id", "")?.isNotEmpty() == true

            if (isLicenseValid && isComandanteAuth) {
                val serviceIntent = Intent(context, SisBomTelService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (_: Exception) {}
            }
        }
    }
}
