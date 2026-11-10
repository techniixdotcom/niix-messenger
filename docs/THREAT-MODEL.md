# NiiX Threat Model

Adversary classes A–G, and what NiiX actually does against each. Read with `PROTOCOL.md`.

The purpose of writing this down is to make the claims falsifiable. "It's encrypted" is not a
security property; "adversary B cannot learn X because of mechanism Y" is one, and can be shown
to be wrong.

---

## A. Passive network observer

*Sees traffic, timing, sizes, connection patterns.*

| Defence | Mechanism |
|---|---|
| Content | E2EE; the observer sees Tor cells |
| Size | Padding to 256 / 1024 / 4096 / 16384 byte buckets |
| Endpoints | Onion-to-onion; no IP of either party is exposed |
| Timing | Optional cover traffic (off by default) |
| Calls | Over Tor like messages; fixed-size audio records at a fixed rate, muted or not |

**Residual:** connection timing to the Tor network still reveals *that* the app is in use and
roughly when. Padding hides exact sizes, not the fact of a message. A voice call is a steady
stream for as long as it lasts, which shows that *something* long-running is going on, and for how
long, though not with whom or what is said.

---

## B. Malicious Nostr relay

*A public server holding mailbox envelopes for offline recipients (PROTOCOL.md §5). Can drop,
delay, replay, or modify what passes through it.*

| Attempt | Outcome |
|---|---|
| Read messages | Fails: AES-GCM under a per-contact key, around Signal ciphertext |
| Learn the sender | Fails: a fresh Nostr key per event, published over its own Tor circuit |
| Learn the recipient | Sees only which shared buckets someone listens to; never an identity key or onion |
| Link a recipient's mailboxes | Fails: mailboxes share buckets with everyone else's, padded with decoys |
| Count traffic between two people | Fails while others share the bucket; see the residual |
| Modify or inject envelopes | Fails: needs the mailbox key; Signal then authenticates the sender |
| Replay an envelope | Dropped: Signal refuses a consumed message key; ids are remembered |
| Drop or delay | **Succeeds.** Mitigated by publishing to several relays and by direct retries |
| Learn either IP address | Fails: Tor only, hostnames never resolved on the device |

**Residual:** Nostr relays are third-party servers. They learn when a Tor user publishes or
subscribes, envelope sizes, and per-week traffic volume per bucket. With few NiiX users on a relay,
a bucket may be one person's alone, which brings back counting their traffic. Availability is not
guaranteed, only confidentiality and integrity. Anyone can run a relay; using one's own or a
`.onion` relay removes the third party entirely.

---

## C. Colluding relays

*Multiple relays share what they see.*

Each still sees only bucket tags and ciphertext. A bucket's tag is the same on every relay that
week, so colluding relays can combine their views of a bucket. That reveals *someone* published
for *someone* in that bucket, not who either party is, since both connect over Tor.

**Residual:** an adversary running a large fraction of relays could do statistical correlation
over time. This is the same limitation Tor has, and NiiX does not solve it.

---

## D. Compromised endpoint

*The attacker controls the device.*

**NiiX loses.** This is stated plainly because the alternative is dishonest. Screen capture, a
keylogger, or a malicious keyboard defeats every mechanism in the app.

Partial mitigations, which slow an attacker with *temporary physical access* rather than one with
code execution:

- Passcode-gated database; keys are hardware-backed
- Duress passcode destroying all data
- Blur on new messages, so a glance at an unlocked phone shows nothing
- Screenshot blocking (optional)
- Locked inbox: messages arriving while locked stay ciphertext
- Remote wipe by a nominated contact
- Optional wipe of a device that goes unused for a chosen time, 3 hours to 7 days, with no network
  needed
- The database closes a minute after leaving the app
- A wipe on any of the account's devices, including by duress passcode, erases the others, and a
  locked phone acts on it

**Residual:** none of this survives an attacker who can run code as the app.

**Receiving while locked.** So that messages keep arriving after Android ends the app or the phone
restarts, the onion key and the Nostr inbox secrets are kept outside the passcode-protected
database, under a Keystore key that works while the phone is locked. Someone running code as the
app could take them without the passcode, answer on this device's address and see when envelopes
arrive for it; reading messages still needs the database. "Disconnect when locked" removes this
copy, for anyone who would rather receive nothing until they unlock.

**Ringing while locked.** For a call to ring on a locked NiiX, the ring keys of the contacts
allowed to call are kept the same way, outside the database (PROTOCOL.md, section 10). They carry
no names or addresses. Someone running code as the app could take them and learn how many
contacts may call; someone who takes a ring key from a contact's device can make this one ring,
and nothing else. A locked device that rings shows only that a call is coming in. "Ring while
NiiX is locked" off, or "Disconnect when locked" on, removes the keys.

