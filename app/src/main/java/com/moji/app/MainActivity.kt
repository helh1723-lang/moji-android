package com.moji.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moji.app.capture.CaptureFeedback
import com.moji.app.ui.MojiApp
import com.moji.app.ui.MojiViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var editTransactionId by mutableStateOf<String?>(null)
    private var notificationPermissionRequestedThisSession = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptEditIntent(intent)
        enableEdgeToEdge()
        val app = application as MojiApplication
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.settings.values.collect { settings ->
                    if (settings.hideRecents) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    if (
                        settings.captureConsent &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED &&
                        !notificationPermissionRequestedThisSession
                    ) {
                        notificationPermissionRequestedThisSession = true
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
        setContent {
            val viewModel: MojiViewModel = viewModel(factory = MojiViewModel.Factory(app.repository, app.settings))
            MojiApp(
                viewModel = viewModel,
                editTransactionId = editTransactionId,
                onEditConsumed = { editTransactionId = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptEditIntent(intent)
    }

    private fun acceptEditIntent(intent: Intent?) {
        editTransactionId = intent
            ?.takeIf { it.action == CaptureFeedback.ACTION_EDIT }
            ?.getStringExtra(CaptureFeedback.EXTRA_TRANSACTION_ID)
    }
}
