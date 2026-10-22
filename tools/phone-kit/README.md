# Phone kit: letting Claude drive an Android phone politely

A small kit, taken from Enki Shader Editor's own tooling, for when Claude
(or any agent) tests an Android app on a real phone over adb while its
owner might be holding it. It covers four things:

1. **Ask first.** A dialog on the phone, over whatever's showing:
   "Can Claude use your phone?", with Go ahead and Not now.
   - If nobody answers, it counts down ("Claude will go ahead in 15 s",
     with a yellow warning icon) and carries on. The owner's wish: never
     stuck waiting on a click.
   - If the dialog is pushed away (another app opened over it), a
     notification offers the same two answers, and tapping it brings the
     dialog back.
2. **Narrate.** A translucent panel just under the status bar, over
   every app, with the last few lines of what Claude is doing. Each line
   fades after a while, and touches go straight through it. Lines get an
   icon by kind (step, tap, build, good, bad...), and `code`, numbers and
   good/bad words are coloured.
3. **Let the owner interrupt.** A notification, "Claude is using your
   phone", is posted for the run. Swiping it away is the stop signal:
   `status.sh check` fails, and Claude finishes the step in hand and
   stops.
4. **Hand back.** At the end Claude puts the phone back as it found it
   and leaves a "Claude is all finished" notification up, so it's clear
   nothing is driving the phone.

## What's here

- `status.sh`: the host side, run from the computer with adb. It needs
  `PHONE_KIT_APP` set to your debug build's applicationId, plus
  `PHONE_KIT_CLASSES` if you moved the Java package.
  - `status.sh ask "what for, how long" [SECONDS]` shows the prompt.
    It exits 0 to go ahead (by answer or timeout) and 1 for Not now.
    `SECONDS` (default 15) is how long the question waits before the
    15 s countdown.
  - `status.sh post 'text' [KIND]` adds a line to the panel, and the run
    notification the first time.
  - `status.sh again` re-posts the last line. The panel lives in the app's
    process, so a force-stop or install takes it away; call this straight
    after one.
  - `status.sh check` fails if the run notification was swiped away.
  - `status.sh hide` clears the panel for a clean screenshot.
  - `status.sh done 'summary' [sad]` writes the last line and leaves the
    finished notification up.
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

1. Copy `android/java/com/example/phonekit/` into `app/src/debug/java/`.
   Rename the package if you like, and set `PHONE_KIT_CLASSES` to match.
2. Merge `AndroidManifest-debug.xml` into `app/src/debug/AndroidManifest.xml`.
3. Copy `phone_kit_styles.xml` into `app/src/debug/res/values/`. Its
   parent theme must be a Material Components or Material3 one, because
   the prompt uses `MaterialAlertDialogBuilder`. Its dependencies are
   AndroidX AppCompat and Core, and Material Components.
4. Put `status.sh` somewhere in the repo and export
   `PHONE_KIT_APP=com.your.app.debug`.
5. Add the instructions below to the project's `CLAUDE.md` (or AGENTS.md).

`status.sh post` grants the overlay permission itself (`appops set ...
SYSTEM_ALERT_WINDOW allow`). Without it, lines fall back to plain toasts.
The prompt's fallback notification needs POST_NOTIFICATIONS on Android
13+; grant it once on the phone, or with `adb shell pm grant <app>
android.permission.POST_NOTIFICATIONS`.

## For CLAUDE.md

```markdown
## Testing on the phone

When driving the phone over adb:
- Ask on the phone first: `status.sh ask 'what for, how long'` shows a
  dialog over whatever's on screen; unanswered for 15 s it counts down 15 s
  and goes ahead. Exit 0 means go ahead; 1 is Not now - say so and wait.
- Note what's on screen first
  (`adb shell dumpsys activity activities | grep -m1 topResumedActivity`).
- Narrate every step - a line before each action or batch, so the panel
  is never empty while you're driving - with `status.sh post 'what I'"'"'m doing' KIND`
  (step, look, tap, type, wait, build, install, load, think, good, bad,
  warn, happy, sad, party): good/bad for what worked or didn't. Quote with
  single quotes, or the shell runs the backticks.
- `status.sh hide` before a screenshot that needs the screen clear.
- After anything that stops the app (force-stop, install, a restart),
  run `status.sh again` straight away: the panel went with the process.
- Run `status.sh check` between steps: if it fails, the owner swiped the
  notification away to interrupt - finish the step in hand (don't leave a
  dialog half-done), note where you got to, and stop.
- Finish by putting the phone back: reopen the app that was showing
  (`adb shell monkey -p <package> -c android.intent.category.LAUNCHER 1`)
  or go home, turn stay-awake off, restore any setting changed. Then
  `status.sh done "..."` (`... sad` if it went badly).
```

## Lessons learned

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
- **Keep the screen on** while testing (`adb shell svc power stayon true`),
  and turn it off again at the end.
- **Look before acting.** Screenshots (`adb exec-out screencap -p`) are
  the agent's eyes, and the panel and prompt show up in them, so clear
  them first when the result matters.
