# NiiX Protocol Specification v1

This describes what NiiX actually does, not what it aspires to. Where the implementation is
weaker than it could be, that is stated rather than omitted. A specification that flatters the
code is worse than none, because it makes review harder while appearing to make it easier.

Version: corresponds to app version 0.9.x.
Authors: techniix, cuteLiLi, QuacK.

---

## 1. Identity

An installation generates, on first run and never again:

| Item | Type | Notes |
|---|---|---|
| Identity key pair | Curve25519 | Long-term. Never leaves the device. |
| Registration ID | 14-bit integer | libsignal requirement. |
| Onion service key | Ed25519 | Becomes the user's address. |

**There is no account.** No phone number, email, username, or server-side record. A user's
network identity *is* their v3 onion address, derived from a key they hold.

An account can be on several devices, which share its identity key; the address it was created
with names it. See section 9.

### Contact exchange

Out of band only: a QR code or a copied code containing the onion address and identity key. There
is no directory, no lookup, and no discovery protocol, so there is no service that could be asked
who knows whom.

### Verification

Safety numbers are generated with libsignal's `NumericFingerprintGenerator` (5200 iterations)
over both identity keys and both onion addresses. Marking a contact verified **pins their
identity key**: subsequent messages whose identity does not match the pin are refused, and the
identity store will not replace a pinned key.

### Binding an address to a key

The sender address in a direct frame is only a claim. Before the first session-starting message
from an address with no stored or pinned identity is decrypted, the receiver fetches that
address's prekey bundle over Tor and compares its identity key with the one in the message. Only
the holder of an onion service's key can answer on it, so this ties the address to the identity
before libsignal's trust-on-first-use records it. A mismatch is dropped. The lookup runs in the
background and the first attempt is left unacknowledged, so the sender's retry, usually within 20
seconds, is the one accepted. Nostr envelopes skip the check: they are addressed with a secret
the receiver gave only to that peer.

---

## 2. Key agreement and message encryption

libsignal's PQXDH with the Triple Ratchet: the Double Ratchet plus Signal's post-quantum ratchet
(SPQR). NiiX does not implement its own cryptography.

### Prekey bundle

| Component | Algorithm |
|---|---|
| Identity key | Curve25519 |
| Signed prekey | Curve25519, signed |
| One-time prekey | Curve25519, batch of 100 |
| Kyber prekey | **ML-KEM (Kyber-1024)**, signed, batch of 100 |

The Kyber prekey makes initial key agreement **post-quantum secure**: an adversary recording
traffic today cannot decrypt it later with a quantum computer, because the session key depends on
a KEM output as well as the classical DH.

**Rotation:** signed prekeys every 2 days (retained 14); Kyber last-resort every 30 days
(retained 60). Both refill when one-time keys drop below 20.

### The ratchet is post-quantum too

In the libsignal version NiiX uses (0.102), every session runs SPQR, the Sparse Post-Quantum
Ratchet, alongside the Double Ratchet, and its output is mixed into the message keys. libsignal
turns it on for every new session and requires the other side to speak it, so there is no setting
and no fallback to a classical-only session. An adversary with a quantum computer who records a conversation now
can't decrypt it later, and stealing the session state at one moment doesn't keep them reading
once SPQR has moved on.

Groups use Sender Keys (section 6). The keys themselves travel inside one-to-one sessions, so
they're protected the same way, and the chain that derives each message key is symmetric. The
signature on each group message is Ed25519, which a quantum computer could forge; reading the
messages would still need the keys.

### When a session breaks

If the two ends of a session stop agreeing, say because one restored a backup, every message
between them fails to decrypt, and retrying would never help. When a message from a contact (or
from one of our own devices) fails because the session can't open it, as opposed to being a
duplicate or coming from a changed identity key, the receiver throws its session with that device
away, starts a new one from the device's current key bundle (checked against the identity key it
already knows, as for any session) and sends it a dummy message on it. Receiving that switches the
sender to the new session too, and its next retry decrypts. The failed message is not acknowledged,
so it is retried. This happens at most once every ten minutes per device, and never for a stranger,
since it dials them and hands them our keys.

A duplicate, a second copy of something already decrypted, is acknowledged and dropped.

---

## 3. Transport

Every connection is a Tor v3 onion service. There is no clearnet path for messaging.

- Each client runs its own hidden service; its address is its identity.
- Connections are made onion-to-onion, so **neither party learns the other's IP address**, and
  this is a property of the transport rather than a setting.
- Inbound connections are capped at 24 concurrent with a 30-second read timeout.
- Tor forwards the onion service to a loopback port the system picks at random on each start, so
  other apps on the phone can't find the app by probing a fixed port.

### Padding

