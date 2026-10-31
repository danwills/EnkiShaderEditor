# Enki Shader Editor: a brief for writing shaders

Enki Shader Editor is an Android app for writing and running GLSL ES fragment shaders live on the
phone. This brief covers what a shader can use and what to watch for. Dan's phone is a Galaxy S22
Ultra, with an Adreno 730 GPU and a 1440x3088 screen.

## The shader as text

A shader is one plain text: the Image pass first, then optional sections, each starting on a
marker line. Dan pastes it in with Paste as New, and Copy and Share produce the same format.

```glsl
//// Name My Shader          (optional first line: the name it's saved under)
// ... Image pass source ...
//// Common
// ... code every pass starts with ...
//// Buffer A
// ... up to four buffers: A, B, C, D ...
//// Text scroller
plain text a pass can read as a texture (see "Text, font and keyboard")
```

- The Image pass draws the picture. It can be ShaderToy style, with
  `void mainImage(out vec4 fragColor, in vec2 fragCoord)`, or native, with `main()`.
  - With `mainImage()`, Enki adds `#version 300 es`, a `main()`, and declarations for any ShaderToy
    uniforms used. These are `iTime`, `iTimeDelta`, `iFrame`, `iFrameRate`, `iResolution`,
    `iMouse`, `iDate`, `iChannel0`-`3`, `iChannelResolution[4]` and `iSampleRate`.
  - A native shader declares its uniforms itself. To use GLES 3, put `#version 300 es` on the very
    first line, with nothing before it, and write to your own `out vec4`.
- Native uniforms (below) can be declared in a ShaderToy-style pass too.
- `#define SHADER_EDITOR 1` is always defined.

## Offscreen buffers (Buffer A-D)

- **What they are:** up to four ShaderToy-style buffer passes, each its own program with ping-pong
  targets.
- **Order:** every frame runs A, B, C, D, then Image.
  - A pass reading itself gets its previous frame.
  - Reading an earlier pass gets this frame's result; a later pass, last frame's.
- **Binding channels:** in any pass, or once in Common to bind in every pass:
  ```glsl
  uniform sampler2D iChannel0; ///buf:A;
  uniform sampler2D iChannel1; ///buf:B;min:n;mag:n;s:r;t:r;
  ```
  The parameters, `;`-separated after `///buf:X;`:
  - Filtering is linear by default. `min:n`/`mag:n` make it nearest; `min:ll` and similar add
    mipmaps.
  - Wrapping clamps by default. `s:` and `t:` take `r` (repeat), `m` (mirror) or `c` (clamp).
  - For exact texel reads, use `texelFetch(iChannelN, ivec2(...), 0)`.
- **Format:** RGBA16F (half float) by default. Add a `///float32` line to a pass for RGBA32F.
  Half float holds only about 3 significant digits, and whole numbers exactly up to 2048. Any
  pass holding positions, clocks, counters or accumulated values needs `///float32`.
- **Size:** by default a buffer is the render size (screen x the quality setting). A line in the
  pass gives it its own size, whatever the quality:
  - `///size:WxH` is a fixed size, e.g. for a lookup table or an atlas.
  - `///scale:F` is F x the screen size.
  - `///minScale:F` is the render size, but at least F x the screen.

  A pass sees its own size as `iResolution`. Readers should use `iChannelResolution[n].xy`, not
  `iResolution`.
- **Steps:** `///steps:N` in any pass (1-10000) runs the buffers N times per drawn frame, for
  simulations. `iFrame` then counts steps.
- **When buffers are cleared:** they start at zero.
  - A resize (a quality change or a rotation) remakes them cleared, unless the pass has its own
    `///size`.
  - `///stateRow:top` (or `bottom[,N]`) keeps those rows through a resize or rotation, and with
    `///resize:tile` the rest of the buffer is kept too, turned with the rotation and then tiled.
  - Editing the source while it runs keeps the buffers' contents, the clock and the backbuffer.
    So state written by an older version of the code can still be there.

  Don't rely on `iFrame == 0` to initialise. Keep a marker value in a state texel instead. For
  example, write `MARK` to a texel and reinitialise when it isn't there, plus a version number if
  the state layout changes.
- **Sound pass:** a buffer with `vec2 mainSound(int samp, float time)` and no `mainImage()` plays
  audio at 44.1 kHz, made about 0.1 s ahead. It can read the other buffers and touches. It has
  no sound as a live wallpaper.
  - `uniform int soundSample;` (or `float soundTime`) in another buffer gives the next sample to
    be played, for stamping events.
  - `///mod:name` anywhere plays a tracker module (MOD, S3M or XM) from the library. The
    `moduleInfo`, `moduleScope`, `moduleTime` and `moduleRow` uniforms describe what's playing.
