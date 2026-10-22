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
- **"I enabled workflows in the Actions tab."** To be confirmed by the next docs push to master;
  the manual fallback stays in CLAUDE.md until then.

## 2026-10-21, branch `UltraRestArea`

- **"A second app from this codebase, 'Ultra Rest Area': no Android UI at all, every screen
  drawn by shaders, leading through a curated library of shader sets you can configure (audio
  source etc.) with shader-drawn widgets. Thoughts? Branch: UltraRestArea."** Branch made. Liked
  it; suggested a Gradle product flavour (its own app id, sharing the renderer), Java owning the
  UI's state and hit-testing while shaders draw it, packs as the library, and noted the
  unavoidable system bits (permission prompts, back gesture) - questions back to Dan before a
  first milestone.
- **"No editor, bundled sets first, make a first menu shader; visual style to explore - metaballs
  that blob together for snapping, reaction-diffusion guided into UI shapes, even RD 'machines'
  like GoL computers. App id is good - document the plan."** Wrote `docs/ultra-rest-area.md`:
  decisions, architecture, the three visual directions, milestones (first: the flavour and a
  metaball menu), open questions.
- **"The UI could be so fascinating people just play with it - could sell 'RD-machine
  contraption packs' instead?"** Yes: a contraption ships as a pack like any set (same store and
  delivery), and a playable home screen doubles as the free sample. Added to the plan.
- **"Go ahead with milestone 1."** Built: Gradle product flavours `enki` and `ultrarestarea`
  (app id `enk.danwills.ultrarestarea`); Ultra Rest Area's manifest drops Enki's launcher, editor,
  wallpaper, capture services and every permission (the APK asks for none). Its launcher,
  `RestAreaActivity`, is one full-screen shader (`rest_menu.glsl`): sets as glossy metaballs, the
  finger a blob that merges into them, names drawn with the SDF font from a shader text; Java owns
  layout and hit-testing and passes them as uniforms. A tap grows the set to fill the screen and
  plays it with ShowActivity's new quiet mode (no titles/toasts). Three bundled sets from Enki's
  samples (Drift, Life, Demoscene) install into its own database as packs. Metaball icon rendered
  by `tools/ura/make_icon.py`. Both flavours lint at 0 errors, 50 warnings.
- **"Give it a go now, then back to VTIs."** Tested on the phone: the menu draws three glossy
  merging blobs with their names; tapping Drift swells it over the screen and the set plays with
  no titles; Back returns to the menu, reset. To tune later: the tiles sit close enough to always
  merge, and the names' letters are spaced wide.
- **"Another VTI - convert both, named after the files. In the all-reagents view add the colour
  view too; tap a tile for it full screen (painting only that reagent), tap to go back, three
  fingers to exit; painting on a tile paints only that reagent. A gradient button cycling Ready's
  maps and all the CET maps (I have a copy); a rainbow button for composite colour (each reagent
  to a power, times a colour and weight, over the weights' sum); hold a reagent tile for an HSV
  wheel, two fingers for its weight."** Built: a built-in `colormaps` texture in the app (Ready's
  ten + all 77 CET maps - Dan's 59 CSVs plus 18 newer from colorcet; CC BY 4.0, credited), and the
  converter's interface rebuilt around a state row (views, taps, per-reagent painting, map
  button, composite with wheel/weight/power). Both VTIs loaded into Enki debug under their file
  names. **"Buttons only on the all-reagents screen."** Done. **"Another circle to edit the main
  brush's paint colour."** Asked what it should change.
- **"3: a circle in the top row - drag it to edit the brush's values, and if a second finger
  joins the drag, the eraser's. Grade and tint each reagent on the tiles screen and see the sum
  full screen, for recordings. And a 'complement' (1 - reagent) per reagent - can it go
  negative?"** Added the brush button (drag: paint values of its two chemicals, shown on a pad;
  second finger: erase values; tap: back to the file's own) and a two-finger tap on a reagent's
  tile to complement it. Levels are already clamped 0-1 to the tile's range (MASK_LOW/HIGH)
  before the power, so 1 - level can't go negative; that range doubles as a levels/contrast
  control. Composite was already what the colour tile full screen and the colour view show.