Plaintext is padded to one of `256 / 1024 / 4096 / 16384` bytes before encryption; larger
payloads pad to a multiple of 16384. An observer sees a bucket, not a length.

### Connection lifecycle

The Tor connection is held by a foreground service, which Android requires to show a
notification for as long as it runs. The app stays connected rather than sleeping between
wakeups.

That was measured, not assumed. Across three devices, waking Tor from nothing took 8, 9, 13 and
28 minutes, against 3 seconds when the app was already warm; and fewer than half the scheduled
alarms fired at all, because phone makers drop them. A polling cycle whose connection step costs
most or all of its interval delivers nothing, so periodic wakeups were removed.

`ReducedConnectionPadding` and `ReducedCircuitPadding` are enabled instead. Both are provided by
Tor for mobile use and reduce idle traffic at some cost to traffic-analysis resistance; on a
phone, padding traffic means radio wakeups.

"Disconnect when locked" remains, and is a different thing: a privacy choice for people who want
nothing reachable while the app is locked, with messages arriving when they unlock. It does not
claim to poll.

The app follows the phone's default network. When it goes, Tor's network is switched off; when it
comes back or another replaces it, Tor's network is switched on again (off and on, for a switch,
so connections over the old network are closed rather than left to time out), and Nostr relays are
redialled without waiting out their backoff.

With a passcode, the database is only open after unlocking in the app. So that receiving doesn't
stop when Android ends the app while it's locked, or when the phone restarts, the onion key and
the Nostr inbox (relays, mailbox secrets, decoy seed, and how far it has been read) are also kept
outside the database, encrypted with a Keystore key that works while the phone is locked (see
section 8). From these the service starts again by itself, also after a reboot or an update, and
messages wait in the locked inbox. "Disconnect when locked" deletes this copy and stops it being
written.

A connection carries one message after another rather than one each. After a message the sender
keeps it open for 90 seconds, and for as long as the conversation is on screen; it opens it in
advance when the conversation comes on screen, so the first message doesn't wait for Tor to
connect. A quiet connection is kept alive with a ping (frame 18, empty, answered with an ACK)
every 20 seconds, inside the receiver's 30-second read timeout. A connection that turns out to be
dead is replaced and the message sent again; the receiver drops a copy it already has. A receiver
on an older build closes after the first frame, which the sender treats the same way, so the two
still work together.

Every incoming connection has 90 seconds to finish, on top of the 30-second limit on each read,
so a sender trickling bytes can't hold one of the 48 connection slots indefinitely. That's lifted
for a connection whose messages decrypt, which is a contact keeping it open, and for an attachment
transfer that has shown a valid token.

Sending is parallel: to every device of a contact at once, and to up to six group members at once.
Whatever is waiting for a contact goes the moment a message arrives directly from them, since that
shows they're online, and everything from the last hour goes as soon as the connection starts or
the network comes back, instead of waiting for the retry schedule.

Every ten minutes, and a minute after the network changes, the device connects to its own onion
address through Tor and sends a single zero byte, which is not a frame type, so the connection is
simply closed. If that fails twice in a row while there's a network, Tor is restarted and the
service published again.

### Cover traffic

Optional, off by default. When enabled, `Dummy` messages are sent to contacts at randomised
intervals. Off by default because it costs battery and bandwidth continuously to defend against
an adversary most users do not have.

---

## 4. Wire format

Framed binary. Envelope type is a single byte:

| Type | Name | Purpose |
|---|---|---|
| 1 | Text | A message, optionally a reply |
| 2 | DeleteForEveryone | Retract a message |
| 3 | TimerUpdate | Change the disappearing timer |
| 4 | Receipt | Delivered / read |
| 5 | GroupInvite | Membership state |
| 6 | Attachment | Encrypted file metadata |
| 7 | ProfileUpdate | Display name and avatar |
| 8 | SenderKeyDistribution | Group sender key |
| 9 | GroupCiphertext | Group message |
| 10 | *(retired)* | Was RelayGrant; accepted and ignored |
| 11 | *(retired)* | Was RelayCapabilityUpdate; accepted and ignored |
| 12 | Dummy | Cover traffic |
| 13 | GroupLeave | Voluntary departure |
| 14 | RemoteWipe | Erase this device |
| 15 | RemoteWipeNomination | You may / may no longer erase my device |
| 16 | NostrInbox | How to reach me through Nostr relays (section 5) |
| 17 | Devices | The sending account's device list (section 9) |
| 18 | SyncSent | Own devices only: something sent from another of them |
| 19 | ContactUpdate | Own devices only: a contact was added, renamed, verified, blocked or unblocked on another of them |
| 20 | WipeKey | Own devices only: the account's new wipe key and the device-list epoch it belongs to |
| 21 | CallPermission | I allow voice calls with you, or no longer; when allowing, the ring key (section 10) |
| 22 | CallOffer | Rings: call id and the caller's half of the call key |
| 23 | CallAnswer | Picked up: call id and the callee's half of the call key |
| 24 | CallEnd | Ends or refuses a call, with a reason |

