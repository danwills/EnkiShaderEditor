#!/bin/sh
# Installs a shader pack file into Enki debug's library on the test phone,
# with no UI, as Get Shader Pack -> Install from File would:
#
#   tools/phone/add_pack.sh [--replace] [--open] pack.sqlite
#
# Goes through the debug build's ShaderImportReceiver (src/debug). The
# pack's shaders are added (as "<PackTitle>.<name>"); the open shader isn't
# touched, so this works with Enki debug on screen too (its sidebar shows
# them after a restart). --replace first takes out the shaders an earlier
# install of the same pack added (as Update Version does), so a pack sent
# over and over doesn't pile up; --open then opens the pack's newest
# shader in the editor. ANDROID_SERIAL picks the phone.
set -e
PKG=enk.danwills.android.enkishadereditor.debug
replace=false
open=
while [ $# -gt 1 ]; do
	case "$1" in
	--replace) replace=true ;;
	--open) open=1 ;;
	*) break ;;
	esac
	shift
done
file="$1"
if [ ! -f "$file" ]; then
	echo "usage: $0 [--replace] [--open] pack.sqlite" >&2
	exit 2
fi
adb push "$file" /data/local/tmp/enki-pack.sqlite >/dev/null
incoming="pack-$(date +%s%N).sqlite"
adb shell "cat /data/local/tmp/enki-pack.sqlite | run-as $PKG sh -c 'mkdir -p files/import && cat > files/import/$incoming'"
adb shell rm -f /data/local/tmp/enki-pack.sqlite
adb logcat -c
adb shell "am broadcast -n $PKG/enk.danwills.android.enkishadereditor.receiver.ShaderImportReceiver --es pack $incoming --ez replace $replace" >/dev/null
# The receiver installs on its own thread: wait for its report (its last
# line; the shader ids come just before it).
for _ in $(seq 60); do
	lines=$(adb logcat -d -s ShaderImport:I | grep -v "^-" | sed 's/^.*ShaderImport: //')
	line=$(printf '%s\n' "$lines" | grep -v '^Shader ids: ' | tail -n 1)
	if [ -n "$line" ]; then
		echo "$line"
		id=$(printf '%s\n' "$lines" | sed -n 's/^Shader ids: //p' | tail -n 1 | tr ',' '\n' | tail -n 1)
		if [ -n "$open" ] && [ -n "$id" ]; then
			adb shell am start -n $PKG/enk.danwills.android.enkishadereditor.activity.MainActivity \
					--el open_shader "$id" >/dev/null 2>&1
			echo "opened $id"
		fi
		exit 0
	fi
	sleep 1
done
echo "no answer from the phone after a minute" >&2
exit 1
