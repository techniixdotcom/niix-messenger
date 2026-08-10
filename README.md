# NiiX

A serverless, peer-to-peer messenger for Android. No accounts, no phone number, no servers. Messages are end-to-end encrypted with post-quantum cryptography and delivered directly between phones over the Tor network. The app is disguised as a working calculator.

> **NiiX is experimental software and has not had an independent security audit. Do not rely on it in situations where your safety depends on it.**

## How it works

- **No servers.** There is no NiiX server anywhere. Your phone runs its own Tor onion service, and your contacts connect to it directly. No company operates infrastructure that could log who talks to whom, store your messages, or be compelled to hand them over — because it does not exist.
- **Post-quantum end-to-end encryption.** Messages use Signal's libsignal (PQXDH: X25519 + Kyber), so they stay protected even against a future quantum computer that recorded today's traffic.
- **Everything over Tor.** All traffic is routed through Tor. Neither you nor your contact learns the other's IP address, and the connection is hidden from your network provider.
- **Encrypted at rest.** Your messages, attachments, and keys live in a SQLCipher database. By default the encryption key is derived from your passcode; without it, the data on disk is unreadable. If you turn passcode protection off (Settings > Security), the database is still encrypted, but by a key tied to your phone's hardware alone — as strong as your phone's own screen lock, not by anything only you know.
- **Attachments.** Photos and files are supported, not just text. Each attachment is encrypted with its own per-file key before it ever leaves your phone, streamed to your contact over the same Tor connection as everything else, and decrypted locally on arrival. A failed transfer (contact briefly offline, Tor hiccup) retries automatically rather than silently failing.
- **Calculator disguise (on by default).** The app looks and behaves like a real calculator. Your actual messenger opens only when you type your passcode and press `=`. This can be turned off in Settings if you'd rather NiiX show its real icon and name; doing so requires a passcode to still be enabled, since a disguise with nothing behind it can't hide anything.
- **Duress passcode.** An optional second passcode that wipes everything and opens into a fresh account seeded with plausible, harmless conversations, so a coerced unlock looks like it worked normally rather than obviously failing. Only available while passcode protection is on — Settings also has a manual "Wipe all data now" button for when it's off.
- **Disappearing messages.** Set a per-conversation timer and messages delete themselves — content and any attachment file — after it elapses, on a background sweep that runs independently of whether the chat is open.
- **Delete for everyone.** Remove a message you sent from your contact's device too, not just your own, via a signed control message rather than just hiding it locally.

## Identity and adding contacts

Your identity is a cryptographic key, not a phone number. You share a short code — your onion address plus your identity key — or its QR code. To add someone, scan their QR or paste their code. The first time you message a new contact, your app fetches their keys over Tor and checks them against the code you have.

To be certain no one is intercepting the key exchange, compare **safety numbers** with your contact in person or over another trusted channel — this check is built into the chat screen, not something you have to do out-of-band with a separate tool.

## Important limitations — please read before relying on this

NiiX has no servers by design. That gives strong privacy, but it comes with real, unavoidable tradeoffs.