Besides message frames, a connection can carry a link bundle (frame 14) or a wipe key (frame 15),
both described in section 9, an attachment's file (frame 16, section 7), pings between
messages (frame 18, above), a call's audio (frames 19 and 20, section 10), or a ring for a
locked device (frame 21, section 10). Frame 5 was the
earlier attachment frame and is no longer accepted. Frame type 0 is never used; a device's check on its own address
sends it (section 3).

Every field is length-prefixed and bounds-checked on read.

Trailing optional fields (`replyToId`, `prevStateHash`, `displayName`) were added after the format
shipped. A frame that ends cleanly before them is accepted, since a peer on an older build simply does
not send them, and rejecting the message over a field carrying no security weight would mean
silently losing messages between peers on different builds.

A field that *starts* and then fails to parse is a different matter: that is truncation or
tampering, and the message is rejected rather than treated as though the sender had omitted it.

---

## 5. Offline delivery: Nostr mailboxes

Nostr relays are always-on public servers that store events and push them to subscribers, so
they are used as a mailbox when the recipient's onion service is unreachable.

Earlier versions used a peer-to-peer relay hosted by other NiiX phones, found through a Kademlia
DHT. It was removed: the phones hosting it tended to be offline exactly when they were needed,
hosting was off by default, and it meant every device accepted relay traffic from strangers.
Its wire types (10, 11) and frame types (6-13) are reserved; types 10 and 11 from older peers are
accepted and ignored, and relay frames are answered by closing the connection.

Nostr is a transport only. None of Nostr's messaging NIPs (04, 17, 44, 59) are used, and no
long-term Nostr identity exists.

### Delivery order

1. Direct to the recipient's onion service.
2. If that hasn't been acknowledged within five seconds, the same ciphertext is also published to
   the recipient's Nostr inbox relays, while the direct attempt carries on. Reaching a phone that
   is offline can take Tor a minute to give up on. Whichever copy arrives second is dropped as one
   already received. A publish counts once one relay has accepted it; the rest finish in the
   background.
3. Otherwise it stays pending and is retried on the usual schedule (for up to 30 days).

A message delivered through a relay shows as *Sent via relay* until the recipient's receipt
arrives. Retries keep attempting direct delivery on the usual schedule, but republish to Nostr at
most every 12 hours per message.

### Mailbox secrets

For each contact, the **recipient** generates a random 32-byte mailbox secret and sends it, with
the list of relays it listens on, in a `NostrInbox` wire message (type 16). This travels inside
the Signal session, so it has the same PQXDH protection as message content. It is sent directly
and counted as delivered only on acknowledgement; it is resent when the relay list changes, and an
empty secret withdraws it (sent when Nostr delivery is turned off).

A contact therefore has to have been reached directly once before Nostr delivery to them works.

### Derivation

Time is divided into weekly epochs, `epoch = floor(unix_seconds / 604800)`. With HKDF-SHA256,
salt `"niix-nostr-mailbox-v1"`:

| Value | Info | Use |
|---|---|---|
| bucket | `"bucket" ‖ epoch (u64 BE)` | first two bytes, big-endian, mod 1024: the mailbox's bucket |
| key | `"key" ‖ epoch (u64 BE)` | AES-256-GCM key |

The relay-visible tag is the bucket's, not the mailbox's:
`tag = hex(SHA-256("niix-nostr-bucket-v1" ‖ epoch (u64 BE) ‖ bucket (u16 BE)))`. Anyone can work
it out, and every mailbox in that bucket that week, whoever it belongs to, uses the same one.

### Event

| Field | Value |
|---|---|
| kind | 4459 (regular, stored) |
| tags | `["z", <tag>]`, `["expiration", created_at + 14 days]` (NIP-40) |
| content | base64(nonce₁₂ ‖ AES-GCM(key, signal_ciphertext, aad = salt ‖ tag)) |
| pubkey / sig | A fresh secp256k1 key per event, discarded after signing (BIP-340) |
| created_at | Current time, forced strictly increasing per device so that events sort in send order |

Envelopes larger than 40 KiB are not published (most relays cap events near 64 KiB).

### Receiving

The device holds one WebSocket subscription per inbox relay (at most six), with a filter on
`kind` and on the bucket tags of its mailboxes for the current epoch, the two before it and the
next one. For each epoch the buckets are padded with decoys to a multiple of 16, at least 16, so
the number asked for says little about the number of contacts. Decoys come from a random seed
kept on the device, so they stay the same for the whole epoch; ones that changed between
connections would show which buckets are real.

