package com.passlock

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.passlock.crypto.BouncyCastleCryptoEngine
import com.passlock.crypto.RecoveryKit
import com.passlock.data.AppSettings
import com.passlock.data.Backup
import com.passlock.data.KeystoreManager
import com.passlock.data.KeystoreOuterWrap
import com.passlock.data.Opened
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.crypto.Cipher
import kotlin.math.max

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
    data object Gallery : Screen
}

class VaultViewModel(app: Application) : AndroidViewModel(app) {
    private val keystore = KeystoreManager()
    private val store = VaultStore(app.filesDir, KeystoreOuterWrap(keystore))
    private val decoyStore = VaultStore(app.filesDir, KeystoreOuterWrap(keystore), "decoy.plk", "decoy_bio.plk")
    private var activeStore: VaultStore = store
    private var biometricSuppressed = false
    private val passwordGen = PasswordGenerator()
    private val settings = AppSettings(app)
    private val engine = BouncyCastleCryptoEngine()
    private val attAad = "passlock.attachment.v1".toByteArray()

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
    var duressEnabled by mutableStateOf(decoyStore.exists())
        private set
    var fontScale by mutableStateOf(settings.fontScale)
        private set
    var requirePasswordColdStart by mutableStateOf(settings.requirePasswordColdStart)
        private set
    var requireRecoveryKit by mutableStateOf(settings.requireRecoveryKit)
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
            val result: Pair<VaultStore, Opened>? = withContext(Dispatchers.Default) {
                val pw = password.toCharArray()
                try {
                    when {
                        creating -> store to store.create(pw)
                        else -> {
                            val real = store.unlock(pw)
                            if (real != null) {
                                store to real
                            } else {
                                val decoy = if (decoyStore.exists()) decoyStore.unlock(pw) else null
                                if (decoy != null) decoyStore to decoy else null
                            }
                        }
                    }
                } catch (e: Exception) {
                    null
                } finally {
                    pw.fill(' ')
                }
            }
            busy = false
            when {
                result != null -> {
                    val (which, opened) = result
                    settings.failedAttempts = 0
                    settings.lockoutUntilMs = 0
                    activeStore = which
                    biometricSuppressed = which !== store
                    if (which === store) passwordUnlockedThisProcess = true
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
        lastInteractionAt = SystemClock.elapsedRealtime()
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

    private var expectingResult = false
    private var pickerFlowActive = false
    private var lastInteractionAt = SystemClock.elapsedRealtime()

    init {
        // Idle auto-lock: after 5 minutes with no interaction, lock and require auth again.
        // Polled every 30s, so it fires within ~30s of the 5-minute mark.
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                if (ui is VaultUiState.Unlocked &&
                    SystemClock.elapsedRealtime() - lastInteractionAt >= 5 * 60_000L
                ) {
                    lock()
                }
            }
        }
    }

    /** Resets the idle timer; called on every screen touch/key event via the Activity. */
    fun recordInteraction() {
        lastInteractionAt = SystemClock.elapsedRealtime()
    }

    /** Call right before launching a file/photo picker so it doesn't trip auto-lock. */
    fun expectActivityResult() {
        expectingResult = true
    }

    /**
     * Sticky auto-lock suppression for multi-step flows (e.g. export: passphrase → Recovery Kit
     * dialog → file picker). A single [expectActivityResult] only covers one backgrounding, but
     * here the user may leave the app to save the kit AND go through the picker — several ON_STOPs.
     * If the vault locked mid-flow it would zeroize the key and the export would silently write an
     * empty file. The flow MUST call [endPickerFlow] at every exit so locking resumes.
     */
    fun beginPickerFlow() {
        pickerFlowActive = true
    }

    fun endPickerFlow() {
        pickerFlowActive = false
        expectingResult = false
    }

