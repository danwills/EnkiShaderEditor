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
# appops for the run, and given back at `done`) - a panel of the run's last 8 lines, there the whole run (Dan's
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
#                     to take ("~30 s"; "?" for no estimate), ends the line;
#                     when the next line comes, the last one gets how long
#                     it actually took ("→ took 12 s").
#   status.sh ask "Test the new splats mode?" [SECONDS]
#                     a dialog on the phone asking whether Claude can use it
#                     (TakeOverActivity, over whatever's showing). After
#                     SECONDS (default 15) unanswered it counts down 15 s, with
#                     a warning icon, and goes ahead (as Dan wants: never
#                     stuck on a click). Hang on a sec holds it a minute
#                     more each tap, and this waits that long too.
#                     Exit 0 to go ahead (prints yes), 1 for Not now
#   status.sh again   the run's last lines again - after anything that
#                     stopped the app (force-stop, install), which takes the
#                     panel with it
#   status.sh check   exit 1 if the notification was swiped away, or the
#                     panel's Stop tapped; while its Pause is on, waits
#                     (saying so) until Continue
#   status.sh wait SECONDS [TEXT [KIND]]
#                     use instead of sleep: TEXT posted first (if given),
#                     then waits, heeding the panel's buttons as it goes
#
# Every command (but ask and done) heeds the panel's buttons first, so
# they get through whatever Claude's doing:
#   - messages from its 💬 come out on stderr: "💬 Dan says: ..."
#   - Pause waits there (saying so) until Continue
#   - Stop exits 3 with a note on stderr - once: after that commands run
#     as normal, so Claude can narrate putting things back - but check
#     keeps failing
#   status.sh rate "Does the preview look right?" [SECONDS]
#                     the line, with thumbs up/down and Clarify
#                     beside the panel until one's tapped or SECONDS
#                     (default 30) pass; prints up, down, clarify: TEXT, or
#                     none. While Dan's typing a clarification it waits up
#                     to 5 minutes more
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

PKG=enk.danwills.android.enkishadereditor.debug
# This run's notification tag, and the last finished run's (left up).
STATE="${TMPDIR:-/tmp}/enki-phone-status-tag"
FINISHED="${TMPDIR:-/tmp}/enki-phone-status-finished"
# The last line posted, for `again`.
LAST="${TMPDIR:-/tmp}/enki-phone-status-last"
# The run's lines, KIND<tab>TEXT, for `again` and `done`.
LOG="${TMPDIR:-/tmp}/enki-phone-status-log"
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
	adb shell "am broadcast -n $PKG/enk.danwills.android.enkishadereditor.receiver.TestStatusReceiver $1" </dev/null >/dev/null 2>&1
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

# Seconds as the log shows them: 12 s, 3 min 5 s.
duration() {
	if [ "$1" -lt 60 ]; then
		echo "$1 s"
	else
		echo "$(($1 / 60)) min $(($1 % 60)) s"
	fi
}

# The log's last line, given how long it took (from when it was posted
# to now) - on the panel too - if it was estimated or took a while.
took() {
	[ -s "$LOG" ] || return 0
	last=$(tail -n 1 "$LOG")
	k=$(printf %s "$last" | cut -f1); text=$(printf %s "$last" | cut -f2)
	eta=$(printf %s "$last" | cut -f3); start=$(printf %s "$last" | cut -f4)
	done_=$(printf %s "$last" | cut -f5)
	[ -n "$start" ] && [ -z "$done_" ] || return 0
	secs=$(($(date +%s) - start))
	if [ "$eta" != "-" ] || [ "$secs" -ge 3 ]; then
		t=$(duration "$secs")
		say "--es took $(quote "$t")"
		sed -i '$d' "$LOG"
		printf '%s\t%s\t%s\t%s\t%s\n' "$k" "$text" "$eta" "$start" "$t" >> "$LOG"
	fi
}