A bucket mostly holds other people's envelopes. Each one is tried against the keys of the
device's mailboxes in that bucket; one that opens tells the device which contact it is from, and
it is handed to the ordinary receive path, where Signal decryption authenticates the sender and
every receive-side check applies. The rest are skipped, and not remembered, since trying a key
costs next to nothing. Stored events are buffered until `EOSE`, then processed oldest first; live
events are processed as they arrive.

Event ids that have been handled are remembered (the last 512 persistently) so reconnections and
restarts do not reprocess them. A subscription resumes from its last caught-up time minus six
hours, to tolerate senders whose clocks run behind.

While the app is locked, the subscription continues using derived per-epoch keys held in memory,
and envelopes go to the locked inbox as ciphertext, exactly like direct messages. With
*Disconnect when locked* on, the subscription stops and those keys are dropped.

### Network

Every relay connection goes through Tor's SOCKS port with the host name unresolved, and with a
fresh random SOCKS username so Tor puts it on its own circuit. Clearnet relays must use `wss://`;
`ws://` is accepted only for `.onion` relays. Two of the four default relays are used at their
onion addresses, so for them nothing leaves Tor through an exit. The WebSocket client sends no User-Agent, Origin or
cookies.

### What a Nostr relay learns

| Learns | Does not learn |
|---|---|
| That some Tor user published an event of kind 4459 | The sender's identity, Nostr key or IP |
| Which of 1024 weekly buckets it went into | The recipient, or which mailbox in the bucket it's for |
| Envelope size (Signal padding applies) and time | Content, or which contact sent it |
| That one subscriber listens to a set of buckets | Which of them are real, how many contacts that is, or who the subscriber is |

### Known limitations

- A bucket mixes only the people using NiiX on that relay that week. While there are few, a
  bucket may hold one person's traffic alone, and counting it is close to counting theirs.
- Decoys hide how many contacts someone has, not every change: a contact added during a week can
  show up as one bucket swapped for another.
- Listening to buckets means downloading everyone's envelopes in them, about 1/64 of all NiiX
  traffic on the relay for most people. That is small now and would need fewer buckets per
  person, or more buckets, if NiiX grew large.
- Relays may drop events, refuse Tor exits, or rate-limit. Several are used; the defaults are
  public relays and can be replaced with one's own, including `.onion` relays.
- The kind number and tag letter make NiiX traffic recognisable as NiiX traffic.

---

## 6. Groups

Sender Keys, with a membership policy layered on top. **NiiX does not use MLS.**

Membership transitions are accepted only if:

1. The epoch strictly increases.
2. The sender is an admin, and is in the resulting member list.
3. Every admin is a member.
4. The claimed previous state hash matches the recipient's held state (once chaining is
   established for that group, this is permanent: an unchained transition is never accepted
   again).

State hash is SHA-256 over the ordered, length-prefixed conversation ID, epoch, members and
admins. Length-prefixing prevents a crafted member name from forging field boundaries.

Removing a member deletes their sender keys, scoped to members no longer sharing any group.

### Known limitation

This is a bespoke protocol. It is tested (31 dedicated cases) but has not been formally analysed,
and MLS would provide properties (notably efficient post-compromise security for large groups)
that this does not.

---

## 7. Attachments

1. A random 256-bit key is generated per attachment.
2. The file is encrypted with streaming AEAD, with **the attachment ID bound in as associated
   data**, so ciphertext cannot be decrypted as a different attachment even with the correct key.
3. The key travels inside the E2EE message, never with the file.
4. Transfer requires a token: `SHA-256(encryption key ‖ attachment ID)`, verified before any
   bytes are written.

The sender opens a connection with frame 16: the attachment ID, the token and the file's size.
The recipient answers before any of the file is sent. If it already has the whole file, from an
attempt whose acknowledgement was lost or from another of the sender's devices, it acknowledges at
once. Otherwise it answers with frame 17 and how many bytes it already has, and only the rest
follows. A transfer that breaks off keeps what arrived, so the next attempt resumes. The file is
checked against the digest from the offer once it's all there; if a resumed one doesn't match, the
partial file is thrown away and the next attempt starts over, and if one sent whole doesn't match,
the attachment is marked failed. One transfer per attachment runs at a time.

A message with an attachment counts as delivered once every device of every recipient has the
file, so a group member who was offline gets it when they come back. The sender's own other
devices are sent it too, and retried on the same schedule until they have it, but they don't hold
up the message's delivery.

