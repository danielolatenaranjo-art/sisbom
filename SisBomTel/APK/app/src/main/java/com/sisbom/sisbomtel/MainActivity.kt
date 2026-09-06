package com.sisbom.sisbomtel

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.sisbom.sisbomtel.ui.DashboardScreen
import com.sisbom.sisbomtel.ui.SetupWizard
import com.sisbom.sisbomtel.ui.TelColors

class MainActivity : ComponentActivity() {

    private val viewModel: TelViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted && viewModel.isLicenseValid && viewModel.isComandanteAuthenticated) {
            SisBomTelService.startService(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}

        checkAndRequestPermissions()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = TelColors.BackgroundDark
                ) {
                    val isEnrolled = viewModel.isLicenseValid && viewModel.isComandanteAuthenticated

                    if (isEnrolled) {
                        DashboardScreen(
                            viewModel = viewModel
                        )
                    } else {
                        SetupWizard(
                            viewModel = viewModel,
                            onSetupCompleted = {
                                SisBomTelService.startService(this@MainActivity)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        // Essential permissions for caller ID (core functionality)
        val essentialPermissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            essentialPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val neededPermissions = essentialPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        } else if (viewModel.isLicenseValid && viewModel.isComandanteAuthenticated) {
            SisBomTelService.startService(this)
        }
    }
}
