# PassLock Backup Hardening — Design (v0.5.0)

**Goal:** Make an exported backup file infeasible to brute-force offline, even by an
attacker holding the file *and* the full public source code.

**Scope:** The exported `.plk` backup **only**. The on-device vault is untouched — its DEK
is wrapped by a non-extractable StrongBox/TEE key, so a stolen `vault.plk` cannot be
attacked offline regardless of passphrase strength. No on-device crypto changes.

---

## Threat model

| Asset | Attacker has | Today | After v0.5.0 |
|-------|--------------|-------|--------------|
| `vault.plk` (on device) | the file, off-device | Safe — needs the hardware key, which can't leave the secure element | unchanged |
| `passlock-backup.plk` (exported) | the file + full source | **Crackable** — only `Argon2id(passphrase)` stands in the way; a weak passphrase falls to a GPU farm | **Infeasible** — also gated by a 128-bit random Recovery Kit the attacker can't guess |

The core asymmetry: an app can rate-limit and wipe; **a file cannot count attempts** (the
attacker runs their own decryptor against a copy — there is no code of ours in the loop).
So we do not try to limit guesses; we make the guess space unwinnable by adding entropy.

## Design decisions (resolved)

- **Model:** Recovery Kit + passphrase (two independent secrets). Chosen over passphrase-only.
- **Kit format:** Crockford Base32 with a checksum group, e.g. `K7QF-2M9X-4ABD-…`. No new dependency.
- **Kit strength:** 128 bits (the standard infeasible bar; 256+ adds no real safety, 64 is too weak).
- **Default:** Recovery Kit **required** for every backup; a Settings toggle can opt out
  (passphrase-only) with a blunt "GPU-crackable" warning.
- **Rejected:** honey encryption (redundant with the kit and the existing decoy vault),
  on-device crypto changes (already hardware-safe), zxcvbn dependency, per-device Argon2
  calibration. All YAGNI given the kit guarantees entropy.

---

## 1. Two-secret key derivation (core)

New backup format **FORMAT 3**. The AES key is derived from **both** secrets using
Argon2id's built-in *secret / pepper* parameter (`K`):

```
recoveryKey = SecureRandom(16 bytes)        # 128 bits, fresh per export
salt        = SecureRandom(16 bytes)
finalKey    = Argon2id(passphrase, salt, secret=recoveryKey, m=256MiB, t=4, p=1)  # 32B
ciphertext  = AES-256-GCM(finalKey, payload, AAD=header)
```

- Implemented with BouncyCastle `Argon2Parameters.Builder.withSecret(recoveryKey)` —
  **no HKDF, no new dependency.** Without the exact 128-bit kit, Argon2 yields a completely
  different key and GCM authentication fails. Both secrets are mathematically required;
  there is no decision branch to patch out (the public repo cannot weaken this — Kerckhoffs).
- `CryptoEngine.deriveKey` gains an optional `secret: ByteArray = EMPTY`. The on-device
  path passes empty, so its behavior and stored format are unchanged.

## 2. Recovery Kit encoding (`:core-crypto/RecoveryKit.kt`, pure stdlib)

- 128-bit value → Crockford Base32, grouped `XXXX-XXXX-…`, with a checksum group so a
  mistyped character is rejected *before* a decrypt is attempted.
- `RecoveryKit.encode(bytes: ByteArray): String`
- `RecoveryKit.decode(text: String): ByteArray?` — normalizes case/dashes/`O→0`/`I→1`,
  validates the checksum, returns null on any malformation.
- Unit-tested in isolation (pure JVM).

## 3. Tamper-evidence (AAD binding)

The full header (`magic | format | flags | salt | m | t | p`) is bound as the GCM **AAD**.
Editing the cost params, salt, or version to weaken or downgrade the file makes it refuse to
decrypt (`AEADBadTagException`). A file can't self-destruct, but it can't be silently
weakened either.

## 4. Argon2 cost bump for backups

`KdfParams.BACKUP_DEFAULT` → **256 MiB / t=4 / p=1** (~1–2 s on a modern phone; backups are
rare). Defense-in-depth for the degraded case where a kit is lost or stored next to the
passphrase. Params live in the header, so any device can still decrypt.