The binding is **required, with no fallback**. Ciphertext that does not authenticate under its
own attachment ID is refused rather than retried unbound, because a fallback would hand the unbound path
to anyone able to strip the binding, which is the property it exists to prevent.

Consequence: attachments created before the binding no longer open. This was a deliberate trade
made while the only users were testers.

Such an attachment is retired on the first failed read: the ciphertext is deleted, since nothing
can ever decrypt it and no future version brings it back, and the record is marked FAILED so the
conversation shows that something was sent rather than silently omitting it. The UI says the
attachment is unavailable instead of offering to open it.

---

## 8. Storage

| Layer | Protection |
|---|---|
| Database | SQLCipher, key derived from passcode + hardware-backed device secret |
| Attachments | Tink streaming AEAD; a random key per attachment, kept in the database |
| Locked inbox | Signal ciphertext, sealed again with AES-GCM under a per-entry key wrapped by a Keystore RSA key (OAEP). Sealing uses only the public key, so it works while the phone is locked; opening needs the phone unlocked |
| Backup file | Argon2id over the passphrase (same parameters), then Tink streaming AES-GCM with the header as associated data. Inside: the database, under a random key that travels inside too, then the attachment and profile picture files, which are already encrypted with keys in the database. File names are checked against the patterns the app uses before anything is unpacked, and nothing live changes until the whole file has decrypted, reading to the end so a shortened file is refused. Version 1 backups (the database only) still restore |
| Locked inbox share | Direct deliveries name a sender nobody has checked yet, so they may take only 300 of the 500 entries and 40 of the 64 MB. The rest is kept for envelopes from relays, which arrive through mailboxes given to known contacts. Entries that the keystore won't open are kept for a week, not deleted, because they were already acknowledged |
| Receive copy | The onion key and Nostr inbox, for receiving while locked (section 3). AES-GCM under a Keystore key that works while the phone is locked, so not protected by the passcode. Someone able to run code as the app could take the onion key, answer on the address and see when envelopes arrive; messages stay unreadable. Absent with "Disconnect when locked" |

Passcode derivation uses Argon2id (64 MiB, 3 passes, 2 lanes), in native code when the device's
copy of it matches BouncyCastle on a self-test, BouncyCastle otherwise. The result is mixed with the
device secret by HKDF and handed to SQLCipher as a raw key, so SQLCipher's own PBKDF2 is not run on
top of it. Failed attempts are throttled with exponential backoff checked
against **both** wall-clock and elapsed-realtime, so setting the device clock backwards does not
reset it.

### Duress

A second passcode destroys everything (database, files, all Keystore aliases, cached data and the
relay state) and opens a new account that looks used: a few contacts with ordinary conversations
in the phone's language, spread over past months. Each decoy contact has a real identity key and a
real session, made by exchanging its messages with a stand-in device that exists only in memory
during setup. Throttling is applied *before* the duress check, so the duress code cannot be used
to bypass rate limiting.

### Remote wipe

Off by default. A wipe is honoured when it arrives from a contact the user nominated, over an
authenticated Signal session. Nomination is the authorisation.

Nominating a contact sends them a `RemoteWipeNomination`, so their device knows it may act;
revoking sends the same message with the flag cleared. Two lists are held in opposite directions:
who may erase this device, and who has nominated this device to erase theirs.

An earlier design also required a shared 256-bit token. It was dropped because it protected
against nothing the session did not already cover: presenting a wipe request requires control of
the nominated contact's device, and anyone with that also has whatever token was stored there.

No acknowledgement is ever sent: a refusal would be a guessing oracle, and a confirmation would
prove the device exists.

---

## 9. Multiple devices

An account can be on up to five devices. They share the account's identity key, so contacts'
safety numbers don't change when a device is added. Everything else is per device: each has its
own onion address, prekeys, registration id, sessions, sender keys and Nostr inbox. The account is
named by the address of the device it was created on, which stays its address in contacts' lists
and group memberships even if that device is later removed.

### Device lists

`Devices` carries the account's list: an epoch and up to five (device number, onion address)
pairs. It's sent inside a session with the account, which authenticates it, to every contact and
group member and to the account's own devices. A list is only accepted with a higher epoch than the
one held, and refused if it names an address that belongs to another account. An account that has
never sent one is assumed to be a single device, number 1, at the account's address, so a contact
on an older build stays reachable at that one device.

Sessions are libsignal sessions named by the account and the device number. Before the first
message to a device, its bundle is fetched from its own onion address and must carry the account's
identity key (the pinned one, if pinned) and the device number from the list.

Incoming frames name the device that sent them. The address is looked up in the stored lists to
find the account and device number; one that isn't listed is a separate account, as before. A
session-starting message from an unlisted address that claims a known contact's identity key is
not acknowledged until that contact's new list arrives, so one person never turns into two
contacts. One that claims our own key from an unlisted address is a removed device and is dropped.

