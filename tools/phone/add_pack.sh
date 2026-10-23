#!/bin/sh
# Installs a shader pack file into Enki debug's library on the test phone,
# with no UI, as Get Shader Pack -> Install from File would:
#
#   tools/phone/add_pack.sh pack.sqlite
#
# Goes through the debug build's ShaderImportReceiver (src/debug). The
# pack's shaders are added (as "<PackTitle>.<name>"); the open shader isn't
# touched, so this works with Enki debug on screen too (its sidebar shows
# them after a restart). ANDROID_SERIAL picks the phone.
set -e
PKG=enk.danwills.android.enkishadereditor.debug
file="$1"
if [ ! -f "$file" ]; then
	echo "usage: $0 pack.sqlite" >&2
	exit 2
fi
adb push "$file" /data/local/tmp/enki-pack.sqlite >/dev/null
incoming="pack-$(date +%s%N).sqlite"
adb shell "cat /data/local/tmp/enki-pack.sqlite | run-as $PKG sh -c 'mkdir -p files/import && cat > files/import/$incoming'"
adb shell rm -f /data/local/tmp/enki-pack.sqlite
adb logcat -c
adb shell "am broadcast -n $PKG/enk.danwills.android.enkishadereditor.receiver.ShaderImportReceiver --es pack $incoming" >/dev/null
# The receiver installs on its own thread: wait for its report.
for _ in $(seq 60); do
	line=$(adb logcat -d -s ShaderImport:I | grep -v "^-" | tail -n 1 | sed 's/^.*ShaderImport: //')
	if [ -n "$line" ]; then
		echo "$line"
		exit 0
	fi
	sleep 1
done
echo "no answer from the phone after a minute" >&2
exit 1
