# Notes for branch `audio-and-shows`

Built mostly unattended, so most of it is **untested on a device** - each section says what was
checked and what to try. Lint is kept at the 50-warning baseline.

## Morning: moving your release install to this branch

This branch upgrades the database from v13 to v14 (a `modules` table, and a `show` column on `packs`) - done
on the release install the morning of 21 Oct - and then to **v15** (a `shader_texts` table, for shader
texts). Each upgrade only *adds* tables and columns, but a database can't go back down to an older app,
so for each one:

1. In the **release** app: Settings → Export database (keep the file somewhere safe).
2. Build the release APK from this branch:
   `source ~/.android-signing/enki-shader-editor-release.env && ./gradlew -q assembleRelease`
3. Install it over the release app: `adb install -r app/build/outputs/apk/release/app-release.apk`
4. Open it: your shaders, packs and textures should all be there. If anything looks wrong, the
   exported file from step 1 restores everything (Settings → Import database) on a reinstall of the
   master build.

## 1. Sound passes (tested on the phone)

- A buffer with `mainSound()` is a Sound pass; its tab reads **Snd**. Tested: plays, 0 underruns
  over 20 s at ~55 fps, tab label, pause.
- Try: a heavier mainSound, low quality settings, switching shaders while it plays (should stop or
  restart cleanly), Restart (back to sample 0), backgrounding the app (stops).

## 2. Soundboard sample (partly tested)

- Tested with you: pads, labels, sound at 71 fps with no underruns.
- Changed after, **untested**: smooth bends (oscillators run on "bent time", so no phase jumps,
  and the bend eases towards the finger), a smaller dead zone (20 px), a punchier snare
  (band-passed noise burst over a pitch-dropping body), brighter, louder hats (808-style squares,
  high-passed), and a clap made of three quick bursts and a tail.
- New uniforms: `soundSample` (int) and `soundTime`: the next sample the Sound pass will make.
- `tools/samples/check_shader.py` compile-checks samples offline with glslangValidator.

## 3a. Save Buffer as Texture (untested)

