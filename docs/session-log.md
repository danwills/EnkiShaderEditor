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
