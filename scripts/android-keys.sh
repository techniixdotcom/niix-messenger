# shellcheck shell=bash
# android-keys.sh -- one Android signing key for all your apps, and moving an app onto it.
#
# Sourced by the build scripts of TestTube, NiiX and niixfliix, so all of them keep their keys in
# the same place and switch the same way. Every key lives in ~/.keys (or KEYS_HOME):
#
#   ~/.keys/android/release.p12, signing.env   the Android key all apps share
#   ~/.keys/<app>/                             the app's own original key, and lineage.bin once
#                                              the app has switched to the shared key
#   ~/.keys/linux/                             the key Linux releases are signed with
#
# Switching is Android's key rotation (APK Signature Scheme v3). The app's own key signs, once,
# its approval of the shared key: lineage.bin. Every APK built after that carries the approval and
# is signed with both keys. Android 9 and newer accept it as a normal update of the old app and
# from then on only take the shared key; Android 8 has no rotation and keeps checking the
# original key, which is why that key has to be kept too.
#
# The caller sets these before using the functions:
#   AK_KEYTOOL     keytool of a JDK 17 or newer
#   AK_APKSIGNER   apksigner from the Android build tools (only for switching and signing)
#   AK_JAVA_HOME   the JDK apksigner runs on
# Every function prints what went wrong and returns non-zero instead of exiting, so the caller
# decides how to stop.

AK_HOME="${KEYS_HOME:-$HOME/.keys}"
AK_SHARED_DIR="$AK_HOME/android"
AK_SHARED_KS="$AK_SHARED_DIR/release.p12"
AK_SHARED_ENV="$AK_SHARED_DIR/signing.env"
# Rotation applies from Android 9 (API 28). apksigner 33+ would otherwise wait for Android 13.
AK_ROTATION_MIN_SDK=28

# The same look as the BUILD.sh of every app: "==>" for steps, "warning:" and "error:".
ak_say() { printf '==> %s\n' "$*"; }
ak_warn() { printf 'warning: %s\n' "$*" >&2; }
ak_err() { printf 'error: %s\n' "$*" >&2; return 1; }

# Questions go to the terminal, not through a log pipe that could hold them back.
ak_ask() {
	local __ak_prompt="$1" __ak_var="$2" __ak_reply=""
	if { true > /dev/tty; } 2> /dev/null; then
		printf '%s' "$__ak_prompt" > /dev/tty
		IFS= read -r __ak_reply < /dev/tty || __ak_reply=""
	else
		printf '%s' "$__ak_prompt"
		IFS= read -r __ak_reply || __ak_reply=""
	fi
	printf -v "$__ak_var" '%s' "$__ak_reply"
}

ak_yes() {
	local answer
	ak_ask "$1 [y/N] " answer
	[[ "$answer" =~ ^[Yy] ]]
}

ak_sha256() {
	if command -v sha256sum > /dev/null 2>&1; then
		sha256sum "$1" | cut -d' ' -f1
	else
		shasum -a 256 "$1" | cut -d' ' -f1
	fi
}

# The value of the first of the given names in a key=value file (signing.env, keystore.properties).
ak_value() {
	local file="$1" name line
	shift
	for name in "$@"; do
		while IFS= read -r line || [[ -n "$line" ]]; do
			line="${line%$'\r'}"
			if [[ "$line" == "$name="* ]]; then
				printf '%s' "${line#"$name"=}"
				return 0
			fi
		done < "$file"
	done
	return 1
}

# Only you can open ~/.keys: folders 700, files 600. Writes the README the first time.
ak_secure() {
	mkdir -p "$AK_HOME" || return 1
	find "$AK_HOME" -type d -exec chmod 700 {} + 2> /dev/null || true
	find "$AK_HOME" -type f -exec chmod 600 {} + 2> /dev/null || true
	[[ -f "$AK_HOME/README.txt" ]] && return 0
	(umask 077; cat > "$AK_HOME/README.txt" << 'README'
All signing keys, for every app. Only you can open this folder; keep it that way.

android/release.p12, android/signing.env
    The Android key all apps use. Its password is in signing.env.
testtube/, niix/, niixfliix/
    Each app's own original Android key, and lineage.bin once the app uses the shared key:
    the original key's signed approval of it. Keep both for good. Android 8 phones keep
    checking the original key, and without lineage.bin no update installs over the old app.
    niix/ also holds NiiX's update-signing key (update-signing-key.pem).
linux/
    The key Linux releases are signed with (linux-signing-key.pem), earlier ones in old/, and
    handover.txt: each earlier key's signed approval of the next one.
windows/
    Windows signing settings, once an app needs them. The Windows key itself has to stay on its
    hardware token or in the signing service.

Back up this whole folder to two places that are offline, such as two USB sticks. Any of the
build scripts does it and checks the copy: ./BUILD.sh --backup-keys in any app's folder. Anyone
with these files can sign updates for your apps; without them no new version can update what
people have installed.
README
	) || return 1
	chmod 600 "$AK_HOME/README.txt"
}

