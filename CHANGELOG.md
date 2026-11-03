# Changes

This file describes the app as it is now. **Unreleased** lists everything that differs from the
last published release, and becomes that release's notes when the next version is published; the
build script then moves it under the version's heading and starts a new, empty Unreleased.

Rules for entries:

- Record the net effect, not the history. A feature added and removed again before a release
  never happened as far as users are concerned, and a bug introduced and fixed in the same cycle
  was never shipped.
- Write for the person using the app: what they will notice, not how it was implemented.
- Remove or correct an entry the moment it stops being true.

## Unreleased

### Added
- Reply to a specific message
- Copy a message's text from the long-press menu
- Mute a conversation
- Change your display name
- Wipe button on the main screen, with an option to skip the confirmation
- Remote wipe: contacts you nominate can erase this device
- Pull down on the main screen or in a conversation to check for new messages now
- Group messages show who sent them
- Safety numbers for each group member, from group info
- Source code link and Monero donation address in Settings > About
- Optional wipe if NiiX is not unlocked for a chosen number of days, which works with no network
- Optional setting to let recent contacts know when you are back online, so messages they were
  holding arrive straight away
- If Android stopped NiiX in the background, the app now says so and shows how to prevent it

### Changed
- New messages are blurred until tapped
- Messages show the date as well as the time
- The disappearing messages setting explains that it cannot stop screenshots or copies
- Settings is organised into categories, with shorter descriptions
- Attachments that are not media are saved to Downloads instead of opened
- Checks for updates every time the app starts
- Downloading an update shows the percentage, size and a progress bar, and can be cancelled.
- On Samsung phones, a blocked update now explains that Auto Blocker is the cause and where to turn it off
  Installing from the home screen no longer looks like nothing is happening.
- Calculator disguise is off by default
- Screenshots are allowed by default
- New app icon; the icon chooser has been removed
- New look matching niixa.org: black and dark glass with thin pink edges, pink for whatever is
  selected or on, and the JetBrains Mono typeface throughout
- Dialogs have rounded corners in the same style, and confirmations that destroy data have a red
  confirm button
- Lower battery use while idle: Tor uses its reduced-padding modes for mobile
- Built for Android 16
- The "unverified identity" warning appears only in one-to-one chats
- Safety numbers change once with this update, because they are now calculated the same way
  everywhere. Contacts you verified before need checking again.

### Fixed
- Messages to someone who added you now deliver reliably
- Group messages reach every member. A member who was offline gets them when they come back,
  instead of missing them for good while the sender's app showed them as delivered
- Group messages no longer disappear on a phone that missed a member's group key
- Messages waiting for someone are sent the moment that person is seen online, instead of at the
  next scheduled retry, which could be 15 minutes away
- After reconnecting or a network change, NiiX checks for messages and sends waiting ones straight
  away instead of within the next five minutes
- A reply that had to be resent keeps its link to the message it answers
- When several people start a conversation with you around the same time, their first messages
  no longer fail to decrypt
- Tapping a blurred message no longer jumps to the bottom of the chat
- The app returns to the lock screen after a remote wipe instead of leaving the chat on screen
- Group chats show contact names instead of address fragments
- A crash while changing your passcode can no longer lock you out
- Backups made on older versions restore; a backup from a newer version is refused before
  anything is changed
- Cancelled photos and failed attachments no longer leave copies behind
- Turning relay storage down to zero switches relaying off instead of failing silently
- Network timeouts no longer misfire when the device clock changes
- Returning to the app with "Disconnect when locked" on no longer risks an "app not responding"
  freeze while the connection shuts down
- Busy conversations scroll and update more smoothly: only changed messages are redrawn, and
  images are no longer decrypted again on every update
- A phone hosting a relay no longer slowly accumulates memory from every sender that connects

### Security
- Disappearing messages can no longer be kept alive by winding the device clock back
- A group member can no longer make your copy of a message start disappearing
- Remote wipe permission is tied to a contact's identity, not just their address
- A relay can no longer reuse another relay's request to collect your messages
- Malformed attachment offers are rejected on arrival
- Updates are refused unless they are newer than what is installed
- The phone's files no longer reveal that a duress code is set or that the calculator is a
  disguise
- New passcodes need at least 8 digits. Existing 6-digit passcodes keep working
- With the calculator disguise on, update checks always go through Tor, so they cannot reveal that
  the phone runs NiiX
- Wiping clears copied message text from the clipboard
- Someone you have not added can no longer pretend to be another person by claiming their address:
  a new conversation is checked against the address it claims to come from
- A phone hosting a relay can no longer be made to waste effort on endless message requests
- Relays no longer see who sent a message: each contact stores messages for you under a random key
  you gave them, different for every pair of people, instead of their identity