- **Points and Gaussian splats:** an upscaling mode draws what the last buffer lists instead of a
  picture. A `///upscale:filter[,edge[,amount]]` line in the shader picks it, or any other
  upscaling filter, as the shader's default. The filters are nearest, sharp, linear, bspline,
  catmull, sinc, defocus, bokeh, crt and gaussian.

## Native uniforms

Declare only what's used. Sensors are switched on only for shaders that use them.

- **Time and frames:**
  - `float time` (s since load) and `float runtime` (excludes pauses, and survives the wallpaper
    restarting);
  - `int second`, `float subsecond`, `float ftime`;
  - `int frame`;
  - `vec4 date`, `vec3 daytime`;
  - `float startRandom`.
- **Resolution and touch:**
  - `vec2 resolution` (render pixels);
  - `vec2 touch`, `vec2 touchStart`, `vec2 mouse`;
  - `vec3 pointers[10]` and `int pointerCount`. Each pointer is x, y in render pixels from the
    bottom left, and z is the touch's size.
  - `vec2 offset` (the wallpaper's scroll offset).
- **Sensors:** `vec3 gravity`, `linear`, `gyroscope`, `magnetic`, `orientation`, `rotationVector`;
  `mat3 rotationMatrix`, `inclinationMatrix`; `float inclination`, `light`, `pressure`, `proximity`.
- **Device:** `float battery`, `int powerConnected`, `int nightMode`, `int notificationCount`,
  `float lastNotificationTime`, `float mediaVolume`, `float micAmplitude`.
- **Camera:** `samplerExternalOES cameraBack` / `cameraFront`, with `vec2 cameraAddent` and
  `mat2 cameraOrientation`. Only one camera can be open at a time.
- **Audio:** `sampler2D audioSpectrum` is 512x2, laid out as on ShaderToy: the spectrum at
  v 0.25 and the waveform at v 0.75. It hears the microphone, system audio (a setting), or the
  shader's own sound.
- **Previous frame:** `sampler2D backbuffer`. Adding `///p:noise` after it starts it as noise
  rather than black.
- **Built-in textures:**
  - `sampler2D font`: an SDF glyph atlas, 16x16 cells in ShaderToy's layout, 0.5 on a glyph's
    edge. A channel can read it with `///tex:font;`.
  - `sampler2D colormaps` and `int colormapCount`: one colour map per row, 256 steps from low
    (u 0) to high (u 1). Row r is at v = 1 - (r + 0.5) / colormapCount. There are 87 maps:
    Ready's ten, then the CET maps.
- **Saved textures:** Dan's own, by name (`uniform sampler2D name;`), including cube maps.
  - "Exact Pixels" imports keep data PNGs byte for byte, up to 4096x4096.
  - Texture row 0 is the image's bottom row.
  - More Options → Save Buffer as Texture saves a buffer's frame as a texture the shader can read
    next time it starts.

## Text, font and keyboard

- **The shader's own source:** `sourceText` is the pass's own source, one texel per character,
  with `sourceTextSize` and `sourceTextLength`.
  - Row n is line n.
  - R is the character code, G*256+B its index in the text, and A is 1 for a character.
  - `sourceTextColor` is the same layout, with the editor's syntax colours.
- **Shader texts:** a `//// Text name` section is read the same way with `uniform sampler2D name;`
  plus `nameSize` and `nameLength`. Editing it updates the running shader without recompiling.
- **Keyboard:** `sampler2D keyboard` is 256x3, laid out as on ShaderToy, by JavaScript key code.
  `int typedChar` gives one typed character per frame, queued. A channel can read the keyboard
  with `///tex:keyboard;`. More Options → Type into Shader brings up the phone's keyboard.
- **Self-editing:** `///text:Image` (or A-D) in a buffer makes its `sourceText` that pass's source.
  The buffer can then hold an edited copy, and Run Typed Text compiles that copy in. The Notepad
  sample does this.

## Adjust Defines and live tweaking

- **Adjust Defines** lists every `#define NAME value` across the passes for editing.
- **Edit Live** turns ticked defines into uniforms with sliders over the running shader, with no
  restart. Defines used in `#if`/`#ifdef` can't go live.
- **Writing for it:** put tunables in `#define`s with a comment saying what they do. Names that
  share prefixes (`TREE_COLS`, `TREE_ROWS`) are grouped together in the panel.

## Quality, performance and captures

- **Quality:** the quality spinner renders at 1/32 to 2x the screen, and each shader remembers its
  own setting. Heavy shaders typically run at 1/4 to 1/16; aim for about 30 fps there.
- **Upscaling:** a low-res frame is upscaled for the screen with a chosen filter, e.g. sharp,
  bspline, crt or bokeh.
- **Progressive rendering:** a heavy frame may be drawn in bands over several display frames. The
  buffer chain counts as part of the frame, so this is handled for you.
- **High-res capture (Custom Res/Seq):** renders stills or image sequences at any size, in tiles,
  with optional oversampling. With buffers:
  - At the live size, the capture is the live frame.
  - At any other size, only the Image pass is tiled at the capture size. The buffers are read as
    they are, at their live size.
  - So in the Image pass, `texelFetch(iChannelN, ivec2(fragCoord))` on a render-sized buffer reads
    the wrong place during a capture. Either sample by UV, or check
    `iChannelResolution[n].xy == iResolution.xy` and work it out in the Image pass when they
    differ. Outback Window does the latter.

## Gotchas (learned on Dan's phone)

1. **Big integer constants get mangled** by the Adreno compiler: anything over 2^24, as in typical
   integer hashes. Use float hashes, such as Dave Hoskins' `fract`-based `hash12`/`hash42`.
2. **Big `const` arrays are unreliable**, coming out with wrong components, and slow to index. Put
   tables in a texture or a buffer (e.g. a `///size` buffer filled once) and use `texelFetch`.
3. **The compiler has a performance cliff.** Speed depends far more on how much code sits in the
   hot loops than on the arithmetic done.
   - One extra branch or shading term inside a per-pixel loop has taken shaders from 16 fps to 2.
     Removing any one part of a heavy loop body saved nothing, while removing a whole kind of
     object saved 27%.
   - What works:
     - Split heavy work into buffer passes, each its own smaller program. Moving Outback Window's
       plants into Buffer D took it from 13 to 42 fps.
     - Work out anything that's the same for every pixel (lighting, camera, per-frame state) once
       a frame, in a state buffer's texels, and read it where it's used rather than keeping it in
       globals.
     - Keep tables in textures.
   - What didn't help: fewer loop iterations, unroll-proof loops, cheaper distant objects,
     branching around unused work, and a noise texture.
   - Test changes on the phone; desktop timings don't predict this.
4. **Half-float buffers** (the default) quietly lose precision. Use `///float32` for state.
5. **Buffers can be stale or cleared** (see "When buffers are cleared"). Use a marker texel to know
   whether state is valid.
6. **Precision:** `time` can lose accuracy over long runs in mediump. Use highp where supported,
   or `runtime`, or `second`/`subsecond`.
7. **Compiling is slow on the phone for big shaders**, and every pass compiles Common again. A
   pass that builds something over several frames (an atlas, say) should show progress.
8. **`#version 300 es`** must be the very first line when written by hand: no comments or blank
   lines before it.
9. **Error logs:** some drivers give empty or unhelpful error logs. A link failure with an empty log
   has happened on the phone with a large Image pass.
10. **A table in a render-sized buffer can fall off its edge.** At 1/16 the render is only 90x193,
    so any buffer holding a table or state at fixed texels needs `///size:WxH`. Reads past the
    edge return undefined values without any error.
11. **Time a shader at the same scene.** A simulation clocked by `iTimeDelta` (often capped) runs
    at a different pace at each quality, so after the same wait two runs show different scenes,
    and Outback's frame cost varied 8x along its drive. Compare timings from a fixed state.
12. **Live wallpapers** have no sound, and battery matters there.

## Working with Dan

- Each new iteration goes on the phone as a new shader, "Name vN", rather than replacing the last.
- He likes each round to move the look forward. Report the fps, but don't spend long on
  performance unless he asks.
- Put tunables in `#define`s, so he can play with them in Edit Live.
- Comment the code: what each pass does, what each state texel holds, and the touch controls.
  Outback Window's header is the model.
- If you have the repo:
  - `tools/samples/check_shader.py` and `tools/samples/powder/run_powder.py` compile and run
    multipass shaders headless on the desktop, buffers and sizes as the app does them.
  - `tools/phone/` has scripts to put shaders on the phone and capture them.
  - Samples to learn from are in `tools/samples/` and `app/src/main/res/raw/sample_*.glsl`.
