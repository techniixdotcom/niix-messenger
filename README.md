# NiiX

NiiX is a messenger for Android that's built to keep your conversations, and the fact that you're
having them, to yourself.

You don't sign up for it. There's no phone number, no email address and no account. There's also
no company server in the middle that stores your messages or knows who your contacts are, because
NiiX doesn't have one. Your phone talks directly to the phones of the people you're messaging.

## What it protects you from

**People reading your messages.** Everything you send is encrypted on your phone before it leaves,
and only the person you're talking to can unlock it. That includes photos, files and group chats.
Nobody in between can read it: not your internet provider, not the people running the network,
and not us.

**People finding out who you talk to, or where you are.** Messages travel over Tor, a network that
bounces your connection through several computers run by volunteers around the world. The person
you're messaging never learns your IP address (which would give away roughly where you are), and
you never learn theirs. Someone watching your Wi-Fi sees that you're using Tor, but not who you're
talking to.

**Someone getting hold of your phone.** Everything NiiX stores is locked with your passcode and a
key kept in your phone's security chip, so copying the files off the phone gets an attacker
nothing readable. On top of that you get a few tools for when things go wrong, explained further
down: a duress passcode, a wipe button, an automatic wipe if the app goes unused, and a disguise
that makes NiiX look like a calculator.

**Prying eyes nearby.** New messages show up blurred until you tap them, and notifications only
say that a message arrived, never who it's from or what it says.

## How it works, in plain terms

**Your identity is made on your phone.** The first time you open NiiX it creates a pair of keys
and a Tor address for you. Think of the address as your phone number and the keys as a lock only
you have the key to. Nobody hands these out, and nobody else ever holds your private key.

**You add people in person, or by sharing a code.** Since there's no directory to search, you swap
codes directly: scan each other's QR code, or send your share code some other way you trust. This
is a bit more effort than typing in a phone number, and it's also why nobody can look you up.

**Messages are locked with the Signal protocol**, the same encryption Signal and WhatsApp use. Each
conversation also starts with a newer kind of key exchange designed to hold up against future
quantum computers, so messages recorded today can't be unlocked by one later.

**When someone's offline, other NiiX phones hold the message for them.** These are called relays.
A relay keeps the encrypted message until the other person collects it, for six hours at most, and
can't read it. It doesn't learn who the message is for, and it doesn't learn who sent it either:
each sender uses a random key that the recipient handed to them privately, a different one for
every pair of people. If a message can't be delivered at all, your phone keeps trying for up to 30
days before marking it "Failed to send".

**Updates are signed.** The version from GitHub checks for new releases and won't install one
unless it's signed by us and genuinely newer than what you have. If you got NiiX from Google Play,
Play handles updates instead.

## Getting started

### Install it