**On a Linux computer.** There is no keystore chip, so the secret combined with the passcode is
kept in the desktop keyring (the Secret portal inside the Flatpak), or, where there is none, in a
file only the user can read. Either way anything running as the same user while the keyring is
unlocked can read it, so on a computer the passcode, not the device, is what stands between a
copied disk and the messages. The Flatpak sandbox keeps NiiX's own files out of other sandboxed
apps' reach, but not out of the reach of programs running unsandboxed as the same user.

**More devices, more exposure.** Every linked device holds the account's identity key and receives
every message, so compromising any one of them is as good as compromising the account. Removing a
device stops new messages reaching it and tells it to erase itself, but a device that was already
taken keeps the key and what it had received. Only link devices you control, and keep the list
short.

---

## E. Global passive adversary

*Watches a large fraction of the internet.*

Inherited from Tor: an adversary who can observe both ends of a circuit can correlate timing
regardless of encryption. Cover traffic raises the cost; it does not close the gap. Calls make
this easier: two steady streams that start and stop together are much simpler to match than
occasional messages.

**NiiX does not defend against this, and should not claim to.**

---

## F. Future quantum adversary

*Records traffic now, decrypts later.*

| Phase | Status |
|---|---|
| Initial key agreement | **Protected**: Kyber-1024 prekeys (PQXDH) |
| Ongoing session ratchet | **Protected**: SPQR runs alongside the Double Ratchet |
| Group message keys | **Protected**: sent inside one-to-one sessions, derived symmetrically |
| Group message signatures | **Not protected**: Ed25519, so forgeable, though not readable |
| Voice calls | **Protected**: call keys come from secrets sent inside the session, then HKDF and AES-256 |

Harvest-now-decrypt-later fails against both the session start and the messages that follow.

---

## G. Malicious contact

*Someone you have accepted.*

| Attempt | Outcome |
|---|---|
| Impersonate a third party | Fails: identity keys are pinned on verification |
| Claim an address they don't own | Fails: a new address has to serve the same key over Tor |
| Substitute their own key later | Fails: a pinned key cannot be replaced |
| Add themselves to a group | Fails: requires admin, and the state chain must descend |
| Remove others and take over | Fails: admin-subset and sender-consistency checks |
| Replay an old membership state | Fails: epochs must strictly increase |
| Flood with messages | Throttled: 120/min and 16 MB/min per sender |
| Oversized or malformed input | Bounds-checked; images guarded against decompression bombs |
| Trigger a remote wipe | Requires having been nominated by the target, locally |
| Add themselves to your "wipe another device" list | Requires a verified, pinned identity |
| Call you without your consent, or to probe whether you're online | Fails: refused before ringing unless you allowed calls with that verified contact; nothing rings and no audio connection is accepted |
| Keep listening after you withdraw permission | Fails: withdrawing ends the call at once on your side |
| Learn your IP address through a call | Fails: calls go onion to onion over Tor; no WebRTC, STUN or TURN |
| Record the call | **Succeeds.** Inherent, as for anything said |
| Expire your copy of someone else's message | Read receipts only start expiry on the sender's own copy |
| Keep a seized phone offline so remote wipe never arrives | Optional inactivity wipe erases the device after a chosen time, from 3 hours to 7 days, without being unlocked or used. Time is counted with Android's time since boot, which can't be set, so winding the clock back doesn't delay it. Time spent switched off with the clock wound back isn't counted: there is no trusted time offline |
| Learn from the filesystem that a duress code exists | The verifier is keystore-wrapped and the filenames say nothing |
| Stop messages disappearing by changing the device clock | Expiry uses a clock that never reports a time earlier than one already seen |
| Screenshot or repeat what you said | **Succeeds.** Inherent to communication |

**Residual:** anyone you talk to can record what you say to them. No protocol changes this.

---

## Assumptions

If any of these is false, the analysis above does not hold:

1. libsignal's implementation of X3DH/PQXDH and the Double Ratchet is correct.
2. Tor provides the anonymity properties it claims.
3. The Android Keystore protects keys against an attacker without code execution.
4. SQLCipher and Tink are correctly implemented.
5. The device is not already compromised at install time.
6. The user verifies safety numbers out of band before trusting a contact.

Assumption 6 is the one most often false in practice. An unverified contact is trust-on-first-use:
secure against a later attacker, not against one present at the first exchange.
