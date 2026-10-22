# CODEX – Enki Shader Editor

This document captures how the app is structured today so you can change it confidently. Keep it close when you explore the code base.

---

## 1. What the app does
- Enki Shader Editor is an Android GLSL playground/live wallpaper. Users edit fragment shaders, preview them live, import textures/uniforms, and set any shader as a wallpaper (`README.md`).
- The whole product lives in a single Android application module under `app/`. Source is Java (language level 17) with Gradle Kotlin build scripts.
- The UI is a single-activity, multi-fragment setup with custom widgets for the editor, shader preview, uniform pickers, and texture tooling.
- Sensors, camera, microphone, battery status, wallpaper offsets, notification counts, etc., are exposed to shaders as uniforms via `ShaderRenderer`.

---

## 2. Build, run, and tooling
- Requirements: Android Studio Ladybug+, Android SDK 21–36, Java 17, and the Android NDK is not required. Gradle wrapper + Kotlin DSL (`build.gradle.kts`, `app/build.gradle.kts:1`).
- Build variants: `debug` and `release`; release is minified/resource-shrunk and expects signing env vars (`ANDROID_KEY_*`).
- View binding + `BuildConfig` generation are enabled. Compose is not used.
- Helpful commands:
  - Two product flavours: `enki` (this app) and `ultrarestarea` (Ultra Rest Area - every screen a shader; `app/src/ultrarestarea`, `docs/ultra-rest-area.md`), so tasks carry the flavour.
  - `./gradlew assembleEnkiDebug` (or `make debug`) – build APK (`make ura-debug` for Ultra Rest Area).
  - `./gradlew assembleEnkiRelease` and `./gradlew bundleEnkiRelease` – ship builds.
  - `./gradlew lintEnkiDebug`, `make lint`, `make infer`, `make avocado` – lint/static analysis/drawable checks.
  - `make install`, `make start`, `make uninstall` – adb helpers.
- Version catalog (`gradle/libs.versions.toml:1`) centralizes dependency versions (AppCompat 1.7, Material 1.12, Preference 1.2, CameraX 1.4, AGP 8.12).
- Fastlane metadata lives in `fastlane/` for Play/F-Droid; SVG assets are under `svg/`.

---

## 3. Repository layout (high signal paths)

| Path | Notes |
| --- | --- |
| `app/src/main/AndroidManifest.xml:1` | Permissions (camera, mic, legacy storage), wallpaper + notification services, exported activities. |
| `app/src/main/java/enk/danwills/android/enkishadereditor/` | All runtime code. Key packages: `activity`, `fragment`, `widget`, `opengl`, `database`, `hardware`, `io`, `view`, `adapter`. |
| `app/src/main/res/` | Layouts, drawables, fonts, `res/raw/*.glsl` sample shaders, `res/xml/preferences.xml`. |
| `res/xml/backup_rules.xml`, `res/xml/extraction_rules.xml` | Backup configuration referenced by the manifest. |
| `fastlane/metadata/...` | Store descriptions/screenshots. Keep in sync for releases. |
| `CONTRIBUTING.md`, `FAQ.md`, `CHANGELOG.md` | Ground rules, docs referenced in-app. |

---

## 4. Runtime architecture

### 4.1 Application start
- `ShaderEditorApp` (`app/src/main/java/enk/danwills/android/enkishadereditor/app/ShaderEditorApp.java:1`) is the `Application`. It initializes the singleton `Preferences`, prewarms `Database`, keeps a process-wide `UndoRedo.EditHistory`, turns on StrictMode in debug builds, and registers `BatteryLevelReceiver` (API ≥ 24) to drive low-power behavior.
- Splash flow: `SplashActivity` immediately launches `MainActivity` so the app initializes before the UI is drawn (`activity/SplashActivity.java:1`).

### 4.2 Main screen plumbing
- `MainActivity` (`activity/MainActivity.java:1`) hosts everything. It instantiates:
  - `EditorFragment` (code editing).
  - `ShaderViewManager` (GLSurfaceView preview + quality spinner).
  - `ShaderManager` (load/save shaders, handle `ACTION_SEND`/`ACTION_VIEW` intents, duplicate/delete, persist thumbnails).
  - `ShaderListManager` (ListView + background loader for saved shaders).
  - `UIManager` (toolbar buttons, drawer, toggling editor visibility, extra keys).
  - `MainMenuManager` (popup menu with editor actions, navigation to Add Uniform, samples, settings).
  - `NavigationManager` (previews, FAQ link, share intent).
  - `ExtraKeysManager` (soft keyboard helpers + completions strip).
- State flow: the editor notifies `ShaderManager` when text changes; `ShaderManager` updates `ShaderViewManager` and `ShaderListManager`; UI state (title/subtitle, show-errors pill) is adjusted via `UIManager`.

