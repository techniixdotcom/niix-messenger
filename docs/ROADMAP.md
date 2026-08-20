# Roadmap

## Implemented and working today

This is a real, working app end to end, not a foundation-only skeleton — on-device build and
review confirm the following are implemented and wired together, not stubbed:

- **Transport:** embedded Tor (`KmpTorTransport`, via `kmp-tor`) with a persistent v3 onion
  service, inbound accept loop, and outbound SOCKS connections — no external Tor/Orbot
  required. An alternate implementation for connecting to an external Tor process also exists
  in `:core:transport` (`RealTorTransport`), not wired by default.
- **App lock:** Argon2id passcode mixed with the Keystore secret to derive the DB key; lock
  screen; auto-lock on timeout; manual lock.
- **Duress passcode:** wipes the real data and opens a fresh account seeded with plausible
  placeholder conversations, rather than an obviously empty app.
- **Calculator disguise:** real launcher icon disabled by default in favor of a calculator
  activity-alias; opens the real app on correct passcode entry.
- **Contact exchange:** QR display and QR scanning (`PortraitCaptureActivity`) for
  onion-address + identity-key bundles, plus safety-number verification in the chat screen.
- **Messaging domain:** conversations (direct + group), typed wire protocol + codec, send/
  receive over the real transport.
- **1-on-1 and group messaging:** 1-on-1 sessions and group message content now use libsignal's
  persisted sender-key protocol (`GroupCryptoEngine`, `SenderKeyStore` backed by the
  `group_sender_keys` table) rather than in-memory, non-persisted state — see "Recently
  completed" below for exactly what that covers and doesn't yet.
- **Attachments:** per-file streaming AEAD encryption with a sendable per-file key, actual byte
  transfer over the transport (not just a metadata offer), and automatic retry on a failed
  send.
- **Disappearing-message timers** + background expiry sweeper (deletes rows and attachment
  files).
- **Manual delete:** locally, or "delete for everyone" via a control message.
- **Contact blocking + allowlist mode**, notification-content privacy, `FLAG_SECURE` on
  sensitive screens.
- **Encrypted backup/restore** to an app-private file under a user passphrase.
- **Opt-in, off-by-default update checker:** Tor-only GitHub Releases check, Ed25519 signature
  verification, fail-closed if unconfigured.
- **Key rotation:** the signed prekey and last-resort Kyber prekey now rotate on a schedule
  (`PreKeyManager.rotateKeysIfDue`, checked on every `ConnectivityService` start plus a 6-hour
  timer) rather than being generated once and reused forever, with a bounded retention window so
  a still-in-flight handshake against the previous key isn't broken by rotation.

## Recently completed: sender-key groups

Group message *content* (`Text`/`AttachmentOffer`) is now encrypted once per message under the
sender's own sender-key chain (`GroupCryptoEngine`, wrapping libsignal's `GroupCipher` /
`GroupSessionBuilder`) instead of being re-encrypted from scratch per recipient through N
separate pairwise Double Ratchet sessions. The sender-key chain itself is still only ever hand
delivered to a member over that member's own already-verified pairwise session — nothing here
removes the pairwise sessions or the safety-number verification they carry — this only changes
how the *message body* is encrypted, taking the ratchet-step cost from O(members) to O(1) per
message. A membership change (add, remove, promote, self-leave) generates and redistributes a
fresh sender-key chain, since a sender-key chain only ratchets forward and a removed member who
already held it could otherwise keep decrypting messages sent after they left — this is required
behavior per libsignal's own sender-key security model, not an optional hardening step.

Group control traffic (invites, receipts, timer updates, profile pushes) intentionally still
goes out as plain per-recipient pairwise messages, unchanged — sender-keys only pay for
themselves on the actual message bodies, which is where the O(members) cost was; wrapping small,
infrequent control messages in a second protocol layer wouldn't be worth the added complexity.

