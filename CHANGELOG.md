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
- NiiX for Linux, as a Flatpak: chats, groups, files and voice messages, the passcode, duress
  passcode and inactivity wipe, backups, linked devices, blocking and remote wipe, in the same
  twelve languages. Link it to your phone (it shows a QR code to scan in Settings › Security ›
  Linked devices) or use it with an account of its own. Its keys are kept in the desktop
  keyring, it keeps receiving with its window closed, and it can start at login. Voice calls
  work there as on the phone, from a chat's menu; use a headset if the other person hears
  themselves
- On Linux: an icon in the system tray, where the desktop has one. Closing the window leaves NiiX
  running there, and so does minimising it on desktops that tell apps about that; clicking the
  icon brings the window back, and its menu opens, locks or quits NiiX. On GNOME the tray needs
  the AppIndicator extension
- On Linux: the phone app's six themes, in Settings › Theme
- Right-click a chat in the Linux app's list, or long-press a chat or a contact in the contacts
  drawer on Android, for what the chat's own menu offers: rename, safety number, disappearing
  messages, mute, block and delete
- Calls ring while NiiX is locked. A locked NiiX can't tell who is calling, so it says only
  "Incoming call"; answer, enter your passcode, and the call is picked up. Both people need this
  version, and it starts working once their apps have been online together. Settings › Security ›
  Ring while NiiX is locked turns it off
- The call screen says when the other side's audio is arriving late ("Slow connection") or not
  at all ("Connection stalled"), so a slow Tor circuit isn't mistaken for the app misbehaving
- A section on voice calls in docs/HOW-IT-WORKS.md: what they sound like, how much data they use,
  and when they can ring
- Settings › Updates › Include pre-releases (GitHub version, off by default): also offers versions
  marked as pre-releases on GitHub. They're verified against the same signing key as every other
  update, marked "(pre-release)" when offered, and turning the setting off never goes back to an
  older version
- Voice calls, end-to-end encrypted with the same post-quantum protection as messages, and
  carried over Tor so neither person learns the other's IP address. Off for everyone by default:
  each person turns them on for a contact from the chat menu, a call only goes through once both
  have, and only with contacts whose safety number has been verified. Either person turning it
  off, or blocking the other, ends a call at once. Calls from anyone else never ring. Both
  screens show the same eight-digit code to compare out loud. Expect about a second of delay.
  There is no call history; a missed call is only a notification, which never says who called
- Messages reach people who are offline: they wait, encrypted, on Nostr relays and arrive as soon
  as the recipient is back. Works once you and a contact have been online together once.
  Choose your own relays (including .onion relays) or turn it off in Settings. Envelopes go into
  buckets shared with other people, and you listen to extra decoy buckets, so a relay can't tell
  your contacts apart or count them
- Setting up and unlocking show each step as it happens, so a few seconds of key derivation no
  longer look like a frozen screen
- The passcode is optional when setting up, as it already was in Settings
- A profile page, opened from your picture on the main screen or from Settings: a large photo,
  your name, and a small QR code that opens the full-size one. Tap your photo to see it full size,
  or to add one if you have none
- Each contact has a profile page too, opened by tapping their picture on the main screen or the
  name at the top of a chat: their photo full size, their address, and sending a message,
  renaming, the safety number and whether it's verified, muting and blocking in one place
- Tapping a group's name at the top of its chat opens group info
- Tapping a member in group info opens their profile page too. It has the same options, adding them
  to your contacts if they aren't one, and for group admins promoting or removing them. Removing
  someone asks first
- "Report a bug or request a feature" in Settings opens the GitHub issues page
- Themes, from Settings › Theme: Pink, Green, Orange, Royal blue, Light (black on white) and
  Dark (white on black). A theme changes every colour in the app and the app icon to match. Some
  home screens put the icon back in the app list when it changes
- Dialogs, menus and sheets are see-through glass over a dimmed screen, which Android 12 and later
  also blur
- Available in English, German, French, Spanish, Italian, Russian, Persian, Chinese, Arabic,
  Japanese, Thai and Korean. On Android 13 and later the language can be set for NiiX alone
- The built-in keyboard has German, French, Spanish, Italian, Russian, Arabic and Persian layouts,
  with a key to switch to Latin letters
