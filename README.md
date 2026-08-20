# NiiX

I built NiiX because I wanted a messenger where privacy isn't something a company promises —
it's just how the thing is built. There's no NiiX server. There's no account database sitting
anywhere for someone to breach, subpoena, or quietly start logging. Your phone talks directly to
your contact's phone over Tor, encrypted end-to-end with post-quantum crypto. If I vanished
tomorrow, NiiX would keep working exactly the same, because nothing about it depends on me.

It looks and behaves like a calculator until you type your passcode.

> **NiiX hasn't had an independent security audit yet.** Use your own judgment about how much
> to trust it, especially if your safety depends on it.

## How it works

There is no NiiX server anywhere — your phone runs its own Tor onion service, and your contacts
connect to it directly. No company operates infrastructure that could log who's talking to whom,
store your messages, or get compelled to hand them over, because that infrastructure doesn't
exist.

Messages are encrypted with libsignal's PQXDH (X25519 + Kyber), so the handshake stays protected
even against a future quantum computer that recorded today's traffic. Everything after that runs
over Tor — neither you nor your contact ever learns the other's IP, and your network provider
can't see what you're doing either.

On the phone itself, everything is encrypted at rest with SQLCipher. By default the key comes
from your passcode; without it, the data on disk is unreadable. Turn passcode protection off in
Settings → Security and the database is still encrypted, just by a key tied to your phone's own
hardware — as strong as your screen lock, not by something only you know.

Photos and files work too, not just text. Each attachment gets its own per-file encryption key
before it ever leaves your phone, streams over the same Tor connection as everything else, and
gets decrypted locally on arrival. If a transfer fails — contact briefly offline, Tor being Tor —
it just retries on its own.

The calculator disguise is on by default: the real app only opens once you type your passcode and
press `=`. You can turn it off in Settings if you'd rather NiiX just look like NiiX — doing so
still requires a passcode, since a disguise with nothing behind it isn't protecting anything.

There's also a duress passcode: a second, separate code that wipes everything and drops you into
a fresh account seeded with harmless-looking conversations, so a coerced unlock looks like it just
worked instead of obviously failing. It only exists while passcode protection is on — with that
off, Settings has a plain "Wipe all data now" button instead.

Disappearing messages work per-conversation: set a timer and messages — content and any attached
file — delete themselves once it runs out, on a background sweep that doesn't care whether the
chat happens to be open. And you can delete something for both sides, not just yours, via a
signed control message rather than just hiding it locally.

## Identity and adding contacts

Your identity is a cryptographic key, not a phone number. You share a short code — your onion
address plus your identity key — or its QR form. To add someone, scan their QR or paste their
code. The first time you message someone new, your app fetches their keys over Tor and checks
them against the code you already have.

If you want to be certain nobody tampered with that exchange, compare **safety numbers** with
your contact in person or over another channel you trust — it's built right into the chat screen,
not something you need a separate tool for.

## Before you rely on this, know the tradeoffs

No servers gives real privacy, but it isn't free. Here's what that costs you:

**Both people need to be reachable, eventually.** With nothing holding messages in the middle,
delivery happens phone to phone. If your contact's offline, nothing's lost — your message waits
on your device and goes out automatically once they're reachable again. But if they stay offline
a long time, it waits that long, and two people who are never online at the same moment can't
exchange anything at all.