**Known limitation carried over from upstream Signal's own sender-key design, not specific to
this implementation:** a member who's since been removed could still decrypt messages sent in
the brief window between their removal and the next `syncGroup()`-triggered rotation completing
across all remaining members, since a sender-key chain doesn't have per-recipient "future
secrecy" the way a Double Ratchet session does. This is an inherent property of sender-key
protocols generally (Signal's own groups have the same characteristic), not something rotation
alone fully closes — multi-device support and further hardening of the rotation trigger path are
listed as still-ahead work below.

## What's genuinely still ahead

- **Multi-device support.** The storage schema already keys sessions by name + device, but
  there's no device-linking flow yet — one identity lives on one phone today.
- **Continuous post-quantum ratcheting.** See the SPQR section below — this is an upstream
  `libsignal` capability to adopt, not something to build in this repo directly.
- **Reproducible builds and signed releases**, beyond the existing local keystore-signing flow.
- **Fuzzing of the wire protocol and codecs.**

## Store-and-forward delivery (offline mailbox via DHT relay)

Today, delivery requires both parties online at the same time: `sendText()`/`sendAttachment()`
hand off to a background delivery attempt (see the message-display-latency fix), but if the
recipient's onion service isn't reachable, the message just sits `PENDING` until
`retryPending()` catches them online later. There's no path for a message to actually leave the
sender's device while the recipient is offline.

The proposed fix is a Kademlia-style DHT relay layer — the same routing logic BitTorrent's
mainline DHT and I2P's floodfill use — with **relay participation opt-in in Settings**:

- Any NiiX node may host ciphertext for others. A sender hashes the recipient's identifier into
  the DHT keyspace and pushes the encrypted payload to whichever nodes are numerically closest
  to that key — no prior relationship between sender/recipient and the hosting nodes required.
- The recipient queries the same keyspace on coming online, pulls down anything waiting, and the
  relay nodes drop their copy once fetched (or on TTL expiry, whichever comes first).
- This is pure swarm storage: redundancy comes from the network's total size, not from the
  sender's or recipient's own contact graph, and it needs no dedicated infrastructure or second
  device.

**Open problem: what it takes to eventually consider opt-out.** This ships opt-in first — a
person has to actively turn relaying on. Flipping the default to opt-out later is plausible but
not assumed, because default-on has three real costs that would need explicit answers first:

- *Mobile devices are unreliable relay nodes regardless of the toggle.* Doze/App Standby and
  OEM battery managers kill background processes aggressively; a phone opted into relaying will
  still drop out of the swarm constantly whether the person chose it or not. [Session](
  https://arxiv.org/pdf/2002.04609)'s Service Node swarms — the closest real-world prior art for
  this exact "mailbox" idea — deliberately do *not* rely on volunteer end-user devices; nodes
  stake Oxen to participate, specifically because uptime has to be economically enforced or the
  swarm degrades. NiiX has no staking layer and no plan to add one, so this project's version of
  "redundancy from network size" is weaker than Session's until proven otherwise in practice.
- *Consent and liability.* Opt-in sidesteps the sharpest version of this — nobody's phone stores
  strangers' ciphertext without them choosing it — but the legal/app-store-policy read on
  "storage you can't inspect, running on end-user devices" is still worth doing properly before
  leaning on this feature as a load-bearing part of the delivery story.
- *Sybil/eclipse exposure is higher-stakes here than in a torrent DHT.* Losing a chunk of a
  torrent is low-cost; a censored or dropped message in a security messenger is not. A
  permissionless keyspace with free node-ID minting is exactly the shape Sybil/eclipse attacks
  target — an adversary can grind IDs to surround a specific recipient's keyspace neighborhood
  and see or selectively drop their mailbox traffic. Needs a real mitigation (e.g. proof-of-work
  or proof-of-storage-backed node IDs, redundant fetch-and-cross-check across multiple close
  nodes rather than trusting the single closest one) regardless of which default it ships with.

None of the above blocks building this opt-in — it blocks ever flipping it to default-on without
first answering them. Path: land it opt-in, measure real-world swarm health (how many people
actually enable it, how often opted-in nodes are actually reachable) and abuse patterns, then
revisit a default-on proposal once there's data instead of an assumption.

Whichever default ships, this only ever stores ciphertext the relay nodes cannot read (payload
stays end-to-end encrypted exactly as it is over direct delivery today) — the DHT layer changes
*who holds the encrypted bytes while the recipient is offline*, not who can decrypt them.

## Post-quantum ratcheting (SPQR): adopt, do not reimplement

The current build uses PQXDH: hybrid X25519 + ML-KEM ("Kyber") on the *initial handshake*,
after which the ongoing Double Ratchet is classical. Continuous post-quantum forward secrecy
and post-compromise security for the whole conversation come from the **Triple Ratchet**
(Dodis, Jost, Katsumata, Prest, Schmidt; EUROCRYPT 2025), which Signal productionized as the
**Sparse Post-Quantum Ratchet (SPQR)** and built into the Signal Protocol with formal
(ProVerif) proofs in CI, in collaboration with PQShield, AIST, and NYU.

Because `libsignal` is the upstream that carries SPQR, the correct and safe way for NiiX to
gain continuous PQ ratcheting is to **upgrade `libsignal` and switch the session layer to its
SPQR-enabled API when available** — an audited implementation — rather than hand-rolling
erasure-coded ratchets or a custom KEM from research papers.

Deliberately NOT implemented here, by design:

- A hand-written Triple Ratchet / erasure-coded PQ ratchet. (Use libsignal's SPQR instead.)
- The custom "Katana" KEM. Signal evaluated Katana and chose standardized ML-KEM to remain
  NIST-conformant; we follow the same standardized-primitive principle.
- Hand-rolled eSM / KEM+DVS deniable-handshake protocols from formal papers. These are
  serious research with no audited, maintained implementation; writing them by hand would
  reintroduce exactly the "invent your own cryptography" risk this project exists to avoid.

Sound, non-crypto-inventing ideas worth adopting alongside sender-key groups and multi-device,
done correctly rather than as cosmetic add-ons:

- A periodic re-key cadence implemented as a session-rotation policy on top of libsignal's
  audited handshake (re-run PQXDH after N messages / T time), not as new ratchet math.
- Transcript/version binding: negotiate a protocol version and bind it, plus a session id,
  into the authenticated associated data of the wire frames — so downgrade and unknown-key-share
  attempts are rejectable in-band. This must be bound into the authenticated transcript to mean
  anything; an unbound version field is security theater.
- Dual-signature *identity* authentication (Ed25519 + ML-DSA) for the long-term identity and
  handshake only. Do not sign every message: per-message non-repudiation would break the
  deniability property that protects users.
