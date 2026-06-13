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
import com.passlock.data.AppSettings
import com.passlock.data.Backup
import com.passlock.data.KeystoreManager
import com.passlock.data.KeystoreOuterWrap
import com.passlock.data.RootCheck
import com.passlock.data.VaultStore
import com.passlock.domain.Item
import com.passlock.domain.PasswordGenerator
import com.passlock.domain.PasswordPolicy
import com.passlock.domain.SearchQuery
import com.passlock.domain.Template
import com.passlock.domain.Templates
import com.passlock.domain.Totp
import com.passlock.domain.TotpSecret
import com.passlock.domain.Vault
import com.passlock.domain.VaultSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.crypto.Cipher

sealed interface VaultUiState {
    data object Setup : VaultUiState
    data object Locked : VaultUiState
    data class Unlocked(val vault: Vault) : VaultUiState
}

/** In-vault navigation (only meaningful while [VaultUiState.Unlocked]). */
sealed interface Screen {
    data object List : Screen
    data class Detail(val itemId: String) : Screen
    data class Editor(val itemId: String?) : Screen
    data object Settings : Screen
}

class VaultViewModel(app: Application) : AndroidViewModel(app) {
    private val keystore = KeystoreManager()
    private val store = VaultStore(app.filesDir, KeystoreOuterWrap(keystore))
    private val passwordGen = PasswordGenerator()
    private val settings = AppSettings(app)

    private var dek: ByteArray? = null

    /** Advisory root/tamper signal — we warn, never block. */
    val rooted: Boolean = RootCheck.isLikelyRooted()
    val lockoutUntilMs: Long get() = settings.lockoutUntilMs

    val biometricCapable: Boolean =
        BiometricManager.from(app).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    var biometricEnrolled by mutableStateOf(store.hasBiometric())
        private set
    var themeMode by mutableStateOf(settings.themeMode)
        private set
    var autoWipeEnabled by mutableStateOf(settings.autoWipeEnabled)
        private set
    var fontScale by mutableStateOf(settings.fontScale)
        private set
    var requirePasswordColdStart by mutableStateOf(settings.requirePasswordColdStart)
        private set

    private var passwordUnlockedThisProcess = false

    var ui by mutableStateOf<VaultUiState>(if (store.exists()) VaultUiState.Locked else VaultUiState.Setup)
        private set
    var screen by mutableStateOf<Screen>(Screen.List)
        private set
    var query by mutableStateOf(SearchQuery())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val vault: Vault? get() = (ui as? VaultUiState.Unlocked)?.vault

    // ---------------- Auth ----------------

    fun submitAuth(password: String) {
        if (busy || password.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now < settings.lockoutUntilMs) {
            error = "Locked — wait ${(settings.lockoutUntilMs - now) / 1000 + 1}s"
            return
        }
        error = null
        busy = true
        val creating = !store.exists()
        viewModelScope.launch {
            val opened = withContext(Dispatchers.Default) {
                val pw = password.toCharArray()
                try {
                    if (creating) store.create(pw) else store.unlock(pw)
                } catch (e: Exception) {
                    null
                } finally {
                    pw.fill(' ')
                }
            }
            busy = false
            when {
                opened != null -> {
                    settings.failedAttempts = 0
                    settings.lockoutUntilMs = 0
                    passwordUnlockedThisProcess = true
                    unlockInto(opened.dek, opened.vault)
                }
                creating -> error = "Couldn't create vault"
                else -> onFailedAttempt()
            }
        }
    }

    /** Escalating back-off after the 2nd wrong attempt; optional auto-wipe after N. */
    private fun onFailedAttempt() {
        val fails = settings.failedAttempts + 1
        settings.failedAttempts = fails
        when {
            autoWipeEnabled && fails >= AppSettings.AUTO_WIPE_THRESHOLD -> {
                store.wipe()
                keystore.deleteBiometricKey()
                biometricEnrolled = false
                settings.failedAttempts = 0
                settings.lockoutUntilMs = 0
                ui = VaultUiState.Setup
                error = "Too many attempts — vault wiped"
            }
            fails >= 2 -> {
                val delaySec = (1L shl (fails - 1)).coerceAtMost(64)
                settings.lockoutUntilMs = System.currentTimeMillis() + delaySec * 1000
                error = "Wrong password — wait ${delaySec}s"
            }
            else -> error = "Wrong master password"
        }
    }

    private fun unlockInto(key: ByteArray, v: Vault) {
        dek = key
        screen = Screen.List
        query = SearchQuery()
        ui = VaultUiState.Unlocked(v)
    }

    fun lock() {
        dek?.fill(0)
        dek = null
        error = null
        if (ui is VaultUiState.Unlocked) {
            screen = Screen.List
            ui = VaultUiState.Locked
        }
    }

    // ---------------- Biometric ----------------

    fun encryptCipherForEnroll(): Cipher? =
        if (dek == null) null else try { keystore.biometricEncryptCipher() } catch (e: Exception) { null }

    fun decryptCipherForUnlock(): Cipher? {
        val iv = store.biometricIv() ?: return null
        return try {
            keystore.biometricDecryptCipher(iv)
        } catch (e: Exception) {
            store.disableBiometric(); keystore.deleteBiometricKey(); biometricEnrolled = false; null
        }
    }

    fun confirmEnroll(authorizedCipher: Cipher) {
        val key = dek ?: return
        try {
            store.enableBiometric(key, authorizedCipher); biometricEnrolled = true
        } catch (e: Exception) {
            error = "Couldn't enable biometric unlock"
        }
    }

