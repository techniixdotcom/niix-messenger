# Publishing to F-Droid

Everything here is ready to submit, but **two things will get this rejected as it stands**. Both
are about the project, not about these files, so they can't be fixed from inside this folder.
Read the blockers first.

## Blocker 1: there is no LICENSE file

The metadata declares `GPL-3.0-only`, but the repository has no `LICENSE` file. F-Droid requires
a free software licence, and reviewers check that the declared licence matches what is actually
in the repo. A mismatch — or a missing file — is a standard rejection.

Pick a licence and add it at the repository root before submitting. **Change the `License:` line
in `metadata/app.niix.yml` to match whatever you choose.** GPL-3.0-only is a placeholder I put
there, not a decision I can make for you; it is a common choice for this kind of app because it
requires derivatives to stay open, which matters when people are trusting the source.

This is genuinely your call and it is not reversible in practice once others have the code.

## Blocker 2: prebuilt native binaries

The app depends on `libsignal-android` and `kmp-tor-resource`, both of which ship precompiled
`.so` files: libsignal's Rust core, the tor binary, and SQLCipher.

F-Droid builds everything from source and its scanner flags prebuilt binaries. There are three
realistic outcomes:

1. **Rejected** until the binaries are built from source in the build recipe. That means adding
   Rust and C toolchains to `sudo:` and `prebuild:` steps and compiling libsignal and tor as part
   of the build. It is a substantial piece of work and it is what other Signal-derived apps have
   had to do.
2. **Accepted with the `NonFreeAssets` anti-feature declared.** Undeclared anti-features are
   themselves a rejection reason, so if you go this route, declare it honestly in the metadata.
3. **Publish through your own F-Droid repository** instead of the main one. You keep control,
   users add your repo URL, and the prebuilt-binary rule does not apply. This is the fastest
   route and it is what many privacy apps do.

Option 3 is worth considering seriously given where the project is. It gets you F-Droid
distribution without the toolchain work, and you can move to the main repo later.

## What is in this folder

```
metadata/app.niix.yml                          the build recipe
fastlane/metadata/android/en-US/title.txt
fastlane/metadata/android/en-US/short_description.txt
fastlane/metadata/android/en-US/full_description.txt
fastlane/metadata/android/en-US/images/phoneScreenshots/   (empty - add your own)
```

Screenshots are optional but the listing looks abandoned without them. Put PNGs or JPEGs in
`phoneScreenshots/`. Given the app's threat model, use a test account with fake conversations —
not your own.

## One thing already handled

F-Droid signs with its own key, so no keystore exists at build time. The build deliberately
*fails* in that situation, because an unsigned release APK can be signed by anyone later and
silently producing one is a supply-chain hazard.

The recipe's `prebuild:` step sets `niixUnsigned=true`, which makes the omission explicit rather
than accidental. That distinction is the whole point of the check, so do not remove it.

## Submitting

1. Fork https://gitlab.com/fdroid/fdroiddata and clone your fork
2. Create a branch named after the application id: `app.niix`
3. Copy `metadata/app.niix.yml` into `metadata/` in that repo
4. Install `fdroidserver`, then verify locally before opening anything:

```
fdroid readmeta
fdroid lint app.niix
fdroid build app.niix:3509762
```

That last command runs the actual build in F-Droid's environment. **Run it.** It is where the
prebuilt-binary problem will surface concretely, and finding that yourself is faster than
waiting for a reviewer to find it.

5. Open a merge request against `fdroiddata`

The fastlane files live in *your* repository, not in fdroiddata — F-Droid reads them from the
tagged commit. So `fdroid/fastlane/...` needs to be committed and included in the tag you point
`commit:` at.

## Keeping it updated

`AutoUpdateMode: Version v%v` with `UpdateCheckMode: Tags` means F-Droid picks up new `v*` tags
automatically and opens its own merge requests. Your existing publish flow already creates those
tags, so this should keep working without further effort.

One caveat: version codes are derived from the clock (minutes since 2020-01-01), so they jump by
large amounts between releases. That is fine for F-Droid and guarantees they always increase,
but do not be surprised by the numbers.