# A line for the panel and the run's log: TEXT KIND [ESTIMATE]; the
# previous line first gets how long it took.
line() {
	took
	say "--es text $(quote "$1") --es kind $(quote "$2") --es eta $(quote "${3:-}")"
	printf '%s\n%s\n' "$1" "$2" > "$LAST"
	# "-" for no estimate: read with a tab IFS would run two tabs together.
	printf '%s\t%s\t%s\t%s' "$2" "$1" "${3:--}" "$(date +%s)" | tr -d '\n' >> "$LOG"
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

# The panel's buttons, from files in the app's storage (see the header).
# Messages from the panel's 💬, printed for Claude: moved aside before
# reading, so one sent meanwhile isn't lost.
messages() {
	msgs=$(adb shell "run-as $PKG sh -c 'mv files/claude-messages files/claude-messages.read 2>/dev/null && cat files/claude-messages.read && rm -f files/claude-messages.read'" </dev/null 2>/dev/null | tr -d '\r')
	if [ -n "$msgs" ]; then
		printf '%s\n' "$msgs" | while IFS= read -r m; do
			[ -n "$m" ] && echo "💬 Dan says: $m" >&2
		done
	fi
}

heed() {
	messages
	said=
	while :; do
		control=$(adb shell "run-as $PKG cat files/claude-control 2>/dev/null" </dev/null | tr -d '\r')
		case "$control" in
		stopped)
			# Told once; after that Claude's putting things back.
			adb shell "run-as $PKG sh -c 'echo stopping > files/claude-control'" </dev/null >/dev/null 2>&1
			echo "⏹ Dan tapped Stop: finish the step in hand, put the phone back, run done, and stop." >&2
			exit 3
			;;
		paused)
			if [ -z "$said" ]; then
				line "Paused - waiting for ▶" "⏸" "?"
				echo "⏸ Dan paused: waiting for Continue" >&2
				said=1
			fi
			sleep 2
			messages
			;;
		*) return 0 ;;
		esac
	done
}

case "$1" in
post)
	# Posted once a run (each post makes its sound again), after taking
	# down the last run's finished one.
	if [ ! -s "$STATE" ]; then
		[ -s "$FINISHED" ] && remove "$(cat "$FINISHED")" && rm -f "$FINISHED"
		: > "$LOG"
		adb shell "run-as $PKG rm -f files/claude-control" >/dev/null 2>&1
		adb shell "cmd notification post -S bigtext -t $(quote "🤖 Claude is using your phone") $(tag) $(quote "Testing - swipe this away to interrupt")" >/dev/null
		adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
	fi
	heed
	line "${2:-Testing...}" "${3:-step}" "${4:-}"
	;;
wait)
	[ -n "$2" ] || { echo "usage: $0 wait SECONDS [TEXT [KIND]]" >&2; exit 2; }
	heed
	[ -n "${3:-}" ] && line "$3" "${4:-wait}" "~$2 s"
	end=$(($(date +%s) + $2))
	while [ "$(date +%s)" -lt "$end" ]; do
		sleep 1
		heed
	done
	;;
