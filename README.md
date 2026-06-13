# 🔐 PassLock

A **fully-offline, hardware-backed encrypted vault** for Android — for your most sensitive secrets: card PINs, passwords, 2FA seeds, notes, and images. Built native (Kotlin + Jetpack Compose), with a true-black "Stealth" OLED design.

> **Why trust it?** A vault's security rests on your master password, the phone's secure element, and (for backups) a Recovery Kit — **never** on the secrecy of the code (Kerckhoffs's principle). PassLock is open-source precisely so you *can* verify there's no backdoor. The one secret that stays private is the APK **signing key**.

## Security model

### On-device vault

- **No `INTERNET` permission at all** — the app physically cannot phone home. Fully offline.
- Each vault is sealed with a random 256-bit Data Encryption Key (DEK). The DEK is wrapped by **Argon2id(master password)** *and* by a **non-extractable Android Keystore key (StrongBox / TEE)** — so a copy of the storage file can't be brute-forced offline; it needs the hardware key, which can't leave the secure element.
- **AEAD:** AES-256-GCM (authenticated — tampering and wrong passwords are rejected, never bypassed). Symmetric-only design → already post-quantum-safe.
- **Unlock:** master password (Argon2id) or **biometrics** (`BiometricPrompt` bound to a Keystore `CryptoObject`) — the biometric prompt appears automatically the moment the lock screen shows. Optional "require password at cold start."
- **`FLAG_SECURE`:** no screenshots, no screen recording, blank app-switcher thumbnail.
- **Auto-lock** on every background / screen-off, and after 5 minutes idle in the foreground, with in-memory key zeroization.
- **Clipboard** copies auto-clear after 30s and are flagged sensitive.
- **Brute-force defenses:** escalating lock-out after repeated wrong attempts; optional auto-wipe after 10 failed attempts.
- **Duress / decoy password:** a second password opens a separate, empty decoy vault under coercion; your real vault stays hidden.
- **Biometric-gated reveal:** mark a field so it requires a fresh fingerprint/face to reveal or copy.
- **Root / tamper warning** (advisory — never blocks).

### Encrypted backups (`.plk`)

A backup is a portable file, so it can't rely on the phone's hardware key and **can't count failed attempts** — an attacker with the file gets unlimited offline guesses. PassLock closes that gap by adding entropy the attacker can't guess:

- **Recovery Kit (default ON):** a 128-bit key generated at export and shown once as a Crockford-Base32 code (`K7QF-2M9X-…`, with a typo-catching checksum). It is mixed into Argon2id as the *secret / pepper* parameter and is **never written to the file**. Restoring needs **both** the kit *and* the passphrase — so even a weak passphrase plus the full source code leaves a 2¹²⁸ wall. (Optional: disable the kit in Settings for passphrase-only backups.)
- **Hardened KDF:** Argon2id at 128 MiB / t=4 for backups (a device-safe amount; the Recovery Kit, not KDF cost, is what makes brute force infeasible).
- **Tamper-evident:** the full header (salt + Argon2 params) is bound as AEAD AAD, so the cost parameters can't be silently downgraded to speed up cracking.
- **Shape-hiding:** the payload is Deflate-compressed and padded to a 4 KiB bucket, so the file size doesn't reveal how many items the vault holds.
- Backups bundle your images too, re-encrypted under the new device key on restore. Older backup formats still import.

## Features

- Searchable vault with filter chips and favorites; templates (Login, Credit Card, Bank, Secure Note, Custom) → fully editable key-value fields
- One-tap copy of a primary field; tap-to-reveal masked secrets
- **Offline TOTP** 2FA codes (Base32 / `otpauth://`), live with a countdown
- Strong password / PIN generator; tags & favorites
- **Encrypted images & gallery** — attach photos to items or keep a standalone gallery (pinch-to-zoom viewer); long-press an image to export a single copy to your phone gallery or delete it with confirmation
- **Encrypted backup & restore** with a separate recovery passphrase + Recovery Kit (see above)
- **Forgot-password reset** — "Erase & start over" from the unlock screen or Settings (behind a typed `ERASE` confirmation) wipes everything and returns to first-run setup, since a forgotten master password is unrecoverable by design
- Modern bottom-sheet menus, (i) help tooltips, dark / light / system theme, adjustable text size

## Install (auto-updating)

The vault stays offline; updates are handled *outside* the app:

1. Install **[Obtainium](https://github.com/ImranR98/Obtainium)** (open-source app updater).
2. Add this repository's URL in Obtainium.
3. Obtainium watches GitHub Releases and installs new signed APKs automatically — like a normal app store, with zero network access inside PassLock.

Or grab the APK directly from the [Releases](../../releases) page.

## Build from source

```bash
./gradlew :app:assembleRelease   # requires keystore.properties for signing
./gradlew test                   # run the crypto + domain unit tests
```

Modules: `:core-crypto` (Argon2id, AES-256-GCM, DEK wrap, Recovery Kit codec — pure JVM, unit-tested), `:core-domain` (models, TOTP, generator, search — pure JVM), `:app` (Compose UI, Keystore/biometric, storage, backup).

## Releases / CI

Pushing a `v*` tag triggers `.github/workflows/release.yml`, which builds a signed release APK and publishes it to GitHub Releases. The signing keystore is supplied via repository secrets (`KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`) and never stored in the repo.
