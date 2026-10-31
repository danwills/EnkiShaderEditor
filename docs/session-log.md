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
- **"Can the status toasts appear closer to the top, and could you show a dialog to ask before taking over the phone?"** The status panel now sits just under the status bar. New `status.sh ask 'text' [seconds]`: a see-through dialog over whatever's showing (debug build's `TakeOverActivity`, own task, started by `am start`) with Go ahead / Not now, the answer read back with run-as (exit 0/1, 2 for no answer). Tested: Dan answered Go ahead from the home screen and was left there. CLAUDE.md's phone-testing steps now start with asking.
- **"The xform would work for flame fractals, right? I'd love a buddhabrot on splats with scale and opacity, soft then sharpening with certainty."** Answered in chat; the splat buddhabrot is next.
- **"Splat buddhabrot (soft, sharpening with certainty), a colourful but tasteful splat flame fractal randomised by N-clicking (OKLCH?), the splats xform too - with points scaling on zoom - and call it 'splats xform'."** All built and tried on the phone (Dan said go ahead via the new prompt):
  - Presenter: hit counts in the sum's alpha and a log-density tone map (control w = 2); the splats xform (offset, zoom, turn) applied as primitives are drawn, points growing with the zoom; a change restarts the sum and redraws at once, even paused.
  - Upscaling… > Adjust Splats Xform… (points/splats only): one finger drags, two pinch and twist, a bar with Reset/Done; reset when another shader opens. Drag tested (follows the finger exactly); pinch/twist need two fingers.
  - Splat Flame (`tools/samples/primitives/splat_flame.glsl`): particles playing the chaos game through 2-4 seeded transforms with blended variations, log density, a four-stop OKLCH palette (neighbouring hues dark to light, sometimes a complementary accent, moderate chroma); double-tap for a new one. First tries clumped; less contraction and wider chroma gave teal/amber, dusty rose, olive-gold flames at ~30 fps. Some seeds are still sparse or line-art.
  - Buddhabrot Splats: the conveyor buddhabrot as splats shrinking from 3 px to 0.5 px sigma as the view stays still, each keeping its light: a soft nebula that sharpens, ~26 fps. It pans/zooms in the shader (recomputing orbits); the flame uses the splats xform.
- **"Don't block waiting on a click forever: time out into 'going ahead in 30 s'."** Done: unanswered for 90 s the prompt counts down 30 s and goes ahead; Not now still stops it (not installed yet, to not restart Enki mid-test).
- **"An 'Icons' fractal as a splats shader (see ~/quid/icons), dragged to explore the coefficients, symmetry an integer"** and **"the splat flame with idle animation, velocity-stretched gaussians, and a running set of gaussians updated with old samples evicted - and fading smoothly to the new fractal on double-tap"**:
  - Presenter: the control texel's keep is now a fraction - older frames fade (an exponential running set) - with a fade quad where primitives blend straight into the sum.
  - Icons (`tools/samples/primitives/icons.glsl`): Field & Golubitsky's z' = (λ + α|z|² + β Re(zⁿ) + iω)z + γ z̄ⁿ⁻¹ as splats; one finger λ/α, two β/γ and twist ω, three swipe n (2-12), double-tap ten classic presets; a six-line readout while touching; fitted to the frame's shorter side; ~35 fps. Tried on the phone: dragging reshapes it live, presets step (adb taps under a frame long don't register; real ones do).
  - Splat flame: transforms wander (ANIMATE), each particle's last step also taken in the last frame's flame for its velocity, splats stretched along it keeping their light, keep 0.85, double-tap morphs over 2.5 s (numbers, variations and palettes blended). ~18 fps; checked the morph and blur on the phone.