### Sending

Every message to an account goes to each of its devices, directly over Tor and then to that
device's Nostr inbox. For retried messages the devices that acknowledged are recorded, so a retry
only goes to the rest; resending a group message to a device that already decrypted it would fail
and never be acknowledged. Attachments are pushed to every device that was offered them.

### Between our own devices

These types are only accepted from the account's own devices and are dropped from anyone else:

| Type | Carries |
|---|---|
| `SyncSent` | A message this account sent from another device, with the conversation it went to: texts, attachment offers, deletions, timer changes, group changes and group pictures |
| `ContactUpdate` | A contact added, renamed, verified, pinned or blocked |
| `WipeKey` | A new wipe key, after a device is removed |

Profile updates from our own devices set our own name and picture. Copies are best effort: a
device that's offline gets them from its Nostr inbox.

### Linking

The new device is set up with its own passcode and shows a QR code holding its onion address and
a random 32-byte secret. A device of the account scans it, connects to that address over Tor and
sends a link bundle: the identity key pair, the new device's number, the account's device list and
wipe key, the display name, contacts (with identity keys, pins and their device lists), groups
(members, admins, epoch and chain state) and the blocklist. No message history.

The bundle is sealed with AES-256-GCM under HKDF-SHA256 of the secret, with the new device's
address as associated data. Only a device that saw the QR code can produce one that opens, and the
connection is to the address in the code, so only that device receives it. The new device applies
it in one database transaction, replacing its own identity, and acknowledges; the linking device
then publishes the new device list.

### Removing a device and wiping

The devices share a random 32-byte wipe key. Each keeps it, with the other devices' addresses,
encrypted under a Keystore key outside the database, plus the key's SHA-256 in a separate file.
Every install has both, with a random key on a single device.

Erasing any device (manually, by the duress passcode, by a remote wipe or because it was taken
off the account) sends the wipe key to the other devices in a `WIPE` frame before the local data
goes, or, under the duress passcode, once the decoy's Tor is up, so the decoy doesn't appear late.
A device that receives a frame whose key matches the stored hash erases itself. The check needs
neither the database nor the Keystore, so it works on a locked phone. The sealed copy needs the
Keystore, so sending works whenever the phone is unlocked, which it is whenever someone is typing
a passcode into the app.

Removing a device sends it the wipe key, publishes a list without it, and moves the remaining
devices to a new wipe key, since the removed device knows the old one. The new key goes to each
remaining device directly and through its Nostr inbox, and keeps being resent, on the same
schedule as an unsent message, until every device has acknowledged it directly. A `WipeKey`
carries the epoch of the device list it came with, and a device only takes a key with a higher
epoch than its own, so a copy delayed on a relay can't bring an older key back. Two devices
removing others at once can make two keys for the same epoch; then the larger key wins, and every
device ends up with the same one.

### Limits

- A removed device that was lost or taken keeps the identity key and everything it had received.
  Contacts stop sending to it once the new list reaches them, but its key stays the account's key.
- Message history isn't copied to a new device, and read state isn't shared between devices.
- Until a device that was offline gets the new wipe key, it and the others can't wipe each other.

---

## 10. Voice calls

One-to-one voice calls, only between two people who have both allowed them. There are no group
calls and no video.

### Consent

Calls are off for every contact until the user turns them on for that contact, and they can only
be turned on for a contact whose safety number has been verified, so their identity key is
pinned. Turning them on records the contact's identity key with the permission; if the key
changes, calls are off again until the user turns them back on. Each side tells the other with a
`CallPermission` message inside the Signal session, sent directly or through the contact's Nostr
inbox, and resent until it lands. The same setting is copied to the account's other devices in
`ContactUpdate`.

A call can be placed once the caller has allowed it; the caller's phone doesn't wait to hear that
the callee has too, since that message can lag over Tor and a stale "not yet" would block a call
both people want. The rule that matters is the callee's: an offer from someone the callee hasn't allowed, or who
isn't a verified contact, is refused before anything rings or any audio connection can exist. It
is answered with a `CallEnd` saying "not permitted", which tells the caller only what they already
knew. Each call also keeps that knowledge current: an offer or an answer from someone shows they
allow calls, and every one of their devices refusing as not permitted shows they don't, whatever
their last `CallPermission` said. Turning calls off, by either side, ends a call in progress at once. Blocking someone turns
calls with them off, and unblocking doesn't turn them back on.

### Ringing a locked device

While the app is locked its database, and with it the Signal session, is closed: a `CallOffer`
that arrives can be stored but not read. So that a call can ring anyway, a `CallPermission` that
allows calls carries a 32-byte *ring key*:

