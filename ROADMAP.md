# Roadmap

What's planned, roughly in order. Finished work moves to [`CHANGELOG.md`](CHANGELOG.md) and comes
off this list. Things left out on purpose are in [`docs/PROTOCOL.md`](docs/PROTOCOL.md) §11, not
here.

## 1. Test on real phones

Much of the recent work has been checked in code and tests but never on a device. Until it has,
it's the most likely place for problems. The full list is under "Needs testing on a phone" in
[`docs/SECURITY-AUDIT.md`](docs/SECURITY-AUDIT.md); the parts that matter most:

- Delivery when switching between Wi-Fi and mobile data, after losing signal, and with the app
  opened several times while it connects.
- The check on the app's own address getting an answer through a real Tor.
- Linking two phones, keeping them in step, removing one, and the duress passcode on one wiping
  the other. A device that was offline while another was removed should get the new wipe key
  when it comes back.
- Receiving through public relays with shared bucket tags.
- Receiving after Android ends the app while it's locked, after a reboot and after an update.
- A broken session healing itself, and large or group attachments over a shaky connection.
- Restoring a backup onto another phone and being reachable straight away.
- Turning a passcode on or off and closing the app straight away, and backups that carry photos.
- The locked inbox with the screen off, backups, and update checks.
- Updating a phone from 1.0.1 to this version: unlocking with its passcode (6 digits included),
  the inactivity wipe setting carrying over, and relay mode and the calculator being cleaned up.
- The new look on every screen, and the Google Play build, which must show no update settings.
- The inactivity wipe going off on the lock screen and from the background, and the notice that
  Android stopped NiiX.

## 2. Smaller gaps

- **Decoy contacts have no relay history.** A real contact you've been online with leaves mailbox
  state behind; the duress decoy's contacts don't. Spotting that takes knowing how NiiX stores it,
  but it is a tell.
- **NiiX traffic is recognisable on a relay.** The event kind (4459) and the `z` tag mark an event
  as NiiX's. Using a common kind such as NIP-59 gift wraps would blend in, but some relays only
  hand those out after a login the recipient can't do without revealing a key. That needs trying
  against real relays first.

## 3. Bigger additions

- **Per-ABI split APKs.** A separate, smaller APK for each processor type instead of one that
  carries all of them, so downloads shrink without dropping 32-bit phones.
- **Message history on a new device.** Linking copies contacts and groups only. Copying history,
  and sharing read state between devices, would make a second device a full replacement.
- **Relay buckets that scale.** A bucket mixes only the people using NiiX on that relay, and
  listening to one means downloading everything in it. Both are fine at today's size; with many
  more people the number of buckets would need to grow, and with very few a bucket can still be
  one person's alone.

## 4. Long term

- **Post-quantum group signatures.** Group messages are signed with Ed25519, which a quantum
  computer could forge, though not read. That waits on a post-quantum signature libsignal can use.
- **A formally analysed group protocol.** Groups use a protocol of NiiX's own, tested but never
  formally analysed. MLS would also give large groups efficient post-compromise security. It's a
  large change to how groups work.