# Moves the key files of one folder into another (~/.keys/<app>), checking every copy. Files
# already in the target are never replaced. With "keep" as third argument the source is left as
# it was (for example a USB stick).
ak_move_dir() {
	local from="${1%/}" to="${2%/}" keep="${3:-}" file
	[[ -d "$from" ]] || return 0
	mkdir -p "$to" || return 1
	chmod 700 "$to"
	while IFS= read -r file; do
		[[ -n "$file" ]] || continue
		if [[ -e "$to/$file" ]]; then
			if [[ -f "$from/$file" && "$(ak_sha256 "$from/$file")" != "$(ak_sha256 "$to/$file")" ]]; then
				ak_err "$to/$file already exists and differs from $from/$file; neither was changed"
				return 1
			fi
			continue
		fi
		mkdir -p "$(dirname "$to/$file")" || return 1
		(umask 077; cp -p "$from/$file" "$to/$file.tmp") || { rm -f "$to/$file.tmp"; return 1; }
		if [[ "$(ak_sha256 "$from/$file")" != "$(ak_sha256 "$to/$file.tmp")" ]]; then
			rm -f "$to/$file.tmp"
			ak_err "Could not copy $from/$file to $to (it is still in $from)"
			return 1
		fi
		mv "$to/$file.tmp" "$to/$file" || return 1
	done < <(cd "$from" && find . -type f | sed 's#^\./##')
	if [[ "$keep" != "keep" ]]; then
		rm -rf "${from:?}"
	fi
	ak_secure
}

ak_shared_exists() {
	[[ -f "$AK_SHARED_KS" && -f "$AK_SHARED_ENV" ]]
}

ak_shared_password() {
	ak_value "$AK_SHARED_ENV" KEYSTORE_PASSWORD
}

ak_shared_alias() {
	ak_value "$AK_SHARED_ENV" KEY_ALIAS || printf 'release'
}

ak_random_secret() {
	od -An -tx1 -N32 /dev/urandom | tr -d ' \n'
}

