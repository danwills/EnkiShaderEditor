#!/bin/sh
# Renders the shader open in Enki debug at any size, as Custom Res/Seq
# would but with no dialog, and pulls the frames back:
#
#   tools/phone/capture.sh WxH [--oversample N] [--frames N] [--skip N]
#       [--fps F] [--bucket SMALL|NORMAL|LARGE|MAX|...] [--shader ID]
#       [--out DIR] [--quiet]
#
# One frame is a single capture; --frames N is a sequence, stepping the
# shader (buffers too) 1/fps s between frames, after --skip steps first.
# --shader ID opens that shader first (add_shader.sh prints its id);
# otherwise Enki debug's editor must already be on screen. Frames land in
# DIR (default ~/dev/shaderEditor/captures/<date-time>/) as frame0000.png...
#
# Each frame (and the end) is posted to the status panel unless --quiet;
# ⏹ on the panel stops the capture. Exits 0 when every frame was saved,
# 1 if it failed, 3 if stopped (whatever frames were made are still
# pulled). Goes through MainActivity's runScriptedCapture() (debug builds
# only), whose log lines are under the ScriptedCapture tag. ANDROID_SERIAL
# picks the phone.
set -e
PKG=enk.danwills.android.enkishadereditor.debug
MAIN=$PKG/enk.danwills.android.enkishadereditor.activity.MainActivity
# MainActivity listens for this while it exists (a broadcast, so it isn't
# paused on the way, as am start would - an ANR mid-capture).
ACTION=enk.danwills.android.enkishadereditor.CAPTURE
STATUS="$(dirname "$0")/status.sh"
usage() {
	sed -n '4,6p' "$0" | sed 's/^# *//' >&2
	exit 2
}
size="$1"
case "$size" in
[0-9]*x[0-9]*) shift ;;
*) usage ;;
esac
extras="--es capture $size"
frames=1
shader=
out=
quiet=
while [ $# -gt 0 ]; do
	case "$1" in
	--oversample) extras="$extras --ei oversample $2"; shift 2 ;;
	--frames) frames="$2"; extras="$extras --ei frames $2"; shift 2 ;;
	--skip) extras="$extras --ei skip $2"; shift 2 ;;
	--fps) extras="$extras --ef fps $2"; shift 2 ;;
	--bucket) extras="$extras --es bucket $2"; shift 2 ;;
	--shader) shader="$2"; shift 2 ;;
	--out) out="$2"; shift 2 ;;
	--quiet) quiet=1; shift ;;
	*) usage ;;
	esac
done
out="${out:-$HOME/dev/shaderEditor/captures/$(date +%Y%m%d-%H%M%S)}"

stop() {
	[ -n "$stopped" ] && return 0
	stopped=1
	echo "Stopped from the panel - stopping the capture." >&2
	adb shell am broadcast -a $ACTION -p $PKG --ez capture_stop true >/dev/null 2>&1
}
stopped=

# A line on the panel (and here); a ⏹ there stops the capture.
post() {
	echo "$1"
	[ -n "$quiet" ] && return 0
	set +e
	"$STATUS" post "$@"
	code=$?
	set -e
	[ $code -eq 3 ] && stop
	return 0
}

if [ -n "$shader" ]; then
	post "Opening shader $shader for the capture" load '~5 s'
	adb shell am start -n $MAIN --el open_shader "$shader" >/dev/null 2>&1
	sleep 5
fi
if ! adb shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q "$MAIN\|$PKG/.activity.MainActivity"; then
	echo "Enki debug's editor isn't on screen: open the shader, or pass --shader ID." >&2
	exit 1
fi

since=$(adb shell date "+%m-%d\ %H:%M:%S.000" | tr -d "\r")
post "Capturing $size, $frames frame(s)" step '?'
adb shell am broadcast -a $ACTION -p $PKG $extras >/dev/null 2>&1
seen=0
result=
while [ -z "$result" ]; do
	sleep 2
	# ⏹ (or the notification swiped away) stops it now, not at the next
	# frame - minutes away for a heavy shader.
	if [ -z "$quiet" ] && [ -z "$stopped" ] && ! "$STATUS" check >/dev/null; then
		stop
	fi
	log=$(adb logcat -d -T "$since" -s ScriptedCapture:I | sed -n 's/^.*ScriptedCapture: //p')
	n=$(printf '%s\n' "$log" | grep -c '^frame ' || true)
	if [ "$n" -gt "$seen" ]; then
		post "$(printf '%s\n' "$log" | grep '^frame ' | tail -n 1)" good '?'
		seen=$n
	fi
	result=$(printf '%s\n' "$log" | grep -m1 -E '^(done|failed|stopped)' || true)
done

mkdir -p "$out"
for f in $(adb shell run-as $PKG ls files/capture 2>/dev/null | tr -d '\r'); do
	adb exec-out run-as $PKG cat "files/capture/$f" > "$out/$f"
done
echo "$result -> $out"
case "$result" in
done*) post "Capture $result" party; exit 0 ;;
stopped*) post "Capture $result" warn; exit 3 ;;
*) post "Capture $result" bad; exit 1 ;;
esac
