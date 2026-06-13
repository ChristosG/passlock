package com.passlock

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.passlock.ui.PassLockRoot
import com.passlock.ui.PassLockTheme

// FragmentActivity (not ComponentActivity) is required by androidx BiometricPrompt.
class MainActivity : FragmentActivity() {
    private val vm: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Block screenshots, screen recording, and the recents-thumbnail preview.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        setContent {
            PassLockTheme(vm.themeMode) {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, vm.fontScale)) {
                    PassLockRoot(vm)
                }
            }
        }
    }

    // Resets the idle auto-lock timer on every touch/key event while the app is in use.
    override fun onUserInteraction() {
        super.onUserInteraction()
        vm.recordInteraction()
    }
}
