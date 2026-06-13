package com.passlock

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.passlock.ui.PassLockRoot
import com.passlock.ui.PassLockTheme

class MainActivity : ComponentActivity() {
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
