# Phone kit: letting Claude drive an Android phone politely

A small kit, taken from Enki Shader Editor's own tooling, for when Claude
(or any agent) tests an Android app on a real phone over adb while its
owner might be holding it. It covers four things:

1. **Ask first.** A dialog on the phone, over whatever's showing:
   "Can Claude use your phone?", with Go ahead, Not now and Hang on a
   sec (out of the way for 30 s, then back to count down - for when
   it's nearly OK but the owner needs a moment to finish something; the
   notification it leaves has the same buttons, its Hang on 30 s more).
   - If nobody answers, it counts down ("Claude will go ahead in 15 s",
     with a yellow warning icon) and carries on. The owner's wish: never
     stuck waiting on a click.
   - If the dialog is pushed away (another app opened over it), a
     notification offers the same two answers, and tapping it brings the
     dialog back.
2. **Narrate.** A translucent panel just under the status bar, over
   every app, with the last 8 lines of what Claude is doing, up the whole
   run (nothing fades, so it's never empty), and touches go straight
   through it. Lines get an
   icon by kind (step, tap, build, good, bad...), and `code`, numbers and
   good/bad words are coloured.
3. **Let the owner interrupt.** A notification, "Claude is using your
   phone", is posted for the run. Swiping it away is the stop signal:
   `status.sh check` fails, and Claude finishes the step in hand and
   stops. The panel has buttons too, in a small corner window of their
   own (the only part that takes touches): 💬 sends Claude a message
   (typed, or dictated with the keyboard's mic), ⏸/▶ pauses it until
   Continue, ⏹ stops it like the swipe, ✕ closes the panel there and
   then (Claude's next line brings it back), and 👍/👎/Clarify appear
   while Claude asks whether something looks right (`status.sh rate`).
   Every status.sh command heeds the buttons, so they get through
   whatever Claude is doing. What's typed to Claude is never lost: it's
   kept as a draft until sent (Cancel, Back, leaving the box or the app
   being killed all keep it, and 💬 shows ✏️ while one's waiting), the
   panel steps out of the box's way while it's up, and a clarification
   sent after Claude stopped waiting arrives as a message. When the
   panel's had no new line for a few seconds, a dim last line ticks,
   saying whether Claude's thinking or waiting, and for how long.
4. **Hand back.** At the end Claude puts the phone back as it found it,
   the panel goes at once, and a "Claude is all finished" notification is
   left up with the summary and the log's last 8 lines, so it's clear
   nothing is driving the phone and the end of the run can be read back.

## What's here

- `status.sh`: the host side, run from the computer with adb. It needs
  `PHONE_KIT_APP` set to your debug build's applicationId, plus
  `PHONE_KIT_CLASSES` if you moved the Java package.
  - `status.sh ask "what for, how long" [SECONDS]` shows the prompt.
    It exits 0 to go ahead (by answer or timeout), 1 for Not now and 2
    when no phone is connected.
    `SECONDS` (default 15) is how long the question waits before the
    15 s countdown.
  - `status.sh post 'text' [KIND] [ESTIMATE]` adds a line to the panel,
    and the run notification the first time. ESTIMATE (`'~30 s'`, or `'?'`
    for no estimate) shows dimmed at the line's end; when the next line
    comes, it gets how long the step actually took ("→ took 12 s"), in
    the finished notification's tail too.
  - `status.sh again` replays the run's last lines. The panel lives in
    the app's process, so a force-stop or install takes it away; call this
    straight after one.
  - `status.sh check` fails if the run notification was swiped away or
    ⏹ tapped; while ⏸ is on it waits, saying so, until ▶.
  - Every command but `ask` and `done` heeds the panel's buttons first:
    💬 messages print on stderr (`💬 Dan says: ...`), ⏸ waits until ▶,
    and ⏹ exits 3 with a note, once (then commands work again, for
    narrating the wrap-up, but `check` keeps failing).
  - `status.sh wait SECONDS ['why' [KIND]]` waits like `sleep`, but
    heeds the buttons while it does. Use it instead of `sleep`.
  - `status.sh rate 'Does it look right?' [SECONDS]` shows 👍/👎 and
    Clarify until one's tapped (or SECONDS, default 30) and prints up,
    down, none, or `clarify: <text>`. Clarify opens a text box
    (SayActivity), and the wait stretches by 5 minutes while it's open.
  - `status.sh shot file.png [why]` says it's hiding, hides the panel,
    takes a screenshot and comes back saying it's looking at it.
  - `status.sh hide` says so and takes the panel out of sight; the next
    post brings it back, lines and all.
  - `status.sh done 'summary' [sad]` takes the panel away at once and
    leaves the finished notification up, with the summary and the log's
    last lines.
- `android/`: the phone side, for your app's **debug** build only:
  - `java/com/example/phonekit/`:
    - `TestStatusReceiver` and `StatusOverlay`: the panel.
    - `TakeOverActivity`: the prompt, its countdown and its notification.
  - `AndroidManifest-debug.xml`: what to merge into `src/debug/AndroidManifest.xml`.
  - `res/values/phone_kit_styles.xml`: the see-through theme the prompt
    needs.

The run notifications are posted by adb's shell (`cmd notification
post`), so they don't need the app. The panel and the prompt live in the
debug build, so the release build never gets exported components or the
overlay permission.

## Setting it up

`install.sh APP_MODULE_DIR DEBUG_APPLICATION_ID [JAVA_PACKAGE]` does steps
1, 2 and 4 below (the host script as `tools/phone/status.sh`, a wrapper
around this kit's). By hand:

1. Copy `android/java/com/example/phonekit/` into `app/src/debug/java/`.
   Rename the package if you like, and set `PHONE_KIT_CLASSES` to match.
2. Merge `AndroidManifest-debug.xml` into `app/src/debug/AndroidManifest.xml`.
3. Copy `phone_kit_styles.xml` into `app/src/debug/res/values/`. The
   prompt is a platform `AlertDialog` in the device's own style, so the
   only dependency is AndroidX Core (`NotificationCompat`): it works in
   Compose-only apps that have no AppCompat or Material Components. The
   Java sticks to Java 11 (no switch expressions or `instanceof`
   patterns), which some projects still compile with.
4. Put `status.sh` somewhere in the repo and export
   `PHONE_KIT_APP=com.your.app.debug`.
5. Add the instructions below to the project's `CLAUDE.md` (or AGENTS.md).

`status.sh post` grants the overlay permission itself (`appops set ...
SYSTEM_ALERT_WINDOW allow`), and `status.sh done` gives it back (`default`),
so it's only on during a run. Without it, lines fall back to plain toasts.
The prompt's fallback notification needs POST_NOTIFICATIONS on Android
13+; grant it once on the phone, or with `adb shell pm grant <app>
android.permission.POST_NOTIFICATIONS`.

## For CLAUDE.md

```markdown
## Testing on the phone

When driving the phone over adb:
- Ask on the phone first: `status.sh ask 'what for, how long'` shows a
  dialog over whatever's on screen; unanswered for 15 s it counts down 15 s
  and goes ahead. Exit 0 means go ahead; 1 is Not now - say so and wait;
  2, no phone connected.
- Note what's on screen first
  (`adb shell dumpsys activity activities | grep -m1 topResumedActivity`).
- Narrate every step - a line before each action or batch, so the panel
  is never empty while you're driving - with `status.sh post 'what I'"'"'m doing' KIND`
  (step, look, tap, type, wait, build, install, load, think, good, bad,
  warn, happy, sad, party): good/bad for what worked or didn't. Before a
  pause to think, post a `think` line saying so. Give each line an estimate
  as a third argument (`'~30 s'`, `'?'` when unsure). Quote with single
  quotes, or the shell runs the backticks.
- Screenshots with `status.sh shot file.png [why]`, which hides the panel
  around it and says so.
- After anything that stops the app (force-stop, install, a restart),
  run `status.sh again` straight away: the panel went with the process,
  and this replays its lines.
- The owner can message you (💬), pause or stop from the panel, and
  every status.sh command heeds that: read each command's stderr for
  `💬 Dan says: ...` and act on it. A command exiting 3 means ⏹: finish
  the step in hand (don't leave a dialog half-done), note where you got
  to, put the phone back, and stop. Use `status.sh wait SECONDS 'why'`
  instead of `sleep`. `status.sh check` between steps also fails if the
  notification was swiped away.
- `status.sh rate` can answer `clarify: <text>`: that's the real
  answer, so act on what it says.
- Finish by putting the phone back: reopen the app that was showing
  (`adb shell monkey -p <package> -c android.intent.category.LAUNCHER 1`)
  or go home, turn stay-awake off, restore any setting changed. Then
  `status.sh done "..."` (`... sad` if it went badly): the panel goes,
  and the notification keeps the log's last lines.
```

## Lessons learned

- **Don't leave an overlay up.** Some Settings screens (Developer options,
  Wireless debugging) ignore taps while another app's overlay covers them,
  so a panel left up after a run that never finished (the phone dropped
  off wifi) kept the user out of the very screen needed to reconnect. The
  panel now goes by itself after 2 quiet minutes (not while the user is
  answering or typing), has ✕ to close it at once, and `done` turns
  "appear on top" back off.
- **Touches through an overlay.** Since Android 12 a non-touchable overlay
  from another app only lets touches through at an opacity of 0.8 or
  less, so the panel's window alpha is 0.8.
- **The shell can't cancel its own notifications**, only snooze them;
  `status.sh` snoozes the last run's for a year.
- **Answers come back through the app's files.** The prompt writes
  `files/takeover-<id>`, and `status.sh` reads it with `run-as`, which
  works for debuggable builds.
- **A tap outside a dialog is easy to make by accident.** The prompt
  ignores it (`setCanceledOnTouchOutside(false)`); only the buttons and
  Back answer.
- **adb input can be too quick for apps.** Two `input tap`s in one
  command can be too short and too close for an app's own double-tap
  detection. `input swipe x y x y 120` holds a tap for 120 ms.
- **`adb shell` reads stdin.** In a `while read` loop it eats the rest of
  the input; give it `</dev/null`.
- **Keep the screen on** while testing (`adb shell svc power stayon true`),
  and turn it off again at the end.
- **Look before acting.** Screenshots (`adb exec-out screencap -p`) are
  the agent's eyes, and the panel and prompt show up in them, so clear
  them first when the result matters.
