# Architecture

## Overview

The app is split into a thin application shell and four library modules. Dependencies flow
in one direction: `:app` depends on the core modules; `:core:crypto` depends on
`:core:storage` and `:core:model`; `:core:storage` and `:core:transport` depend on
`:core:model`. Nothing depends on `:app`.

## Storage (`:core:storage`)

- `KeystoreKeyManager` creates a non-exportable AES-256-GCM key in the Android Keystore
  (StrongBox-backed and unlocked-device-required where supported, with graceful fallback).
  It wraps/unwraps small secrets.
- `DatabaseSecretProvider` generates a 32-byte random database passphrase, wraps it with the
  Keystore key, and persists the wrapped blob in the app's no-backup files directory.
- `SecureDatabase` loads SQLCipher, opens the encrypted database with that passphrase,
  enables foreign keys, and creates the schema.
- `EncryptedFileStore` / `AttachmentCipher` use Tink's streaming AEAD (AES-256-GCM-HKDF) with a
  keyset protected by a Keystore master key, for encrypting attachments and other larger files;
  each attachment gets its own per-file key rather than sharing the file-store keyset.
- `AppLockManager` derives the app-lock key from the user's passcode (Argon2id) combined with
  the Keystore-bound device secret, and separately handles the duress passcode: on a duress
  unlock it wipes the real database/keys/files and initializes a fresh account seeded with
  plausible placeholder conversations, rather than leaving an obviously empty app.
- `EncryptedBackup` re-encrypts the database under a user-supplied passphrase (Argon2id +
  AES-256-GCM streaming) to an app-private file, for manual export/import.
- `BlocklistDao` / `SettingsStore` back contact blocking, allowlist-only mode, and general app
  settings (lock timeout, disguise on/off, update-check opt-in, etc.).
- `SecureStorage` is the facade exposing the database, file store, and these managers.

The database passphrase never leaves the device unencrypted, and is only recoverable via the
hardware-backed Keystore key. Binding the Keystore key to user authentication (raising at-rest
protection further) is a one-line change documented in `SECURITY.md`.

## Crypto (`:core:crypto`)

Built entirely on `org.signal:libsignal` (the maintained Rust implementation), so the
post-quantum guarantees come from integration, not from hand-rolled cryptography.

- `IdentityManager` generates and persists the long-term identity key pair and registration
  ID in the encrypted database.
- `DatabaseSignalProtocolStore` implements all six `libsignal` store interfaces
  (identity, prekey, signed-prekey, Kyber-prekey, session, sender-key) against the encrypted
  database. Identity trust is strict trust-on-first-use: a key is trusted only if unseen or
  unchanged. Kyber one-time prekeys are deleted on use; the Kyber last-resort prekey persists
  and reuse of a `(kyberId, signedId, baseKey)` tuple is rejected. The `SenderKeyStore` half
  (for groups) is currently an in-memory, non-persisted map — sufficient for the fan-out group
  model the app actually uses today, not yet a full sender-key implementation (see
  `ROADMAP.md`).
- `PreKeyManager` generates the signed prekey, a batch of one-time prekeys, a batch of Kyber
  one-time prekeys, and one Kyber last-resort prekey, all signed by the identity key. It also
  assembles a `PreKeyBundle` to publish, and replenishes one-time keys when they run low.
- `PreKeyBundleCodec` serializes/reconstructs a `PreKeyBundle` for QR/text exchange.
- `SessionManager` performs PQXDH session establishment (`SessionBuilder.process`) and message
  encryption/decryption (`SessionCipher`), wrapping ciphertext in a small typed envelope so the
  receiver knows whether a message is an initial (prekey) message or a normal ratchet message.
- `SafetyNumber` derives the comparable safety-number string from both parties' identity keys,
  surfaced in `ChatActivity` for in-person or out-of-band verification.
- `CryptoEngine` is the public facade: identity info, bundle export/import, encrypt, decrypt.

The session `localAddress` name is a stable local identifier; for one-to-one sessions the
ratchet state is keyed by the remote address, so this value is not security-sensitive.

## Transport

The `TorTransport` interface (lifecycle `start`/`stop`, `publishOnionService(port)` returning
an `OnionAddress`, and `connect(address, port)` returning a `DuplexConnection`) lives in
`:core:transport`, but the app does not use `:core:transport`'s own `TorTransport`
implementation by default — this is worth being precise about, since the two are easy to
conflate:

- **What the app actually runs:** `KmpTorTransport`, in `:app`. It embeds Tor via `kmp-tor`'s
  bundled binaries directly inside the app process — no external Tor or Orbot install needed.
  It defines a v3 onion service via `HiddenServiceDir` config, so Tor itself persists the
  service key and keeps the address stable across restarts; inbound onion traffic is forwarded
  to a local `ServerSocket`, and outbound connections use Tor's SOCKS port through
  `:core:transport`'s `Socks5Client`. `AppContainer` wires this as `TorTransport` for the whole
  app.
- **An alternate, not-wired-by-default path:** `:core:transport` also has `RealTorTransport` +
  `ExternalTorProcessProvider`, which speaks the Tor control protocol (`ADD_ONION` etc.) and
  SOCKS5 to an **already-running external Tor** (for example a device-wide Orbot with its
  control port exposed), rather than embedding one. It's a real, working implementation, just
  not what `AppContainer` currently instantiates — switching to it would mean changing one line
  in `AppContainer` plus supplying Orbot's cookie/control-password auth.
- **`PlaceholderTorTransport`** also still exists in `:core:transport` (a non-routable stand-in
  that fails `connect()`) but nothing in the app references it anymore; it predates
  `KmpTorTransport` and is effectively dead code at this point.

Tor's onion v3 transport authenticates with Ed25519 and performs a classical `ntor`
(curve25519) handshake. The post-quantum protection in this app is at the message layer
(PQXDH/Kyber) and rides on that classically-secure transport.

## Contact exchange and messaging model

There is no server handing out prekeys. Each peer's identity + onion address + `PreKeyBundle`
is exported (`CryptoEngine.exportLocalBundle()`) and rendered as a QR code from **My code**;
the recipient scans it with the app's built-in scanner (`PortraitCaptureActivity`, via
`NewMessageActivity`) or pastes the code by hand, and it's pinned as that contact's identity.
The receiver imports it with `CryptoEngine.establishOutboundSession(...)` and can then send.
`ConversationManager` (in `:core:messaging`) is the orchestration layer above this: sending and
receiving text and attachments, group fan-out to each member's session, disappearing-message
timers, delete-for-everyone, and retrying attachment transfer on a failed send.