You'll need Android 10 or newer. Download the APK from the
[releases page](https://github.com/techniixdotcom/niix-messenger/releases) and open it. Your phone
may ask you to allow installs from your browser or file manager first.

**On a Samsung phone**, a setting called Auto Blocker stops apps being installed from anywhere
except the Galaxy Store and Play Store, and it's switched on by default on newer Galaxy phones.
Turn it off under Settings › Security and privacy › Auto Blocker, install NiiX, and turn it back on
afterwards if you like. It also blocks NiiX's updates, so you'll need to switch it off again each
time you update.

### Set it up

1. Pick a name. Other people can choose the same name, so it's just a label, not a username
   anyone can find you by.
2. Choose a passcode of at least 8 digits. This is what unlocks NiiX, and what protects your data
   if someone gets hold of your phone. There's no "forgot passcode" option, because there's nobody
   to reset it, so pick one you'll remember.
3. Optionally, set a duress passcode (see below). You can also do this later.
4. Tap Create identity. NiiX then connects to Tor, which can take anywhere from a few seconds to a
   minute or two the first time.

### Add a contact

Tap **+** and then **New message**. Then, with the other person:

- one of you taps **Show my code**, and the other taps **Scan QR code** and points the camera at it, or
- one of you copies their share code and sends it, and the other pastes it under **Enter code**.

Scanning in person is the safest way, because you know the code really came from them.

### Check you're really talking to them (optional)

In a conversation, open the menu and tap **Safety number**. If the number on your screen matches
the one on theirs, nobody was sitting in the middle when you first connected. Compare them in
person or over a call where you recognise the voice. In a group, open **Group info** and tap a
member to see your safety number with them.

You don't have to do this. Your messages are encrypted either way. It just rules out someone having
impersonated your contact at the very start.

### Everyday use

- **Long-press a message** to reply to it, copy it, or delete it for yourself or for everyone.
- **Long-press a conversation** on the main screen to mute it or delete it.
- **Pull down** on the main screen or in a chat to check for new messages straight away.
- **Start a group** with **+** and then **Create group**.
- **Make messages disappear** from the chat menu: choose 5 minutes, 1 hour or 1 day. The timer
  starts when a message is actually read, not when it arrives, and the message is deleted from
  both phones when the time runs out. It can't stop the other person taking a screenshot first,
  though, so don't treat it as a guarantee.
- **Change your name or photo** by tapping your picture on the main screen. **My code** is in the
  same menu.

## If your phone might be taken

These are all optional. Most of them live under Settings › Security.

**Duress passcode.** A second passcode. If someone forces you to unlock NiiX and you type this one
instead, it quietly erases everything and opens a fresh account with some ordinary-looking
conversations in it, so it looks like the app simply unlocked.

**Wipe button.** The button at the top of the main screen erases everything on the spot. It asks
you to confirm first, unless you turn that off with *Wipe without confirming*.

**Wipe if not unlocked.** Erases everything if NiiX isn't unlocked for 3, 7, 14 or 30 days. This
works even if the phone is kept offline, which is exactly when a remote wipe can't reach it.

**Remote wipe.** Lets contacts you pick erase this phone from theirs. It's off until you nominate
someone, and they're told when you do.

**Disguise as calculator.** NiiX appears on your home screen as a working calculator. Type your
passcode and press = to open the real app.

**Disconnect when locked** (under Settings › Updates). While NiiX is locked, nothing can reach your
phone at all. Messages arrive once you unlock.

**Allow screenshots** (under Settings › Privacy). Screenshots are allowed by default; switch this
off to block them inside the app.

## Backups

If you lose or reset your phone, your messages and identity go with it unless you've made a
backup. Go to Settings › Backup › Export encrypted backup and choose a passphrase. The file is
useless without that passphrase, so keep both somewhere safe. Restoring is in the same place.

This is the only way your data ever leaves your phone. Android's own cloud backup is switched off
for NiiX on purpose.

## Battery and getting messages on time

NiiX stays connected so messages reach you the moment they're sent, and because of that Android
shows a small ongoing notification while it's running. It uses some battery, though NiiX keeps
Tor's background chatter to a minimum.

Some phones, Samsung, Xiaomi and Huawei especially, shut down apps in the background to save
battery. When that happens to NiiX, messages wait until you open it again, and NiiX will tell you
it happened. To prevent it, let NiiX run without battery restrictions in your phone's settings,
and on a Samsung, make sure it isn't listed under Sleeping apps or Deep sleeping apps.

If you want messages that were held for you to arrive faster when you come back online, turn on
*Tell contacts when I'm back online* under Settings › Privacy. It's off by default because it lets
your contacts see when you reconnect.

## What NiiX can't protect you from

It's worth being honest about this.

- **A phone that's already compromised.** If someone has installed spyware on your phone, they can
  see what's on your screen, and no app can stop that.
- **The person you're talking to.** They can screenshot, photograph the screen, or simply repeat
  what you said.
- **Someone who can watch a very large part of the internet at once.** Tor makes this hard, but
  it isn't magic against the most powerful observers.
- **Mistakes in our code.** NiiX has been checked by several independent reviewers, and we fixed
  what they found. But it hasn't had a professional cryptographic audit, and it hasn't been used
  by many people yet. Treat it as promising, not proven.

If your safety depends on this, read [`docs/THREAT-MODEL.md`](docs/THREAT-MODEL.md), which goes
through what NiiX does and doesn't defend against in detail.

## For the technically curious

- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) explains how the encryption, groups and relays actually
  work, including the places where they're weaker than they could be.
- [`docs/THREAT-MODEL.md`](docs/THREAT-MODEL.md) lists the kinds of attacker NiiX is designed
  against, and the ones it isn't.
- [`PRIVACY.md`](PRIVACY.md) is the privacy policy. It's short, because NiiX collects nothing.
- [`CHANGELOG.md`](CHANGELOG.md) says what's changed, in plain language.

If you find something in these that the code doesn't actually do, please tell us. That's a bug in
one or the other.

### Building it yourself

You'll need a JDK and a Linux or macOS computer. The build script downloads and checks the rest of
what it needs.

```
./build-niix.sh                  # run the tests, then build a debug APK
./build-niix.sh --menu           # a menu with every option
./build-niix.sh --create-keys    # create your signing key (once, before a signed build)
./build-niix.sh --release        # build a signed APK
```

Your signing key is kept in `~/.niix-keys`, outside the project, so it never ends up in a zip or
a copy of the folder. Back that folder up somewhere offline. Builds signed with your own key are
yours: Android won't install an official NiiX release over them, or them over an official one.

## Supporting NiiX

NiiX is free and has no funding behind it. If you'd like to help, donations are welcome in
Monero:

```
47rUgJ3q7DvUTWDQQ2BteSRxLDkLutV78YziycFagKnP1S7wnT9T9R6fo5ti6zFh6BcLfxNYDJsGqJXq85GghUnRNSwXX19
```

The same address is in the app under Settings › About. Check that the two match before sending
anything, and treat an address you find anywhere else as fake. Nothing in the app changes whether
you donate or not.

## Licence

NiiX is free software under the GNU General Public License version 3; the full text is in
[`LICENSE`](LICENSE). You can use it, study it, change it and share it, and anything you share
that's built from it has to stay open in the same way. That matters for a messenger: if nobody can
check the code, you're just taking its promises on trust.

The app includes the JetBrains Mono typeface, used under the SIL Open Font License 1.1; its
licence is in [`licenses/JetBrainsMono-OFL.txt`](licenses/JetBrainsMono-OFL.txt).

Copyright (C) 2026 techniix, cuteLiLi and QuacK.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.