- **Both people must be reachable to exchange messages.** With no server to hold messages, delivery happens directly, phone to phone. If your contact is offline, your message is **not lost** — it waits on your phone and is delivered automatically the next time they are reachable. But if they stay offline for a long time, the message waits that long, and two people who are never online at the same time cannot exchange messages.
- **Background delivery depends on your phone.** To receive while the app is closed, NiiX runs a background service. Many Android phones — especially Xiaomi/MIUI, Oppo, Vivo, OnePlus, Samsung, Huawei and other custom ROMs — aggressively kill background apps to save battery. For reliable delivery you should grant NiiX **"Autostart"** and set its battery usage to **"No restrictions"** in your system settings. No app can do this on your behalf. See [dontkillmyapp.com](https://dontkillmyapp.com) for instructions specific to your phone.
- **After a reboot or a force-close, open the app once.** Because your data is encrypted with your passcode, NiiX cannot receive anything after the phone restarts or the system kills the app until you open it and enter your code once. This is a deliberate choice: your messages stay encrypted at rest, at the cost of not being able to receive while fully locked after a kill. (A messenger that receives 24/7 without you unlocking — like most mainstream apps — keeps its keys unlocked all the time, which NiiX intentionally does not.)
- **The first message to a new contact needs both of you online.** Setting up a conversation fetches your contact's keys over Tor, so you both need to be reachable at that moment.
- **Tor can be slow, especially on first start.** The first connection after opening the app can take roughly 20–90 seconds while Tor builds its circuits. Your own onion address appears once Tor has connected.
- **Groups are basic.** Group messages are sent to each member individually (client-side fan-out over each member's verified 1-on-1 session); there is no group key agreement yet, so sending to a large group costs more bandwidth and time than a true group protocol would.
- **No multi-device support yet.** NiiX lives on one phone at a time. The storage layer is already shaped to support it later, but there's no way to link a second device today.
- **Only the initial handshake is post-quantum.** PQXDH protects the start of a conversation against a future quantum computer, but the ongoing Double Ratchet after that is still classical. Continuous post-quantum protection for the whole conversation (Signal's "Triple Ratchet" / SPQR) is planned once it's available in an audited `libsignal` release — see `docs/ROADMAP.md`.

## Using NiiX

1. Install and open — you'll see a calculator.
2. On first launch, type the registration code (`1+6+1`, then `=`) to begin setup. Choose a username and a passcode of at least 6 digits, and optionally a duress passcode.
3. After setup, the app is just a calculator. Type your passcode and press `=` to open the real app.
4. Share your code from the menu → **My code**, and add contacts by scanning their QR or pasting their code.
5. Start a chat, send text or attach a photo/file, and set a disappearing-message timer from the chat menu if you want messages to expire automatically.
6. Entering a wrong code simply performs the calculation. Entering your **duress** code wipes everything and opens into a fresh, harmless-looking account, so it looks like it worked.
7. Settings → **Security** lets you turn passcode protection and the calculator disguise off individually if you'd rather NiiX behave like an ordinary app — both are on by default, and turning either off shows a full explanation of what you're giving up before it takes effect.
8. Settings also has an optional, **off-by-default** "Check for updates" toggle. If you turn it on, NiiX periodically checks GitHub Releases for a newer version — over Tor, like everything else it does — and verifies the release's Ed25519 signature before ever offering to install it. No update is offered, let alone installed, without your action.

## Security at a glance

| Property | How it's protected |
|---|---|
| Message content | Post-quantum end-to-end encryption (libsignal PQXDH) for the handshake; Double Ratchet thereafter |
| Attachments | Per-file streaming encryption, sent over the same E2E Tor connection as messages |
| Metadata (who/when/IP) | Hidden by Tor; no server exists to log it |
| Data on the device | Encrypted with your passcode (SQLCipher); duress passcode wipes it |
| App presence | Disguised as a calculator |
| App updates (if enabled) | Fetched over Tor, fail-closed if the release signature doesn't verify |

## Building

The repository includes a self-contained build script that downloads its own JDK, Gradle and Android SDK into a local folder (no system installation required).

**Signing a release build.** A release APK must be signed with your own key, which only you should ever hold:

```
keytool -genkeypair -v -storetype PKCS12 \
  -keystore niix-release.jks -alias niix \
  -keyalg RSA -keysize 4096 -validity 10000
cp keystore.properties.example keystore.properties   # then edit in your passwords
```

Back up `niix-release.jks` somewhere safe outside this folder — losing it means you can never sign an update as "the same app" again. Never commit `niix-release.jks` or `keystore.properties`; both are already git-ignored.

```
./build-niix.sh                 # signed release APK (needs keystore.properties, above)
./build-niix.sh --debug         # unsigned debug APK instead, for quick local testing
./build-niix.sh --update        # bump all dependencies to the newest stable versions, then build
```

You can also build with a standard Android/Gradle setup (Kotlin, `minSdk 26`, `compileSdk 35`).

**Note on code shrinking:** the release build type has R8 shrinking (`isMinifyEnabled`) turned off deliberately. This app leans on libsignal, SQLCipher, and kmp-tor, all of which use JNI or reflection in ways R8 can silently strip without a build error — the failure only shows up later, on a real device, often as something as consequential as Tor never starting. `proguard-rules.pro` already has keep rules for all three so shrinking can be tried safely later, but that's worth doing as its own deliberate, tested step, not bundled into every build.

## Notes for packagers (F-Droid)

Two things to be aware of if you package NiiX for a build-from-source repository such as F-Droid:

- **Prebuilt native binaries.** NiiX embeds Tor via `kmp-tor` and uses `libsignal`, both of which are normally consumed as **prebuilt** native libraries. F-Droid's inclusion policy prefers building everything from source, so a compliant recipe would need to build Tor and libsignal from source (as Briar and Orbot do), which is non-trivial packaging work.
- **Licensing.** `libsignal` is AGPLv3. Linking it means NiiX as a whole must be distributed under AGPLv3-compatible terms — factor that into the project's license.
- **The optional update checker should be disabled or removed** in any packaged build (F-Droid and similar repositories manage updates themselves; NiiX's own checker exists for people installing outside such a repository).

## License and status

Experimental. Not audited. Provided as-is, with no warranty. See the limitations above and choose accordingly.