    fun confirmBiometricUnlock(authorizedCipher: Cipher) {
        busy = true
        viewModelScope.launch {
            val opened = withContext(Dispatchers.Default) {
                try { store.unlockWithBiometric(authorizedCipher) } catch (e: Exception) { null }
            }
            busy = false
            if (opened == null) error = "Biometric unlock failed — use your password"
            else unlockInto(opened.dek, opened.vault)
        }
    }

    // ---------------- Navigation & search ----------------

    fun openList() { screen = Screen.List }
    fun openDetail(id: String) { screen = Screen.Detail(id) }
    fun openEditor(id: String?) { screen = Screen.Editor(id) }
    fun openSettings() { screen = Screen.Settings }
    fun back() { screen = Screen.List }

    fun chooseTheme(mode: String) { settings.themeMode = mode; themeMode = mode }
    fun chooseAutoWipe(enabled: Boolean) { settings.autoWipeEnabled = enabled; autoWipeEnabled = enabled }
    fun chooseFontScale(scale: Float) { settings.fontScale = scale; fontScale = scale }
    fun chooseRequirePassword(enabled: Boolean) { settings.requirePasswordColdStart = enabled; requirePasswordColdStart = enabled }

    fun disableBiometric() {
        store.disableBiometric()
        keystore.deleteBiometricKey()
        biometricEnrolled = false
    }

    /** Whether to offer biometric unlock now (respects the password-at-cold-start policy). */
    fun biometricUnlockOffered(): Boolean =
        biometricCapable && biometricEnrolled && (!requirePasswordColdStart || passwordUnlockedThisProcess)

    // ---------------- Encrypted backup ----------------

    /** Produces an encrypted backup of the current vault under a recovery passphrase. */
    suspend fun exportBytes(passphrase: CharArray): ByteArray? = withContext(Dispatchers.Default) {
        val v = vault ?: return@withContext null
        try {
            Backup.export(v, passphrase)
        } catch (e: Exception) {
            null
        } finally {
            passphrase.fill(' ')
        }
    }

    /**
     * Restores a backup onto this device: decrypts it with the recovery passphrase, then
     * seals it under a freshly chosen master password (and this device's hardware key).
     * Replaces any existing vault. Returns false if the recovery passphrase is wrong.
     */
    suspend fun restoreFromBackup(bytes: ByteArray, recoveryPassphrase: CharArray, masterPassword: CharArray): Boolean {
        val imported = withContext(Dispatchers.Default) {
            try {
                Backup.import(bytes, recoveryPassphrase)
            } catch (e: Exception) {
                null
            } finally {
                recoveryPassphrase.fill(' ')
            }
        } ?: run { masterPassword.fill(' '); return false }

        val opened = withContext(Dispatchers.Default) {
            try {
                store.disableBiometric()
                val o = store.create(masterPassword)
                store.save(o.dek, imported)
                o
            } catch (e: Exception) {
                null
            } finally {
                masterPassword.fill(' ')
            }
        } ?: return false

        keystore.deleteBiometricKey()
        biometricEnrolled = false
        passwordUnlockedThisProcess = true
        unlockInto(opened.dek, imported)
        return true
    }

    fun setSearchText(text: String) { query = query.copy(text = text) }
    fun setTemplateFilter(template: Template?) { query = query.copy(template = template, favoritesOnly = false) }
    fun showFavoritesOnly() { query = query.copy(favoritesOnly = true, template = null) }
    fun clearFilters() { query = SearchQuery(text = query.text) }

    fun visibleItems(): List<Item> {
        val v = vault ?: return emptyList()
        return VaultSearch.filter(v.items, query)
            .sortedWith(compareByDescending<Item> { it.favorite }.thenByDescending { it.updatedAt })
    }

    fun itemById(id: String): Item? = vault?.items?.firstOrNull { it.id == id }

    // ---------------- CRUD ----------------

    fun blankItem(template: Template): Item {
        val now = System.currentTimeMillis()
        val fields = Templates.defaultFields(template) { UUID.randomUUID().toString() }
        return Item(
            id = UUID.randomUUID().toString(),
            title = "",
            template = template,
            fields = fields,
            primaryFieldId = (fields.firstOrNull { it.isSecret } ?: fields.firstOrNull())?.id,
            createdAt = now,
            updatedAt = now,
        )
    }

    fun saveItem(item: Item) {
        val key = dek ?: return
        val cur = vault ?: return
        val now = System.currentTimeMillis()
        val idx = cur.items.indexOfFirst { it.id == item.id }
        val finalItem = item.copy(
            updatedAt = now,
            createdAt = if (idx >= 0) cur.items[idx].createdAt else now,
        )
        val items = if (idx >= 0) cur.items.toMutableList().also { it[idx] = finalItem } else cur.items + finalItem
        persist(key, cur.copy(items = items)) { openDetail(finalItem.id) }
    }

    fun deleteItem(id: String) {
        val key = dek ?: return
        val cur = vault ?: return
        persist(key, cur.copy(items = cur.items.filterNot { it.id == id })) { openList() }
    }

    fun toggleFavorite(id: String) {
        val key = dek ?: return
        val cur = vault ?: return
        val items = cur.items.map { if (it.id == id) it.copy(favorite = !it.favorite) else it }
        persist(key, cur.copy(items = items)) {}
    }

    private fun persist(key: ByteArray, newVault: Vault, onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.Default) { store.save(key, newVault) }
            ui = VaultUiState.Unlocked(newVault)
            onDone()
        }
    }

    // ---------------- Helpers ----------------

    fun generatePassword(policy: PasswordPolicy = PasswordPolicy()): String = passwordGen.generate(policy)

    fun totpCode(value: String, epochSec: Long = System.currentTimeMillis() / 1000): String? =
        TotpSecret.parse(value)?.let { runCatching { Totp.generate(it, epochSec) }.getOrNull() }

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
