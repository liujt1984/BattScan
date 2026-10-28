package com.battscan.leadacid.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.battscan.leadacid.monitor.MonitorForegroundService
import com.battscan.leadacid.ui.screens.CalibrationScreen
import com.battscan.leadacid.ui.screens.DashboardScreen
import com.battscan.leadacid.ui.screens.RawDebugScreen
import com.battscan.leadacid.ui.screens.ReplayScreen
import com.battscan.leadacid.ui.screens.SessionsScreen
import com.battscan.leadacid.ui.screens.SettingsScreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface

object Routes {
    const val DASHBOARD = "dashboard"
    const val SESSIONS = "sessions"
    const val REPLAY = "replay/{sessionId}"
    const val CALIBRATION = "calibration"
    const val SETTINGS = "settings"
    const val RAW = "raw"

    fun replay(sessionId: Long) = "replay/$sessionId"
}

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) startMonitorService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()
        startMonitorService()
        setContent {
            MaterialTheme {
                Surface {
                    val vm = viewModel<MonitorViewModel>(factory = MonitorViewModel.Factory)
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = Routes.DASHBOARD) {
                        composable(Routes.DASHBOARD) {
                            DashboardScreen(
                                vm,
                                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                                onOpenRaw = { nav.navigate(Routes.RAW) },
                                onOpenCalibration = { nav.navigate(Routes.CALIBRATION) },
                                onOpenSessions = { nav.navigate(Routes.SESSIONS) },
                            )
                        }
                        composable(Routes.SESSIONS) {
                            SessionsScreen(
                                vm,
                                onReplay = { nav.navigate(Routes.replay(it)) },
                                onExport = { vm.export(it) },
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.REPLAY) { entry ->
                            ReplayScreen(
                                vm,
                                entry.arguments?.getString("sessionId")?.toLongOrNull() ?: 0L,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.CALIBRATION) { CalibrationScreen(vm) }
                        composable(Routes.SETTINGS) { SettingsScreen(vm) }
                        composable(Routes.RAW) { RawDebugScreen(vm) }
                    }
                }
            }
        }
    }

    private fun requestNeededPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startMonitorService() {
        ContextCompat.startForegroundService(this, Intent(this, MonitorForegroundService::class.java))
    }
}
