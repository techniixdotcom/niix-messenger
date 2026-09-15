# NiiX Protocol Specification v1

This describes what NiiX actually does, not what it aspires to. Where the implementation is
weaker than it could be, that is stated rather than omitted — a specification that flatters the
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

Device ID is fixed at 1: **NiiX is single-device.** Multi-device would require a device-linking
protocol that does not exist here.

### Contact exchange

Out of band only — QR code or a copied code containing the onion address and identity key. There
is no directory, no lookup, and no discovery protocol, so there is no service that could be asked
who knows whom.

### Verification

Safety numbers are generated with libsignal's `NumericFingerprintGenerator` (5200 iterations)
over both identity keys and both onion addresses. Marking a contact verified **pins their
identity key**: subsequent messages whose identity does not match the pin are refused, and the
identity store will not replace a pinned key.

---

## 2. Key agreement and message encryption

libsignal's X3DH/PQXDH with the Double Ratchet. NiiX does not implement its own cryptography.

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

### Known limitation: the ratchet is classical

The *handshake* is post-quantum. The ongoing Double Ratchet is not — it derives new keys from
Curve25519 DH. An adversary who obtained a quantum computer *and* compromised long-term state
could attack the continuing session in a way they could not attack the initial agreement.

Fixing this requires continuous post-quantum ratcheting (Signal's SPQR / ML-KEM Braid). It is not
implemented and depends on libsignal exposing it.

---

## 3. Transport

Every connection is a Tor v3 onion service. There is no clearnet path for messaging.

- Each client runs its own hidden service; its address is its identity.
- Connections are made onion-to-onion, so **neither party learns the other's IP address**, and
  this is a property of the transport rather than a setting.
- Inbound connections are capped at 24 concurrent with a 30-second read timeout.

### Padding

Plaintext is padded to one of `256 / 1024 / 4096 / 16384` bytes before encryption; larger
payloads pad to a multiple of 16384. An observer sees a bucket, not a length.

### Connection lifecycle

The Tor connection is held by a foreground service, which Android requires to show a notification
for as long as it runs.

By default the service stops about a minute after the screen goes off. The notification goes with
it, and an alarm wakes the app every 30 minutes to connect, collect whatever the relays are
holding, and stop again. Unlocking the phone reconnects immediately.

The 30-minute figure is a floor imposed by the platform as much as a choice: `setExactAndAllowWhileIdle`
is the only scheduling that fires during Doze, and Android rate-limits it to roughly once every
nine minutes per app. Shorter intervals also stop saving anything, because each wakeup pays for a
fresh Tor bootstrap.

Consequence: while asleep the onion service is unreachable, so senders fall back to relays. That
requires a relay grant, which contacts receive automatically. Relays hold envelopes for up to six
hours, so a missed wakeup costs delay rather than messages.

Sleeping is skipped entirely while relay hosting is enabled. A relay has to be reachable for
other people's messages to reach it, so disconnecting would take it offline for most of the day
and silently fail the peers depending on it.

Tor's own dormant mode is unavailable here. `DormantClientTimeout` explicitly does not affect
onion services, and every NiiX install hosts one, so Tor's largest built-in battery feature
cannot apply. `ReducedConnectionPadding` and `ReducedCircuitPadding` are enabled instead: both
are provided by Tor for mobile use, and both reduce idle traffic at some cost to traffic-analysis
resistance.

Phone manufacturers can and do ignore these alarms. Nothing in the app prevents that; excluding
NiiX from the vendor's battery optimisation is the only fix.

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
| 10 | RelayGrant | Permission to relay to the sender |
| 11 | RelayCapabilityUpdate | Peer is hosting a relay |
| 12 | Dummy | Cover traffic |
| 13 | GroupLeave | Voluntary departure |
| 14 | RemoteWipe | Erase this device |
| 15 | RemoteWipeNomination | You may / may no longer erase my device |

Every field is length-prefixed and bounds-checked on read.

Trailing optional fields (`replyToId`, `prevStateHash`, `displayName`) were added after the format
shipped. A frame that ends cleanly before them is accepted — a peer on an older build simply does
not send them, and rejecting the message over a field carrying no security weight would mean
silently losing messages between peers on different builds.

A field that *starts* and then fails to parse is a different matter: that is truncation or
tampering, and the message is rejected rather than treated as though the sender had omitted it.

---

## 5. Offline delivery: relays

When a recipient is unreachable, the sender stores an envelope on relay nodes.

**A relay learns:** an opaque recipient hash, the ciphertext, a size, and a TTL.
**A relay does not learn:** who sent it, who the recipient is, or what it says.

| Parameter | Value |
|---|---|
| Max envelope | 8 KiB |
| Max per recipient hash | 50 |
| TTL | 5 minutes to 6 hours |
| Fetch proof window | 2 minutes |

### Authorisation

Storing for a recipient requires a **grant** they issued, signed by their identity key. Fetching
requires a proof of possession of the recipient's identity key. Each proof is honoured **once**;
replays are refused.

### Node discovery

A Kademlia DHT over onion addresses. Nodes inserted into the routing table must present a
self-signed node identity binding their node ID to their onion address. Candidates named in a
`find_node` response are *not* authenticated — they are transient lookup hints only, and anything
actually contacted must prove its identity before being kept.

Proofs are **bound to the relay they are sent to**: the relay's own onion address is part of what
is signed, and each relay verifies against its own. A proof presented to the wrong relay does not
verify, so one observed in transit cannot be used elsewhere. Fetch and delete proofs are
domain-separated so neither can be repurposed as the other.

### Known limitation

Proofs are timestamped rather than challenge-response. Within its window, a proof can still be
replayed at the relay it was made for, which the replay cache catches. A relay-chosen nonce would
remove the window entirely and needs another round trip.

---

## 6. Groups

Sender Keys, with a membership policy layered on top. **NiiX does not use MLS.**

Membership transitions are accepted only if:

1. The epoch strictly increases.
2. The sender is an admin, and is in the resulting member list.
3. Every admin is a member.
4. The claimed previous state hash matches the recipient's held state (once chaining is
   established for that group, this is permanent — an unchained transition is never accepted
   again).