    /**
     * Clears a pending one-shot picker expectation once we're safely back in the foreground.
     * Some pickers (e.g. the system photo picker) can return without ever firing ON_STOP, which
     * would otherwise leave [expectingResult] stuck true and silently suppress the auto-lock on the
     * NEXT genuine backgrounding — i.e. you'd return to an already-unlocked vault. Sticky multi-step
     * flows ([pickerFlowActive]) manage their own lifetime via [endPickerFlow], so leave those alone.
     */
    fun clearExpectedResult() {
        if (!pickerFlowActive) expectingResult = false
    }

    /** Whether an ON_STOP should skip the auto-lock (sticky flow, or a single expected picker). */
    fun consumeExpectedResult(): Boolean {
        if (pickerFlowActive) return true
        val expected = expectingResult
        expectingResult = false
        return expected
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
            if (opened == null) {
                error = "Biometric unlock failed — use your password"
            } else {
                activeStore = store
                biometricSuppressed = false
                unlockInto(opened.dek, opened.vault)
            }
        }
    }

    // ---------------- Navigation & search ----------------

    fun openList() { screen = Screen.List }
    fun openDetail(id: String) { screen = Screen.Detail(id) }
    fun openEditor(id: String?) { screen = Screen.Editor(id) }
    fun openSettings() { screen = Screen.Settings }
    fun openGallery() { screen = Screen.Gallery }
    fun back() { screen = Screen.List }

    fun chooseTheme(mode: String) { settings.themeMode = mode; themeMode = mode }
    fun chooseAutoWipe(enabled: Boolean) { settings.autoWipeEnabled = enabled; autoWipeEnabled = enabled }
    fun chooseFontScale(scale: Float) { settings.fontScale = scale; fontScale = scale }
    fun chooseRequirePassword(enabled: Boolean) { settings.requirePasswordColdStart = enabled; requirePasswordColdStart = enabled }
    fun chooseRequireRecoveryKit(enabled: Boolean) { settings.requireRecoveryKit = enabled; requireRecoveryKit = enabled }

    /**
     * Forgot-password escape hatch: permanently erases the real vault, the decoy vault, every
     * encrypted image, biometric material and counters, then returns to first-run setup. There is
     * no recovery — the data was unreadable without the password anyway, so this only starts fresh.
     */
    fun resetEverything() {
        dek?.let { engine.zeroize(it) }
        dek = null
        store.wipe()
        decoyStore.wipe()
        keystore.deleteBiometricKey()
        getApplication<Application>().filesDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("att_") && f.name.endsWith(".plk")) f.delete()
        }
        settings.failedAttempts = 0
        settings.lockoutUntilMs = 0
        biometricEnrolled = false
        duressEnabled = false
        biometricSuppressed = false
        passwordUnlockedThisProcess = false
        activeStore = store
        endPickerFlow()
        query = SearchQuery()
        error = null
        screen = Screen.List
        ui = VaultUiState.Setup
    }

    fun disableBiometric() {
        store.disableBiometric()
        keystore.deleteBiometricKey()
        biometricEnrolled = false
    }

    /** Whether to offer biometric unlock now (respects the password-at-cold-start policy). */
    fun biometricUnlockOffered(): Boolean =
        biometricCapable && biometricEnrolled && !biometricSuppressed &&
            (!requirePasswordColdStart || passwordUnlockedThisProcess)

    fun setupDuress(password: CharArray) {
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    decoyStore.create(password)
                } catch (e: Exception) {
                } finally {
                    password.fill(' ')
                }
            }
            duressEnabled = true
        }
    }

    fun removeDuress() {
        decoyStore.wipe()
        duressEnabled = false
    }

    // ---------------- Encrypted backup ----------------

    /** A fresh 128-bit Recovery Kit, shown once at export and required (with the passphrase) to restore. */
    fun generateRecoveryKit(): String = RecoveryKit.encode(engine.randomBytes(RecoveryKit.SECRET_BYTES))

    /** Whether a backup file was sealed with a Recovery Kit (so restore must collect one). */
    fun backupNeedsKit(bytes: ByteArray): Boolean = Backup.peekNeedsKit(bytes)

    /**
     * Produces an encrypted backup of the current vault under a recovery passphrase, optionally
     * bound to a [recoveryKit] (128 bits) so the file is brute-force-infeasible offline.
     */
    suspend fun exportBytes(passphrase: CharArray, recoveryKit: String?): ByteArray? = withContext(Dispatchers.IO) {
        val v = vault ?: run { passphrase.fill(' '); return@withContext null }
        val kitBytes = recoveryKit?.let { RecoveryKit.decode(it) }
        try {
            val images = allImageIds().mapNotNull { id -> decryptBlob(id)?.let { id to it } }.toMap()
            Backup.export(v, images, passphrase, kitBytes)
        } catch (e: Exception) {
            null
        } finally {
            passphrase.fill(' ')
            kitBytes?.fill(0)
        }
    }

    /**
     * Restores a backup onto this device: decrypts it with the recovery passphrase, then
     * seals it under a freshly chosen master password (and this device's hardware key).
     * Replaces any existing vault. Returns false if the recovery passphrase is wrong.
     */
    suspend fun restoreFromBackup(
        bytes: ByteArray,
        recoveryPassphrase: CharArray,
        recoveryKit: String?,
        masterPassword: CharArray,
    ): Boolean {
        val kitBytes = recoveryKit?.let { RecoveryKit.decode(it) }
        val restored = withContext(Dispatchers.Default) {
            try {
                Backup.import(bytes, recoveryPassphrase, kitBytes)
            } catch (e: Exception) {
                null
            } finally {
                recoveryPassphrase.fill(' ')
                kitBytes?.fill(0)
            }
        } ?: run { masterPassword.fill(' '); return false }

        val opened = withContext(Dispatchers.IO) {
            try {
                store.disableBiometric()
                val o = store.create(masterPassword)
                // Re-encrypt the bundled images under the NEW device key.
                val dir = getApplication<Application>().filesDir
                restored.images.forEach { (id, data) ->
                    File(dir, "att_$id.plk").writeBytes(engine.aeadEncrypt(o.dek, data, attAad))
                }
                store.save(o.dek, restored.vault)
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
        activeStore = store
        biometricSuppressed = false
        unlockInto(opened.dek, restored.vault)
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
        cur.items.firstOrNull { it.id == id }?.attachments?.forEach { deleteImageBlob(it) }
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
            withContext(Dispatchers.Default) { activeStore.save(key, newVault) }
            ui = VaultUiState.Unlocked(newVault)
            onDone()
        }
    }

    // ---------------- Helpers ----------------

    fun generatePassword(policy: PasswordPolicy = PasswordPolicy()): String = passwordGen.generate(policy)

    fun totpCode(value: String, epochSec: Long = System.currentTimeMillis() / 1000): String? =
        TotpSecret.parse(value)?.let { runCatching { Totp.generate(it, epochSec) }.getOrNull() }

    private var clipboardClearAt = 0L

    fun copy(value: String) {
        val cm = clipboard()
        val clip = ClipData.newPlainText("PassLock", value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        clipboardClearAt = System.currentTimeMillis() + 30_000
        viewModelScope.launch {
            delay(30_000)
            // Attempt the clear; if we're backgrounded the OS no-ops it, so we do NOT
            // disarm the pending flag — clearClipboardIfDue() will retry on return.
            tryClearClipboard()
        }
    }

    private fun tryClearClipboard() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard().clearPrimaryClip()
        } catch (_: Throwable) {
        }
    }

    /** On returning to PassLock, clear any past-due sensitive copy (covers cross-app pastes). */
    fun clearClipboardIfDue() {
        if (clipboardClearAt in 1..System.currentTimeMillis()) {
            tryClearClipboard()
            clipboardClearAt = 0L
        }
    }

    private fun clipboard() =
        getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // ---------------- Encrypted image attachments ----------------

    /** Downscales + JPEG-compresses, encrypts with the DEK, writes a blob, returns its id. */
    suspend fun encryptAndStoreImage(uri: Uri): String? {
        val key = dek ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val raw = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
                val processed = processImage(raw)
                val blob = engine.aeadEncrypt(key, processed, attAad)
                val id = UUID.randomUUID().toString()
                File(app.filesDir, "att_$id.plk").writeBytes(blob)
                id
            } catch (e: Throwable) {
                null // includes OutOfMemoryError on huge images
            }
        }
    }

    private fun decryptBlob(id: String): ByteArray? {
        val key = dek ?: return null
        return try {
            val f = File(getApplication<Application>().filesDir, "att_$id.plk")
            if (!f.exists()) null else engine.aeadDecrypt(key, f.readBytes(), attAad)
        } catch (e: Throwable) {
            null
        }
    }

    suspend fun loadImage(id: String): ByteArray? = withContext(Dispatchers.IO) { decryptBlob(id) }

    /** Exports a decrypted copy of an image to the phone's Pictures gallery (the user's explicit choice). */
    suspend fun saveImageToPhoneGallery(id: String): Boolean = withContext(Dispatchers.IO) {
        val bytes = decryptBlob(id) ?: return@withContext false
        try {
            val resolver = getApplication<Application>().contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "passlock_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PassLock")
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@withContext false
            true
        } catch (e: Throwable) {
            false
        }
    }

    fun deleteImageBlob(id: String) {
        File(getApplication<Application>().filesDir, "att_$id.plk").delete()
    }

    /** Every image in the vault: item attachments plus standalone gallery photos. */
    fun allImageIds(): List<String> {
        val v = vault ?: return emptyList()
        return (v.items.flatMap { it.attachments } + v.galleryImages).distinct()
    }

    /** True if this id is a standalone gallery photo (deletable here), not an item attachment. */
    fun isStandaloneImage(id: String): Boolean = vault?.galleryImages?.contains(id) == true

    /** Encrypts and stores several picked photos, then persists the vault once for the whole batch. */
    suspend fun addGalleryImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val key = dek ?: return
        val cur = vault ?: return
        val newIds = uris.mapNotNull { encryptAndStoreImage(it) }
        if (newIds.isEmpty()) return
        val newVault = cur.copy(galleryImages = cur.galleryImages + newIds)
        withContext(Dispatchers.Default) { activeStore.save(key, newVault) }
        ui = VaultUiState.Unlocked(newVault)
    }

    suspend fun addGalleryImage(uri: Uri) {
        val key = dek ?: return
        val cur = vault ?: return
        val id = encryptAndStoreImage(uri) ?: return
        val newVault = cur.copy(galleryImages = cur.galleryImages + id)
        withContext(Dispatchers.Default) { activeStore.save(key, newVault) }
        ui = VaultUiState.Unlocked(newVault)
    }

    /** Deletes a standalone gallery photo. Item-attached photos are managed in the item editor. */
    fun deleteGalleryImage(id: String) {
        val key = dek ?: return
        val cur = vault ?: return
        if (id !in cur.galleryImages) return
        deleteImageBlob(id)
        persist(key, cur.copy(galleryImages = cur.galleryImages - id)) {}
    }

    private fun processImage(bytes: ByteArray): ByteArray {
        val maxDim = 1600
        // Decode bounds first, then downsample DURING decode so a 50MP photo never
        // inflates to a full Bitmap in memory (which would OutOfMemory-crash).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return bytes
        val largest = max(bmp.width, bmp.height)
        val scaled = if (largest > maxDim) {
            val s = maxDim.toFloat() / largest
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        } else {
            bmp
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    override fun onCleared() {
        dek?.fill(0)
        dek = null
    }
}
