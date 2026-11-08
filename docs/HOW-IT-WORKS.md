# How NiiX works

The detail behind the [README](../README.md): how it protects you, what it stores, how updates
work and how to build it yourself.

A private messenger for Android. It runs entirely over Tor, and asks for no phone number, no
email address, and no account of any kind. There is no server that sees your contacts, your
messages, or who you are talking to — because there is no NiiX server.

## How it identifies you

Your identity is a Tor v3 onion address and a Signal protocol key pair, both generated on your
device the first time you open the app. Nobody assigns them to you and nobody but you holds the
private keys.

You share your code with people directly, by QR or by copying it. There is no directory to look
anyone up in, and no lookup service that could be asked who knows whom.

## Messages

Direct messages, groups and attachments are end-to-end encrypted using the Signal protocol.
Messages route over Tor straight to the recipient's own onion address, so **neither end learns
the other's IP address** — that is a property of the transport, not a setting.

**Post-quantum encryption.** Conversations start with a Kyber-1024 key exchange alongside the
classical one, and every message after that also takes keys from Signal's post-quantum ratchet
(SPQR), so traffic recorded today cannot be decrypted later by a quantum computer.
[`docs/PROTOCOL.md`](PROTOCOL.md) explains what that does and does not cover.

**Replies and quoting.** Long-press a message to reply to it. Only the message ID travels, never
a copy of the quoted text — so a reply cannot preserve something the sender later deletes.

**Groups** use sender keys. Removing someone actively revokes the key material they distributed,
rather than merely stopping future updates, so their old keys stop working instead of quietly
continuing to decrypt. Membership changes require an admin, must carry a strictly newer epoch,
and must descend from the group state the recipient already holds — so a captured message cannot
be replayed to reinstate a removed member.

**Disappearing messages.** Any member can set a timer. The countdown starts when a message is
actually read, not when it arrives.

**Offline delivery.** If someone is offline, messages wait on public Nostr relays and arrive
within seconds of them coming back. Nostr is only used as a mailbox: a relay holds ciphertext
filed under a label that changes every week and is shared with other people's messages, and you
also listen to decoy labels, so it cannot tell your contacts apart or count them. It never learns
who sent a message, who it is for, or what it says, and every connection to it goes over Tor on
its own circuit. You can choose your own relays, `.onion` ones included, or turn this off.

**Delivery that heals itself.** Messages are retried until they arrive, directly when the other
phone is reachable and through the relays when it is not. If the two ends of a conversation ever
stop agreeing on their encryption state — after restoring a backup, say — the phones start a new
session on their own instead of failing until the sender gives up. Photos and files resume where
they stopped if the connection drops, and only count as delivered once every recipient has them.

**Blocking** drops someone's messages without telling them, and can be undone from Settings >
Privacy > Blocked or the conversation menu.

## On the device

Everything stored locally — messages, contacts, keys, attachments, and the Tor onion service
private key — sits in a database encrypted with SQLCipher.

The database key is derived from your passcode with Argon2id and combined with a secret held in
the phone's hardware-backed keystore, using StrongBox where available. That combination is what
stops the database being attacked offline: part of the key never leaves your phone's secure
hardware.

Wrong-passcode attempts are throttled with an increasing delay, checked against both the wall
clock and the device's elapsed-time clock — so setting the clock backwards does not reset it.

**Locks itself.** With a passcode, the database closes a minute after you leave the app and its
key leaves memory. Messages keep arriving while it is locked — even after Android closes the app,
the phone restarts or the app updates — and wait, still encrypted, until you unlock. To make that
possible, the onion key and relay inbox keys are also kept outside the database, under a keystore
key that works while the phone is locked; someone able to run code as the app could take them
without your passcode, though not read your messages. **Disconnect when locked** removes that copy
for anyone who would rather receive nothing until they unlock.

