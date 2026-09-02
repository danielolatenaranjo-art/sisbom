package com.sisbom.sisbomcar

import android.app.Application
import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp

class SisBomCarApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Capturador global de excepciones para registrar trazas y proteger contra crashes
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val errorMsg = throwable.message ?: ""
            if (throwable is IllegalStateException && (
                    errorMsg.contains("FirebaseApp was deleted", ignoreCase = true) ||
                    errorMsg.contains("Default FirebaseApp is not initialized", ignoreCase = true)
                )) {
                Log.w("SisBomCarApp", "Ignorando excepción interna de Firebase en '${thread.name}': $errorMsg")
                return@setDefaultUncaughtExceptionHandler
            }

            try {
                Log.e("SisBomCarCrash", "FATAL EXCEPTION in ${thread.name}: ${throwable.message}", throwable)
                val prefs = getSharedPreferences("SisBomCarPrefs", Context.MODE_PRIVATE)
                val stackTrace = Log.getStackTraceString(throwable)
                prefs.edit().putString("last_crash_trace", stackTrace).commit()
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Inicializar Osmdroid de forma segura antes de crear vistas nativas
        try {
            android.preference.PreferenceManager.getDefaultSharedPreferences(this).let { sp ->
                org.osmdroid.config.Configuration.getInstance().load(this, sp)
            }
            org.osmdroid.config.Configuration.getInstance().userAgentValue = packageName
            val osmDir = java.io.File(cacheDir, "osmdroid")
            if (!osmDir.exists()) osmDir.mkdirs()
            org.osmdroid.config.Configuration.getInstance().osmdroidBasePath = osmDir
            org.osmdroid.config.Configuration.getInstance().osmdroidTileCache = java.io.File(osmDir, "tiles")
        } catch (e: Exception) {
            Log.e("SisBomCarApp", "Error initializing Osmdroid: ${e.message}")
        }

        // Inicializar Firebase si ya existe configuración guardada de la licencia
        try {
            val prefs = getSharedPreferences("SisBomCarPrefs", Context.MODE_PRIVATE)
            val fbConfigStr = prefs.getString("saas_firebase_config", null)
            if (!fbConfigStr.isNullOrEmpty()) {
                CarViewModel.initializeDynamicFirebase(this, fbConfigStr)
            } else if (FirebaseApp.getApps(this).isEmpty()) {
                CarViewModel.initializeFallbackFirebase(this)
            }
        } catch (e: Exception) {
            Log.e("SisBomCarApp", "Error initializing FirebaseApp: ${e.message}")
        }
    }
}
