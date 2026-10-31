# Enki Shader Editor — What We've Built Since Markus' Version

Oct 19, 2026 · @Daniel John Wills

## Overview

In six days (14–19 October 2026) and 24 commits, Markus Fisch's Shader Editor 2.36.3 became Enki Shader Editor: 286 files changed, about 23,800 lines added and 3,100 removed. The work runs along four lines: a faster, keyboard-friendly editor; a renderer that keeps the phone responsive even on shaders running below 1 fps; high-resolution stills and video export; and shader state that survives pauses, resizes and captures.

The starting point is Markus' last commit, f534a68 ("Advance version number to 2.36.3", 9 October). The version number hasn't been bumped since, so the app still reports 2.36.3.

| Dates | What landed |
| --- | --- |
| 14 Oct | Rebrand, pause/find/paste tools, audioSpectrum from mic or system audio, name-tree sidebar |
| 15 Oct | Flow toolbar, compiled-binary cache, Compile All Shaders, shader name bar, fullscreen exit gesture |
| 16 Oct | Screenshot capture and its action dialog, back-button UI stepping, compile-time warnings |
| 17 Oct | Tiled high-res export, frame-sequence and MP4 export, background capture |
| 18 Oct | Capture polish, global hotkeys, Adjust Defines tool, syntax-highlighting modes |
| 19 Oct | Tile-size probing, GPU courtesy settings, progressive live rendering, state kept across resizes |

## Editor and UI

The editor now works as well from a hardware keyboard as from touch, and the chrome gets out of the way of the shader.

- **Toolbar and layout.** A single wrapping flow of buttons replaced the fixed rows, with an Icon Size setting. A name bar under the toolbar shows the shader name (tap to rename), with the fps and render mode on their own line beneath it. The status bar shows only while the editor is open, and Back steps out one layer at a time down to fullscreen. A three-finger gesture exits fullscreen.
- **Navigation.** Previous/Next/Random shader buttons, a 20-entry back/forward history (Ctrl+Left/Right), and a sidebar that groups camelCase names into a tree. Shaders can be reparented into groups, and a Jaccard "Find similar shaders" filter defaults to 64%.
- **Find.** Find Shader and Find in Shader both have previous/next/random buttons and a live "match X of Y" count, and remember their queries. Matches are highlighted and flashed with a ring.
- **Hotkeys.** A global set covering screenshot (Ctrl+R), find, save, undo/redo, fullscreen (F11), copy/paste shaders and quality steps (Ctrl+= / Ctrl+−). Each shows a toast naming the action, and a Hotkeys list sits under More Options.
- **Adjust Defines (Ctrl+Shift+D).** Edits a shader's #define lines as sliders, per-component vector fields and an RGB/HSV colour picker with an intensity slider for HDR values. A filter and a "recent" view help with big shaders, and changes apply undoably or with a save.
- **Editing comfort.** Syntax highlighting has Off/Fast/Full modes so large shaders type smoothly. Paste as new or over the current shader, Copy #define lines, and a Shader Stats dialog (size, defines, uniforms used, compile time, binary size). Compiles that took 3s or more get a warning first, and 7s or more need an explicit tap.
- **Dialogs.** The screenshot dialog remembers your last action, shows it bold and yellow, and runs it on Enter. Paste and screenshot dialogs pause the shader while open.

## Rendering and performance

A heavy shader can no longer freeze the phone: slow frames are drawn in thin bands between screen refreshes, and the app switches into and out of that mode on its own.

The root problem is that a GPU can't interrupt a draw partway through, so one long draw stalls everything on screen, Alt+Tab included. Moving rendering to another thread can't fix that; making each draw short does.

- **Progressive live rendering.** Each frame is drawn as full-width bands across several refreshes, and only completed frames are shown, so there's no tearing. Backbuffer feedback stays exact because bands read the previous completed frame. Every uniform is frozen when a frame starts, so a drag mid-frame can't split it into mismatched bands.
- **Band sizing.** Bands grow or shrink so each fits in about one refresh. On the 120Hz test phone (1440×3088) the fixed cost of presenting a frame alone exceeds 1.4 refreshes. So the renderer learns that floor from small bands, which lets bands grow back once a shader gets cheaper.
- **Automatic mode (default).** Switches to progressive below about 2.5 fps (three frames over 400 ms) and back to full frame once three frames are estimated under 350 ms. A doubling backoff stops it flip-flopping. Normal and Always Progressive remain as settings.
- **Safer quality increases.** Raising quality on a shader already under 10 fps asks first, using per-shader fps history stored in the database. Confirming starts the new resolution in small bands sized from the expected cost. Ctrl+= and Ctrl+− step quality.
- **Courtesy settings.** GPU Priority asks the driver for a context priority: High for real-time preview (Normal or Automatic), Low for Always Progressive rendering, each its own setting. Breathing Room adds idle time between slow frames, adaptively or as a set multiple, so other apps get the GPU.
- **Faster resume.** The GL context is kept across pauses, and the first frame waits until the UI has drawn, so returning to the app is quick. A spinner shows until the first frame lands.
- **Compile caching.** Compiled program binaries are cached in the database, keyed by source hash and driver signature, so reselecting a shader skips the compile. Compile All Shaders pre-warms the whole library in the background.
- **Readouts.** Fps is a one-second moving average shown to one decimal below 10, labelled "full frame" or "progressive" with frame and band sizes. A white dot shrinks as the current progressive frame nears completion.

## Capture and export

Any shader can now be saved as a still far above screen resolution, or as a frame sequence and MP4, and the capture keeps running if you leave the app.