State hash is SHA-256 over the ordered, length-prefixed conversation ID, epoch, members and
admins. Length-prefixing prevents a crafted member name from forging field boundaries.

Removing a member deletes their sender keys, scoped to members no longer sharing any group.

### Known limitation

This is a bespoke protocol. It is tested (31 dedicated cases) but has not been formally analysed,
and MLS would provide properties — notably efficient post-compromise security for large groups —
that this does not.

---

## 7. Attachments

1. A random 256-bit key is generated per attachment.
2. The file is encrypted with streaming AEAD, with **the attachment ID bound in as associated
   data**, so ciphertext cannot be decrypted as a different attachment even with the correct key.
3. The key travels inside the E2EE message, never with the file.
4. Transfer requires a token: `SHA-256(encryption key ‖ attachment ID)`, verified before any
   bytes are written.

The binding is **required, with no fallback**. Ciphertext that does not authenticate under its
own attachment ID is refused rather than retried unbound — a fallback would hand the unbound path
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
| Files | Tink streaming AEAD, keyset wrapped by Android Keystore |
| Locked inbox | Separate Keystore alias; messages queued as ciphertext while locked |

Passcode derivation uses Argon2id. Failed attempts are throttled with exponential backoff checked
against **both** wall-clock and elapsed-realtime, so setting the device clock backwards does not
reset it.

### Duress

A second passcode unlocks to an empty state and destroys everything: database, files, all
Keystore aliases, cached data, and the relay state. Throttling is applied *before* the duress
check, so the duress code cannot be used to bypass rate limiting.

### Remote wipe

Off by default. A wipe is honoured when it arrives from a contact the user nominated, over an
authenticated Signal session. Nomination is the authorisation.

Nominating a contact sends them a `RemoteWipeNomination`, so their device knows it may act;
revoking sends the same message with the flag cleared. Two lists are held in opposite directions:
who may erase this device, and who has nominated this device to erase theirs.

An earlier design also required a shared 256-bit token. It was dropped because it protected
against nothing the session did not already cover — presenting a wipe request requires control of
the nominated contact's device, and anyone with that also has whatever token was stored there.

No acknowledgement is ever sent: a refusal would be a guessing oracle, and a confirmation would
prove the device exists.

---

## 9. What NiiX does not protect against

Stated plainly, because a threat model that lists only successes is marketing.

- **A compromised endpoint.** Screen capture, a keylogger, or a malicious keyboard defeats
  everything here. No protocol fixes this.
- **A global passive adversary.** Tor's own threat model excludes an observer who can watch the
  whole network and correlate timing. NiiX inherits that limitation.
- **A malicious contact.** Anyone you talk to can screenshot, record, or repeat what you said.
- **Traffic confirmation during calls.** Not applicable yet — there are no calls.
- **Metadata your device leaks elsewhere.** Other apps, the OS, and the network operator are
  outside this boundary.
- **Quantum attack on an ongoing session.** See section 2.

---

## 10. Deliberate omissions

Absent by choice rather than oversight:

- **Multi-device.** Would require device linking and key synchronisation.
- **Voice and video calls.** A substantial subsystem with different latency constraints; adding
  it naively would leak IP addresses.
- **Message backup to a server.** There is no server.
- **Contact discovery.** Deliberate: no lookup service means no social graph to seize.
