# Ultra Rest Area

A second app built from this codebase: a place to rest in front of gorgeous, fascinating
shaders, where **every pixel is a shader** - no Android UI at all. Its own screens, built from
shaders, lead through a curated library of shader sets, which can be configured (pacing, which
audio they listen to, and so on) with widgets that are themselves shaders.

Branch: `UltraRestArea`. App id: `enk.danwills.ultrarestarea`.

## Decided so far (2026-10-21)

- **A second app, not a mode of Enki.** A Gradle product flavour gives it its own app id, name,
  icon and store listing. It shares Enki's renderer and runtime; Enki itself builds exactly as
  before.
- **No editor.** Ultra Rest Area plays and configures sets; making shaders stays in Enki.
- **Bundled sets first.** The library starts as sets shipped inside the app; buying and
  downloading more (Enki's pack store/Play Asset Delivery path) comes later.
- **The visual style is to be explored** - starting with a first menu shader to react to. Three
  directions below.

## How it would be built

- **Java decides, shaders draw.** The app keeps the UI's state - which screen, what's focused,
  slider values, transitions - and does the hit-testing, then hands all of it to the UI shaders
  as uniforms (or a small state texture) to draw however they like. Driving the logic from the
  shaders themselves is possible (Enki's Tracker sample does it) but fragile for a whole app, and
  makes things like the back gesture hard to get right. Every widget is still drawn entirely in
  GLSL.
- **One full-screen canvas, in layers.** The set being shown renders first; a UI shader draws on
  top, in the same GL context, with the system bars hidden (immersive mode).
- **The library is shader packs.** A curated set is a pack; its per-set settings are a show's JSON
  (see `pack/PackShow.java`) grown a little: pacing, audio source, #define presets (Adjust
  Defines' live values), resolution, `///steps`.
- **Reused from Enki:** the renderer (multipass, 32-bit float buffers, `///steps`, live defines),
  touch/pointer uniforms, sound from the shader itself and tracker modules, audioSpectrum, the
  SDF `font`, shader texts (for any words a screen needs), shows, packs.

### Where Android will still show through

These are the system's, and can't be drawn as shaders:
- **Permission prompts** - listening through the microphone, or capturing other apps' sound. A
  set's own sound (modules, Sound passes) needs neither, so that's the natural default.
- **The back gesture** - it can be caught and handled, but it exists.
- **The Play purchase sheet**, once sets are sold.
- **Accessibility** - a screen reader can't see shader-drawn UI unless it's described separately
  (a hidden accessibility tree mirroring Java's UI state). Not needed at first; worth planning
  for the store.

## Visual directions to explore

### 1. Metaballs

The UI as a soft field of blobs. Each widget is a source in one implicit field, so things that
belong together can *flow together*:
- menu tiles as blobs that bulge when focused;
- **snapping shown by merging** - a dragged item's blob reaches out and fuses with the slot it'll
  land in;
- a slider's thumb as a blob that melts into its tick marks;
- page changes as blobs splitting and regathering into the next layout.

Straightforward to build - Java passes each widget's position, size and state as uniforms, and
one shader sums the field - so it's a good **first menu shader**.

### 2. Reaction-diffusion, guided into shape

UI made of living reaction-diffusion (the converted-Ready machinery: `///float32`, `///steps`, the
Gray-Scott family), *steered* into the shapes the layout needs:
- a **guide mask** from the layout (signed distance fields of the widgets) modulates the feed and
  kill rates, so pattern only grows inside the shapes - and different regions can be tuned to
  different pattern types (spots for buttons, labyrinths for panels, stripes for sliders);
- **touch seeds the reaction** (as painting does in the converted patterns), so pressing a button
  makes it bloom;
- **page transitions grow**: change the mask and the pattern dissolves out of the old layout and
  re-forms into the new one.

### 3. Reaction-diffusion machines

The most ambitious: interaction that *computes*, like Game of Life machines where gliders bounce
around and produce a prime - but with reaction-diffusion as the primitive instead of discrete
cells. Gray-Scott has moving solitons (the "U-Skate world" family, and the gliders in Dan's
history-wave pattern); reaction-diffusion computing (Adamatzky and others) builds logic gates from
colliding waves and solitons. So:
- a touch launches gliders, which travel along channels (masks again) and collide at gates;
- which region ends up lit *is* the result - the choice the user made, or something more playful;
- Java reads back a few texels (as Enki's BufferText does) to learn the outcome.

An art piece as much as a UI - probably for special screens and moments rather than every control.

## The UI as the content

Dan's thought (2026-10-21): the UI could be so fascinating that some people just keep playing with
it instead of going on to the shader sets - and that's fine. It suggests:
- **Contraption packs** - curated reaction-diffusion machines (sorting machines, prime finders,
  chain reactions, small puzzles with a goal) sold alongside, or instead of, shader sets. A
  contraption is a multipass shader plus a little description of its channels, gates and inputs,
  so it ships as a pack exactly like a set: same store, same delivery.
- **The menu as the free sample** - if the home screen is itself a playable machine, it's both the
  way in and the hook; packs sell more of the same feeling.
- So it's worth making the reaction-diffusion menu genuinely *playable* early, not just navigable.

## Milestones (proposed)

1. **Scaffold and a metaball menu.** The product flavour; a full-screen, immersive activity with
   no Android UI; a menu shader drawing a few set tiles as metaballs from Java's state; tap a
   tile to play that set as a show; back returns to the menu.
2. **A reaction-diffusion menu.** The same menu grown from guided reaction-diffusion, side by side
   with the metaball one, to compare.
3. **Set configuration** with shader widgets: pacing, audio source, a couple of live defines.
4. **The first bundled library** - a handful of curated sets.
5. **Experiments with reaction-diffusion machines.**

## Open questions

- Name and icon art.
- Which sets go in the first library.
- Whether any text appears at all, or the UI stays wordless (names could come from shader texts
  drawn with the SDF font).
- Settings Ultra Rest Area needs beyond each set's own (overall volume? sleep timer?).
