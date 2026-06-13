# PassLock — Design Specification

- **Date:** 2026-06-13
- **Status:** Approved (design phase) — ready for implementation planning
- **Platform:** Android (single phone, fully offline)
- **One-line:** A native, offline, hardware-backed encrypted vault for a person's most sensitive secrets — credit-card PINs, passwords, 2FA seeds — with a premium OLED UI and one-tap convenience.

---

## 1. Goals

In priority order, as set by the owner:

1. **Ultra-secure.** Encrypt everything; protect against a realistic worst-case consumer attacker; minimise the trusted code/dependency surface.
2. **Effortless UX.** A clean table of secrets with one-tap copy, tap-to-reveal, and other low-friction tricks. "Dope look."
3. **Offline by construction.** No network, ever — both for privacy and to shrink the attack surface.
4. **Always gated.** Authentication required on every cold start; no plaintext ever leaves the device unencrypted.
5. **Lean.** As few third-party packages as possible; prefer platform and one world-class audited crypto library over many dependencies.

## 2. Threat model

**Defending against:** a **forensic-grade attacker who has physical possession of the device** (can power it off, image storage, and attempt offline brute-force) **plus on-device malware** (a hostile app attempting to read PassLock's data, clipboard, or screen).

**Explicitly accepted as out of practical reach:** a funded, targeted nation-state adversary using device-specific zero-days or hardware implants. The design raises the bar as high as a consumer app can and pairs it with operational guidance, but does not claim to defeat this class.

**Core defensive consequence:** there must be **no password-only ciphertext stored on the device**, so that imaging storage yields nothing brute-forceable without the phone's non-extractable hardware key. (See §6.)

## 3. Non-goals (YAGNI)

Cloud sync; user accounts; cross-platform / iOS; secret sharing; online breach-checking; Wear OS; and Android Autofill-into-other-apps. Autofill is a plausible future addition but widens the attack surface and is excluded from v1.

## 4. Technology choices

- **Language / UI:** Kotlin + Jetpack Compose. Single language, single `Activity`, native look and motion.
- **Min/Target SDK:** target a modern Android with hardware-backed Keystore; gracefully degrade on older devices (see hardware fallback ladder, §12).
- **Cryptography:** one audited library rather than many. **Primary recommendation: libsodium** (via a thin JNI binding) — it provides XChaCha20-Poly1305 (AEAD), Argon2id (KDF), and secure memory zeroing in a single, world-class, audited C library, which fits the "lean + close to the metal" goal. Acceptable alternative: Google Tink (AEAD) + a vetted Argon2 binding. **We never hand-roll primitives.**
- **Hardware key custody:** Android Keystore (StrongBox where available, else TEE).
- **No `INTERNET` permission** declared at all.

## 5. Architecture & module layout

Single-Activity Compose app in clean layers; the crypto module is walled off behind a small interface so the algorithm is swappable and unit-testable in isolation.

```
:app          Compose UI, navigation, theme (Stealth OLED), ViewModels
:core-crypto  pure JVM — KDF, AEAD, key hierarchy (no Android deps → fast JVM tests)
:core-domain  models + use-cases (pure Kotlin)
:data         encrypted-file storage, Keystore/biometric adapter, settings store
```

Crypto interface (illustrative):

```
interface CryptoEngine {
    fun deriveKey(passphrase: CharArray, salt: ByteArray, params: KdfParams): SecretKey
    fun aeadEncrypt(key: SecretKey, plaintext: ByteArray, aad: ByteArray): ByteArray
    fun aeadDecrypt(key: SecretKey, ciphertext: ByteArray, aad: ByteArray): ByteArray
    fun zeroize(material: ByteArray)
}
```

## 6. Security & cryptography design

### 6.1 Key hierarchy

The vault is **never** encrypted directly with the master password. A random **Data Encryption Key (DEK)** encrypts the vault; the DEK is what we protect. Indirection lets two independent factors guard one vault and makes password changes an instant re-wrap.

- **DEK** — random 256-bit key; encrypts the vault document with AEAD (XChaCha20-Poly1305 preferred for its large random nonce; AES-256-GCM acceptable).
- **At rest, the DEK is double-wrapped:**

  ```
  stored_blob = Keystore_hw( AEAD( Argon2id(master_password, salt), DEK ) )
  ```

  - **Inner layer** requires the **master password**.
  - **Outer layer** requires the **hardware key** (non-extractable, StrongBox/TEE).
  - **Therefore no password-only ciphertext exists on the device** → an attacker imaging storage cannot begin an offline brute-force of the master password without the hardware key, which the secure element will not release. This is the central anti-forensic property.

- **Biometric quick-unlock** uses a *separate* Keystore key created with user-authentication-required; it re-releases the in-session DEK for fast re-entry. It is never the cold-start root of trust.

### 6.2 Algorithms & parameters

- **AEAD:** XChaCha20-Poly1305 (192-bit random nonce removes nonce-reuse bookkeeping) or AES-256-GCM.
- **KDF:** Argon2id. Parameters **calibrated at first run** to a target unlock time of ~0.5–1.0 s on the device, with a hard floor (e.g. ≥ 64 MiB memory, ≥ 3 iterations, parallelism 1). Backup uses **stronger** parameters than daily unlock (backups may be stored in riskier locations).
- **Post-quantum posture:** symmetric-only design → already quantum-resistant (AES-256 / XChaCha20 retain ~128-bit strength under Grover). No asymmetric crypto in the core. The `CryptoEngine` boundary keeps a PQC KEM (e.g. ML-KEM) addable *only if* a future public-key feature is introduced.

### 6.3 Backup cryptography

The backup is the only artefact that leaves the secure element, so it cannot use the hardware key (it must restore on a new device). It is encrypted with a key derived from a **separate, dedicated recovery passphrase**:

```
backup_file = header || AEAD( Argon2id(recovery_passphrase, salt_bk, strong_params), DEK_and_vault )
```

The recovery passphrase therefore carries the full security weight of the backup — by design it is distinct from and stronger than the daily master password.

## 7. Authentication & unlock policy

- **Configurable policy**, but *something* is always required at cold start (app restart, reboot, or long idle timeout).
- **Secure default:** master password at cold start; biometric for quick re-unlock within the session/timeout.
- The user may change this in Settings (e.g. biometric-at-every-start), with an explicit in-app warning when downgrading from the secure default.
- **Throttling:** escalating delay (1s, 2s, 4s, 8s…) starting **after the 2nd** wrong attempt (first miss is free).
- **Optional auto-wipe** after N consecutive failures (opt-in, off by default).
- **Duress / decoy password:** a distinct password that opens a separate, innocuous decoy vault while the real vault stays sealed.

## 8. Data model (in memory when unlocked)

```
Vault   = settings + List<Item>
Item    = id, title, template, tags[], favorite, primaryFieldId, icon,
          createdAt, updatedAt, List<Field>
Field   = id, label, value, type, isSecret
Field.type ∈ { TEXT, PASSWORD, PIN, NUMBER, TOTP_SEED }
Template ∈ { Login, CreditCard, Bank, SecureNote, Custom }
```

- **Templates** are just a starting set of fields; every field is renamable/removable after creation (templates → editable key-values).
- **`primaryFieldId`** is the field copied by a row's one-tap ⧉.
- **`TOTP_SEED`** powers offline 2FA: the rotating 6-digit code is computed on demand and never persisted.

## 9. Storage

The vault is **one AEAD-encrypted document**, decrypted into memory on unlock and re-written **atomically** (write-temp + rename) on save. No SQL engine / no SQLCipher — keeps the dependency surface minimal. A personal vault is small (hundreds of entries), so in-memory search is instant and full-file rewrite is cheap. *(If thousands of entries were ever expected, the alternative is Room + field-level encryption; not planned for v1.)* Settings are stored separately; any sensitive settings are encrypted.

## 10. Screens & navigation

1. **Unlock** — true-black, emerald accent; master-password field + biometric prompt per policy; throttle countdown after the 2nd wrong attempt; silent duress-password check.
2. **Vault (home)** — **flat expandable table by default**: search bar, filter chips (All / Cards / Logins / Bank / Notes / Favorites), rows expand inline to reveal key-values with per-value copy/reveal; FAB to add. A Settings toggle switches to **list → detail** mode.
3. **Item editor** — choose template (or Custom) → editable key-value rows; mark fields secret; set the primary field; add a TOTP seed; invoke the password generator.
4. **Settings** — unlock policy, auto-lock timeout, clipboard-clear seconds, theme (dark/light/system), home mode (table/list), backup & restore, change master/recovery passphrase, auto-wipe toggle, duress setup, root-warning toggle.

## 11. Signature interactions

- **One-tap copy (⧉)** of any value; clipboard **auto-clears** after a configurable timeout *and* on lock; copies flagged "sensitive" so they don't appear in clipboard history/preview (Android 13+).
- **Tap-to-reveal (👁️)** for masked secrets; **biometric-gated reveal/copy** for fields marked extra-sensitive (e.g. card PIN), requiring fresh auth even mid-session.
- **Offline TOTP** rotating codes with a countdown ring.
- **Password / PIN generator** with length and character-set controls.
- **Tags & favorites**; instant in-memory search and filtering.

## 12. Security hardening

**Baseline (always on):**

- **No `INTERNET` permission** — OS-enforced; asserted by a build test. `usesCleartextTraffic=false`.
- **`FLAG_SECURE`** on all windows — no screenshots, no screen recording, blank recents thumbnail.
- **Auto-lock** on background/screen-off (timeout configurable, default short); locking **zeroizes** the DEK and plaintext from memory.
- **Clipboard auto-clear** + sensitive-content flag.
- **Secrets masked by default**, tap-to-reveal.
- **Hardware-wrapped DEK**; biometric cryptographically bound to a Keystore key (not a UI check).
- **Memory hygiene** — secrets in `byte[]`/`char[]`, zeroized after use; minimise plaintext lifetime.
- **Zero trackers / analytics / third-party SDKs.**

**Optional (owner opted in to all):**

- **Failed-attempt throttling** (after the 2nd attempt).
- **Auto-wipe** after N failures (opt-in).
- **Duress / decoy** password.
- **Root / tamper detection** — non-blocking warning by default.

**Hardware fallback ladder:** StrongBox → TEE → (very old devices) software keystore **with an explicit warning** that the forensic guarantee is reduced.

## 13. Backup & restore flow

- **Export:** derive key from the recovery passphrase (Argon2id, strong params) → write a single **versioned, AEAD-encrypted `.passlock` file** via Android's file picker (Storage Access Framework). The app never uploads; the user chooses the destination.
- **Restore:** install on any device → import file → enter recovery passphrase → vault is re-wrapped under the *new* device's hardware key.
- The file header carries format version + KDF parameters for forward compatibility.
- **Encrypted-only:** the app never writes secrets to disk unencrypted (no plaintext export).

## 14. Settings inventory

Unlock policy · auto-lock timeout · clipboard-clear seconds · theme (dark/light/system) · home mode (table/list) · backup & restore · change master password · change recovery passphrase · auto-wipe (on/off + N) · duress setup · root-warning (on/off).

## 15. Error handling & edge cases

- **Forgotten master password = no backdoor** (zero-knowledge by design). The recovery-passphrase backup is the only escape hatch — stated prominently at setup.
- **Biometric re-enrollment** invalidates the biometric key → silent fallback to password (intended security behaviour).
- **Corrupt/tampered vault** → AEAD authentication fails → refuse to load, offer restore. Atomic writes prevent half-written files.
- **Locked mid-edit** → committed items are safe; an in-progress unsaved field is discarded rather than retained in cleartext.
- **No hardware keystore at all** → warn and degrade (see fallback ladder).

## 16. Testing strategy

- **Crypto (`:core-crypto`):** known-answer test vectors for AEAD + Argon2id; wrap/unwrap round-trips; **tamper test** (flip one ciphertext byte → decryption must fail).
- **Domain (`:core-domain`):** pure JVM unit tests for use-cases.
- **ViewModels:** lock-state transitions, throttle escalation, auto-lock timer, auto-wipe trigger.
- **Instrumented (on-device):** Keystore/biometric binding, `FLAG_SECURE`, backup export→import round-trip, decoy-vault separation.
- **Build assertion:** merged manifest contains **no `INTERNET` permission**.

## 17. Open questions / future considerations

- Final pick between libsodium and Tink+Argon2 binding (lean toward libsodium) — decided at implementation start.
- Exact Argon2id calibration target and floors per device class.
- Android Autofill service as a future, opt-in capability (out of scope for v1).
- Optional PQC KEM layer for backups *if* a public-key recovery/sharing feature is ever added.

## Appendix — Decision log

| Decision | Choice |
|---|---|
| Threat model | Forensic-grade + on-device malware |
| Stack | Native Kotlin + Jetpack Compose + hardware Keystore |
| Unlock | Configurable; default password-at-cold-start + biometric re-unlock; always gated |
| Crypto | Symmetric, lean, swappable (AES-256-GCM / XChaCha20-Poly1305 + Argon2id) |
| PQ posture | Quantum-safe by construction; PQC addable only if public-key features appear |
| Data model | Templates → editable key-value entries |
| Home screen | Flat expandable table (default) + search/filters; list→detail mode toggle |
| Storage | Single AEAD-encrypted document (no SQL engine) |
| Hardening | Full baseline + throttle (after 2nd) + opt-in auto-wipe + duress + root warning |
| Power features | Offline TOTP, password generator, biometric-gated reveal, tags & favorites |
| Backup | Encrypted-only; separate dedicated recovery passphrase |
| Theme | Stealth OLED (true-black + emerald); dark/light/system |
