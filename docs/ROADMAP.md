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
- **1-on-1 and group messaging:** group delivery by client-side fan-out over each member's
  verified 1-on-1 session; sender-key optimization for large groups still deferred (see below).
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

Still required before treating this as more than experimental: an independent security/crypto
audit (see `SECURITY.md`) — nothing below changes that.

## What's genuinely still ahead

- **Sender-key groups.** Today's `SenderKeyStore` is an in-memory, non-persisted stub; groups
  work via fan-out. Moving to a real, persisted sender-key protocol reduces per-message cost
  for larger groups and is the main remaining messaging-architecture gap.
- **Multi-device support.** The storage schema already keys sessions by name + device, but
  there's no device-linking flow yet — one identity lives on one phone today.
- **Continuous post-quantum ratcheting.** See the SPQR section below — this is an upstream
  `libsignal` capability to adopt, not something to build in this repo directly.
- **Key rotation schedules** (signed prekey and Kyber last-resort rotation policy).
- **Reproducible builds and signed releases**, beyond the existing local keystore-signing flow.
- **Fuzzing of the wire protocol and codecs.**
- **Independent security audit** of the whole system, including the embedded-Tor integration
  specifically, plus a written threat model document.

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
