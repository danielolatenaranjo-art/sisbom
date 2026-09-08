package com.sisbom.sisbomcrew

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sisbom.sisbomcrew.ui.CrewDashboardScreen
import com.sisbom.sisbomcrew.ui.LicenseActivationScreen
import com.sisbom.sisbomcrew.ui.theme.BgDark
import com.sisbom.sisbomcrew.ui.theme.SentinelCrewTheme

class MainActivity : ComponentActivity() {

    private val viewModel: CrewViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SentinelCrewTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BgDark
                ) {
                    val licenseConfig by viewModel.licenseConfig.collectAsState()

                    if (licenseConfig.active && licenseConfig.key.isNotEmpty()) {
                        CrewDashboardScreen(viewModel = viewModel)
                    } else {
                        LicenseActivationScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}
