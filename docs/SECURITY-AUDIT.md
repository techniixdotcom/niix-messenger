# Security audit

October 2026. Covers every module: app, core/model, core/storage, core/crypto,
core/messaging, core/transport and core/nostr. Docs, the manifest, ProGuard rules and the build
configuration were checked too.

## Method

- Read every source file. The most attention went to code that handles input from the network,
  key material, storage and the lock.
- Traced each incoming frame type from the socket to the database, asking who can send it and
  what it can change.
- Checked what happens around passcode changes, wipes, locking and process death.
- Ran the pure-JVM tests: model, wire format, limiter, storage logic and Nostr. 145 pass.
- Compiled every source file together to catch dangling references after the removals.
- Things that can't be run here are listed under *Needs testing on a phone*: the Android build,
  the keystore and SQLCipher on a device.

Severity is about impact on users: **High** means a realistic way to lose data or protection,
**Medium** means a real leak or abuse that needs some position or effort, and **Low** means
hardening.

## Fixed in this pass

### High

**H1. Anyone could claim an address they don't own on first contact.**
The sender address in a direct frame is just a claim, and libsignal trusts any name it hasn't
seen before. Someone could start a session under the address of a group member we had never
messaged, and then post into that group as them. They could also open a request that looks as if
it comes from an address the user recognises. Contacts added by scanning were already safe
because their keys are pinned.
*Fix:* before the first session-starting message from an unknown address is decrypted, the app
asks that address over Tor for its key bundle and compares the identity key. Only the owner of
an onion key can answer on that address. The lookup runs in the background and the first attempt
isn't acknowledged, so the sender's retry is the one accepted. Lookups are rate-limited, because
the attacker chooses the address. See PROTOCOL.md §1.

**H2. Backups could be protected by six digits.**
The backup dialog used a numeric PIN field with a 6-character minimum. A backup holds the
identity key, the onion key and every message. Once it leaves the phone, nothing else protects
it: there's no hardware secret. A million guesses against Argon2id is a matter of hours on rented
hardware.
*Fix:* the passphrase is free text, at least 12 characters, and has to be typed twice when
exporting. Old backups still restore, but they're worth making again.

**H3. The background service crashed on Android 10 to 13.**
It called `startForeground` with type `DATA_SYNC`, which the manifest doesn't declare. The
manifest's `specialUse` type only exists from Android 14, so older versions throw.
*Fix:* the untyped call is used below Android 14.

**H4. Messages couldn't be queued while the phone was locked.**
The locked inbox wrapped entries with a keystore AES key that requires an unlocked device, and on
current Android such a key can't be used at all while the phone is locked. So whenever the
database was closed, typically after the system restarted the app in the background, a message
arriving with the screen off was refused. If it came through a Nostr relay, it was marked seen
and only came back on the sender's next republish, up to 12 hours later.
*Fix:* each entry is sealed with a fresh AES-GCM key, which is wrapped with the public half of a
keystore RSA key (OAEP). Sealing needs only the public key, so it works while locked. Opening
needs the private key, which the keystore only releases while the phone is unlocked. Entries from
older builds still drain.

**H5. A crash during cleanup after a passcode change could roll the database back.**
At every start, `recoverFromInterruptedRekey` restored any rekey backups it found, even when the
marker said the rekey had finished. The backups are deleted just after the marker, so a crash
between the two would put the old database back under the new salt. That locks the user out,
and the change from raw keys has the same window.
*Fix:* backups are only restored while the in-progress marker exists. Otherwise they are stale and
are deleted.

### Medium

**M1. Your profile and read receipts went to strangers.**
Starting a session sent your name and photo, and reading a disappearing message sent a read
receipt. Both happened for unanswered message requests too. *Fix:* both go only to people you
chose: contacts, shared group members and accepted requests. Your profile is sent when you accept
a request.

**M2. Receipts could rewrite delivery state.**
Any receipt state was accepted for any message in the conversation, including other people's
messages in a group. *Fix:* receipts only apply to messages this device sent, and only
`DELIVERED` and `READ` are accepted.

**M3. Attachment ids could be reused across conversations.**
An offer reusing an existing attachment id would create a new message pointing at someone else's
file. Deleting that message then deleted the file. *Fix:* an id that already exists is refused.
The MIME type also has to look like one, since it decides how the file is shown and saved.

**M4. The duress decoy's contacts had impossible addresses.**
They were random base32. A v3 address carries a version byte and a SHA3 checksum, so 31 out of 32
of them were visibly fake. *Fix:* they're built the way Tor builds them, from a real Ed25519 key
that is then thrown away. A test checks the checksum and version.