- Option to use the phone's own keyboard instead, needed for Chinese, Japanese, Korean and Thai
- Unblock people, from Settings › Privacy › Blocked or from the conversation menu
- "Send again" on a message that failed, from its long-press menu
- Backups include your photos, files and profile pictures. Older backups still restore
- Linking a device asks for confirmation first and says what the other phone will get
- Use one account on up to five devices. Link a new one by scanning its code from Settings ›
  Security › Linked devices; it gets your contacts and groups, and everything you send from any
  device shows up on the others. Removing a device erases it, and a wipe on any device, including
  the duress passcode, erases all of them

### Changed
- On phones with gesture navigation, swiping in from the screen's edge on the main screen does the
  same as swiping across it: from the left edge opens your contacts, from the right puts NiiX in
  the background (Android 14 and later). Back on the main screen never closes NiiX outright; it
  goes to the background and keeps running
- The reminder to let calls ring on screen checks whether it worked when you come back from
  settings, says exactly where the switch is on phones where the direct page doesn't do it, and
  has "Don't ask again"
- Muting a call closes the microphone, so Android's microphone indicator goes off while muted
- On the main screen, swipe right to open your contacts and left to put NiiX in the background,
  where it keeps running (mirrored in right-to-left languages)
- Starting a call while one is still going on opens that call instead of failing
- The + button on the main screen goes straight to starting something new, with a New group tab
  beside entering a code and scanning one, instead of first asking which
- A chat's options open in the same glass dialog as Attach, from a ⋮ button, instead of the
  system's menu
- Holding the send button in a chat brings up a call button just above it
- Checks for updates over Tor by default, each time the app is opened and about once a day while
  it stays open
- "Wipe if not used" is set with a slider, from 3 hours to 7 days. Settings of 14 or 30 days from
  1.0.1 keep working until changed
- "Wipe if not used" also counts using NiiX without a passcode, not only unlocking it, and erases
  only the device it's set on, not your other linked devices. Winding the phone's clock back no
  longer delays it: time is counted with Android's time since boot, which can't be set, and saved
  every quarter of an hour so a reboot doesn't lose it
- With a passcode, the app locks a minute after you leave it, even if you don't come back. Messages
  that arrive meanwhile wait encrypted and appear when you unlock. A message you just sent that
  hasn't got anywhere yet holds the lock off for up to five more minutes, since nothing can be
  sent while locked
- The app is called NiiX everywhere: home screen, app list, installer and notifications. Before,
  some of these said "Sync"
- The app icon is NiiX pink with a thin black outline, and the notification icon is tinted pink
  where Android shows colour
- "Disconnect when locked" is under Security instead of Updates

### Removed
- Calculator disguise. NiiX always opens to its own lock screen, or straight in if you have no
  passcode. Anything the calculator had in memory, including a duress code saved there, is deleted
  on update
- Relay mode. Your phone no longer holds messages for other people; offline messages wait on
  Nostr relays instead, which are always online. Anything relay mode had stored is deleted on
  update

### Fixed
- A call could be refused with "hasn't allowed calls with you yet" for a while after the other
  person had allowed them, until their message saying so arrived. The call is now tried, and their
  phone decides; each call also brings what one phone knows about the other up to date
- Declining a call stops it ringing for the caller even over a shaky connection, and the caller
  can call again straight away. Ending, declining and answering are now retried until they arrive,
  instead of being sent once and sometimes lost
- Updating NiiX from inside the app no longer turns off full-screen notifications on Android 14
  and later, so calls keep ringing on screen after an update without turning it back on by hand
- Picking up a call connects much faster: both phones open the connection to each other while it
  rings, so the answer and the audio don't have to wait for Tor to build a new route first
- With a chat open, a message sent after a short pause could be taken as refused and sent again
  over a new connection, which made it slower to arrive. The reply to each message is now read in
  full, so the open connection keeps being used
- Calls ring until they're answered or declined. Where Android doesn't allow NiiX full-screen
  notifications, the ringing banner comes back every few seconds instead of disappearing, and
  NiiX offers to open the setting that fixes it
- Unlocking is several times faster, with the same protection: the passcode is stretched by native
  code instead of Java, the phone's secure hardware is asked for its part of the key at the same
  time, and a redundant second stretching step in the database layer is gone. The first unlock
  after updating converts the database and takes a moment longer
- Unlocking no longer fails or freezes when the unlock button is pressed twice or the phone is
  short of memory
- Opening the app without a passcode no longer stalls while the database reopens
- Tapping "Create identity" twice no longer reports that the identity could not be created
- Setup and the lock screen follow the screenshot setting instead of always blocking screenshots
- Android 10 to 13: the background connection no longer fails to start
- Messages that arrive while both the phone and the app are locked are kept for when you unlock,
  instead of being refused until the sender's next attempt
