package com.passlock

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.passlock.ui.PassLockRoot
import com.passlock.ui.PassLockTheme

// FragmentActivity (not ComponentActivity) is required by androidx BiometricPrompt.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Block screenshots, screen recording, and the recents-thumbnail preview.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        setContent {
            PassLockTheme {
                val vm: VaultViewModel = viewModel()
                PassLockRoot(vm)
            }
        }
    }
}
