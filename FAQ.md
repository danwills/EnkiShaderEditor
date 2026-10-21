# Frequently Asked Questions

## How do I use GLES 3.0 (or 3.1 / 3.2)?

Declare requested GLES version in **very first line** of shader:

```glsl
#version 300 es
```

You can also use `310 es` or `320 es` if your device supports it.

In GLES 3.x, fragment output changes from `gl_FragColor` to your own `out vec4` variable.
Enki Shader Editor detects version directive and creates matching GLES context automatically.

Do not put comments or blank lines before `#version ...`.

## How many FPS do I need for a live wallpaper?

Some devices limit GPU usage to consume less power when not plugged in.
Always check performance with soft keyboard hidden and power cord off.

As rough rule, shader should make at least around **30 FPS** to avoid slowing down UI.
If it does not, lower render quality with quality spinner (`1/2`, `1/4`, etc.).

## How much battery does a live wallpaper use?

Live wallpaper should only consume battery when you actually see it.
So real impact depends on how often and how long you look at it.
With normal use, you usually should not notice much difference.

When **Save battery** is enabled, rendering pauses on low battery to reduce power use further.

## What if errors are not highlighted?

Unfortunately error information is disabled on some devices and GPU drivers.
Error highlighting and reporting are not possible on those devices.

Shader can still compile and run normally even when driver does not report useful error logs.

## `Set wallpaper` does not work

You need to set Enki Shader Editor as live wallpaper first.
Go to system **Settings → Display → Wallpaper → Live Wallpapers** and choose **Enki Shader Editor**.

As soon as Shader Wallpaper is active live wallpaper, you can change what shader is running from app menu with **Set as wallpaper** and **Update wallpaper**.

## Why can’t both cameras be opened at same time?

This is system limitation.
Android only allows one camera to be opened at a time.
No app can use both front and back camera simultaneously.

## `time` uniform loses accuracy / framerate drops over time

Most probably `time` is medium precision float in fragment shader.
That is default on many devices and it eventually loses precision.

Try high precision if hardware supports it:

```glsl
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
```

