# NiiX privacy policy

Effective 30 September 2026.

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

If you turn on "Tell contacts when I'm back online" (off by default), your recent contacts'
phones are sent a small encrypted message whenever yours reconnects, so they learn that you are
online.

If your contact's phone is offline, the encrypted message is held on a relay: another NiiX
phone whose owner has chosen to help deliver messages. A relay cannot read the message. It sees
an unreadable identifier for the recipient, and a random key the recipient gave that one sender.
The key is different for every pair of people and reveals nothing about who the sender is. (A
sender still using an older version of NiiX is shown by their public identity key instead, until
they update.) Messages are deleted from the relay once collected, and after six hours at
most.

## What is stored on your phone

Your messages, contacts, keys and attachments are stored on your phone only, in an encrypted
database. Its key is derived from your passcode together with a secret held in your phone's
hardware-backed keystore (StrongBox where the phone has one), so the data cannot be read by
copying it off the phone.

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
- **Microphone:** to record voice messages. Only while you are recording.
- **Installing apps (GitHub version only):** to install updates the app has downloaded and
  verified. The Google Play version does not have this permission.

NiiX does not request location, contacts, call logs, SMS or access to your files.

## Updates

**Google Play version:** updates are delivered by Google Play. The app itself makes no update
checks. Google's handling of data for Play installs and updates is covered by Google's privacy
policy.

**GitHub version:** each time the app starts, it asks GitHub whether a newer release exists, and
downloads it if you choose to update. By default this uses your normal internet connection, so
GitHub can see your IP address and that it is being asked about NiiX; GitHub's privacy policy
applies to that. In Settings > Updates you can route update checks through Tor instead, or turn
them off. Every update is verified against the developers' signature before it is installed.

## Donations

The Monero address shown in the app is only text you can copy. Nothing is collected.

## Children

NiiX is not directed at children under 13.

## Changes to this policy

Any change is published in this file, with a new effective date, before it takes effect in a
release. The history of the file is public in the repository.

## Contact

https://github.com/techniixdotcom/niix-messenger
