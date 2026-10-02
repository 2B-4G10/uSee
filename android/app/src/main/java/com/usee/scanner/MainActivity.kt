package com.usee.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.usee.scanner.ui.AppViewModel
import com.usee.scanner.ui.USeeRoot
import com.usee.scanner.ui.screens.PermissionScreen
import com.usee.scanner.ui.theme.Palette
import com.usee.scanner.ui.theme.USeeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        vm.setPermissionsGranted(hasPermissions())

        // Radios run only while the UI is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.setPermissionsGranted(hasPermissions())
                vm.start()
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    vm.stop()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.settings.collect { s ->
                    if (s.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        setContent {
            USeeTheme {
                val granted by vm.permissionsGranted.collectAsStateWithLifecycle()
                var askedOnce by rememberSaveable { mutableStateOf(false) }
                var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    val ok = hasPermissions()
                    vm.setPermissionsGranted(ok)
                    permanentlyDenied = !ok && askedOnce &&
                        !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                    askedOnce = true
                }
                LaunchedEffect(Unit) {
                    if (!hasPermissions() && !askedOnce) {
                        askedOnce = true
                        launcher.launch(requiredPermissions)
                    }
                }
                if (granted) {
                    USeeRoot(vm)
                } else {
                    Box(Modifier.fillMaxSize().background(Palette.backdrop).safeDrawingPadding()) {
                        PermissionScreen(
                            permanentlyDenied = permanentlyDenied,
                            onGrant = { launcher.launch(requiredPermissions) },
                            onOpenSettings = {
                                startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
