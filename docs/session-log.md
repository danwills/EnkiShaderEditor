# Session log

A running log of what Dan asked for and what Claude did or answered, newest at the bottom.
Earlier sessions are summarised briefly; from 2026-10-21 each request gets its own entry.

## Before 2026-10-20 (summary)

- **Image and video output formats.** Asked whether captures could use other formats without
  new dependencies. Added an Image Format setting (PNG, JPEG, WebP, lossless WebP, with a quality
  slider) and a Video Format setting (H.264, H.265, VP9, VP8, and GIF with its own encoder), listing
  only the formats the phone can encode.
- **Restart Shader** checkbox on the Custom Res/Seq dialog, so a render can start from frame 0.
- **Docs editing guide** (`docs-site/EDITING.md`) so Dan can edit the site himself.
- **Pack updates.** Re-installing a newer pack now replaces its unedited shaders in place; the
  sidebar shows same-named shaders side by side. Regenerated the Neural CA lizard pack.
- **Neural CA gestures.** Asked to make drags and twists cumulative; they already were.

## 2026-10-20

- **"Real-time adjustment of defines - would uniforms be the only way, or can main() take
  arguments?"** main() can't take arguments; uniforms are the way. Proposed turning chosen
  `#define`s into uniforms temporarily, baking them back when done.
- **Live defines.** Built it: Adjust Defines → Edit Live → tick defines → Go Live. Fullscreen
  sliders (colours via a pop-up hue/saturation disc that follows the finger), ranges that stretch
  past their ends, finer control by moving the finger up, double-tap to reset. While paused, a
  change redraws the last frame from the same inputs instead of stepping on. Done/Discard swap
  the shader without restarting it. Defines that can't be uniforms (used by `#if`, etc.) go red.
- **Comment wrapping** in Adjust Defines.
- **"Could a shader draw (and later edit) its own source, demoscene-writer style?"** Yes:
  - `sourceText` / `sourceTextColor` (the source and its syntax colours as textures);
  - a built-in IBM Plex Mono signed-distance-field `font`;
  - the CRT Self-Portrait sample;
  - `keyboard` / `typedChar` keyboard input, and Type into Shader for the phone's keyboard;
  - self-editing shaders: a `///text:Image` buffer holds an editable copy of the source, and Run
    Typed Text swaps it in without a restart.
  - Saved for later: one shader editing *another*.

## 2026-10-21

- **"It rendered black after I broke it - only run typed text when I press a toolbar button,
  check it compiles, and toast if not."** Run Typed Text is now only a toolbar button that
  appears for self-editing shaders; a compile failure shows a warning bar and the shader carries
  on. Also arrow-key navigation, Shift selection, PageUp/PageDown, one-finger drag, and
  two-finger pinch/pan/rotate in the Notepad sample.
- **Tap to place the cursor.** Added.
- **Longer error message with a coloured icon.** Six-second bar with an orange warning icon.
- **"Only the changed bit was highlighted in the editor after Run."** Fixed: replacing the whole
  text now re-highlights everything.
- **Long-press selects a word; Shift+long-press extends.** Added.
- **Floating Run button in fullscreen**, draggable. Added.
- **"Releasing Shift clears the selection."** Fixed. Also fixed slow view drift from
  whole-pixel touch positions and quick taps being missed on slow shaders.
- **"Some grey widget has appeared."** Samsung's keyboard toolbar: a hidden input view was
  taking focus. Fixed.
- **The audio/demoscene wishlist** (sound output tab, soundboard, tracker sequencer, tracker
  module playback, presentation packs, show timeline). Recommended IBXM (BSD-3, pure Java,
  MOD/S3M/XM) over libopenmpt (needs the NDK); Dan agreed, plus a database upgrade (it became v14) and pushing the branch.
  Plan in branch `audio-and-shows`; the sound pass is built first with the phone at hand.
- **"Prepare to migrate my release install's database in the morning."** Will do (checklist in
  `NOTES-audio-and-shows.md`).
- **"Save a running log of what I've asked and your answers."** This file.
- **Sound pass built and heard on the phone ("it's working!").** A buffer with `mainSound()`
  plays through the speaker, ~0.1 s ahead, no underruns. The first try was silent: Android's
  AudioTrack waits for its buffer to fill before starting, and it was sized bigger than what was
  queued.
- **"I heard one kick, then it stopped when I turned on Bluetooth."** The GL thread was stuck
  inside an AudioTrack call when the output device changed. All audio work moved to a thread of
  its own that remakes the track if it dies. Reading the sound back also stalled the GPU (71 fps
  down to 17), so it's now read asynchronously a frame later.