- **"Phone's free, load them."** Deleted the two old copies and loaded the new ones through the
  Paste dialog (ids 1002, 1003); phone back on Materialistic. **"We need a better way of you
  adding shaders to the library!"** Added one: the debug build's `ShaderImportReceiver` plus
  `tools/phone/add_shader.sh file.glsl [name]` - the file goes into the app's own storage with
  run-as, and the receiver replaces the shader of that name in place (same id) or adds it. No UI;
  refuses while Enki debug is on screen. Tested: replaced 1003 while Dan stayed in Materialistic.
- **"The VTI shader runs out of time and resets after a while - can it simulate forever
  (days)?"** The app's frame count returns to 0 whenever its surface is made again (screen
  timeout, coming back to the app), though the buffers survive - and the converted shaders
  started their pattern on iFrame == 0. They now start on a mark in their state row instead, so
  only truly blank buffers (new, or cleared by Restart) start again. Nothing else limits a run:
  `time` and `frame` don't wrap (frame overflows after ~12 days at 33 steps, harmlessly now).
  Simulation pauses while the screen is off.
- **"It's closed now, go for it."** Both shaders replaced in place (1002, 1003) with
  `add_shader.sh` - no UI.
- **"Now it doesn't do the noisy initialisation - gotta paint to start it."** The app clears new
  buffers to black with alpha 1, which read as the "started" mark (w > 0.5). The mark is now 2.
  (On the way, found and deleted a stray nameless copy, id 1001, left by an earlier paste.)
  Dan: "nice the startup is back now!"
- **"If Enki's open when shaders come over, could it ask whether to discard the current versions or
  ignore the import?"** The debug build's receiver now opens a see-through ShaderImportActivity
  over whatever's showing, with Replace/Ignore; Replace on the open shader reloads it in the
  editor (a debug-only `reload_shader` extra on MainActivity) without saving the old version.
  Tested on the phone: both shaders replaced through the dialog, the open one reloading.
