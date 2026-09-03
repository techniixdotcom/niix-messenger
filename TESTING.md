# Runtime verification

These need a real device. They cover the protections the app claims that no automated check can
confirm — the code is written and reviewed, but until these pass the claims are unverified
rather than proven.

Work through them in order. Each says what to do, what should happen, and what it means if it
doesn't.

## 1. The build works at all (do this first)

Release builds now go through R8, which strips unused code. The risk isn't a failed build — it's
something removed that a native library resolves by name at runtime, which only surfaces when
you use the feature.

- [ ] Install the release APK
- [ ] Tor connects (watch the notification reach 100%)
- [ ] Send a message; the other device receives it
- [ ] Reply; the first device receives it
- [ ] Send and open an attachment
- [ ] Create a group and send a message in it
- [ ] Settings → Check now completes

If any fail, set `isMinifyEnabled = false` in `app/build.gradle.kts` and rebuild. That isolates
R8 as the cause immediately.

## 2. The duress wipe destroys data

The app claims a duress passcode wipes your real data. A decoy account appearing is not proof —
the old database could still be on disk, unreferenced. Those look identical from the UI and very
different to anyone examining the phone.

Needs a **debug build** (`./build-niix.sh --debug`); `adb run-as` doesn't work on release builds.

- [ ] Put real data in the app: a conversation, an attachment
- [ ] Note the safety number shown in Settings
- [ ] `./build-niix.sh --verify-duress` — records a snapshot
- [ ] Lock the app, then unlock with the **duress** passcode
- [ ] `./build-niix.sh --verify-duress` again — compares

Expected: the database replaced (new size and timestamp), attachments and Tor state gone.

- [ ] **The safety number in Settings differs from before the wipe**

That last one can't be seen from the filesystem and matters most. If it matches, the app is
still using the pre-wipe identity even though the data is gone — the decoy would be
cryptographically indistinguishable from the real account, defeating the point entirely.

## 3. Group revocation locks a removed member out

The protection with the most code behind it and no runtime verification at all. Needs three
devices: A (admin), B, C.

- [ ] A creates a group with B and C; all three exchange messages
- [ ] A removes C
- [ ] A sends a message → B receives it, **C does not**
- [ ] B sends a message → A receives it, **C does not**
- [ ] C sends a message → **neither A nor B receives it**
- [ ] C tries to change the disappearing-message timer → nothing changes for A or B

If C receives anything, revocation is incomplete. Check Diagnostics on A and B — a correct drop
appears as `dropped Text: sender not a current member of that group`.

Also worth testing, in rough order of likelihood to fail:

- [ ] A removes C **while B is offline**; B comes online, then C sends → B does not receive it
- [ ] C is offline when removed, comes back online → still locked out

The first is the one most likely to break: B only learns about the removal when it processes the
membership update, so there is a window where B's state is stale.

## 4. Identity binding

Confirms a scanned contact's key is enforced on incoming messages, not only outgoing.

- [ ] A scans B's code
- [ ] B messages A first, before A has ever messaged B
- [ ] A receives it normally

The attack this prevents needs a third device impersonating B's address, which is harder to
stage. At minimum confirm the normal path still works — a mistake here would block first contact
rather than allow an attack, and that would be obvious.

## 5. Message delivery

- [ ] Send to a device that is powered off
- [ ] Turn it on → the message arrives
- [ ] Send while the sender is offline → it queues and sends on reconnect
- [ ] Diagnostics shows `peer offline -- queued on relay` rather than a failure

## What to send back

For anything that fails: which step, what happened instead, and Settings → Diagnostics from both
devices. Diagnostics records why a message was dropped or a send failed, which is usually the
difference between finding the cause in minutes and guessing at it.
