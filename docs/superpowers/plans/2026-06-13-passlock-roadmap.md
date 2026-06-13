# PassLock Implementation Roadmap

> Companion to the design spec: `docs/superpowers/specs/2026-06-13-passlock-design.md`.
> The app is built in 8 sequential phases. Each phase has (or will have) its own plan document under `docs/superpowers/plans/` and produces working, tested software on its own. Later-phase plans are written just-in-time, right before execution, so they reflect what actually got built in earlier phases.

**Crypto library decision (resolves spec §17 open question):** AEAD via platform `javax.crypto` AES-256-GCM (zero-dependency, hardware-accelerated) + KDF via Bouncy Castle Argon2id (audited, pure-JVM). No JNI/NDK. Fully unit-testable on the JVM. Swappable behind the `CryptoEngine` interface.

| Phase | Plan file | Deliverable | Spec coverage |
|---|---|---|---|
| **1 — Foundation** | `2026-06-13-passlock-phase-1-foundation.md` | Multi-module Gradle project; `:core-crypto` (Argon2id, AES-256-GCM AEAD, DEK + passphrase-wrap, zeroize) and `:core-domain` (models, templates, offline TOTP, password generator, search) — pure JVM, fully unit-tested | §4, §5, §6.2, §6.3 (inner layer), §8, §11 (TOTP/gen logic), §16 (crypto/domain tests) |
| **2 — Secure storage & key custody** | `…-phase-2-storage.md` | `:data` module: Android Keystore adapter (hardware outer-wrap, StrongBox→TEE→software ladder), biometric gating, atomic encrypted vault document, settings store; instrumented tests | §6.1 (double-wrap), §7 (key release), §9, §12 (keystore, fallback ladder) |
| **3 — App shell & unlock** | `…-phase-3-shell-unlock.md` | `:app`: single-Activity Compose, navigation, unlock screen (password + biometric per policy), lock/auto-lock state machine, throttle after 2nd attempt | §7, §10 (unlock), §12 (auto-lock/zeroize) |
| **4 — Vault UI** | `…-phase-4-vault-ui.md` | Flat expandable table (default) + search/filter chips, list→detail mode toggle, item editor with templates, one-tap copy + clipboard auto-clear, tap-to-reveal | §10 (vault, editor), §11 (copy/reveal/clipboard) |
| **5 — Hardening** | `…-phase-5-hardening.md` | `FLAG_SECURE`, no-`INTERNET` manifest + build assertion, memory zeroize on lock, clipboard sensitive flag, opt-in auto-wipe, duress/decoy vault, root/tamper warning | §2, §12 (full), §15 |
| **6 — Power features** | `…-phase-6-features.md` | Offline TOTP UI (countdown ring), password-generator UI, biometric-gated reveal/copy, tags & favorites | §11 (full) |
| **7 — Backup & restore** | `…-phase-7-backup.md` | Encrypted `.passlock` export (recovery passphrase, strong Argon2), import/restore + re-wrap under new device key, versioned header; export/import round-trip tests | §6.3, §13 |
| **8 — Theme & polish** | `…-phase-8-theme.md` | Stealth OLED theme (true-black + emerald), dark/light/system, motion, empty states, settings completion | §10 (settings), Stealth theme |

## Cross-cutting conventions (all phases)

- **TDD:** every behavioural unit gets a failing test first, then minimal implementation, then commit.
- **Package root:** `com.passlock`.
- **Commits:** small and frequent, conventional-commit style (`feat:`, `test:`, `chore:`).
- **No `INTERNET` permission** is ever added; Phase 5 adds a build-time assertion that locks this in.
- **Secrets in memory:** `ByteArray`/`CharArray`, never `String`, zeroized after use (enforced from Phase 1 in the crypto module; applied in UI from Phase 3).
