# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Start here

`AGENTS.md` in the repo root is a detailed architecture map of the Android app (app start-up flow,
editor stack, `ShaderRenderer`, persistence, uniforms/textures, preferences, sensors/camera/audio,
services, import/export, and a "common change scenarios" cookbook). Read it before making non-trivial
changes to `app/` — this file only summarizes build/test commands and adds what AGENTS.md doesn't cover
(the docs-site subproject, CI).

## What this repo contains

Two independent projects:
- `app/` — the Android app itself (Enki Shader Editor), package
  `enk.danwills.android.enkishadereditor`. Java 17, Gradle Kotlin DSL, no Jetpack Compose.
- `docs-site/` — a separate Astro static site (the project's public docs/landing page), deployed to
  GitHub Pages by `.github/workflows/deploy-docs.yml` on pushes touching `docs-site/**` or `FAQ.md`.
  Push-triggered runs weren't firing on this fork (only manual runs, until Dan enabled workflows
  in the Actions tab on 2026-10-21 - not yet confirmed by a docs push): after pushing master with
  docs changes, check `gh run list` and, if no run started, `gh workflow run deploy-docs.yml
  --ref master` (and tell Dan the push trigger still isn't working).

## Commands

### Android app (run from repo root)
- Two apps, as product flavours of one module: `enki` (Enki Shader Editor) and `ultrarestarea`
  (Ultra Rest Area, `src/ultrarestarea` - see `docs/ultra-rest-area.md`). Tasks and APK paths carry
  the flavour: `app/build/outputs/apk/<flavour>/<type>/app-<flavour>-<type>.apk`. A plain
  `assembleDebug`/`assembleRelease` builds both.
- `./gradlew assembleEnkiDebug` or `make debug` — build Enki's debug APK (`make ura-debug` for
  Ultra Rest Area's).
- `make install` / `make start` / `make uninstall` — adb install/launch/uninstall Enki's debug
  build (`make ura-install` / `make ura-start` for Ultra Rest Area's).
- `./gradlew assembleEnkiRelease` / `make release` — release build (minified, resource-shrunk;
  requires `ANDROID_KEY_*` signing env vars). `make bundle` builds the AAB instead.
- `./gradlew lintEnkiDebug` or `make lint` — Android lint (`lintUltrarestareaDebug` for the other
  app; both should stay at the 50-warning baseline).
- `make infer` — runs Facebook Infer static analysis over a clean assembleDebug.
- `make avocado` — optimizes vector drawables under `app/src/main/res` in place.
- `make meminfo` / `make glxinfo` — adb dumpsys helpers for memory/gfx debugging on a connected device.
- There are no automated unit/instrumentation tests in this project. Verify changes by building,
  installing, and manually exercising the app (see AGENTS.md §12 for what to check: compile errors,
  battery/render-mode switching, notification permission flow, etc.).

### docs-site (run from `docs-site/`)
- `npm install`
- `npm run dev` — local Astro dev server.
- `npm run build` — static build (what CI runs).
- `npm run preview` — preview the built output.

## Testing on the phone

When driving Dan's phone over adb (only when he's said it's free):
- Ask on the phone first: `tools/phone/status.sh ask 'what for, how long'` shows a dialog over
  whatever's on screen (debug build's TakeOverActivity). Unanswered for 15 s it counts down 15 s
  and goes ahead (Dan's wish: never stuck waiting on a click); Hang on a sec gives him a minute
  more a tap, and `ask` waits that long. Exit 0 means go ahead; 1 is Not now - say so and wait;
  2, no phone connected.
- Note what's on screen first (`adb shell dumpsys activity activities | grep -m1 topResumedActivity`).
- Keep Dan posted, in detail - he wants to hear about every step, as it happens, with a line
  before every action or batch so the panel is never empty while you're driving. The panel lives
  in Enki debug's process: after a force-stop, install or restart, run `status.sh again`
  straight away (add_shader.sh does when it stops the app):
  `tools/phone/status.sh post 'what I'"'"'m doing' [KIND]` puts a line in the debug build's status
  overlay (TestStatusReceiver/StatusOverlay: the run's last 8 lines over whatever's on screen,
  kept up the whole run - nothing fades), and the first post of a run also posts the
  notification. Before a pause to think (reading a screenshot or code, working something out),
  post a `think` line saying so - it stays up until the next. Give each line an estimate of how
  long its step will take as a third argument (`'~30 s'`, `'~2 min'`; `'?'` when you can't
  tell), shown dimmed at its end. KIND is
  its icon and colour - step, look, tap, type, wait, build, install, load, think, good, bad,
  warn, happy, sad, party, or any emoji: use good/bad for what worked or didn't. `code`,
  numbers and good/bad words are coloured too - so quote with single quotes, or the shell runs
  the backticks. For a screenshot use `status.sh shot file.png [why]`: it says it's hiding,
  hides the panel, takes the screenshot and comes back saying it's looking at it (`hide` alone
  just says so and hides; the next post brings it back, lines and all); `status.sh again`
  replays the run's last lines. Run `status.sh check` between steps: it waits while Dan has the
  panel's ⏸ on; if it fails, he swiped the notification away or tapped ⏹ to interrupt - finish
  the step in hand (don't leave a dialog half-done), note where the test got to so it can be
  picked up later, and stop. When unsure whether something looks right, ask him:
  `status.sh rate 'Does the preview look right?' [seconds]` shows 👍/👎 by the panel and prints
  up, down or none.
- To put a shader in Enki debug's library, use `tools/phone/add_shader.sh file.glsl [name]`, not the
  Paste dialog: it replaces a shader of that name in place, or adds one. With Enki debug in the
  background that needs no UI; with it on screen, the phone asks Replace/Ignore over whatever's
  showing (Replace is at (1082,1764)), and replacing the open shader reloads it.
- A shader pack file goes in with `tools/phone/add_pack.sh pack.sqlite` (as Install from File would; no UI).
- Either way, finish by putting the phone back: reopen the previous app
  (`adb shell monkey -p <package> -c android.intent.category.LAUNCHER 1`) or go home; turn
  stay-awake off; restore any setting changed (rotation). Then `tools/phone/status.sh done "..."`
  (`... sad` if it went badly): the panel goes at once, and the notification is left saying
  Claude's finished, with the summary and the log's last 8 lines to look back over.

## Conventions

- Indentation is tabs, not spaces; `.editorconfig` is authoritative if a contributor enables
  EditorConfig support in their IDE — otherwise follow Android Studio's default Java formatting.
- Java import order and most formatting choices are encoded in `.editorconfig`; don't fight it.
- Commit style: one feature/fix per commit, squash feature branches when merging to `master`
  (`git merge <branch> --squash`), write a proper commit message.
- String resources: when adding/changing a string, update it for all supported languages under
  `app/src/main/res/values-*`, not just the default `values`.
- Keep `docs/session-log.md` up to date continuously: after each request from Dan, append an
  entry under today's date - the gist of what he asked (quoted where short) and a line or two on
  what was done or answered - and commit it with the related work (or on its own). Don't let it
  lapse across context compaction; rebuild any missing entries from what's known.
- Release prep touches `CHANGELOG.md`, `fastlane/metadata/**/changelogs`, and the version in
  `app/build.gradle.kts` together — see AGENTS.md §13.