### 4.3 Editor stack
- `EditorFragment` (`fragment/EditorFragment.java:1`) wires the custom `ShaderEditor` widget to undo/redo, completion listeners, and preference-driven appearance (font, ligatures, line numbers, update delay).
- `ShaderEditor` (`widget/ShaderEditor.java:1`) extends `LineNumberEditText`. Responsibilities: syntax highlighting (`highlighter/*`), debounce compile requests, auto-indent, completion entry, lint error highlighting. Highlights/errors are recalculated on a worker thread (`TokenListUpdater`).
- `UndoRedo` (`view/UndoRedo.java:1`) tracks a shared, process-wide edit history so the editor can preserve undo stacks between fragment recreations (history object is kept in `ShaderEditorApp.editHistory`).
- `ExtraKeysManager` (`activity/managers/ExtraKeysManager.java:1`) shows tab/brace buttons and completion chips, auto-hiding when the IME is closed if preferences demand.
- Compile errors from the GL renderer are visualized by `ErrorListModal` and `ErrorAdapter` (`widget/ErrorListModal.java:1`, `adapter/ErrorAdapter.java:1`).

### 4.4 Shader preview + rendering
- `ShaderView` (`widget/ShaderView.java:1`) wraps a `GLSurfaceView`, sets the GLES client version, installs a custom EGL context factory (prefers GLES3 but falls back to GLES2), forwards touch events to the renderer, and scrubs non-ASCII characters before sending shader source to OpenGL.
- `ShaderRenderer` (`opengl/ShaderRenderer.java:1`) is the core engine:
  - Parses shader code for `sampler2D`, `samplerCube`, and `samplerExternalOES` uniforms, manages backbuffer FBOs, thumbnails, and resolution scaling via the "quality" multiplier.
  - Exposes dozens of uniforms (time, resolution, multi-touch, sensors, battery, day/night, notification counts/time, camera frames, mic amplitude, wallpaper offsets, etc.). Sensor listeners live in `hardware/*` (e.g., `AccelerometerListener`, `CameraListener`, `MicInputListener`, etc.) and are registered only when the shader actually references the relevant uniform.
  - Taps Android services: `NotificationService` for notification uniforms, `BatteryLevelReceiver` for power state, media volume via `AudioManager`, wallpaper offsets, etc.
  - Uses `TextureBinder`, `TextureParameters`, and `BackBufferParameters` to manage imported textures/backbuffer configuration.
  - Publishes FPS + compile info logs back to UI threads via `ShaderRenderer.OnRendererListener`.
