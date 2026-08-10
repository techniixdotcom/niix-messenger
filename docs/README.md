# NiiX

An Android-only, serverless, peer-to-peer, end-to-end-encrypted messenger that runs over Tor v3
onion services, with post-quantum key agreement (PQXDH / ML-KEM "Kyber") provided by Signal's
maintained `libsignal`, encrypted-at-rest local storage, and a calculator disguise.

This build is a working app, not a foundation-only skeleton: encrypted storage, a
libsignal-backed post-quantum crypto layer, a **real Tor transport** (Tor control protocol +
SOCKS5, with an inbound accept loop) wired to a **real wire protocol and UI** (chat, contacts,
groups, settings), and a set of security features (safety-number verification, contact
blocking, an allowlist mode, notification privacy, a duress passcode with panic wipe, and
encrypted backup/restore) are all implemented and wired together, not stubbed.

It has **not** had an independent security or cryptography audit. Treat it as experimental —
see `docs/SECURITY.md` — and do not rely on it in situations where your safety depends on it.

## Module map

```
:app              Application shell, calculator disguise + real launcher alias, lock screen
                  (PasscodeActivity), chat/contacts/groups/settings UI, foreground
                  ConnectivityService, notifications, QR display + scanning, opt-in
                  Tor-only update checker, and KmpTorTransport — the embedded-Tor
                  TorTransport implementation the app actually runs (see below)
:core:model       Value types (OnionAddress, Identity, Contact, Message, Conversation,
                  Attachment, …)
:core:storage     SQLCipher database, Keystore key wrapping, Tink file/attachment encryption,
                  app lock + duress wipe, settings, blocklist, contacts, encrypted backup
:core:crypto      libsignal-backed identity, prekeys, stores, sessions (PQXDH/Kyber),
                  safety numbers
:core:transport   TorTransport abstraction + reusable primitives (control client, SOCKS5
                  client) and RealTorTransport/ExternalTorProcessProvider, an alternate
                  implementation for connecting to an already-running external Tor (e.g.
                  Orbot) instead of the embedded one — not what AppContainer wires by default
:core:messaging   Wire protocol/codec, ConversationManager orchestration (send/receive,
                  attachments, group fan-out, disappearing messages, delete-for-everyone),
                  MessageReceiver, ExpirySweeper
```

## Requirements

- JDK 17
- Android Studio (latest stable) or a command-line Android SDK with Platform 35 and recent
  Build-Tools
- Network access during the build to Maven Central, Google's Maven, and Signal's build
  artifacts repository `https://build-artifacts.signal.org/libraries/maven/` (already
  configured in `settings.gradle.kts`)
- A device or emulator on Android 8.0 (API 26) or newer

You don't need a separately running Tor process — see "Running over Tor" below; the app can
run its own.

## Build and run

The simplest path is the repo's self-contained build script, which provisions its own JDK,
Gradle, and Android SDK — see the top-level `README.md`:

```
./build-niix.sh --debug         # unsigned debug APK -> app/build/outputs/apk/debug/
```

Or with a standard Android/Gradle setup already installed:

```
./gradlew :app:assembleDebug          # debug APK -> app/build/outputs/apk/debug/
./gradlew :app:installDebug           # install on a connected device/emulator
```

On first launch you set an app passcode (used to derive the database key together with a
Keystore-bound device secret) and, optionally, a duress passcode. After unlocking, the app
generates a post-quantum identity and prekeys, starts the foreground service, brings up the
Tor transport, publishes an onion service, and shows your identity as a shareable QR code.

## Running over Tor

`AppContainer` wires `KmpTorTransport` (in `:app`) as the transport the app actually runs —
this is a real, embedded Tor process bundled via `kmp-tor`, not an optional add-on and not a
placeholder. On first start it launches Tor inside the app, defines a v3 hidden service
(`HiddenServiceDir`), and persists the service key under the app's files directory so your
onion address survives restarts. Outbound connections go through Tor's own SOCKS port via
`Socks5Client`; inbound onion traffic is forwarded to a local `ServerSocket`. **No external Tor
or Orbot install is required** for the app to work.

`:core:transport` additionally provides `RealTorTransport` + `ExternalTorProcessProvider`, an
alternate `TorTransport` implementation that connects to an already-running external Tor
instance (for example a device-wide Orbot with its control port exposed) instead of embedding
one. This is not what ships by default — `AppContainer` would need to be changed to use it —
but the seam exists if you want the app to share a system Tor process instead of running its
own.

If no Tor endpoint is reachable, the transport reports an error state and messaging stays
offline; nothing else crashes.

## Adding a contact and messaging

Contact exchange happens through **My code** in the app menu: it renders your onion address
and identity key as a QR code, and the same screen scans a contact's QR (or accepts a pasted
code) to add them. Always verify the **safety number** with the other person — from the chat
screen — before trusting a conversation carries real weight. Once added, start a chat, send
text or an attachment, and optionally set a disappearing-message timer from the chat menu.

## Security features

- **Safety-number verification** — symmetric number derived from both identity keys; compare
  out-of-band or in person, then mark verified in the chat screen.
- **Contact blocking + allowlist mode** — drop messages from blocked onions; optionally accept
  only from known contacts.
- **Notification privacy** — the persistent notification hides content; lock-screen visibility
  is secret by default.
- **Calculator disguise** — the app's real launcher icon is disabled by default in favor of a
  calculator alias; switching this off in Settings is explicit and explained.
- **Duress passcode + panic wipe** — a distinct passcode that, when entered at the lock screen,
  destroys all local data (database, secrets, Keystore key, files) and opens a fresh,
  plausible-looking account instead of an obviously empty one.
- **Encrypted backup/restore** — re-encrypts the database under a user passphrase (Argon2id +
  AES-256-GCM streaming) to an app-private external file for portability.

## Known limitations in this build

- **Not independently audited.** The application-layer integration, storage design, wire
  protocol, and transport have not had outside cryptographic or security review.
- **Groups are client-side fan-out**, not a real group protocol: each message is individually
  encrypted and sent to every member's 1-on-1 session. The `SenderKeyStore` implementation is
  an in-memory, non-persisted stub — functional for basic groups today, but not the sender-key
  optimization a mature group protocol needs. See `ROADMAP.md`.
- **Only the initial handshake is post-quantum.** PQXDH protects session establishment; the
  ongoing Double Ratchet after that is classical, pending libsignal exposing SPQR/"Triple
  Ratchet" — see `ROADMAP.md`.
- **No multi-device support.** One identity lives on one phone; the storage schema already
  keys sessions by name + device for when this is built out, but there's no linking flow yet.
- **Backup uses a fixed app-private path**; a production-grade version should use the Storage
  Access Framework so the user picks the destination.

## Dependency versions

`gradle/libs.versions.toml` pins a known-good, internally consistent toolchain (AGP, Kotlin,
`libsignal`, SQLCipher, Tink, BouncyCastle, AndroidX). `./build-niix.sh --update` bumps
everything in the catalog to current stable releases and rebuilds, if you want to move the
whole toolchain forward deliberately rather than drifting dependency-by-dependency.