```
ringRoot = HMAC-SHA256(identity key pair, "niix-ring-root-v1")
ringKey  = HMAC-SHA256(ringRoot, contact's account address)
```

It is different for every contact and can be derived again at any time, so nothing is stored for
it on the device that issues it except, outside the database, the *ring beacon*: the ring keys of
the contacts currently allowed to call, in a file encrypted with a hardware-backed key (the
desktop keyring on Linux) that can be opened while the app is locked. It holds no addresses and
no names. It is rewritten whenever a permission changes and deleted with the setting, with
"Disconnect when locked", and in a wipe.

When a call starts, the caller sends each of the callee's devices the offer as usual and, where
it holds a ring key, also opens a connection with frame 21 and a 56-byte token:

```
sentAt(8) || nonce(16) || HMAC-SHA256(ringKey, "niix-ring-v1" || 0 || callee device address || 0 || sentAt || nonce)
```

An unlocked device ignores it: it has the offer. A locked one accepts it if `sentAt` is within
two minutes, the nonce hasn't been seen, and the MAC matches a key in the beacon. Then it rings,
showing only that a call is coming in, for as long as the connection stays open and at most as
long as an offer rings. Nothing is sent back unless the user declines, which sends one byte (1)
and ends the call for the caller; the caller closes the connection when the call is answered,
given up or ended, which stops the ringing. Answering asks for the passcode; unlocking reads the
queued offer, which is checked exactly as any offer is (permission, verified key, one call at a
time), and is then answered without a second tap. A ring whose offer never arrives, or fails
those checks, ends with nothing more than having rung.

What a ring key is worth to someone who steals it from a contact's device: making this device
ring while that contact is still allowed to call. It doesn't identify the caller to the device
(the beacon has no names), opens nothing, and stops working when calls with that contact are
turned off. The token is bound to the device address and the time, so one captured in transit
can't be replayed elsewhere or later, and it travels inside Tor's end-to-end encrypted onion
connection like every frame. The call itself is unchanged: its keys still come only from the
offer and answer inside the Signal session.

### Signalling

`CallOffer`, `CallAnswer` and `CallEnd` travel inside the Signal session like any message, so they
have its protection, post-quantum included. They go **directly only**, never through a Nostr
relay: a call that can't reach someone now is over, and a relay would only keep a record that it
was attempted. One that arrives through a relay anyway is ignored.

1. The caller sends `CallOffer` (a random UUID call id, 32 random bytes, and the time by its own
   clock) to every device of the callee. Devices that acknowledge it ring.
2. A callee device that is picked up sends `CallAnswer` (the same id and its own 32 random bytes)
   to the caller's device that sent the offer, and no other.
3. The caller takes the first answer, tells the callee's other devices `CallEnd` (answered
   elsewhere), and opens the audio connection to the device that answered.

While a call rings, each side holds open a connection to the other's ringing device (the callee
to the device that rang it, the caller to every device that took the offer). Tor uses one circuit
per onion for all connections to it, so the answer, and then the audio connection, go out over a
circuit that's already built instead of waiting for a new one, which is most of the delay over
Tor. The connections are let go when the call ends. Holding them tells each side nothing it
doesn't already know: the offer's acknowledgement already showed the callee was online.

An offer more than two minutes old or ahead by the receiver's clock isn't rung, only reported as
a missed call; that's what happens to one that waited in the locked inbox. A call already in
progress answers a new offer with `CallEnd` (busy). When two people call each other at the same
moment, both phones keep the call with the higher id and drop the other, and the surviving call is
picked up straight away.

Rings stop after 60 seconds at the caller (70 at the callee). From picking up, the audio has 60
seconds to start flowing.

### Keys

    prk   = HKDF-Extract(salt = "niix-call-v1", offer secret ‖ answer secret)
    ctx   = len ‖ call id ‖ len ‖ caller account ‖ len ‖ callee account   (lengths u16 BE)
    key_i = HKDF-Expand(prk, label_i ‖ 0x00 ‖ ctx, 32)

with labels `caller-to-callee` and `callee-to-caller` (AES-256-GCM keys, one per direction),
`caller-proof` and `callee-proof` (32-byte proofs), and `safety-code` (8 bytes, shown as eight
digits). Both secrets travel only inside the Signal session, so the call keys are exactly as
strong as that session: PQXDH and the Triple Ratchet, with nothing classical-only underneath.
Everything derived from them is symmetric. Secrets and keys are fresh for every call and erased
when it ends, so nothing left on either phone afterwards can decrypt it.

### Audio connection