- **"The instrument labels are a bit small" / "the drums are good though."** Labels enlarged.
- **"Punchier snare, audible hats, a clap that sounds like a clap, smooth bends, a smaller dead
  zone - I'll see how you went in the morning."** All done, unattended (untested on the phone):
  bends now run oscillators on accumulated "bent time" so the pitch glides without phase jumps.

Then, unattended overnight on `audio-and-shows` (all untested on a device - see
`NOTES-audio-and-shows.md` for what to try):
- **Save Buffer as Texture** (More Options): keeps a buffer's contents as a saved texture, so a
  shader can start from what it made.
- **Tracker sample:** a demoscene-style step sequencer - tap to place a note, long-press to edit,
  double-tap for a red delete button, play/pause; the song keeps via Save Buffer as Texture.
- **Tracker module playback:** IBXM plays MOD/S3M/XM for a shader with a `///mod:name` line;
  More Options → Import Tracker Module; `moduleInfo` / `moduleScope` / `moduleTime` /
  `moduleRow` uniforms match what's heard. Modules live in the database (v14) and travel in
  backups and shader packs.
- **Module Viewer sample** with a demo song of our own (a one-minute MOD written by a script, so
  no licence questions); credit for IBXM in the README, FAQ and a "Built with" row in the docs
  site's footer.
- **Presentation packs:** "Present as a show" in Build Shader Pack; the pack plays full screen in
  a loop (30 s each) under one soundtrack that doesn't restart between shaders. Tap its row in the
  sidebar tree, or Play Show in Get Shader Pack.
- **Show timeline**, blocked out: JSON scenes with #define keyframes eased between, no editor yet.

### 2026-10-21, from the morning (rebuilt from notes after the context was compacted)

- **"Let's do the release DB migration now, then test your features."** Release install moved
  to DB v14 after Dan exported a backup; all 953 shaders came through.
- **"How do I get into the tracker?" / "I don't see a Tracker sample."** Dan was on the old
  debug build; installed a fresh one. Tracker and Module Viewer then worked, and an imported `.mod`
  played.
- **"The picker doesn't list all tracker formats - OpenMPT? What do I install on Gentoo for native
  libs?"** Stay on IBXM (MOD/S3M/XM) for now; for native builds, the SDK's sdkmanager installs
  `ndk;27.2.12479018` and `cmake;3.22.1`.
- **"Brief how-to for presentation packs."** Given. **"No sound, and it doesn't advance - can you
  test it yourself?"** The pack had been saved without the show settings; playback itself was
  fine, and Dan's retest worked.
- **"Make the pack builder remember its settings and bump the version."** Done.
- **"Should audioSpectrum hear the module/Sound pass too?"** Yes: a "Use the Shader's Own Sound"
  setting (on by default) feeds audioSpectrum from the app's own sound, no microphone needed.
- **Smoother shader changes in shows, a show resolution, choosing a module file in the builder,
  Edit Pack and Update Version (newest first, buttons at the top) on a long-pressed pack.** All
  built; changes now precompile ahead and dip through black (~250 ms).
- **"Keep the Packs tick from changing text colour; auto-tick the open shader in the builder;
  similarity filtering there; Save as New should offer a postfix for a named shader."** Done.
- **"The sidebar's icon buttons went grey and moved."** Couldn't reproduce; Dan will report if it
  recurs.
- **"A button to save and install the new pack version."** Save & Install in the builder.
- **"Show the tracker module's name (or 'Shader audio') while a pack plays."** Done.
- **"A buffer/uniform type for text, with a demoscene-writer sample - and tree view on by
  default."** Shader texts: named text blocks per shader (DB v15), each its own editor tab, read
  as a texture by name, updated live; carried in copy/share/packs as `//// Text` sections. Demo
  Writer sample: a sine-twisted bar scrolling rainbow greets. Tree view is now the default.
- **"Demo Writer looks sweet!"** Release install moved to DB v15 (backup checked first: 960
  shaders, 2 packs, 34 textures, 5 modules).
- **"A tickbox to sort the tree newest first, and better Find matches first."** Newest first
  tickbox (remembered); Find Shader and the sidebar filter rank whole-name > whole-word > prefix >
  substring > fuzzy, shorter names first.
- **"Paste as New: buttons at the top, and an Add as Child button."** Done.
- **"Demo Writer does something odd on rotation."** Two bugs: after a rotation, run-on-change ran
  the editor's text before the shader's session was restored (losing texts and buffers); and a
  fresh renderer carried a never-started clock, so `time` was the phone's uptime and everything
  stepped. Both fixed and checked on the phone.
