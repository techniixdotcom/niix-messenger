# Security notes

## What protects what

- **Message confidentiality/integrity:** `libsignal` (PQXDH key agreement with X25519 +
  ML-KEM/"Kyber-1024", then the Double Ratchet). This is mature, audited cryptography used in
  production by Signal. We integrate it; we do not reimplement it.
- **Post-quantum scope:** the *key agreement* is hybrid post-quantum. The Tor onion v3
  *transport* is classical (Ed25519 + curve25519 `ntor`). Describe this app as
  "post-quantum end-to-end encryption over Tor," not as a fully post-quantum stack.
- **Data at rest:** the SQLCipher database is encrypted with a 32-byte random passphrase that
  is wrapped by a non-exportable Android Keystore key (StrongBox where available). Larger files
  use Tink streaming AEAD under a Keystore-protected keyset.
- **Identity trust:** strict trust-on-first-use. A remote identity key is accepted only if it
  has not been seen before or matches what was stored; any change is treated as untrusted and
  must be re-verified.
- **Screen content:** the main activity sets `FLAG_SECURE` to exclude the app from screenshots
  and the recents preview.
- **Backups:** cloud backup and device-to-device transfer of app data are disabled.

## Hardening options (one-line / small changes)

- Bind the Keystore key to user authentication by adding
  `setUserAuthenticationRequired(true)` (and a validity window) in `KeystoreKeyManager`. This
  makes the database key unusable until the user authenticates, at the cost of a prompt.
- Add an explicit user passphrase as a second factor by deriving an additional key (Argon2id)
  and combining it with the Keystore-wrapped secret.

## What is NOT yet assured

- The application-layer integration, storage design, wire protocol, and transport in this
  repository have **not** had an independent security audit.
- The placeholder transport provides no anonymity or network security whatsoever.
- Metadata protection depends entirely on the real Tor integration you add.
- Group messaging is not implemented; the sender-key store is an in-memory stub.

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

## If you take this toward production

1. Replace the placeholder transport with a real, reviewed Tor integration and persist the
   onion-service key encrypted.
2. Define and document the peer wire protocol (framing, replay protection, padding).
3. Commission an independent security review of the whole system, including the cryptographic
   integration points (prekey distribution, session lifecycle, identity verification UX).
4. Do not advertise anonymity or post-quantum security guarantees you have not validated.

Do not rely on this software to protect people at risk until it has been independently
audited.