- **Screenshots.** The camera button (Ctrl+R) reads the shader surface's exact pixels, with no toolbar or editor in the image. It pauses the shader and offers Copy to Clipboard, Save Current Frame, Save and Open, or Custom Res/Seq. Images go to Pictures/Enki Shader Editor with no storage permission needed on Android 10+.
- **Custom Res/Seq.** Renders at any size, from 1/32× to 8× device resolution, HD, 4K or 16–128 px squares, with box-filter oversampling for antialiasing. Big frames are split into tiles, offsetting gl\_FragCoord per tile so shader maths is unchanged, and stitched back together. Tile order can be Scanline, Random, From Center or Hilbert.
- **Backbuffer state in captures.** Shaders that stash state in fixed pixels (often row 0) read it correctly in every tile, from a full-width, pixel-exact copy. A frame sequence keeps its own full-resolution backbuffer, so feedback shaders accumulate detail frame to frame.
- **Sequences and video.** Render N frames at a chosen fps after skipping a settling period, then optionally encode an MP4 with MediaCodec. Video Scale enlarges the output with nearest-neighbour pixels, and Match Device Playback sets it up automatically. "Interrupt but Still Make Video" stops early and still encodes the finished frames, and Play Recent Recordings replays results.
- **Auto-detect tile size.** Driver watchdogs kill draws that run too long, so tile sizes are tested in increasing order in an isolated :probe process, where a GPU reset only takes down the probe. Draws the driver silently cuts short (at about 2 s) are caught by a sentinel readback, a 1.2 s time budget and a plateau check. If the whole frame is safe, tiling is skipped. Each attempt is shown colour-coded on the progress dialog.
- **Progress and background.** One progress dialog covers compiling, skipping, tiling and encoding, with a live tile preview, resolution and tile size, "Interrupting after frame N / T" and a Cancel that keeps or deletes partial frames. A shared EGL context and a foreground service keep a capture running in the background; Background Render Priority can be set from the notification. A fatal GL error now aborts the capture cleanly instead of crashing.

## Shader state, time and inputs

Shaders that run simulations in their own pixels now carry on through pauses, resolution changes and captures instead of resetting.

- **Time.** Restart resets time, runtime, frame and startRandom together and clears the backbuffer. Pause genuinely freezes time instead of letting it jump forward on resume. A quality change no longer restarts the clock at all.
- **Resolution changes.** The previous frame stays on screen until the new resolution's first frame is ready, instead of going black. Touch, touchStart and pointer positions are rescaled so held fingers stay put.
- **Keep State Rows on Resize (default Automatic).** For shaders that use backbuffer, the bottom rows holding stored data are copied into the new buffers as exact pixel values. Rows are re-wrapped from the old width to the new one, since state that overflows a row usually continues on the next. Automatic finds up to 8 such rows by how sharply they differ from the picture above them. Tested on a 7-ball physics landscape shader whose 128 state values span two rows at 1/16 quality.
- **Touch fixes.** A stuck pointerCount, left over from pausing mid-gesture, made "3 fingers = scrub time" shaders barely advance during captures; it's now cleared at the start of every capture.
- **Audio.** An audioSpectrum uniform (ShaderToy-compatible, 512×2) is fed from the microphone or from captured system playback. Optional per-frame FFT timing gives smoother spectra. A health check avoids tearing down a working capture, and several MediaProjection leaks and crashes are fixed.
- **Local music player.** Plays your own audio files for audio-reactive shaders without another app.

## Platform and housekeeping

The app keeps a minimal permission footprint with an eventual store release in mind, and every new string ships in all five languages.

- **Identity.** The package is now enk.danwills.android.enkishadereditor and the app is named Enki Shader Editor. Internal class names and upstream references are unchanged.
- **Permissions.** Screenshots and videos save through MediaStore with no storage permission. The tile probe runs as an unexported service in its own process and needs no extra permission. System-audio capture uses MediaProjection consent plus the foreground service Android 14 requires.
- **Database (now v9).** New columns store compiled program binaries, compile times, a source-only modified time (so autosaves don't reorder the list) and each shader's last fps at its last quality.
- **Robustness.** Fixed a TransactionTooLargeException on rotation with big shaders, a StrictMode database read on the UI thread, and several Activity and MediaProjection leaks found with LeakCanary.
- **New settings.** Live Rendering, GPU Priority, Breathing Room, Keep State Rows on Resize, Background Render Priority, Syntax Highlighting, Warn About Slow Compiles, ShaderToy Audio Timing and Icon Size. Update Delay now explains itself and defaults to 2 s.
- **Languages.** English, Russian, Ukrainian, Simplified Chinese and Traditional Chinese.
- **Developer notes.** CLAUDE.md documents build and test commands for future sessions, and FAQ.md was updated. There are no automated tests; each change was verified by building, installing and exercising it on the phone.

## Open threads

Everything is committed (latest 799d0e1); these are the loose ends.

- [ ] Make Automatic return to full frame sooner: learn the per-band floor during full-frame rendering too, and switch after one frame when the estimate is well under 350 ms.
- [ ] Find why "Increase Anyway" sometimes lost shader state early on. It worked in two later tests after the time fix, but the cause was never pinned down.
- [ ] Skip the tiling machinery for small renders (deferred earlier).
- [ ] Prepare a release: bump the version from 2.36.3 and update CHANGELOG.md and the fastlane changelogs together.
- [ ] Lint still reports 22 errors and 48 warnings; triage before a store build.