The audio goes over its own Tor connection, onion to onion, from the caller to the onion address
of the device that answered. There is no WebRTC, ICE, STUN or TURN: those exist to find a direct
path between the two phones, and a direct path is what would reveal their IP addresses. Neither
phone learns the other's, and to anyone watching the network a call is Tor traffic like any other.

    caller → callee:  19 ‖ len ‖ caller device address ‖ len ‖ call id ‖ 32 ‖ caller proof
    callee → caller:  20 ‖ callee proof

The callee accepts the connection only from the address of the device that rang it, for the call
it picked up, with the right proof (compared in constant time), and otherwise closes it without
a word. The caller checks the callee's proof before any audio flows. Each side has then shown it
holds keys only the two of them could derive. After that each direction carries records:

    u16 BE length ‖ AES-256-GCM(key, nonce = 32 zero bits ‖ u64 BE record counter, plaintext)

The counter is never sent: both ends count, so a record that is dropped, repeated, reordered or
altered fails to open and the call ends. The plaintext is a type (audio or hangup), a codec, a
packet count and up to four length-prefixed voice packets, **padded to a fixed size**, and records
go out **every 60 ms whether anyone is speaking or not**, muted included. Variable-sized voice
packets are known to give away a surprising amount of what is said, and gaps give away when;
fixed records at a fixed rate give away only that a call is going on and how long it lasts.

| Codec | Audio | Record plaintext | On the wire |
|---|---|---|---|
| Opus | 16 kHz mono, 20 ms packets, 16 kbit/s constant where the phone supports it | 320 bytes | ≈ 45 kbit/s |
| G.711 µ-law | 8 kHz mono, 20 ms packets; only if a phone can't encode Opus | 656 bytes | ≈ 90 kbit/s |

Muting closes the microphone; records keep going out on the same schedule, with no voice
packets in them, so muting doesn't show on the wire either.

Because records arrive at a known rate whatever is said, the receiving phone can tell how the
circuit is doing from them alone: under 80% of the records due in the last three seconds, or none
for 400 ms, shows as a slow connection; none for three seconds as a stalled one. It shows worse at
once and better only after two seconds of records arriving on time, so it doesn't flicker.
Twenty seconds without a record ends the call as lost. A hangup is sent in-band and as a
`CallEnd`, so whichever arrives first ends it.

### Safety code

Both screens show the same eight digits, derived from the call keys. Read aloud and matching, they
confirm nobody is relaying the call between two different sets of keys. The real protection is
the verified Signal session the secrets came through; the code is a check the two people can do
themselves.

### Limits

- **Delay.** Tor adds roughly half a second to a second and a half each way, depending on the
  circuits: like an old satellite call. Audio is buffered for about 160 ms against uneven arrival
  and never more than 600 ms; past that the oldest is dropped.
- **A locked device rings without knowing who calls.** The offer itself can't be read until the
  passcode is entered; see "Ringing a locked device" above. With that turned off, or with a peer
  on a build without it, an offer waits in the locked inbox and shows as a missed call after
  unlocking.
- **A call is visible as traffic.** Someone watching your connection sees steady Tor traffic for
  the length of the call. They can't see who it's with, or that it's a call rather than anything
  else, but a long, steady stream at a call's bitrate is a recognisable pattern.
- No call history is kept. A missed call is a notification and nothing more.

---

## 11. What NiiX does not protect against

Stated plainly, because a threat model that lists only successes is marketing.

- **A compromised endpoint.** Screen capture, a keylogger, or a malicious keyboard defeats
  everything here. No protocol fixes this.
- **A global passive adversary.** Tor's own threat model excludes an observer who can watch the
  whole network and correlate timing. NiiX inherits that limitation.
- **A malicious contact.** Anyone you talk to can screenshot, record, or repeat what you said.
- **Traffic confirmation during calls.** A call is a sustained stream at a steady rate for its
  whole length. An adversary watching both phones' connections can correlate the two streams far
  more easily than occasional messages. See section 10.
- **Metadata your device leaks elsewhere.** Other apps, the OS, and the network operator are
  outside this boundary.
- **Forging group message signatures with a quantum computer.** See section 2.

---

## 12. Deliberate omissions

Absent by choice rather than oversight:

- **Video calls and group calls.** Voice calls exist (section 10), one to one, over Tor. Video
  over Tor would need many times the bandwidth, and group calls would multiply both the traffic
  and the consent between every pair of people.
- **Direct (non-Tor) calls.** Faster, but they show each side the other's IP address, which is
  the one thing NiiX exists not to do.
- **Message backup to a server.** There is no server. Nostr relays (section 5) hold encrypted
  envelopes for up to 14 days for delivery only; they are not a backup and cannot be read.
- **Contact discovery.** Deliberate: no lookup service means no social graph to seize.
