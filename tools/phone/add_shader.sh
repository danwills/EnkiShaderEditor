#!/bin/sh
# Puts a shader into Enki debug's library on the test phone, with no UI:
#
#   tools/phone/add_shader.sh [--open] shader.glsl [name] [quality]
#
# The name defaults to the file's (without .glsl); a shader of that name
# is replaced in place, keeping its id, or else added (quality, default
# 0.25, is for a new one). The file can be multipass text (`//// Buffer A`,
# `//// Text name` sections), as Copy and Share make and vti2enki writes.
# Goes through the debug build's ShaderImportReceiver (src/debug).
#
# If Enki debug is on screen it asks on the phone whether to replace or
# ignore (the open shader's edits would otherwise be saved over it);
# otherwise its process is stopped first (it saved when it left the
# screen) and the import just happens; --open then starts Enki debug on
# it (not when it was on screen: the phone's asking first). ANDROID_SERIAL
# picks the phone.
set -e
PKG=enk.danwills.android.enkishadereditor.debug
open=
if [ "$1" = --open ]; then
	open=1
	shift
fi
file="$1"
name="${2:-$(basename "$file" .glsl)}"
quality="${3:-0.25}"
if [ ! -f "$file" ]; then
	echo "usage: $0 [--open] shader.glsl [name] [quality]" >&2
	exit 2
fi
if adb shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q "$PKG/"; then
	on_screen=1
else
	on_screen=
	adb shell am force-stop "$PKG"
	# That took the status panel with it: put it back.
	"$(dirname "$0")/status.sh" again
fi
adb push "$file" /data/local/tmp/enki-import.glsl >/dev/null
# A file of its own each time: several sent while Enki's on screen wait in
# their dialogs together, each for its own.
incoming="incoming-$(date +%s%N).glsl"
adb shell "cat /data/local/tmp/enki-import.glsl | run-as $PKG sh -c 'mkdir -p files/import && cat > files/import/$incoming'"
adb shell rm -f /data/local/tmp/enki-import.glsl
q=$(printf "'%s'" "$(printf %s "$name" | sed "s/'/’/g")")
adb shell "am broadcast -n $PKG/enk.danwills.android.enkishadereditor.receiver.ShaderImportReceiver --es name $q --es file $incoming --ef quality $quality" >/dev/null
if [ -n "$on_screen" ]; then
	echo "Enki debug is on screen - asking on the phone."
	exit 0
fi
# The receiver runs on its own thread; give it a moment, then report.
sleep 2
report=$(adb logcat -d -s ShaderImport:I | tail -n 1 | sed 's/^.*ShaderImport: //')
echo "$report"
# "Added <name> (<id>)" or "Replaced <name> (<id>)".
id=$(printf '%s\n' "$report" | sed -n 's/^.*(\([0-9][0-9]*\))$/\1/p')
if [ -n "$open" ] && [ -n "$id" ]; then
	adb shell am start -n $PKG/enk.danwills.android.enkishadereditor.activity.MainActivity \
			--el open_shader "$id" >/dev/null 2>&1
	echo "opened $id"
fi
