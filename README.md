# NiiX

A private messenger for Android. It runs entirely over Tor, and asks for no phone number, no
email address, and no account of any kind. There is no server that sees your contacts, your
messages, or who you are talking to — because there is no server.

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

**Post-quantum key agreement.** Conversations start with a Kyber-1024 key exchange alongside the
classical one, so traffic recorded today cannot be decrypted later by a quantum computer. The
ongoing session ratchet is still classical; [`docs/PROTOCOL.md`](docs/PROTOCOL.md) explains what
that does and does not cover.

**Replies and quoting.** Long-press a message to reply to it. Only the message ID travels, never
a copy of the quoted text — so a reply cannot preserve something the sender later deletes.

**Groups** use sender keys. Removing someone actively revokes the key material they distributed,
rather than merely stopping future updates, so their old keys stop working instead of quietly
continuing to decrypt. Membership changes require an admin, must carry a strictly newer epoch,
and must descend from the group state the recipient already holds — so a captured message cannot
be replayed to reinstate a removed member.

**Disappearing messages.** Any member can set a timer. The countdown starts when a message is
actually read, not when it arrives.

**Offline delivery.** If someone is offline, messages wait on a small peer-to-peer relay network.
A relay holds ciphertext and an opaque recipient hash: it never learns who sent a message, who it
is for, or what it says. Storing for someone requires a grant they issued; fetching requires
proof of their identity key, and each proof is honoured once.

## On the device

Everything stored locally — messages, contacts, keys, attachments, and the Tor onion service
private key — sits in a database encrypted with SQLCipher.

The database key is derived from your passcode with Argon2id and combined with a secret held in
the phone's hardware-backed keystore, using StrongBox where available. That combination is what
stops the database being attacked offline: part of the key never leaves your phone's secure
hardware.

Wrong-passcode attempts are throttled with an increasing delay, checked against both the wall
clock and the device's elapsed-time clock — so setting the clock backwards does not reset it.

**Blurred previews** are on by default. New messages arrive obscured, in the conversation and in
the list, so a glance at an unlocked phone shows nothing. Tap to reveal; revealing starts the
disappearing timer, because being on screen is not the same as having been read. Once revealed, a
message stays revealed.

**Duress passcode.** A second passcode that wipes your real data and opens an empty account
instead. It looks like the app simply unlocked. The wipe covers the database, every
keystore-held key, attachments, Tor's stored state and the in-memory identity.

**Remote wipe** is off by default. When enabled, contacts you nominate can erase this device
remotely. They are told when you nominate them and when you revoke it; nomination is the
authorisation, carried over an authenticated session. Nothing is ever acknowledged, so a sender
cannot learn whether it worked — or whether the device exists at all.

**Wipe button** in the main screen, deliberately on the opposite side from the controls you use
daily. It confirms first, unless you turn that off.

**Calculator disguise** is off by default. When enabled, the app appears and functions as a
working calculator, and opens with a code only you know.

**Screenshots are allowed by default** and can be blocked in Settings. The default favours
usability; the toggle is there for when it does not.

**No logs.** Nothing is written to storage. An optional diagnostics view exists for
troubleshooting, lives only in memory, is erased when the app locks or data is wiped, and is
never transmitted.

**Notifications** never reveal who a message is from or what it says.

**Backups** are encrypted with a passphrase you choose, and are the only way data leaves the
device. Android's cloud backup and device-to-device transfer are disabled. A restore checks the archive is complete and matches this version of the app before touching
anything, so an incompatible or corrupt backup cannot destroy what you have.

## Updates

The app checks for releases and verifies them before installing. Every release is signed, an
update whose signature does not verify is discarded, and an update that is not strictly newer is
refused — signatures prove authorship, not freshness.

Update downloads use a normal connection by default, because reliability matters for security
patches. Messages always use Tor regardless. There is a setting to route updates over Tor too.

## Building it yourself

Requires a JDK and a Linux or macOS host. The build script fetches its own toolchain, verifying
what it downloads.

```
./build-niix.sh            # interactive menu
./build-niix.sh --debug    # unsigned debug APK, no keys needed
./build-niix.sh --test     # run the test suite
```

Android 10 (API 29) or later. Test results are written to `build-test-full.log`.

There is also `--device-tests`, which builds a second APK that exercises the encryption paths on
a connected phone — the Android Keystore and SQLCipher cannot be tested any other way.

## What this does not claim

NiiX has been reviewed by several independent auditors and their findings addressed, but it has
**not had a professional cryptographic audit**, and it has not been used at scale. It is a large,
security-sensitive codebase; the properties above describe how it is built, not a guarantee that
the implementation is free of mistakes.

Some things it deliberately cannot protect you from: a compromised phone, an adversary who can
watch a large fraction of the internet, or a contact who chooses to repeat what you told them.
These are set out honestly in [`docs/THREAT-MODEL.md`](docs/THREAT-MODEL.md) rather than left
implied.

Read the source, question it, and do not take "it's encrypted" on trust from any single source —
including this one.

## Changes

[`CHANGELOG.md`](CHANGELOG.md) lists what changed in each release, written for people using the
app rather than reading the diff.

## Documentation

- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — what the protocol actually does, including where it is
  weaker than it could be
- [`docs/THREAT-MODEL.md`](docs/THREAT-MODEL.md) — the adversaries NiiX defends against, and the
  ones it does not

Both are written to be falsifiable. If you find a claim in them the code does not support, that
is a bug in the code or the document, and worth reporting either way.

## Supporting it

NiiX is free software with no funding behind it. If it is useful to you:

```
84UTF2dihNK8pUHPb6ddiZTBwij4b98GjR54vsQkptz5bGaLQPYgdwRQaPNVYmWc4QRCuqGW76kkPDWm2x4cTyGaH9zhSBF
```

The same address is in the app under Settings > About. Check one against the other before sending
anything, and treat any address you find anywhere else as fake.

Nobody is asked to donate and nothing in the app changes if you do.

## Licence

NiiX is free software under the GNU General Public License version 3. The full text is in
[`LICENSE`](LICENSE).

You can use, study, modify and share it, and anything you distribute built from it has to be free
software under the same terms. That is the point rather than a side effect: a messenger whose
security claims cannot be checked is one you are taking on trust, and the licence keeps modified
versions open to the same scrutiny as this one.

Copyright (C) 2026 techniix, cuteLiLi and QuacK.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.