**Several devices.** An account can be on up to five devices, linked by scanning a code in
Settings > Security > Linked devices. Each has its own address and keys and gets every message;
contacts, groups and what you send are kept in step, but message history stays on the device that
has it. Every device holds the account's key, so each one you add is one more to keep safe.

**Blurred previews** are on by default. New messages arrive obscured, in the conversation and in
the list, so a glance at an unlocked phone shows nothing. Tap to reveal; revealing starts the
disappearing timer, because being on screen is not the same as having been read. Once revealed, a
message stays revealed.

**Duress passcode.** A second passcode that wipes your real data and opens a different account
instead, with a few ordinary-looking conversations in your language. It looks like the app simply
unlocked. The wipe covers the database, every keystore-held key, attachments, Tor's stored state
and the in-memory identity — and reaches your other linked devices too, even locked ones.

**Remote wipe** is off by default. When enabled, contacts you nominate can erase this device
remotely. They are told when you nominate them and when you revoke it; nomination is the
authorisation, carried over an authenticated session. Nothing is ever acknowledged, so a sender
cannot learn whether it worked — or whether the device exists at all.

**Wipe button** in the main screen, deliberately on the opposite side from the controls you use
daily. It confirms first, unless you turn that off, then erases everything — on all your linked
devices — and goes straight to setting up a new account.

**Wipe if not used** is off by default. Set it anywhere from 3 hours to 7 days and this device
erases itself if NiiX goes that long without being unlocked or used, with no network needed:
remote wipe can't reach a phone kept offline, and this still works. It only erases the device it's set on. Time is
counted with Android's time since boot, which nobody can set, so winding the phone's clock back
doesn't stop it. The one thing it can't count is time the phone spends switched off with its clock
wound back.

**Languages.** English, German, French, Spanish, Italian, Russian, Persian, Chinese, Arabic,
Japanese, Thai and Korean, following the phone's language (or the per-app language on Android 13
and later). The built-in keyboard has layouts for the European languages, Russian, Arabic and
Persian; Chinese, Japanese, Korean and Thai need the phone's own keyboard, which can be turned on
in Settings > Privacy.

**Screenshots are allowed by default** and can be blocked in Settings. The default favours
usability; the toggle is there for when it does not.

**No logs.** Nothing is written to storage. An optional diagnostics view exists for
troubleshooting, lives only in memory, is erased when the app locks or data is wiped, and is
never transmitted.

**Notifications** never reveal who a message is from or what it says.

**Backups** hold your messages, contacts, keys, photos, files and profile pictures, encrypted with
a passphrase of at least 12 characters, and are the only way data leaves the device. Android's cloud backup and device-to-device transfer are disabled. A restore
checks the archive is complete and matches this version of the app before touching anything, so
an incompatible or corrupt backup cannot destroy what you have — and once restored, the app
reconnects as the restored account straight away.

## Updates

The app checks for releases and verifies them before installing. Every release is signed, an
update whose signature does not verify is discarded, and an update that is not strictly newer is
refused — signatures prove authorship, not freshness.

Updates go over Tor by default, so neither GitHub nor anyone watching the network learns that the
phone runs NiiX. The app checks each time it's opened and about once a day while it stays open.
There is a setting to download updates over a normal connection instead, which is faster;
messages always use Tor regardless. The Google Play version has no updater of its own: Play
delivers its updates.

**On a Samsung phone**, Auto Blocker stops apps being installed or updated from anywhere except
the Galaxy Store and Play Store, and it's on by default on newer Galaxy phones. Turn it off under
Settings › Security and privacy › Auto Blocker to install or update NiiX, and back on afterwards if
you like.

## Building it yourself

Requires a JDK and a Linux or macOS host. The build script fetches its own toolchain, verifying
what it downloads.

```
./BUILD.sh                  # menu with every option
./BUILD.sh --debug          # debug APK, no keys needed
./BUILD.sh --test           # run the test suite
./BUILD.sh --create-keys    # make the signing keys, once, outside the project
./BUILD.sh --release        # signed APK
./BUILD.sh --publish        # signed APK, published to GitHub Releases with its signature
./BUILD.sh --play-bundle    # Google Play bundle, with no self-updater
./BUILD.sh --push-source    # source to GitHub, never keys, APKs or build files
```