**Background delivery depends on your phone letting it happen.** Receiving while the app's closed
needs a background service running, and a lot of Android phones — Xiaomi/MIUI, Oppo, Vivo,
OnePlus, Samsung, Huawei, and other custom ROMs especially — kill background apps aggressively to
save battery. Grant NiiX **"Autostart"** and set its battery usage to **"No restrictions"** in
your phone's settings if you want delivery to actually be reliable — nothing in the app can do
this for you. [dontkillmyapp.com](https://dontkillmyapp.com) has instructions for most phones.

**After a reboot or a force-close, open the app once.** Your data's encrypted with your passcode,
so NiiX can't receive anything after a restart or a kill until you open it and type your code
once. That's deliberate — your messages stay encrypted at rest, at the cost of not receiving
while fully locked after a kill. (A messenger that receives 24/7 without ever asking you to
unlock keeps its keys sitting unlocked the whole time. I didn't want that.)

**Messaging someone new needs both of you online at that moment**, since setting up a
conversation means fetching their keys live over Tor.

**Tor takes a moment, especially the first time.** The first connection after opening the app can
take anywhere from 20 to 90 seconds while Tor builds its circuits. Your own onion address shows up
once that's done.

**Groups are basic for now.** A group message gets sent to each member individually — fanned out
client-side over each member's own verified 1-on-1 session — since there's no real group-key
agreement yet. That costs more time and bandwidth than a proper group protocol would.

**One phone at a time.** There's no multi-device support yet. The storage layer's already shaped
to support it later, but there's no way to link a second device today.

**Only the handshake is post-quantum right now.** PQXDH protects the start of a conversation
against a future quantum computer, but the ongoing Double Ratchet after that is still classical.
Full post-quantum protection for the whole conversation (Signal's "Triple Ratchet" / SPQR) is
something I'm planning once it lands in an audited `libsignal` release — see `docs/ROADMAP.md`.

## Using NiiX

Install it, open it — you'll see a calculator. On first launch, type the registration code
(`1+6+1`, then `=`) to start setup: pick a username, a passcode of at least 6 digits, and
optionally a duress passcode. After that, it's just a calculator until you type your passcode and
press `=`.

Share your own code from the menu → **My code**, and add people by scanning their QR or pasting
their code. Start a chat, send text or attach something, and set a disappearing-message timer from
the chat menu if you want messages to expire on their own.

Type the wrong code and it just does the calculation, like a normal calculator would. Type your
**duress** code and it wipes everything and opens into a fresh, harmless-looking account instead —
so it looks like it worked.

Settings → **Security** lets you turn passcode protection and the calculator disguise off
independently, if you'd rather NiiX act like an ordinary app. Both are on by default, and turning
either off shows you exactly what you're giving up before it takes effect.

There's also an optional, off-by-default "Check for updates" toggle in Settings. Turn it on and
NiiX periodically checks GitHub Releases for a newer version — over Tor, same as everything else —
and verifies the release's Ed25519 signature before it'll ever offer to install it. Nothing gets
offered, let alone installed, without you acting on it.

## Security at a glance

| Property | How it's protected |
|---|---|
| Message content | Post-quantum end-to-end encryption (libsignal PQXDH) for the handshake; Double Ratchet after that |
| Attachments | Per-file streaming encryption, over the same E2E Tor connection as messages |
| Metadata (who/when/IP) | Hidden by Tor; there's no server to log it in the first place |
| Data on the device | Encrypted with your passcode (SQLCipher); duress passcode wipes it |
| App presence | Disguised as a calculator |
| App updates (if enabled) | Fetched over Tor, fails closed if the release signature doesn't check out |

## Building it yourself

This folder is exactly the source — everything here is what gets pushed to GitHub, nothing more.
The build tooling, signing keys, and everything a build generates live one directory up:

```
niix-portable/
├── build-niix.sh   <- run this, from up here, not from inside this folder
├── upload/          <- you're in this one
├── keys/             <- signing material, never committed
└── build/              <- toolchain + build output, never committed
```

`../README.md` has the full build/sign/publish walkthrough. Short version:

```
cd ..
./build-niix.sh                 # menu-driven if you run it with no arguments
./build-niix.sh --debug         # unsigned build, fastest, for local testing
./build-niix.sh --update        # bump dependencies to latest stable, then build
./build-niix.sh --publish-release   # sign for the update checker and push a GitHub release
```

A plain Android/Gradle setup works too (Kotlin, `minSdk 26`, `compileSdk 35`) if you'd rather
point your own `keystore.properties` at your own keystore — see `keystore.properties.example`.

**On code shrinking:** release builds have R8 (`isMinifyEnabled`) turned off on purpose. NiiX
leans on libsignal, SQLCipher, and kmp-tor, all of which use JNI or reflection in ways R8 can
silently strip without ever throwing a build error — you only find out later, on a real device,
sometimes as something as bad as Tor never starting. `proguard-rules.pro` already has keep rules
for all three, so shrinking is safe to try, but it deserves its own deliberate, tested pass rather
than riding along in every build by default.

## If you're packaging this (F-Droid, etc.)

Two things worth knowing if you're putting together a build-from-source recipe:

- **Prebuilt native binaries.** NiiX pulls in Tor via `kmp-tor` and uses `libsignal`, both
  normally consumed as prebuilt native libraries. F-Droid's inclusion policy prefers building
  everything from source, so a compliant recipe would need to build Tor and libsignal from source
  itself (the way Briar and Orbot do) — that's real packaging work, not a formality.
- **Licensing.** `libsignal` is AGPLv3. Linking it means NiiX as a whole needs AGPLv3-compatible
  distribution terms — worth factoring into how you package it.
- **Turn off the update checker** in any packaged build. F-Droid and similar repos already manage
  updates themselves; the built-in checker exists for people installing outside of one.

## Status

Experimental. Not audited. Provided as-is, no warranty. Read the tradeoffs above and decide for
yourself whether that's good enough for what you need it for.
