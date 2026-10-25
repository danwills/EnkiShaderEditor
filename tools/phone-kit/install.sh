#!/bin/sh
# Sets the phone kit up in an Android project, so Claude can ask, narrate
# and be interrupted while it tests on the phone (see README.md).
#
#   install.sh APP_MODULE_DIR DEBUG_APPLICATION_ID [JAVA_PACKAGE]
#
# APP_MODULE_DIR is the app module (the folder with src/), e.g. ./app.
# DEBUG_APPLICATION_ID is what the debug build installs as (with any
# applicationIdSuffix). JAVA_PACKAGE (default: DEBUG_APPLICATION_ID +
# .phonekit) is where the kit's classes go, in the debug source set only.
#
# It copies the Java classes and the dialog theme into src/debug, writes
# src/debug/AndroidManifest.xml (or says what to merge if one exists), and
# writes tools/phone/status.sh in the repo: a wrapper that runs this kit's
# status.sh with the app filled in, so kit improvements reach every
# project. Safe to re-run: it refreshes the copied files.
set -eu

KIT="$(cd "$(dirname "$0")" && pwd)"
MODULE="${1:?usage: install.sh APP_MODULE_DIR DEBUG_APPLICATION_ID [JAVA_PACKAGE]}"
APP_ID="${2:?usage: install.sh APP_MODULE_DIR DEBUG_APPLICATION_ID [JAVA_PACKAGE]}"
PACKAGE="${3:-$APP_ID.phonekit}"
MODULE="$(cd "$MODULE" && pwd)"
[ -d "$MODULE/src" ] || { echo "No src/ in $MODULE: pass the app module directory" >&2; exit 1; }

DEBUG="$MODULE/src/debug"
JAVA_DIR="$DEBUG/java/$(echo "$PACKAGE" | tr . /)"
mkdir -p "$JAVA_DIR" "$DEBUG/res/values"
for f in "$KIT"/android/java/com/example/phonekit/*.java; do
	sed "s/^package com\.example\.phonekit;/package $PACKAGE;/" "$f" > "$JAVA_DIR/$(basename "$f")"
done
cp "$KIT/android/res/values/phone_kit_styles.xml" "$DEBUG/res/values/"
echo "Copied the kit's classes to $JAVA_DIR"

MANIFEST="$DEBUG/AndroidManifest.xml"
if [ ! -e "$MANIFEST" ]; then
	sed -e "s/com\.example\.phonekit\./$PACKAGE./g" \
		-e '/^<!-- Merge into/,/-->$/c\
<!-- The phone kit (phone-testing skill, kit/install.sh). Debug builds only:\
     these are exported so adb'"'"'s shell can reach them. -->' \
		"$KIT/android/AndroidManifest-debug.xml" > "$MANIFEST"
	echo "Wrote $MANIFEST"
elif grep -q "$PACKAGE.SayActivity" "$MANIFEST"; then
	echo "$MANIFEST already has the kit"
elif grep -q "$PACKAGE.TakeOverActivity" "$MANIFEST"; then
	# An older kit: add what's new since (SayActivity), before </application>.
	block=$(sed -n '/<!-- Opened by StatusOverlay/,/PhoneKitDialogTheme"\/>/p' "$KIT/android/AndroidManifest-debug.xml" |
		sed "s/com\.example\.phonekit\./$PACKAGE./g")
	awk -v block="$block" '/<\/application>/ && !done { print block; done = 1 } { print }' "$MANIFEST" > "$MANIFEST.new"
	mv "$MANIFEST.new" "$MANIFEST"
	echo "Added SayActivity to $MANIFEST"
else
	echo "$MANIFEST exists: merge in (with com.example.phonekit -> $PACKAGE):" >&2
	echo "  $KIT/android/AndroidManifest-debug.xml" >&2
fi

REPO="$(git -C "$MODULE" rev-parse --show-toplevel 2>/dev/null || dirname "$MODULE")"
mkdir -p "$REPO/tools/phone"
cat > "$REPO/tools/phone/status.sh" <<WRAPPER
#!/bin/sh
# Phone kit for this project's debug build; the kit itself lives in the
# phone-testing skill (~/.claude/skills/phone-testing/kit), see its README.md.
export PHONE_KIT_APP="\${PHONE_KIT_APP:-$APP_ID}"
export PHONE_KIT_CLASSES="\${PHONE_KIT_CLASSES:-$PACKAGE}"
exec "\$HOME/.claude/skills/phone-testing/kit/status.sh" "\$@"
WRAPPER
chmod +x "$REPO/tools/phone/status.sh"
echo "Wrote $REPO/tools/phone/status.sh"
echo "Next: build and install the debug build, then try: tools/phone/status.sh ask 'Trying the phone kit, ~1 min'"