- **Prompt tweaks**: a warning icon (yellow) on the countdown dialog, 10 s question then 5 s countdown (timed: 17 s end to end). **"I accidentally clicked away from the dialog - maybe it should post a notification, where one can cancel or go ahead"**: a tap outside no longer answers; unanswered and pushed away, it posts a notification with Not now / Go ahead, its body bringing the dialog back. Tested by pressing Home mid-prompt: the notification appeared with both buttons, and the countdown still went ahead in the background (65 s for a 60 s wait).
- **"Extract all this phone-use-notification stuff into a folder in the repo to help other Claudes do the same"**: `tools/phone-kit/` - a README (what it does, setup, a CLAUDE.md snippet, lessons learned), a generalised `status.sh` (PHONE_KIT_APP / PHONE_KIT_CLASSES), and the debug-build Android pieces under a neutral package with manifest and theme snippets.
- **"Do some actions when it times out so I can tell - sing me a song! Maybe both dialogs 15 s?"** Both are 15 s now (app, both status.sh copies, CLAUDE.md, kit README). Timed out on its own in 31 s, then sang "The Ballad of Dan and Claude" in the status panel, a line every 5.5 s, checking for an interrupt between lines.
- **"Icons: 2-finger pan/rot/zoom. Splat Flames: avoid picking contractive dots/lines. Splats where points are far apart as large soft transparent gaussians (a new shader). 1-finger drag adjusts the flame coefficients. Grey during transitions - additive?"**
  - Presenter: for splats the control texel's z can give a reference sigma; each splat then keeps that size's light however big it's drawn.
  - Icons: two fingers pan/zoom/turn the view (three: beta/gamma/omega, four: n; one still lambda/alpha). Untested by touch (adb can't do two fingers).
  - Splat Flame: a coverage check (96 particles in a 12x12 grid) skips dot/line flames after they settle - six flames in a row all came out full on the phone; one-finger drag nudges the transforms (checked); the grey was the palettes blending in a straight line across OKLab - now through OKLCH (hue turns the short way).
  - Splat Flame Adaptive (new): density grid in Buffer C, splats sized by it in Buffer D (the presenter draws the last buffer - the first version had them the other way round and drew the grid as giant red splats). Soft brushy glow in sparse areas, ~13 fps.
- **"Should there be a toast at all times while the phone's in use?"** Now there is: a "Claude is using your phone" pill tops the status panel from the first line to `done` (checked: still there after the line faded).
- **"Icons: correct brightness so it doesn't go thin when zoomed (same for flame). Flame: two-finger pan/zoom/rot. Motion blur that includes parameter changes so 1-finger dragging is smooth."** Exposure × zoom² in Icons and both flames (same brightness at any zoom, grainier until it builds). Both flames got the two-finger view (taps ignore two-finger touches). Motion blur is now each splat's on-screen movement since the last frame, including the nudge (each particle's last step also taken with last frame's nudge) and the view (last frame's view kept), capped at 0.3 frame heights; while the view moves the sum keeps 0.5. Tried on the phone: a slow one-finger drag streaks smoothly rather than breaking up. Pinches untested by touch (adb is single-touch).
- **"Flame brightness dips when parameters change; speckles in sparse areas as scaled Gaussians? Icons: Metropolis to speed it up, orbit velocity to stretch points, and avoid the extra-bright points."** The dip was the plain flame dimming stretched splats' colour but not their count, which log density averages over - it now uses the presenter's light-keeping (colour and count together). Sparse-area speckles: that's what Splat Flame Adaptive does; also new particles (both flames, Icons) now warm up before they're drawn, which removed the bright specks. Icons: explained that attractor samples are already exact, so the win is not wasting them off screen - off-screen particles restart as nudged copies of on-screen ones (resampling); at 6x zoom (tested with a temporary zoomed copy) filaments are crisp at 29 fps with no exposure boost. Velocity stretch tried (STRETCH 0.3): smeary radial streaks, so it's off by default.
- **"The running status isn't showing - make sure it does, and save a note about always doing it."** Two causes: I'd posted only at the start and end of runs, and force-stops/installs killed the panel (it lives in Enki debug's process). New `status.sh again` re-posts the last line (add_shader.sh calls it after stopping the app); CLAUDE.md, the kit README and my memory now say a line before every action and `again` after any restart. Checked: force-stopped Enki and the panel came straight back with its pill.
- **"Get current code built for release and installed to the phone (new shaders into the release library maybe tomorrow)."** Signed `assembleEnkiRelease` (5.2 MB) installed over the release app (still DB v15, no migration; asked first). It opened on Dan's ErosionNoiseAnother3 at 25 fps, library intact; Dan put back on the Claude app. Copying the new shaders (Life Viewer is a built-in sample; Buddhabrot Points/Splats, Splat Flame (+ Adaptive), Icons, the tests) into the release library is for tomorrow - they're in `tools/samples/primitives/`.
- **"Might as well continue with the VTIs now."** The machine had rebooted overnight, clearing `/tmp` and with it the pack (index and 1,051 states). Rebuilt the index (same: 1,636 unique in 230 families) into `~/dev/shaderEditor/vtipack/`, which survives reboots, and restarted the build there: three workers, then a retry pass and the report (~8-9 h).
- **"Maybe we shouldn't keep them in /tmp as I usually turn off metalucia overnight."** Agreed - already moved to `~/dev/shaderEditor/vtipack/`; saved as a standing rule (long jobs and anything worth keeping go outside /tmp and the scratchpad, and stay resumable).
- **"Keep going with the rest of the shaders - ultimately I want them as a collection of related packs."** The build carries on (161 of 1,635 when asked, no failures). Meanwhile built the way from a build to packs: vti2enki can now start a shader from textures instead of the initial pattern, and new `vtishader.py` + `vtipack.py shader/pack/plan/packs` turn each kept pattern into a shader starting from its stored seed or state (exact-pixel PNGs, decoded on the CPU to within half an 8-bit step with exact means), with a thumbnail of its saved state. `plan` drafted 82 packs of up to 48 related patterns (`~/dev/shaderEditor/vtipack/packs.json`, to edit). A 7-shader test pack (one per seed kind, 2.7 MB) compiles; the phone wasn't connected, so it's untested on the device - `tools/phone/add_pack.sh` (debug receiver) will install it. Also fixed `status.sh ask` treating no phone as a yes (exits 2 now).
- **"What interface code is repeated - is there a nice way not to repeat it? Phone's back."** Installed Enki debug with the pack installer and the 7-shader test pack (installed fine: 7 shaders, 13 textures); added a debug `open_shader` extra to open shaders by id; opened the synthetic-seed one, then Dan needed the phone for a meeting - paused and handed back (home screen) before checking it. Answered: each pass repeats the interface code (UI_COMMON ~8 KB in all five, UI_UPDATE ~14 KB in the first-step passes, parameters, helpers), about half of each shader; suggested a ShaderToy-style `//// Common` section.
- **"Make the VTI scanning hit my hardware about half as hard - I don't mind it taking longer"** (then: "the main problem was the GPUs"). Added `vtipack build --duty` (each trial rests between samples so it keeps the GPU busy only that share of the time) and restarted with two workers at duty 0.5: GPU from ~83% to ~38% on average, CPU from 3 cores to about 1. Roughly twice as long now; still resumable.
- **"Yes, go ahead with the Common section."** Built it in the app as ShaderToy's Common tab: stored as a `shader_buffers` row at buffer -1 (so copies, imports and packs carry it), `common.glsl` in projects, `//// Common` in copied/pasted text; compiled into the start of every pass after its #version/#extension lines and ShaderToy setup, with a default precision; its compile errors are reported once, on the Common tab at its own lines; binaries cached by pass + Common (compile-ahead too); a Common tab after Img, and Add/Remove Common in Offscreen Buffers' dialog; Adjust Defines lists Common's defines (used if any pass uses them). vti2enki now writes one Common section: patterns are 34-42% smaller (133 -> 77 KB for 5 chemicals), bit-identical on the desktop GPU, `--no-common` gives the old output. Also fixed lint errors from the phone kit (back to the 50 baseline). Not yet tried on the phone - Dan's in meetings; a 7-shader test pack with Common is ready.
- **"Cool, phone is free now."** Installed the new debug build and the 7-shader test pack (v2) with Common. The Common tab showed, but the Image pass failed to link on the phone ("Call to undefined function"): Common declares `rdChem()` and Image never defined it (the desktop driver let it pass). Fixed in vti2enki (Image's `rdChem()` returns `chemical()`), rebuilt the pack as v3: all 7 compile and run (3.5-5.7 fps full frame), start states as expected. A test shader with a bad line in Common reported "Common: 3: 'oops' : undeclared identifier" once, on the Common tab. Phone handed back (home, stay-awake off).
- **"I think it will be good to use 1/4 as the baseline res."** Pack shaders from Ready files now start at quality 1/4 (was 1/2).
- **"When the pattern is made of randomly rotated tiles, could it vary the rotation continuously instead of 90-degree steps?"** Yes, for seeds: their 128² tiles wrap seamlessly, so each copy is now a cell around a jittered point with its own random angle, flip and offset (no rows of seams). Copies of a whole saved state keep quarter turns so they aren't resampled. Checked on the desktop GPU (patches4 before/after); pack v4 built, not yet on the phone.
- **"Can we add this phone notification scheme to your ongoing abilities, and keep developing it so it flows into other projects - is that how ~/.claude/skills works?"** Yes: personal skills in `~/.claude/skills/` load in every project (a repo's `.claude/skills/` only in that project). Made `~/.claude/skills/phone-testing/`: SKILL.md (the rules - ask, narrate every step, `again` after restarts, interrupt by swipe, hand back as found - plus setting the kit up in a new Android project, and gotchas) and `kit/` (a copy of `tools/phone-kit`). The folder is its own git repo, so the kit can develop there; Enki's copies are to be kept in step.
- **"Add a launcher for live edit mode in the top bar with an exciting icon; straight into live mode if a selection was already made, long-press back to the picker; scrolling the panel gets confused with the sliders; and Select All in Adjust Defines."** New toolbar button after Undo (three sliders and a lightning bolt, no tooltip): goes straight live with the defines last ticked for the shader this session, checked as Go Live checks them; with none ticked, or if some fail, it opens the live setup list (failures in red); long-press always opens the list. The setup list's third button is now Select All / Select None (over what the filter shows). Sliders in the live panel wait for the touch slop: sideways starts a drag, up or down scrolls the panel. Builds, lint at 50/0; the phone wasn't connected, so not yet tried on the device (incl. whether the toolbar still fits two rows).
- **"You can jump on the phone now."** (Wireless debugging had a new port: 10.1.1.198:34017.) All three work on the phone: the toolbar still fits two rows with the new button; with nothing ticked it opened the live setup list; Select All ticked everything and became Select None; Go Live went live with every define of a Ready shader; a vertical swipe starting on a slider's thumb scrolled the panel and left the values alone, while a sideways drag changed the value; after Discard, tapping the button went straight live, and a long press opened the list with the selection still ticked. Phone handed back to the Claude app.
- **"Add a filter field to find a particular control, and show the defines as a tree - groups of similar controls often start with the same `_NAME_ETC`."** The live panel now groups defines by their underscore-separated name parts: two or more sharing a part get a header like `GROUND_ (4)` (parts they all share are merged, e.g. `c_to_`; groups nest, e.g. `wave_` > `wave_to_`), indented, tap the header to fold it (remembered per shader for the session). A Filter box in the panel's top row shows only matching names, opening their groups and counting matches. Tried on the phone with Dan's terrain shader (POND_ (3), GROUND_ (4)): folding, filtering `detail` (GROUND_ (2)) and clearing all worked; discarded, so nothing changed. Couldn't see in screenshots where the keyboard sits relative to the panel (Samsung's keyboard doesn't show in them).
- **"(Had a Bluetooth keyboard connected - worked pretty well.) Make save-with-postfix OK with a number as the postfix, and after adjusting defines allow saving as a postfix of the open shader."** A postfix (or rename) starting with a digit no longer gets the capitals warning (a number after a lowercase letter is its own name-tree part). Adjust Defines' confirm dialog has a Save as New Shader row: a new shader named after the open one plus a postfix, the open one unchanged, the new one opened with the same live defines ticked; the postfix dialog (here and in More Options) offers the first free number, pre-selected. Tried on the phone: live-edited timestep on bouncySpaceFiller, Save as New offered `2`, OK made `grayscott-historyWave_bouncySpaceFiller2` (timestep 1.129511 in its Common; the original still 1.0), no warning. The live button on the new shader went live with the same defines but took ~20 s with no sign of checking - it now greys out with a Checking toast (not yet seen on the phone).
- **"Add a spinner to the startup for live edit mode so people know it's going to take a while."** The toolbar button now shows a "Getting live mode ready…" spinner card mid-screen (button greyed); the dialog's Go Live shows a progress bar under the filter. The first try showed nothing on the phone: the UI thread was frozen for seconds building a test copy of the shader per define before the check started. Now only the whole live shader is built up front (after a frame, so the spinner draws first), and the per-define copies only if it fails. Seen on the phone: both spinners show and animate, then live mode opens. Also dropped the "Checking…" label on Go Live, which made the dialog's buttons wrap.
- **"With Save as New on a shader like 'thingYo2' the dialog was pre-filled with 2 and I got 'thingYo22' - make it clear when incrementing the number versus making a new child name."** A name ending with a number now gets a choice in the dialog: "Next number: thingYo3" (default; the first free number after its own) or "A variation of thingYo2" (empty postfix, hint "A word, e.g. Warm"), and a bold "New name: ..." line shows the whole name as typed. Tried on the phone on Dan's `...bouncySpaceFiller22`: offered `...Filler23`, switching to variation and typing Warm previewed `...Filler22Warm`; cancelled, so nothing was made. Back to Enki release as found.
- **"Make the 'reparent' / 'add shader to name group' dialog remember the filter when it's used again."** The parent picker (sidebar's +, and Paste's Add as Child) now starts with the filter it was last left with, for the app session, selected so typing replaces it. Tried on the phone: typed `gray`, closed with Back, reopened - `gray` was there and the list filtered. (I first hit the sidebar's tree toggle by mistake and switched it straight back.)
- **"I have a shader open in debug showing the double-pixel problem - swap to it and diagnose this again."** (fuseWormsDev from the VtiStartTest pack, at 1/16, colour tile full screen.) No record of the problem from before survived. Measured screenshots (mine and Dan's): every block is exactly 16 px, one cell per quality pixel, neighbours not duplicated in pairs, the blobs smooth across cells - so nothing doubled found yet; asked Dan what to look for. A tap of mine missed the toolbar (fullscreen hides the status bar, so it sits higher) and switched the shader's interface to its tiles view; tapping the colour tile put it back.
- **"Make sure the phone control log is visible the whole time you're controlling the phone - if you're thinking, say so and leave it on screen; always leave a good amount of lines visible; when finished, make the log disappear instantly but put its tail into the notification."** Status panel lines no longer fade: the run's last 8 stay up the whole run; `hide` only hides it (lines kept); `again` replays the run's last lines from a log status.sh keeps (fixed `adb shell` eating the loop's input on the way); `done` removes the panel at once and leaves the summary plus the last 8 lines in the finished notification. Rule added (CLAUDE.md, skill, memory): post a `think` line before any pause to think. Tried on the phone: lines stayed 25 s+, a force-stop then `again` brought back all 8, `hide` cleared for a screenshot and the next line restored everything, `done` cleared the panel instantly and the notification showed the tail. Kit copies (tools/phone-kit, the skill) updated too.
- **Dan's zoomed screenshot of "low-res modes repeating pixels".** Measured it: 64 px blocks (16 px cells zoomed 4x) with spots where two neighbouring cells match in a steep gradient. A probe shader on the phone (1/16) showed the Image pass and buffers the same size and a perfect 16 px checker, so the app's scaling is fine. The cause is vti2enki's one-tile-full-screen view (the one Dan had fuseWormsDev in): it scaled the grid to the screen, and the grid is one row shorter than the buffers (the state row), so its middle row showed twice - the colour view had been fixed for the same thing before, this view hadn't. Now one cell per pixel (rdCell), matching where painting lands. Put the fixed fuseWormsDev over the pack's copy on the phone (runs; the full-screen tile view needs three fingers, so Dan to check). A `Size Probe` shader is left in the debug library.
- **"The doubled pixels look fixed! Now the VTI randomisation with contrast tracking leads to dark areas where the wave is low - is it clamped before this stage, or can we recover the whole wave range?"** It was clamped: composite colour, the tiles and the random look's guide read levels across each chemical's mask range, 0-1 for all but the active one, so the wave's negative half (and small chemicals) went black. Now each chemical's real range is tracked in the state row (S_RANGE, from the guide's 8x8 sample: grows at once, shrinks slowly) and the views use it; painting keeps the mask ranges. Desktop: wave d tracked at -0.29..0.26 (actual -0.40..0.21), e -0.0013..0.0004. Put on the phone as fuseWormsDev (runs); the random look needs three fingers, so Dan to judge it. Other converted shaders pick it up when rebuilt.
- **"That sorted out the clamping. Make the randomise button change things even more slowly, and more carefully explore the high-contrast, high hue-variation options for how it composites."** Holding random now searches instead of just jittering: half-second stretches along a random direction, each judged by a look score (brightness spread + colourfulness + colour variation across the grid, minus crushed/blown samples) - carry on if better, retrace if worse, then a new direction. Steps much smaller (about half the old drift per second at 60 fps) and the pull halved. A tap still gives a new random look at once. On the way: fixed Ready's "HSV blend" colour map not compiling (rgb2hsv used before it was defined; colour conversions are shared code now), and untracked Python __pycache__ that an earlier `git add -A tools` had committed. Put fuseWormsDev on the phone (runs, 95 fps); the wander needs the tiles view (three fingers), so Dan to try it.
- **"I kicked off a render of an RD and the progress dialog stays on the shader-compiling message instead of counting frames."** (Then: "it's not showing the preview while it renders, can it?") A buffer shader's frames are read back from the live renderer, not the tiled path, and that path reported no progress or preview, so nothing moved the dialog on. It now reports each frame as one tile done and sends it as the preview; the Avg/ETA timing runs frame to frame. Tried on the phone with fuseWormsDev: "Frame 79 of 300…", Avg/ETA, and the preview updating. My cancels came after the (sub-minute) renders had finished, so two test videos were left (10:31 and 10:33 pm, fuseWormsDev) plus a 5-frame one (10:29 pm); asked Dan before deleting. Noticed the Cancel dialog stays up over a finished render.
- **"I flaming love this notification system!" and "before it hides itself say 'about to hide', and when back 'back.. examining screenshot'; and each item could have an estimate (or lack thereof) of how long it'll take."** `status.sh post` takes an estimate (shown dimmed, "⏱ ~15 s" or "⏱ no estimate"); new `status.sh shot FILE [why]` says it's hiding, hides, screenshots and comes back "Back - looking at the screenshot (why)"; `hide` says so too. Tried on the phone: estimates, hide/back lines, a clean screenshot, and the finished notification's tail. Kit, skill, CLAUDE.md and memory updated.
- **"With VTI shaders, composite colours set up in the tiles view are lost when the screen rotates - could the row-zero handling persist through rotation? And `///steps:N` beyond a few hundred doesn't seem to do that many - make 900 and 3000 work."** (Then, while working: "the continuous range adjustment makes the wave reagent flash - take a lot longer, stay at the same ranges, change very smoothly"; "add a 'hang on a sec' button to the can-I-use-your-phone dialog"; "add a paint size control to the icon buttons, brush 4x bigger by default"; "the rendering dialog shouldn't say the tiling mode when it's not tiling"; "can the running log be updated with how long things actually took?")
  - Rotation: a resize restarted every buffer, and a rotation recreates the activity and with it the GL context. New `///stateRow:top` (or `bottom[,N]`) per buffer pass: those rows are blitted into the new targets on a resize, and read back a few times a second so they're put back after the context is remade (per pass source, this session). vti2enki writes it in every pass; the grid restarts (size changed, or grid blank) while the interface carries on. Phone: composite set in portrait survived landscape and back.
  - Steps: `///steps:` read at most three digits and capped at 64 - now up to 10000 (progressive bands keep the screen responsive). Phone at 1/16: 900 steps 1.1 fps, 3000 steps 0.3 fps, both alive. VTI drifts (wandering, ranges) now move once a frame, not once a step.
  - Ranges: no more instant widening on a spike; eased slowly both ways (out a little faster than in).
  - Hang on a sec: holds the ask a minute more per tap (dialog and notification); `status.sh ask` waits accordingly.
  - Brush size: a button left of random cycles a quarter / half / 1x / 2x of BRUSH_SIZE, now 0.2 (4x the old). Checked on the phone.
  - Render dialog: with no tiling, no tile order spinner, no tile text on the size line, and the one-pass status phrases no longer mention tiling (5 locales).
  - Panel: each line gets "→ took N s" once the next one arrives (and in the finished notification's tail). Checked on the phone.
  - Also fixed Ready's "HSV blend" colour map not compiling and untracked committed __pycache__ earlier in this stretch. Note: the saved state row is keyed by buffer source, so copies of a shader with identical buffers share their interface settings the first time they open.
- **"Swapping between portrait and landscape still seems to reset everything - could the history pixels be copied in?"** (And: "still some bumping from the wave - make the limits much slower.") A rotation recreates the activity and its GL context, so only the saved state row came back and the simulation restarted. New `///resize:tile`: the whole buffer is read back (on pause, and before a resize) and put into the next targets, turned by the display's rotation change (so what was at the top of the phone stays there) and tiled over any size difference; used once, within 10 s. vti2enki adds it and only restarts on a blank grid. Phone: a paused pattern rotated to landscape came back turned the right way, nothing cropped, and back again; composite kept. Ranges now 10x slower. (Plain tiling, tried first, cropped half the pattern going landscape to portrait.)
- **"When switching between landscape and portrait it shows the menus and everything - can whatever mode we're in (fullscreen especially) stay when rotating?"** Fullscreen and the hidden toolbar weren't in the saved instance state (only the editor's visibility was), so the recreated activity came back with menus. Now saved and restored (after the views settle), with fullscreen's pause bookkeeping. Phone: fullscreen held portrait -> landscape -> portrait.

## 2026-10-24

- **"Rotates (without rotating the sim/shader) like a champion now!"** Dan confirmed rotation keeps fullscreen and carries the VTI simulation and its settings through. Nothing changed.
- **"Log buttons would be cool - all your suggestions; the looks right/wrong ones as small thumbs up/down. Don't worry about the old videos. I need clarification about the cancel dialog question."** Added a small touchable window beside the panel's pill: ⏸/▶ (status.sh check waits while paused), ⏹ (check fails, like the swipe), and 👍/👎 while `status.sh rate` asks. Phone: Dan tapped Pause himself and thumbs-up to my question (rate printed "up"); my Stop made check exit 1 and I wrapped up. Fixed durations landing on the buttons' own lines. Kit, skill, CLAUDE.md updated. Explained the cancel dialog question.
- **"Yeah, the first sounds good"** (the render waiting while its Cancel dialog is open). Done: the capture waits at its next frame, tile or video frame until the dialog closes any way. Phone: frames on disk stayed at 292 for 20 s with the dialog up; Delete then removed them and made no video. (Each run gets its own frame folder, so Delete only ever removes that run's frames.)
- **"The pause and stop icons aren't very legible - even black-and-white icons you make yourself would be better."** Drawn now as plain white shapes (two bars / triangle / square) on the dark buttons. Checked on the phone, both states.
- **"Make a cool shader: fuseWormsDev rendered as a heightfield we gradually scroll over, a little lens curvature, solitons not too high; grab view controls from recent shaders."** (Then: "triple-tap could go into what it has now, and back"; "could the 3D view paint on single press, since it's the same as two-finger drag?"; "keep the original non-3D view - this is an experiment"; and "the pill could say 'Claude is Orbiting your phone'".) New `tools/samples/terrain/make_terrain.py` + `fuseworms_terrain.glsl`: buffers unchanged, new ray-marched Image pass (smooth bicubic-ish heights, b hills 1.5 cells, c/d relief, colour map, sun, fog, lens); camera in state-row texels drifting forward; one finger paints on the ground (camGround()), two fingers slide/pinch/twist (icons-style), triple tap toggles the pattern's own interface. 43-47 fps at 1/8 on the phone. It's a separate shader ("FuseWorms Terrain", debug library); fuseWormsDev itself is untouched. Pill verb now random per run.
- **The "isn't responding" hang (Dan: "a new bug I think").** Found by sampling the main thread with jdb: LineNumberEditText.onDraw() set padding every draw, which invalidates, so the editor redrew itself continuously - ~150 ms a frame with a long shader - and the import dialog's input timed out. Fixed (padding only on change, visible line numbers only, no emoji compat, tab-span cleanup fix): 14 ms frames, and Send-with-editor-open no longer hangs. (Some of my retests were confused by a stuck system ANR box lingering from the first hang, as Dan suspected.)
- **"Better interpolation on the 3D version - still a bit pixelly/voxelly"** and **"can't use 2 fingers to look straight down - make the gestures support that smoothly."** The smoothstep-weighted cell blend terraced at every cell; now bilinear for the ray march and a cubic B-spline (4x4 cells) for the hit, normals and lighting; colour bilinear. Two-finger vertical drag tilts (0 to straight down; camera basis from the heading so straight down holds), horizontal slides sideways, pinch height, twist turn. 47 fps at 1/8. Tilt is Dan's to try (adb is single-touch).
- **"I wanted it to still have the 3-finger swap to editing the colours - off to bed, let's do that tomorrow."** Noted for tomorrow: in FuseWorms Terrain, three fingers should go to the pattern's colour/tiles interface as in the flat shader (now it's the triple tap; three fingers currently flip the hidden view).
- "Solid, let's get it going again now": restarted the VTI build (two workers, `--duty 0.5`,
  logs `vtipack/build_d.log`/`build_e.log`). FuseWorms Terrain: three fingers go into the
  pattern's tiles and buttons and back out to the terrain, as they do in the flat shader (the
  terrain stands in for the colour view); a triple tap still swaps in the flat colour view.
- Dan: the 3D terrain was a "how well would it work?" experiment, nearly explored enough - he'll
  try this version, then back to honing the 2D one. (Phone not connected over adb yet, so the new
  terrain isn't in the debug library.)
- "Keep the heightfield version there and not overwrite it, but put the new one on the phone":
  added as FuseWorms Terrain 2 (debug id 1032, 1/8 res), old 1031 untouched. Fixed
  `status.sh again` exiting 1 when the last line had no "took" time (which stopped
  add_shader.sh); same fix in the phone-kit and skill copies.
- "Can you put a version there that is back to 2d please?": added the flat fuseWormsDev (latest
  vti2enki, the vtipack seed start) as FuseWorms 2D (debug id 1033, 1/4 res); nothing replaced.
- **Composite colour: "why is the green wave reagent so faint ... meander to settings that put the higher frequency details on display with good contrast ... get to bright yellow/orange (and cyan) solitons with a complementary blue/purple background ... taps on randomise pick a whole new colouring ... still too fast ... hold on randomise: drag down to slow, past the middle to stop; drag left/right to scrub through the past and future of the meandering."**
  vti2enki's random looks rebuilt. A look is now a colouring by role, from new per-chemical guide
  texels (mean, spread, correlation with the file's active chemical, sticky role): the active
  chemical gets the main hue (often orange/amber/yellow/cyan), chemicals that go with it nearby
  hues, ones against it the complement, dimmer (the background), and the rest (detail, e.g. the
  wave reagents) a third hue, stretched about their typical level so fine detail shows (new
  stretch in the power texel, rdShownLevel()). Each tap is a new seed = a whole new colouring.
  Holding follows that seed's deterministic path (smooth noise of t): RANDOM_RATE 0.06/s,
  lingering (x0.3) while the score beats its recent mean (score now rewards fine detail too);
  dragging down slows it (to stopped past the screen's middle), across scrubs the path back and
  forward (3 units per screen width); let go rewinds 0.4 s at the pace it was going (none if
  stopped). Roles freeze while held. Verified headlessly (tap/hold/slow/stop/scrub/release on the
  state row) and on the phone (four taps: e.g. yellow solitons on blue, cyan on gold) via a
  "FuseWorms 2D Tiles Test" copy (debug id 1034, starts in the tiles since adb can't do three
  fingers). The phone dropped off wifi before the final version (active chemical as main) went on.
- **"I can't get to the wireless debugging settings because it keeps popping back to enki with the log overlay ... Enki had 'appear on top' enabled - if it gets left stuck on, it's not good."**
  The log showed Enki never actually came to the front: the run's status panel was left up after
  the phone dropped off wifi, and Settings' Developer/Wireless debugging screens ignore taps under
  another app's overlay. Fixed in the kit: `status.sh done` now gives "appear on top" back
  (`appops ... default`), so it's only on during a run, and the panel takes itself down after 5
  quiet minutes (not while paused). Enki's copy, tools/phone-kit, the skill and its notes updated.
- **"Can it [the Hang on a sec dialog] dismiss itself for like 30s"** ("Yes exactly": out of the
  way, then back). Hang on a sec now moves the ask dialog's task to the back for 30 s (its
  notification has the same buttons, its Hang on +30 s); `status.sh ask` brings it back with
  `am start` when the time's up (an app in the background can't come forward itself), and its
  15 s countdown only starts once it's back on screen. Kit copies, skill and docs updated; lint 50.
- **"It says paused on the last line but the button is the pause button, not play."** Dan had
  paused before my install; the reinstalled app's new panel assumed running while
  files/claude-control still said paused (so `check` kept waiting). The panel now reads that file
  when it starts. Dan also asked to turn off access outside the project: no settings grant it
  (only `./gradlew assembleDebug` is allowed), so pointed at /permissions; I'm keeping to the
  repo (screenshots to the scratchpad, the skill repo left as it is - its copies of today's kit
  changes are the latest skill commits; this pause fix is only in tools/phone-kit).
- **"I swiped away the notification because the log had disappeared"** - the paused-state bug let
  the 5-minute quiet timeout take the panel down while paused. **"Yes add that [a notification
  saying why], then jump on the phone":** when the panel times out, the debug app now posts
  "Claude's gone quiet ... the panel's down" (cancelled by the next line).
- Phone run: latest debug build installed; FuseWorms 2D (1033) and the Tiles Test copy (1034)
  updated to the active-chemical-as-main colourings. Four taps gave yellow on blue, pink on green,
  orange on teal, orange on violet; hold/stop below the middle/scrub right/scrub back/let go all
  behaved (scrub back matched the stopped look exactly, no rewind when stopped). Dan's Hang on
  stepped aside 30 s and came back as designed. Phone handed back (DoorDash), stay-awake and
  appear-on-top off.
- **"A special shared 'headers' section in packs so they don't repeat the same code? Starting res
  1/8 in general, adjustable in the curation. A little web app for curation, with thumbnails of
  the starting state and after some warmup, and a button to push a shader into Enki on my phone."**
  Pack format 2: blocks of code shared between a pack's passes (measured: ~86% of the text) are
  stored once in `pack_snippets` and referred to as `///snippet:ID` blocks; ShaderPackInstaller
  expands them on install (and refuses packs newer than it reads, new string in all locales).
  Packs start at 1/8 (vtishader.QUALITY), with `quality` per pack and `qualities` per path in
  packs.json (`--quality` for `pack`). Round trip checked exactly on an 11-shader pack.
- **"A special shared 'headers'-kind of section in packs ... make the starting res 1/8 in general, but make it possible to adjust this in the curation ... a little web app to make the curation easier ... thumbnails of starting state and after some warmup ... a button on the web UI and have it push the shader into Enki on my phone."**
  - Pack format 2 (committed earlier): code shared by a pack's passes is stored once (`pack_snippets`) and put back on install. Packs start at 1/8; `packs.json` can set `quality` per pack, or `qualities` per shader.
  - New `tools/ready/vticurate.py` (+ `.html`) at http://127.0.0.1:8770/. Packs are shown as cards: drag them between packs (shift-click picks several), retitle, describe, set the resolution, and search every pack. It saves as you go, first backing up to `packs.json.bak`.
  - Each card has start and warmed-up thumbnails from the real shader with its start textures (1,200 runs on the desktop GPU), cached in `vtipack/thumbs/`.
  - 📱 Send builds a one-shader "VTI Curation" pack and runs `add_pack.sh --replace --open`. The debug receiver can now replace a pack and logs its shader ids, so the newest one opens.
  - Tested locally (headless Chrome); the phone side isn't tried yet.
- **"Change the rules so that you have access to anything in ~/dev/shaderEditor."** Added it to `additionalDirectories` in `.claude/settings.local.json` and noted the rule (still no writes outside it: the skill repo, Ready's Patterns).
- **"Draft a curated grouping please."** New `tools/ready/vtithemes.py` replaced the plan's 82 packs (named after folders) with 31 themed ones of up to 20, each with a title and blurb (Fuse Worms, Magic Dance, Chasms and Swirls, Orbits: Nova, Cell Spirals, Boiling Heat, Ready Classics, Life and Friends ...). Each takes the most varied 20 of its theme (by parameters and name words, one of each file name): 579 patterns in all, the other 818 left under Not in a pack to drag in. The old file is kept as `packs.json.20261024-140213.bak`.
- **"Do the thumbs in advance ... at a low-intensity so that it might be done in 4-6h."** `vticurate.py --prefill DUTY` makes every thumbnail in the background, the packs' patterns first, resting after each so the GPU's busy only that share of the time (conversion, on the CPU at nice 19, doesn't count). 0.35 looked like 11 h with the build still running (~9 s of GPU a pair), so it runs at 0.55, which should take 5-6 h and less once the build's done. Progress: `~/dev/shaderEditor/vtipack/thumbs/prefill.log`.
- **"Make the web app available on my lan so I can use it on phone?"** `vticurate.py --host 0.0.0.0` serves it on the LAN (it prints the address: http://10.1.1.61:8770/); restarted that way, prefill carrying on.
- **Dan's phone showed the curation page empty** (header only). Desktop Chrome at phone width was fine, and the server log had the phone's requests cut off, so the page now shows what went wrong (and reports it to the server log) instead of staying blank, retries loading the packs, and fits a phone's width.
- **Send gave "'rdBrushSize' : no matching overloaded function found" on the phone** - "because the code is in the 'headers' section?" Yes, in effect: the phone's Enki debug (installed 12:29) predates pack format 2, so the shared blocks stayed as `///snippet:` lines - GLSL comments - and their functions went missing. The pack expands correctly as the current app does it; the fix is installing the current debug build.
- **"Go ahead and install it now."** Installed the current Enki debug build (15:52); the phone stayed on the Claude app.
- **Asked why the install didn't ask on the phone first.** His "install it now" was taken as the answer; agreed rule now in CLAUDE.md: a specific, do-it-now go-ahead in the chat skips `status.sh ask` for that one thing; anything more or later asks on the phone.
- **"Could Send also swap the phone to Enki?"** It already does (`add_pack.sh --open`): the event log showed Enki brought to the front at 15:54:35.9, ~6 s after pressing Send in Opera, then Dan opening Recents 3 s later - probably just too quick. He's trying again.
- **"Yep it totally works! Amazing!"** Send from the phone's browser installs the shader and swaps to Enki with it open - confirmed on the device.
- **"A 'the powder toy'-style shader using Enki's offscreen buffers ... a sound generation shader ... based on the actual simulation ... a 2d MPM-solver, so that we can have chunky sand and liquids too."** First version: `tools/samples/powder/powder_mpm.glsl` - reintegration-tracking MPM (one blob a cell, bit-packed; forces A, reintegration B, jelly deformation + palette state C, granular sound D), with Sand, Water, Jelly, Wall, Erase and a brush size in a palette, and an opening scene. Tuned headless with the new `run_powder.py` (frames + WAV): fixed explosions (constant wall/material pushes, a wall "pump"), mass loss on walls, a rounding bias, odd-even row stripes (bigger boxes + bulk damping), and made sand granular (Coulomb stick/slip: a heap stands at ~25 deg, a column slumps like damp sand). Known rough edges: jelly crumbles where it meets sand/water, mixed regions speckle, water forms long strands. Not tried on the phone yet.
- **"Install the .glsl into debug Enki on my phone please"** (after reconnecting on a new port). Added as Powder MPM (id 1038) with add_shader.sh; the phone stayed on the Claude app.
- **Powder MPM feedback: "The sound is a bit harsh ... water ... softer and more 'sloshy' ... sand ... a soft rumbling noise ... jello ... low-frequency-wobble ... The Sim did explode a few times and jello seems to be partly made of dark matter ... long-press on the `x` button clears the whole field. Sand is far too liquid ... perfectly dry sand, but track how wet it gets and become crumbly, and finally slightly liquid when fully saturated ... sand should eventually 'settle out' ... Sand is too active."** Reworked: sound as long grains of band-shaped noise/notes, kept out of the clipping that made it harsh; mass creation at painted (round) walls found and fixed (boxes kept out of walls) - the likely explosions; the "dark matter" was lone jelly cells with runaway deformation, vibrating, plus jelly scattered as dots through water - fixed with a regularised gradient, plastic jelly, jelly grip, a jelly speed cap and keeping jelly and the rest apart; hold Erase to clear; dry sand now follows a Powder Toy rule inside the MPM (settles still, rolls off to open diagonals: heaps ~35 deg), wetness carried in C (darker, cohesive, steep when damp; mud when saturated among water), sand 2.6x water's weight so it settles out. Tested headless (heap, column, jelly drop, sand into a pool, scripted painting stress test). Replaced Powder MPM (id 1038) in Enki debug on the phone (asked there first).
- **"Next time lets increment a version in the name so we can look back on versions of it later."** Noted: new iterations go on the phone as "Powder MPM v3" etc. (v1 = ecd72c31, v2 = 092ca36c, both were "Powder MPM", id 1038, which now holds v2).
- **Powder MPM v2 feedback: "the water seems to adhere to the concrete, let's make it slide off instead ... a yellow outline around the currently selected brush, and add a way to set the brush size ... a cup of concrete ... water and then sand ... it seems to stay very active and the sand never fully settles out ... the jello rendering can sometimes look a bit 'liney'."** v3: water has no grip on walls and walls never pull (a film drains off a slope in ~6 s, where v2 left it clinging); the selected tool gets a yellow outline (v2's ring was drawn under the button face, so never showed); tap the brush button for the next size or drag out from it to any size (a yellow circle while sizing, and round each finger while painting); sand poured into a cup of water now settles (water beside sand keeps a tight box so cells stop flipping between sand and water, settled sand keeps water out, soaked sand slumps, mud only where sand is thin in water: sand speed 0.002 after 20 s vs. 0.008 and still busy); jelly drawn from a wider normalised field, so no lines where it's stretched, and jelly's edges keep tight boxes (it no longer leaks away in the stress test). Added to the phone as "Powder MPM v3".
- **"Something a bit strange about the sand colouring.. it makes straight lines and blocks instead of kinda uniform random sandyness ... Sand in water still seems a bit too active instead of gently falling to the bottom."** Then: **"make it so that jello can't break up and get intermingled with other things so easily.. make it more like a fairly firm rubber."** v4: sand tints were copied from the biggest part landing in a cell (a majority rule: patches grow and get drawn into stripes) - now sand that stays keeps its tint and sand that moves gets a fresh one (neighbours match at chance, 1 in 8). Found an old bug while chasing sand "growing" in water: `unpack`'s `+ 0.5` rounded odd texel values up past 2^23, adding mass to every cell with a big impact value; fixed. Sand in water now sinks at a slow steady pace relative to the water, saturated sand loses its cohesion, settled sand eases to its cell's middle: poured into a cup of water, it settles into a bed under clear water with no trapped pockets. Jelly: firmer shear, plastic only at large strains, shear stress from F's shape only (it had been slowly inflating, ~20% in 13 s), and a short-range pull for thinned jelly or jelly beside water/sand: dropped into water it stays one block with no holes or fraying, on the floor it bounces and keeps its shape. Added to the phone as "Powder MPM v4" (id 1040) once Dan reconnected ("give it a push").
- **"New jello is heaps better! It does sometimes leave particles off on their own ... still a bit too active where it interfaces with water. Can we add something to the water to make it not all the same shade of blue? ... see the movement of the flow inside it ... smooth and not lumpy. ... when sand flies through the air it is still too liquid-like and forms smooth lumps where it should spread out into a spray ... only when it is completely dry ... When sand is slightly wet, I'm still expecting crumbly ... perhaps you could do a crumbly-specific probe?"** v5: wrote a crumbly probe (a standing column, a block overhanging a ledge, a dropped block, at set wetnesses): damp sand was foaming into rings and ending flatter than dry sand. Damp sand now holds by grip and by a support number in C (held up by a column down to the floor/wall, or from the side up to 3 cells when damp), so slightly wet sand (from ~0.1) stands in columns and overhangs and breaks into clumps when dropped; the same support number fixed falling blocks peeling apart row by row. Dry sand in the air sprays (no grip, a small random kick, mass-sized boxes so it doesn't inflate) and is drawn as separate grains where it's thin. Water gets caustic ripple lines carried along by its smoothed flow (flow-map), and no flickering gap where it meets jelly; loose bits of jelly are pulled back harder (strays in the test scene ~halved). Interface churn between jelly and water is only partly better. Added to the phone as "Powder MPM v5" (id 1041; asked there first).
- **"Can we make it so there are both static/fixed rock, and moving rock.. and make long press on the rock button swap it to fixed-rock with a different outline color."** v6:
  - **Rock button:** it now paints moving rock. Holding it for half a second swaps it to fixed rock (the old walls, drawn as bricks, with a blue outline instead of yellow), and holding it again swaps back.
  - **What didn't work:** an elastic solid (unstable past stiffness ~1, clay-like below it).
  - **How it works:** shape matching on rest positions that C carries. A pulls each rock cell toward where the rock around it would put it, turned as that's turned. Rocks that land on each other stay separate, the grain texture moves and turns with the rock, and it knocks in the sound.
  - **Fixed along the way:** B's material pick could round past the last share and turn water into jelly.
  - **Stray jelly bits:** found what caused them. The jelly pull was one-sided, so small blobs held themselves up in mid-air and jelly bounced on water instead of going in. Jelly and rock now pull in equal pairs: jelly goes into water and stays one piece, the opening scene keeps one jelly (v5: three pieces), and rock sinks to the bottom of a pool. Sand now feels the weight of rock on it, so a rock rests on a sand bed rather than sinking through.
  - Tested headless only: floor, heap, pool, sand bed, and a painting stress test.
- **"I'd like the water shading to represent the velocity and pressure of the liquid ... still have some amount of the caustic-y noise but maybe minimal ... make the sand have a color even when emitted and make it vary between where it currently is, and darker, and brighter.. have the sand particles carry this color. Jelly could have a smooth blobby shading ... a little specular highlight ... a simple daylight lighting environment for the whole scene, so everything gets a smooth normal and a bit of lighting."** v7:
  - **Daylight:** a sky backdrop, and every material lit by sky and sun from a smooth normal: the metaball field's analytic gradient, eased so each material is flat on top and rounded at its edges.
  - **Water:** a deeper blue the harder it's pressed (its depth plus how over-packed it is), paler and whiter the faster it flows, with a sun glint along its surface. The ripples are now faint.
  - **Jelly:** a smooth, glossy dome with a specular highlight. C relaxes a height field over each blob, and jelly's F is now stored as an angle and a unit stretch to make room for it. Jelly drops and the stress test behave as in v6.
  - **Sand:** each grain carries a shade, dark to bright, in C from the moment it's painted. A cell takes one landing grain's shade, picked at random by mass, so the colours travel with the sand and don't fade.
- **"the Jell-O has a rough edge when it's placed ... a smooth edge like concrete has but concrete could have the rough Edge instead and concrete seems a bit liquid like ... use the gradient of the concrete Colors to shade like a bump map the concrete is also too active and keeps jumping around ... the jello seems to be able to be compressed by having lots of concrete on top of it ... if we fill up the whole screen of jello it forms bubbles underneath ... concrete sound to be much baseier and more more noisy ... make the paint paint on top of existing materials instead of avoiding them."** v8:
  - **Painting:** paint now covers whatever's there, except walls. Jelly and rock fill the brush and are held where they're drawn until the finger lifts, so each stroke is one piece. Rock's brush edge is rough, eaten into by noise.
  - **Jelly's edge:** it went frilly as soon as it moved. Any tension at its surface clumped the edge. Its volume is now tracked from its own deformation (J), with cohesion only inside it: a dropped blob stays a smooth glossy ball.
  - **Jelly under load:** under a pile of rock it keeps its volume (density ~1.0; v7 was ~2.1). A whole screen of jelly no longer breaks into bubbles at the bottom: that was a checkerboard instability, fixed by spreading packed jelly a little more.
  - **Rock:**
    - A resting rock now comes properly to rest. It had been churning under its own weight for ever.
    - Different rocks no longer merge cells, which had flung bits off.
    - Each rock's rotation is evened out across it, so big rocks don't bend like dough when they land.
    - It's drawn with a bump map from its own colour pattern: pebbles, pits and grain.
    - It sounds much deeper: 83% of its sound is now below 100 Hz, was 12%.
  - **Known:** mass still swaps slowly at boundaries between materials. It averages out but wanders, e.g. ±20% for a small rock in water over 40 s, as in v7.

## 2026-10-25

- **"let's wrap up for today and close any processes, and summarise where we are at"** Stopped the VTI curation server (its picks are saved as they're made, in `vtipack/packs.json`) and the Gradle and Kotlin daemons. Moved the Powder test scripts out of /tmp to `~/dev/shaderEditor/powder-tests/`. The phone is as found, with the Claude app on screen and stay-awake off. Powder MPM v8 (id 1044) is on the phone and still to be tried there; the next one is v9.
- **"Can you start the curation server again and let me know the url for phone?"** Restarted vticurate (with the thumbnail prefill) at http://10.1.1.61:8770/.
- **"can we do a new debug build and test the new phone-testing skill? ... is it possible for the user to ever lose what they have typed into the 'clarify' subwindow"**
  - **Lost text:** yes, in several ways: a tap outside the box, Back or Cancel, the box being recreated, a clarification sent after `rate` stopped waiting, and messages sent at the end of a run. All fixed: what's typed is kept as a draft until it's sent.
  - **From Dan's notes during the test:**
    - The panel steps out of the box's way while it's open.
    - A box left by going Home is kept, and the panel's 💬 button shows ✏️ until it's finished.
    - A dim "thinking" line ticks when the panel's quiet.
  - **Panel stuck on screen:** the phone dropped off adb mid-test and the panel stayed up. It now has ✕ to close it, and it takes itself away after 2 quiet minutes instead of 5.
  - **Stop carried over:** the retest was stopped at once by the old run's leftover Stop. `ask` now starts each run afresh.
  - **Status:** the new build is on the phone, but the retest was cut short at Dan's request and is still to do.
  - **Skill copy:** the skill's own copy in `~/.claude/skills` needs these changes carried over.
- **"I have wanted to attempt a rocky cliff face shader for some time ... deep cracks with occlusion shading, faithful rock-coloring and angular features, and a hint of vegetation at the top ... pick up the pointer handling from the PointerDiagnosticsXformTracking shader"** Rock Cliff v1 (`tools/samples/cliff/rock_cliff.glsl`, id 1046 in Enki debug).
  - **Gestures:** XformTracking's drag, fling, pinch and turn, anchored at the fingers.
  - **The cliff:** a height field of strata, joints and angular facets, with stains and moss. Grass and conifers along the top.
  - **Lighting:** soft marched shadows and ambient occlusion. The cliff and its lighting are recomputed only when the view moves.
  - **On the phone:** checked by screenshot that it renders right. It runs at 90 fps when still and about 11 fps while moving.
- **"corners look too sharp ... smoothing them slightly and adding a bit of noise ... trees darker like pine trees, more of a silhouette, and the grass a bit more of a noisy fuzz ... only grow upwards ... grass could grow on green upper surfaces everywhere including halfway down the cliff"** Rock Cliff v2 (id 1047).
  - **Edges:** rounded slightly, with noise.
  - **Plants:** dark pine silhouettes, and fuzzy grass that grows only upward, along the top and up from every ledge.
  - **Moss:** on any rock that faces up.
  - **On the phone:** renders at 92 fps when still.
- **"drop the res to 1/4 maybe.. this whole crevice went black ... ditch [the trees] ... Go on with your other plans ... fix that open-enki-with-new-shader bug"**, and mid-way: **"a very fine/subtle stone like noise on the rock"**. Rock Cliff v3 (id 1048, at 1/4 quality).
  - **Rock:**
    - Crevices keep a little light.
    - Less regular strata and fewer hairline fractures.
    - Stronger stains.
    - A shelf along the top, and fine mineral grain.
  - **Trees:** removed.
  - **Shadows:** grainy soft edges fixed by smoothing them in a new pass.
  - **Open-shader bug:** fixed. The launch intent's open request is now honoured on a cold start, and `add_shader.sh --open` uses it. Tested on the phone: v3 opened straight from a cold start.
- **"I'd rather this was always a sub-pixel-sized effect ... use a gradient noise ... glints ... as if there were tiny crystals ... zoomed really far out ... we need some lower frequency variation ... (but try not to distort the nice straight lines)"**. Rock Cliff v4 (id 1049): the grain is gradient noise with only near-pixel octaves blended in (from the pixel size), so it never becomes blobs; pixel-sized crystal glints; all noise now gradient noise; slow region noise varies relief and overhangs, joint density, tone, rust, stains and moss without moving any lines, plus broad light/dark zones and long water streaks. Checked headless at normal, x20 and x0.1 zoom, and on the phone (65 fps settled).
- **"sparkles actually do fade out a bit when zoomed right out ... less intense ... diffraction/thin-film style colors ... halation and eyelash-aperture-like spiky flares ... vary the overall cliff-block-size a lot ... without introducing additional bending"**. Rock Cliff v5 (id 1050): glints fainter, fading far out, thin-film tinted; Buffer C stores them and D (cached) adds a warm-edged halo and uneven mostly-vertical spikes; row heights vary up the cliff via a monotonic stretch, blocks split in halves by straight lines in fine regions and merge in coarse ones. Also fixed one-pixel creases seen zoomed in (facets() blended only the two nearest cells and its wobble was one-sided). Checked headless and on the phone (64 fps settled).
- **"I don't see v06 in Enki debug.. can you try to push it again?"** There's no v6 yet; v1-v5 (1046-1050) were all in the debug library. Re-sent Rock Cliff v5 (replaced in place) and opened it in Enki debug.
- **"There were some leaks detected on that last run, can you look into that?"** (a LeakCanary trace). It wasn't from that run: the trace is analysis 11 in the debug build's leaks.db, from 2026-10-16 02:24; all 18 there are the same MediaProjection -> destroyed MainActivity chain (Oct 14-18), none since. One came after the earlier stop() guards, so the projection is now made from the application context's MediaProjectionManager, leaving it nothing that reaches an Activity.
- A heightfield/mesh-resolution request turned out to be meant for another Claude session; nothing done.
- **"some noises that are a bit too lumpy and can also run out of octaves.. would be good if they always looked rough. The moss/grass is a little too luminous ... sparkles ... I'd prefer they were isotropic ... a bit broader but much lighter intensity.. slightly more chromatic"**. Rock Cliff v6 (id 1051): new rough() relief (creased gradient noise down to pixel size at any zoom), fbm() adds octaves as the view zooms in; darker moss and grass; eight even flare spikes, a broader fainter halo, stronger thin-film colour shifting outward. Added in the background, not opened: Ultra Iterator's screen-capture dialog was up.
- "The glints are still too strong.. this shader doesn't automatically recook the result when the
  parameter values change.. can we maybe make it detect changes there and trigger a recook?" -
  Rock Cliff v7: B, C and D now keep a `settings()` key (every define boiled down to a vec4) in
  their top row next to the view, so a define changed in Edit Live (a uniform then) recooks them.
  Glints turned down (GLINT 1.4 to 0.6, HALO 0.035 to 0.02, SPIKES 0.03 to 0.018). Added to Enki
  debug as "Rock Cliff v7" (1052).
- Asked whether Play's user-generated content policy means Enki needs moderation now it renders
  image and video files. Answered (not legal advice): no - Play's UGC is content visible to other
  users through the app, and Enki's files stay on the device (Share goes out through other apps; no
  INTERNET permission). It would apply only to in-app sharing such as a gallery or server.
- Dan may later let people share packs by saving, sending and loading them, outside the app.
  Agreed that's still not UGC: Install from File already works that way, like any app that opens files.

## 2026-10-26

- "phone-testing tends to leave my phone in autorotate mode.. can you see the code that might be
  doing that?" - No script did it: rotation tests put it back with hand-typed
  `settings put system accelerometer_rotation 1` (auto-rotate on), whatever it had been. Now
  `status.sh ask` saves both rotation settings and `done` restores them (Enki's copy and
  `tools/phone-kit`); CLAUDE.md says not to restore them by hand.
- "yes copy it to the skill, and do stay-awake too" - `ask` now also saves stay-awake
  (`stay_on_while_plugged_in`) and `done` restores it; copied status.sh and README into the
  phone-testing skill repo (with Dan's OK) and updated its SKILL.md hand-back rules.
- "phone's free, test the rotation and stay-awake restore" - Tested on the phone (found locked
  portrait, stay-awake off: `0 0 0`):
  - Changed to `1 1 15`; `done` put back `0 0 0`.
  - Preset `1 0 3`, changed it, then a second `ask` as if the first run had dropped; the saved
    values were kept and `done` restored `1 0 3`.
  - Settings put back to `0 0 0` and Settings reopened. `settings put global` works from adb shell.

## 2026-10-27

- "Do we have any outstanding threads to pull on? I'm keen to get back to that physarum shader ...
  making the pheromones dissipate more slowly ... any advice?" - Listed the open threads. Rebuilt
  the 19 Oct physarum and Slime Cartographer sources from the session transcript into
  `~/dev/shaderEditor/physarum/`, since the scratchpad copies were gone; the phone's copies are
  the real ones, so they'll be pulled from there first. Advice given: separate diffusion from decay
  (every step is a full 3x3 blur), slower decay with lower deposit, sensor and turn angle ratios,
  `///steps`, more agents per pixel, several species, settings as defines for Edit Live.
- "that does sound bloody cool!" (phone free, 10.1.1.198:46075) - Read Enki debug's library
  (the release build isn't readable): its newest Slime Cartographer is "Restore Test" (904). Built
  v2 from it:
  - partial diffusion (DIFFUSE 0.1);
  - slower fade (0.03);
  - brightness scaled by the fade;
  - `///steps:3`;
  - a fresh colony's settings as defines, after a desktop sweep (sensor distance 0.007, turn 0.8).
  Fixed `run_powder.py` missing `;///buf:` channels. Added to Enki debug as "Slime Cartographer
  v2" (1053): 23 fps at 1/4 quality. Saved as `tools/samples/physarum/slime_cartographer.glsl`.
- "let's wrap up and keep it for tomorrow" - Wrapped up. Next time: Slime Cartographer v3 with two or three
  species (each follows its own trail channel, avoids the others').

## 2026-10-29

- "Take some inspiration from Softology [physarum-simulations post] ... an offline search for
  similar settings that we can then add to the shader as presets that we can switch between?" -
  Slime Cartographer v3 (`tools/samples/physarum/`):
  - **Model:** a trail spread with a 1-5 px radius, turning jitter and a spawn rate, all held in
    settings pixels.
  - **Presets:** a preset row in the display (tap its left or right half); the table and names
    are generated by `make_presets.py`.
  - **Search:** downloaded the post's 17 pictures, which give no numbers. `search.py` runs
    candidates headless, 600 random ones plus probes for hatching, curls and rings, and scores
    them against the pictures. Picked 14 presets by eye: Lace, Cells, Veins, Web, Weave, Fibres,
    Rivers, Stars, Glow Worms, Maze, Worms, Hairs, Spots, Fingerprint.
  - **Fixed:** `run_powder.py` now filters and wraps channels as the app does.
  - **Not done yet:** the post's three-species colour pictures. Not on the phone yet: Dan hasn't
    said it's free.
- "wrap up and keep your notes ... the scratch area is cleared when I reboot" - Wrapped up. All
  physarum search data, renders and Softology's pictures are already under
  `~/dev/shaderEditor/physarum/`; the scratchpad held only an early v3 draft. Next time: v3 on
  the phone, then species.
- "phone is here" - Slime Cartographer v3 (1054) on the phone. All 14 presets look as on the
  desktop, at 47-85 fps (1/4 quality). Once, stepping Stars → Glow Worms left it stuck: a solid
  yellow speckle, the row reading LACE, with Lace's settings, and taps doing nothing (as if a
  fresh colony were seeded every frame) until Rewind. It didn't happen again on the phone (Maze
  → Glow Worms, Stars → Glow Worms), nor replaying the nine taps headless on the desktop.
  Screenshots: `~/dev/shaderEditor/physarum/phone/v3/`. Cause not found yet.
- "can we make single-tap show/hide the menu, and make the menu sliders draggable ... separate
  opacity control for each [particles and pheromones] ... an overall 'scale' (whole effect)
  control?" - Slime Cartographer v4: a tap toggles the menu, food only once a touch isn't a tap;
  bars set where touched and dragged (the 2/3-finger gestures are gone); new bars for trail
  opacity, agent opacity and scale (above 1 a smaller colony magnified, below 1 distances
  shrunk). Tested headless with scripted touches; not on the phone yet.
- "phone's here" - Slime Cartographer v4 on the phone (1055). The menu tap, touching and
  dragging bars, both opacities, and scale at 2.5 and 0.3 all work there, at 54-67 fps. Put it
  back to defaults after. Screenshots: `~/dev/shaderEditor/physarum/phone/v4/`.
- "add the dark band behind the menu ... gaussian filter for the pheromone ... something that
  changes the filtering domain sizes and the spacings of the sensors and the deposition ... so
  that the sim can 'really' run at a higher res ... I also ran into the yellow-out issue ...
  color presets ... greyscale ... a gamma slider" - Slime Cartographer v5: two-column menu over
  a dark band; zoom (v4's scale) with B-spline smoothing; scale now a coarse agent grid under a
  full-resolution trail (scaling every distance with one agent per pixel changed the pattern:
  agents pack veins about one a pixel), tied to the height so higher quality gives a finer
  trail; 10 colour schemes incl. Grey and Paper; gamma. Yellow-out: fresh colonies no longer
  key off iFrame and B drops NaN, cause still unconfirmed. Also fixed run_powder.py losing long
  unsynced runs on the desktop GPU.
  On the phone as Slime Cartographer v5 (1056): the two-column menu, Paper and Grey, scale 2
  (54 fps) and smoothed zoom 2 (74 fps) all work; bars put back to defaults after.
- "can zoom zoom around the center of the screen? Also let's make the default coloring Paper
  and turn the agent opacity to zero." - Slime Cartographer v6: a zoom change moves the
  colony (agents and trail) by half the patch's change, so the middle stays put (checked
  headless: the zoomed view matches the old centre, not the bottom-left); fresh colonies are
  Paper with agent opacity 0. Not on the phone yet.
- "there we go" (phone) - Slime Cartographer v6 on the phone (1057): starts in Paper with no
  agents at 57 fps; Dan gave zooming about the middle a thumbs up. Zoom put back to 1, phone
  back on the home screen. Screenshots: `~/dev/shaderEditor/physarum/phone/v6/`.
- "play some kind of chime for the thumbs-up/down question too as I often miss those.. make it
  sound inquisitive" (meant for another session, but "you can do it") - `Chime.wondering` in
  Enki debug and tools/phone-kit, played when `rate` asks: tuned on the phone from a three-note
  bend to a 0.2 s low chirp rising C4 to G4 (thumbs up). The phone-testing skill's own kit
  still needs the same change (outside ~/dev/shaderEditor, so not done here).
- "go ahead and copy it to the skill's kit" - copied the chirp (Chime, TestStatusReceiver,
  README) into ~/.claude/skills/phone-testing/kit and committed it there (b212dae).
- "let's do multi-species now.. maybe 3 initially each with their own color ramp setting..
  would like to be able to go back to 1 reagent.. could number of reagents be a slider that
  redoes the ui only when released" - Slime Cartographer v7: up to 3 species (default 3), each
  with its own colours bar; a SPECIES bar that takes effect when the finger lifts (the menu's
  rows change then; agents dealt species afresh); AVOIDANCE (how much each shies from the
  others' trails); new Rust and Ink schemes (Paper/Rust/Ink by default). One species is the old
  colony. Checked headless: three species weave bands, dragging species to 1 changes the menu
  only on release and goes back to Lace.
- "chuck it on phone now" - the Replace/Ignore prompt came up for v7 though no shader had that
  name; Dan ignored it to keep v6. Fixed: ShaderImportReceiver asks only when the name exists
  (add_shader.sh reports accordingly). Reinstalled Enki debug (asked first), and v7 went on as
  1058 alongside v6, open at 56 fps with three species.
- "make the menu take up 3/4 of the screen ... tap on the color ones cycle through all the
  ramps ... soften out the look of the pheromones when they have just had sharp trails
  deposited ... can [Scale] scale from the center too?" - Slime Cartographer v8: menu rows
  spread up to 3/4 height; tap on a colours bar = next scheme; SOFTEN bar (display only: ridges
  above the mean of a ring of samples are eased down, so fresh thin trails blur and veins stay
  crisp; no extra buffers); scale about the middle like zoom (agents shifted, trail resampled).
  Checked headless (scale: centre correlation 0.34 vs 0.007 for the corner). Not on the phone yet.
- "maybe pheromone deposit radius should be a thing?" - DEPOSIT RADIUS bar (left column, 0-2
  cells): agents lay a normalised tent that wide instead of one pixel; 0 is as before. Renders
  at 0/1/2 show softer fresh trails and smoother, slightly bolder veins.
- "chuck it on the phone" - Slime Cartographer v8 on the phone (1059), next to v7, open with
  the 3/4-height menu showing.
- "merge it to master" - squash-merged UltraRestArea (276 commits since 21 Oct) into master as
  0cd2ec4d and pushed; master and the branch now have the same tree. No docs-site changes, so
  no docs deploy to check.

- Dan wants Enki on the Play Store as a free app, with paid shader packs later: answered with the checklist (bundles already build with `make bundle`; the 14-day closed test for new personal accounts; Play App Signing; the Console forms; credit for the fork; licences when curating packs; billing in a later update).
- He'll do internal testing first and handle the Console himself, and asked for the bundle and release notes: bumped to 2.37.0 (versionCode 96), with a CHANGELOG entry and `fastlane/.../en-US/changelogs/96.txt` (383 characters, under Play's 500). The signed bundle needs his keystore passwords (`~/.android-signing/enki-shader-editor-release.jks`), which turned out to be in `~/.android-signing/enki-shader-editor-release.env` (made 2026-10-14): `source` it, then `make bundle`. Built and signed (lint passed, no debug kit inside); a copy is at `~/dev/shaderEditor/release/enki-2.37.0-96.aab`. Explained keystores and upload keys. Also told him where Create app is in Play Console.
- "can we make sure you pick up that env source next time": CLAUDE.md (release/bundle commands) and AGENTS.md §13 now say to `source ~/.android-signing/enki-shader-editor-release.env` before `make bundle`, and where to copy the bundle.
- Dan asked for a shader with the fast, infinite-seeming zoom of KPT's Fraxplorer ("perturbation is probably what KPT used.. let's try that!"). Wrote `tools/samples/fractal/mandelbrot_dive.glsl` v1. It reuses last frame's pixels by reprojection and redoes only those that have drifted (about 12% a frame while zooming). It uses perturbation against a double-float reference orbit in Buffer A, with Zhuoran's rebasing, and goes to 2^-44 a pixel. The gestures: pan/fling, pinch, and taps to auto-zoom in or out or reset. KPT-style gradients with cycling and an emboss. Fixed GPU FMA fusion and constant folding that broke double-float. Checked against long double headless.
- "Yeah go ahead please": Mandelbrot Dive v1 went on the phone as 1060. It runs at 83 fps in the editor at quality 0.25. There's no red bar, so double-float works on the phone's GPU. A tap dived about 14 octaves into Seahorse Valley in 20 s and stayed sharp. Dan gave a thumbs-up to leaving it running to play with.
- Dan, after playing with Mandelbrot Dive v1, said it slowed down with inside regions on screen. He asked to be able to swap to Julia sets, to drop the resolution and multisample more ("I prefer that look to sharp and sparkly"), and for some control over the noise. v2:
  - A pixel whose four reprojected neighbours were inside stays inside (the set has no holes). There's also Brent cycle finding, with tolerances kept tight because loose ones called 6% of deep outside points inside, plus a direct test for the two big blobs. A minibrot dive is 24% faster than v1 despite more refining.
  - A long press switches to the Julia set of the point under the finger, and another switches back. It uses a second reference orbit (of 0) for rebasing, and is checked against long double at 2^-25.
  - Jittered samples are averaged in a new Buffer C, carried by Catmull-Rom.
  - Distance-estimated `EDGE` colouring, plus `COLOUR_POWER`, `SOFTNESS` and `SAMPLES`.
- "throw it on": Mandelbrot Dive v2 went on the phone as 1061 at quality 0.2. It runs at 93 fps in the editor, with no red bar, and looks soft and smooth fullscreen. It's left up for Dan to play with.
- Dan: "Stunning!" It took him a while to see that a tap zooms in. It stops after a while: "I thought this technique could be pretty much extended indefinitely if we allow a small amount of error?" He also wanted somewhat more base iterations for the Julia sets ("not too much!"). v3:
  - Explained that depth needs the reference finer than a pixel (its error grows like a pixel's difference from it). The reference and the view's middle are now in 128-bit integer fixed point (eight 16-bit limbs) instead of double-float. That goes to MAX_OCTAVES 100 (about 10^30), against 36 before; float's exponent ends near 2^-126.
  - Checked against an exact integer render: median error 5e-4 at 2^-60. At 2^-90 the median is 0.013, with scattered chaotic pixels matching a CPU float32 simulation.
  - MAX_ITER 300 -> 450. Taps up to 0.35 s. AUTO_ZOOM 1 octave a second.
- "Yeah do it": Mandelbrot Dive v3 went on the phone as 1062 at quality 0.2. It showed 23 then 32 fps, against v2's 93 earlier. After asking on the phone, I tried three stripped copies (1063-1065, "zz dive test 1-3"): without the Image self-test it ran at 38 fps, without the reference work 39, and with Buffer A only copying itself 40. So Buffer A wasn't the cause. v2 measured then also gave 41 fps: the phone had slowed (battery 30%, 37-38 °C), and v3 is as fast as v2. v3 is left fullscreen for Dan. The test copies can be deleted.
- Dan: "Damned amazing! Could zoom for ages before precision ran out! Would it be possible to swap to one of those other fake schemes when that happens?" Answered yes, with a recommendation: a minibrot handoff. Near the limit, find the nearest minibrot (its period from where the reference orbit comes closest to 0, its exact centre by Newton's method in fixed point) and steer onto it. As it fills the screen, cross-fade to the whole set at the matching size and angle, and carry on. A dissolve to a new dive would be the fallback for Julia sets or when no minibrot is found; Misiurewicz loops would only suit a showpiece mode. Asked whether to build it as v4.
- "yeah give that minibrot mode a go for v4 and let's see how it looks!" Mandelbrot Dive v4 goes on for ever:
  - Diving in, Buffer A looks for the minibrot whose atom domain the view's middle is in. A period is an iteration where the reference orbit comes nearer 0 than ever before. Newton's method in fixed point finds the nucleus, then Heiland-Allen's estimate gives the size and angle. It takes the first minibrot that's still ahead and at least 2^-70 deep. Ball-arithmetic periods over the whole view gave periods Newton couldn't follow, so they were dropped.
  - The zoom glides onto where the next tour place sits on that minibrot. When the minibrot fills the screen, the view swaps to the whole set there, turned to match, and the colours dissolve from one to the other.
  - The tour places are Misiurewicz points, which have ever smaller minibrots round them, so the next dive meets one. With no minibrot, or on a Julia set, it dissolves into a new dive at MAX_OCTAVES.
  - The view now has an angle, and two fingers twist it.
  - Checked headless:
    - A dive from a Misiurewicz point hands off at 2^-71.4 and carries on.
    - Accuracy is the same as v3 (long double: median 5.6e-5), and so is the cost.
- "I'm concerned about taps that don't land near the edge, and can we make it so that somehow we always zoom with a bit of movement towards the 'set edge' so that we never land on a flat bit?" Mandelbrot Dive v4 now drifts to the edge while zooming in. Each frame Buffer A reads last frame's distance-to-edge estimates on a 15 x 15 grid and moves the middle 3% of the way a frame (screen-relative) to the nearest escaped point near an edge. It's left alone within 2% of the screen of an edge, so the tour places and minibrot glides stay on course. With all inside, it backs out until an edge shows. Checked headless from far outside (two spots), from inside the cardioid and from inside the period-2 bulb: all four end up diving into detail. The Seahorse start and the Misiurewicz handoff (2^-71.4) are unchanged.
- "give phone a go now": Mandelbrot Dive v4 went on the phone as 1066 at quality 0.2. It compiled with no red bar and runs at 46 fps in the editor. It's left fullscreen on the whole set for Dan to try the dive, the edge drift and the handoff.
- "very nice! let's wrap up for today, more shader fun tomorrow!" Wrapped up for the day. UltraRestArea is pushed; the merge to master is still to do.

## 2026-10-30

- "I'd like to start a new shader... looking out of a car window at the edge of the road while driving fast... Australian roads and dirt/vegetation... Red rocks, red dirt, bitumen, spinifex grass, road markings" (no car interior yet; a refracting wound-down window later). Outback Window v1 (`tools/samples/outback/outback_window.glsl`), one Image pass:
  - A sideways view at 110 km/h with motion blur worked out exactly, no samples. The ground's noise is box-filtered along the road (streaks), and plants, rocks and posts are cut-outs made of horizontal spans, so blur is how much of a span the pixel's stretch of road covers.
  - Lane chip seal, edge line, dusty shoulder, red gravel, red dirt with pebbles. Spinifex hummocks with golden needles, saltbush, mulga and desert oaks, ghost gums, termite mounds, red boulders, guide posts with red reflectors, ground shadows, red mesas far off, deep blue sky.
  - Framing works in portrait and landscape. About 3 ms a frame at quality 0.25's size on the desktop GPU.
- "throw it on the phone": Outback Window v1 went on as 1068 at quality 0.25. It compiled and looks as the desktop renders do, but runs at only 24 fps in the editor (about 3 ms on the desktop's GTX 960). It's left fullscreen for Dan.
- "make it faster for v2 if possible without reducing the fidelity of the plants" plus a wishlist: 3D mountains, straights and corners, varied edge lines and turning markings, plant shadows, day/night with an in-frame sunset and moonrise, potholes and rough edges, three-finger time scrub with an eased pause, suspension, coloured gravel, a wandering dirt edge, weeds, power poles about 10% of the time. Outback Window v2, all built:
  - A new state pass (clock, scrub, spring suspension) and a table pass that works out what stands in each cell once a frame, instead of once a pixel.
  - Bends with straights between; edge lines solid, worn or dashed, with turning lanes and arrows; potholes and bitten seal edges on rough stretches; chip seal made of packed stones; coloured gravel; buffel and weeds; poles on about 12% of the loop, starting at the very beginning.
  - Ray-marched ranges (mesas, ridges, gullies); the sun sets in front, the moon rises soon after, stars come out; smoother plant lighting and denser needles.
  - From 6.3 ms on the first pass of v2 to 3.2 ms (v1: 3 ms) at 360x772 on the desktop.
- "yes please" (put v2 on the phone): Outback Window v2 went on as 1069. It compiled and runs at 32 fps (v1: 24), and was left fullscreen.
- Outback Window feedback: power lines without poles; "pull over" when slowing for three fingers; gravel colour at full speed; suspension flipping when scrubbing fast; plants floating; "occasional quite sharp corners that require the viewer to slow down", or stop signs; trees "a bit blobby" - want dark branching trunks, some bare, darker, less blobby leaves; "a quite-rare glint from the terrain" with a spiky flare, from the sun/moon, about every 10 s; "also depth haze". Outback Window v3, all done:
  - Wires only between two poles; three fingers pull the car over onto the gravel (rougher there) and back out; coloured stone streaks at speed; suspension targets and state clamped; plant contact shadows found from both rows and darken the sunlight too.
  - Sharp corners (radius about 50-80 m, 50-85 degrees) every few stretches, the car braking to about 55 km/h; crossroads with a stop sign, a stop line and a track off into the bush, waiting 3 s at the line.
  - Trees rebuilt from branches: trunk, limbs and twigs with ragged leaf clumps; mulga, darker desert oak, bare dead trees, ghost gums.
  - Glints: Buffer A finds mirror shards in the dirt that catch the sun or moon, about one every 6-15 s by day, drawn as a spiky flare. Warm dust haze nearer.
  - About 4.1 ms a frame on the desktop (v2 3.2).
- "throw it on the phone" (new port 37201): Outback Window v3 went on as 1070 but runs at 0.5 fps, while v2 (1069) still runs at 28. Bisected on the phone with test copy 1071: the slowness is in v3's Image pass, but not in the glint search, trees, crossroads, pull-over offset, dust haze, gravel colour or contact shadows (v2's Image with v3's Common and buffers runs at 21). Dan needed the phone back; one-change-undone variants of the last slow copy are ready in `~/dev/shaderEditor/outback/bisect/` (setLight's out-parameters, the extra texel reads, the ground's crossroads code, the pole/post checks, tree extents) for next time.
- "phone's free again, keep bisecting": none of the five one-change undos helped, but putting v2's tree() back into the last slow copy took it from 0.5 to 23 fps. The new trees' leaf clumps did it: with no leaves it ran at 4.2 fps, with no limbs at 0.5, and with no branches at all at 10. Leaves now wobble with sines instead of noise (no hashing in nine clumps a tree), and the branches are skipped below the fork and when far from the pixel. They look the same on the desktop. The phone got hot (thermal status 3, v2 down to 6.3 fps), so the later numbers are skewed, and the cheaper trees haven't been timed on it yet. Handed back to chompSMS.
- "phone's cooled down, test the cheaper trees" (then "maybe I need to turn off power saving"): the cheaper trees still run at 0.5 fps (v2 9 fps as a control, the phone still at thermal status 3, battery about 45 C on the wireless charger; battery saver was already off). The phone is an Adreno 730, and logcat shows no GPU faults. One limb instead of three, tips only, and the branches written as straight calls with no loops all stay at exactly 0.5; a bare trunk runs at 10. So any branch code in tree() at all trips some compiler limit, however little work it does. Variants are in `~/dev/shaderEditor/outback/bisect/`; the shader is as committed. Handed back to chompSMS.
- "phone's cooled down, test the cheaper trees" / "try the tree atlas idea... room for normals... 2d SDFs for the silhouette even?": the trees now come from Buffer C, an atlas of 18 trees (mulgas, desert oaks, dead trees, ghost gums). It's drawn once with branches, twigs and gappy clumps of leaves, storing coverage, colour, normals, a leaf fraction and the outline's distance (an SDF, stored but not used yet). Then its rows are summed, so the Image pass gets a pixel's blurred stretch from two filtered reads per band, and the motion blur stays exact. The trees look about as v3's did, mirrored for variety. On the phone, cool and off the charger: atlas trees with 16 texelFetches a tree ran at 1.6 fps, 4 filtered reads at 3.5, and with the atlas capped at 96x200-texel tiles (instead of screen-sized) about 14, the same as v3 with no trees at all (v2: 25). Later readings jumped about (3.6 to 18 for the same shader), so the rest of v3's extra cost (gravel colours and the flare looked like a couple of fps each) needs a steadier way to time. Handed back to chompSMS.
- "make the gap between bush and shadow smaller... make bushes like trees, using the atlas, and have some sticky with no leaves, and some with darkish leaves... the shadow... a bit softer and not so black"; "there are still floating wires that are missing their poles"; "Trees also definitely need some noise in the trunks, more as we get further from the root":
  - The wires were half a span out: they peaked midway between poles and sagged at them, so they climbed to poles that weren't there. Fixed on its own.
  - Shadows take 0.7 of the key light (was 0.85) and soften with the height of what casts them (`SHADOW`, `SHADOW_SOFT`). The darkening round things' feet is stronger and starts right at the base, so they sit down.
  - Bushes come from the atlas now: six tiles after the trees (two grey-green saltbushes, two darker-leaved, two bare and sticky), each five stems fanning from the root with twigs and leaf clumps. The atlas is 6 x 4 tiles.
  - Every trunk, branch and stem wanders by two octaves of noise, nothing at the root and growing to `WOBBLE` at the top.
  - Checked on the desktop only; not yet on the phone.
- "chuck it on as v4": Outback Window v4 went on as 1072 and was left fullscreen. It read 3.6 fps 12 s in and 19 fps 30 s in, so it starts slow and settles; that explains the jumpy readings in the bisecting, which were all taken at 10 s. v2 was 25 at a cool 10 s reading.
- "the low bushes are very sharp and straight at the base... a small branching trunk inside them"; "the distant mountains are very pixelly, could they analytically antialias themselves"; "most [stars] 1/2 as bright, keeping just a handful"; "Shadows under bushes still seem a bit strong"; "Glints... a little too bright at night time":
  - Spinifex hummocks now tuck in over the bottom half, with thinner foliage there, and four woody stems fork out from the middle and show through it.
  - The ranges' march keeps the most the skyline stands above (or below) the ray, in pixels, and the ranges cover the pixel by that, blended over the sky. Just-missed rays are lit at the skyline's point. Mesa tops and ridges are smooth at quality 0.25; steep cliff sides still step a little.
  - Stars: all but about the brightest 1.5% are half as bright.
  - Shadows take 0.6 of the key light (was 0.7); the darkening round plants' feet is back down (0.55).
  - Glints by moonlight are `GLINT_NIGHT` (0.35) as bright.
  - Desktop only so far.
- "Yeah please do" (v5 on the phone): Outback Window v5 went on as 1073, read 17 fps after 30 s (v4: 19), and was left fullscreen.
- "the bushes are all a bit bright, could they vary more in colour?"; "the bush shadow (including self-shadowing) doesn't seem aware of the sun position, could it be? without needing to raytrace?"; "when 3-pointers are down, let's limit how slow the car can go.. so it's still always a little bit motion blurred":
  - Each bush and spinifex hummock gets its own green (`plantTint()`): up to 45% darker, bluer or yellower; trees get just the hue shift, on their leaves.
  - Hummocks are lit by their dome's normal at each slice (`domeSpan()`), so their shadow side turns with the sun (the atlas plants already were, by their stored normals). The darkening round plants' feet moves away from the key light, more as it's lower.
  - Three fingers now ease the car down to `MIN_RATE` (0.15: about 16 km/h, still blurred) instead of a stop, fully pulled over. Stop signs still wait 3 s at a standstill.
  - Desktop only so far.
- "yes rolling stops, then put it on as v6": stop signs are rolling stops now - the car brakes to the three-finger crawl (`MIN_RATE` of full speed, 4.6 m/s) at the line and picks up again, no waiting (`STOP_WAIT` gone). Checked headless through the stop at 14500 m.
- Putting v6 on: the first v6 (1074) ran at 3.4 fps against v5's 16 on the same test copy. Lighting the hummocks by their dome's normal tipped the phone's compiler over again (without it: 16-25 fps; without the leaf tint instead: 15). Cheaper versions didn't help: four precomputed lights blended gave 1.6 fps, and just darkening by the light's height gave 2.6. So the hummocks are back on span()'s shady-to-sunny lighting; the tints, the shifted foot shadows and the rolling stops stay. The Image pass is right at the Adreno's cliff, so any change to the row loop needs checking on the phone.
- v6 (1074) replaced with that build: 16 fps after 45 s (v5 on the same copy: 16), left fullscreen.
- "a bit of a simple graphical bright 'rim' on the sun-side of the bushes"; the verge "could vary continuously in width and have stripes (aligned with road) of different colours like sandy and greyish, and the odd lighter edge"; white lines undulating "from their positions on the road and also a tiny bit of car drift"; "about 10x as many bright stars as there should be"; "The bushy stuff is still too bright and the shapes to simple and blobby. I'd like to replace a bunch of it with dark spinifex instead"; the foreground road "missing some fine stripes in the travel direction":
  - Spinifex: needles mostly dark grey-green (30% dried golden), the core darker, the outline ragged; the sunny side's needles lit 60% of the way from shady to sunny, so no bright rim. Bushes cut from 0.15 to 0.06 of the mix, spinifex up from 0.8 to 0.9, and tinted darker.
  - The verge: wider, continuous width swings (three octaves), bands of sandy and grey gravel along the road, and here and there a pale edge to the dirt.
  - The edge line wanders in and out (±7 cm over ~80 m), and the car drifts ±15 cm in its lane (`DRIFT`).
  - Stars: only the brightest 1.5% bright; the rest are faint.
  - Road: two octaves of fine stripes along it, which the blur leaves, faded as a pixel spans them.
  - Desktop only so far.
- "Yeah go for it" (v7 on the phone): the first v7 (1075) ran at 2.5 fps (v6 re-timed at 20). Bisecting on the phone: the spinifex changes and the ground changes each tipped the Adreno compiler over with the other, though each ground piece alone was fine (12-18 fps); the content changes (drift, plant mix) weren't it. Cheap shadows (a coverage-only `cover()`) and stopping the cell loop unrolling didn't help. Splitting the ground and sky into a new Buffer D (sharing code through Common; `run_powder.py` now binds Common's `///buf:` declarations too, as the app does) gave a 52 fps Buffer D, but the Image pass left with the cut-outs fails to link on the phone with an empty info log (not the struct, the D read, unrolling or the spinifex; it links only with the ground back in). That's parked in `~/dev/shaderEditor/outback/split_wip.glsl`. v7 is instead v6 plus the stars, drift, plant mix, darker hummock cores and bushes, the edge line's wander and the road's fine stripes: 17 fps. Waiting for the split: the ragged dark spinifex (the sunny-side rim) and the verge's bands and wider swings.
- v7 (1075) replaced with that: 22 fps, left fullscreen.
- "let's not worry so much about performance next time.. took ages and we hardly advanced visually!"; the bushes' "very smooth bright fringe"; vegetation and soil to "vary in size, colour and texture across large regions", "sandy areas and darker/greyer areas of soil"; distant bushes "mainly a flicker"; the white line, verge edge and 'outback' edge to wander more, noisier; "larger trees and dark large bushes near the road"; "a demoscene-style progress bar ... while all the textures are being produced":
  - Back in from v7's first try: the dark, ragged spinifex (no bright fringe; its needles now tinted too) and the verge's bands.
  - `region()`: slow noise over the land. Plants are bigger or smaller by region, dry gold or dark green, with finer or coarser, denser or sparser needles; soil goes sandy or dark grey over large regions.
  - Near the road: the odd big mulga or ghost gum, and big dark bushes in some regions.
  - The edge line wanders ±15 cm (plus a finer wobble), the seal's edge and the dirt's edge are raggeder at two scales, and the verge's width has a fourth, finer octave.
  - Small plants far off are averaged over two heights half a pixel apart, and their texture over the pixel's height, against the flicker.
  - The tree atlas is drawn a tile a frame (24 frames, then the sums), with a white progress bar in a hollow box until it's done.
  - Saved as feedback: push the look forward, don't bisect fps at length.
- "Yeah go for it" (v8 on the phone): Outback Window v8 is 1076, 0.5 fps after 40 s (the Adreno cliff again); the progress bar was over before the first screenshot. Left fullscreen.
- "I'm loving it at 1/16 actually!"; "a tiny possible gap between the tops of the distant hills and their bases"; the verge "can vary heaps more with low grassy clumps, occasional lighter or darker/greyer edges, and a much noisier interface to the 'outback' behind":
  - The gaps were the skyline antialiasing half-covering rays that graze a near ridge, letting the sky through in front of the ranges behind: a hit now covers fully. The ground's haze also eases to the ranges' where they meet, so there's no seam.
  - The verge's style changes along the road (how banded; red, sandy or grey). Now and then it has a pale or dark grey edge on either side. Low grassy clumps lie in it, thick in places, with small tussocks standing among them. Its boundary with the dirt is fingered both ways (2D streaks, plus a finer ragged octave).
- "Yeah do it" (v9 on the phone): Outback Window v9 is 1077; 0.7 fps at 1/4, 14 fps at 1/16 (Dan: "Can go to 1/16"), left fullscreen at 1/16.
- "at 1/16 the distant mountains have way too much horizontal shape change.. and occasionally there are pops that seem more like a vertical contraction"; "The grass-y aspect on the verge also seems to not be [motion] blurring properly at slower speeds"; "Solid":
  - The ranges are marched in 7% steps (60 at most) instead of 22% (16), which skipped over peaks, so the skyline jumped about and dropped for a frame. The terrain's gully detail and the rock's colour detail (gullies, strata) fade out once they're finer than a pixel.
  - `streaks()` averages three samples along the road for octaves whose features the blur nearly spans: it used to blur only once the blur was longer than a feature, so the grass clumps stayed crisp at slow speeds. The thresholds on blurred noise (grass clumps, the dirt's fingers) widen with the blur.
  - The shutter lengthens as the car slows (`SLOW_SHUTTER` 0.05 s at a crawl, `SHUTTER` 0.01 s from 20 m/s), so there's always visible blur.
- "Yeah do it" (v10 on the phone): Outback Window v10 is 1078; set to 1/16 (it opened at 1/4: 0.5 fps), 14 fps, left fullscreen.
- "'solid' as in: cool, sounds good" (not about the grass: the longer slow shutter was my own idea, offered back); "a slightly bright yellowish line directly between the foreground and the mountains ... changes too fast, so it appears and disappears.. can we make it stable even at low res?"; "the hills are just as far away no?"; "some kind of depth haze too that works with everything ... So we can do sandstorm mode later":
  - The line was the ranges' own plain 2.5-4 km out: rays skimming it hit it or not as the view bobbed, and it was lit by a bumpy normal, in its own colour. Its low flats are now lit flat, and the far ground fades to the same colour (`RANGE_PLAIN`) by 2.6 km, so ground, plain and hills meet without a line. A pixel row straddling the ground's 2.6 km cut-off is blended by how much of it is ground, and far-ground soil and flecks average out as a pixel spans them.
  - A cooler, greyer band was tried and dropped: as Dan said, the plain's as far off as the hills, so it hazes the same.
  - Haze: everything hazed by its true distance (the ranges used half theirs, the far ground a blend; `HAZE` doubled to 24 km to keep the look), times a new `HAZE_DENSITY`. A dust storm will also need `DUSTY` raised.
- "Yeah chuck it on phone": Outback Window v11 is 1079, at 1/16: 14 fps, left fullscreen.
- "does the res of the offscreen buffers scale with the main draw res? Might it be good to allow that to be decoupled ... high res generated textures but low res main output?" / "Yeah do it" / "relative scale might be the most useful ... outback seems like it kinda needs a scale or a minimum":
  - They were locked together. New per-pass lines: `///size:WxH` (fixed), `///scale:F` (F x the screen, whatever the quality), `///minScale:F` (the render's, at least F x the screen). Such buffers are remade only when that size changes, see it as `iResolution`, and readers use `iChannelResolution`; the chain, progressive bands and captures run each pass at its own height. `run_powder.py` does the same.
  - Outback Window's atlas is `///size:576x1601`, just fitting the biggest tiles (a screen-scaled float buffer would be ~140 MB). Debug build installed; v12 is 1080, 13 fps at 1/16, trees fine.
- "could we make offscreen buffers work with high res/tiled rendering?" / "let's try 1": a buffer shader's capture at any size or oversampling now tiles the Image pass alone, reading its buffers live as they are (data buffers - state, tables, atlases, simulations read by UV - come out right; buffers that draw the picture at screen size come out upscaled). Tiled captures also set `iResolution` (they'd only set `resolution`). Tested on the phone: Outback Window v12 at 1440x3088 (twice the live 1/2), 8 s a frame, as a partial sequence. Dan: whether buffers should be kept whole or tiled depends on the shader (Outback wants whole); scripted captures welcome.
- "Yeah do the capture script, also on the Custom Res/Seq dialog the menu says 1/8 /14 and then 0.5 for half.. lets make that consistent and use 1/2 instead":
  - The presets now say 1/2× (all 5 languages); their fractions no longer trip lint (46 warnings, from 51).
  - `tools/phone/capture.sh WxH [--oversample N] [--frames N --fps F] [--shader ID]` renders the open shader in Enki debug with no dialog, posts frames to the panel (⏹ stops it) and pulls the PNGs back. First tries failed: `am start` pauses the activity, which drops the GL context, so the capture now waits until it's resumed. Tested on Outback Window v12: 720x1544 in 7 s, and a 3-frame sequence.
- "test the stop button too": the first try froze Enki debug (an ANR, not a crash; Dan: "I think Enki crashed"). The stop went by `am start`, which pauses the activity, and pausing waits for the GL thread, which was busy with the capture. Captures and stops now go by a broadcast that MainActivity registers (debug only), and capture.sh checks the panel every 2 s rather than at each frame. Retested: ⏹ stopped a 1440x3088 Outback capture 5 s later, mid-frame, and frame 1 came back. The live shader is paused while a capture runs (Dan thought it had stopped).

## 2026-10-31
- "merge it to master": squashed into master as a36e9635. The branch is identical to master's last merge as of 72a5a82a, and master had nothing since, so the result is the branch's tree; the conflicts were only from the squash history.
- "make a release bundle, I'll try to get this onto play console tomorrow": 2.38.0 (versionCode 97), with changelogs for the buffer sizes, high-res buffer captures, the iResolution capture fix and the 1/2x preset. The signed bundle is at ~/dev/shaderEditor/release/enki-2.38.0-97.aab, and the release commit is on master too.
- "install the release build on the phone": 2.38.0 (97) installed over 2.36.4 (95), signed with the same key. The database was already at v15 from 21 October, so nothing migrated. It opened cleanly with no crash.
- "looks good - need to massage outback window a little as it's pretty massive, but something to get onto tomorrow, let's wrap up for now": wrapped up. Next: slim Outback Window down (v13). Upload 2.38.0 to Play.
- "let's see what can be done with Outback to make it faster ... maybe you'll get lucky?" / "It's only a reasonable framerate at 1/16 now": timed variants live on the phone (a bench script in ~/dev/shaderEditor/outback/prof). The plants' drawing was most of the cost, and taking out a whole kind of plant saved far more than its arithmetic, which points to the compiler's register budget. Fewer lanes, cheaper far plants, unroll-proof loops, a noise texture and branching the ground made no difference live. What worked: the plants drawn in their own pass (Buffer D, the code shared through Common, with the Image pass drawing them itself for captures at other sizes), and the light made once a frame in Buffer A. Outback Window v13 (1083): 42 fps at 1/16 against v12's 13, same look; left fullscreen. Also found: a tiled high-res capture renders about 5x slower than live drawing at the same size, worth a look. Two "Outback bench" shaders are left in Enki debug's library.
- "can you please make me a summary of Enki's features in terms of offscreen buffers and so on, such that I can paste it to another Claude ... and also be aware in advance of any gotchas": docs/enki-shader-brief.md covers the multipass text format, buffers (channels, formats, sizes, steps, clearing, sound), native uniforms, text/font/keyboard, Edit Live, quality and captures, the phone gotchas and how Dan likes to work. Copied to his clipboard.
- "could it perhaps go in an artifact so I can copy it on phone?": published the brief as a private artifact with a Copy button that copies the Markdown (https://claude.ai/artifact/AzsGFdn2JSbydgW5mmUmwY). Dan: "Brilliant!"
- "Can we look into why tiled captures are 5x slower": timed on the phone at 360x772 - a simple shader is 0.167 s a frame tiled, 0.167 single-pass and 0.150 live, and Outback v12 at 1/4 0.55 tiled vs 0.57 single-pass, so the capture code is not slow. The 5x was Outback's own cost changing along the drive (its clock runs at a different pace at each quality, so runs show different scenes: 0.55 to 4.5 s a frame). Found on the way: Buffer B's 128-wide table fell off a render-sized buffer at 1/16 - fixed with ///size:128x40 in the sample. Dan: test only the obvious, revisit later.
- "chuck it on phone now": Outback Window v14 (id 1092, 1/16) on the phone and fullscreen - 10 fps in the editor about 40 s in, not comparable with the bench's 42 for v13 (different scenes).
- "Find In Shader not working too well in a 'Common' section ... zooming-to and selecting the wrong text": the dialog kept the last search's match positions while the query was unchanged, so after switching pass (or editing, or another shader) it selected stale positions. Now it searches again each time it opens, keeping the place only if the matches are unchanged. Installed in Enki debug for Dan to try.
- "marble gauntlet V3 ... it's gotten pretty slow ... speed it up for v4?": timed variants on the phone (live 1/8, 180x386): v3 ~7/60 s a frame; blank Image 2/60, so Buffer A is cheap. Taking out the march, terrain shading or marble shading each saved about one 1/60 step; splitting the marbles into their own pass (Buffer B) didn't help, so it isn't a compiler-size cliff like Outback - the work is spread out. Fresh starts roll different courses, so timings are noisy. Options put to Dan; nothing new on the phone. Work in ~/dev/shaderEditor/marble/.
- "let's give 3 a go": Marble Gauntlet v4 (id 1111) = v3 with march octaves 4, steps 64, normals 7 octaves, shadow 8 steps, glass rays 24/40, underwater 28, detail 3 octaves. In the editor it showed 2.5 fps against v3's 3.3 (different scenes, a close marble, phone warm) - no clear gain. Reported to Dan.
- "give the cheaper glass rays a crack in v5": Marble Gauntlet v5 (id 1112, 1/16) = v4 with BALL_REFRACT_DETAIL 0 (what's seen through a marble shaded the cheap way, like its reflection). Added and opened, but the phone locked before its fps could be read. Dan: v4 is "a fair bit faster at 1/16".
- "Can give it a go now": v5 runs, 13 fps at 1/16 in the editor; left fullscreen for Dan.
- Marble Gauntlet v6 (Dan: fast enough at 1/8 please; stars can go for a horizon glow, cheaper caustics but keep ripples, stronger shimmer and pond boost, fewer terrain octaves, maybe drop the pink stripes; course to turn back on itself; then "fade the edges of the new caustics ... and make them noisier"): v6 (id 1113) runs at 17 fps at 1/8, against 2.5 for the same code as one program. Timed with the editor's fps counter: the bench's live-size captures had been measuring the wrong thing, which had hidden that splitting the marbles into Buffer B is a 5-8x win. Image reads Buffer B by position (upscaled in other-size captures). Also: horizon glow for stars, sine-lattice caustics with a noise warp, fading in the shallows, terrain octaves 5/3, sheen off, shimmer 1.0, pond boost 0.6. The winding course is v7.
- Marble Gauntlet v7 (Dan: caustics can go; the course to turn back on itself; boosts with a cooldown and a cap per 30 s, used more when behind, with a yellow halo; stronger shimmer; "Can the course be random every time? ... a real world time uniform?"): v7 (id 1120, 12 fps at 1/8). The course is a chain of 40-unit bend units (three arcs each, lobes up to 125 degrees toward or away from the camera); Buffer A steers along it exactly, and a new Buffer B maps it once a frame for the picture passes (the course maths in every terrain lookup ran at 1.5 fps); the marbles moved to Buffer C. Boosts: 2 per 30 s, 8 a race, 4 s cooldown, likelier when behind. Caustics off, SHIMMER_GAIN 2.5. Each race (the first too) seeds its course from iDate and the clock. run_powder.py now sets iTimeDelta and iDate.
- Marble Gauntlet v8 (Dan: a dimmer, sharper boost halo leaning the way it travels; a mauve glow in the water; a pale sky-blue halo after a tap; the winner floats up in a royal purple halo with a crown; a restart button, and a start button with a dice-dot countdown; the marbles ranked in a column top right, the leader subtly ringed): v8 (id 1121). The halos and crown now go on after the grade, so night no longer washes them white. Boosts wait 4 s into the race (several went off the line). The winner celebrates for 20 s, then a new race starts by itself. fps read 3.5-14 across v7/v8/no-HUD runs: it swings with the course, which is random each start, so there is no clear v8 cost.
- Marble Gauntlet v9 (Dan: marbles jump on their own, off ramp tops and over spike traps; drop the crown (fps) but keep the winner glow; a plain triangle restart button; the boost halo round, just thicker in front; soften where the track meets the terrain, or colour the track, e.g. sparkles along the middle): v9 (id 1125, 14 fps at 1/8 on its course). Each marble decides once per ramp lip (55%) or per out-spike trap ahead (65%) whether to jump. The bank now eases out (no crease at the rim), and pond bowls meet the ground with a smooth min. Twinkling multicoloured sparkles and a faint pearly tint run along the middle of the course.
- Marble Gauntlet v10 (Dan: pixel-sized, sparser sparkles (rand^2), more view-angle dependent and hugging the centre of the track; marbles the water boosts softly glow in their own colour, surface unchanged; ranks every few seconds; stamina so marbles fight through the pack, fall back, and boost when they drop too far behind): v10 (id 1126, 10 fps at 1/8 on its course). The sparkles are one-pixel glints off randomly tilted facets, in 35% of 0.22-unit cells, Gaussian 0.7 from the centre line, and dropped when sub-pixel. Stamina: push +20% for about 7 s, then rest -12% for about 6 s (each marble its own +-30%), boosting 2x as often while resting; rubber band 0.12 to 0.07. Headless minute with no damage: order changes 18 (v9) to 37, pack spread 20 to 14. Ranks update every 2 s and on a knock-out. Script: marble/v7work/overtakes.py.
- Marble Gauntlet v11 (Dan: "not seeing the sparkles at all any more" - make them visible, a very few very bright, the path core wider with a 1/x falloff; the boost halo in the marble's own colour, like the water effect; replace the water's surface glow with self-illumination through the glass): v11 (id 1127, 14 fps at 1/8). v10's sparkles were dropped whenever cells got smaller than ~2 pixels, which at 1/8 was nearly everywhere. Now the cells double in size with distance, so each sparkle stays one pixel. Brightness rand^5 x 14, density 0.4, falloff 1/(1 + u/0.8). The boost halo uses the marble's own colour. The surface shimmer and the water halo are gone; in the water (and at 0.6 while boosting) the marble glows from inside in its own colour, scaled by its thickness squared, in slow turning bands, and scattered by its rings.
- Marble Gauntlet v12 (Dan: much brighter sparkles on the trail's core as flare targets, "pinch it from outback"; more rainbow sparkles; noise on the pond shores; a softer water edge; boosts likelier uphill and up high, for accidental jumps; the winner rises to the top third, grows to nearly the screen width, everything else paused, looking amazing; centre the replay button): v12 (id 1129, 15 fps at 1/8). Buffer A finds the two brightest glints (facets on the core every 2.5 units, a mirror test against the light and the camera, three height samples for hills) and the Image draws Outback's flare at each. Sparkle chroma 0.85, 4x brighter on the centre line. Pond shores are noised; the water's reflection fades in over 0.18 of depth. Boost rate x(1+3 climb)(1+1.5 height). Winner's moment: 1.2 s after the win, the scene fades over 1.6 s to a royal backdrop (rays, twinkles), and the winner grows in at the top, near the screen width, lit from inside, with a purple rim. Only the backdrop and the winner are drawn then. The first try put the glass shading in the Image pass and fell off the compiler cliff (1.6 fps); it moved to Buffer C's single shadeBall call. The button triangle's distance function was off-centre (now within a pixel).
- Marble Gauntlet v13 (Dan: "too slow again", ditch the sparkles and make the core of the track a pearlescent sheen like the marbles', much more on the core with a nice falloff): v13 (id 1132, 14 fps at 1/8; v12 read 15 on another course). The sparkles, flares and Buffer A's glint search are gone. The course's core is now a thin film (filmReflectEx, already in the Image pass for the water) of 420+-260 nm in noise patches, shifting with the view, over the reflected sky plus the light's glint, at 1.6 x (1/(1+u))^2 from the centre line.
- Marble Gauntlet v14 (Dan: a little emitted rainbow on the track core, without washing out the reflection; one big spike instead of five; a bright yellow-to-white leader glow; the tap glow in the marble's own colour; why the glows look complementary to the stripes, absorption vs scattering): v14 (id 1133). Faint rainbow emission (0.05, flowing along the course, (1/(1+u))^3) that recedes where the picture is already bright (/(1+6 lum)). One spike, 1.2x taller and 2x wider. The complement came from milky glass scattering in its absorption spectrum (-log(rgb), opal-like); it now scatters in its own hue, with MILK_OPAL 0.15 of the old. Fps: the first reading was 2.6 on its course; with the course fixed, v13 read 17 and v14 14.
- Marble Gauntlet fps (Dan: "analyse the fps again, it got too slow, happy to sacrifice anything"; then "do less tests and be more bold", "always go fullscreen when measuring"): the editor's fps label reads low and lags (3.4 against 6.0 by SurfaceFlinger), so earlier readings were unreliable; marble/sfps.sh now times by SurfaceFlinger, fullscreen. Random courses swing results, so comparisons need the course fixed (clock = 1.0). Established on one fixed course: v10 25.8 fps, v11 6.0, v12 5.8, v19 5.2; with the Image pass off the rest runs at ~118. Tried and not enough: v15 (sky-only ponds, fewer steps/octaves, no shadows), v16 (overlay in Buffer D, countdown dots), v17 (pearl out of reflections), v18/v19 (shore noise out of terrainH), v20 (simple terrain in reflections), all ~4-5 fps. Next: bisect inside v11 on the fixed course, or rebuild up from v10.
- Marble Gauntlet rebuild (Dan: "2, add the features in incrementally", frame the leader and look back at the trailers, rank column off with a four-finger toggle, keep v20's rainbow trail, no flecks; "less testing", "3 2 1 can go twice as fast"): v21 = v10 + a camera that keeps the leader in frame and turns back towards the trailers as the pack spreads + ranks hidden (four-finger hold toggles) - 26.1 fps. Timed v11-v14's features stacked on it (fixed course): inner glow alone free (26.5); all of v14 6.6 without the inner glow, 21.6 without the ragged pond shores too (noise in every terrain height lookup) - the pearl trail is cheap. v22 (shores smooth, countdown twice as quick in dots) 4.2 with the inner glow; v23 drops it - Dan: fast (thumbs up), though its timing read 4.3 (scene-dependent). v23 is id 1179. Debug build runs at GPU priority Low, Automatic.
- Marble Gauntlet fog (Dan: "would it be faster with fog so we couldn't see far? try a version", "some nice colour instead of grey", "occlude the background behind the balls much sooner... much closer/hazier"): v24 lilac fog (peach to the sun) out to 60 - timed 7.5 against v23's 20.5 (its fog code in the Image pass likely tipped the cliff), Dan: not faster. v25 (id 1183): a lilac haze (violet at night) from just behind the pack, solid 18 further on, rays stop there, 36 march steps, sky fades into it; minimal code. Dan: thumbs up, though it timed 5.3 - and v23 timed 4.3 once and 20.5 another, so single fixed-course timings are not reliable either.
- Marble Gauntlet v26 (Dan: don't trust raw fps - it drifts with charge and heat; auto-jumps should glow like boosts, both halos a bit softer; bring back terrain detail down to the pixel, bump mapping is fine; perturb the colouring so the snow line isn't level): auto-jump time kept in K_FILM.y, the boost halo shown for it too (gain 0.75 to 0.6); groundBumps() - analytic-gradient value noise, octaves down to the pixel, primary view only, plus a little tone; elev for the colour bands wobbles with the existing colour noise (COLOR_WOBBLE 0.35). On the phone as id 1184 - Dan: thumbs up. No fps timing.
- Marble Gauntlet v27 (Dan: opaque pearlescent marbles if faster; "not feeling the vying for first place" - noisy stamina/speed?; show screenshots on the desktop without blocking): marbles opaque pearl (own colour, lit, thin-film sheen, one reflection ray - none through the glass). Racing: noisy speed + surges + weaker rubber band made it worse headless (lead changes 4 vs v26 11, pack spread out); drafting instead - up to DRAFT_RANGE behind another a marble is towed (DRAFT_PUSH 0.16), and the leader spends stamina 1.6x faster: 14 lead changes, front gap 0.8 (v26 1.4). New marble/v7work/leads.py counts lead changes; show.sh puts renders in one reused feh window (MarbleView) on the desktop. On the phone as id 1185 (no rating yet).
- Marble Gauntlet v28-v29 (Dan: dark rims on steep hills; rainbow trail a touch brighter; pond sand nearer the rock colour with a gradient-noise edge; blocky relief in a screenshot; then "texels showing in the terrain", "marble shadows slightly softer"): the blocks were Enki sampling buffers GL_NEAREST by default - the course map now asks for min:l;mag:l (easing between texels made a washboard, dropped), then v29 reads it through a cubic B-spline (four bilinear taps) so no texel creases. Shores: the ground round them plus 30% sand, edge pushed by snoise, less film; PEARL_EMIT 0.065; 48 march steps for the rims (a guess). Shadows: solid (marbles are opaque), soft-edged, softer with distance, no caustic. v28 id 1186, v29 id 1187 - Dan: "not quick but working well visually".
- GPU priority (Dan: "make sure GPU prio is high for realtime and only set to low for rendering modes"): split into GPU Priority (real time), new, default High, for Normal and Automatic live rendering, and GPU Priority (rendering), the old key, default Low, for Always Progressive (picked when the context is made); strings in all 5 locales, enki-since-markus.md updated. Debug build installed: the phone granted a HIGH context (0x3101). Marble v29 still ~1 fps at High - its 4-read cubic map lookup ran in every height lookup; v30 (id 1188) uses it only to shade the ground seen directly (one linear read elsewhere): 21.6 fps fullscreen, Dan: thumbs up.
- Marble Gauntlet v31 (Dan: "gorgeous at 1/16", a bit more antialiasing at low res; the rainbow core brighter and colouring what the marbles reflect): Buffer C now shades the pixel just outside each marble's silhouette with alpha = coverage (up to 0.5), and the Image pass blends that fringe in where no marble was hit and the marble is in front of what was; PEARL_EMIT 0.065 to 0.14 (receding less where bright); courseRainbow() shared, and lights each marble from below near the course core (PEARL_BALL_RAIN 0.5). On the phone at 1/16 as id 1190 - Dan: thumbs up.
- Marble Gauntlet v32-v34 (Dan: the terrain looks detailed through the water - make it look that good everywhere; frame the top 3; no look-back turn visible yet, subtle is fine; sphere-edge antialiasing; the rainbow trail looks voxelly): through water the ground's distance counted from the surface, so its detail went finer - v32 lets detail go to 0.35 px everywhere (DETAIL_PX_SCALE) with more octaves; the camera frames the top CAM_FOCUS=3 by rank and turns back up to 0.3 rad as the rest trail (eased over 15 units; before, the pack always fit, so it never turned). v33: the marble fringe 1.5 px, up to 60%. v34: the rainbow core reads the map through the cubic filter (on directly seen ground). Phone ids 1191-1193 at 1/16, thumbs up for v32 and v34.
- Marble Gauntlet v35-v36 (Dan: fast enough now; marbles too lit from below - dark at the base near the ground; jumps more varied with bigger ones, easing into the track; thin-film noise a couple more octaves; the core glow disappears under water; ripples "instantaneous" - a time-based trail; edges of water (taken back) - "maybe just the specular that needs ramping"): v35 - rampSize() per ramp (lip 0.6-2.4x RAMP_H, run-up 0.8-1.7x, most small; auto-jumps use it), the ramp's sides ease over 2 units past the course edge; marble underside darkened near the ground (BALL_BASE_DARK 0.8), rainbow-from-below 0.5 to 0.15; filmNoise() - 3 octaves, roughness 0.6 - for the course pearl and the marbles. v36 - the core's emission no longer gated to above the water; each ripple ring centred where the marble was when it set off (RIPPLE_TRAIL 0.12 s/unit); the sun glint on the water eases in over 4x SHORE_FADE. Phone ids 1194-1195 at 1/16.
- Marble Gauntlet v37 (Dan: big ramps even bigger and rarer; "draw just the leader marble like we were before and see if the rendering is ok"): RAMP_BIG 2.4 to 3.6, size from hash^4 (big ones rare), run-up up to 2.2x; read as the leader in glass again (refracted ray through the same env() call site as the reflection, skipped for the others), the rest pearl. Phone id 1196 at 1/16 - Dan: thumbs up.
- Marble Gauntlet v38 (Dan: v37 "KILLER"; all marbles glass again - "just that it was made clear who the leader was"; a smoother, more atmospheric horizon; ditch the spikes entirely, fall damage only, aim for 2-3 minute races): all glass; the HUD shows just the leader's disc top right (the full column on a four-finger hold); sky into haze 1/(1+h(1+h)) with FOG_HORIZON 2.2 (was exp, 6); no traps (trapExists false), no spike tracing or plate shading. New v7work/racetime.py times a headless race: no spikes and old settings ran past 5 min; LAND_DAMAGE 0.05, LAND_DMG_MIN 6, POND_HEAL 0.08, DAMAGE_RAMP 0.4, plus a wear-down from 140 s (SUDDEN_DEATH 0.0008/s per s) so races end. A test race with an earlier tuning won at 114 s; final tuning untested headless. Phone id 1197 - Dan: thumbs up.
- Marble Gauntlet v39 (Dan: v38 "verrrry nice"; the whitish sand edges round the pools need octaves/roughness, following the terrain's lower-freq-plus-filter-width scheme; same for the main trail's noise): fineNoise(q, px) - gradient-noise octaves from frequency 1 up to the pixel (last fading in; at most 6, roughness 0.55) - drives the shore edge (BEACH_NOISE_FREQ, the albedo's px) and the course's pearl sheen thickness (PEARL_SCALE, the shading's pxD). Phone id 1198 (no rating yet).
- Marble Gauntlet v40 (Dan: "bloody amazing" but wants marbles overtaking all the time - a lowish-frequency background boost mostly for the lagging positions): a surge per marble - two slow sines (~0.21 rad/s and ~2x, own phases) over a threshold lift cruise by up to SURGE_POWER 0.4 of PACE for a stretch, scaled by rank from 0.1 (leader) to 1 (last). Fixed-course overtakes.py, 90 s: v39 55 swaps; 0.32 gave 67, 0.5 gave 47 (one race each - noisy), so 0.4. Phone id 1199 - Dan: thumbs up.
- Marble Gauntlet v41-v42 (Dan: v40 "outrageously cool"; the winner view a bit flat - more shading, reflection, modulated roughness; then "an audio-rendering version ... as much as you can think of, and subtle"): v41 - the winner's moment lit from a studio of its own in its camera's frame (key light up-left, studioEnv: dark violet, a softbox, a win-colour ring behind), 8 glossy samples of it with roughness in fineNoise patches stuck to the glass, a rim; the milky white glass had read as a flat disc lit by the sun. New v7work/wincap.py captures the winner's moment headless. v42 - a Sound pass (Buffer D, v7work/sound_v42.glsl): per marble rolling rumble + faint ring, water swish and bubbles, air wind, panned by bearing from the camera and quieter with distance; glass clinks as hard as each knock (K_GND.w now packHit: time to 1/64 s + strength, set on landings and marble knocks over KNOCK_MIN), jump fwips, boost shimmer, shatter on knock-out; countdown pips and GO, a winner chime and pad; soft wind; steady sounds use exact local time looping every 8 s. First try was black on the phone: "Buffer D: INTERNAL ERROR: Call to undefined function!" - Common's courseAt() prototype needs a body in every pass (the desktop compiler didn't mind); stubbed. Phone ids 1200-1201; Dan to report whether impacts are clear.
- Marble Gauntlet v43-v44 (Dan: "the audio it's producing is very clicky... does it need to fade at the ends?"; then: landings should thud, and boosts/jumps need appropriate sounds): the clicks were a jump at every audio block - the race clock's step is capped at 0.05 s, so at ~12 fps it runs at ~60% of real time and every event sound restarted a little earlier each block, and the rolling/wind noise rates followed speed (a rate change jumps the waveform). v43: Buffer A notes each sound's real time (iTime): K_SND (knock, boost, jump, knock-out), G_SND (countdown pip, win); the Sound pass plays them SND_LATENCY 0.12 s behind, with exact whole/part-second differences; fixed-rate noise bands mixed by speed; levels eased from last frame's values (K_PREV) over the frame. Headless, the step across block edges was 6.4x the normal step in v42, 1.2x in v43. v44: landings record K_SND2 and thud (low knock dropping in pitch + grit, over LAND_MIN 2.0); clinks only for marble knocks; boosts a rushing zing up under the chord, self-jumps a springy whoop; louder. Phone ids 1202-1203 - Dan: thumbs up on v44.
- Marble Gauntlet v45 (Dan: "the water sound could be a lot more fluid-y, random and occasionally splashy"; mid-turn: "the main travel sound seems still to be voiced but I think a rumble-only is more appropriate"): K_SND2.yz note crossing the surface (packHit: real time, speed) - a splash going in (spray, a dropping bloop, six droplets plinking down at random, as big as the drop), a smaller slosh coming out; in the water a gurgle swirling by slow random noise and bubbles from four voices (5/7/9/12 slots a second, random onset, pitch and chance, rising, busier when moving). Rolling: the glass ring and 1400 Hz crackle dropped; a rumble from five unrelated low noise rates, the upper two brought in by speed. Phone id 1204 - Dan: thumbs up.
- Dan: "we need to make it so you never wait forever for a thumb!": status.sh rate now waits 20 s by default, 45 s at most (a clarification being typed adds 2 min, was 5); on no answer the thumbs stay up and a late tap comes out of the next command like a message (late_rating in heed and done). Same in the kit copy; CLAUDE.md and README updated. Tested on the phone: a 5 s question timed out, Dan's late thumb came through on the next command.