Android 10 (API 29) or later. Test results are written to `build-test-full.log`.

**Keys live outside the project**, in `~/.keys/niix` (with the keys of the maintainer's other
apps in `~/.keys`) unless you choose another folder (a USB stick, say) when creating them; the
build remembers where. Keys kept in `~/.niix-keys` by earlier versions are moved there on the
first run. `niix-release.jks` signs the APK,
`update-signing-key.pem` signs the update file the app checks before installing, and an optional
`niix-play-upload.jks` signs uploads to Google Play. The script refuses to create keys inside the
project, so a zip or upload of it can never carry them. Back the folder up offline in two places:
without these keys no update can reach people who already have the app. The public update key
goes in `update-key.pub`, which the build reads into the app; a second line there is a standby
key the app also accepts, for if the main one is ever lost.

`./BUILD.sh --use-shared-key` moves NiiX to the APK signing key the maintainer's apps share
(`~/.keys/android`), for good. NiiX's own key signs, once, its approval of the shared key
(`lineage.bin`, Android's key rotation, which every Android version NiiX runs on has), and every
APK built after that carries it: installed copies take it as a normal update and from then on
only accept the shared key. `--backup-keys` copies all of `~/.keys` to another folder and checks
the copy.

The public repository has the same `BUILD.sh`, with the same menu as the maintainer's other apps
(TestTube, niixfliix). Its publishing options need the maintainer's keys and GitHub login, so for
anyone else they stop with a message; everything else works the same. `./BUILD.sh --help` lists
every option.

There is also `--device-tests`, which builds a second APK that exercises the encryption paths on
a connected phone — the Android Keystore and SQLCipher cannot be tested any other way.

## What this does not claim

NiiX has been reviewed by several independent auditors and their findings addressed, but it has
**not had a professional cryptographic audit**, and it has not been used at scale. It is a large,
security-sensitive codebase; the properties above describe how it is built, not a guarantee that
the implementation is free of mistakes.

Some things it deliberately cannot protect you from: a compromised phone, an adversary who can
watch a large fraction of the internet, or a contact who chooses to repeat what you told them.
These are set out honestly in [`docs/THREAT-MODEL.md`](THREAT-MODEL.md) rather than left
implied.

Read the source, question it, and do not take "it's encrypted" on trust from any single source —
including this one.

## Changes

[`CHANGELOG.md`](../CHANGELOG.md) lists what changed in each release, written for people using the
app rather than reading the diff.

## Documentation

- [`docs/PROTOCOL.md`](PROTOCOL.md) — what the protocol actually does, including where it is
  weaker than it could be
- [`docs/THREAT-MODEL.md`](THREAT-MODEL.md) — the adversaries NiiX defends against, and the
  ones it does not
- [`docs/SECURITY-AUDIT.md`](SECURITY-AUDIT.md) — the latest audit, what it fixed and what is
  still open
- [`ROADMAP.md`](../ROADMAP.md) — what is planned next
- [`PRIVACY.md`](../PRIVACY.md) — the privacy policy, for both the GitHub and Google Play versions

The first three are written to be falsifiable. If you find a claim in them the code does not
support, that is a bug in the code or the document, and worth
[reporting](https://github.com/techniixdotcom/niix-messenger/issues) either way.

## Supporting it

NiiX is free software with no funding behind it. If it is useful to you:

```
47rUgJ3q7DvUTWDQQ2BteSRxLDkLutV78YziycFagKnP1S7wnT9T9R6fo5ti6zFh6BcLfxNYDJsGqJXq85GghUnRNSwXX19
```

The same address is in the app under Settings > About. Check one against the other before sending
anything, and treat any address you find anywhere else as fake.

Nobody is asked to donate and nothing in the app changes if you do.
