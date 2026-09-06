package com.sisbom.sisbomtel

import android.app.Application
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.json.JSONObject

class SisBomTelApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("SisBomTelPrefs", Context.MODE_PRIVATE)
        val fbConfigStr = prefs.getString("saas_firebase_config", null)

        if (!fbConfigStr.isNullOrBlank()) {
            initializeDynamicFirebase(this, fbConfigStr)
        }

        val isLicenseValid = prefs.getString("saas_license_key", "")?.isNotEmpty() == true
        val isComandanteAuth = prefs.getString("auth_comandante_id", "")?.isNotEmpty() == true

        if (isLicenseValid && isComandanteAuth) {
            SisBomTelService.startService(this)
        }
    }

    companion object {
        fun initializeDynamicFirebase(context: Context, configJsonStr: String) {
            try {
                if (configJsonStr.isBlank()) return
                val config = JSONObject(configJsonStr)
                val apiKey = config.optString("apiKey")
                val projectId = config.optString("projectId")
                val storageBucket = config.optString("storageBucket")
                val messagingSenderId = config.optString("messagingSenderId")
                val appId = config.optString("appId")

                if (projectId.isBlank() || apiKey.isBlank()) return

                // Check if default app already points to the correct project
                val existingApps = FirebaseApp.getApps(context)
                if (existingApps.isNotEmpty()) {
                    try {
                        val defaultApp = FirebaseApp.getInstance()
                        if (defaultApp.options.projectId == projectId) {
                            return // Already pointing to the correct project
                        }
                        // Delete the current default app so we can reinitialize with the tenant's config
                        defaultApp.delete()
                    } catch (_: Exception) {}
                }

                val options = FirebaseOptions.Builder()
                    .setApiKey(apiKey)
                    .setApplicationId(if (appId.isNotEmpty()) appId else "1:123456789:android:default")
                    .setProjectId(projectId)
                    .setGcmSenderId(messagingSenderId)
                    .setStorageBucket(storageBucket)
                    .build()

                FirebaseApp.initializeApp(context, options)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
