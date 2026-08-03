[logo]: https://private-user-images.githubusercontent.com/220974970/630247394-90a13774-4f93-4027-84dd-312bf5452470.jpeg?jwt=eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJnaXRodWIuY29tIiwiYXVkIjoicmF3LmdpdGh1YnVzZXJjb250ZW50LmNvbSIsImtleSI6ImtleTUiLCJleHAiOjE3ODU3MzA1NTksIm5iZiI6MTc4NTczMDI1OSwicGF0aCI6Ii8yMjA5NzQ5NzAvNjMwMjQ3Mzk0LTkwYTEzNzc0LTRmOTMtNDAyNy04NGRkLTMxMmJmNTQ1MjQ3MC5qcGVnP1gtQW16LUFsZ29yaXRobT1BV1M0LUhNQUMtU0hBMjU2JlgtQW16LUNyZWRlbnRpYWw9QUtJQVZDT0RZTFNBNTNQUUs0WkElMkYyMDI2MDgwMyUyRnVzLWVhc3QtMSUyRnMzJTJGYXdzNF9yZXF1ZXN0JlgtQW16LURhdGU9MjAyNjA4MDNUMDQxMDU5WiZYLUFtei1FeHBpcmVzPTMwMCZYLUFtei1TaWduYXR1cmU9YTkwNmQ0MWE2YWI0MzZjNTExMWY1MzcxMDMzOWM5YzMyOTliY2RmYjIxNTc5M2I3OThkMjY0MzcwOTJjM2FlMiZYLUFtei1TaWduZWRIZWFkZXJzPWhvc3QmcmVzcG9uc2UtY29udGVudC10eXBlPWltYWdlJTJGanBlZyJ9.-i2bXnJZPl31RrzuDGDANDdonHhjC3CTdmHx_Ds61pc "screen 1"
[logo]: https://ibb.co/chKHN7s9 "screen 2"



# NiiX

A serverless, peer-to-peer messenger for Android. No accounts, no phone number, no servers. Messages are end-to-end encrypted with post-quantum cryptography and delivered directly between phones over the Tor network. The app is disguised as a working calculator.

> **NiiX is experimental software and has not had an independent security audit. Do not rely on it in situations where your safety depends on it.**

## How it works

- **No servers.** There is no NiiX server anywhere. Your phone runs its own Tor onion service, and your contacts connect to it directly. No company operates infrastructure that could log who talks to whom, store your messages, or be compelled to hand them over — because it does not exist.
- **Post-quantum end-to-end encryption.** Messages use Signal's libsignal (PQXDH: X25519 + Kyber), so they stay protected even against a future quantum computer that recorded today's traffic.
- **Everything over Tor.** All traffic is routed through Tor. Neither you nor your contact learns the other's IP address, and the connection is hidden from your network provider.
- **Encrypted at rest.** Your messages and keys live in a SQLCipher database encrypted with a key derived from your passcode. Without your passcode, the data on disk is unreadable.
- **Calculator disguise.** The app looks and behaves like a real calculator. Your actual messenger opens only when you type your passcode and press `=`.
- **Duress passcode.** An optional second passcode that silently wipes everything and leaves the calculator on screen, so a coerced "unlock" reveals nothing.

## Identity and adding contacts

Your identity is a cryptographic key, not a phone number. You share a short code — your onion address plus your identity key — or its QR code. To add someone, scan their QR or paste their code. The first time you message a new contact, your app fetches their keys over Tor and checks them against the code you have.

To be certain no one is intercepting the key exchange, compare **safety numbers** with your contact in person or over another trusted channel.

## Important limitations — please read before relying on this

NiiX has no servers by design. That gives strong privacy, but it comes with real, unavoidable tradeoffs.

- **Both people must be reachable to exchange messages.** With no server to hold messages, delivery happens directly, phone to phone. If your contact is offline, your message is **not lost** — it waits on your phone and is delivered automatically the next time they are reachable. But if they stay offline for a long time, the message waits that long, and two people who are never online at the same time cannot exchange messages.
- **Background delivery depends on your phone.** To receive while the app is closed, NiiX runs a background service. Many Android phones — especially Xiaomi/MIUI, Oppo, Vivo, OnePlus, Samsung, Huawei and other custom ROMs — aggressively kill background apps to save battery. For reliable delivery you should grant NiiX **"Autostart"** and set its battery usage to **"No restrictions"** in your system settings. No app can do this on your behalf. See [dontkillmyapp.com](https://dontkillmyapp.com) for instructions specific to your phone.
- **After a reboot or a force-close, open the app once.** Because your data is encrypted with your passcode, NiiX cannot receive anything after the phone restarts or the system kills the app until you open it and enter your code once. This is a deliberate choice: your messages stay encrypted at rest, at the cost of not being able to receive while fully locked after a kill. (A messenger that receives 24/7 without you unlocking — like most mainstream apps — keeps its keys unlocked all the time, which NiiX intentionally does not.)
- **The first message to a new contact needs both of you online.** Setting up a conversation fetches your contact's keys over Tor, so you both need to be reachable at that moment.
- **Tor can be slow, especially on first start.** The first connection after opening the app can take roughly 20–90 seconds while Tor builds its circuits. Your own onion address appears once Tor has connected.
- **Attachments are not yet supported.** Only text messages are delivered today. Image/file transfer is on the roadmap.
- **Groups are basic.** Group messages are sent to each member individually; there is no group key agreement yet.

## Using NiiX

1. Install and open — you'll see a calculator.
2. On first launch, type the registration code (`1+6+1`, then `=`) to begin setup. Choose a username and a passcode of at least 6 digits, and optionally a duress passcode.
3. After setup, the app is just a calculator. Type your passcode and press `=` to open the real app.
4. Share your code from the menu → **My code**, and add contacts by scanning their QR or pasting their code.
5. Entering a wrong code simply performs the calculation. Entering your **duress** code silently wipes everything and stays on the calculator.

## Security at a glance

| Property | How it's protected |
|---|---|
| Message content | Post-quantum end-to-end encryption (libsignal PQXDH) |
| Metadata (who/when/IP) | Hidden by Tor; no server exists to log it |
| Data on the device | Encrypted with your passcode (SQLCipher); duress passcode wipes it |
| App presence | Disguised as a calculator |

## Building

The repository includes a self-contained build script that downloads its own JDK, Gradle and Android SDK into a local folder (no system installation required):

```
./build-niix.sh                 # build a debug APK
./build-niix.sh --update        # bump all dependencies to the newest stable versions, then build
```

You can also build with a standard Android/Gradle setup (Kotlin, `minSdk 26`, `compileSdk 35`).

## Notes for packagers (F-Droid)

Two things to be aware of if you package NiiX for a build-from-source repository such as F-Droid:

- **Prebuilt native binaries.** NiiX embeds Tor via `kmp-tor` and uses `libsignal`, both of which are normally consumed as **prebuilt** native libraries. F-Droid's inclusion policy prefers building everything from source, so a compliant recipe would need to build Tor and libsignal from source (as Briar and Orbot do), which is non-trivial packaging work.
- **Licensing.** `libsignal` is AGPLv3. Linking it means NiiX as a whole must be distributed under AGPLv3-compatible terms — factor that into the project's license.

## License and status

Experimental. Not audited. Provided as-is, with no warranty. See the limitations above and choose accordingly.