- Multipass (ShaderToy-style offscreen buffers): a shader may have up to four buffer passes (Buffer A-D), stored in the `shader_buffers` table (`database/dao/BufferDao.java`) and carried as extra project files (`ShaderProjectPaths.getBufferPath()`). `RendererProgramManager` prepares/compiles one `BufferPass` per buffer (its own program plus RGBA16F-where-possible ping-pong targets, or RGBA32F for a pass with a `///float32` line where the GPU can render and filter it - `GlDevice.supportsFloatTargets()`); `ShaderRenderer.drawChainRows()` runs the buffers in order before the Image pass, binding each pass's `iChannel0`-`3` per the `///buf:A;...` parameters on its declaration (`ChannelParameters`/`ChannelDeclarations`). Progressive rendering treats the whole chain as one tall frame. A `///steps:N` line in any pass (1-64, `RendererProgramManager.getSteps()`) runs the buffers N times per drawn frame, inside that same chain (`ShaderRenderer.getChainRows()`/`drawChainRows()`, so progressive mode and Custom Res/Seq capture step it too); `frame`/`iFrame` then count steps (`setStepFrame()`), and a paused redo re-runs only the last step. `ShaderSourcePreparer.adaptShaderToy()` wraps `mainImage()` and declares ShaderToy's implicit uniforms (`iTime`, `iResolution`, `iMouse`, `iChannelN`...; values in `BuiltinUniforms.bindShaderToyUniforms()`). The editor shows a pass-tab strip and iChannel row (`EditorFragment.setPassTabs()`); Other Options -> Offscreen Buffers sets the count. Copy, share, paste and opened files carry every pass as one text (`project/MultipassSource`: the Image pass, then `//// Buffer A` marker lines). Custom Res/Seq captures buffer shaders only at the live render size (Match App Res, no oversampling), by stepping the whole live chain (`stepSimulationFrame()`) and reading back the Image pass (`ShaderRenderPipeline.readCompletedFrameAsPng()`), on the live context; tiled/larger captures and the export-to-folder still cover only the Image pass.
- Adjust Defines (More Options; `MainActivity.showAdjustDefinesDialog()`, `adapter/DefineAdapter.java`, `opengl/ShaderDefineParser.java`) edits the active pass's `#define` values, plus (multipass) the other passes' that it hasn't got, one entry per name, marked with their tab (`ShaderDefine.passPath`); applying or going live sets every pass's define of that name (`MainActivity.withDefines()`). Its **Edit Live** mode turns ticked defines into uniforms (`ShaderDefineParser.toLiveSource()`, `ShaderDefine.toLiveLine()`) after a test compile (`ShaderView.testCompileAndWait()`; defines used by `#if`/`#ifdef` are refused up front) and shows `widget/LiveDefinesPanel` over the fullscreen shader. Values go through `opengl/LiveDefines`, bound in every `BuiltinUniforms.beginFrame()`. Going live, Done and Discard all swap the source with `ShaderViewManager.setProjectSessionKeepingState()` (`ShaderRenderer.setFragmentShader(..., keepState)`), which keeps the clock, buffer contents (`RendererProgramManager.adoptBufferTargets()`) and backbuffer instead of restarting. While paused, a live change redoes the last frame from the same inputs instead of stepping on (`ShaderRenderer.requestRedoLastStep()`: each ping-pong pair is swapped back and the frame counter rewound).
- `sourceText` (`opengl/SourceText.java`): each pass's own source as an RGBA8 texture (R code, GB reading-order index, A present), with `sourceTextSize`/`sourceTextLength`; `sourceTextColor` is its syntax highlighting in the same layout (RGB editor colour from `highlighter/Lexer` + `Highlight`, A = Highlight ordinal + 1), made only for a pass that declares it. Treated as a built-in like `backbuffer` (skipped by `ShaderSourcePreparer`'s sampler discovery). The Image pass's is made after each reload (`RendererProgramManager.createMainSourceText()`) and bound in `BuiltinUniforms.beginFrame()`; each buffer pass's is made lazily by `BufferPass.getSourceText()` and bound in `ShaderRenderer.drawChainRows()`. Only made for a program that declares it.
- `font` (`opengl/FontTexture.java`, `res/raw/font_atlas.png`): a built-in SDF glyph atlas (16x16 cells, ShaderToy layout, value in all four channels), generated by `tools/font/make_atlas.py` from IBM Plex Mono (OFL; licence in `tools/font/OFL.txt` and shipped as `res/raw/font_atlas_license.txt`, kept by `res/raw/keep.xml`). Made by `ShaderRenderer.updateFontTexture()` after a reload when any pass declares `font` or a `///tex:font;` channel, and bound in `BuiltinUniforms.beginFrame()` before saved textures (so a saved texture named `font` still wins). Channels: `ChannelParameters.getTexture()`, the editor's channel menu offers Font.
- `colormaps` (`opengl/ColormapTexture.java`, `res/raw/colormaps.png`): a built-in colour map atlas, one map per row, 256 steps low (u 0) to high (u 1): Ready's ten, then the 77 CET maps (CC BY 4.0, credits in `res/raw/colormaps_license.txt`), as `tools/colormaps/names.txt` lists them; row r at v = 1 - (r + 0.5) / `colormapCount`. Built by `tools/colormaps/make_atlas.py`; made by `ShaderRenderer.updateColormapTexture()` when a pass declares `colormaps`, bound in `BuiltinUniforms.beginFrame()`.
- Keyboard (`opengl/KeyboardInput.java`): `keyboard` (ShaderToy-style 256x3 texture by JS key code: held/pressed/toggled) and `typedChar` (queued, one per frame). `MainActivity.sendKeyToShader()` feeds it from `dispatchKeyEvent()` while `ShaderRenderer.isKeyboardWanted()` (set by `updateKeyboard()` after a reload) and the editor is hidden or fullscreen; applied in `BuiltinUniforms.beginFrame()`. Channels: `///tex:keyboard;`. Type into Shader (More Options; `MainActivity.startTypingIntoShader()`) shows the soft keyboard for `widget/ShaderKeyInputView`, whose `InputConnection` turns commits/deletes into `typedChar` and keyboard taps; it ends on the chip, the IME closing (window insets), or the editor opening.
- Self-editing shaders: a buffer source with `///text:Image` (or A-D; `opengl/TextDirective.java`) gets that pass's source as its `sourceText` (`BufferPass.setTextSource()`), with `sourceTextVersion` bumping per `SourceText`. Run Typed Text (toolbar button `run_shader_text`, shown by `MainActivity.updateRunShaderTextButton()` only for such shaders; `MainActivity.runShaderText()`) reads the buffer back (`opengl/BufferText.java`: row n = line n, R = code, tab = 9 then 0s, top row skipped; half-float buffers read in the GPU's read format), test-compiles (a failure is only a toast), then swaps it in with `setProjectSessionKeepingState()` and updates the editor/session. Load Sample splits multipass sample text (`ShaderManager.splitSampleBuffers()`), e.g. `res/raw/sample_notepad.glsl`.
- Sound passes (`opengl/SoundOutput.java`): a buffer source with `mainSound()` and no `main`/`mainImage` (`ShaderSourcePreparer.isSoundSource()`) is wrapped to write one stereo sample per texel (16-bit L/R as byte pairs in RGBA8, `enkiSoundOffset` + texel index) and kept out of the drawn buffers (`RendererProgramManager.getSoundPass()`). Each frame `ShaderRenderer.renderSound()` renders what the `AudioTrack` is short of (~0.1 s ahead, start threshold one block), reads it back and queues it; it pauses with the shader and restarts with it (`updateSound()`). The editor labels its tab Snd (`EditorFragment.setPassTabs()`); `MultipassSource` maps ShaderToy's `//// Sound` to a free buffer.
- Upscaling: how the on-screen surface pass scales a frame rendered below the screen's resolution (`opengl/Upscaling.java`) - nearest, sharp (anti-aliased pixels), linear, bspline, catmull, sinc (Lanczos, amount = lobes, 2-6), defocus (a bokeh disc, amount = radius in texels), bokeh ("fancy defocus": a seven-bladed iris with a bright rim, red/blue fringes and cat's-eye vignetting towards the edges, amount = radius) crt (an old TV seen close up: lines at least 8 screen px apart, each a beam between dark scanlines; a staggered slot mask of soft triads at least 12 px wide, wider than tall; a slight bulge and rounded corners, convergence error, glow, static, flicker, a rolling hum bar and line wobble - animated by `upscaleTime`; amount = scanline/mask strength) or gaussian (soft at any radius: the frame blurred at its own size in two separable passes - `BLUR_FRAGMENT_SHADER`, `getBlurBindings()`, `ShaderRenderPipeline.preBlur()` - then B-spline upscaled; amount = sigma in texels, 0.5-8) - with what's past the frame's edges (clamp, periodic, mirror, black); Default samples the frame as its own (backbuffer) filtering says. It's the view's, not the shader's: `ShaderRenderer.setUpscale()`, kept for the app's session by `ShaderViewManager.setUpscale()` (static), starting from Settings → Performance → Default upscaling (`Preferences.DEFAULT_UPSCALE`, an `Upscale.spec()` string; `Upscale.parse()` falls back to Default for anything it doesn't know). The quality spinner's last entry, Upscaling…, opens `widget/UpscaleDialog` (each choice previewed live; Settings opens the same one with `forDefault`). All of it lives in `RendererProgramManager.SURFACE_FRAGMENT_SHADER`, compiled once per filter, edge and reach (`Upscale.reach()`) as constants (`getUpscaleBindings()`, kept while asked for) - one program with them as uniforms ran about 6x slower on the phone. `ShaderRenderPipeline.drawSurface()` uses it only on screen (thumbnails, and captures by default, are of the frame itself). Custom Res/Seq's Include Upscaling (only with Match App Res Setting) makes captures come out as the screen shows them: `ShaderRenderer.setCaptureUpscaled()` has each live-size frame drawn through the upscaling at the view's size (`readUpscaled()`, on the capture's own context), Video Scale 1, the video made at that size.
- Tracker modules: `///mod:name` (`opengl/ModuleDirective.java`) plays a module from the `modules` table (`database/dao/ModuleDao.java`, DB v14) through `opengl/ModulePlayer.java` (vendored IBXM in `app/src/main/java/ibxm`, lint-ignored; its own thread and AudioTrack; per-tick snapshots matched to the playback head). `ShaderRenderer.updateSound()` starts/stops it on reload, `updateModule()` fills `moduleInfo`/`moduleScope` each frame and `BuiltinUniforms` binds them with `moduleTime`/`moduleRow`. `setSoundEnabled(false)` (the wallpaper) silences Sound passes and modules. `audioSpectrum` hears the app's own sound when the shader has some and `use_own_sound_audio` is on (default): `ModulePlayer`/`SoundOutput` each write an `opengl/OwnSoundTap` (mono ring + how much is still queued in the track), and `BuiltinAudioUniforms.apply()` pushes what's been heard into an `AudioSpectrumListener.registerPushed()` listener in place of any capture - no RECORD_AUDIO needed. Import via More Options → Import Tracker Module (`MainActivity.importModule()`); samples can name a bundled module (`ShaderManager.BUNDLED_MODULES`).
- Shader texts: named plain-text blocks per shader (`shader_texts` table, DB v15, `database/dao/ShaderTextDao.java`), project files at `text/<name>.txt` (`ShaderProject.getTexts()/withTexts()`), with editor tabs at `EditorFragment.TEXT_PASS_BASE + i` and Offscreen Buffers → Texts… (`MainActivity.showShaderTextsDialog()`). `ShaderTextureResources.load()` binds a sampler named after a text to a `SourceText`-layout texture plus `<name>Size`/`<name>Length`; `ShaderRenderer.setShaderTexts(texts, live)` - live re-uploads only (`reloadTexts()`), no recompile (`ShaderViewManager.setProjectSessionIfChanged()`). Copied with the shader, imported per shader (`ShaderDao` → `ShaderTextDao.importTexts()`), written into packs, and carried by `MultipassSource` as `//// Text <name>` sections (samples too - `sample_demo_writer.glsl`).
- Presentation packs: `packs.show` / `pack_info.show` hold `pack/PackShow.java`'s JSON (seconds, `module` or `soundShader` soundtrack, optional `scenes` timeline with #define keys). Set in `ShaderPackBuilderDialog`; played by `activity/ShowActivity.java` (slides from `PackDao.getShaderIds()`, soundtrack injected per slide, keyed defines via `toLiveSource()` + `LiveDefines`). `ShaderRenderer.setShowSoundtrack()` keeps sound running through reloads (and `unregisterListeners(true)` from `ShaderView.setFragmentShader()` doesn't pause it). The tree marks show packs (`ShaderNameNode.show`, `ShaderListManager.Listener.onPlayShow()`); Get Shader Pack has Play Show. `quality` in the JSON overrides each shader's render quality. ShowActivity compiles every slide's passes ahead (`HeadlessShaderCompiler.warm()`) into its own in-memory `ProgramBinaryCache`, and dips through black between slides until `onFirstFrameAfterResume()`. Long-pressing a pack in the tree offers Edit Pack (`ShaderPackBuilderDialog.edit()`, pre-filled, next version) and Update Version (`widget/UpdatePackDialog`, versions from `ShaderPackInstaller.findVersions()` - MediaStore Downloads, so only this app's own saved files without a permission - swapped in by `ShaderPackInstaller.replace()`). `PackDao.writePackFile()` strips installed pack shaders' "<Pack Title>: " prefix.
- `ShaderViewManager` (`activity/managers/ShaderViewManager.java:1`) coordinates `ShaderView` lifecycle with the activity, exposes the quality spinner, and pushes renderer events to `MainActivity`.
- `PreviewActivity` (`activity/PreviewActivity.java:1`) runs shaders fullscreen when the user prefers manual runs. Its static `RenderStatus` object transports FPS/errors/thumbnail data back to `MainActivity`.

### 4.5 Wallpaper & services
- `ShaderWallpaperService` (`service/ShaderWallpaperService.java:1`) hosts a `GLSurfaceView` inside a live wallpaper engine. It listens for preference changes (`Preferences.WALLPAPER_SHADER`, `SAVE_BATTERY`), enforces low-power render modes when the receiver marks the battery low, and reuses `ShaderView`/`ShaderRenderer`.
- `NotificationService` (`service/NotificationService.java:1`) is a notification listener. Its static getters feed `ShaderRenderer` uniforms `notificationCount` and `lastNotificationTime`. It prompts the user to enable the listener when needed.
- `BatteryLevelReceiver` (`receiver/BatteryLevelReceiver.java:1`) toggles low-power mode and power-connected flags and updates wallpaper render modes.

---

## 5. Persistence & data layer
- `Database` (`database/Database.java:1`) is a singleton façade around `SQLiteOpenHelper` with schema version 5. It builds tables via `DataSource.buildSchema()` and exposes DAOs.
- `DataSource` (`database/DataSource.java:1`) bundles the DAOs and is retrieved everywhere via `Database.getInstance(context).getDataSource()`. Never cache raw `SQLiteDatabase` objects outside DAO scope.
- `ShaderDao` (`database/dao/ShaderDao.java:1`):
  - CRUD for shaders, thumbnails (PNG blobs), and quality multiplier.
  - `insertShaderFromResource` seeds new shaders from `res/raw/*.glsl`.
  - Column definitions live in `DatabaseContract`.
  - Names are optional; UI falls back to the last-modified timestamp (`DataRecords.ShaderInfo.getTitle()`).
- `TextureDao` (`database/dao/TextureDao.java:1`):
  - Stores user-imported 2D textures and cube maps (ratio differentiates them: cube maps are 3:2 atlases, anything else is 2D).
  - `insertExactTexture()` stores a PNG's own bytes untouched (Add Texture -> Exact Pixels, `widget/ExactTextureImportDialog`), for textures that hold data; matrix PNGs are decoded unpremultiplied and uploaded bottom row first, so `texelFetch()` reads them back exactly.
  - Saves both thumbnails (PNG) and full-resolution PNG matrices. Dimensions feed sampler defaults.
- `DataRecords` (`database/DataRecords.java:1`) holds immutable records used throughout UI/DAOs.
- Shader packs (curated sets meant to be sold): a pack file is a standalone SQLite database with the app's own tables holding just the pack's shaders, buffers and textures, plus `pack_info` (id, title, blurb, author, version) and `pack_media` (thumbnails now, screenshots later) - written by `io/ShaderPackWriter` via `PackDao.writePackFile()` (Build Shader Pack, or long-press a sidebar name-tree group), installed by `io/ShaderPackInstaller` (Get Shader Pack -> Install from File, or opening/sharing a `.sqlite` file), which tags each shader's `pack_id` and records the pack in the `packs` table (`database/dao/PackDao`). Get Shader Pack lists `assets/packs/*.json` previews (`pack/ShaderPackCatalog`); buying and delivery go through `pack/ShaderPackStore` / `ShaderPackDelivery`, stubbed (`Unavailable*`) until Play Billing / Play Asset Delivery are wired in.
- Import/export:
  - `ImportExportAsFiles` (`io/ImportExportAsFiles.java:1`) reads/writes `.glsl` files under `Downloads/EnkiShaderEditor`. Uses legacy storage APIs on Android < Q; guarded by runtime permissions from `PreferencesFragment`.
  - `DatabaseImporter` / `DatabaseExporter` support SAF-based SQLite exports/imports.
  - Texture import flows go through `BitmapEditor` and `TextureDao`.

---

## 6. Uniforms, textures, and samples
- Adding uniforms:
  - `AddUniformActivity` (`activity/AddUniformActivity.java:1`) hosts `UniformPagesFragment` (ViewPager with presets, 2D textures, cube maps). The activity registers ActivityResult launchers for picking/cropping images and returns the selected GLSL statement in the result bundle.
  - `UniformPresetPageFragment` (`fragment/UniformPresetPageFragment.java:1`) shows the built-in uniform list from `PresetUniformAdapter`. Sampler presets launch `TextureParametersFragment`.
  - `UniformSampler2dPageFragment` and `UniformSamplerCubePageFragment` (`fragment/UniformSampler2dPageFragment.java:1`, `fragment/UniformSamplerCubePageFragment.java:1`) list user textures via `TextureAdapter`, async-loaded through `TextureDao`.
  - `TextureViewFragment` / `TextureViewActivity` let users preview a texture, delete it, or insert the uniform statement for it.
  - `TextureParametersFragment` (`fragment/TextureParametersFragment.java:1`) formats sampler parameters (wrap, filter, backbuffer options) before returning the statement to `AddUniformActivity`.
- Texture creation:
  - `CropImageActivity` + `CropImageFragment` (`activity/CropImageActivity.java:1`, `fragment/CropImageFragment.java:1`) handle picking and cropping an image prior to import.
  - `Sampler2dPropertiesFragment`, `CubeMapActivity`, `CubeMapFragment`, and `TextureParametersView`/`BackBufferParametersView` (under `widget/`) finalize sampler metadata.
- Samples:
  - `LoadSampleActivity` with `LoadSampleFragment` (`activity/LoadSampleActivity.java:1`, `fragment/LoadSampleFragment.java:1`) surfaces sample shaders from `res/raw/sample_*.glsl` via `SamplesAdapter` (`adapter/SamplesAdapter.java:1`). `ShaderManager.loadSampleLauncher` inserts the sample into the DB and selects it.

---

## 7. Preferences and behavior flags
- Preference keys/constants live in `Preferences` (`preference/Preferences.java:1`). Important toggles:
  - Run mode (auto vs manual vs manual-with-preview activity).
  - Update delay/debounce for shader recompiles.
  - Editor styling (font, tab width, ligatures, extra keys, line numbers).
  - Auto-save, save-on-run, default shader template ID, default wallpaper shader, "show extra keys".
  - Sensor delay, hide native IME suggestions.
  - Import/export directory preferences (only used for labeling).
- `PreferencesFragment` (`fragment/PreferencesFragment.java:1`) renders `res/xml/preferences.xml`, requests runtime permissions for file I/O, and wires preference click listeners for import/export, database backup, etc. It also updates preference summaries (shader pickers via `ShaderListPreference`/`ShaderListPreferenceDialogFragment`) and triggers `BatteryLevelReceiver` when save-battery changes.
- Many runtime checks reference `ShaderEditorApp.preferences`. Always read/update through this singleton to keep UI/resume logic consistent.

---

## 8. Sensors, camera, audio, and permissions
- Sensors (accelerometer, gyroscope, gravity, rotation vector, magnetic field, light, linear acceleration, pressure, proximity) share a base `AbstractListener` (`hardware/AbstractListener.java:1`). `ShaderRenderer` registers/unregisters them based on uniforms in the shader source. Sensor delays honor the preference set in settings.
- Camera textures use `CameraX` (`hardware/CameraListener.java:1`) with `ProcessCameraProvider` and `SurfaceTexture`. Both front/back cameras are exposed, along with orientation matrices and addent vectors (`UNIFORM_CAMERA_ADDENT`, `UNIFORM_CAMERA_ORIENTATION`). The wallpaper engine acts as the `LifecycleOwner` when rendering live wallpapers.
- Microphone input (`hardware/MicInputListener.java:1`) samples amplitudes via `AudioRecord`. RECORD_AUDIO permission must already be granted (no custom request flow here).
- Notifications require the Notification Listener permission; `NotificationService.requirePermissions()` prompts with a dialog.
- Media volume uniforms query `AudioManager` each frame.
- Battery events rely on `BatteryLevelReceiver`, coupled with the `save_battery` preference to switch `GLSurfaceView` render modes.

---

## 9. Services, receivers, and background components
- `ShaderWallpaperService` – see §4.5.
- `NotificationService` – see §4.5.
- `BatteryLevelReceiver` – see §4.5.
- `ShaderWallpaperService` toggles the manifest-enabled state of `BatteryLevelReceiver` based on lifecycle to avoid wakeups when not running.
- `ShaderWallpaperService.ShaderWallpaperView` extends `ShaderView` but overrides `getHolder()` to render into the wallpaper surface.

---

## 10. Import/export & file handling
- File import/export only works on API < 29 because scoped storage removed direct SD access; for newer versions the preference entries are hidden (`PreferencesFragment.wireImportExport()`).
- Database import uses the Storage Access Framework with MIME types `application/x-sqlite3`, `application/vnd.sqlite3`, and `application/octet-stream`.
- Texture imports rely on SAF `ACTION_GET_CONTENT` flows and optionally cropping/resampling to avoid OOM (see `BitmapEditor.getBitmapFromUri()`).
- When exporting shaders, tabs can be converted to spaces based on `export_tabs` preference before sharing (handled inside `NavigationManager.shareShader()`).
- `tools/ready/vti2enki.py` (desktop) converts Ready (GollyGang/ready) 2D formula `.vti` patterns into multipass shader text: up to 8 chemicals as `///float32` buffers (4 a buffer; 4 steps a frame up to 4 chemicals, 2 up to 8), Ready's stencils/initial-pattern overlays/colour maps ported, parameters as `#define`s, and finger painting (`pointers`; one finger paints a and b as the initial pattern does - `rdInitial()`, fresh noise each frame, `--paint "a=init,b=init"` - within `BRUSH_SIZE` (a radius, as a fraction of the grid's shorter side, so the same on screen at any quality); two erase to `ERASE_*` (`--erase "a=1,b=0"`) within `ERASE_SIZE` times that, in every step) in each frame's first step, after a touch has lasted `PAINT_DELAY` (0.2 s). The top row of every buffer is state, not grid (texel (0, top): most fingers this touch, its start time, the view); three fingers switch the Image pass between the colour view and every chemical as a labelled black-and-white mask (`MASK_LOW_*`/`MASK_HIGH_*`; `--masks` starts there). Writes `///steps:4` (`--steps`). Its interface (UI_COMMON/UI_UPDATE/IMAGE_VIEWS) keeps state along the buffers' top row: three fingers switch to tiles of every chemical plus the colour view, with buttons just above the tiles (`rdButtonsBottom()`): a colour map button (tap: the next `colormaps` row; hold: a strip of every map to drag through, mode 5), a random button (tap: a random composite look, `rdRandomLook()`; hold: wandering, `rdWanderStep()` every step - a small random nudge plus a small pull towards a look that shows every chemical, from an 8x8 sample of the grid (`rdGuide()`/`rdLevelStats()`): hues spread round the circle by how much each chemical varies (complementary for the top two), powers putting each one's typical level mid-way (complemented if mostly high), weights by variation; on release back to a snapshot one or two `RANDOM_REWIND` periods old, `S_SNAP`), the map picker's frequency zone (drag right off the strip, past a gap: `S_MAP` frequency 1 to `MAX_FREQUENCY` 4, the map repeating over the colour view's range - `rdRepeat()`), the brush and composite colour; a tap on a tile shows it full screen, a tap there goes back; painting on a chemical's tile or full screen paints just that chemical; composite colour adds its chemicals' parts (no normalising: weights summing to `COMPOSITE_GAIN` make an average look, 0 hides one, negative darkens), each chemical's tile (and full screen) showing its part (`rdContribution()`: tinted, to its power, by its weight), hold a chemical's tile for a hue/saturation wheel, two fingers drag its weight and power (bars shown only while dragging), a two-finger tap complements it (1 - level, before the power); a brush button (dragged: what one finger paints, with a second finger what two erase; tapped: back to the file's own) sets the paint values. A simulation starts its initial pattern only when its buffers are blank (a mark of 2 in S_FINGERS.w - `rdStarted()`; new buffers clear to alpha 1), not on `iFrame == 0`, so a run survives the surface being recreated. `LAPLACIAN_SCALE` (a parameter) scales every `laplacian_*`: below 1 the stencil's value times its square; above 1 a Gaussian's (`rdWideBlur()`, sigma = 0.7 x the scale, so as stable as the stencil at the same timestep, reading more cells the larger). Every step ends with `rdSane()`: a NaN/infinite value (`rdBad()`, from its bits) goes back to the step's previous value (or 0); nothing else is clamped. Check its output with `tools/samples/check_shader.py`; `tools/ready/examples/` holds VTIs to try it on (two Gray-Scott history-wave patterns, and Dan's own orbits rule, which uses `atan2`, `pow` and direct chemical assignments).
- `tools/ready/vtipack.py` (desktop) packs a Ready collection small, reading the source folder only: `index` groups files into families (same rule type, chemicals and code) with the parameter sets that vary them, notes exact duplicates and keeps every header (`index.json`, `headers.json.xz`); `build` stores each saved state as the cheapest seed that still restarts it (`states/<md5>.seed` + `.json` saying why), resumable and splittable (`--part i/n`); `expand` writes a `.vti` back with its state grown from the seed; `report` sums it up. Seeds are in `vtiseed.py` (synthetic noise fitted to each chemical's quantiles, spectrum and correlations; typical or busiest 8-bit patches, tiled or scattered; or the whole state 8/16-bit or exact), `.vti` state reading/writing in `vtistate.py`. The check is `rdheadless.py`: vti2enki's buffer passes run headless on the desktop GPU (PyOpenGL, EGL, GLES 3) from the saved state and from each seed, which passes if, after at most 2,000 steps of warm-up and stretched 1-2x in time, every chemical's mean and std stay within 10% of the saved run's (of its spread, or a quarter of its level). Files whose initial pattern applies on load need no state. Anything awkward is left out, with its reason: states nothing restarts, ones over 1024² that no seed restarts, runaway rules, and rules vti2enki can't convert.

---

## 11. Coding conventions & dependencies
- Follow `CONTRIBUTING.md`: tabs for indentation, EditorConfig is authoritative, align with Android Studio defaults otherwise. Keep commits focused (one feature per commit) and prefer squash merges.
- Most helpers live under `activity/managers/`; resist the urge to introduce new global singletons—prefer scoped manager classes or fragments.
- Shared state flows through the `ShaderManager`/`ShaderListManager`/`ShaderViewManager` triad. If you add new shader metadata, update all of them plus the DAO and UI surfaces.
- Rendering code runs on GL threads; do not touch UI widgets from inside `ShaderRenderer`. Use `queueEvent()` or the provided listener callbacks to hand results back to the main thread.
- Keep database interactions inside DAOs; they already open/close databases in `try-with-resources`. Long-running reads are dispatched to single-thread executors + main-thread handlers (follow the patterns in `ShaderListManager` and `UniformSampler2dPageFragment`).
- When adding uniforms or sensors, define new constants in `ShaderRenderer`, expose them in `PresetUniformAdapter`, and wire end-to-end (renderer uniform location, value assignment, UI copy).
- Build/res dependencies:
  - Material components for dialogs, tabs, snackbars.
  - `androidx.preference` for settings.
  - `CameraX` for live camera textures.
  - No Jetpack Compose/Navigation; fragments are added manually via helper methods in `AbstractSubsequentActivity`.

---

## 12. Debugging, logging, and testing
- The project doesn’t ship automated unit/instrumentation tests. Rely on manual testing, lint (`./gradlew lintEnkiDebug`, and `lintUltrarestareaDebug`), and static analyses (`make infer`).
- Debug builds enable StrictMode (thread + VM violations), so fix any disk/network access on the main thread before shipping.
- Compile/GLSL errors:
  - For live preview (`ShaderView`), errors are surfaced through the UI (Snackbar + `ErrorListModal`).
  - For manual runs (`PreviewActivity`), error info is stored in `PreviewActivity.renderStatus.infoLog`.
- Use `adb shell dumpsys meminfo ...` or `make meminfo` to inspect memory usage; `make glxinfo` dumps gfx stats.
- Wallpapers should be tested both with `save_battery` enabled/disabled and while toggling device charging state to verify render mode switching.
- Verify notification uniforms by granting the listener permission via the system dialog triggered in `NotificationService`.

---

## 13. Common change scenarios
- **Add a new sensor/uniform**: extend `ShaderRenderer` (new constant, uniform location lookup, value updates), register/unregister a new listener in `hardware/`, add it to `PresetUniformAdapter`, and document it in `FAQ.md`/UI strings. Consider its availability (API level) and gating preferences.
- **Add shader metadata** (e.g., tags): update `DatabaseContract`, bump DB version, add columns to `ShaderDao` CRUD, persist via `ShaderManager`, show via `ShaderAdapter`/layouts.
- **Add a new sample shader**: drop a `.glsl` file + thumbnail in `res/raw`/`res/drawable`, extend `SamplesAdapter` entry, add translation strings for name/rationale, and bump screenshots if needed.
- **Modify shader preview behavior**: touch `ShaderViewManager`, `ShaderRenderer`, and `MainActivity`. Remember manual-run flows rely on `PreviewActivity`, not the embedded GLSurfaceView.
- **Adjust import/export**: modify `ImportExportAsFiles` and the preference wiring; keep runtime permissions + scoped storage differences in mind.
- **Extend preferences**: add entries to `res/xml/preferences.xml`, define keys/defaults in `Preferences`, update `PreferencesFragment` summaries and any code paths referencing the new setting.
- **Release prep**: update `CHANGELOG.md`, `fastlane/metadata/**/changelogs`, bump `versionCode`/`versionName` in `app/build.gradle.kts`, run `make release` or `make bundle`, and refresh screenshots if UI changed.

---

## 14. Reference docs & assets
- `README.md` – feature overview, download links, support info.
- `FAQ.md` – answers referenced from settings (NavigationManager → FAQ).
- `PRIVACY.md` – sensors/camera/mic usage disclosure (needed for stores).
- `CONTRIBUTING.md` – workflow, formatting rules, EditorConfig hints.
- `fastlane/metadata/**` – store copy/screenshots; keep translations consistent with `res/values-*`.

---

## 15. Tips while editing
- When adding string resources/translations, always add them for all supported languages.
- Prefer `ShaderEditorApp.preferences` for all preference reads/writes to keep state centralized.
- Respect the async loading patterns: DAO operations should not touch the UI thread; use `Executors` + `Handler` the same way existing code does.
- Mind multi-window: `PreviewActivity` is marked `singleInstance` and may run adjacent to `MainActivity` if `RUN_MODE` is set accordingly.
- Keep GLSL sources ASCII-only to avoid compiler crashes (`ShaderView.removeNonAscii()` already enforces this).
- When touching layout/system bar behavior, update `SystemBarMetrics` to maintain consistent inset handling.
- For wallpaper-specific bugs, remember the engine uses `GLSurfaceView.RENDERMODE_WHEN_DIRTY` when on low battery; test both render modes.
- Always recycle/release bitmaps and textures (see `TextureViewFragment` and `BitmapEditor`) to avoid spikes.

With this map you should be able to navigate the project quickly, understand the impact of a change, and know where to plug new functionality in. Refer back whenever you need to chase a flow—from GLSL compilation, to sensor wiring, to persistence, to the UI. Happy hacking!
