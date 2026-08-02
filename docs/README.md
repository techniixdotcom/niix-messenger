# NiiX

An Android-only, peer-to-peer, end-to-end-encrypted messenger that runs over Tor v3 onion
services, with post-quantum key agreement (PQXDH / ML-KEM "Kyber") provided by Signal's
maintained `libsignal`, and encrypted-at-rest local storage.

This build implements a working foundation end to end: encrypted storage, a libsignal-backed
post-quantum crypto layer, a **real Tor transport** (Tor control protocol + SOCKS5), a peer
wire protocol, an inbound receiver, a foreground connectivity service, and a set of security
features (safety-number verification, contact blocking, an allowlist mode, notification
privacy, a duress passcode with panic wipe, and encrypted backup/restore).

It has **not** been built, run, or audited in this environment (no Android SDK and no Tor are
available here), and it must not be used to protect real people until it has been compiled,
tested on-device against a real Tor, and independently reviewed. See `docs/SECURITY.md`.

## Module map

```
:app              Application shell, manual DI, lock screen, status/actions UI,
                  foreground ConnectivityService, notifications, QR display
:core:model       Value types (OnionAddress, Identity, Contact, Message, Conversation, …)
:core:storage     SQLCipher database, Keystore key wrapping, Tink file encryption,
                  app lock + duress, settings, blocklist, contacts, encrypted backup
:core:crypto      libsignal-backed identity, prekeys, stores, sessions (PQXDH/Kyber),
                  safety numbers
:core:transport   Real Tor transport: control client (ADD_ONION), SOCKS5 client,
                  inbound accept loop, process-provider seam
:core:messaging   Wire protocol/codec, ConversationManager orchestration, MessageReceiver,
                  disappearing-message sweeper
```

## Requirements

- JDK 17
- Android Studio (latest stable) or a command-line Android SDK with Platform 35 and recent
  Build-Tools
- Network access during the build to Maven Central, Google's Maven, and Signal's build
  artifacts repository `https://build-artifacts.signal.org/libraries/maven/` (already
  configured in `settings.gradle.kts`)
- A device or emulator on Android 8.0 (API 26) or newer
- A reachable Tor instance for networking (see "Running over Tor")

## Generate the Gradle wrapper jar

`gradle/wrapper/gradle-wrapper.jar` is intentionally not included (a binary jar cannot be
shipped as source). Create it once:

- **Android Studio:** open the project; it provisions the wrapper automatically.
- **Command line** (needs a system Gradle 8.11+): `gradle wrapper --gradle-version 8.11.1`

## Build and run

```
./gradlew :app:assembleDebug          # debug APK -> app/build/outputs/apk/debug/
./gradlew :app:installDebug           # install on a connected device/emulator
```

On first launch you set an app passcode (used to derive the database key together with a
Keystore-bound device secret). After unlocking, the app generates a post-quantum identity and
prekeys, starts the foreground service, brings up the Tor transport, publishes an onion
service, and shows your fingerprint, registration ID, onion address, transport state, and your
shareable contact bundle.

## Running over Tor

The transport speaks Tor's stable control protocol and SOCKS5; it needs a Tor process that
exposes a control port and a SOCKS port. Two options:

1. **External Tor (default).** `ExternalTorProcessProvider` connects to an already-running Tor
   at `127.0.0.1:9050` (SOCKS) and `127.0.0.1:9051` (control). Run Orbot (or a standalone tor)
   configured to expose those ports to the app. If the control port requires authentication,
   supply a cookie file or control password to `ExternalTorProcessProvider` (see
   `AppContainer`). Bridges / pluggable transports (obfs4) are configured in Orbot.

2. **Embedded Tor (optional).** Implement `TorProcessProvider` over a binary provider such as
   kmp-tor that ships the native tor binaries, returning its assigned SOCKS/control ports. The
   rest of the transport is unchanged. Bridges are then set in the embedded torrc, which also
   requires bundling an obfs4/lyrebird pluggable-transport binary.

If no Tor endpoint is reachable, the transport reports an ERROR state and messaging stays
offline; nothing else crashes.

## Adding a contact and messaging

Contact exchange is out-of-band: share your Base64 "contact bundle" (shown on the main screen,
also rendered as a safety-number QR after a session exists) with a peer over a channel you
trust, exchange onion addresses, then "Add contact" with their onion + bundle. Always verify
the **safety number** with the other person before trusting a conversation. "Send message"
sends a text to a conversation id (a peer onion for direct chats, or a group id).

## Security features

- **Safety-number verification** — symmetric 60-digit number derived from both identity keys;
  compare out-of-band, then "Mark verified".
- **Contact blocking + allowlist mode** — drop messages from blocked onions; optionally accept
  only from known contacts.
- **Notification privacy** — the persistent notification hides content; lock-screen visibility
  is secret by default.
- **Duress passcode + panic wipe** — a distinct passcode that, when entered at the lock screen,
  destroys all local data (database, secrets, Keystore key, files) and opens a fresh empty app.
- **Encrypted backup/restore** — re-encrypts the database under a user passphrase (Argon2id +
  AES-256-GCM streaming) to an app-private external file for portability.

## Known limitations in this build

- Not compiled, run, or audited here; the Tor control/SOCKS code is written to the stable
  specs but is untested against a live Tor. Treat version-sensitive native calls (SQLCipher,
  Tink streaming AEAD) as the first things to verify on device.
- Attachment **byte transfer** is not implemented: `sendAttachment` encrypts the file locally
  and sends an encrypted *offer* (key + digest + metadata); the recipient does not yet fetch
  the bytes. Text, timers, delete-for-everyone, receipts, and group invites do flow.
- QR **scanning** is not wired (camera capture is intentionally omitted); bundles are shared as
  selectable Base64 text. QR **display** of the safety number is provided.
- Backup uses a fixed app-private path; production should use the Storage Access Framework.
- Group messaging is client-side fan-out over per-member sessions; sender keys are future work.

## Dependency versions

`gradle/libs.versions.toml` pins a known-good, internally consistent toolchain. Because this
was authored without live access to package repositories, update the catalog (AGP, Kotlin,
`libsignal`, SQLCipher, Tink, BouncyCastle, AndroidX) to current releases after the first
successful sync and re-sync.