# A new RSA 4096 key, valid 100 years. The name in its certificate is public (anyone can read it
# from an APK), so it says nothing personal.
ak_create_shared() {
	ak_shared_exists && return 0
	if [[ -e "$AK_SHARED_KS" || -e "$AK_SHARED_ENV" ]]; then
		ak_err "$AK_SHARED_DIR holds only part of the shared key. Restore it from your backup."
		return 1
	fi
	mkdir -p "$AK_SHARED_DIR" || return 1
	chmod 700 "$AK_HOME" "$AK_SHARED_DIR"
	local password
	password="$(ak_random_secret)"
	[[ ${#password} -eq 64 ]] || { ak_err "Could not create a password"; return 1; }
	if ! (umask 077; AK_NEW_PASSWORD="$password" "$AK_KEYTOOL" -genkeypair -noprompt \
		-keystore "$AK_SHARED_KS" -storetype PKCS12 \
		-storepass:env AK_NEW_PASSWORD -keypass:env AK_NEW_PASSWORD \
		-alias release -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Release" > /dev/null 2>&1); then
		rm -f "$AK_SHARED_KS"
		ak_err "keytool could not create the shared Android key"
		return 1
	fi
	(umask 077; printf 'KEYSTORE_PASSWORD=%s\nKEY_ALIAS=release\n' "$password" > "$AK_SHARED_ENV") || return 1
	ak_secure
	ak_say "Created the shared Android key in $AK_SHARED_DIR"
}

# The SHA-256 of a key's certificate, lower case without colons, as apksigner prints it.
# Arguments: keystore, store password, alias.
ak_fingerprint() {
	AK_FP_PASSWORD="$2" "$AK_KEYTOOL" -list -v -keystore "$1" -storepass:env AK_FP_PASSWORD -alias "$3" 2> /dev/null \
		| awk -F': ' '/SHA256:/ {print $2; exit}' | tr -d ':' | tr 'A-F' 'a-f'
}

ak_shared_digest() {
	local password alias
	password="$(ak_shared_password)" || { ak_err "$AK_SHARED_ENV has no KEYSTORE_PASSWORD"; return 1; }
	alias="$(ak_shared_alias)"
	ak_fingerprint "$AK_SHARED_KS" "$password" "$alias"
}

ak_apksigner() {
	JAVA_HOME="$AK_JAVA_HOME" PATH="$AK_JAVA_HOME/bin:$PATH" "$AK_APKSIGNER" "$@"
}

# Creates lineage.bin: the app's own key approving the shared key. Done once per app.
# Arguments: own keystore, its store password, its key password, its alias, lineage file.
ak_rotate() {
	local ks="$1" sp="$2" kp="$3" alias="$4" lineage="$5" own shared certs
	own="$(ak_fingerprint "$ks" "$sp" "$alias")"
	shared="$(ak_shared_digest)" || return 1
	[[ -n "$own" ]] || { ak_err "Could not open $ks"; return 1; }
	[[ -n "$shared" ]] || { ak_err "Could not open $AK_SHARED_KS"; return 1; }
	if [[ "$own" == "$shared" ]]; then
		ak_err "That is the shared key already; there is nothing to switch"
		return 1
	fi
	rm -f "$lineage.tmp"
	if ! (umask 077; AK_OLD_SP="$sp" AK_OLD_KP="$kp" AK_NEW_PW="$(ak_shared_password)" ak_apksigner rotate \
		--out "$lineage.tmp" \
		--old-signer --ks "$ks" --ks-key-alias "$alias" --ks-pass env:AK_OLD_SP --key-pass env:AK_OLD_KP \
		--new-signer --ks "$AK_SHARED_KS" --ks-key-alias "$(ak_shared_alias)" \
		--ks-pass env:AK_NEW_PW --key-pass env:AK_NEW_PW > /dev/null); then
		rm -f "$lineage.tmp"
		ak_err "apksigner could not create the key switch"
		return 1
	fi
	certs="$(ak_apksigner lineage --in "$lineage.tmp" --print-certs 2> /dev/null | tr 'A-F' 'a-f' || true)"
	if ! grep -q "$own" <<< "$certs" || ! grep -q "$shared" <<< "$certs"; then
		rm -f "$lineage.tmp"
		ak_err "The key switch doesn't name both keys; nothing was changed"
		return 1
	fi
	mv "$lineage.tmp" "$lineage" || return 1
	chmod 600 "$lineage"
}

# Signs an APK with the app's own key and the shared key, carrying lineage.bin. Replaces the file.
# Arguments: apk, own keystore, store password, key password, alias, lineage file.
ak_sign() {
	local apk="$1" ks="$2" sp="$3" kp="$4" alias="$5" lineage="$6" help
	help="$(ak_apksigner sign --help 2>&1 || true)"
	local extra=(--v4-signing-enabled false)
	grep -q -- '--rotation-min-sdk-version' <<< "$help" && extra+=(--rotation-min-sdk-version "$AK_ROTATION_MIN_SDK")
	grep -q -- '--alignment-preserved' <<< "$help" && extra+=(--alignment-preserved true)
	# Only for tests with APKs whose manifest apksigner can't read; real APKs say it themselves
	[[ -n "${AK_MIN_SDK_OVERRIDE:-}" ]] && extra+=(--min-sdk-version "$AK_MIN_SDK_OVERRIDE")
	rm -f "$apk.signed" "$apk.signed.idsig"
	if ! AK_OLD_SP="$sp" AK_OLD_KP="$kp" AK_NEW_PW="$(ak_shared_password)" ak_apksigner sign \
		--ks "$ks" --ks-key-alias "$alias" --ks-pass env:AK_OLD_SP --key-pass env:AK_OLD_KP \
		--next-signer --ks "$AK_SHARED_KS" --ks-key-alias "$(ak_shared_alias)" \
		--ks-pass env:AK_NEW_PW --key-pass env:AK_NEW_PW \
		--lineage "$lineage" "${extra[@]}" --out "$apk.signed" "$apk"; then
		rm -f "$apk.signed" "$apk.signed.idsig"
		ak_err "apksigner could not sign $(basename "$apk") with the shared key"
		return 1
	fi
	rm -f "$apk.signed.idsig"
	mv "$apk.signed" "$apk"
}

# The certificate apksigner sees for a range of Android versions. Arguments: apk, from, to.
ak_signer_for() {
	ak_apksigner verify --print-certs --min-sdk-version "$2" --max-sdk-version "$3" "$1" 2> /dev/null \
		| awk -F': ' '/Signer #1 certificate SHA-256 digest/ {print $2; exit}' | tr 'A-F' 'a-f'
}

# Checks a switched APK: from Android 9 on it shows the shared key, below that the app's own.
# Arguments: apk, own key's digest, the app's minSdk.
ak_check() {
	local apk="$1" own="$2" min="$3" shared name
	shared="$(ak_shared_digest)" || return 1
	name="$(basename "$apk")"
	if (( min < AK_ROTATION_MIN_SDK )); then
		[[ "$(ak_signer_for "$apk" "$min" $((AK_ROTATION_MIN_SDK - 1)))" == "$own" ]] \
			|| { ak_err "$name: Android 8 doesn't see the app's own key, so it couldn't update there"; return 1; }
		[[ "$(ak_signer_for "$apk" "$AK_ROTATION_MIN_SDK" "$AK_ROTATION_MIN_SDK")" == "$shared" ]] \
			|| { ak_err "$name: Android 9 doesn't see the shared key"; return 1; }
	else
		[[ "$(ak_signer_for "$apk" "$min" "$min")" == "$shared" ]] \
			|| { ak_err "$name: Android doesn't see the shared key"; return 1; }
	fi
	[[ "$(ak_signer_for "$apk" "$AK_ROTATION_MIN_SDK" 2147483647)" == "$shared" ]] \
		|| { ak_err "$name: newer Android versions don't see the shared key"; return 1; }
}

# The switch itself, with questions. Creates the shared key if there is none yet.
# Arguments: app name, own keystore, store password, key password, alias, lineage file.
ak_switch() {
	local app="$1" ks="$2" sp="$3" kp="$4" alias="$5" lineage="$6" own shared
	if [[ -f "$lineage" ]]; then
		ak_say "$app already uses the shared Android key"
		return 0
	fi
	if ! ak_shared_exists; then
		ak_say "There is no shared Android key yet. It is made once, and all your apps use it."
		ak_yes "Create the shared Android key in $AK_SHARED_DIR now?" || { ak_err "Nothing was changed"; return 1; }
		ak_create_shared || return 1
	fi
	own="$(ak_fingerprint "$ks" "$sp" "$alias")"
	shared="$(ak_shared_digest)" || return 1
	[[ -n "$own" ]] || { ak_err "Could not open $ks with its password"; return 1; }
	[[ -n "$shared" ]] || { ak_err "Could not open $AK_SHARED_KS with its password"; return 1; }
	if [[ "$own" == "$shared" ]]; then
		ak_say "$app's key is the shared key, so there is nothing to switch"
		return 0
	fi
	ak_say "$app's own key:     $own"
	ak_say "Shared Android key: $shared"
	ak_warn "This can't be undone. From the next build on, $app is signed with the shared key, and installed copies update to it. Keep both keys for good (they are in $AK_HOME)."
	ak_yes "Switch $app to the shared Android key?" || { ak_err "Nothing was changed"; return 1; }
	ak_rotate "$ks" "$sp" "$kp" "$alias" "$lineage" || return 1
	ak_secure
	ak_say "Done. The next release build of $app is signed with the shared Android key."
	ak_say "Back up $AK_HOME now: lineage.bin is new, and without it no update installs over this one."
}

# Copies all of ~/.keys to TARGET/.keys and checks every file.
ak_backup() {
	local target="${1%/}" copy file bad=""
	[[ -d "$AK_HOME" ]] || { ak_err "There are no keys in $AK_HOME yet"; return 1; }
	[[ -n "$target" ]] || { ak_err "No folder given"; return 1; }
	case "$target" in
		"$AK_HOME"|"$AK_HOME"/*) ak_err "Not inside $AK_HOME itself: a backup has to be somewhere else"; return 1 ;;
	esac
	copy="$target/.keys"
	if [[ -e "$copy" ]]; then
		ak_yes "$copy already exists. Replace it with the keys from $AK_HOME?" || { ak_err "Nothing was copied"; return 1; }
	fi
	mkdir -p "$target" || return 1
	rm -rf "${copy:?}.new"
	(umask 077; mkdir -p "$copy.new" && (cd "$AK_HOME" && tar -cf - .) | (cd "$copy.new" && tar -xf -)) \
		|| { rm -rf "${copy:?}.new"; ak_err "Could not copy to $target"; return 1; }
	while IFS= read -r file; do
		[[ "$(ak_sha256 "$AK_HOME/$file")" == "$(ak_sha256 "$copy.new/$file" 2> /dev/null || true)" ]] || bad+=" $file"
	done < <(cd "$AK_HOME" && find . -type f | sed 's#^\./##')
	if [[ -n "$bad" ]]; then
		rm -rf "${copy:?}.new"
		ak_err "The copy in $target doesn't match ($bad). Check the drive and try again."
		return 1
	fi
	rm -rf "${copy:?}"
	mv "$copy.new" "$copy" || return 1
	chmod -R go-rwx "$copy" 2> /dev/null || true
	ak_say "All signing keys copied to $copy and checked"
	ak_say "Keep it somewhere safe and private: anyone with these files can sign updates for your apps"
}
