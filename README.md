# 🔐 PassLock

A **fully-offline, hardware-backed encrypted vault** for Android — for your most sensitive secrets: card PINs, passwords, 2FA seeds, notes. Built native (Kotlin + Jetpack Compose), with a true-black "Stealth" OLED design.

> **Why trust it?** A vault's security rests on your master password and the phone's secure element — **never** on the secrecy of the code (Kerckhoffs's principle). PassLock is open-source precisely so you *can* verify it. The one secret that stays private is the APK **signing key**.

## Security model

- **No `INTERNET` permission at all** — the app physically cannot phone home. Fully offline.
- **Encryption:** each vault is sealed with a random 256-bit Data Encryption Key (DEK). The DEK is wrapped by **Argon2id(master password)** *and* by a **non-extractable Android Keystore key (StrongBox / TEE)** — so a storage image can't be brute-forced offline.
- **AEAD:** AES-256-GCM (authenticated; tampering and wrong passwords are rejected, never bypassed). Symmetric-only design → already post-quantum-safe.
- **Unlock:** master password (Argon2id) or **biometrics** (`BiometricPrompt` bound to a Keystore `CryptoObject`).
- **`FLAG_SECURE`:** no screenshots, no screen recording, blank app-switcher thumbnail.
- **Auto-lock** on background / screen-off, with in-memory key zeroization.
- **Clipboard** copies auto-clear after 20s and are flagged sensitive.

## Features

- Searchable vault with filter chips and favorites
- Templates (Login, Credit Card, Bank, Secure Note, Custom) → fully editable key-value fields
- One-tap copy of a primary field, tap-to-reveal masked secrets
- **Offline TOTP** 2FA codes (Base32 / `otpauth://`), live with a countdown
- Strong password / PIN generator
- Tags & favorites

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

Modules: `:core-crypto` (Argon2id, AES-256-GCM, DEK wrap — pure JVM, unit-tested), `:core-domain` (models, TOTP, generator, search — pure JVM), `:app` (Compose UI, Keystore/biometric, storage).

## Releases / CI

Pushing a `v*` tag triggers `.github/workflows/release.yml`, which builds a signed release APK and publishes it to GitHub Releases. The signing keystore is supplied via repository secrets (`KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`) and never stored in the repo.