- A crash at the wrong moment just after changing your passcode can no longer roll the database
  back to the old one and lock you out
- The app starts faster: an unused encrypted store that was set up on every launch is gone
- Setting up an account and topping up encryption keys write in one go instead of hundreds of
  separate disk writes
- The duress passcode opens the decoy account, filled with ordinary conversations in your language,
  instead of wiping and then saying the passcode was wrong
- Setting up a new account right after a wipe no longer fails
- Wiping, from the main screen, Settings or remotely, goes straight to account setup instead of
  leaving the old screen up
- Messages keep arriving after Android closes the app while it's locked, after the phone restarts
  and after an update, without opening the app first. They wait, still encrypted, until you unlock.
  "Disconnect when locked" still stops all of this
- Messages that stopped decrypting, for instance after one side restored a backup, get through
  again: the two phones start a new session by themselves instead of failing until the sender gives
  up a month later
- Every member of a group gets a photo or file, including members who were offline when it was
  sent. Before, it counted as delivered as soon as one member had it
- A file the recipient already has is no longer uploaded again and again, and a message with an
  attachment only shows as delivered once the file has arrived
- An interrupted photo or file transfer carries on where it stopped instead of starting over
- After restoring a backup, contacts can reach you right away. Until the app restarted, it kept
  answering on the address it had before the restore
- Your other linked devices get the photos and files you send even if they were off at the time
- Turning a passcode on or off can no longer lock you out of your data if the app is closed or the
  phone runs out of space partway through
- Messages received while locked are no longer lost if the phone locks again while they're being
  opened
- A message to someone with several linked devices keeps going to the devices that haven't got it
  yet, instead of stopping once one of them confirms
- Decrypted copies of photos and files you opened are deleted a minute after you leave the app,
  not only the next time the lock screen appears
- A clock that once ran far fast no longer leaves the app's times stuck in the future
- Messages that expired while the app was locked are deleted before anything is shown on unlock
- Messages arrive much faster. A conversation keeps its connection open between messages, and
  opens it as soon as the chat is on screen, so a message goes out in one trip instead of waiting
  for Tor to connect first
- Group messages go to several members at once, and to all of a contact's devices at once,
  instead of one after another
- A message to someone who's offline is on a relay within about five seconds, instead of after up
  to a minute of trying to reach them directly
- Incoming messages no longer stop arriving directly after the app was opened twice while it was
  still connecting. Until a restart, everything came the slow way through relays, or not at all
- Messages sent to the same person at the same moment (a message and a read receipt, say) no longer
  get scrambled so that some can't be opened on the other end and have to be sent again
- After losing signal or switching between Wi-Fi and mobile data, messages flow again within
  seconds instead of up to 20 minutes later
- If connecting fails, for example on a weak network, the app keeps trying instead of sitting idle
  until it's next opened. It also checks every few minutes that it can still be reached, and
  reconnects by itself if not, which used to take a force stop
- One contact being offline no longer holds up messages to everyone else

### Security
- Wrong passcode attempts are counted before they're checked, so closing the app mid-attempt no
  longer gets a guess past the growing delay
- An incoming connection can't be held open indefinitely to block others, and a sender posing as
  many can't fill up the space for messages received while locked
- Someone can no longer pose as an address they don't own: the first message from a new address
  is only accepted once that address has confirmed the same key over Tor
- Backup passphrases must be at least 12 characters of any kind and are typed twice. Backups made
  with a short numeric passphrase still restore, but are worth making again
- Your name, photo and read receipts only go to people you've accepted, not to anyone who sends a
  message request
- Receipts can only update messages you sent, and only to "delivered" or "read"
- An attachment offer can't reuse an id that already belongs to another attachment, and its file
  type has to look like one
- The decoy contacts a duress code creates have properly formed addresses, and real encryption
  keys and history, so the decoy holds up to a closer look at the phone's storage
- Other apps on the phone can no longer find NiiX by probing a fixed local port, for incoming
  connections or for Tor
- The identity key held in memory is dropped when the app locks

## 1.0.1 -- 2026-11-04

### Important
- This version is signed with new keys, so Android cannot update a version older than 1.0.0 to
  it. Back up your data (Settings > Backup), uninstall NiiX, install this version and restore.
- If you have 1.0.0, download this version from the releases page and install it over 1.0.0 once.
  Your data stays. 1.0.0 cannot install it by itself, because its updater still checks for the
  old update key.
- Updates after this one install from inside the app as normal.

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
