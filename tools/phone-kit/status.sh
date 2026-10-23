#!/bin/sh
# Claude's status on the test phone while it drives it over adb, so Dan
# can see what's happening - and an interrupt: swiping the notification
# away asks Claude to wrap up the step it's on, put the phone back as it
# found it, and stop (see README.md).
#
# The notification is posted by the phone's shell (`cmd notification`),
# so no app is involved; at the end it stays, saying Claude's finished,
# until the next run replaces it. The running commentary shows over
# whatever is on screen in your app's *debug* build's StatusOverlay (through
# TestStatusReceiver; its overlay permission granted here with
# appops) - a panel of the run's last 8 lines, there the whole run (Dan's
# wish: never an empty panel while Claude's driving), gone at once when
# it's done, when the notification gets the log's tail instead. Without
# the debug build it's quietly skipped.
#
#   status.sh post "Opening `Adjust Defines`..." [KIND] [ESTIMATE]
#                     a line (and, the first time in a run, the notification);
#                     KIND picks its icon and colour: info step look tap type
#                     wait build install load think good bad warn happy sad
#                     party, or any emoji. `code`, numbers and good/bad words
#                     are coloured too. ESTIMATE, how long the step's likely
#                     to take ("~30 s"; "?" for no estimate), ends the line.
#   status.sh ask "Test the new splats mode?" [SECONDS]
#                     a dialog on the phone asking whether Claude can use it
#                     (TakeOverActivity, over whatever's showing). After
#                     SECONDS (default 15) unanswered it counts down 15 s, with
#                     a warning icon, and goes ahead (as Dan wants: never
#                     stuck on a click).
#                     Exit 0 to go ahead (prints yes), 1 for Not now
#   status.sh again   the run's last lines again - after anything that
#                     stopped the app (force-stop, install), which takes the
#                     panel with it
#   status.sh check   exit 1 if the notification was swiped away
#   status.sh hide    the panel out of sight (a clean screenshot), saying so
#                     first; the next post brings it back, lines and all
#   status.sh shot FILE [WHY]
#                     says it's hiding, hides, screenshots the phone to FILE
#                     (PNG), and is back saying it's looking at it (at WHY)
#   status.sh done "Back to Materialistic" [sad]
#                     the panel gone at once, and the notification left
#                     saying Claude's finished (or stopped), with the summary
#                     and the log's last lines
#
# ANDROID_SERIAL picks the phone, as for adb.

# Your debug build's applicationId, and the Java package the kit's classes are in.
PKG="${PHONE_KIT_APP:?set PHONE_KIT_APP to your debug applicationId}"
CLS="${PHONE_KIT_CLASSES:-com.example.phonekit}"
# This run's notification tag, and the last finished run's (left up).
STATE="${TMPDIR:-/tmp}/phone-kit-status-tag"
FINISHED="${TMPDIR:-/tmp}/phone-kit-status-finished"
# The last line posted, for `again`.
LAST="${TMPDIR:-/tmp}/phone-kit-status-last"
# The run's lines, KIND<tab>TEXT, for `again` and `done`.
LOG="${TMPDIR:-/tmp}/phone-kit-status-log"
# How many of them the panel shows (StatusOverlay.MAX_LINES), and the
# notification at the end.
TAIL=8

tag() {
	if [ ! -s "$STATE" ]; then
		echo "claude_status_$(date +%s)" > "$STATE"
	fi
	cat "$STATE"
}

# The phone's shell gets one string: no straight single quotes inside.
quote() {
	printf "'%s'" "$(printf %s "$1" | sed "s/'/’/g")"
}

# extras: more `am broadcast` extras.
say() {
	# No stdin: in `again`'s loop it would eat the rest of the log.
	adb shell "am broadcast -n $PKG/$CLS.TestStatusReceiver $1" </dev/null >/dev/null 2>&1
}

# A kind's icon, as StatusOverlay.icon() has it, for the notification.
icon() {
	case "$1" in
	info) echo 💬 ;; step|'') echo 🔧 ;; look) echo 👀 ;; tap) echo 👆 ;;
	type) echo ⌨️ ;; wait) echo ⏳ ;; build) echo 🛠️ ;; install) echo 📲 ;;
	load) echo 📥 ;; think) echo 🤔 ;; good) echo ✅ ;; bad) echo ❌ ;;
	warn) echo ⚠️ ;; happy) echo 😊 ;; sad) echo 😢 ;; party) echo 🎉 ;;
	*) echo "$1" ;;
	esac
}