**M5. A fixed local port.**
Tor forwarded the onion service to `127.0.0.1:7600`, and every app on the phone can reach
loopback. Another app could find NiiX by trying that port. *Fix:* the system picks a random
loopback port each time the service starts. Publishing is also idempotent now, so the repeated
service starts can't collide in Tor.

### Low

- **The identity private key stayed in memory after locking.** It's dropped on lock now. Nothing
  needs it while locked.
- **Locked-inbox races.** Entry names could collide under concurrent writes, and the queue wasn't
  in arrival order. Enqueue and drain are synchronized now, and names start with the arrival time.
- **The expiry sweep had a race.** Selecting and deleting were separate steps, so a message
  expiring between them left its attachment file behind. They run in one transaction now.
- **Turning remote wipe off left the nominees' recorded identity keys behind.** They're removed
  now.
- **Cover traffic was predictable.** Its timing used a non-cryptographic RNG; it uses SecureRandom
  now.
- **An unused FileProvider path.** The `updates` path was still exposed after installs moved to
  `PackageInstaller`. Removed.

## Efficiency and dead code

- **EncryptedFileStore was never used.** It created a Tink keyset and a keystore key at every
  start, and only its `clear()` was ever called. Removed, and its leftovers are cleaned up once on
  existing installs.
- **MonotonicClock wrote to the database on every call.** That included every expiry sweep and
  message insert. It now keeps the value in memory and writes at most once a minute.
- **The conversation list loaded every message of every conversation to show the last one.** It
  now runs one `LIMIT 1` query.
- **Prekey generation committed each key separately**, about 200 disk syncs during setup. It now
  runs in one transaction.
- **The updater downloaded a checksum file the signature already makes redundant.** Removed.
- **Removed:** the lock-timeout setting that nothing could set, a last-update-check timestamp that
  nothing read, `SettingsStore.getLong` and `setLong`, `BlocklistDao.unblock`, `BlocklistDao.all`
  and `ConversationManager.unblock`, which had no UI, `CoverTrafficScheduler.isRunning`, and a
  dead release-key guard.

## Closed since the audit

A follow-up pass closed most of what was left open:

- **The database closed only when you came back to the app.** It now closes a minute after the
  app leaves the screen, with or without you coming back, and the key leaves memory with it.
  Messages that arrive meanwhile go into the locked inbox, still encrypted, and are opened at the
  next unlock. Without a passcode nothing changes, since there would be nothing to unlock with.
- **The local SOCKS port was fixed at 9055.** Tor now picks a port at each start.
- **"The ongoing ratchet is classical"** was out of date. libsignal 0.102 runs SPQR, Signal's
  post-quantum ratchet, in every session and requires it of the other side. PROTOCOL.md §2 is
  corrected.
- **The duress decoy only survived a casual look.** Each decoy contact now gets a real identity
  key and a real session, made by exchanging its messages with a stand-in phone held in memory,
  so the database holds what a used account holds.
- **Update checks went to GitHub directly, at every start.** They go over Tor by default and
  happen about once a day at a randomised time.
- **Two default Nostr relays are reached at their onion addresses**, so that traffic doesn't leave
  through a Tor exit.
- **Losing the update signing key meant everyone reinstalling.** The app also trusts a standby key
  kept offline, which takes over with a normal update.
- **The release key's password was stored next to the key.** The build asks for it instead.

Looked at and left as it is:

- **Group membership doesn't carry identity keys.** It doesn't need to: an address's identity key
  is pinned the first time it's seen and the store refuses any other key for it afterwards, and
  the first contact is checked against the onion address itself (H1). Group members' keys arrive
  over those same one-to-one sessions. Putting keys in the membership messages would change the
  protocol without stopping anything that isn't already stopped.

## Multiple devices

Added after this audit, and described in PROTOCOL.md §9. What was checked:

- Messages that only our own devices may send (`SyncSent`, `ContactUpdate`, `WipeKey`) are dropped
  from any other account, and a device list is only accepted inside a session with its account.
- A sender address is only treated as a device of an account if that account's list says so. An
  unlisted address claiming a contact's key waits for their list instead of becoming a second
  contact; one claiming our own key is dropped.
- A link bundle only opens with the secret from the QR code and for the address in it, and is
  applied in one transaction.
- The wipe key is checked against a stored hash, so it works on a locked phone without exposing
  anything, and it's replaced when a device is removed.
- A test against libsignal 0.102 checks that devices sharing an identity key keep separate
  sessions and sender keys that a contact tells apart by device number.

Not tested: two phones linking, syncing and wiping each other. That needs real devices.

## Delivery reliability

Reported as messages arriving only some of the time, with a force stop often bringing them in.
What was found and fixed:

- **Two startups at once broke direct delivery.** Every screen that opens starts the background
  service, and while Tor was connecting two startups could overlap. Both published the onion
  service at the same moment: the second closed the first one's listener, Tor rejected the second
  as a duplicate, and the address was left forwarding to a closed port. Startup and publishing now
  each run one at a time.
- **Sessions weren't locked.** libsignal loads a session, advances it and stores it again. Two
  sends to the same device at once reused a message counter, and the copy that lost couldn't be
  decrypted on the other end until it was sent again. A test with eight sends at once against
  libsignal 0.102 lost about 30 messages in 100 without a lock and none with one. Every session,
  and the sender keys, now has a lock, and starting a session with a device is serialised too.
- **Nothing reacted to the network.** Tor kept using connections over a network that was gone, and
  inbox relays backed off up to 20 minutes while there was no signal. The default network is now
  followed: Tor's network is switched off and on with it, and relays are redialled at once.
- **A failed startup wasn't retried**, and nothing checked afterwards that the address was still
  reachable. Startup now retries with a backoff (or at once on a new network), and every ten
  minutes the app connects to its own address through Tor. Two failures in a row, with a network,
  restart Tor. The check costs one onion connection and carries no data.
- **Retries ran one message at a time**, so a contact who was offline held up everyone else. Up to
  four conversations are now retried side by side, and a device that didn't answer isn't dialled
  again within the same pass.
- **Nothing is retried while locked**, as unsent messages are in the database. A message sent in
  the last ten minutes that hasn't reached anyone holds the auto-lock off for up to five minutes.

## Open findings

1. **Media is parsed by Android's decoders.** Attacker-supplied video and audio is played with
   `VideoView` and `MediaPlayer`, which run in the system media process. That's the usual
   messenger trade-off, not something the app can sandbox further.
2. **Decoy contacts have no Nostr mailbox history.** A real contact who has been online with you
   leaves mailbox state behind; decoy contacts don't. Spotting that takes knowing how NiiX stores
   it, and some real contacts lack it too, for instance when offline delivery is off.
3. **Group message signatures aren't post-quantum.** They're Ed25519. A quantum computer could
   forge one, though reading group messages would still need keys it can't get.

## Relay privacy, wipe keys and unblocking

- **Relays could link and count a person's mailboxes.** A subscriber asked for all its mailbox
  tags at once, and each mailbox had a tag of its own all week. Mailboxes now go into one of 1024
  weekly buckets shared with everyone, and the buckets someone listens to are padded with stable
  decoys. A test against a loopback relay checks that the subscription is padded and that, of two
  envelopes in the same bucket, only the one for this device's mailbox is delivered.
- **A device could miss a new wipe key for good.** It was sent once, so a device that was offline
  and whose relay didn't hold it couldn't be wiped by the others, nor wipe them. The key is now
  resent until every device has acknowledged it, and carries an epoch so a late copy can't bring
  back an older key.
- **Blocking couldn't be undone.** Settings › Privacy › Blocked lists everyone blocked, including
  requests that were blocked and deleted, and the conversation menu offers Unblock. Unblocking is
  sent to the account's other devices as its own flag, so a rename that crosses a block in transit
  can't undo it.

## More delivery reliability

- **Nothing was received after Android ended the app while it was locked**, or after a reboot,
  until the app was opened: the onion key and inbox secrets were only in the database. They're now
  also kept in a receive copy under a Keystore key usable while locked (PROTOCOL.md §3 and §8, and
  THREAT-MODEL.md D for what that gives up), and a receiver for boot and app updates restarts the
  service. With "Disconnect when locked" on, the copy doesn't exist and nothing starts.
- **A broken session never recovered.** A message that failed to decrypt was retried for a month
  and failed every time. The receiver now starts a new session with that device, rate-limited and
  only with known people. A test against libsignal 0.102 checks that this rescues both a lost
  session and one restored from an old backup. Duplicates are now acknowledged instead of being
  retried.
- **Attachments**: a group upload counted as delivered once any member had it; a recipient that
  already had a file never acknowledged it again, so it was resent on every retry; and the first
  send was marked delivered as soon as the offer arrived, even if the file didn't. Now every
  recipient device has to have the file, a device that has it says so before anything is sent, and
  interrupted transfers resume.

## Restore, failed messages and our own devices

- **A restore kept the old address running.** A backup holds the onion key, Nostr inbox and wipe
  key, but Tor went on publishing the address from before the restore, and the relay inbox and
  receive copy stayed on the old account, until the app restarted. Restoring now takes all of it
  down and reconnects as the restored account.