Or use `second`, `subsecond`, or `ftime` uniforms instead.
See issue [#10](https://github.com/markusfisch/ShaderEditor/issues/10#issuecomment-160463706) for details.

## Values in a big `const` array come out wrong

Some GPU drivers don't handle large constant arrays reliably.
On a Galaxy S22 Ultra (Adreno GPU), a `const vec4` array of about 1,500 entries came back with most of its `.y` components wrong, while `.x` and `.z` were fine.
A `const float` array of the same values was almost right, with one entry still wrong.
Splitting the array into smaller ones made it worse.
Indexing such an array with a variable can also be very slow.
Other devices may behave differently.

If a shader needs a big table of numbers (lookup tables, neural network weights, glyphs), keep it in a texture instead and read it with `texelFetch()` (GLES 3.0).
Add it with **Exact Pixels** (see “Can I use custom textures?” below) so every value is kept exactly, including alpha.
Integer constants above 2^24 can also lose precision on some GPUs, so use several smaller numbers instead.

To check a table on your device, draw its entries as pixels and compare them with the values you expect.

## How can wallpaper animations continue after a pause?

Use `uniform highp float runtime;` instead of `time` for animation time.
`runtime` counts seconds between rendered frames and excludes pauses.
The first frame after a pause uses the same value as the last frame before it.
Unlike `time`, it survives surface and OpenGL context recreation.

The active wallpaper saves its runtime per shader when hidden, changed, or
stopped, and restores it when loaded again. Editor and wallpaper previews
have separate, temporary runtimes which reset when a shader is loaded.
An abrupt process termination can lose time since the last save.

This preserves animation time, not backbuffer contents, random values, or
sensor input. As with `time`, floating-point precision decreases over time;
use high precision where supported.

## How does backbuffer work?

Declare backbuffer like this:

```glsl
uniform sampler2D backbuffer;
```

Then sample previous frame with something like:

```glsl
vec4 prev = texture2D(backbuffer, uv);
```

This enables stateful effects like trails, blur, feedback, and cellular automata.

If you add `///p:noise` after declaration, backbuffer starts with noise texture instead of black:

```glsl
uniform sampler2D backbuffer;///p:noise
```

## How do I visualize audio?

Declare the audio uniform like this:

```glsl
uniform sampler2D audioSpectrum;
```

It's a 512x2 texture matching ShaderToy's audio input layout: sample `vec2(x, 0.25)` for the
frequency spectrum (low to high) and `vec2(x, 0.75)` for the waveform, both 0.0 to 1.0:

```glsl
float freq = texture2D(audioSpectrum, vec2(x, 0.25)).x;
float wave = texture2D(audioSpectrum, vec2(x, 0.75)).x;
```

By default this listens to the microphone (needs the record audio permission). Enable
**Settings → Use system playback audio** to analyze what the device is playing back instead —
you'll be asked to grant a one-time capture permission each time you run a shader that uses it.
When the system dialog appears, choose **Entire screen**, not **A single app** — picking an app
switches you to it instead of capturing its audio, since Android requires that app to be in the
foreground to share its playback. If you cancel the prompt, toggle the setting off and back on to
ask again. System playback audio only works while running a shader in the app; the live wallpaper
and manual preview always fall back to the microphone.

## Can a shader read its own source?

Yes - declare:

```glsl
uniform sampler2D sourceText;
uniform vec2 sourceTextSize;    // columns, lines
uniform float sourceTextLength; // number of characters
```

`sourceText` holds the source of the pass it's declared in, one texel per character: texel row
`n` is line `n` (so line 0 is at `v = 0`). A tab is code 9 followed by code-0 cells up to the
next tab stop (the editor's tab width), so text lines up as in the editor. Each texel has the character code in R, the character's index in reading
order as `G * 256 + B` (handy for typing the text on in order), and A = 1 for a character or 0
past the end of a line. Look one up by column and line:

```glsl
vec4 t = texture2D(sourceText, (vec2(column, line) + 0.5) / sourceTextSize);
float code = floor(t.r * 255.0 + 0.5);
float index = floor(t.g * 255.0 + 0.5) * 256.0 + floor(t.b * 255.0 + 0.5);
```

Lines are cut at 512 columns, and the text at 2048 lines.

For colour, `uniform sampler2D sourceTextColor;` is the same text's syntax highlighting, in the
same layout: RGB is the colour the editor shows that character in, so text drawn with it looks
like the editor, and `floor(a * 255.0 + 0.5)` is the kind of token it's part of (0 past the end of
a line), for choosing your own colours.

To draw text, there's a built-in font too:

```glsl
uniform sampler2D font;
```

It's a 16x16 grid of glyphs for character codes 0-255, in the same layout as ShaderToy's font
texture (code `n` is in column `n % 16`, row `n / 16` from the top), stored as a signed distance
field: 0.5 on a glyph's edge, higher inside. So text stays crisp at any size or warp:

```glsl
vec2 cell = vec2(mod(code, 16.0), 15.0 - floor(code / 16.0));
float d = texture2D(font, (cell + p) / 16.0).r; // p: 0-1 within the cell
float ink = smoothstep(0.5 - w, 0.5 + w, d);    // w: about 4.0 / cell height in pixels
```

Glyphs are centred in their cells and about 0.37 of a cell wide. A ShaderToy-style shader can
read it through a channel instead, with `uniform sampler2D iChannel1; ///tex:font;` (or pick
**Font** for the channel in the editor's channel row). The glyphs are rendered from IBM Plex Mono
(SIL Open Font License).

## Can a shader keep text out of its code?

Yes - give it a *shader text*. Open **Offscreen Buffers → Texts… → Add Text** and name it (a
GLSL identifier, e.g. `scroller`); it gets its own tab beside the passes, as plain text. Any pass
reads it by declaring a sampler of the same name:

```glsl
uniform sampler2D scroller;
uniform vec2 scrollerSize;    // columns, lines
uniform float scrollerLength; // number of characters
```

It's laid out exactly like `sourceText` (above): one texel per character, row n is line n, R the
character code, G*256+B its index in the whole text, A 255 for a character and 0 past the end of
a line - so draw it with the `font` atlas the same way. Edit the text while the shader runs and it
updates on the next frame, without recompiling or restarting. Texts go with the shader into
copies, backups, shader packs and shared or copied text (as `//// Text <name>` sections). The
**Demo Writer** sample scrolls one round a twisting bar.

## Can a shader edit its own source?

Yes - see the **Notepad** sample. A shader with an offscreen buffer can keep an editable copy of
a pass's text in that buffer and type into it (see the keyboard question below). A line

```glsl
///text:Image
```

in the buffer's source (or `///text:A` to `///text:D`) marks it: that buffer's `sourceText` is
then the named pass's source, to copy from, and `sourceTextVersion` changes whenever that source
does, so the buffer knows when to copy it again. The buffer holds the text the way `sourceText`
does - row `n` is line `n`, R is the character code (0 for none; a tab is 9 then 0s) - with its
top row left free for the shader's own use, such as a cursor.

A self-editing shader gets a **Run Typed Text** button in the toolbar. It reads that buffer back
and, if the text compiles, makes it the named pass's source - in the editor too - without
restarting the shader, so the buffer, and what's typed in it, carry on. If it doesn't compile, a
toast says why and the shader keeps running as it was. Nothing is ever run without pressing it.
Save as usual to keep it.

## Can a shader read the keyboard?

Yes, a hardware or Bluetooth keyboard:

```glsl
uniform sampler2D keyboard; // like ShaderToy's: 256x3, by JavaScript key code
uniform int typedChar;      // the character typed this frame, or 0
```

`keyboard` is laid out like ShaderToy's keyboard texture, indexed by JavaScript key code (65 for
A, 37-40 for the arrows, 13 for Enter, 32 for Space...): row 0 is 1 while a key is held, row 1
for the frame it went down in (and again each time it auto-repeats while held), and row 2 flips
each time it's pressed:

```glsl
float held = texture2D(keyboard, vec2((37.0 + 0.5) / 256.0, 0.5 / 3.0)).r; // Left
```

`typedChar` gives the text as typed, Shift and keyboard layout included, plus 8 for Backspace,
9 for Tab, 10 for Enter and 127 for Delete. Characters queue up and arrive one per frame, so none
are lost when typing faster than the frame rate. A ShaderToy-style shader can read the keyboard
through a channel with `///tex:keyboard;` (or **Keyboard** in the channel row).

Keys go to the shader while it reads them and the code editor is hidden (or the app is
fullscreen), instead of the app's own single-key shortcuts. Ctrl shortcuts, Back and Escape
still work as usual.

Without a hardware keyboard, **More Options → Type into Shader** brings up the phone's keyboard
for the running shader: what you type arrives in `typedChar`, and each key taps the matching key
in `keyboard`. Tap the "Typing into the shader" chip, or close the keyboard, to stop.

## Can a shader make sound?

Yes - like ShaderToy's Sound tab. Add an offscreen buffer (**Offscreen Buffers**) and give it a
`mainSound()` instead of a `mainImage()`:

```glsl
vec2 mainSound(int samp, float time) {
	return vec2(sin(6.2831853 * 440.0 * time) * 0.2); // left, right: -1..1
}
```

Its tab then reads **Snd**, and it plays while the shader runs (and pauses with it), at 44100
samples a second (`iSampleRate`). `samp` counts samples from the start, and `time` is
`samp / iSampleRate`, so the sound lines up with `iTime`. ShaderToy's older
`vec2 mainSound(float time)` works too.

Unlike ShaderToy, the sound is made as it plays, a little ahead (about a tenth of a second), so
it can react to anything the shader can see: read your other buffers through `iChannel0`-`3`, or
use `iMouse` and touches directly. A Sound pass draws nothing, and a shader can have one.

Pasting a ShaderToy shader with a `//// Sound` section puts it in the first free buffer.

To make a sound start exactly when something happens - a tap, say - a buffer can read
`uniform int soundSample;` (or `uniform float soundTime;`, the same in seconds): the next sample
the Sound pass will make. Stamp the event with it, and play from there in `mainSound()`. The
**Soundboard** sample does this, and bends pitch smoothly by keeping each note's oscillator time
running across frames, so the phase never jumps.

## Can a shader play a tracker module?

Yes - MOD, S3M and XM files. **More Options → Import Tracker Module…** adds one to your library
under its file's name, and offers to make it the current shader's soundtrack, which is just a line

```glsl
///mod:name
```

anywhere in the source. It plays while the shader runs, pauses and restarts with it, and loops.
The **Module Viewer** sample shows what a shader can see of it:

```glsl
uniform sampler2D moduleInfo;  // where the song is, each channel's note, and the pattern
uniform sampler2D moduleScope; // each channel's waveform, and its spectrum
uniform float moduleTime;      // seconds into the song
uniform float moduleRow;       // the pattern row playing, with how far through it as the fraction
```

`moduleInfo` is 64x72 bytes: row 0 the song position (texel 0: order, orders, row, rows in the
pattern; texel 1: speed, tempo, channels, pattern), row 1 each channel's (note, instrument,
volume, panning), and rows 8-71 the pattern rows from 24 before the playing one to 39 after
(texel `x` is channel `x`'s note, instrument, volume column and effect, texel `32 + x` its effect
parameter). `moduleScope` is 512x64: rows 0-31 each channel's last 512 samples, rows 32-63 a
64-band spectrum of each. It all matches what you hear, not what's been made ahead of it. Shaders
set as the live wallpaper stay silent.

