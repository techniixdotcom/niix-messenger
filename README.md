# Niix

Niix is a private messenger for Android. It runs entirely over Tor and doesn't need a phone
number, email, or any other account — there's no server that sees your contacts, your messages,
or who you're talking to.

## What it does

- Direct messages, group chats, and encrypted attachments
- Disappearing messages with a timer any member can set
- Offline delivery through a peer-to-peer relay network, not a central server
- Encrypted backup you can move to a new device

## Safety and privacy

- **No phone number, email, or account.** Your identity is a Tor v3 onion address and a Signal
  protocol key pair, generated on your device. Nobody assigns it to you and nobody but you holds
  the private keys behind it.
- **End-to-end encryption**, using the Signal protocol, for every message, group, and attachment.
- **No metadata server.** Messages route over Tor directly to the recipient's own onion address,
  or queue on a small relay network if they're offline. There's no central point that sees who's
  talking to whom.
- **A duress passcode.** A second passcode that wipes your real data and opens a decoy account
  instead, for situations where you're forced to unlock the app.
- **A disguise mode.** The app can appear on your home screen — and function — as a calculator.
- **Screenshot protection**, on by default, on every screen with anything sensitive on it.
- **Local encryption at rest.** Everything on your device is encrypted with a key derived from
  your passcode; nothing is readable without it.

This is a large, security-sensitive codebase, and it has not had an independent professional
security audit. Read it and question it — don't take "it's encrypted" on trust from any one
source, including this one.

## Building

```
./build-niix.sh                   # interactive menu
./build-niix.sh --release         # signed release APK
./build-niix.sh --debug           # debug APK, no signing key needed
./build-niix.sh --publish         # build, sign for updates, and publish to GitHub Releases
./build-niix.sh --upload-only     # publish an already-built APK (no build needed)
```

`--publish` and `--upload-only` need the [GitHub CLI](https://github.com/cli/cli) (`gh`,
logged in via `gh auth login`) and the update-signing private key from `UpdateChecker.kt`'s
setup — it looks for `update-signing-key.pem`, `niix-update-signing.pem`, or
`update-signing.pem` in the project root automatically, or set
`NIIX_UPDATE_SIGNING_KEY` to point elsewhere if yours is named or stored differently. Before
signing anything, the script derives that key's public half and checks it against
`RELEASE_SIGNING_PUBLIC_KEY` already compiled into the app, and refuses to publish if they don't
match — so it's not possible to accidentally sign a release with the wrong key and have the app
silently reject it later with no explanation.

`--publish` also bumps the version for you (`version.properties` at the project root is the
single source of truth — `versionCode` goes up by one, `versionName`'s patch number goes up by
one) before building, so every published release is one Android will actually accept as an
update over the last. The built APK is renamed to `niix-messenger-<version>.apk`.

Publishing replaces the actual source on GitHub, not just the release assets: the repo's
default branch is force-pushed with a single fresh commit of the current tree (old history is
gone, same as `git push --force` always implies -- this is deliberate, not a bug), authenticated
through `gh auth setup-git` rather than a separately-managed token. Every existing release is
then deleted, and the new one is created with the signed APK, its `.sig`, and
`SHA256SUMS.txt`. Before any of this, a staged copy of the tree is built with every secret and
build artifact stripped out and verified absent -- `keystore.properties`, `*.jks`, `*.pem`,
`local.properties`, `build.log`, `.git`, `.gradle`, `.toolchain`, and every `build/` directory
are never included, checked after the fact rather than just assumed, and the whole run stops
before pushing or publishing anything if that check ever fails.

The script downloads the JDK, Gradle, and Android SDK on its own if they aren't already on your
machine — no Android Studio or manual setup required.