- **A failed message was final.** After 30 days of retries there was no way to send it again;
  the long-press menu now has "Send again".
- **Our own devices could miss attachments.** They were sent the file once, best effort, so one
  that was off never got it. They're now retried like any recipient, separately from the
  message's delivery.

## Full audit, October 2026

A read of the whole codebase. The encryption, key derivation, backup format, update signatures and
the authorisation of every incoming message type held up; these were found and fixed:

| # | Finding | Fix |
|---|---|---|
| 1 | Turning a passcode on recorded it before re-encrypting the database. A failure or a kill in between left a database the passcode couldn't open, with no way back. Turning it off had a smaller window of the same kind | A marker is set while the database moves to or from the no-passcode key; while it's there, unlock also tries that key and repairs the state. A failed change is rolled back |
| 2 | A wrong passcode was recorded a second or more after it was known to be wrong, so killing the app in between made guesses free of the delay | Attempts are counted before testing and cleared on success; one that never got tested (out of memory, keystore busy) is taken back |
| 3 | Draining the locked inbox while the phone itself was locked failed to open the entries and deleted them, though they'd been acknowledged | Nothing drains while the phone is locked, and entries the keystore refuses are kept for a week |
| 4 | Every key bundle carried the same one-time prekey until it was used, so two people starting a conversation at once collided; a stranger's request never recovered | A random unused one-time prekey (and Kyber prekey) per bundle |
| 5 | One device's receipt for a relayed copy marked the whole message delivered and stopped retries to the others | A receipt clears only its own device; group messages wait for direct acknowledgements |
| 6 | Decrypted temporary files were only deleted when the lock screen appeared, not when the app locked in the background, and never without a passcode until the app was opened | Deleted on every lock and a minute after leaving the app |
| 7 | A connection trickling bytes could be held open indefinitely, and 24 of them blocked direct delivery | A 90-second deadline per connection, lifted only for an authenticated attachment transfer |
| 8 | The locked inbox's per-sender limit used the unverified sender address, so fake senders could fill it | Direct deliveries get only part of it; the rest is kept for relay envelopes from known contacts |
| 9 | A clock that once ran far fast left every later time in the future | The mark is dropped when it's more than three days ahead while Tor is connected, which needs a roughly correct clock |
| 10 | Backups held the database but not attachment or profile picture files | Backup version 2 carries them; version 1 still restores |
| 11 | Messages that expired while locked could show briefly on unlock | Expired messages are swept before unlock completes |
| 12 | Linking a device handed over the account key on scanning, with no confirmation | A confirmation says what the other phone gets |
| 13 | A connection refused for a full queue was never closed | Closed |
| 14 | A comment said updates used a normal connection by default | Corrected |

Checked here: the core modules type-check, and unit tests cover the clock. Not checkable without a
phone: the passcode recovery, the locked inbox with the screen off, backups with files, and the
link confirmation.

## Needs testing on a phone

None of these can run in the build environment used for this audit:

- The locked inbox while the screen is off, and its RSA key generation. This includes the
  OAEP-with-MGF1-SHA-1 combination, which the keystore accepts on every Android version this app
  supports.
- First contact between two fresh installs. The first message should arrive about 20 seconds
  after it's sent.
- The background service starting on an Android 10 to 13 device.
- Unlocking after updating, which converts the database once.
- Exporting and then importing a backup with the new passphrase rules.
- The database closing a minute after leaving the app, and messages that arrive meanwhile
  appearing after unlock.
- Update checks over Tor finding a release, and the two onion relays being reachable.
- Linking a second phone, a message from a contact reaching both, a message sent from either
  showing on the other, removing a device, and the duress passcode on one phone wiping the other
  while it's locked.
- Delivery after switching between Wi-Fi and mobile data, after losing signal for a while, and
  with the app opened several times while it connects. The own-address check succeeding against a
  real Tor, which allows connecting to its own onion service.
- Receiving through public relays with bucket tags, and how much other traffic a bucket brings.
- With a passcode: force-stopping isn't the same as Android ending the app, so leave the app
  locked, let the system end it (or reboot), and check that messages still arrive and appear after
  unlocking. Also after an update.
- Restoring a backup on one phone and then messaging: the first message should fail once, then
  arrive after the new session starts.
- A large file sent while the connection drops, and a group photo to a member who was offline.
- Restoring a backup on a second phone, then being messaged at once by a contact.
- Turning a passcode on and force-closing the app straight away: it should open with no passcode
  afterwards, not ask for one that doesn't work. A backup with photos restoring them, and a
  version 1 backup still restoring.
- Removing a device while another is offline, then bringing that one back: it should get the new
  wipe key and be able to wipe and be wiped.
