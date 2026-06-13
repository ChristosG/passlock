@file:OptIn(ExperimentalMaterial3Api::class)

package com.passlock.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.passlock.Screen
import com.passlock.VaultUiState
import com.passlock.VaultViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.crypto.Cipher

internal fun authenticateBiometric(
    activity: FragmentActivity,
    cipher: Cipher,
    title: String,
    subtitle: String,
    onSuccess: (Cipher) -> Unit,
    onError: (String) -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authorized = result.cryptoObject?.cipher
                if (authorized != null) onSuccess(authorized) else onError("No cipher returned")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString.toString())
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setNegativeButtonText("Use password")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
}

@Composable
fun PassLockRoot(vm: VaultViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.lock()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // In-app back: navigate to the list instead of closing the app.
    BackHandler(enabled = vm.ui is VaultUiState.Unlocked && vm.screen != Screen.List) {
        vm.back()
    }

    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()

    fun triggerBiometricUnlock() {
        val act = activity ?: return
        val cipher = vm.decryptCipherForUnlock() ?: return
        authenticateBiometric(act, cipher, "Unlock PassLock", "Use your fingerprint or face", { vm.confirmBiometricUnlock(it) }, { })
    }

    fun triggerBiometricEnroll() {
        val act = activity ?: return
        val cipher = vm.encryptCipherForEnroll() ?: return
        authenticateBiometric(act, cipher, "Enable biometric unlock", "Confirm to link your biometrics", { vm.confirmEnroll(it) }, { })
    }

    // Restore-from-backup file picker.
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes != null) restoreBytes = bytes else Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_LONG).show()
        }
    }

    when (val state = vm.ui) {
        is VaultUiState.Unlocked -> when (val sc = vm.screen) {
            is Screen.List -> VaultListScreen(vm, if (vm.biometricCapable && !vm.biometricEnrolled) ::triggerBiometricEnroll else null)
            is Screen.Detail -> ItemDetailScreen(vm, sc.itemId)
            is Screen.Editor -> ItemEditorScreen(vm, sc.itemId)
            is Screen.Settings -> SettingsScreen(
                vm = vm,
                onEnableBiometric = if (vm.biometricCapable && !vm.biometricEnrolled) ::triggerBiometricEnroll else null,
                onDisableBiometric = vm::disableBiometric,
            )
        }
        else -> AuthScreen(
            isSetup = state is VaultUiState.Setup,
            busy = vm.busy,
            error = vm.error,
            onSubmit = vm::submitAuth,
            showBiometric = state is VaultUiState.Locked && vm.biometricUnlockOffered(),
            onBiometric = ::triggerBiometricUnlock,
            onRestore = { openDoc.launch(arrayOf("*/*")) },
            rooted = vm.rooted,
            lockoutUntilMs = vm.lockoutUntilMs,
        )
    }

    restoreBytes?.let { bytes ->
        RestoreDialog(
            onDismiss = { restoreBytes = null },
            onConfirm = { recovery, master ->
                restoreBytes = null
                scope.launch {
                    val ok = vm.restoreFromBackup(bytes, recovery.toCharArray(), master.toCharArray())
                    if (!ok) Toast.makeText(context, "Restore failed — wrong passphrase or bad file", Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}

@Composable
private fun RestoreDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var recovery by remember { mutableStateOf("") }
    var master by remember { mutableStateOf("") }
    val canRestore = recovery.isNotEmpty() && master.length >= 8
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(recovery, master) }, enabled = canRestore) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Restore backup") },
        text = {
            Column {
                Text("This replaces any vault on this device.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                PasswordField(
                    value = recovery,
                    onValueChange = { recovery = it },
                    label = "Recovery passphrase",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                PasswordField(
                    value = master,
                    onValueChange = { master = it },
                    label = "New master password (8+)",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
fun AuthScreen(
    isSetup: Boolean,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    showBiometric: Boolean = false,
    onBiometric: () -> Unit = {},
    onRestore: () -> Unit = {},
    rooted: Boolean = false,
    lockoutUntilMs: Long = 0L,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lockoutUntilMs) {
        while (System.currentTimeMillis() < lockoutUntilMs) {
            nowMs = System.currentTimeMillis()
            delay(500)
        }
        nowMs = System.currentTimeMillis()
    }
    val lockedRemaining = if (lockoutUntilMs > nowMs) ((lockoutUntilMs - nowMs) / 1000 + 1).toInt() else 0

    val tooShort = isSetup && password.isNotEmpty() && password.length < 8
    val mismatch = isSetup && confirm.isNotEmpty() && confirm != password
    val canSubmit = !busy && lockedRemaining == 0 && password.isNotEmpty() &&
        (!isSetup || (password.length >= 8 && password == confirm))

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (rooted) {
                Text(
                    "⚠ This device looks rooted/tampered — your secrets may be at higher risk.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            Text("🔐", fontSize = 56.sp)
            Spacer(Modifier.height(16.dp))
            Text("PassLock", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
            Text(
                if (isSetup) "Create your master password" else "Enter your master password",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            PasswordField(
                value = password,
                onValueChange = { password = it },
                label = "Master password",
                isError = tooShort,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isSetup) {
                Spacer(Modifier.height(12.dp))
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = "Confirm password",
                    isError = mismatch,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val hint = when {
                error != null -> error
                tooShort -> "Use at least 8 characters"
                mismatch -> "Passwords don't match"
                else -> null
            }
            if (hint != null) {
                Spacer(Modifier.height(10.dp))
                Text(hint, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onSubmit(password) },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                when {
                    busy -> CircularProgressIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    lockedRemaining > 0 -> Text("Locked ${lockedRemaining}s", fontWeight = FontWeight.Bold)
                    else -> Text(if (isSetup) "Create vault" else "Unlock", fontWeight = FontWeight.Bold)
                }
            }
            if (showBiometric) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onBiometric, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text("Unlock with biometrics")
                }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onRestore, enabled = !busy) { Text("Restore from backup") }
            Spacer(Modifier.height(16.dp))
            Text("Offline · encrypted · no network", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
