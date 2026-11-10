# NiiX privacy policy

Effective 4 November 2026.

NiiX is a messenger built so that nobody, including its developers, can learn who you talk to or
what you say. This policy describes exactly what the app does with information. It applies to
both the Google Play version and the version published on GitHub; where they differ, it says so.

## What the developers receive

Nothing. NiiX has no servers operated by its developers, no accounts, no analytics, no crash
reporting, no advertising and no tracking of any kind. The developers never receive your
messages, your contacts, your identity or anything about how you use the app.

## Your identity

NiiX does not ask for a phone number, email address, name or any other personal detail. Your
identity is a cryptographic key pair and a Tor address, generated on your phone the first time
you open the app. You choose a display name if you want one; it is shared only with the contacts
you message.

## Messages and attachments

Messages, attachments and read receipts are end-to-end encrypted with the Signal protocol
before they leave your phone. Only you and the people you are talking to can read them.

They travel over the Tor network directly to your contact's phone, so neither of you learns the
other's IP address.

If you turn on "Tell contacts when I'm back online" (off by default), your contacts' phones are
sent a small encrypted message whenever yours reconnects, so they learn that you are online.

If your contact's phone is offline, the encrypted message is left on Nostr relays: public servers
run by third parties, not by the developers. By default NiiX uses four well-known ones, two of
them reached as Tor onion services; you can choose your own in Settings, or turn this off. A
relay cannot read the message, and NiiX reaches it only over Tor, so it never sees your IP
address. What it sees is a sealed envelope signed by a throwaway key, filed in one of 1024 weekly
"buckets" shared by everyone using NiiX on that relay, so it can't tell who the message is from,
who it is for, or how many contacts you have. Envelopes carry an expiry 14 days after they are
sent, and relays that honour it delete them then. The relays' own privacy policies apply to what
they keep.

## Voice calls

Calls are off for everyone until you turn them on for a contact, and only work with a contact who
has turned them on for you too, and whose safety number you have verified. Either of you can turn
them off at any time, which also ends a call in progress.

A call is end-to-end encrypted, with keys exchanged through the same encrypted session as your
messages, and travels over Tor directly between the two phones. Neither of you learns the other's
IP address, and no server, relay or third party carries the call. Nothing about a call is
recorded: there is no call history, and the audio exists only in memory while it is being sent or
played.

## Linked devices

If you link a second device to your account, your contacts, groups and the messages you send
are copied to it, encrypted the same way. Removing a device, or wiping any of them, erases the
others too.

## What is stored on your phone

Your messages, contacts, keys and attachments are stored on your phone only, in an encrypted
database. Its key is derived from your passcode together with a secret held in your phone's
hardware-backed keystore (StrongBox where the phone has one), so the data cannot be read by
copying it off the phone.

On a Linux computer the same encrypted database is kept in NiiX's own folder (inside the
Flatpak, ~/.var/app/app.niix.NiiX), and the secret combined with your passcode is kept in your
desktop keyring instead of a hardware keystore. Without a keyring it's kept in a file only your
user account can read, and NiiX says so in its settings; a passcode then is what protects the
data from someone who copies your files.

Diagnostics, if you open them, are kept in memory only, erased when the app locks, and never
sent anywhere.

Backups are created only when you ask for one. They are encrypted with a passphrase you choose
and saved where you choose. The developers never receive them.

## Permissions

- **Internet and network state:** to connect to the Tor network.
- **Running in the background:** to stay connected so messages arrive when they are sent.
- **Battery optimisation exemption:** so Android does not cut that connection while the phone is
  idle. You are asked, and can refuse.
- **Notifications:** to tell you a message has arrived. Notifications never show who it is from
  or what it says.
- **Camera:** to scan a contact's code and to take photos you choose to send. Only when you use
  those features.
- **Microphone:** to record voice messages, and for voice calls. Only while you are recording or
  in a call.
- **Audio settings, keeping the screen on and showing over the lock screen:** during calls only,
  to switch between earpiece and speaker, turn the screen off when the phone is at your ear, and
  let you answer a call without unlocking. An incoming call never shows who is calling while the
  phone is locked.
- **Installing apps (GitHub version only):** to install updates the app has downloaded and
  verified. The Google Play version does not have this permission.

NiiX does not request location, contacts, call logs, SMS or access to your files.

## Updates

**Google Play version:** updates are delivered by Google Play. The app itself makes no update
checks. Google's handling of data for Play installs and updates is covered by Google's privacy
policy.

**GitHub version:** each time the app is opened, and about once a day while it stays open, it
asks GitHub whether a newer release exists, and downloads it if you choose to update. By default
this goes through Tor, so GitHub sees a request about NiiX but not your IP address; GitHub's
privacy policy applies to that. In Settings › Updates you can use your normal connection instead,
which GitHub can then see, or turn the checks off. Every update is verified against the
developers' signature before it is installed.

## Donations

The Monero address shown in the app is only text you can copy. Nothing is collected.

## Children

NiiX is not directed at children under 13.

## Changes to this policy

Any change is published in this file, with a new effective date, before it takes effect in a
release. The history of the file is public in the repository.

## Contact

Questions and problems: https://github.com/techniixdotcom/niix-messenger/issues