- **"A repeated row of pixels in the centre (rendering, not the sim)."** The buffers have one row
  more than the grid (the state row); the colour view stretched the grid's rows over the screen's,
  so the middle row showed twice. Now one screen row per grid row, the extra top row wrapping (or
  clamping) - rdCell() is shared with the Image pass for that. Sent both shaders over (the
  phone asks, as Enki's open).
- **"Control how pixels are interpolated at low res (1/32)? Options, and where - a comment flag,
  editable from the UI?"** and **"explore scaling the Laplacian"** - answered with options only
  (nothing built yet): smooth upscaling done in the surface shader behind a `///upscale:` line,
  picked from the quality menu, or a separate simulation resolution; for the Laplacian, a
  strength knob (dx already does this), wider stencils, smooth blur-based kernels, and
  per-chemical, oriented or painted scales.

## 2026-10-22

- **"Go ahead with the upscale one - can we include a sinc filter, a few-pixel 'defocussed' look,
  and a NearestN-plus-CRT one for lulzies? All respecting a frame edge mode (periodic, clamp,
  mirror, black)."** Built `///upscale:filter[,edge[,amount]]` (UpscaleDirective): nearest,
  sharp pixels, bilinear, B-spline, Catmull-Rom, sinc (Lanczos, 2-6 lobes), defocus (radius),
  CRT (strength), each with the four edge modes, on screen only. Upscaling… at the bottom of the
  quality menu opens a live-preview dialog; OK writes the line without re-running. First version
  ran at ~7 fps (one shader, runtime branches); now one small program per filter/edge/reach:
  sinc 40 fps, defocus 39, CRT 38 on the phone at 1/32.
- **"Doesn't look very sharp for sinc!?"** It's right for smooth content: sinc rebuilds the
  smoothest signal through the pixels, and these blobs are smooth at 1/32, so there's no edge to
  ring at. Added a Lobes amount (more ringing); 6 lobes costs ~6 fps and still shows no ringing
  here.
- **"2 is really what I meant"** (the smooth wide Laplacian) - vti2enki's `LAPLACIAN_SCALE`:
  below 1 scales the stencil down, above 1 a Gaussian-based Laplacian (`rdWideBlur()`) whose
  width grows with the scale, so it stays stable at the same timestep. Tested at 2.0 at 1/32:
  much bigger blobs, stable, 49 fps.
- **VTI interface list:** in composite, tiles (and a chemical full screen) show each chemical's
  tinted, powered, weighted part (`rdContribution()`) - so the swatch circle and letter labels
  are gone, and weight/power bars show only while two fingers drag them; the buttons sit just
  above the tiles; holding the colour map button opens a strip of every map to drag through; a
  new random button (tap: random composite look; hold: wander through looks, stopping a quarter
  second before letting go).
- **"Painting often gets taken over by black or a reddish (NaN?) colour - could clamping
  help?"** Added `rdSane()` at the end of every step: NaN/infinite values go back to the cell's
  previous value (a NaN otherwise spreads through the Laplacian), and each chemical is clamped
  to `KEEP_RANGE` (4) range-widths past its mask range. Not yet tried on the phone.
- **"The sim still resets after a while - can they last forever?"** The phone's log showed the
  buffers made afresh ~2 s after each dialog closed: Run mode Automatic re-runs a shader 2 s after
  any edit (Upscaling…'s OK writes a line), and every run started buffer shaders over. Now a
  re-run of the shader that's running, at the same quality, keeps its clock and buffers
  (`ShaderViewManager.rerunProjectSession()`, as ShaderToy does); switching shaders, a quality
  change or Restart still start over.
- **Dan's own VTI, orbits_ddd_epochs_init.djw** (complex-number orbit rule): the converter now
  maps OpenCL's `atan2` (and atan2pi, sinpi/cospi/tanpi, expm1, log1p). rdSane()'s clamp range
  is now the wider of a chemical's mask range and LOW..HIGH, so this rule's a and b (default 0-1
  masks) aren't clamped tightly.
- **"The guard should only clamp off NaN and Inf, not otherwise clamp anything."** Done: rdSane()
  now only replaces NaN/infinite values (tested from the float's bits, `rdBad()`) with the
  previous value (or 0); KEEP_RANGE is gone.
- **"Go ahead and install it."** Installed the keep-state-on-re-run build and replaced all three
  VTI shaders (1002-1004) with the NaN-only guard; Enki reopened. (The replace dropped the
  `///upscale:sinc` line Dan had added to 1002.)
- **"Much larger, more verbose, colourful status toasts - every detail, icons, happy/sad - without
  piling up; and leave the notification at the end saying Claude's finished."** Replaced the
  toasts with a debug-only StatusOverlay (TestStatusReceiver): a panel of the last 8 lines over
  every app, each with a kind's icon (good/bad/warn/build/...), `code`, numbers and good/bad
  words coloured, fading after 20 s; touches pass through (0.8 opacity, as Android requires).
  `status.sh done` now updates the notification to "Claude is all finished 😊" and leaves it;
  the next run takes it down. `status.sh hide` clears the panel for screenshots.
- **"Upscaling shouldn't be a property of the shader (the source change recompiles - a nasty
  bump); keep it for the session, with a preference for the one to start on."** It's now the
  view's (`Upscaling`, `ShaderRenderer.setUpscale()`), kept for the app's session, starting from
  Settings → Performance → Default upscaling (same picker; stored as a spec string that falls
  back to Default if the modes change). No `///upscale:` lines any more.
- **"Get them on the phone, and do some housekeeping so you can go on about it."** Installed;
  narrated everything in the new panel. Re-imported 1002 without its old `///upscale:` line.
  Found the keep-state-on-edit fix didn't cover Run mode Automatic (its own
  `setProjectSessionIfChanged()` path) - fixed and confirmed: an edit re-runs with 0 new
  buffers. Set sinc + periodic edges through the new view-only picker (no rebuild), saw Settings'
  new Default upscaling entry, and returning from Settings kept the sim too.
- **"Random colour walk much slower, heading towards high-contrast, complementary looks that show
  all the reagents - guided by sampling the buffers"** and **"adjustable ramp frequency, by
  dragging right off the gradient picker, up to 4x."** Random looks now change about every 4 s
  (RANDOM_RATE 0.25) and are steered (by a wandering amount) by an 8x8 sample of each chemical:
  hues spread by variation rank (top two complementary), powers putting the typical level
  mid-way (complement if mostly high), weights by variation. Picking a map, dragging right past a
  gap sets its frequency (1-4, eighths, whole numbers sticky), shown in a zone with ticks and
  "x2.50". Dan: the conversions match Ready "pretty much perfectly"; he has ~9 GB of VTIs to
  turn into packs for Enki and Ultra Rest Area later.
- Sending three shaders at once while Enki was open exposed an add_shader.sh bug: every import
  used the same incoming file, so waiting dialogs would all read the last one sent. No harm done
  (Dan replaced orbits first, which matched; the other two failed cleanly). Each import now
  gets its own file; resent the two grayscott shaders.
- **CRT: stronger scanlines, triads wider than tall, old-TV imperfections; fancy defocus with a
  shaped, sharp, chromatic bokeh and cat's-eye vignetting; brush a fixed share of the screen;
  guided random walk updated every frame but much more gently; additive composite (can it go
  black?).** CRT reworked (narrower beams, staggered slot mask, bulge/rounded corners,
  convergence error, glow, static, flicker, hum bar, line wobble; `upscaleTime`). New "Fancy
  defocus (bokeh)" filter: seven-bladed iris, bright rim, crisp edge, red/blue fringes, cat's
  eyes. Brush: `BRUSH_SIZE` 0.05 of the grid's shorter side (`ERASE_SIZE` 1.5x) - an on-screen
  size control is still to do. Wandering is now `rdWanderStep()` every step (RANDOM_NUDGE /
  RANDOM_PULL 0.004) with snapshots for the rewind. Composite now adds (COMPOSITE_GAIN 1.6 for
  an average look); weights -2..4, so 0 hides a chemical and negative darkens - before, nothing
  could go black. Not yet tried on the phone.
- Installed that build and replaced 1002-1004 silently while Dan was in another app (no taps).
- **"CRT: turn the bulge down (taps don't line up), bigger softer phosphors, scanlines still not
  showing - like a CRT seen close up. A Gaussian upscaler. And the upscaling in captures (a
  tickbox, with Match App Res, Video Scale 1)."** CRT: bulge 0.045 -> 0.012, smaller corners;
  lines at least 8 px apart (virtual lines at high res), beams narrower; soft cosine-profile
  slot-mask triads at least 12 px wide. Gaussian: separable blur at the frame's own size, then
  B-spline up - cheap at any radius (sigma 0.5-8). Capture: Include Upscaling under Match App
  Res draws each frame through the upscaling at the view's size, Video Scale 1. Not yet tried on
  the phone.
- Installed that build silently (Dan in another app).
- **"I tried the fancy bokeh (AMAZING) and it worked great in a capture!"** - Include Upscaling confirmed on the phone with Fancy defocus.
- **"Can we get everything pushed?"** Everything was already committed and pushed on UltraRestArea; only the untracked `vti/` folder (Dan's VTIs and their converted shaders) is outside git.
- **"Commit the VTIs to tools/ready/examples."** Copied the three (both grayscott history-wave ones and Dan's orbits rule) there, checked they still convert and compile, and noted them in AGENTS.md; `vti/` left as Dan's working folder.
- **"Have a look at my big collection of VTIs: collapse near-identical ones into sets of parameter values, and replace the saved initial states (the bulk of the size) with procedural generation that still restarts them correctly."** Read-only survey of `~/bin/ready-0.6/Patterns` (scripts in the session scratchpad, nothing written there): 1,752 files, 8.2 GB, of which rules/settings are only 10.8 MB, so the rest is saved state. There are 116 exact duplicates (0.45 GB), and 1,635 unique files use just 230 distinct rule codes, so 620 files share both code and parameters with another and differ only in state or render settings. Collapsing therefore organises the collection but barely shrinks it. In a 200-file sample, 78% hold an evolved state that Ready loads instead of the initial pattern. Range-scaled 8-bit plus LZMA stores the same states in about 7% of the size, 16-bit in about 28%. Proposed: dedupe, then families, then a procedural "look-alike" seed fitted to each saved state (levels, spectrum, cross-chemical correlation), with quantised states as the fallback, validated by running both.
- **"Do you think you'll be able to determine whether a new synthetic init does the trick and still starts closely enough?"** Yes for most, measured rather than guessed: run each rule forward from its saved state and from the synthetic seed, and compare value distributions, power spectra, cross-chemical joint histograms and rate of change against the saved state's own drift over time, recording how many steps the seed takes to fall within it. Borderline cases go on contact sheets for Dan. History-dependent or hand-painted states will fail visibly and keep a quantised state instead. Desktop GTX 960 + EGL is available for running them in bulk.
- **Synthetic-init criteria from Dan:** time to reach the initialised state should be within about 1.5-2x the from-buffers time (longer means it "missed the turn-off"), and only statistical similarity of each reagent's amounts matters, not shapes. Losing stable structures is fine if the same kind of dynamics restarts, so a sampled subsection could do instead of the whole state. Built a headless desktop runner (scratchpad: EGL + GLES 3 on the GTX 960, stepping vti2enki's buffer passes unchanged, about 650 steps/s at 512² with 5 reagents). It starts each rule from the saved buffers, a fitted synthetic seed (per-reagent quantiles, radial spectrum, correlations: about 300 floats), a tiled 8-bit patch of the saved state, or the converter's initial pattern. It records reagent statistics over time and reports when each run settles into the buffer run's band, with contact sheets. First finding on fuseWorms halfK: the synthetic seed regrows the same dots-and-worms dynamics (settling about 9k steps against 28k for the initial pattern). A seed must also match each reagent's mean exactly, because a wave-velocity reagent conserves its mean and any offset keeps pumping the wave. Running on eight varied rules next.
- Ran the comparison on eight varied rules (the orbits nova one exposed a vti2enki bug, now fixed: formula-declared locals like `float p` clashed with the shader's own `p`). Scored on each reagent's mean and spread, within 10% of its saved spread (a flat reagent: 25% of its level), passing at most 2x the buffers' settle time or a 2,000-step floor. A tiled 128² 8-bit patch passes 5 of 7 (fuseWorms, movingMaze, FitzHugh-Nagumo spots, Kuramoto-Sivashinsky flames, metaGrayScott worms/solitons); churnyWaves gets there but too slowly (18.8k steps); the geoTrees `_init` state (localised tree growth) fails with every seed. The fitted synthetic seed passes 1 of 7 (in FitzHugh-Nagumo it lands on stripes rather than spots) and the converter's initial pattern 0. A 128² patch is about 1% of a 512² state's bytes.
- **"All 3 sound amazing, go ahead"** (bulk test, sturdier seeds, 2,000-step floor). Bulk run started on 246 files: one per rule family (160 families, 1,530 files), plus extra picks from the big families, cropped to 512². Seeds: synthetic, one tiled patch, four tiled half-size patches, a patch on the synthetic seed, and (added after the first files) the busiest patch scattered over the resting background as often as the means need. The early sheets showed that "settle into the buffer run's band" misjudges states that are still growing, so scoring is now by time stretch: the seed's run at a warm-up of at most 2,000 steps plus k·t, with k from 1 to 2, must keep each reagent's mean and std within 10% of the buffer run at t.
- **"Can you open them on an artifact or simple web page, with labels and a bit of analysis?"** Published the contact sheets so far, grouped by verdict, with a chip per seed and a note on each, at https://claude.ai/artifact/28Ap7nXz7Fqb6jxUbwv8ep (built by the scratchpad's build_page.py, so it can be refreshed when the run finishes).
- **"Is it still going? Let me know when it's done."** The bulk run finished all 246 files. Fixed two bugs in the seed code (one-reagent rules, and a nearly constant reagent too narrow to histogram) and reran those 13 files. Verdicts: 106 restart from a seed, 56 don't (36 of them within 20%: slow growers like sparse colonies and coarsening meshes, the rest a different attractor, such as calm-phase orbits rules), 39 have an all-zero saved state (no storage needed), 24 are unbounded, and 21 hit converter gaps (10 OpenCL `get_global_id`, 4 vti2enki stops, 7 non-compiling shaders). Passes per seed: tiled patch 84/162, four patches 70, patch on synthetic 52, synthetic 46, patch on rest 31/158. Estimate for the 1,530 files the sample stands for: 5.7 GB down to about 240 MB, almost all of it 8-bit LZMA copies of the states that need them. Page republished at the same link.
- **"Fix the converter failures in vti2enki, give the longer warm-ups a go, then start building the family index/seed storage."**
  - vti2enki: `<point3d>` read like `<point3D>`; Ready's `x`/`y`/`X`/`Y`/`index_*` and OpenCL's `get_global_id`/`get_global_size` supported (the GL-ramps/GL-magnitude ramps); a missing last semicolon added; a formula's own `a_out[index_here]` write dropped. Formulas written for Ready 0.6's 4-cell blocks (`a.y`, `a_w.w`) or reading `a_in[]` are skipped with that reason. 17 of the 21 failures now convert and compile; the other 225 sample files and the examples convert byte-for-byte as before. Those 17 rerun in the seed trial.
  - Longer warm-ups: rescoring with up to 10k steps of warm-up only lifts passes from 106 to 114 of 162. Most misses aren't slow: their error is flat from 2k to 10k, settling 10-15% off. 29 still improving at the cap are rerunning at 40k steps.
  - New `tools/ready/vtipack.py` (with `vtiseed.py`, `vtistate.py`, `rdheadless.py`): `index` (1,752 files: 1,636 unique in 230 families, 116 duplicates; headers 10.8 MB -> 139 KB), `build` (cheapest passing seed per file, else 8-bit, 16-bit or exact; resumable), `expand` (a `.vti` back with its state grown from the seed), `report`. Full build of Dan's collection running into the scratchpad; Patterns untouched.
- **"No need to worry about things that are too large or problematic! Can just exclude them."** vtipack now leaves those out, noting why, instead of keeping exact or 16-bit copies: states that neither a seed nor a quantised copy restarts, states over 1024² that no seed restarts, runaway rules, and rules vti2enki can't convert. Also found why the build hit MemoryErrors: strict overcommit plus about 34 GB committed per NVIDIA GL process, so at most three GPU processes run at once. Build restarted.
- **"Will you be able to pause for a bit and resume later on?"** Yes: the build and the reruns skip what's done, so killing them frees the GPU and rerunning the same commands carries on (or `kill -STOP`/`-CONT` to freeze them in place). Dan has a few hours and will say when.
- The 29 still-improving files at 40k steps (23 reran one at a time after hitting the memory limit): 0 pass at a 2k warm-up, 2 at 5k, 3 at 10k and 9 at 20k. Most of the rest settle 10-15% off however long they run, so longer warm-ups aren't worth their time. The build was doing about 1.3 files a minute with two processes, so `vtipack build` now takes a claim file per state, letting any number of builds share one list. Restarted as three workers.
- **"Make a small example shader that runs game-of-life in an offscreen buffer and draws it on the main image with scroll and zoom? And when a shader that uses offscreen buffers is pasted, will Enki configure itself with the extra buffers?"** Wrote Life Viewer (scratchpad `life_viewer.glsl`, compile-checked with `check_shader.py`, not yet tried on the phone): Buffer A runs Life on a wrapping 256² grid with fading trails, Buffer B (`///float32`) keeps the view (one finger pans, two pinch-zoom around their midpoint, a finger count change never jumps), three fingers scatter a new soup, and the Image pass draws it with cell lines when zoomed in. Paste answer: yes, text with `//// Buffer A` marker lines (as Copy writes it) replaces the shader's buffers and sets their count (`MainActivity`'s paste → `MultipassSource.parse()` → `setBufferSources()`); text without markers leaves the existing buffers as they are.
- **"Yes add it as a sample!"** and **"Can we make the shader sidebar fill the whole screen, and in flat-list mode use '...' so we can always see the end of long shader names?"** Three commits: the sidebar is now full width (DrawerLayout keeps a 64dp margin beside a match_parent drawer, so `TouchThruDrawerLayout` gives it the exact width); the flat list ellipsises names at the start; and Life Viewer is a sample, with a thumbnail and strings in all five languages. Its grid is now GRID cells across the shorter side and the screen's shape along the longer, because a square 256 grid repeated twice on a tall phone. Tested on the phone (debug build): the sidebar fills the width, a long name shows `...ott-historyWave_fuseWorms-wiggletrains_init`, the sample loads with both buffers and runs at about 100 fps, and a drag moves the view exactly with the finger (392, -588 px for 400, -600). Pinch-zoom wasn't tested, since adb can't do two fingers. Lint: 51 warnings against a 50 baseline, none in the changed files.
- **"Could we keep shader packs as separate DBs? Would opening several to show the sidebar cost much?"** Answered: opening is cheap (a few ms each, and they'd stay open); the cost is in complexity, explained in chat.
- **"Can the '...' appear in the middle of the string instead of at the start when shortening shader names?"** Yes: the flat list now ellipsises in the middle (`grayscott-historyWave_...orms-wiggletrains_init`), checked on the phone.

## 2026-10-23

- **"A shader fails straight away with auto tile size finding, but works with tiny tiles and testing off"** (ErosionNoiseRenderTest, 2048², oversampling 1). The log showed the smallest tile drawing fine in 4-6 ms but failing the probe's "untouched pixels" check (41 pixels). The probe never bound a texture to the shader's samplers, so `backbuffer` read the tile being drawn (still the sentinel colour), and this shader copies its saved-view row through from the backbuffer. Fixed by binding a blank texture to every unit in `TileSafetyProbe`; on the phone auto-detect now passes Tiny to Large and picks Large (Max at 2.3 s is over the 600 ms budget), and the capture runs.
- **"Video Scale should grey out when not making a video?"** Done: greyed unless Render a Sequence and Create Video are both ticked (label dimmed, so its long-press help still works). Checked on the phone.
- **"Changing the res changes the view - is it the shader or Enki?"** The shader: its world was measured in screen pixels (`pxWorld = 1/zoom`, `screenToWorld` divides by zoom only) and its saved pan was scaled by `resolution.x`/`.y`, so any resolution change (quality, Ctrl +/-, a capture) changed both scale and centre. **"Fix that in the shader and upload it"**: v12 [RES] measures the screen in TARGET_HEIGHT units (`scale = resolution.y / TARGET_HEIGHT`, previously only used by the trail rings) and saves pan, inertia and trails in those units. Replaced on the debug build with `add_shader.sh`, Dan's pose kept; the original is in the session scratchpad.
- Still open: the live view resetting after a capture, and which progress-dialog stage text gets stuck (asked Dan).
- Dan confirmed: with v12 [RES] the view stays the same size when changing resolution.
- **"1440x3088 render of the same view"**: auto-detect picked Large, and the capture matches the live 1/32 view exactly (same framing, full detail; sent to Dan).
- **"When I returned to Enki it reset the view - was it the render?"** No, it was leaving the app (to Photos): coming back recreated the surface, which restarted `time`, and the shader resets its view on `time < 0.1` (its backbuffer had survived). **"2 I reckon - would be great if time continued when one came back"**: `ShaderView.onPause()` now stops the clock and keeps it through the surface returning, which starts it again, so time carries on leaving out the time away; shader swaps still restart, a soft-paused shader stays paused. Checked on the phone: dragged the view, went home for 4 s, came back, view unchanged. Dan: keep an eye out for subtle effects.
- **"When shaders are copied, copy the name too (a special comment?) so pasting offers overwrite/rename"**: Copy and Share now start with `//// Name <name>`; Paste as New/opened text pre-fill it, and a taken name asks Replace or Rename. Paste over Current drops the line. Checked on the phone (pre-filled name, clash prompt, Rename back to the selected name field; Replace not run on Dan's shader).
- **"How's the pack build going?"** 794 of 1,635 states done (about 190 an hour with three workers, so roughly 4.5 hours to go). So far 72 MB of 8-bit copies, 24 MB of 16-bit, about 7 MB of seeds, 47 needing no state, 112 left out (59 runaway rules, 39 nothing restarts, 9 too big at 2048², 3 3D, 1 unconvertible, 1 unreadable). Two files with no state arrays at all failed every pass; fixed (stored as "none"), so the retry pass will take them.
- **"Metropolis buddhabrot with pointer controls and accumulation - what are the chances?"** Explained the catch (a buddhabrot is a scatter; fragment passes only write their own pixel) and two routes: per-pixel Metropolis chains (works now) or a point-splat pass in Enki (the classic method, plus attractors/IFS/particles). **"Option 1, and is it fair to say a fragment can't write to another location? Could a splat mode later even do 3D Gaussian splatting, like a special Upscaling mode?"** Yes on both (answered in chat). Wrote Metropolis Buddhabrot (scratchpad `buddhabrot.glsl`, compile-checked; added to Enki debug as id 1007, not yet seen running): A keeps the view (pan, pinch zoom and turn, three fingers reset) and the last frame's view; B is each pixel's chain (cardioid/bulb skip, steps from 1/20 px to 50 px plus 10% uniform proposals, Gaussian visit kernel), its sample weighted by the known proposal density so the average is unbiased; C accumulates nebulabrot colours (1000/200/40 steps) a colour a frame, reprojected and cut to 6 frames' worth while moving; the Image pass auto-exposes per colour.
- **"Update the release build once we're done with the current stuff."** Will do (still DB v15, so no migration).
- **"Stop the VTI processing now but go on with the other things."** Stopped (1,051 of 1,635 states done; rerunning `vtipack.py build` resumes).
- **"Go ahead with both new upscaling modes ('accumulate points' and '2D gaussian splats'), then port the buddhabrot to one - and would 2D splats help a buddhabrot?"** Built `PrimitivePresenter`: Upscaling's Accumulate Points and 2D Gaussian Splats draw what the last buffer lists (points: x, y, intensity, packed colour; splats: texel pairs of centre + major axis, colour + minor), added up over the Image pass in a float sum, with an optional control texel (keep adding, exposure, point radius, tone-map). Fixed on the phone: a colour-packing rounding bug (a seam where values passed 2^23) and accumulation never building (the ping-pong textures looked like a new buffer each frame). Ported the buddhabrot as a conveyor of orbits (every texel a point every frame; Metropolis over c at each chain's start, weighted 1/on-screen points, capped by MIN_SCORE against sparkles): 14 fps, then 23 with half the orbit work (this phone has no EXT_float_blend for the other speed-up).
- **"Can the shader say its default rendering mode?"** Yes: `///upscale:points` (any Upscaling spec) in any pass; the dialog still overrides it for that shader. **"Lower res would be fine"**: points and splats now add up at the frame's size (the quality), smoothly scaled. **"Point radius / shadeable scale?"**: points get a Gaussian radius from the control texel; a per-point size is what splats are.