again)
	# Only mid-run: the panel's back, with the pill and the run's last lines.
	if [ -s "$STATE" ] && [ -s "$LOG" ]; then
		adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
		tail -n "$TAIL" "$LOG" | while IFS="$(printf '\t')" read -r kind text eta start t; do
			[ "$eta" = - ] && eta=
			say "--es text $(quote "$text") --es kind $(quote "$kind") --es eta $(quote "$eta")"
			if [ -n "$t" ]; then
				say "--es took $(quote "$t")"
			fi
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
	start="am start -n $PKG/enk.danwills.android.enkishadereditor.activity.TakeOverActivity --es id $id --es text $(quote "${2:-}") --ei wait $wait --ei countdown 15"
	adb shell "$start" >/dev/null 2>&1
	waited=0
	held=
	back=
	limit=$((wait + 25))
	# The dialog answers itself after wait + 15 s; a little longer, in case
	# it never showed (the screen off, say) - and then go ahead anyway.
	# Hang on a sec writes hold:N:SECONDS (N holds so far) and gets out of
	# the way that long: bring it back then (it can't come forward from
	# the background itself), and wait for its 15 s countdown (and a little).
	while [ "$waited" -lt "$limit" ]; do
		if [ -n "$back" ] && [ "$waited" -ge "$back" ]; then
			back=
			adb shell "$start" >/dev/null 2>&1
			echo "hang on: asking again" >&2
		fi
		answer=$(adb shell "run-as $PKG cat files/takeover-$id 2>/dev/null" | tr -d '\r')
		case "$answer" in
		hold:*)
			# Left there (the answer replaces it) - each new hold once.
			if [ "$answer" != "$held" ]; then
				held="$answer"
				back=$((waited + ${answer##*:}))
				limit=$((back + 25))
				echo "hang on: asking again in ${answer##*:} s" >&2
			fi
			answer=
			;;
		esac
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
	[ -n "$(key "$(tag)")" ] || exit 1
	control=$(adb shell "run-as $PKG cat files/claude-control 2>/dev/null" | tr -d '\r')
	[ "$control" = stopping ] && exit 1
	heed || exit 1
	;;
rate)
	heed
	id="rate$(date +%s)"
	wait="${3:-30}"
	question="${2:-Does this look right?}"
	say "--es rate $id --es question $(quote "$question")"
	line "$question" "🤔" "~${wait} s"
	waited=0
	answer=none
	extended=
	while [ "$waited" -lt "$wait" ]; do
		got=$(adb shell "run-as $PKG cat files/claude-rate-$id 2>/dev/null" | tr -d '\r')
		case "$got" in
		'') ;;
		typing)
			# Dan's writing a clarification: give him time, once.
			if [ -z "$extended" ]; then
				extended=1
				wait=$((waited + 300))
			fi
			;;
		clarify:*)
			answer="clarify: ${got#clarify:}"
			adb shell "run-as $PKG rm -f files/claude-rate-$id"
			break
			;;
		*)
			answer="$got"
			adb shell "run-as $PKG rm -f files/claude-rate-$id"
			break
			;;
		esac
		[ $((waited % 3)) -eq 2 ] && heed
		sleep 1
		waited=$((waited + 1))
	done
	[ "$answer" = none ] && say "--ez rate_clear true"
	echo "$answer"
	;;
hide)
	heed
	line "Hiding the panel for a moment - taking a screenshot" "📸"
	sleep 0.5
	say "--ez hide true"
	;;
shot)
	[ -n "$2" ] || { echo "usage: $0 shot FILE [WHY]" >&2; exit 2; }
	heed
	line "Hiding the panel for a moment - taking a screenshot" "📸" "~2 s"
	sleep 0.5
	say "--ez hide true"
	sleep 0.4
	adb exec-out screencap -p > "$2"
	line "Back - looking at the screenshot${3:+ ($3)}" think "?"
	;;
done)
	took
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

$(tail -n "$TAIL" "$LOG" | while IFS="$(printf '\t')" read -r k text eta start t; do
			printf '%s %s%s\n' "$(icon "$k")" "$(printf %s "$text" | tr -d '`')" "${t:+ (took $t)}"
		done)"
	fi
	# Updated in place - left up, so it's clear nothing's driving the phone.
	adb shell "cmd notification post -S bigtext -t $(quote "$title") $t $(quote "$body")" >/dev/null
	echo "$t" > "$FINISHED"
	rm -f "$STATE" "$LOG" "$LAST"
	adb shell "run-as $PKG rm -f files/claude-control" >/dev/null 2>&1
	# And the panel gone at once, and "appear on top" back as it was (off,
	# unless it's been turned on in Settings) - so it's never left on, to
	# get in the way after the run.
	say "--ez done true"
	adb shell appops set $PKG SYSTEM_ALERT_WINDOW default >/dev/null 2>&1
	;;
*)
	echo "usage: $0 post TEXT [KIND] [ESTIMATE] | again | ask TEXT [SECONDS] | check | wait SECONDS [TEXT [KIND]] | rate TEXT [SECONDS] | hide | shot FILE [WHY] | done [TEXT] [sad]" >&2
	exit 2
	;;
esac
