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
- `EncryptedFileStore` uses Tink's streaming AEAD (AES-256-GCM-HKDF) with a keyset protected
  by a Keystore master key, for encrypting larger files/attachments.
- `SecureStorage` is the facade exposing the database and the file store.

The database passphrase never leaves the device unencrypted, and is only recoverable via the
hardware-backed Keystore key. Binding the Keystore key to user authentication (raising at-rest
protection) is a one-line change documented in `SECURITY.md`.

## Crypto (`:core:crypto`)

Built entirely on `org.signal:libsignal` (the maintained Rust implementation), so the
post-quantum guarantees come from integration, not from hand-rolled cryptography.

- `IdentityManager` generates and persists the long-term identity key pair and registration
  ID in the encrypted database.
- `DatabaseSignalProtocolStore` implements all six `libsignal` store interfaces
  (identity, prekey, signed-prekey, Kyber-prekey, session, sender-key) against the encrypted
  database. Identity trust is strict trust-on-first-use: a key is trusted only if unseen or
  unchanged. Kyber one-time prekeys are deleted on use; the Kyber last-resort prekey persists
  and reuse of a `(kyberId, signedId, baseKey)` tuple is rejected.
- `PreKeyManager` generates the signed prekey, a batch of one-time prekeys, a batch of Kyber
  one-time prekeys, and one Kyber last-resort prekey, all signed by the identity key. It also
  assembles a `PreKeyBundle` to publish, and replenishes one-time keys when they run low.
- `PreKeyBundleCodec` serializes/reconstructs a `PreKeyBundle` for out-of-band exchange.
- `SessionManager` performs PQXDH session establishment (`SessionBuilder.process`) and message
  encryption/decryption (`SessionCipher`), wrapping ciphertext in a small typed envelope so the
  receiver knows whether a message is an initial (prekey) message or a normal ratchet message.
- `CryptoEngine` is the public facade: identity info, bundle export/import, encrypt, decrypt.

The session `localAddress` name is a stable local identifier; for one-to-one sessions the
ratchet state is keyed by the remote address, so this value is not security-sensitive.

## Transport (`:core:transport`)

- `TorTransport` is the abstraction: lifecycle (`start`/`stop`), `publishOnionService(port)`
  returning an `OnionAddress`, and `connect(address, port)` returning a `DuplexConnection`.
- `PlaceholderTorTransport` is a non-routable stand-in so the app builds and runs. It returns
  a structurally-valid placeholder onion address and fails `connect()`.
- `SocketDuplexConnection` adapts a `Socket` for real implementations.

### Integration seam

A production transport replaces the placeholder by implementing `TorTransport`:

1. Start an embedded Tor (C daemon via a JNI wrapper, or Arti) and wait for bootstrap.
2. `publishOnionService(localPort)`: create/restore a persistent v3 onion-service key, map the
   onion's virtual port to a local listening port, and return the onion address. Persisting the
   service key is what keeps your address stable across restarts; store it encrypted (the
   storage module's `EncryptedFileStore` is suitable).
3. `connect(address, port)`: open a stream to `address:port` through Tor's SOCKS proxy
   (or Arti's connect API) and wrap it in a `DuplexConnection`.

Tor's onion v3 transport authenticates with Ed25519 and performs a classical `ntor`
(curve25519) handshake. The post-quantum protection in this app is at the message layer
(PQXDH/Kyber) and rides on that classically-secure transport.

## Manual key-exchange (P2P) model

There is no server handing out prekeys. Each peer publishes its own `PreKeyBundle` (via
`CryptoEngine.exportLocalBundle()`), which is exchanged out of band — for example, scanned as a
QR code in person — and pinned. The receiver imports it with
`CryptoEngine.establishOutboundSession(...)` and can then send. Per-contact distribution of
one-time prekeys (so each one-time key is handed to exactly one peer) is a transport/protocol
responsibility addressed in later phases; the simplest interim policy is to publish a
last-resort-style bundle. See `ROADMAP.md`.
