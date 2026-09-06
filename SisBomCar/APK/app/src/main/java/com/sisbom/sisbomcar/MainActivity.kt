package com.sisbom.sisbomcar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    private val viewModel: CarViewModel by viewModels()
    private var isCharging by mutableStateOf(false)

    private val powerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            checkChargingState()
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineLocationGranted && viewModel.selectedUnitId.isNotEmpty()) {
            GpsTrackingService.startService(this)
        }
    }

    companion object {
        private var instanceRef: java.lang.ref.WeakReference<MainActivity>? = null

        fun wakeUpScreenAndStayAwake(context: Context? = null) {
            try {
                instanceRef?.get()?.let { activity ->
                    activity.runOnUiThread {
                        activity.wakeUpScreen()
                        activity.updateScreenAwakeState(true)
                    }
                }
            } catch (_: Exception) {}

            try {
                val ctx = context ?: instanceRef?.get()
                ctx?.let { c ->
                    val intent = Intent(c, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    c.startActivity(intent)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instanceRef = java.lang.ref.WeakReference(this)

        try {
            // Pantalla completa inmersiva
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (_: Exception) {}

        try {
            checkAndRequestPermissions()
        } catch (_: Exception) {}

        try {
            if (viewModel.isLicenseValid && viewModel.selectedUnitId.isNotEmpty()) {
                GpsTrackingService.startService(this)
            }
        } catch (_: Exception) {}

        try {
            checkChargingState()
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            ContextCompat.registerReceiver(
                this,
                powerReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {}

        setContent {
            CarPlayTheme {
                val hasActiveDispatch = viewModel.activeDispatch != null && viewModel.activeDispatch?.operadorFinal.isNullOrEmpty()
                val hasActiveBitacora = viewModel.activeBitacoraTrip != null && viewModel.activeBitacoraTrip?.hora68.isNullOrEmpty() && viewModel.activeBitacoraTrip?.estadoMovil != "en cuartel"
                val isVehicleDispatched = viewModel.currentUnitVehicle?.let { v ->
                    val enServ = v.enServicio.trim()
                    enServ.isNotEmpty() && enServ != "0" && enServ != "0-8" && enServ != "0-9" && enServ != "6-8" && !enServ.equals("false", ignoreCase = true)
                } ?: false

                val hasActiveTrip = hasActiveDispatch || hasActiveBitacora || isVehicleDispatched

                LaunchedEffect(hasActiveTrip) {
                    if (hasActiveTrip) {
                        wakeUpScreen()
                        updateScreenAwakeState(true)
                    } else {
                        updateScreenAwakeState(false)
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    var showSettings by remember { mutableStateOf(false) }
                    val crashPrefs = remember { getSharedPreferences("SisBomCarPrefs", Context.MODE_PRIVATE) }
                    val hasCrashTrace = remember { !crashPrefs.getString("last_crash_trace", "").isNullOrEmpty() }

                    if (!viewModel.isLicenseValid || viewModel.selectedUnitId.isEmpty() || showSettings || hasCrashTrace) {
                        CarPlaySetupWizard(
                            viewModel = viewModel,
                            onSetupCompleted = {
                                // Al completar la configuración, limpiamos el crash trace previo para permitir entrar al dashboard
                                crashPrefs.edit().remove("last_crash_trace").commit()
                                showSettings = false
                            }
                        )
                    } else {
                        CarPlayDashboard(
                            viewModel = viewModel,
                            onOpenSettings = {
                                showSettings = true
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(powerReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun checkChargingState() {
        try {
            val batteryStatus: Intent? = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status: Int = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) {
            isCharging = false
        }
    }

    private fun updateScreenAwakeState(keepAwake: Boolean) {
        runOnUiThread {
            try {
                if (keepAwake) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        setTurnScreenOn(false)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun wakeUpScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val wakeLock = powerManager?.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                android.os.PowerManager.ON_AFTER_RELEASE,
                "SisBomCar:DispatchWakeLock"
            )
            wakeLock?.acquire(30000L)

            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                keyguardManager?.requestDismissKeyguard(this, null)
            }
        } catch (_: Exception) {}
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}
