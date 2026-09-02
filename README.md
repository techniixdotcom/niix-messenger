# Niix

Niix is a private messenger for Android. It runs entirely over Tor, and it doesn't ask for a
phone number, an email address, or an account of any kind. There is no server that sees your
contacts, your messages, or who you're talking to.

## How it identifies you

Your identity is a Tor v3 onion address and a Signal protocol key pair, both generated on your
device the first time you open the app. Nobody assigns them to you and nobody but you holds the
private keys. You share your code with people directly; there is no directory to look anyone up
in, and nothing to link your identity to a real-world one.

## Messages

Direct messages, group chats, and attachments are end-to-end encrypted using the Signal protocol.
Messages route over Tor straight to the recipient's own onion address, so neither end learns the
other's IP address, and no intermediary sees content.

Session establishment is post-quantum. The key exchange that starts a conversation is designed to
stay secure against an adversary who records traffic now and decrypts it later with a quantum
computer.

**Groups.** Group messages use sender keys, which only move forward — so removing someone has to
actively revoke their access rather than just stopping future updates. When a member is removed,
every remaining device revokes the sender-key material that member gave it, so their old keys
stop working rather than quietly continuing to decrypt. Changing group membership requires being
an admin, and a membership change carrying an old or repeated epoch is rejected, so a captured
message can't be replayed to put a removed member back.

Members who have been removed also lose the ability to change the disappearing-message timer,
send group traffic, or affect when other people's messages expire.

**Disappearing messages.** Any member can set a timer. The countdown on the sender's own copy
starts when the message is actually read, not when it was sent.

**Offline delivery.** If someone is offline, messages wait on a small peer-to-peer relay network
until they reconnect. Relayed messages stay end-to-end encrypted; a relay stores ciphertext and
learns nothing about its contents.

## On your device

Everything stored locally — messages, contacts, keys, attachments, and the Tor onion service
private key — sits in a single database encrypted with SQLCipher.

The key for that database is derived from your passcode using Argon2id, and combined with a
secret held in the phone's hardware-backed keystore (using StrongBox where the device supports
it). That combination matters: the database can't be attacked offline by copying it to another
machine, because part of the key never leaves your phone's secure hardware.

Repeated wrong passcode attempts are throttled with an increasing delay, enforced before each
attempt rather than after.

**Screenshot protection** is on by default. Screens with anything sensitive are excluded from
screenshots and from the recent-apps preview.

**Duress passcode.** You can set a second passcode that wipes your real data and opens a decoy
account instead. It looks like the app simply unlocked. The wipe covers the database, the
keystore-held secret, attachments, and Tor's stored state, and it also clears the identity the
running app has in memory — so nothing survives the wipe still able to act as you.

**Calculator disguise.** The app can appear on your home screen, and function, as a working
calculator.

**No logs.** The app writes no logs to storage. There is an optional diagnostics view for
troubleshooting, but it lives only in memory, is erased when the app locks or data is wiped, and
is never transmitted anywhere.

**Notifications** never show who a message is from or what it says.

**Backups** are encrypted with a passphrase you choose, and are the only way data leaves the
device. Android's own cloud backup and device-to-device transfer are disabled, so nothing is
copied off the phone without you explicitly exporting it.

## Updates

The app can check for new releases and verify them before installing. Every release is signed,
and an update whose signature doesn't verify is discarded rather than installed. Update
downloads use a normal internet connection by default, because reliability matters for security
patches; messages and everything else always use Tor regardless. If you'd rather updates go over
Tor too, there's a setting for it.

## What this doesn't claim

This has not had an independent professional security audit. It's a large, security-sensitive
codebase, and the properties above describe how it's built, not a guarantee that the
implementation is free of mistakes. Read the source, question it, and don't take "it's
encrypted" on trust from any single source — including this one.
