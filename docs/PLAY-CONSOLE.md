# Play Console: prepared answers

Answers for the forms Google Play asks for when publishing NiiX, based on what the code actually
does. Google changes these forms from time to time, so read each question as it appears and check
the answer still fits.

Build the upload with `./build-niix.sh` option 13. It produces `dist/niix-messenger-<version>-play.aab`,
the Play version with no self-updater.

## Privacy policy

Use the public link to `PRIVACY.md` in the repository, for example:

    https://github.com/techniixdotcom/niix-messenger/blob/main/PRIVACY.md

Open it on GitHub first and copy the address from the browser, in case the default branch has a
different name.

## Data safety

**Does your app collect or share any of the required user data types?** No.

Why this is accurate, in case a reviewer asks:

- The developers operate no servers and receive no data. There are no analytics, crash reports,
  advertising or tracking libraries.
- Messages, attachments and receipts are end-to-end encrypted between users. Google's Data safety
  guidance does not count end-to-end encrypted data that the developer cannot read as collected.
- Messages for someone who's offline wait on public Nostr relays run by third parties, reached
  only over Tor. They hold sealed envelopes they can't read or link to a person, which expire
  after 14 days. The developers don't run or control them.
- The Play version makes no update checks. The only update-related network request is in the
  GitHub version, which is not published on Play.

**Is all of the user data collected by your app encrypted in transit?** Yes. Everything travels
end-to-end encrypted over Tor.

**Do you provide a way for users to request that their data is deleted?** Nothing is held by the
developers. Users can erase everything on the device at any time with the wipe button.

## App access

No account or login is needed. On first launch the reviewer creates an identity and sets any
passcode. To test messaging they need a second phone with NiiX, since messages go between
devices directly.

Suggested note for reviewers:

> NiiX is a peer-to-peer encrypted messenger that runs over Tor and needs no account. On first
> launch, choose any passcode. Messaging requires a second device running NiiX. On both, tap +
> and then New message. On one, tap Show my code; on the other, tap Scan QR code and scan it. (A
> share code can also be copied and pasted instead of scanned.)

## Foreground service declaration

Type: `specialUse`, subtype `peer_to_peer_messaging` (declared in the manifest).

Justification:

> NiiX is a peer-to-peer, end-to-end encrypted messenger that runs over the Tor network. It does
> not use Firebase Cloud Messaging or any server operated by the developer. A message can only
> reach the phone while the phone is reachable at its Tor onion address, which requires a
> continuously running Tor connection. The foreground service holds that connection. Without it,
> messages cannot arrive until the user opens the app. The service always shows a notification,
> stops when the user wipes their data, and can be set to disconnect whenever the app is locked.

Google usually asks for a short video showing the feature: record a message arriving on one phone
while NiiX is in the background, with the service notification visible.

## Battery optimisation exemption

The app asks for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Google lists messaging apps that cannot
use Firebase Cloud Messaging as an acceptable use.

> Without the exemption, Android's battery optimisation suspends the Tor connection while the
> phone is idle, and messages stop arriving until the user opens the app. NiiX cannot use Firebase
> Cloud Messaging because it has no server to send push messages from, and routing delivery
> through Google would reveal when users receive messages. The user is asked once and can refuse.

## Target audience and content

- Target age: 18 and over. NiiX carries unmoderated communication between users and is not
  designed for children, and choosing an adult audience keeps it out of the Families programme.
- Content rating questionnaire: it is a communication app where users can talk to each other and
  share images. There is no user-generated content visible to strangers and no location sharing.

## Signing

On the first upload, choose to let Google manage the app signing key (Play App Signing). The key
option 13 signs with is only the upload key; users receive builds signed by Google. Keep
`keystore-play.properties` and `niix-play-upload.jks` backed up offline: losing them means asking
Google to reset the upload key.

## Moving testers to the Play version

The Play version is signed with a different key from the GitHub version, so Android will not
update one to the other. Testers should back up (Settings > Backup), uninstall, install from
Play, and restore.