Tracker playback is [IBXM](https://github.com/martincameron/micromod) by Martin Cameron.

## Can a shader pack play as a show?

Yes. Tick **Present as a show** in Build Shader Pack and the pack plays its shaders full screen,
one after another in a loop, for as many seconds each as you set. Its soundtrack can be a tracker
module from your library, the Sound pass of one of the pack's shaders (played under all of them),
or each shader's own sound. Installed, the pack's group in the sidebar's tree view shows a play
icon - tap it to play the show (or use **Play Show** in Get Shader Pack). Tap for the next shader,
back to leave.

For more control, the Timeline field takes JSON: a list of scenes, each a shader (by name) for so
many seconds, optionally with #define values keyed over time, eased from one key to the next:

```json
{"scenes": [
  {"shader": "Name", "seconds": 15, "keys": [
    {"at": 0, "defines": {"ZOOM": 1.0}},
    {"at": 15, "defines": {"ZOOM": 4.0}}]}
]}
```

## Can a shader keep what it made?

Yes: **More Options → Save Buffer as Texture…** saves an offscreen buffer's latest frame as a
texture, byte for byte, under a name you choose - by default, the first `sampler2D` your shader
declares that isn't saved yet, which is usually the one it's waiting for. Declare that sampler
and the shader can read it back next time it starts (restart it to load a new save). Store data
in it as bytes (`k / 255.0`) and keep alpha at 1 where it matters; values outside 0-1 are
clamped. The **Tracker** sample keeps its song this way.

## Can I paste ShaderToy code directly?

Yes.
If pasted code contains `mainImage()`, Enki Shader Editor automatically converts common ShaderToy names:

- `iResolution` → `resolution`
- `iGlobalTime` → `time`
- `iMouse` → `mouse`
- `iDate` → `date`

A wrapper `main()` is generated automatically.

## How do I import or export shaders?

Use **Settings → Import/Export Database** to back up and restore entire shader collection, including textures.

On older Android versions (below 10), you can also export individual `.glsl` files to `Downloads/EnkiShaderEditor`.

## Can I use custom textures?

Yes.
Open **Menu → Add Uniform → 2D Textures** or **Cube Maps** tab.
Pick image, crop if needed, set sampler parameters like wrap and filter, and Enki Shader Editor inserts uniform declaration into code.

For a texture that holds data rather than a picture, such as a lookup table or neural network weights, choose **Exact Pixels** when adding it.
That takes a PNG of any size up to 4096 × 4096 and keeps every pixel exactly as it is, with no crop or resize.
Read it with `texelFetch()`.
Note that row 0 of the texture is the bottom row of the image.
Like any saved texture, it goes into shader packs along with the shaders that use it.

## What is `#define SHADER_EDITOR`?

Enki Shader Editor injects this line automatically into every shader:

```glsl
#define SHADER_EDITOR 1
```

Use `#ifdef SHADER_EDITOR` when you want code that only runs inside Enki Shader Editor.
Useful for portable shaders shared with other GLSL environments.

## What fonts are available?

Several popular coding fonts are built in, including **JetBrains Mono**, **Fira Code**, and **Source Code Pro**.
Ligature support can be toggled independently.

Choose font in **Settings → Editor → Font**.

## How do I change render resolution?

Use quality spinner in toolbar.
Values range from `1/32` up to `2×`.

Lower values render at fraction of screen resolution and can greatly improve performance or battery life.
Each shader remembers its own quality setting.

## Where can I learn more about shaders?

### Basics

- [The Book of Shaders](https://thebookofshaders.com/)
- [An Introduction to Shaders](https://aerotwist.com/tutorials/an-introduction-to-shaders-part-1/)

### Advanced

- [The OpenGL ES Shading Language](https://www.khronos.org/files/opengles_shading_language.pdf)
- [OpenGL API documentation](https://docs.gl/)
- [GLSL Programming](https://en.wikibooks.org/wiki/GLSL_Programming)
- [Best Practices for Shaders](https://developer.apple.com/library/content/documentation/3DDrawing/Conceptual/OpenGLES_ProgrammingGuide/BestPracticesforShaders/BestPracticesforShaders.html)