- **"Migrate the release app with these fixes."** Done.
- **"Does the resolution setting change Sound pass quality?"** No - always 44.1 kHz stereo; only
  indirectly, if a slow frame starves the sound.
- **"Could my huge library of Ready (GollyGang/ready) VTI files become multi-buffer shaders?"**
  Yes for 2D formula rules. Added 32-bit float buffers (`///float32`) and
  `tools/ready/vti2enki.py`, following Ready's own code (stencils, initial pattern overlays,
  colour maps). The history-wave pattern ran on the phone - "very close" to Ready.
- **Painting** with a finger; **"brush 5x bigger"**; **two fingers erase**; **"debounce it, and a
  three-finger switch to black-and-white masks of every reagent"** (a state row in the buffers
  holds the gesture and view); **"start in colour, and expose every parameter"** (Adjust Defines
  now lists every pass's defines, marked with their tab, and applies each to every pass);
  **parameters moved to the top of each buffer**; **"paint with the initial pattern's noise, a
  firmer eraser"** (saved as ReadyHistoryWaveNoisePaint).
- **"Several steps per frame, for these shaders and for video?"** `///steps:N` runs the buffers N
  times per drawn frame (progressive mode and capture too) - 4x the simulation speed at the same
  frame rate. Dan found 33 steps at 1/8 resolution a sweet spot.
- **"The live sliders' range is far too big."** Ranges now sized to the value, a touch no longer
  jumps the value, and a 1/64 fine step. **"Add a Reset button to the live panel."** Done.
- **"Migrate the release app with these too."** Done (no database change this time).
- **"Can people still import regular Shader Editor databases?"** By the code, yes; test it when
  Play Store testing starts, then say so on the landing page (noted in memory).
- **"Stop the little circle under the cursor in fields."** Android's insertion handle, now hidden
  everywhere except the code editor.
- **"Exit the app after testing on my phone."** Will put the phone back as it was found.
- **"Are you still keeping this log?"** It had lapsed since the overnight work; rebuilt the
  entries above, and CLAUDE.md now asks for it to be kept up continuously.
- **"That grey circle still happens - test it, and leave me back on HN after."** Caught on a
  screen recording: a grey "≡" circle at the left edge while a text field has focus. Not the
  app's - it belongs to the keyboard's window: Gboard shows it when a physical keyboard is
  connected (Dan's K68 Bluetooth keyboard was) in place of its on-screen keyboard. The app can't
  hide another app's window; it's a Gboard/Android setting. (The earlier cursor-handle change
  was aimed at the wrong circle.) Phone returned to Materialistic.
- **"Grey circle gone (an Android setting) - and I love being dropped back where I was. Could
  you show running toasts while testing, with an interrupt button that lets you wrap up
  gracefully, then confirm with a smiley?"** Toasts can't hold buttons, so it's a notification
  instead, posted by the phone's shell (no app involved): `tools/phone/status.sh post` keeps it
  updated with what's being tested; swiping it away is the interrupt (checked between steps, the
  step in hand is finished first); `done` turns it into "Phone is yours 😊". Routine written into
  CLAUDE.md ("Testing on the phone").
- **"A toast too - I won't see the notification text without pulling down the shade."** The
  debug build now has a receiver (src/debug only, so the release app is unchanged) that
  `status.sh` broadcasts to: each status shows as a toast over whatever app is open. The swipe
  interrupt was tested: noticed ~30 s after Dan swiped. **"Make toasts stack rather than queue."**
  A new message replaces the one showing, with the last two from the past few seconds stacked as
  two lines (a text toast's limit).
- **"Make the notification go away when you're done, and the final toast last longer with a
  smiley."** The shell can't cancel a notification but can snooze it: `done` snoozes it for a
  year, and each run gets its own tag so later posts don't land in a snoozed one. The final toast
  ("😊 Phone is yours - ...") shows twice over, ~7 s. **"Does it have to make a sound?"** No: each
  post re-alerted, so the notification is now posted once per run and later updates are toasts
  only (toasts are silent). The one remaining sound can be silenced via the Shell app's
  notification settings on the phone.
- **"Update the release install and merge this branch."** Release build installed (no database
  change since v15). `audio-and-shows` squash-merged into master as one commit, 1adcbc4; master
  not pushed yet - waiting for Dan's word.
- **"Push master and delete the branch."** Done; the docs deploy didn't start from the push (it
  never has - every run so far was manual), so it was started by hand and succeeded.
  **"Could deployment be automatic?"** The workflow already asks to deploy on such pushes; likely
  the fork safeguard (GitHub won't run push workflows on a fork until enabled in the Actions tab).
  Asked Dan to check for that banner; meanwhile CLAUDE.md says to dispatch by hand after a push.