- More Options → Save Buffer as Texture…: pick a buffer and a name (suggested: the first
  `sampler2D` a pass declares that isn't saved yet). Saved byte for byte as a PNG, replacing a
  texture of the same name. Restart the shader to load it.
- Try: save the Tracker's Buffer A as `trackerSong`, restart, and check the song comes back.

## 3b. Tracker sample (untested on a device; all passes compile-checked)

- 8 tracks x 32 steps at 120 BPM on the Soundboard's instruments, with a demo song.
  - Tap an empty cell: a note (the track's instrument). Tap a note: select it.
  - Hold a cell: edit it (note -/+, octave -/+, ten instruments, OK).
  - Tap a note twice: it turns into a red DEL; tap that to delete it.
  - Play/pause, top left.
- Buffer A song / Buffer B UI / Buffer C sound / Image draws. Edits pass from B to A as
  numbered commands, so each is applied exactly once.
- Keeping a song: Save Buffer as Texture, Buffer A, `trackerSong` (suggested automatically).
- Things to check: cells may be small to hit on the phone (32 rows); double-tap timing; whether
  the note-name text is readable at 1/2 quality.

## 4. Tracker module playback (untested on a device)

- **Database v13 → v14** (a new `modules` table). See the morning section at the top.
- More Options → Import Tracker Module… (MOD/S3M/XM via IBXM, pure Java, BSD-3); a `///mod:name`
  line plays it. Uniforms `moduleInfo`, `moduleScope`, `moduleTime`, `moduleRow` (see the FAQ).
- The Module Viewer sample plays the bundled `enki_demo` (a 1-minute MOD made by
  `tools/samples/make_demo_mod.py` - our own, so no licence question).
- Checked on the desktop: IBXM with my per-channel change plays the demo (61 s, no clipping);
  openmpt reads it too. Not checked: how it sounds, scope/visual timing on the phone.
- Wallpapers stay silent (Sound passes too).
- Credit: README, FAQ, the IBXM folder's README, and a "Built with" row in the docs footer
  (only deploys from master).
- Shader packs now carry the modules their shaders' `///mod:` lines name; installing a pack adds
  them (names already in the library are left alone, like textures). To test: build a pack
  containing Module Viewer, install it on the debug build, and check the song plays.

## 5. Presentation packs (untested on a device)

- Build Shader Pack has **Present as a show**: seconds per shader (default 30), a soundtrack
  (each shader's own sound / a library module / a self-contained shader Sound pass - choosing a
  shader adds it to the pack), and an optional timeline (below). Stored as JSON in
  `pack_info.show` → `packs.show` (see `pack/PackShow.java`).
- Installed, the pack's group in the sidebar's **tree** view has a play icon: tapping the row plays
  the show (`ShowActivity`), the chevron still lists its shaders. Get Shader Pack has **Play Show**
  for it too. Not done: a single entry in the *flat* list (synthetic rows there would confuse
  next/previous/random/Find, which read that list) - say if you want it.
- In the show: full screen, screen kept on, the shader's name fades in at the bottom left, tap for
  the next one, back to leave. A shader that won't compile is passed after half a second.
- The soundtrack carries on through shader changes (the renderer doesn't restart it, and the
  view's pause around a shader swap leaves it playing). A module soundtrack replaces every
  `///mod:` line; a Sound pass soundtrack replaces each shader's own Sound pass, put in its first
  free buffer (a shader using all four buffers is silent - logged).
- To test: build a show pack with Module Viewer's song as the soundtrack and a few shaders, install
  it on the debug build, tap it in the tree; check the music doesn't stop or glitch at each change.
- Also fixed on the way: leaving Preview now ends its sound threads (`releaseSound()`).

## 5b. audioSpectrum hears the app's own sound (tested on the phone)

Settings → "Use the Shader's Own Sound" (on by default). While a shader (or a show's soundtrack) plays a
Sound pass or a tracker module, `audioSpectrum` analyses that instead of the microphone/system audio -
no microphone permission or capture prompt. Tested: a show of two Audiospectrum shaders under the demo
module reacts on both, across shader changes, with the microphone off.

To try: turn it off and an audioSpectrum shader with a module goes back to the microphone (if that's on).

## 5c. Pack follow-ups (21 Oct, partly tested on the phone)

- **Resolution:** Build Shader Pack → Resolution - each shader's own, or one Render Quality step
  for the whole show. Untested on the phone.
- **Smooth changes:** every slide is compiled ahead in the background; the change dips through
  black (~250 ms each way). Measured: swap to first frame 220-280 ms with the cache, up to ~900 ms
  without. Try a show of heavy shaders and see if the dip hides it.
- **Module file in the builder:** the soundtrack list ends with "Choose a module file…". Untested.
- **Edit Pack** (long-press a pack in tree view): tested - Barnsley came up filled in at v2 and
  saved barnsley-v2.sqlite with plain shader names.
- **Update Version** (same menu): tested - listed v2 (newest, selected) and v1 (installed),
  Update swapped the pack to v2. Only finds pack files *this* app saved (release and debug are
  different apps); "Choose File…" covers the rest.

## 6. Show timeline (blocked out, untested)

No editor UI - paste JSON into the builder's Timeline field:

```json
{"scenes": [
  {"shader": "Module Viewer", "seconds": 20},
  {"shader": "Name", "seconds": 15, "keys": [
    {"at": 0, "defines": {"ZOOM": 1.0, "TINT": [1, 0.5, 0]}},
    {"at": 15, "defines": {"ZOOM": 4.0}}]}
]}
```

Scenes play in order (in place of "every shader for N seconds"), a shader can appear more than
once, and keyed #defines are made live like Adjust Defines' live mode and eased (smoothstep)
between keys, ~30 times a second. Image pass defines only.

## 7. Shader texts (DB v15; partly untested)

Named plain-text blocks per shader, read by a pass as a texture - see the FAQ ("Can a shader keep
text out of its code?").

- **Upgrade:** tested on the debug app - v14 -> v15, `shader_texts` made, every shader still there.
- **Round trip of the shared format:** tested on the desktop (Image + Buffer A + two texts).
- **To try:** Load Sample -> **Demo Writer**. The bar should twist and scroll four lines of
  rainbow text (read it with the phone on its left side). Open its "scroller" tab, change a line, and
  the bar should show it on the next run without restarting. Then Offscreen Buffers -> Texts... ->
  Add / Rename / Delete; Save as New Shader, Copy/Share (look for `//// Text scroller`), and a pack
  with it should all keep the text.
