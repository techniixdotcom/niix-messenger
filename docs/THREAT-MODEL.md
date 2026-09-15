# NiiX Threat Model

Adversary classes A–G, and what NiiX actually does against each. Read with `PROTOCOL.md`.

The purpose of writing this down is to make the claims falsifiable. "It's encrypted" is not a
security property; "adversary B cannot learn X because of mechanism Y" is one, and can be shown
to be wrong.

---

## A — Passive network observer

*Sees traffic, timing, sizes, connection patterns.*

| Defence | Mechanism |
|---|---|
| Content | E2EE; the observer sees Tor cells |
| Size | Padding to 256 / 1024 / 4096 / 16384 byte buckets |
| Endpoints | Onion-to-onion; no IP of either party is exposed |
| Timing | Optional cover traffic (off by default) |

**Residual:** connection timing to the Tor network still reveals *that* the app is in use and
roughly when. Padding hides exact sizes, not the fact of a message.

---

## B — Malicious relay

*Can drop, delay, replay, or modify what passes through it.*

| Attempt | Outcome |
|---|---|
| Read messages | Fails — relays hold ciphertext only |
| Learn who is talking to whom | Sees an opaque recipient hash, never the sender |
| Modify an envelope | Fails — AEAD authentication |
| Replay a fetch proof | Refused — each proof honoured once |
| Serve someone else's envelopes | Requires possession of their identity key |
| Store unsolicited envelopes | Requires a grant the recipient issued |
| Drop messages | **Succeeds.** Mitigated by using multiple relays |
| Poison routing | Only transient lookup hints; the routing table requires signed node identity |

**Residual:** a relay can silently drop. Availability is not guaranteed, only confidentiality and
integrity.

---

## C — Colluding relays

*Multiple relays share what they see.*

Each still sees only recipient hashes and ciphertext. Correlating store and fetch for the same
hash reveals that *someone* fetched for *some* recipient — not who either party is, since both
connect over Tor.

**Residual:** an adversary running a large fraction of relays could do statistical correlation
over time. This is the same limitation Tor has, and NiiX does not solve it.

---

## D — Compromised endpoint

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

**Residual:** none of this survives an attacker who can run code as the app.

---

## E — Global passive adversary

*Watches a large fraction of the internet.*

Inherited from Tor: an adversary who can observe both ends of a circuit can correlate timing
regardless of encryption. Cover traffic raises the cost; it does not close the gap.

**NiiX does not defend against this, and should not claim to.**

---

## F — Future quantum adversary

*Records traffic now, decrypts later.*

| Phase | Status |
|---|---|
| Initial key agreement | **Protected** — Kyber-1024 prekeys (PQXDH) |
| Ongoing session ratchet | **Not protected** — classical Curve25519 |

Harvest-now-decrypt-later against a *session start* fails. An adversary with both a quantum
computer and compromised long-term state could attack a continuing session.

Closing this needs continuous post-quantum ratcheting. Not implemented.

---

## G — Malicious contact

*Someone you have accepted.*

| Attempt | Outcome |
|---|---|
| Impersonate a third party | Fails — identity keys are pinned on verification |
| Substitute their own key later | Fails — a pinned key cannot be replaced |
| Add themselves to a group | Fails — requires admin, and the state chain must descend |
| Remove others and take over | Fails — admin-subset and sender-consistency checks |
| Replay an old membership state | Fails — epochs must strictly increase |
| Flood with messages | Throttled — 120/min and 16 MB/min per sender |
| Oversized or malformed input | Bounds-checked; images guarded against decompression bombs |
| Trigger a remote wipe | Requires having been nominated by the target, locally |
| Add themselves to your "wipe another device" list | Requires a verified, pinned identity |
| Expire your copy of someone else's message | Read receipts only start expiry on the sender's own copy |
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
