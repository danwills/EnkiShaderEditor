#!/bin/sh
# Claude's status on the test phone while it drives it over adb, so Dan
# can see what's happening - and an interrupt: swiping the notification
# away asks Claude to wrap up the step it's on, put the phone back as it
# found it, and stop (see CLAUDE.md, "Testing on the phone").
#
# The notification is posted by the phone's shell (`cmd notification`),
# so no app is involved; at the end it stays, saying Claude's finished,
# until the next run replaces it. The running commentary shows over
# whatever is on screen in the Enki *debug* build's StatusOverlay (through
# TestStatusReceiver, src/debug; its overlay permission granted here with
# appops) - a panel of the last few lines, each fading after a while, so
# nothing queues up. Without the debug build it's quietly skipped.
#
#   status.sh post "Opening `Adjust Defines`..." [KIND]
#                     a line (and, the first time in a run, the notification);
#                     KIND picks its icon and colour: info step look tap type
#                     wait build install load think good bad warn happy sad
#                     party, or any emoji. `code`, numbers and good/bad words
#                     are coloured too.
#   status.sh ask "Test the new splats mode?" [SECONDS]
#                     a dialog on the phone asking whether Claude can use it
#                     (TakeOverActivity, over whatever's showing). After
#                     SECONDS (default 15) unanswered it counts down 15 s, with
#                     a warning icon, and goes ahead (as Dan wants: never
#                     stuck on a click).
#                     Exit 0 to go ahead (prints yes), 1 for Not now
#   status.sh check   exit 1 if the notification was swiped away
#   status.sh hide    clear the panel for a moment (a clean screenshot)
#   status.sh done "Back to Materialistic" [sad]
#                     the last line, big and happy (or sad), and the
#                     notification left saying Claude's finished
#
# ANDROID_SERIAL picks the phone, as for adb.

PKG=enk.danwills.android.enkishadereditor.debug
# This run's notification tag, and the last finished run's (left up).
STATE="${TMPDIR:-/tmp}/enki-phone-status-tag"
FINISHED="${TMPDIR:-/tmp}/enki-phone-status-finished"

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
	adb shell "am broadcast -n $PKG/enk.danwills.android.enkishadereditor.receiver.TestStatusReceiver $1" >/dev/null 2>&1
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
		adb shell "cmd notification post -S bigtext -t $(quote "🤖 Claude is using your phone") $(tag) $(quote "Testing - swipe this away to interrupt")" >/dev/null
		adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
	fi
	say "--es text $(quote "${2:-Testing...}") --es kind $(quote "${3:-step}")"
	;;
ask)
	id="ask$(date +%s)"
	wait="${3:-15}"
	adb shell "am start -n $PKG/enk.danwills.android.enkishadereditor.activity.TakeOverActivity --es id $id --es text $(quote "${2:-}") --ei wait $wait --ei countdown 15" >/dev/null 2>&1
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
	say "--ez hide true"
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
	# Updated in place - left up, so it's clear nothing's driving the phone.
	adb shell "cmd notification post -S bigtext -t $(quote "$title") $t $(quote "Nothing is driving your phone now. ${2:-}")" >/dev/null
	echo "$t" > "$FINISHED"
	rm -f "$STATE"
	say "--es text $(quote "${2:-Finished testing} - the phone's all yours!") --es kind $kind --ez done true"
	;;
*)
	echo "usage: $0 post TEXT [KIND] | ask TEXT [SECONDS] | check | hide | done [TEXT] [sad]" >&2
	exit 2
	;;
esac
