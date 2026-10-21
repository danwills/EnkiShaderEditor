#!/bin/sh
# A status notification on the test phone while Claude drives it over
# adb, so Dan can see what's happening - and an interrupt: swiping the
# notification away asks Claude to wrap up the step it's on, put the
# phone back as it found it, and stop (see CLAUDE.md, "Testing on the
# phone"). Posted by the phone's shell (`cmd notification`), so no app
# is involved. Each post and done also shows as a toast, over whatever is
# on screen, through the Enki *debug* build's TestStatusToastReceiver
# (src/debug) - quietly skipped if that isn't installed.
#
#   status.sh post "Opening Adjust Defines..."   a toast (and, the first
#                                                time, the notification)
#   status.sh check                              exit 1 if it was swiped away
#   status.sh done "Back to Materialistic"       take it away; a long toast
#
# ANDROID_SERIAL picks the phone, as for adb.

# The shell can't cancel a notification, only snooze it - so `done`
# snoozes it for a year, and each test run gets a tag of its own (kept
# here between calls), so a later run's posts never land in a snoozed one.
STATE="${TMPDIR:-/tmp}/enki-phone-status-tag"

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

# extra: more `am broadcast` extras, e.g. "--ez long true".
toast() {
	adb shell "am broadcast -n enk.danwills.android.enkishadereditor.debug/enk.danwills.android.enkishadereditor.receiver.TestStatusToastReceiver --es text $(quote "$1") $2" >/dev/null 2>&1
}

# The notification's key, if it's still there.
key() {
	adb shell cmd notification list | tr -d '\r' | grep "|$(tag)|" | head -n 1
}

case "$1" in
post)
	# Posted once a run: every post makes the notification sound again,
	# so the updates after the first are toasts only.
	if [ ! -s "$STATE" ]; then
		adb shell "cmd notification post -S bigtext -t $(quote "Claude is using your phone") $(tag) $(quote "Testing - swipe this away to interrupt")" >/dev/null
	fi
	toast "Claude: ${2:-Testing...}"
	;;
check)
	[ -n "$(key)" ]
	;;
done)
	k=$(key)
	if [ -n "$k" ]; then
		adb shell "cmd notification snooze --for 31536000000 $(quote "$k")" >/dev/null
	fi
	rm -f "$STATE"
	toast "😊 Phone is yours - ${2:-finished testing.}" "--ez long true"
	;;
*)
	echo "usage: $0 post TEXT | check | done [TEXT]" >&2
	exit 2
	;;
esac