# A line for the panel and the run's log: TEXT KIND [ESTIMATE].
line() {
	say "--es text $(quote "$1") --es kind $(quote "$2") --es eta $(quote "${3:-}")"
	printf '%s\n%s\n' "$1" "$2" > "$LAST"
	printf '%s\t%s\t%s' "$2" "$1" "${3:-}" | tr -d '\n' >> "$LOG"
	echo >> "$LOG"
}

# A notification's key, by tag, if it's still there.
key() {
	adb shell cmd notification list | tr -d '\r' | grep "|$1|" | head -n 1
}

# The shell can't cancel a notification, only snooze it - for a year.
remove() {
	k=$(key "$1")
	if [ -n "$k" ]; then
		adb shell "cmd notification snooze --for 31536000000 $(quote "$k")" >/dev/null
	fi
}

case "$1" in
post)
	# Posted once a run (each post makes its sound again), after taking
	# down the last run's finished one.
	if [ ! -s "$STATE" ]; then
		[ -s "$FINISHED" ] && remove "$(cat "$FINISHED")" && rm -f "$FINISHED"
		: > "$LOG"
		adb shell "cmd notification post -S bigtext -t $(quote "🤖 Claude is using your phone") $(tag) $(quote "Testing - swipe this away to interrupt")" >/dev/null
		adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
	fi
	line "${2:-Testing...}" "${3:-step}" "${4:-}"
	;;
again)
	# Only mid-run: the panel's back, with the pill and the run's last lines.
	if [ -s "$STATE" ] && [ -s "$LOG" ]; then
		adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
		tail -n "$TAIL" "$LOG" | while IFS="$(printf '\t')" read -r kind text eta; do
			say "--es text $(quote "$text") --es kind $(quote "$kind") --es eta $(quote "$eta")"
		done
	fi
	;;
ask)
	if [ "$(adb get-state 2>/dev/null)" != device ]; then
		echo "no phone connected" >&2
		exit 2
	fi
	id="ask$(date +%s)"
	wait="${3:-15}"
	adb shell "am start -n $PKG/$CLS.TakeOverActivity --es id $id --es text $(quote "${2:-}") --ei wait $wait --ei countdown 15" >/dev/null 2>&1
	waited=0
	# The dialog answers itself after wait + 15 s; a little longer, in case
	# it never showed (the screen off, say) - and then go ahead anyway.
	while [ "$waited" -lt $((wait + 25)) ]; do
		answer=$(adb shell "run-as $PKG cat files/takeover-$id 2>/dev/null" | tr -d '\r')
		if [ -n "$answer" ]; then
			adb shell "run-as $PKG rm -f files/takeover-$id"
			echo "$answer"
			[ "$answer" = yes ]
			exit
		fi
		sleep 1
		waited=$((waited + 1))
	done
	echo "yes (no answer)"
	exit 0
	;;
check)
	[ -n "$(key "$(tag)")" ]
	;;
hide)
	line "Hiding the panel for a moment - taking a screenshot" "📸"
	sleep 0.5
	say "--ez hide true"
	;;
shot)
	[ -n "$2" ] || { echo "usage: $0 shot FILE [WHY]" >&2; exit 2; }
	line "Hiding the panel for a moment - taking a screenshot" "📸" "~2 s"
	sleep 0.5
	say "--ez hide true"
	sleep 0.4
	adb exec-out screencap -p > "$2"
	line "Back - looking at the screenshot${3:+ ($3)}" think "?"
	;;
done)
	t=$(tag)
	if [ "$3" = sad ]; then
		title="😢 Claude has stopped"
		kind=sad
	else
		title="😊 Claude is all finished"
		kind=happy
	fi
	# The summary, then the run's last lines, to look back over.
	body="$(icon "$kind") ${2:-Finished testing} - nothing is driving your phone now."
	if [ -s "$LOG" ]; then
		body="$body

$(tail -n "$TAIL" "$LOG" | while IFS="$(printf '\t')" read -r k text eta; do
			printf '%s %s\n' "$(icon "$k")" "$(printf %s "$text" | tr -d '`')"
		done)"
	fi
	# Updated in place - left up, so it's clear nothing's driving the phone.
	adb shell "cmd notification post -S bigtext -t $(quote "$title") $t $(quote "$body")" >/dev/null
	echo "$t" > "$FINISHED"
	rm -f "$STATE" "$LOG" "$LAST"
	# And the panel gone at once.
	say "--ez done true"
	;;
*)
	echo "usage: $0 post TEXT [KIND] [ESTIMATE] | again | ask TEXT [SECONDS] | check | hide | shot FILE [WHY] | done [TEXT] [sad]" >&2
	exit 2
	;;
esac