## 5. Shape-leak reduction

Payload is **Deflate-compressed** then **padded to the next 4 KiB bucket** before encryption,
so ciphertext length no longer reveals roughly how many items the vault has (and typical
backups shrink). Framing inside the encrypted payload:

```
payload_plain = int32(deflated.size) ‖ deflated ‖ zero-pad → next 4 KiB
```

Decode: decrypt → read `int32` length → take that many bytes → inflate → bundle. Padding ignored.

## 6. Settings toggle

`AppSettings.requireRecoveryKit` (default **true**). A header `flags` byte records whether a
kit was used (`bit0 = hasKit`), so restore knows whether to ask for one. One format, two modes.

## 7. Backup binary format (FORMAT 3)

```
int32  magic   = 0x504C4B42 ("PLKB")
int32  format  = 3
int8   flags          # bit0 = hasKit
int32  saltLen; salt
int32  m; int32 t; int32 p     # Argon2 params
int32  blobLen; blob           # AES-256-GCM( payload_plain, AAD = all header bytes above )
```

`import` still accepts FORMAT 1 (legacy vault-only) and FORMAT 2 (vault+images,
passphrase-only) unchanged. FORMAT 3 is forward-only (older app versions can't open it).

## 8. Export UX

1. Tap **Export** → passphrase dialog (existing).
2. If `requireRecoveryKit`: generate the kit and show a **Recovery Kit dialog** — the code in
   large text, a **Copy** button, a warning ("Save this. Without it *and* your passphrase this
   backup can't be opened — there is no recovery."), and an **"I've saved my recovery kit"**
   checkbox that gates **Continue**.
3. File-save picker (existing SAF flow), exporting with that kit.

## 9. Restore UX

The unlock screen's restore flow reads the file header (`Backup.peekNeedsKit(bytes)`). If a
kit was used, `RestoreDialog` shows **three fields**: Recovery Kit (auto-dash formatting +
live checksum), Recovery passphrase, New master password. Otherwise the kit field is hidden.
Wrong kit or passphrase → one message: *"Wrong passphrase or recovery kit"* (doesn't say which).

---

## Files touched

**`:core-crypto`**
- `CryptoEngine.kt` / `BouncyCastleCryptoEngine.kt` — `deriveKey(…, secret: ByteArray = EMPTY)`.
- `RecoveryKit.kt` *(new)* — Base32 + checksum encode/decode.
- `KdfParams.kt` — bump `BACKUP_DEFAULT` to 256 MiB / t=4.

**`:app`**
- `data/Backup.kt` — FORMAT 3, `flags`, AAD=header, compress+pad, kit-as-secret; `peekNeedsKit`.
- `data/AppSettings.kt` — `requireRecoveryKit`.
- `VaultViewModel.kt` — `generateRecoveryKit()`, thread kit through `exportBytes`/`restoreFromBackup`, `backupNeedsKit`, `requireRecoveryKit` getter/setter.
- `ui/VaultUi.kt` — Settings toggle, Recovery Kit dialog at export.
- `ui/Screens.kt` — `RestoreDialog` gains the conditional kit field.

## Testing

- **`RecoveryKitTest`** — encode/decode round-trip; single-character typo rejected by checksum;
  case/dash/`O↔0` normalization; garbage → null.
- **`BackupTest`** (extends current) —
  - FORMAT 3 with kit: right kit+pass works; wrong kit fails; wrong pass fails; missing kit fails.
  - FORMAT 3 kit-disabled (passphrase-only) round-trips.
  - Legacy FORMAT 1 and FORMAT 2 still import.
  - Tampered header (flipped Argon2 param) → null.
  - Compression + padding round-trips large/repetitive payloads.
  - Size-leak: two vaults of very different item counts produce the same ciphertext bucket.
  - Existing image-bundle round-trip still passes under FORMAT 3.

## Rollout

Ships as **v0.5.0**. New backups = FORMAT 3; old backups remain importable. README updated to
document the Recovery Kit model.
