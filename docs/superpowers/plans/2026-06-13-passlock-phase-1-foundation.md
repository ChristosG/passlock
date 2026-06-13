# PassLock Phase 1 — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the pure-JVM core of PassLock — a tested cryptography module and a tested domain model — that everything else (storage, UI, hardening) will sit on top of.

**Architecture:** Two Kotlin/JVM Gradle modules with no Android dependencies, so they run and test on a plain dev machine. `:core-crypto` owns key derivation, authenticated encryption, the random Data Encryption Key (DEK), and the password-derived inner wrap of that DEK. `:core-domain` owns the data model (Vault/Item/Field/Template), the offline TOTP generator, the password generator, and search/filter. Everything is exercised through narrow interfaces and pinned with TDD.

**Tech Stack:** Kotlin (JVM), Gradle (Kotlin DSL) with a version catalog, JUnit 5, Bouncy Castle (`bcprov-jdk18on`) for Argon2id, platform `javax.crypto` for AES-256-GCM.

---

## File structure (Phase 1)

```
settings.gradle.kts                       # includes :core-crypto, :core-domain
build.gradle.kts                          # root, declares plugins (apply false)
gradle/libs.versions.toml                 # version catalog
gradle/wrapper/gradle-wrapper.properties  # wrapper config

core-crypto/
  build.gradle.kts
  src/main/kotlin/com/passlock/crypto/
    KdfParams.kt          # Argon2id parameters + floors/defaults
    CryptoEngine.kt       # interface: deriveKey/aeadEncrypt/aeadDecrypt/randomBytes/zeroize
    BouncyCastleCryptoEngine.kt   # the implementation
    PassphraseWrap.kt     # wrap/unwrap a DEK under a passphrase-derived key
  src/test/kotlin/com/passlock/crypto/
    KdfParamsTest.kt
    Argon2KdfTest.kt
    AeadTest.kt
    PassphraseWrapTest.kt

core-domain/
  build.gradle.kts
  src/main/kotlin/com/passlock/domain/
    Models.kt             # FieldType, Template, Field, Item, Vault
    Templates.kt          # default fields per template
    Totp.kt               # RFC 6238 offline 2FA codes
    PasswordGenerator.kt  # policy-driven strong password/PIN generation
    VaultSearch.kt        # filter by text/template/favorite/tag (never matches secret values)
  src/test/kotlin/com/passlock/domain/
    TemplatesTest.kt
    TotpTest.kt
    PasswordGeneratorTest.kt
    VaultSearchTest.kt
```

**Responsibilities:** `:core-crypto` is the only place primitives are called. `:core-domain` is pure data + pure functions, no crypto and no Android. The two modules do not depend on each other in Phase 1.

---

## Task 0: Project scaffold (multi-module Gradle)

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `core-crypto/build.gradle.kts`
- Create: `core-domain/build.gradle.kts`
- Create: `gradle/wrapper/gradle-wrapper.properties`

- [ ] **Step 1: Create the version catalog** `gradle/libs.versions.toml`

```toml
[versions]
kotlin = "2.0.21"
junit = "5.11.3"
bouncycastle = "1.79"

[libraries]
bouncycastle = { module = "org.bouncycastle:bcprov-jdk18on", version.ref = "bouncycastle" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher", version = "1.11.3" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

- [ ] **Step 2: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "passlock"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(":core-crypto", ":core-domain")
```

- [ ] **Step 3: Create root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
```

- [ ] **Step 4: Create `core-crypto/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.bouncycastle)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(21) }
```

- [ ] **Step 5: Create `core-domain/build.gradle.kts`** (no Bouncy Castle — domain is dependency-free)

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(21) }
```

- [ ] **Step 6: Create the Gradle wrapper**

