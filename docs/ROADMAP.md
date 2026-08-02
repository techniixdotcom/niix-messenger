# Roadmap

## Implemented in the current build

On-device, transport-independent halves of the requested features are implemented and
reviewable (they cannot be runtime-verified here — no Android SDK, and live send/receive
needs a real transport):

- App lock: Argon2id passcode mixed with the Keystore secret to derive the DB key; lock
  screen; auto-lock on timeout; manual lock.
- Messaging domain: conversations (direct + group), typed wire protocol + codec.
- 1-on-1 and group messaging (group delivery by client-side fan-out over each member's
  verified 1-on-1 session; sender-key optimization for large groups deferred to Phase 4).
- Disappearing-message timers + background expiry sweeper (deletes rows and attachment files).
- Manual delete: locally, or "delete for everyone" via a control message.
- Attachment encryption: per-file streaming AEAD with a sendable per-file key.

Still required for a working app: a real `TorTransport`, the inbound transport listener,
attachment byte transfer over that transport, the chat/contact/group UI, safety-number
verification, and an independent security/crypto audit.

Version-sensitive calls to verify on first compile (isolated and commented in-code): the
SQLCipher `openOrCreateDatabase` / `changePassword` signatures, the Tink
`AesGcmHkdfStreaming` constructor, and the BouncyCastle Argon2/HKDF APIs.

## Phase 1 — foundation (this repository)

- Multi-module Gradle build on current dependencies.
- Encrypted storage: SQLCipher + Keystore-wrapped key + Tink file encryption.
- Crypto wired to libsignal PQXDH/Kyber: identity, prekeys, DB-backed stores, sessions,
  encrypt/decrypt.
- Tor transport abstraction with a placeholder provider and a documented seam.
- Minimal app that initializes and displays identity, registration ID, onion, and state.

## Phase 2 — real transport and wire protocol

- Implement `TorTransport` over a real Tor (C daemon via JNI wrapper, or Arti).
- Persist the v3 onion-service key (encrypted) for a stable address.
- Foreground service to keep the onion service and inbound listener alive.
- Define the peer wire protocol: length-prefixed frames, message types, replay protection,
  and padding to resist traffic analysis.
- Per-contact one-time prekey distribution and replenishment policy.

## Phase 3 — contacts and conversations

- Contact exchange UI: render and scan `PreKeyBundle` + identity as a QR code; pin on import.
- Identity verification UX (comparable safety numbers) and re-verification on key change.
- Conversation storage and UI backed by the encrypted `messages` table.
- Delivery/read state and retry/queueing while a peer is offline.

## Phase 4 — robustness and groups

- Multi-device support (the schema already keys sessions by name + device).
- Group messaging via libsignal sender keys, with a persistent sender-key store.
- Key rotation schedules (signed prekey and Kyber last-resort rotation).
- Continuous post-quantum ratcheting (forward secrecy + post-compromise security for the
  whole session, not just the handshake) by adopting libsignal's Sparse Post-Quantum Ratchet
  (SPQR / "Triple Ratchet") when it is exposed through the library's public API. See the
  note below — this is an upgrade we consume, not one we reimplement.
- Push-free wake/connectivity strategy and battery optimization.

## Phase 5 — assurance

- Threat model document and independent security audit.
- Reproducible builds and signed releases.
- Fuzzing of the wire protocol and codecs.

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

Sound, non-crypto-inventing ideas worth adopting in the protocol phase (Phase 2/3), done
correctly rather than as cosmetic add-ons:

- A periodic re-key cadence implemented as a session-rotation policy on top of libsignal's
  audited handshake (re-run PQXDH after N messages / T time), not as new ratchet math.
- Transcript/version binding: negotiate a protocol version and bind it, plus a session id,
  into the authenticated associated data of the wire frames once the wire protocol exists —
  so downgrade and unknown-key-share attempts are rejectable in-band. This must be bound into
  the authenticated transcript to mean anything; an unbound version field is security theater.
- Dual-signature *identity* authentication (Ed25519 + ML-DSA) for the long-term identity and
  handshake only. Do not sign every message: per-message non-repudiation would break the
  deniability property that protects users.
