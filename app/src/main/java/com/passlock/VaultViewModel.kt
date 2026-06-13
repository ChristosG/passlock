package com.passlock

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.biometric.BiometricManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.passlock.data.KeystoreManager
import com.passlock.data.KeystoreOuterWrap
import com.passlock.data.VaultStore
import com.passlock.domain.Field
import com.passlock.domain.FieldType
import com.passlock.domain.Item
import com.passlock.domain.Template
import com.passlock.domain.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.crypto.Cipher

sealed interface VaultUiState {
    /** No vault file yet — first run, create a master password. */
    data object Setup : VaultUiState

    /** A vault exists but is sealed. */
    data object Locked : VaultUiState

    /** Unlocked: real decrypted contents are present (only reachable with key material). */
    data class Unlocked(val vault: Vault) : VaultUiState
}

class VaultViewModel(app: Application) : AndroidViewModel(app) {
    private val keystore = KeystoreManager()
    private val store = VaultStore(app.filesDir, KeystoreOuterWrap(keystore))

    /** In-memory data-encryption key; null whenever locked. Zeroized on lock. */
    private var dek: ByteArray? = null

    /** Whether the device has a usable strong biometric enrolled. */
    val biometricCapable: Boolean =
        BiometricManager.from(app).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    var biometricEnrolled by mutableStateOf(store.hasBiometric())
        private set

    var ui by mutableStateOf<VaultUiState>(if (store.exists()) VaultUiState.Locked else VaultUiState.Setup)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun submitAuth(password: String) {
        if (busy || password.isEmpty()) return
        error = null
        busy = true
        val creating = !store.exists()
        viewModelScope.launch {
            val opened = withContext(Dispatchers.Default) {
                val pw = password.toCharArray()
                try {
                    if (creating) store.create(pw) else store.unlock(pw)
                } catch (e: Exception) {
                    null // any store/keystore error is treated as a failed unlock, never a crash
                } finally {
                    pw.fill(' ')
                }
            }
            busy = false
            if (opened == null) {
                error = "Wrong master password"
            } else {
                dek = opened.dek
                ui = VaultUiState.Unlocked(opened.vault)
            }
        }
    }

    fun lock() {
        dek?.fill(0)
        dek = null
        error = null
        if (ui is VaultUiState.Unlocked) ui = VaultUiState.Locked
    }

    // ---------------- Biometric ----------------

    /** Cipher to enroll biometric (encrypts the live DEK after auth). Null on failure. */
    fun encryptCipherForEnroll(): Cipher? =
        if (dek == null) null else try { keystore.biometricEncryptCipher() } catch (e: Exception) { null }

    /** Cipher to unlock via biometric. Null if unavailable or the key was invalidated. */
    fun decryptCipherForUnlock(): Cipher? {
        val iv = store.biometricIv() ?: return null
        return try {
            keystore.biometricDecryptCipher(iv)
        } catch (e: Exception) {
            // e.g. KeyPermanentlyInvalidatedException after new biometric enrollment.
            store.disableBiometric()
            keystore.deleteBiometricKey()
            biometricEnrolled = false
            null
        }
    }

    fun confirmEnroll(authorizedCipher: Cipher) {
        val key = dek ?: return
        try {
            store.enableBiometric(key, authorizedCipher)
            biometricEnrolled = true
        } catch (e: Exception) {
            error = "Couldn't enable biometric unlock"
        }
    }

    fun confirmBiometricUnlock(authorizedCipher: Cipher) {
        busy = true
        viewModelScope.launch {
            val opened = withContext(Dispatchers.Default) {
                try {
                    store.unlockWithBiometric(authorizedCipher)
                } catch (e: Exception) {
                    null
                }
            }
            busy = false
            if (opened == null) {
                error = "Biometric unlock failed — use your password"
            } else {
                dek = opened.dek
                ui = VaultUiState.Unlocked(opened.vault)
            }
        }
    }

    // ---------------- Items / clipboard ----------------

    fun addItem(title: String, value: String) {
        val key = dek ?: return
        val current = (ui as? VaultUiState.Unlocked)?.vault ?: return
        val fieldId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val item = Item(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "Untitled" },
            template = Template.CUSTOM,
            fields = listOf(Field(fieldId, "Value", value, FieldType.PASSWORD, isSecret = true)),
            primaryFieldId = fieldId,
            createdAt = now,
            updatedAt = now,
        )
        val newVault = current.copy(items = current.items + item)
        viewModelScope.launch {
            withContext(Dispatchers.Default) { store.save(key, newVault) }
            ui = VaultUiState.Unlocked(newVault)
        }
    }

    fun copy(value: String) {
        val ctx = getApplication<Application>()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("PassLock", value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        // Auto-clear after 20s so secrets don't linger on the clipboard.
        viewModelScope.launch {
            delay(20_000)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) cm.clearPrimaryClip()
            } catch (_: Exception) {
            }
        }
    }

    override fun onCleared() {
        dek?.fill(0)
        dek = null
    }
}