A user-space Gradle 8.10.2 has been provisioned at `/mnt/nvme2TB/passlock/.tooling/gradle-8.10.2/bin/gradle` (the `.tooling/` dir is gitignored). JDK 21 is the installed toolchain.
Run: `/mnt/nvme2TB/passlock/.tooling/gradle-8.10.2/bin/gradle wrapper --gradle-version 8.10.2`
Expected: creates `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar`. After this, all subsequent commands use `./gradlew` (which auto-downloads its matching distribution on first run).

- [ ] **Step 7: Verify the project configures**

Run: `./gradlew projects`
Expected: lists `+--- Project ':core-crypto'` and `\--- Project ':core-domain'` with no errors.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/ core-crypto/build.gradle.kts core-domain/build.gradle.kts gradlew gradlew.bat
git commit -m "chore: scaffold multi-module Gradle project (core-crypto, core-domain)"
```

---

## Task 1: KdfParams (Argon2id parameters with safety floors)

**Files:**
- Create: `core-crypto/src/main/kotlin/com/passlock/crypto/KdfParams.kt`
- Test: `core-crypto/src/test/kotlin/com/passlock/crypto/KdfParamsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.passlock.crypto

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KdfParamsTest {
    @Test
    fun `daily default meets floors`() {
        val p = KdfParams.DAILY_DEFAULT
        assert(p.memoryKib >= KdfParams.MIN_MEMORY_KIB)
        assert(p.iterations >= KdfParams.MIN_ITERATIONS)
        assert(p.parallelism >= 1)
    }

    @Test
    fun `backup default is stronger than daily`() {
        assert(KdfParams.BACKUP_DEFAULT.memoryKib >= KdfParams.DAILY_DEFAULT.memoryKib)
    }

    @Test
    fun `rejects parameters below the memory floor`() {
        assertThrows(IllegalArgumentException::class.java) {
            KdfParams(memoryKib = 1024, iterations = 3, parallelism = 1)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core-crypto:test --tests "com.passlock.crypto.KdfParamsTest"`
Expected: FAIL — `Unresolved reference: KdfParams`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.passlock.crypto

/** Parameters for the Argon2id key-derivation function, with safety floors. */
data class KdfParams(
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    init {
        require(memoryKib >= MIN_MEMORY_KIB) { "memoryKib $memoryKib below floor $MIN_MEMORY_KIB" }
        require(iterations >= MIN_ITERATIONS) { "iterations $iterations below floor $MIN_ITERATIONS" }
        require(parallelism >= 1) { "parallelism must be >= 1" }
    }

    companion object {
        const val MIN_MEMORY_KIB = 64 * 1024   // 64 MiB floor
        const val MIN_ITERATIONS = 3

        /** Conservative defaults for daily unlock; calibrate per device at runtime. */
        val DAILY_DEFAULT = KdfParams(memoryKib = 64 * 1024, iterations = 3, parallelism = 1)

        /** Stronger params for backups, which may live in riskier locations. */
        val BACKUP_DEFAULT = KdfParams(memoryKib = 256 * 1024, iterations = 4, parallelism = 1)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core-crypto:test --tests "com.passlock.crypto.KdfParamsTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add core-crypto/src/main/kotlin/com/passlock/crypto/KdfParams.kt core-crypto/src/test/kotlin/com/passlock/crypto/KdfParamsTest.kt
git commit -m "feat(crypto): add Argon2id KdfParams with safety floors"
```

---

## Task 2: CryptoEngine interface + Argon2id key derivation

**Files:**
- Create: `core-crypto/src/main/kotlin/com/passlock/crypto/CryptoEngine.kt`
- Create: `core-crypto/src/main/kotlin/com/passlock/crypto/BouncyCastleCryptoEngine.kt`
- Test: `core-crypto/src/test/kotlin/com/passlock/crypto/Argon2KdfTest.kt`

> Note: we test the *integration behaviour* of Argon2id (deterministic, correct length, salt-sensitive, params-sensitive). Bouncy Castle itself is validated against the RFC 9106 vectors upstream; we pin our use of it.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.passlock.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class Argon2KdfTest {
    private val engine = BouncyCastleCryptoEngine()
    private val params = KdfParams.DAILY_DEFAULT
    private val salt = ByteArray(16) { it.toByte() }

    @Test
    fun `derives a 32-byte key`() {
        val key = engine.deriveKey("correct horse".toCharArray(), salt, params)
        assertEquals(32, key.size)
    }

    @Test
    fun `is deterministic for the same inputs`() {
        val a = engine.deriveKey("pw".toCharArray(), salt, params)
        val b = engine.deriveKey("pw".toCharArray(), salt, params)
        assertArrayEquals(a, b)
    }

    @Test
    fun `different salt yields a different key`() {
        val a = engine.deriveKey("pw".toCharArray(), salt, params)
        val b = engine.deriveKey("pw".toCharArray(), ByteArray(16) { (it + 1).toByte() }, params)
        assertFalse(a.contentEquals(b))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core-crypto:test --tests "com.passlock.crypto.Argon2KdfTest"`
Expected: FAIL — `Unresolved reference: BouncyCastleCryptoEngine`.

- [ ] **Step 3: Write the interface**, `CryptoEngine.kt`

```kotlin
package com.passlock.crypto

/**
 * Cryptographic primitives behind a narrow, swappable interface.
 * Pure-JVM so it behaves identically in unit tests and on-device.
 */
interface CryptoEngine {
    /** Derive a 256-bit key from a passphrase using Argon2id. */
    fun deriveKey(passphrase: CharArray, salt: ByteArray, params: KdfParams): ByteArray

    /** AEAD-encrypt. Returns nonce || ciphertext-with-tag. */
    fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /** AEAD-decrypt. Throws on tampering or wrong key. */
    fun aeadDecrypt(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray

    /** Cryptographically-random bytes (e.g. a fresh DEK). */
    fun randomBytes(length: Int): ByteArray

    /** Overwrite key material in place. */
    fun zeroize(bytes: ByteArray)
}
```

- [ ] **Step 4: Write the implementation** (deriveKey + the other members as stubs that throw, filled in next task), `BouncyCastleCryptoEngine.kt`

```kotlin
package com.passlock.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom

class BouncyCastleCryptoEngine(
    private val secureRandom: SecureRandom = SecureRandom(),
) : CryptoEngine {

    override fun deriveKey(passphrase: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val argonParams = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(params.iterations)
            .withMemoryAsKB(params.memoryKib)
            .withParallelism(params.parallelism)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator().apply { init(argonParams) }
        val key = ByteArray(KEY_LEN)
        generator.generateBytes(passphrase, key)
        return key
    }

    override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        TODO("implemented in Task 3")

    override fun aeadDecrypt(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray =
        TODO("implemented in Task 3")

    override fun randomBytes(length: Int): ByteArray =
        ByteArray(length).also(secureRandom::nextBytes)

    override fun zeroize(bytes: ByteArray) = bytes.fill(0)

    companion object {
        const val KEY_LEN = 32       // 256-bit
        const val NONCE_LEN = 12     // 96-bit GCM nonce
        const val TAG_BITS = 128
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core-crypto:test --tests "com.passlock.crypto.Argon2KdfTest"`
Expected: PASS (3 tests). (Argon2id at 64 MiB takes a moment — this is intentional.)

- [ ] **Step 6: Commit**

```bash
git add core-crypto/src/main/kotlin/com/passlock/crypto/CryptoEngine.kt core-crypto/src/main/kotlin/com/passlock/crypto/BouncyCastleCryptoEngine.kt core-crypto/src/test/kotlin/com/passlock/crypto/Argon2KdfTest.kt
git commit -m "feat(crypto): add CryptoEngine interface and Argon2id key derivation"
```

---

## Task 3: AES-256-GCM authenticated encryption (with tamper detection)

**Files:**
- Modify: `core-crypto/src/main/kotlin/com/passlock/crypto/BouncyCastleCryptoEngine.kt` (replace the two `TODO` members)
- Test: `core-crypto/src/test/kotlin/com/passlock/crypto/AeadTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.passlock.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AeadTest {
    private val engine = BouncyCastleCryptoEngine()
    private val key = ByteArray(32) { 7 }
    private val aad = "v1".toByteArray()

    @Test
    fun `round-trips plaintext`() {
        val pt = "credit card pin 4821".toByteArray()
        val blob = engine.aeadEncrypt(key, pt, aad)
        assertArrayEquals(pt, engine.aeadDecrypt(key, blob, aad))
    }

    @Test
    fun `encrypting twice yields different ciphertext (random nonce)`() {
        val pt = "same".toByteArray()
        val a = engine.aeadEncrypt(key, pt, aad)
        val b = engine.aeadEncrypt(key, pt, aad)
        assert(!a.contentEquals(b))
    }

    @Test
    fun `flipping a ciphertext byte fails authentication`() {
        val blob = engine.aeadEncrypt(key, "secret".toByteArray(), aad)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        assertThrows(Exception::class.java) { engine.aeadDecrypt(key, blob, aad) }
    }

    @Test
    fun `wrong key fails authentication`() {
        val blob = engine.aeadEncrypt(key, "secret".toByteArray(), aad)
        val wrong = ByteArray(32) { 9 }
        assertThrows(Exception::class.java) { engine.aeadDecrypt(wrong, blob, aad) }
    }

    @Test
    fun `mismatched aad fails authentication`() {
        val blob = engine.aeadEncrypt(key, "secret".toByteArray(), aad)
        assertThrows(Exception::class.java) { engine.aeadDecrypt(key, blob, "v2".toByteArray()) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core-crypto:test --tests "com.passlock.crypto.AeadTest"`
Expected: FAIL — `kotlin.NotImplementedError` from the `TODO` in `aeadEncrypt`.

- [ ] **Step 3: Replace the two `TODO` members in `BouncyCastleCryptoEngine.kt`**

Add these imports at the top of the file:

```kotlin
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
```

Replace the two `TODO(...)` method bodies with:

```kotlin
    override fun aeadEncrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LEN).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    override fun aeadDecrypt(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size >= NONCE_LEN + TAG_BITS / 8) { "blob too short" }
        val nonce = blob.copyOfRange(0, NONCE_LEN)
        val ciphertext = blob.copyOfRange(NONCE_LEN, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)   // throws AEADBadTagException on tamper/wrong key/aad
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core-crypto:test --tests "com.passlock.crypto.AeadTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core-crypto/src/main/kotlin/com/passlock/crypto/BouncyCastleCryptoEngine.kt core-crypto/src/test/kotlin/com/passlock/crypto/AeadTest.kt
git commit -m "feat(crypto): add AES-256-GCM AEAD with tamper detection"
```

---

## Task 4: PassphraseWrap (the DEK inner-wrap)

This is the password-derived inner layer from spec §6.1: `AEAD(Argon2id(passphrase), DEK)`. The Keystore outer-wrap is Phase 2.

**Files:**
- Create: `core-crypto/src/main/kotlin/com/passlock/crypto/PassphraseWrap.kt`
- Test: `core-crypto/src/test/kotlin/com/passlock/crypto/PassphraseWrapTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.passlock.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PassphraseWrapTest {
    private val engine = BouncyCastleCryptoEngine()
    private val salt = ByteArray(16) { 3 }
    private val params = KdfParams.DAILY_DEFAULT
    private val dek = ByteArray(32) { (it * 5).toByte() }

    @Test
    fun `unwrap recovers the original DEK`() {
        val wrapped = PassphraseWrap.wrap(engine, "master-pw".toCharArray(), salt, params, dek)
        val recovered = PassphraseWrap.unwrap(engine, "master-pw".toCharArray(), salt, params, wrapped)
        assertArrayEquals(dek, recovered)
    }

    @Test
    fun `wrong passphrase cannot unwrap`() {
        val wrapped = PassphraseWrap.wrap(engine, "master-pw".toCharArray(), salt, params, dek)
        assertThrows(Exception::class.java) {
            PassphraseWrap.unwrap(engine, "WRONG".toCharArray(), salt, params, wrapped)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core-crypto:test --tests "com.passlock.crypto.PassphraseWrapTest"`
Expected: FAIL — `Unresolved reference: PassphraseWrap`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.passlock.crypto

/**
 * Wraps/unwraps a Data Encryption Key under a key derived from a passphrase.
 * The KDF version tag is bound as AAD so a future algorithm change is authenticated.
 */
object PassphraseWrap {
    private val AAD = "passlock.wrap.v1".toByteArray()

    fun wrap(
        engine: CryptoEngine,
        passphrase: CharArray,
        salt: ByteArray,
        params: KdfParams,
        dek: ByteArray,
    ): ByteArray {
        val kek = engine.deriveKey(passphrase, salt, params)
        try {
            return engine.aeadEncrypt(kek, dek, AAD)
        } finally {
            engine.zeroize(kek)
        }
    }

    fun unwrap(
        engine: CryptoEngine,
        passphrase: CharArray,
        salt: ByteArray,
        params: KdfParams,
        wrapped: ByteArray,
    ): ByteArray {
        val kek = engine.deriveKey(passphrase, salt, params)
        try {
            return engine.aeadDecrypt(kek, wrapped, AAD)
        } finally {
            engine.zeroize(kek)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core-crypto:test --tests "com.passlock.crypto.PassphraseWrapTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Run the whole crypto module and commit**

Run: `./gradlew :core-crypto:test`
Expected: PASS (all crypto tests green).

```bash
git add core-crypto/src/main/kotlin/com/passlock/crypto/PassphraseWrap.kt core-crypto/src/test/kotlin/com/passlock/crypto/PassphraseWrapTest.kt
git commit -m "feat(crypto): add passphrase-derived DEK wrap/unwrap (inner layer)"
```

---

## Task 5: Domain models + template factory

**Files:**
- Create: `core-domain/src/main/kotlin/com/passlock/domain/Models.kt`
- Create: `core-domain/src/main/kotlin/com/passlock/domain/Templates.kt`
- Test: `core-domain/src/test/kotlin/com/passlock/domain/TemplatesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.passlock.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class TemplatesTest {
    private val counter = AtomicInteger(0)
    private val idGen = { "f${counter.incrementAndGet()}" }

    @Test
    fun `credit card template has the expected fields with secrets marked`() {
        val fields = Templates.defaultFields(Template.CREDIT_CARD, idGen)
        assertEquals(listOf("Card number", "Expiry", "CVV", "PIN", "Cardholder"), fields.map { it.label })
        assertTrue(fields.first { it.label == "CVV" }.isSecret)
        assertTrue(fields.first { it.label == "PIN" }.isSecret)
        assertTrue(!fields.first { it.label == "Card number" }.isSecret)
    }

    @Test
    fun `custom template starts empty`() {
        assertEquals(emptyList<Field>(), Templates.defaultFields(Template.CUSTOM, idGen))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core-domain:test --tests "com.passlock.domain.TemplatesTest"`
Expected: FAIL — `Unresolved reference: Template`.

- [ ] **Step 3: Write `Models.kt`**

```kotlin
package com.passlock.domain

enum class FieldType { TEXT, PASSWORD, PIN, NUMBER, TOTP_SEED }

enum class Template { LOGIN, CREDIT_CARD, BANK, SECURE_NOTE, CUSTOM }

data class Field(
    val id: String,
    val label: String,
    val value: String,
    val type: FieldType,
    val isSecret: Boolean,
)

data class Item(
    val id: String,
    val title: String,
    val template: Template,
    val fields: List<Field>,
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
    val primaryFieldId: String? = null,
    val icon: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Vault(
    val items: List<Item> = emptyList(),
)
```

- [ ] **Step 4: Write `Templates.kt`**

```kotlin
package com.passlock.domain

/** Default starting fields for each template. Every field stays editable after creation. */
object Templates {
    fun defaultFields(template: Template, idGen: () -> String): List<Field> = when (template) {
        Template.LOGIN -> listOf(
            Field(idGen(), "Username", "", FieldType.TEXT, isSecret = false),
            Field(idGen(), "Password", "", FieldType.PASSWORD, isSecret = true),
            Field(idGen(), "URL", "", FieldType.TEXT, isSecret = false),
        )
        Template.CREDIT_CARD -> listOf(
            Field(idGen(), "Card number", "", FieldType.NUMBER, isSecret = false),
            Field(idGen(), "Expiry", "", FieldType.TEXT, isSecret = false),
            Field(idGen(), "CVV", "", FieldType.PIN, isSecret = true),
            Field(idGen(), "PIN", "", FieldType.PIN, isSecret = true),
            Field(idGen(), "Cardholder", "", FieldType.TEXT, isSecret = false),
        )
        Template.BANK -> listOf(
            Field(idGen(), "Account", "", FieldType.NUMBER, isSecret = false),
            Field(idGen(), "IBAN", "", FieldType.TEXT, isSecret = false),
            Field(idGen(), "PIN", "", FieldType.PIN, isSecret = true),
        )
        Template.SECURE_NOTE -> listOf(
            Field(idGen(), "Note", "", FieldType.TEXT, isSecret = true),
        )
        Template.CUSTOM -> emptyList()
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core-domain:test --tests "com.passlock.domain.TemplatesTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add core-domain/src/main/kotlin/com/passlock/domain/Models.kt core-domain/src/main/kotlin/com/passlock/domain/Templates.kt core-domain/src/test/kotlin/com/passlock/domain/TemplatesTest.kt
git commit -m "feat(domain): add data model and template field factory"
```

---

## Task 6: Offline TOTP generator (RFC 6238)

**Files:**
- Create: `core-domain/src/main/kotlin/com/passlock/domain/Totp.kt`
- Test: `core-domain/src/test/kotlin/com/passlock/domain/TotpTest.kt`

> Test data are the official RFC 6238 Appendix B vectors (SHA-1, seed = ASCII `"12345678901234567890"`, period 30, 8 digits).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.passlock.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TotpTest {
    private val seed = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    @Test
    fun `matches RFC 6238 vector at T=59`() {
        assertEquals("94287082", Totp.generate(seed, timeSeconds = 59, period = 30, digits = 8))
    }

    @Test
    fun `matches RFC 6238 vector at T=1111111109`() {
        assertEquals("07081804", Totp.generate(seed, timeSeconds = 1111111109, period = 30, digits = 8))
    }

    @Test
    fun `produces 6 digits by default`() {
        assertEquals(6, Totp.generate(seed, timeSeconds = 59).length)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core-domain:test --tests "com.passlock.domain.TotpTest"`
Expected: FAIL — `Unresolved reference: Totp`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.passlock.domain

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

enum class TotpAlgorithm(val macName: String) {
    SHA1("HmacSHA1"), SHA256("HmacSHA256"), SHA512("HmacSHA512")
}

/** RFC 6238 Time-based One-Time Passwords, computed on demand and never stored. */
object Totp {
    fun generate(
        secret: ByteArray,
        timeSeconds: Long,
        period: Int = 30,
        digits: Int = 6,
        algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    ): String {
        val counter = timeSeconds / period
        val msg = ByteArray(8)
        var v = counter
        for (i in 7 downTo 0) {
            msg[i] = (v and 0xff).toByte()
            v = v shr 8
        }
        val mac = Mac.getInstance(algorithm.macName)
        mac.init(SecretKeySpec(secret, algorithm.macName))
        val hash = mac.doFinal(msg)
        val offset = (hash[hash.size - 1] and 0x0f).toInt()
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        var pow = 1
        repeat(digits) { pow *= 10 }
        return (binary % pow).toString().padStart(digits, '0')
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core-domain:test --tests "com.passlock.domain.TotpTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add core-domain/src/main/kotlin/com/passlock/domain/Totp.kt core-domain/src/test/kotlin/com/passlock/domain/TotpTest.kt
git commit -m "feat(domain): add RFC 6238 offline TOTP generator"
```

---

## Task 7: Password / PIN generator

**Files:**
- Create: `core-domain/src/main/kotlin/com/passlock/domain/PasswordGenerator.kt`
- Test: `core-domain/src/test/kotlin/com/passlock/domain/PasswordGeneratorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.passlock.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PasswordGeneratorTest {
    private val gen = PasswordGenerator()

    @Test
    fun `respects requested length`() {
        val pw = gen.generate(PasswordPolicy(length = 24))
        assertEquals(24, pw.length)
    }

    @Test
    fun `digits-only policy produces only digits`() {
        val pin = gen.generate(PasswordPolicy(length = 6, lower = false, upper = false, digits = true, symbols = false))
        assertEquals(6, pin.length)
        assert(pin.all { it.isDigit() })
    }

    @Test
    fun `includes at least one of every selected class`() {
        repeat(50) {
            val pw = gen.generate(PasswordPolicy(length = 8))
            assert(pw.any { it.isLowerCase() })
            assert(pw.any { it.isUpperCase() })
            assert(pw.any { it.isDigit() })
            assert(pw.any { !it.isLetterOrDigit() })
        }
    }

    @Test
    fun `rejects a policy with no character classes`() {
        assertThrows(IllegalArgumentException::class.java) {
            gen.generate(PasswordPolicy(length = 8, lower = false, upper = false, digits = false, symbols = false))
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core-domain:test --tests "com.passlock.domain.PasswordGeneratorTest"`
Expected: FAIL — `Unresolved reference: PasswordGenerator`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.passlock.domain

import java.security.SecureRandom

data class PasswordPolicy(
    val length: Int = 20,
    val lower: Boolean = true,
    val upper: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
) {
    init { require(length >= 4) { "length must be >= 4" } }
}

class PasswordGenerator(private val random: SecureRandom = SecureRandom()) {
    fun generate(policy: PasswordPolicy): String {
        val pools = buildList {
            if (policy.lower) add("abcdefghijklmnopqrstuvwxyz")
            if (policy.upper) add("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            if (policy.digits) add("0123456789")
            if (policy.symbols) add("!@#\$%^&*()-_=+[]{};:,.?")
        }
        require(pools.isNotEmpty()) { "at least one character class required" }
        require(policy.length >= pools.size) { "length too short to include every class" }

        val all = pools.joinToString("")
        val chars = CharArray(policy.length)
        // guarantee one from each selected pool
        for (i in pools.indices) chars[i] = pools[i][random.nextInt(pools[i].length)]
        // fill the rest from the combined pool
        for (i in pools.size until policy.length) chars[i] = all[random.nextInt(all.length)]
        // Fisher-Yates shuffle so the guaranteed chars aren't positional
        for (i in chars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]; chars[i] = chars[j]; chars[j] = tmp
        }
        return String(chars)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core-domain:test --tests "com.passlock.domain.PasswordGeneratorTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add core-domain/src/main/kotlin/com/passlock/domain/PasswordGenerator.kt core-domain/src/test/kotlin/com/passlock/domain/PasswordGeneratorTest.kt
git commit -m "feat(domain): add policy-driven password/PIN generator"
```

---

## Task 8: Vault search / filter (never matches secret values)

**Files:**
- Create: `core-domain/src/main/kotlin/com/passlock/domain/VaultSearch.kt`
- Test: `core-domain/src/test/kotlin/com/passlock/domain/VaultSearchTest.kt`

> Security note: search matches titles, tags, and the **labels of non-secret fields** — never the *values* of secret fields. We don't index secrets.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.passlock.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VaultSearchTest {
    private fun item(
        id: String, title: String, template: Template = Template.LOGIN,
        favorite: Boolean = false, tags: List<String> = emptyList(),
        fields: List<Field> = emptyList(),
    ) = Item(id, title, template, fields, tags, favorite, null, null, 0, 0)

    private val items = listOf(
        item("1", "Main Bank", Template.BANK, tags = listOf("finance")),
        item("2", "Email", favorite = true),
        item(
            "3", "Visa", Template.CREDIT_CARD,
            fields = listOf(Field("f1", "PIN", "4821", FieldType.PIN, isSecret = true)),
        ),
    )

    @Test
    fun `text matches title case-insensitively`() {
        assertEquals(listOf("1"), VaultSearch.filter(items, SearchQuery(text = "bank")).map { it.id })
    }

    @Test
    fun `favorites-only filters to favorites`() {
        assertEquals(listOf("2"), VaultSearch.filter(items, SearchQuery(favoritesOnly = true)).map { it.id })
    }

    @Test
    fun `template filter narrows by type`() {
        assertEquals(listOf("3"), VaultSearch.filter(items, SearchQuery(template = Template.CREDIT_CARD)).map { it.id })
    }

    @Test
    fun `does not match on secret field values`() {
        assertEquals(emptyList<String>(), VaultSearch.filter(items, SearchQuery(text = "4821")).map { it.id })
    }

    @Test
    fun `empty query returns everything`() {
        assertEquals(listOf("1", "2", "3"), VaultSearch.filter(items, SearchQuery()).map { it.id })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core-domain:test --tests "com.passlock.domain.VaultSearchTest"`
Expected: FAIL — `Unresolved reference: VaultSearch`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.passlock.domain

data class SearchQuery(
    val text: String = "",
    val template: Template? = null,
    val favoritesOnly: Boolean = false,
    val tag: String? = null,
)

object VaultSearch {
    fun filter(items: List<Item>, query: SearchQuery): List<Item> {
        val needle = query.text.trim().lowercase()
        return items.filter { item ->
            val textMatch = needle.isEmpty() ||
                item.title.lowercase().contains(needle) ||
                item.tags.any { it.lowercase().contains(needle) } ||
                item.fields.any { !it.isSecret && it.label.lowercase().contains(needle) }
            val templateMatch = query.template == null || item.template == query.template
            val favoriteMatch = !query.favoritesOnly || item.favorite
            val tagMatch = query.tag == null || item.tags.contains(query.tag)
            textMatch && templateMatch && favoriteMatch && tagMatch
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core-domain:test --tests "com.passlock.domain.VaultSearchTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Run the full build and commit**

Run: `./gradlew test`
Expected: PASS — all `:core-crypto` and `:core-domain` tests green.

```bash
git add core-domain/src/main/kotlin/com/passlock/domain/VaultSearch.kt core-domain/src/test/kotlin/com/passlock/domain/VaultSearchTest.kt
git commit -m "feat(domain): add vault search/filter that never indexes secret values"
```

---

## Phase 1 done — definition of done

- [ ] `./gradlew test` is green for both modules.
- [ ] `:core-crypto` provides: Argon2id KDF (with floors), AES-256-GCM AEAD (tamper-detecting), random DEK generation, zeroize, and passphrase DEK wrap/unwrap.
- [ ] `:core-domain` provides: data model, template factory, RFC-6238 TOTP, password generator, and secret-safe search.
- [ ] No Android dependencies in either module (they build with only the Kotlin JVM plugin).
- [ ] Every unit is covered by a behavioural test committed alongside it.

**Next:** Phase 2 (`:data`) — wrap the DEK a second time with a hardware-backed Android Keystore key (the outer layer of spec §6.1), add biometric gating, and persist the encrypted vault document atomically. That plan is written just before we execute it.
