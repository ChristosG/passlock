package com.passlock

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.passlock.ui.PassLockTheme
import com.passlock.ui.UnlockScreen
import com.passlock.ui.VaultScreen

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
                var unlocked by remember { mutableStateOf(false) }
                if (unlocked) {
                    VaultScreen(onLock = { unlocked = false })
                } else {
                    UnlockScreen(onUnlock = { unlocked = true })
                }
            }
        }
    }
}
