# Changes

Entries under **Unreleased** become the release notes next time a version is published, and are
then moved under that version's heading. Write them as you go; what is here at publish time is
what users read.

Keep them short and about what changed for the person using the app, not how it was implemented.

## Unreleased

### Added
- Copy a message's text from the long-press menu
- Reply to a specific message
- Mute a conversation from its long-press menu
- Change your display name from the profile menu
- Wipe button on the main screen, with an option to skip the confirmation
- Remote wipe: nominated contacts can erase this device
- Source code link in Settings, under About
- Disconnect while the screen is off, on by default: removes the ongoing notification and
  checks for messages every 30 minutes instead

### Changed
- New messages are blurred until tapped
- Messages show the date as well as the time
- Settings is organised into categories
- Attachments that are not media are saved to Downloads instead of opened
- Checks for updates every time the app starts
- Calculator disguise is now off by default
- Screenshots are allowed by default
- Single app icon; the icon chooser has been removed

### Fixed
- Messages sent to someone who added you now deliver reliably
- Tapping a blurred message no longer jumps the conversation to the bottom
- The app returns to the lock screen after a remote wipe instead of leaving the conversation on
  screen
- A group member can no longer cause your copy of a message to start disappearing
- Remote wipe permission is tied to a contact's identity, not just their address
- Updates are refused if they are not newer than what is installed
- A relay can no longer use another relay's fetch or delete request to collect your messages
- Turning a relay's storage down to zero now switches relaying off instead of silently failing
- A crash while changing your passcode can no longer lock you out of the app
- Network timeouts no longer misfire when the device clock changes
- Safety numbers now use the same calculation everywhere in the app
- A backup from a different version is now refused before anything is changed, instead of
  failing partway through
- Cancelled photos and failed attachments no longer leave copies in temporary storage
- Fixed a grey, unusable screen on opening the app after updating
- Relay hosting now keeps the connection alive; the screen-off disconnect is skipped while
  hosting, so other people's messages still reach you
- New app icon
- Settings section headers, chevrons and accents now match the rest of the app
- Diagnostics moved to About, where the menu said it was
- Dialogs now use the same dark surface as the rest of the app instead of a grey Material default
- Confirmations that destroy data show their confirm button in red
