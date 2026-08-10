# Security notes

## What protects what

- **Message confidentiality/integrity:** `libsignal` (PQXDH key agreement with X25519 +
  ML-KEM/"Kyber", then the Double Ratchet). This is mature, audited cryptography used in
  production by Signal. We integrate it; we do not reimplement it.
- **Post-quantum scope:** the *key agreement* is hybrid post-quantum. The Tor onion v3
  *transport* is classical (Ed25519 + curve25519 `ntor`), and the Double Ratchet that runs
  after the initial handshake is also classical — continuous post-quantum protection for the
  whole session (not just its start) is pending libsignal exposing SPQR / "Triple Ratchet" (see
  `ROADMAP.md`). Describe this app as "post-quantum end-to-end encryption over Tor," not as a
  fully post-quantum stack.
- **Data at rest:** the SQLCipher database is encrypted with a 32-byte random passphrase that
  is wrapped by a non-exportable Android Keystore key (StrongBox where available). Attachments
  and other larger files use Tink streaming AEAD under a Keystore-protected keyset, with each
  attachment getting its own per-file key.
- **Identity trust:** strict trust-on-first-use. A remote identity key is accepted only if it
  has not been seen before or matches what was stored; any change is treated as untrusted and
  must be re-verified. Safety numbers (derived from both parties' identity keys) let two people
  confirm out-of-band or in person that no one intercepted the key exchange.
- **Transport:** the app runs its own embedded Tor process (`KmpTorTransport`, via `kmp-tor`)
  by default — there is no external dependency on Orbot or a system Tor for this to work. An
  alternate implementation for connecting to an external Tor process instead exists in
  `:core:transport` but is not what ships by default; see `ARCHITECTURE.md`.
- **App presence:** the real launcher icon is disabled by default in favor of a calculator
  disguise (`LauncherDisguised`/`LauncherReal` activity-aliases); the real app opens only after
  the correct passcode is entered on the calculator's `=` key.
- **Duress:** a separate duress passcode, when entered at the lock screen, wipes the real
  database, Keystore-wrapped secrets, and files, then opens a fresh account seeded with
  plausible placeholder conversations — so a coerced unlock looks like it worked rather than
  obviously failing or presenting an empty app.
- **Screen content:** `FLAG_SECURE` is set on the sensitive activities (chat, settings,
  onboarding) to exclude the app from screenshots and the recents preview.
- **Backups:** OS-level cloud backup and device-to-device transfer of app data are disabled;
  the only way data leaves the device is the explicit, user-passphrase-encrypted
  backup/restore feature.

## Hardening options (one-line / small changes)

- Bind the Keystore key to user authentication by adding
  `setUserAuthenticationRequired(true)` (and a validity window) in `KeystoreKeyManager`. This
  makes the database key unusable until the user authenticates, at the cost of a prompt.
- Add an explicit user passphrase as a second factor by deriving an additional key (Argon2id)
  and combining it with the Keystore-wrapped secret.

## What is NOT yet assured

- The application-layer integration, storage design, wire protocol, and transport in this
  repository have **not** had an independent security audit — this is true regardless of how
  complete the feature set looks; a working build is not the same claim as an audited one.
- Group messaging is real (fan-out to each member's verified 1-on-1 session) but the
  `SenderKeyStore` behind it is an in-memory, non-persisted stub, not a mature sender-key
  protocol. Treat groups as functional but not yet architecturally final.
- Only session establishment is post-quantum; the ongoing ratchet is not, until SPQR lands.
- There is no multi-device support, so there's no cross-device consistency story to evaluate
  yet either.
- The opt-in update checker verifies an Ed25519 signature over release bytes and fails closed
  if the pinned public key is unset, but the checker itself — like everything else here — has
  not been independently reviewed for its own attack surface (e.g. how it parses release
  metadata).

## Cryptographic posture and agility

- Algorithms are standardized: X25519 + ML-KEM (Kyber) for key agreement via libsignal's
  PQXDH; this matches Signal's own choice of NIST-standardized primitives over experimental
  alternatives.
- Continuous post-quantum ratcheting (the Triple Ratchet / SPQR) is an upstream libsignal
  capability to adopt by upgrading the library, not to reimplement by hand. See
  `ROADMAP.md`.
- This project does not hand-roll experimental cryptography (custom KEMs, research-stage
  ratchets or handshakes). New cryptographic capability should arrive as audited library
  code or after independent review of any new construction.

## Toward independent review

1. Commission an independent security review of the whole system, including the cryptographic
   integration points (prekey distribution, session lifecycle, identity verification UX,
   duress-wipe correctness) and the embedded-Tor integration specifically, since it's the piece
   furthest from off-the-shelf.
2. Move group messaging onto a real, persisted sender-key implementation rather than the
   current in-memory stub before treating groups as equally trustworthy to 1-on-1 chats.
3. Do not advertise anonymity or post-quantum security guarantees beyond what's actually true
   today (handshake-only PQ, classical-transport anonymity via Tor) until they've been
   validated by someone other than this project.

Do not rely on this software to protect people at risk until it has been independently
audited.
