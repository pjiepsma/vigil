package com.vigil.wear.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vigil.wear.metrics.PermissionCapabilityCoordinator
import com.vigil.wear.presentation.navigation.VigilNavHost
import com.vigil.wear.presentation.theme.VigilTheme
import com.vigil.wear.session.SessionViewModel
import com.vigil.wear.session.SessionViewModelFactory

/**
 * Wear OS entry activity. All UI is built with Compose; navigation lives in [VigilNavHost].
 */
class MainActivity : ComponentActivity() {
    private var isPermissionRequestInFlight = false
    private val permissionPrefs by lazy {
        getSharedPreferences(PERMISSION_PREFS_NAME, MODE_PRIVATE)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            isPermissionRequestInFlight = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!permissionPrefs.getBoolean(KEY_INITIAL_REQUEST_COMPLETE, false)) {
            permissionPrefs.edit().putBoolean(KEY_INITIAL_REQUEST_COMPLETE, true).apply()
            requestMissingLaunchPermissions()
        }

        setContent {
            VigilTheme {
                val sessionViewModel: SessionViewModel =
                    viewModel(factory = SessionViewModelFactory)
                VigilNavHost(viewModel = sessionViewModel)
            }
        }
    }

    private fun requestMissingLaunchPermissions() {
        if (isPermissionRequestInFlight) return
        val nextMissing = PermissionCapabilityCoordinator.missingLaunchPermissions(this).firstOrNull()
        if (nextMissing != null) {
            isPermissionRequestInFlight = true
            permissionLauncher.launch(nextMissing)
        }
    }

    companion object {
        private const val PERMISSION_PREFS_NAME = "vigil_permissions"
        private const val KEY_INITIAL_REQUEST_COMPLETE = "initial_request_complete"
    }
}
