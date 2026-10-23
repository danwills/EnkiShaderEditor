// grayscott-historyWave_fuseWormsDev - flown over as terrain - from Ready (GollyGang/ready) by tools/ready/vti2enki.py.
// Fingers fly the camera: one steers and tilts; two slide, pinch to climb
// or dive, twist to turn (Restart for the start view). A triple tap puts the
// pattern's own interface up (painting, its tiles and buttons), and another
// takes it away again. The look is set by the terrain's #defines (Adjust
// Defines' live mode suits them).
// Rule: Gray-Scott (formula), 5 chemicals, medium accuracy, wrapping edges; 8 steps a frame
// (2 per run of the buffers, which run 4 times a frame - the ///steps
// line below; Ready ran 32 steps a render).
///steps:4
//
// A gray-scott reaction-diffusion system is simulated in the a and b reagents, with two coupled simpler systems in c,d,e.
// The c reagent implements a diffuse kind of 'history' of the other reagents (ie so that gliders can leave a 'trail').
// The d and e reagents implement the wave equation, with additional damping and shaping controls, which is fed by the other reagents (for example gliders can leave a rippley 'wake' behind them).
// The extra reagents can feed back into the gray-scott ones, and also control the gray-scott parameters (including diffusion).
//
//
//
//
// Implemented by Dan Wills based on earlier work from the Ready codebase.
//
// Note: Starts from the saved state's busiest window scattered over its background, as Ready starts from the state saved in the file.
// Paint with a finger: it sets a = the initial pattern's, b = the initial pattern's (PAINT_* in Buffer A's #defines).
// Two fingers erase: they set a = 1.0, b = 0.0 (ERASE_*).
// Three fingers switch to every chemical as a black-and-white mask (MASK_*)
// and back.
// Shows chemical b. Buffers A, B, C, D hold the state.

uniform sampler2D iChannel0; ///buf:C;
uniform sampler2D iChannel1; ///buf:D;
uniform sampler2D font;
uniform sampler2D colormaps; // the app's built-in colour maps, one per row
uniform int colormapCount;

// Ready's "spectral" colour map: 9 stops, evenly spaced from HIGH to LOW.
vec3 colourMap(float v) {
	float t = clamp((v - HIGH) / (LOW - HIGH), 0.0, 1.0) * 8.0;
	vec3 c = vec3(0.8353, 0.2431, 0.3098);
	c = mix(c, vec3(0.9569, 0.4275, 0.2627), clamp(t - 0.0, 0.0, 1.0));
	c = mix(c, vec3(0.9922, 0.6824, 0.3804), clamp(t - 1.0, 0.0, 1.0));
	c = mix(c, vec3(0.9961, 0.8784, 0.5451), clamp(t - 2.0, 0.0, 1.0));
	c = mix(c, vec3(1.0, 1.0, 0.749), clamp(t - 3.0, 0.0, 1.0));
	c = mix(c, vec3(0.902, 0.9608, 0.5961), clamp(t - 4.0, 0.0, 1.0));
	c = mix(c, vec3(0.6706, 0.8667, 0.6431), clamp(t - 5.0, 0.0, 1.0));
	c = mix(c, vec3(0.4, 0.7608, 0.6471), clamp(t - 6.0, 0.0, 1.0));
	c = mix(c, vec3(0.1961, 0.5333, 0.7412), clamp(t - 7.0, 0.0, 1.0));
	return c;
}

vec4 rdState(int texel, ivec2 grid) {
	return texelFetch(iChannel0, ivec2(texel, grid.y), 0);
}

// Chemical i's range as the views show it (composite colour, its tile):
// the range its values have had lately (see rdNextRange()), so one that
// goes below 0 - a wave - or never near 1 still shows its whole swing;
// its mask range until that's been measured (or with too narrow a grid to
// hold it). Painting keeps to the mask range.
vec2 rdShowRange(int i, ivec2 grid) {
	if (S_RANGE + i >= grid.x) {
		return maskRange(i);
	}
	vec4 r = rdState(S_RANGE + i, grid);
	if (r.z < 0.5) {
		return maskRange(i);
	}
	// Not so narrow that a chemical that's all one value shows its
	// rounding as a pattern.
	float least = 1e-5 + 1e-3 * max(abs(r.x), abs(r.y));
	return vec2(r.x, max(r.y, r.x + least));
}

// How big the brush is now, of the grid's shorter side.
float rdBrushSize(ivec2 grid) {
	return BRUSH_SIZE * (S_PAINT < grid.x ? clamp(rdState(S_PAINT, grid).x, 0.25, 2.0) : 1.0);
}

// Whether this is a frame's first step: what drifts slowly (wandering,
// the ranges) moves once a frame, however many steps a frame takes
// (`///steps:`) - by iTime, the same for every step of a frame.
bool rdNewFrame(ivec2 grid) {
	return S_CLOCK >= grid.x || rdState(S_CLOCK, grid).x != iTime;
}

// Whether the state row is blank too - so the interface starts afresh.
bool rdFresh(ivec2 grid) {
	return rdState(S_FINGERS, grid).w < 1.5;
}

// Whether the simulation has started: a mark in the state row, rather than
// iFrame == 0 - the app's frame count goes back to 0 whenever its surface
// is made again (the screen timing out, the app coming back), though the
// buffers carry on; so only buffers that really are blank (new, or cleared
// by Restart) start the initial pattern again. The mark is 2, not 1: the
// app clears new buffers to black with an alpha of 1. And with its
// grid not blank: the app keeps the state row across a resize or a
// rotation (`///stateRow:top`) and tiles the old grid over the new one
// (`///resize:tile`), so both carry on; where it can't keep the grid
// (an older app, 8-bit buffers) it comes back blank, and starts again
// while the interface carries on (see rdFresh(), rdNextSize()).
bool rdStarted(ivec2 grid) {
	if (rdFresh(grid)) {
		return false;
	}
	if (S_SIZE >= grid.x) {
		return true;
	}
	// Not the size: the app tiles a kept grid over a new one
	// (`///resize:tile`), so a rotated one carries on.
	return rdState(S_SIZE, grid).z > 0.5;
}

// Chemical i (0 = a) at cell q.
float chemical(int i, ivec2 q) {
	return i < 4 ? texelFetch(iChannel0, q, 0)[i] : texelFetch(iChannel1, q, 0)[i - 4];
}

float rdChem(int i, ivec2 q) {
	return chemical(i, q);
}

const int ACTIVE = 1; // the chemical the colour view shows

// Chemical i at q, 0-1 across its range (rdShowRange()).
float rdLevel(int i, ivec2 q, ivec2 grid) {
	vec2 range = rdShowRange(i, grid);
	return clamp((chemical(i, q) - range.x) / (range.y - range.x), 0.0, 1.0);
}

// Colour map slot `slot` at t (0-1): 0 the file's own, then colormaps'
// rows.
vec3 rdMap(float t, float slot) {
	if (slot < 0.5) {
		return colourMap(mix(LOW, HIGH, t));
	}
	return texture(colormaps, vec2(t, 1.0 - (slot - 0.5) / float(colormapCount))).rgb;
}

// Composite colour: each chemical's level (or its complement, 1 - level),
// raised to its power, times its colour and weight, added up - so it can
// be as bright as the weights make it; a weight of 0 hides a chemical, and
// a negative one darkens where it is.
vec3 rdComposite(ivec2 q, ivec2 grid) {
	vec3 sum = vec3(0.0);
	for (int i = 0; i < CHEMICALS; ++i) {
		vec4 c = rdState(S_COLOUR + i, grid);
		vec4 power = rdState(S_POWER + i, grid);
		float level = rdLevel(i, q, grid);
		// Its complement first, if a two-finger tap asked - levels are 0-1,
		// so it stays 0-1.
		level = power.y > 0.5 ? 1.0 - level : level;
		sum += c.w * hsv2rgb(c.xyz) * pow(level, power.x);
	}
	return clamp(sum, 0.0, 1.0);
}

// Chemical i's part in composite colour at q: its level (or complement)
// to its power, in its colour, as bright as its weight makes it next to
// an average one (COMPOSITE_GAIN over the chemicals) - a negative weight
// shown as bright as its size.
vec3 rdContribution(int i, ivec2 q, ivec2 grid) {
	vec4 c = rdState(S_COLOUR + i, grid);
	vec4 power = rdState(S_POWER + i, grid);
	float level = rdLevel(i, q, grid);
	level = power.y > 0.5 ? 1.0 - level : level;
	return hsv2rgb(c.xyz) * pow(level, power.x) *
			clamp(abs(c.w) * float(CHEMICALS) / COMPOSITE_GAIN, 0.0, 1.0);
}

// t (0-1) run through the colour map S_MAP's frequency times over.
float rdRepeat(float t, ivec2 grid) {
	float f = rdState(S_MAP, grid).x;
	return f > 1.001 ? fract(t * f) : t;
}

vec3 rdColour(ivec2 q, ivec2 grid, vec4 mode) {
	if (mode.x > 0.5) {
		return rdComposite(q, grid);
	}
	vec2 range = vec2(LOW, HIGH);
	float t = clamp((chemical(ACTIVE, q) - range.x) / (range.y - range.x), 0.0, 1.0);
	return rdMap(rdRepeat(t, grid), mode.y);
}

// Tile k at cell q: a chemical's level (in composite, its part in it),
// or the colour view.
vec3 rdCellColour(int k, ivec2 q, ivec2 grid, vec4 mode) {
	if (k < CHEMICALS) {
		return mode.x > 0.5 ? rdContribution(k, q, grid) : vec3(rdLevel(k, q, grid));
	}
	return rdColour(q, grid, mode);
}

// The same at uv (0-1 across the tile).
vec3 rdTileColour(int k, vec2 uv, ivec2 grid, vec4 mode) {
	return rdCellColour(k, ivec2(clamp(uv, 0.0, 0.999) * vec2(grid)), grid, mode);
}

// The coverage of `code`'s glyph, at g (0-1 across its cell), drawn h
// pixels high.
float rdGlyph(float code, vec2 g, float h) {
	if (any(lessThan(g, vec2(0.0))) || any(greaterThanEqual(g, vec2(1.0)))) {
		return 0.0;
	}
	vec2 cell = vec2(mod(code, 16.0), 15.0 - floor(code / 16.0));
	float d = texture(font, (cell + g) / 16.0).r;
	float w = 8.0 / h;
	return smoothstep(0.5 - w, 0.5 + w, d);
}

// The colour wheel - hue round it, saturation out from the middle - with
// a ring where `current` is; alpha in w.
vec4 rdWheel(vec2 p, vec2 centre, float radius, vec3 current) {
	vec2 v = p - centre;
	float r = length(v) / radius;
	float edge = 1.5 / radius;
	if (r > 1.0 + edge * 4.0) {
		return vec4(0.0);
	}
	float hue = fract(atan(v.y, v.x) / 6.28318531);
	vec3 col = hsv2rgb(vec3(hue, min(r, 1.0), current.z));
	float a = current.x * 6.28318531;
	vec2 mark = centre + radius * current.y * vec2(cos(a), sin(a));
	float ring = abs(distance(p, mark) - radius * 0.08);
	col = mix(col, vec3(1.0), 1.0 - smoothstep(0.0, radius * 0.02, ring));
	float rim = smoothstep(1.0 + edge, 1.0, r);
	col = mix(vec3(0.0), col, smoothstep(1.0 + edge * 4.0, 1.0 + edge, r) > 0.0 && r > 1.0 ? 0.0 : 1.0);
	return vec4(col, max(rim, smoothstep(1.0 + edge * 4.0, 1.0, r) * 0.6));
}

// The colour map button: the current map (or, in composite, each
// chemical's colour) along it, framed.
vec4 rdMapButtonColour(vec2 p, vec2 screen, ivec2 grid, vec4 mode) {
	vec4 b = rdMapButton(screen);
	float frame = 0.006 * rdUnit(screen);
	vec4 outer = vec4(b.xy - frame, b.zw + 2.0 * frame);
	if (!rdInBox(p, outer)) {
		return vec4(0.0);
	}
	if (!rdInBox(p, b)) {
		return vec4(vec3(0.9), 1.0);
	}
	float t = (p.x - b.x) / b.z;
	if (mode.x > 0.5) {
		int i = int(min(t * float(CHEMICALS), float(CHEMICALS) - 0.5));
		return vec4(hsv2rgb(rdState(S_COLOUR + i, grid).xyz), 1.0);
	}
	return vec4(rdMap(rdRepeat(t, grid), mode.y), 1.0);
}

// The composite button: a rainbow ring, filled in while composite is on.
vec4 rdCompositeButtonColour(vec2 p, vec2 screen, vec4 mode) {
	vec3 b = rdCompositeButton(screen);
	vec2 v = p - b.xy;
	float r = length(v) / b.z;
	if (r > 1.05) {
		return vec4(0.0);
	}
	vec3 rainbow = hsv2rgb(vec3(fract(atan(v.y, v.x) / 6.28318531), 0.9, 1.0));
	float ring = smoothstep(0.62, 0.68, r);
	vec3 inside = mode.x > 0.5 ? hsv2rgb(vec3(fract(iTime * 0.1), 0.25, 1.0)) : vec3(0.08);
	return vec4(mix(inside, rainbow, ring), smoothstep(1.05, 0.98, r));
}

// The random button: a die's five, the face lit up while wandering.
vec4 rdRandomButtonColour(vec2 p, vec2 screen, vec4 mode) {
	vec3 b = rdRandomButton(screen);
	vec2 v = (p - b.xy) / b.z;
	float r = length(v);
	if (r > 1.05) {
		return vec4(0.0);
	}
	vec3 col = mode.z > 5.5 ? hsv2rgb(vec3(fract(iTime * 0.7), 0.6, 0.9)) : vec3(0.08);
	vec2 pip = abs(v) - vec2(0.38);
	float d = min(length(v), length(pip));
	col = mix(col, vec3(0.95), smoothstep(0.17, 0.12, d));
	col = mix(col, vec3(0.9), smoothstep(0.8, 0.86, r));
	return vec4(col, smoothstep(1.05, 0.98, r));
}

// The brush size button: a disc as big, next to the button, as the brush
// is next to its largest.
vec4 rdPaintButtonColour(vec2 p, vec2 screen, ivec2 grid) {
	vec3 b = rdPaintButton(screen);
	vec2 v = (p - b.xy) / b.z;
	float r = length(v);
	if (r > 1.05) {
		return vec4(0.0);
	}
	float size = 0.2 + 0.55 * sqrt(rdBrushSize(grid) / (2.0 * BRUSH_SIZE));
	vec3 col = mix(vec3(0.08), vec3(0.95), smoothstep(size + 0.05, size, r));
	col = mix(col, vec3(0.9), smoothstep(0.8, 0.86, r) * step(size + 0.08, r));
	return vec4(col, smoothstep(1.05, 0.98, r));
}

// Picking a colour map: every map, a row each, the current one framed.
vec4 rdMapPickerColour(vec2 p, vec2 screen, vec4 mode) {
	vec4 r = rdMapPicker(screen);
	float frame = 0.006 * rdUnit(screen);
	if (!rdInBox(p, vec4(r.xy - frame, r.zw + 2.0 * frame))) {
		return vec4(0.0);
	}
	if (!rdInBox(p, r)) {
		return vec4(vec3(0.9), 1.0);
	}
	float slot = rdMapPickerSlot(p.y, screen);
	float row = r.w / MAPS;
	float bottom = r.y + r.w - (slot + 1.0) * row;
	vec3 col = rdMap((p.x - r.x) / r.z, slot);
	if (abs(slot - mode.y) < 0.5 && (p.y - bottom < frame * 1.5 || bottom + row - p.y < frame * 1.5)) {
		col = vec3(1.0);
	}
	return vec4(col, 1.0);
}

// Picking a map: off the picker's right, the frequency zone - a tick at
// each whole frequency, a bright line at the map's, and it written out
// (like "x2.5") at the top.
vec4 rdFrequencyColour(vec2 p, vec2 screen, ivec2 grid) {
	vec4 r = rdMapPicker(screen);
	float start = rdFrequencyStart(screen);
	float end = rdFrequencyEnd(screen);
	if (p.x < start || p.x > end || p.y < r.y || p.y > r.y + r.w) {
		return vec4(0.0);
	}
	float f = rdState(S_MAP, grid).x;
	float px = 1.5;
	vec3 col = vec3(0.05);
	for (int i = 1; i <= int(MAX_FREQUENCY); ++i) {
		float x = start + (end - start) * (float(i) - 1.0) / (MAX_FREQUENCY - 1.0);
		col = mix(col, vec3(0.45), step(abs(p.x - x), px));
	}
	float at = start + (end - start) * (f - 1.0) / (MAX_FREQUENCY - 1.0);
	col = mix(col, vec3(1.0, 0.85, 0.2), step(abs(p.x - at), px * 2.0));
	// "x" and the frequency to two places.
	float h = 0.06 * rdUnit(screen);
	vec2 at0 = vec2(start + h * 0.3, r.y + r.w - h * 1.3);
	int hundredths = int(f * 100.0 + 0.5);
	float codes[5] = float[5](120.0, float(48 + hundredths / 100), 46.0,
			float(48 + (hundredths / 10) % 10), float(48 + hundredths % 10));
	for (int c = 0; c < 5; ++c) {
		float ink = rdGlyph(codes[c], (p - at0 - vec2(float(c) * h * 0.55, 0.0)) / h, h);
		col = mix(col, vec3(1.0), ink);
	}
	return vec4(col, 0.85);
}

// A chemical's level in its range, 0-1.
float rdInRange(int i, float v) {
	vec2 r = maskRange(max(i, 0));
	return clamp((v - r.x) / (r.y - r.x), 0.0, 1.0);
}

// The brush button: the top half what it paints, the bottom half what it
// erases, each as its chemicals' two levels side by side (the top
// sparkling while it's still the file's own initial pattern).
vec4 rdBrushButtonColour(vec2 p, vec2 screen, ivec2 grid) {
	vec3 b = rdBrushButton(screen);
	vec2 v = (p - b.xy) / b.z;
	float r = length(v);
	if (r > 1.05) {
		return vec4(0.0);
	}
	vec4 brush = rdState(S_BRUSH, grid);
	vec4 on = rdState(S_BRUSH_ON, grid);
	vec3 col = vec3(0.08);
	bool top = v.y > 0.0;
	bool left = v.x < 0.0;
	float level = top ? (left ? rdInRange(BRUSH_PAINT.x, brush.x) : rdInRange(BRUSH_PAINT.y, brush.y))
			: (left ? rdInRange(BRUSH_ERASE.x, brush.z) : rdInRange(BRUSH_ERASE.y, brush.w));
	if (top && on.x < 0.5) {
		vec2 cell = floor(p / (b.z * 0.18));
		level = fract(sin(dot(cell, vec2(12.9898, 78.233)) + floor(iTime * 6.0)) * 43758.5453);
	}
	col = mix(col, top ? vec3(1.0) : vec3(1.0, 0.35, 0.3), level * 0.9);
	col = mix(col, vec3(0.0), smoothstep(0.03, 0.0, abs(v.y)) + smoothstep(0.03, 0.0, abs(v.x)));
	col = mix(col, vec3(0.9), smoothstep(0.8, 0.86, r));
	return vec4(col, smoothstep(1.05, 0.98, r));
}

// While the brush is dragged: a pad below the buttons, across for its
// first chemical, up for its second, with a dot where they are.
vec4 rdBrushPad(vec2 p, vec2 screen, ivec2 grid, bool erase) {
	float u = rdUnit(screen);
	float side = 0.42 * u;
	vec2 origin = vec2(screen.x * 0.5 - side * 0.5, rdButtonsBottom(screen) - 0.03 * u - side);
	vec2 uv = (p - origin) / side;
	if (any(lessThan(uv, vec2(-0.02))) || any(greaterThan(uv, vec2(1.02)))) {
		return vec4(0.0);
	}
	vec4 brush = rdState(S_BRUSH, grid);
	ivec2 ids = erase ? BRUSH_ERASE : BRUSH_PAINT;
	vec2 at = erase ? vec2(rdInRange(ids.x, brush.z), rdInRange(ids.y, brush.w))
			: vec2(rdInRange(ids.x, brush.x), rdInRange(ids.y, brush.y));
	vec3 frame = erase ? vec3(1.0, 0.35, 0.3) : vec3(1.0);
	vec3 col = vec3(uv * 0.35, 0.12);
	if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
		col = frame;
	}
	float d = distance(uv, at) * side;
	col = mix(col, frame, smoothstep(0.035 * side, 0.025 * side, d));
	col = mix(col, vec3(0.0), smoothstep(0.018 * side, 0.01 * side, d));
	// The chemicals' letters, along the axes.
	float h = 0.1 * side;
	if (ids.x >= 0) {
		col = mix(col, frame, rdGlyph(float(97 + ids.x), (p - origin - vec2(side - h, -h * 1.1)) / h, h));
	}
	if (ids.y >= 0) {
		col = mix(col, frame, rdGlyph(float(97 + ids.y), (p - origin - vec2(-h * 1.0, side - h)) / h, h));
	}
	return vec4(col, 0.92);
}

// The pattern's own views, for when a triple tap's put its interface up.
void rdViews(out vec4 fragColor, vec2 fragCoord) {
	ivec2 size = textureSize(iChannel0, 0);
	vec2 screen = vec2(size);
	// The buffers' top row is the interface's state - see UI_COMMON.
	ivec2 grid = ivec2(size.x, size.y - 1);
	vec4 gesture = rdState(S_GESTURE, grid);
	vec4 mode = rdState(S_MODE, grid);
	vec4 drag = rdState(S_DRAG, grid);
	int view = int(gesture.z + 0.5);
	int shown = int(gesture.w + 0.5);
	vec2 p = fragCoord;
	float u = rdUnit(screen);
	vec3 col;
	if (view == 1) {
		col = vec3(0.04);
		int k = rdTileAt(p, screen);
		if (k >= 0) {
			vec4 tile = rdTile(k, screen);
			vec2 uv = (p - tile.xy) / tile.zw;
			vec2 inTile = p - tile.xy;
			if (any(lessThan(inTile, vec2(2.0))) || any(greaterThan(inTile, tile.zw - 2.0))) {
				col = vec3(0.25);
			} else {
				col = rdTileColour(k, uv, grid, mode);
				// While two fingers drag its weight (white) and power (yellow,
				// 1 at the middle): them, along its bottom.
				if (k < CHEMICALS && mode.z > 1.5 && mode.z < 2.5 && abs(mode.w - float(k)) < 0.5) {
					vec4 c = rdState(S_COLOUR + k, grid);
					float power = rdState(S_POWER + k, grid).x;
					float bar = tile.w * 0.04;
					if (inTile.y < bar * 2.2 && inTile.y > bar * 1.2) {
						col = mix(col, vec3(1.0), step(uv.x, (c.w + 2.0) / 6.0) * 0.85);
					}
					if (inTile.y < bar && inTile.y > 0.0) {
						float level = 0.5 + log(power) / log(10.0) * 0.5;
						col = mix(col, vec3(1.0, 0.85, 0.2), step(uv.x, level) * 0.85);
					}
				}
			}
		}
		// The colour wheel while a chemical's colour is being edited - kept
		// off the colour tile.
		if (mode.z > 0.5 && mode.z < 1.5 && !rdInBox(p, rdTile(CHEMICALS, screen))) {
			vec4 c = rdState(S_COLOUR + int(mode.w + 0.5), grid);
			vec4 wheel = rdWheel(p, drag.xy, WHEEL_RADIUS * u, c.xyz);
			col = mix(col, wheel.rgb, wheel.a);
		}
	} else if (view == 2) {
		// One screen row per grid row, as in the colour view below:
		// scaling the grid to the screen showed its middle row twice.
		col = rdCellColour(shown, rdCell(ivec2(p), grid), grid, mode);
	} else {
		// One screen row per grid row: the screen has one more than the
		// grid (the state row), and stretching the grid over it showed
		// its middle row twice. The extra row at the top wraps (or
		// clamps) instead.
		ivec2 q = rdCell(ivec2(p), grid);
		col = rdColour(q, grid, mode);
	}
	if (view == 1) {
		vec4 b = rdMapButtonColour(p, screen, grid, mode);
		col = mix(col, b.rgb, b.a);
		b = rdBrushButtonColour(p, screen, grid);
		col = mix(col, b.rgb, b.a);
		b = rdCompositeButtonColour(p, screen, mode);
		col = mix(col, b.rgb, b.a);
		b = rdRandomButtonColour(p, screen, mode);
		col = mix(col, b.rgb, b.a);
		b = rdPaintButtonColour(p, screen, grid);
		col = mix(col, b.rgb, b.a);
		if (mode.z > 2.5 && mode.z < 4.5) {
			b = rdBrushPad(p, screen, grid, mode.z > 3.5);
			col = mix(col, b.rgb, b.a);
		}
		if (mode.z > 4.5 && mode.z < 5.5) {
			b = rdMapPickerColour(p, screen, mode);
			col = mix(col, b.rgb, b.a);
			b = rdFrequencyColour(p, screen, grid);
			col = mix(col, b.rgb, b.a);
		}
	}
	fragColor = vec4(col, 1.0);
}

// The terrain: the simulation as a heightfield, flown over - see
// tools/samples/terrain/make_terrain.py.
#define SOLITON_HEIGHT 1.5  // cells high a soliton (chemical b) stands
#define HISTORY_HEIGHT 0.9  // and the trails it leaves (c)
#define WAVE_HEIGHT 0.5     // the waves' ripple (d), either way
#define FOG 0.006           // how quickly the distance fades into the sky
#define MAX_DISTANCE 260.0  // cells the view reaches
#define SUN vec3(0.55, -0.35, 0.62)

vec2 rangeB, rangeC, rangeD;

float level(float v, vec2 r) {
	return clamp((v - r.x) / (r.y - r.x), 0.0, 1.0);
}

// The ground's height at cell q (wrapped), in cells.
float cellHeight(ivec2 q, ivec2 grid) {
	q = ivec2(mod(vec2(q), vec2(grid)));
	vec4 t = texelFetch(iChannel0, q, 0);
	return SOLITON_HEIGHT * level(t.y, rangeB) + HISTORY_HEIGHT * level(t.z, rangeC) +
			WAVE_HEIGHT * (level(t.w, rangeD) - 0.5) * 2.0;
}

// Between the cells, straight lines (bilinear): cheap, for finding the
// ground as the ray marches.
float heightAt(vec2 p, ivec2 grid) {
	vec2 c = p - 0.5;
	vec2 i = floor(c);
	vec2 f = c - i;
	ivec2 q = ivec2(i);
	float h00 = cellHeight(q, grid);
	float h10 = cellHeight(q + ivec2(1, 0), grid);
	float h01 = cellHeight(q + ivec2(0, 1), grid);
	float h11 = cellHeight(q + ivec2(1, 1), grid);
	return mix(mix(h00, h10, f.x), mix(h01, h11, f.x), f.y);
}

// Cubic B-spline weights for the four cells around f (0-1).
vec4 bspline(float f) {
	float g = 1.0 - f;
	return vec4(g * g * g, 3.0 * f * f * f - 6.0 * f * f + 4.0,
			-3.0 * f * f * f + 3.0 * f * f + 3.0 * f + 1.0, f * f * f) / 6.0;
}

// Smooth through and through (a cubic B-spline over the 4x4 cells
// around p): the surface where the ray lands, and how it's lit - no
// facets or steps at the cells.
float smoothHeightAt(vec2 p, ivec2 grid) {
	vec2 c = p - 0.5;
	vec2 i = floor(c);
	vec2 f = c - i;
	vec4 wx = bspline(f.x);
	vec4 wy = bspline(f.y);
	ivec2 q = ivec2(i) - 1;
	float h = 0.0;
	for (int y = 0; y < 4; ++y) {
		float row = 0.0;
		for (int x = 0; x < 4; ++x) {
			row += wx[x] * cellHeight(q + ivec2(x, y), grid);
		}
		h += wy[y] * row;
	}
	return h;
}

vec3 normalAt(vec2 p, ivec2 grid) {
	float e = 0.35;
	float hx = smoothHeightAt(p + vec2(e, 0.0), grid) - smoothHeightAt(p - vec2(e, 0.0), grid);
	float hy = smoothHeightAt(p + vec2(0.0, e), grid) - smoothHeightAt(p - vec2(0.0, e), grid);
	return normalize(vec3(-hx, -hy, 2.0 * e));
}

vec3 sky(vec3 rd) {
	vec3 sun = normalize(SUN);
	float up = clamp(rd.z * 1.6 + 0.25, 0.0, 1.0);
	vec3 col = mix(vec3(0.62, 0.66, 0.74), vec3(0.16, 0.26, 0.48), up);
	col += vec3(1.0, 0.75, 0.45) * pow(max(dot(rd, sun), 0.0), 24.0) * 0.6;
	return col;
}

// The ground's colour where it was hit: the simulation's own colour map
// on the soliton chemical, warmed by the trails.
// The chemicals at p, smoothly between cells, as the height is.
vec4 cellsAt(vec2 p, ivec2 grid) {
	vec2 c = p - 0.5;
	vec2 i = floor(c);
	vec2 f = c - i;
	ivec2 q = ivec2(i);
	vec4 t00 = texelFetch(iChannel0, ivec2(mod(vec2(q), vec2(grid))), 0);
	vec4 t10 = texelFetch(iChannel0, ivec2(mod(vec2(q + ivec2(1, 0)), vec2(grid))), 0);
	vec4 t01 = texelFetch(iChannel0, ivec2(mod(vec2(q + ivec2(0, 1)), vec2(grid))), 0);
	vec4 t11 = texelFetch(iChannel0, ivec2(mod(vec2(q + ivec2(1, 1)), vec2(grid))), 0);
	return mix(mix(t00, t10, f.x), mix(t01, t11, f.x), f.y);
}

vec3 groundColour(vec2 p, ivec2 grid) {
	vec4 t = cellsAt(p, grid);
	float b = level(t.y, rangeB);
	float c = level(t.z, rangeC);
	vec3 col = colourMap(mix(LOW, HIGH, 0.12 + 0.88 * b));
	col = mix(col * 0.55, col, smoothstep(0.0, 0.6, b));
	return mix(col, col * vec3(1.15, 0.9, 0.75), c * 0.6);
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 size = textureSize(iChannel0, 0);
	vec2 screen = vec2(size);
	ivec2 grid = ivec2(size.x, size.y - 1);
	if (camInterface(grid)) {
		rdViews(fragColor, fragCoord);
		return;
	}
	rangeB = rdShowRange(1, grid);
	rangeC = rdShowRange(2, grid);
	rangeD = rdShowRange(3, grid);
	vec4 pose = rdState(CAM_POSE, grid);
	vec4 look = rdState(CAM_LOOK, grid);
	if (look.w < 1.5) {
		camStart(grid, pose, look);
	}
	// The lens: a little barrel curvature, more towards the corners (see
	// camRay(), which painting shares).
	vec2 uv = (fragCoord - 0.5 * screen) / screen.y;
	uv *= 1.0 + LENS * dot(uv, uv);
	vec3 rd = camRay(fragCoord, screen, pose, look);
	// A steady height over the ground's middle, not bobbing over each bump.
	vec3 ro = vec3(pose.xy, 0.5 * SOLITON_HEIGHT + pose.w);

	// March down to the ground: steps as long as the height above it
	// allows, then a few halvings to find the surface.
	float t = 0.0;
	float last = 0.0;
	bool hit = false;
	for (int i = 0; i < 140; ++i) {
		vec3 p = ro + rd * t;
		float above = p.z - heightAt(p.xy, grid);
		if (above < 0.0) {
			hit = true;
			break;
		}
		last = t;
		t += max(0.25, above * 0.55);
		if (t > MAX_DISTANCE || p.z > SOLITON_HEIGHT + HISTORY_HEIGHT + pose.w + 40.0) {
			break;
		}
	}
	vec3 col;
	if (hit) {
		// Then onto the smooth surface: a few halvings against it.
		last = max(0.0, last - 0.5);
		for (int i = 0; i < 7; ++i) {
			float mid = (last + t) * 0.5;
			vec3 p = ro + rd * mid;
			if (p.z < smoothHeightAt(p.xy, grid)) {
				t = mid;
			} else {
				last = mid;
			}
		}
		vec3 p = ro + rd * t;
		vec3 n = normalAt(p.xy, grid);
		vec3 sun = normalize(SUN);
		float diffuse = max(dot(n, sun), 0.0);
		// Soft shadow-ish: how much the ground leans away from the sky.
		float ambient = 0.35 + 0.25 * n.z;
		vec3 h = normalize(sun - rd);
		float spec = pow(max(dot(n, h), 0.0), 48.0) * 0.35;
		vec3 base = groundColour(p.xy, grid);
		col = base * (ambient + diffuse * vec3(1.0, 0.92, 0.8)) + spec;
		col = mix(col, sky(rd), 1.0 - exp(-t * FOG));
	} else {
		col = sky(rd);
	}
	// A touch of vignette, as a lens would.
	col *= 1.0 - 0.25 * dot(uv, uv);
	fragColor = vec4(pow(clamp(col, 0.0, 1.0), vec3(0.9)), 1.0);
}
//// Common
// Every pass starts with this (the app's Common section).

uniform vec3 pointers[10];
uniform int pointerCount;
uniform float startRandom;
uniform sampler2D rd_22dbeed900b2_0;
uniform sampler2D rd_22dbeed900b2_1;

// Ready's parameters (Adjust Defines changes them live):
#define timestep 1.0
#define D_a 0.164
#define D_b 0.082
#define k_ 0.063
#define F 0.042
#define c_to_k 0.0
#define c_to_F 0.05
#define c_to_a 0.0
#define c_to_b 0.0
#define c_to_Da 0.0
#define c_to_Db 0.0
#define D_a_to_c 0.0
#define D_b_to_c 0.0
#define cDamp 0.0003
#define D_c 0.02
#define a_to_c 0.0
#define b_to_c 0.005
#define c_pow 1.002
#define c_pow_norm 1.0
#define wave_diffusion 5e-05
#define wave_damp 0.997
#define wave_power 1.0
#define a_wave 0.0
#define b_wave 0.008
#define c_wave 0.0
#define wave_to_F 0.01445
#define wave_to_k 0.008
#define wave_to_Da 0.04
#define wave_to_Db 0.04
#define wave_to_a 0.0
#define wave_to_b 0.0
#define wave_to_c 0.0
#define dx 1.0 // grid spacing
// The Laplacian's scale: patterns grow (or, below 1, shrink) by about
// this. Above 1 it's a Gaussian's, wider the larger - see rdWideBlur() -
// and slower, as it reads more cells.
#define LAPLACIAN_SCALE 1.0

// Painting - what one finger sets, what two set (erasing), how far
// round each (the brush's radius, of the grid's shorter side - the
// same on screen at any quality; the eraser's, times that), and how
// long a touch lasts before it paints:
#define BRUSH_SIZE 0.2 // the button next to random makes it a quarter to twice this
#define ERASE_SIZE 1.5
#define PAINT_DELAY 0.2 // seconds - a quicker touch is a tap
#define ERASE_a 1.0
#define ERASE_b 0.0

// The colour view's range (Ready's low and high), and each chemical's
// on its tile - black at LOW, white at HIGH; a chemical painted on its
// own is painted to HIGH, erased to LOW, unless PAINT_/ERASE_ say:
#define LOW 0.0
#define HIGH 1.0
#define MASK_LOW_a 0.0
#define MASK_HIGH_a 1.0
#define MASK_LOW_b 0.0
#define MASK_HIGH_b 1.0
#define MASK_LOW_c 0.0
#define MASK_HIGH_c 1.0
#define MASK_LOW_d 0.0
#define MASK_HIGH_d 1.0
#define MASK_LOW_e 0.0
#define MASK_HIGH_e 1.0

float rdPow(float x, float y) {
	if (x < 0.0 && floor(y) == y) {
		return mod(y, 2.0) == 0.0 ? pow(-x, y) : -pow(-x, y);
	}
	return pow(x, y);
}

float fabs(float x) { return abs(x); }


// The initial pattern's pieces - see Ready's overlays.cpp. rel: the
// cell's position as a fraction of the grid.
float rdRandom(ivec2 p, int salt) {
	uvec3 v = uvec3(uvec2(p), uint(salt) * 7919u + uint(startRandom * 65535.0));
	v = v * 1664525u + 1013904223u;
	v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
	v ^= v >> 16u;
	v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
	return float(v.x & 0xffffffu) / 16777216.0;
}

float rdNoise(vec2 x, int salt) {
	vec2 i = floor(x);
	vec2 f = fract(x);
	vec2 u = f * f * (3.0 - 2.0 * f);
	float a = rdRandom(ivec2(i), salt);
	float b = rdRandom(ivec2(i) + ivec2(1, 0), salt);
	float c = rdRandom(ivec2(i) + ivec2(0, 1), salt);
	float d = rdRandom(ivec2(i) + ivec2(1, 1), salt);
	return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Like Ready's octave3D_01: octaves of noise, 0 to 1.
float rdPerlin(vec2 x, int octaves, int salt) {
	float sum = 0.0;
	float amp = 0.5;
	float total = 0.0;
	for (int o = 0; o < octaves; ++o) {
		sum += amp * rdNoise(x, salt + o);
		total += amp;
		x *= 2.0;
		amp *= 0.5;
	}
	return sum / total;
}

bool rdInRect(vec2 rel, vec2 lo, vec2 hi) {
	return all(greaterThanEqual(rel, lo)) && all(lessThanEqual(rel, hi));
}

bool rdInCircle(vec2 x, ivec2 size, vec2 c, float r) {
	return distance(x, c * vec2(size)) < r * float(max(size.x, size.y));
}

float rdLinear(vec2 rel, vec2 p1, vec2 p2, float v1, float v2) {
	float len = distance(p1, p2);
	float u = dot(rel - p1, (p2 - p1) / len) / len;
	return v1 + (v2 - v1) * u;
}

float rdRadial(vec2 x, ivec2 size, vec2 p1, vec2 p2, float v1, float v2) {
	vec2 a = p1 * vec2(size);
	vec2 b = p2 * vec2(size);
	return v1 + (v2 - v1) * distance(x, a) / distance(b, a);
}

float rdGaussian(vec2 x, ivec2 size, vec2 c, float height, float sigma) {
	float s = sigma * float(max(size.x, size.y));
	float d = distance(x, c * vec2(size));
	return height * exp(-d * d / (2.0 * s * s));
}

float rdSine(vec2 rel, vec2 p1, vec2 p2, float phase, float amplitude) {
	float len = distance(p1, p2);
	float u = dot(rel - p1, (p2 - p1) / len) / len;
	return amplitude * sin(u * 6.28318531 - phase);
}

// The initial pattern at cell p: Ready's initial pattern generator,
// its noise salted by seed (0 the first frame; painting takes a new
// one each frame).
void rdInitial(ivec2 p, ivec2 grid, int seed, out float a, out float b, out float c, out float d, out float e) {
	vec2 rel = vec2(p) / vec2(grid);
	a = 0.0;
	b = 0.0;
	c = 0.0;
	d = 0.0;
	e = 0.0;
	{ // a overwrite
		float val = a;
		if (true) {
			val = 0.7;
		}
		a = val;
	}
	{ // b overwrite
		float val = b;
		if (rdInRect(rel, vec2(0.0, 0.0), vec2(1.0, 1.0))) {
			val = mix(-0.2, 0.42, rdRandom(p, seed + 16));
		}
		b = val;
	}
}

// The start state: the saved state's busiest window scattered over its background.
// A 128x128 tile, 8-bit (16-bit where two channels make one value)
// over each chemical's range, laid over the grid - new copies each
// Restart, so a grid bigger than the tile doesn't repeat it. Each
// copy is a cell around a point jittered off a grid of tiles, turned
// by any angle (and flipped) at random, at a random offset, so
// neither the copies nor their seams line up.
uint rdStartHash(ivec2 t) {
	uint k = uint(t.x) * 1664525u + uint(t.y) * 22695477u + uint(startRandom * 65535.0);
	k ^= k >> 13; k *= 0x5bd1e995u; k ^= k >> 15;
	return k;
}

ivec2 rdStartCell(ivec2 p) {
	vec2 s = vec2(128.0, 128.0);
	vec2 x = vec2(p) + 0.5;
	vec2 c = floor(x / s);
	float best = 1e30;
	vec2 q = vec2(0.0);
	for (int j = -1; j <= 1; j++) {
		for (int i = -1; i <= 1; i++) {
			ivec2 n = ivec2(c) + ivec2(i, j);
			uint k = rdStartHash(n);
			vec2 jitter = vec2(float(k & 255u), float((k >> 8) & 255u)) / 255.0 - 0.5;
			vec2 d = x - (vec2(n) + 0.5 + jitter * 0.8) * s;
			float e = dot(d, d);
			if (e < best) {
				best = e;
				float a = float((k >> 16) & 1023u) * (6.2831853 / 1024.0);
				if (((k >> 26) & 1u) != 0u) d.x = -d.x;
				uint o = rdStartHash(n + ivec2(7919, 104729));
				q = vec2(float(o & 65535u), float(o >> 16)) / 65536.0 * s
						+ mat2(cos(a), sin(a), -sin(a), cos(a)) * d;
			}
		}
	}
	return ivec2(mod(floor(q), s));
}

void rdStart(ivec2 p, out float a, out float b, out float c, out float d, out float e) {
	ivec2 q = rdStartCell(p);
	vec4 t0 = round(texelFetch(rd_22dbeed900b2_0, q, 0) * 255.0);
	vec4 t1 = round(texelFetch(rd_22dbeed900b2_1, q, 0) * 255.0);
	a = 0.3051337707034637 + t0.x / 255.0 * 0.6810938119888306;
	b = 0.0041997740827207156 + t0.y / 255.0 * 0.43844802118837833;
	c = -0.00026991445181275 + t0.z / 255.0 * 0.4876274444395676;
	d = -0.21774939124382553 + t0.w / 255.0 * 0.761836051940918;
	e = -0.0030109283910124173 + t1.x / 255.0 * 0.0037946379161439836;
}

ivec2 rdCell(ivec2 p, ivec2 size) {
	p.x += p.x < 0 ? size.x : 0;
	p.x -= p.x >= size.x ? size.x : 0;
	p.y += p.y < 0 ? size.y : 0;
	p.y -= p.y >= size.y ? size.y : 0;
	return p;
}

vec3 rgb2hsv(vec3 c) {
	vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
	vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
	vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
	float d = q.x - min(q.w, q.y);
	return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + 1e-10)), d / (q.x + 1e-10), q.x);
}

vec3 hsv2rgb(vec3 c) {
	vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
	return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

// The interface. Views: 0 colour; 1 every chemical, and the colour view,
// as tiles; 2 one tile full screen. Buttons just above the tiles (in the
// tiles view only): the colour map (tap for the next, hold and drag up and
// down to pick one), random (tap for a random composite look; hold to
// wander through them, stopping a moment before letting go), the brush
// (drag to set what one finger paints; a second finger joining the drag
// sets what two erase; tap to go back to the file's own), and composite
// colour (tap to switch).
const int CHEMICALS = 5;
const int TILES = CHEMICALS + 1; // the chemicals, then the colour view
const float MAPS = 88.0;   // 0 the file's own map, then colormaps' rows
// The state row's texels:
const int S_GESTURE = 0; // most fingers this touch, when it began, view, the tile shown
const int S_TOUCH = 1;   // where it began (x, y), moved too far for a tap, tile it began on
const int S_MODE = 2;    // composite, colour map, editing (1 colour, 2 weight, 3 brush,
                         // 4 eraser, 5 picking a colour map, 6 wandering), its chemical
const int S_DRAG = 3;    // the colour wheel's centre (x, y), last frame's finger centre (x, y)
const int S_FINGERS = 4; // last frame's finger count, a chemical whose complement a two-
                         // finger tap switched (-1 none), 1 if a tap reset the brush, and
                         // 2 once started - see rdStarted()
const int S_BRUSH = 5;   // what the brush paints (its two chemicals), and erases (two)
const int S_BRUSH_ON = 6; // 1 once the brush (x), eraser (y) has been set by dragging
const int S_RANDOM = 7;  // the random look's seed, when wandering began, 1 while wandering,
                         // the RANDOM_REWIND period its snapshots were last taken in
const int S_COLOUR = 8;  // + a chemical: its composite hue, saturation, value, weight
const int S_POWER = 16;  // + a chemical: its composite power, complement (1: 1 - level)
const int S_MAP = 24;    // the colour map's frequency: how many times it runs over the range
const int S_SNAP = 25;   // + a chemical: snapshots of its hue and weight; + CHEMICALS + a
                         // chemical: of its power and complement - each the newer (x, y)
                         // and older (z, w), for wandering's rewind
const int S_RANGE = 41;  // + a chemical: the range its values have had lately (low, high),
                         // and 1 once measured - what the views show it across, see
                         // rdShowRange()
const int S_SEARCH = 49; // wandering's search: the stretch it's on (by SEARCH_STRETCH), its
                         // direction, the look's score as it began, 1 going on or -1
                         // going back - see rdNextSearch()
const int S_SIZE = 50;   // the grid's size (x, y) the simulation's running at, and 1 if
                         // its grid wasn't blank last step - see rdStarted()
const int S_CLOCK = 51;  // iTime as this frame's first step saw it - see rdNewFrame()
const int S_PAINT = 52;  // the brush's size, times BRUSH_SIZE (x) - see rdNextPaint()
// The brush's chemicals (-1 none): what dragging it sets.
const ivec2 BRUSH_PAINT = ivec2(0, 1);
const ivec2 BRUSH_ERASE = ivec2(0, 1);

// A touch shorter than this, moving less than TAP_MOVE of the screen's
// shorter side, is a tap.
#define TAP_TIME PAINT_DELAY
#define TAP_MOVE 0.03
#define WHEEL_RADIUS 0.22 // of the screen's shorter side
#define WEIGHT_RATE 3.0   // weight per screen height dragged
// Composite colour adds its chemicals' parts: weights summing to about
// this make an average look. A weight of 0 hides a chemical; below, it
// darkens where it is.
#define COMPOSITE_GAIN 1.6
#define POWER_RATE 3.0    // power doubles in about a quarter screen's drag
#define BRUSH_RATE 1.0    // a chemical's whole range per screen width (or height) dragged
#define RANDOM_REWIND 0.25 // wandering stops this to twice this before letting go
#define RANDOM_NUDGE 0.0003 // how far wandering moves a look along its search, a step
#define RANDOM_PULL 0.002  // and how far towards one that shows every chemical
#define SEARCH_STRETCH 0.5 // seconds wandering keeps to one direction before judging it
#define RANGE_GROW 0.0003  // how far a chemical's range widens towards its values' a frame,
#define RANGE_SHRINK 0.0001 // and narrows - very slowly both ways (a minute or more to
                            // settle), so the look holds steady as the wave moves

float rdUnit(vec2 screen) {
	return min(screen.x, screen.y);
}

bool rdInBox(vec2 p, vec4 box) {
	return all(greaterThanEqual(p, box.xy)) && all(lessThan(p, box.xy + box.zw));
}

// The tiles' layout (see rdTile()): columns, rows, one tile's size, and the
// bottom left corner of them all.
void rdTiles(vec2 screen, out int cols, out int rows, out vec2 size, out vec2 origin) {
	float top = screen.y - 0.15 * rdUnit(screen);
	cols = int(ceil(sqrt(float(TILES))));
	rows = (TILES + cols - 1) / cols;
	float scale = min(1.0 / float(cols), top / (float(rows) * screen.y));
	size = screen * scale;
	origin = vec2(screen.x - size.x * float(cols), top - size.y * float(rows)) * 0.5;
}

// The bottom of the buttons' row: just above the tiles.
float rdButtonsBottom(vec2 screen) {
	int cols, rows;
	vec2 size, origin;
	rdTiles(screen, cols, rows, size, origin);
	float u = rdUnit(screen);
	return min(origin.y + size.y * float(rows) + 0.025 * u, screen.y - 0.12 * u);
}

// The colour map button, left: origin, size.
vec4 rdMapButton(vec2 screen) {
	float u = rdUnit(screen);
	return vec4(0.04 * u, rdButtonsBottom(screen), vec2(0.3, 0.07) * u);
}

// The composite colour button, right: centre, radius.
vec3 rdCompositeButton(vec2 screen) {
	float u = rdUnit(screen);
	float r = 0.045 * u;
	return vec3(screen.x - 0.04 * u - r, rdButtonsBottom(screen) + 0.035 * u, r);
}

bool rdOnMapButton(vec2 p, vec2 screen) {
	vec4 b = rdMapButton(screen);
	float pad = 0.02 * rdUnit(screen);
	return rdInBox(p, vec4(b.xy - pad, b.zw + 2.0 * pad));
}

bool rdOnCompositeButton(vec2 p, vec2 screen) {
	vec3 b = rdCompositeButton(screen);
	return distance(p, b.xy) < b.z * 1.5;
}

// The brush button, left of the composite one: centre, radius.
vec3 rdBrushButton(vec2 screen) {
	vec3 b = rdCompositeButton(screen);
	return vec3(b.x - b.z * 3.2, b.y, b.z);
}

bool rdOnBrushButton(vec2 p, vec2 screen) {
	vec3 b = rdBrushButton(screen);
	return distance(p, b.xy) < b.z * 1.5;
}

// The random button, left of the brush one: centre, radius.
vec3 rdRandomButton(vec2 screen) {
	vec3 b = rdBrushButton(screen);
	return vec3(b.x - b.z * 3.2, b.y, b.z);
}

bool rdOnRandomButton(vec2 p, vec2 screen) {
	vec3 b = rdRandomButton(screen);
	return distance(p, b.xy) < b.z * 1.5;
}

// The brush size button, left of the random one: centre, radius.
vec3 rdPaintButton(vec2 screen) {
	vec3 b = rdRandomButton(screen);
	return vec3(b.x - b.z * 3.2, b.y, b.z);
}

bool rdOnPaintButton(vec2 p, vec2 screen) {
	vec3 b = rdPaintButton(screen);
	return distance(p, b.xy) < b.z * 1.5;
}

bool rdOnButton(vec2 p, vec2 screen) {
	return rdOnMapButton(p, screen) || rdOnCompositeButton(p, screen) ||
			rdOnBrushButton(p, screen) || rdOnRandomButton(p, screen) ||
			rdOnPaintButton(p, screen);
}

// Held on the colour map button: a strip of every map, one a row from the
// file's own at the top, down from the button - origin, size.
vec4 rdMapPicker(vec2 screen) {
	float u = rdUnit(screen);
	vec4 b = rdMapButton(screen);
	float bottom = 0.04 * u;
	float top = b.y - 0.02 * u;
	return vec4(b.x, bottom, b.z, top - bottom);
}

// Where, dragging right off the picker, the map's frequency starts being
// set: 1 there, up to MAX_FREQUENCY at the screen's right edge.
#define MAX_FREQUENCY 4.0
float rdFrequencyStart(vec2 screen) {
	vec4 r = rdMapPicker(screen);
	return r.x + r.z + 0.1 * rdUnit(screen);
}

float rdFrequencyEnd(vec2 screen) {
	return screen.x - 0.04 * rdUnit(screen);
}

// The colour map slot at height y on the picker.
float rdMapPickerSlot(float y, vec2 screen) {
	vec4 r = rdMapPicker(screen);
	return clamp(floor((r.y + r.w - y) / r.w * MAPS), 0.0, MAPS - 1.0);
}

// Chemical i's range on its tile (MASK_LOW_, MASK_HIGH_), as composite
// colour and the brush use it too.
vec2 maskRange(int i) {
	if (i == 0) return vec2(MASK_LOW_a, MASK_HIGH_a);
	if (i == 1) return vec2(MASK_LOW_b, MASK_HIGH_b);
	if (i == 2) return vec2(MASK_LOW_c, MASK_HIGH_c);
	if (i == 3) return vec2(MASK_LOW_d, MASK_HIGH_d);
	if (i == 4) return vec2(MASK_LOW_e, MASK_HIGH_e);
	return vec2(0.0, 1.0);
}

// Tile i in the tiles view (origin, size), below the buttons: each the
// screen's shape, as many across as down - or one more.
vec4 rdTile(int i, vec2 screen) {
	int cols, rows;
	vec2 size, origin;
	rdTiles(screen, cols, rows, size, origin);
	int row = i / cols;
	int col = i - row * cols;
	return vec4(origin + vec2(float(col), float(rows - 1 - row)) * size, size);
}

int rdTileAt(vec2 p, vec2 screen) {
	for (int i = 0; i < TILES; ++i) {
		if (rdInBox(p, rdTile(i, screen))) {
			return i;
		}
	}
	return -1;
}


// Each pass's own: the state row, and chemical i at cell q.
vec4 rdState(int texel, ivec2 grid);
float rdChem(int i, ivec2 q);
vec2 rdShowRange(int i, ivec2 grid);
bool rdNewFrame(ivec2 grid);

// Where the fingers are centred.
vec2 rdCentre() {
	vec2 c = vec2(0.0);
	for (int i = 0; i < 10; ++i) {
		if (i >= pointerCount) {
			break;
		}
		c += pointers[i].xy;
	}
	return c / max(float(pointerCount), 1.0);
}

// Each texel's start: the first view, every chemical's composite colour
// round the hue circle, weight and power 1.
vec4 rdStartState(int texel) {
	if (texel == S_GESTURE) return vec4(0.0, 0.0, 0.0, 0.0);
	if (texel == S_TOUCH || texel == S_MODE) return vec4(0.0, 0.0, 0.0, -1.0);
	if (texel >= S_COLOUR && texel < S_COLOUR + CHEMICALS) {
		return vec4(float(texel - S_COLOUR) / float(CHEMICALS), 0.8, 1.0,
				COMPOSITE_GAIN / float(CHEMICALS));
	}
	if (texel >= S_POWER && texel < S_POWER + CHEMICALS) return vec4(1.0, 0.0, 0.0, 0.0);
	if (texel == S_BRUSH) return vec4(0.5, 0.5, 1.0, 0.0);
	if (texel == S_FINGERS) return vec4(0.0, -1.0, 0.0, 2.0);
	if (texel == S_MAP) return vec4(1.0, 0.0, 0.0, 0.0);
	if (texel == S_PAINT) return vec4(1.0, 0.0, 0.0, 0.0);
	return vec4(0.0);
}

// The next frame's gesture, touch, mode, drag and fingers texels.
void rdNextState(ivec2 grid, vec2 screen, out vec4 g, out vec4 t, out vec4 m, out vec4 d,
		out vec4 f) {
	vec4 pg = rdState(S_GESTURE, grid);
	vec4 pt = rdState(S_TOUCH, grid);
	vec4 pm = rdState(S_MODE, grid);
	g = pg;
	t = pt;
	m = pm;
	d = rdState(S_DRAG, grid);
	int n = pointerCount;
	vec2 first = n > 0 ? pointers[0].xy : vec2(0.0);
	vec2 centre = rdCentre();
	f = vec4(float(n), -1.0, 0.0, 2.0);
	bool tiles = pg.z > 0.5 && pg.z < 1.5;
	bool tapped = pt.z < 0.5 && iTime - pg.y < TAP_TIME;
	if (n == 0 && pg.x > 1.5 && pg.x < 2.5 && tapped && tiles) {
		// A two-finger tap on a chemical's tile: its complement in
		// composite colour, on or off.
		if (pt.w > -0.5 && pt.w < float(CHEMICALS) - 0.5) {
			f.y = pt.w;
		}
	}
	if (n == 0) {
		if (pg.x > 0.0 && pg.x < 1.5 && tapped) {
			// A tap: in the tiles view a button or a tile, full screen back
			// to the tiles.
			vec2 at = pt.xy;
			if (tiles && rdOnBrushButton(at, screen)) {
				f.z = 1.0;
			} else if (tiles && rdOnRandomButton(at, screen)) {
				// A random look (see rdNextChemical()) - in composite.
				m.x = 1.0;
			} else if (tiles && rdOnMapButton(at, screen)) {
				m.y = mod(pm.y + 1.0, MAPS);
			} else if (tiles && rdOnCompositeButton(at, screen)) {
				m.x = 1.0 - pm.x;
			} else if (tiles) {
				int hit = rdTileAt(at, screen);
				if (hit >= 0) {
					g.z = 2.0;
					g.w = float(hit);
				}
			} else if (pg.z > 1.5) {
				g.z = 1.0;
			}
		}
		g.x = 0.0;
		m.z = 0.0;
	} else {
		if (pg.x == 0.0) {
			g.y = iTime;
			t = vec4(first, 0.0, float(rdTileAt(first, screen)));
		}
		g.x = max(pg.x, float(n));
		if (distance(first, t.xy) > TAP_MOVE * rdUnit(screen)) {
			t.z = 1.0;
		}
		// Three fingers: into the tiles, or out of them to colour.
		if (g.x >= 3.0 && pg.x < 3.0) {
			g.z = pg.z < 0.5 ? 1.0 : 0.0;
		}
		// The brush, dragged from its button: what one finger paints; a
		// second finger joining in, what two erase.
		if (tiles && rdOnBrushButton(t.xy, screen) && g.x < 3.0) {
			m.z = n >= 2 ? 4.0 : 3.0;
		}
		bool held1 = g.x < 1.5 && iTime - g.y >= PAINT_DELAY;
		// Held on the colour map button: the map under the finger, on the
		// picker.
		if (tiles && held1 && rdOnMapButton(t.xy, screen)) {
			m.z = 5.0;
		}
		// (Off its right, past a gap, the finger sets the map's frequency
		// instead - see rdNextMap().)
		if (m.z > 4.5 && m.z < 5.5 && first.x < rdFrequencyStart(screen)) {
			m.y = rdMapPickerSlot(first.y, screen);
		}
		// Held on random: wandering through random looks, in composite.
		if (tiles && held1 && rdOnRandomButton(t.xy, screen)) {
			m.z = 6.0;
			m.x = 1.0;
		}
		// Composite colour is edited on the tiles: hold one finger on a
		// chemical for its colour wheel; two drag its weight (up/down) and
		// power (sideways).
		bool held = g.x < 3.0 && iTime - g.y >= PAINT_DELAY;
		bool onChemical = t.w > -0.5 && t.w < float(CHEMICALS) - 0.5;
		if (pm.x > 0.5 && g.z > 0.5 && g.z < 1.5 && held && onChemical) {
			if (n == 1 && pm.z < 0.5) {
				m.z = 1.0;
				m.w = t.w;
				// Centred so the finger starts on the chemical's colour.
				vec4 c = rdState(S_COLOUR + int(t.w + 0.5), grid);
				float a = c.x * 6.28318531;
				d.xy = t.xy - WHEEL_RADIUS * rdUnit(screen) * c.y * vec2(cos(a), sin(a));
			} else if (n >= 2) {
				m.z = 2.0;
				m.w = t.w;
			}
		}
	}
	d.zw = centre;
}

// How far the fingers' centre moved since last frame, in screen
// heights - nothing across a change in how many there are.
vec2 rdMoved(ivec2 grid, vec2 screen) {
	if (pointerCount == 0 || float(pointerCount) != rdState(S_FINGERS, grid).x) {
		return vec2(0.0);
	}
	return (rdCentre() - rdState(S_DRAG, grid).zw) / rdUnit(screen);
}

// The brush texels, next frame: m and f are the next mode and fingers.
vec4 rdNextBrush(int texel, ivec2 grid, vec2 screen, vec4 m, vec4 f) {
	vec4 s = rdState(texel, grid);
	if (f.z > 0.5) {
		// A tap on the brush: back to the file's own.
		return rdStartState(texel);
	}
	if (texel == S_BRUSH_ON) {
		if (m.z > 2.5 && m.z < 3.5) s.x = 1.0;
		if (m.z > 3.5) s.y = 1.0;
		return s;
	}
	vec2 moved = rdMoved(grid, screen) * BRUSH_RATE;
	if (m.z > 2.5 && m.z < 3.5) {
		vec2 r1 = maskRange(max(BRUSH_PAINT.x, 0));
		vec2 r2 = maskRange(max(BRUSH_PAINT.y, 0));
		s.x = clamp(s.x + moved.x * (r1.y - r1.x), r1.x, r1.y);
		s.y = clamp(s.y + moved.y * (r2.y - r2.x), r2.x, r2.y);
	} else if (m.z > 3.5) {
		vec2 r1 = maskRange(max(BRUSH_ERASE.x, 0));
		vec2 r2 = maskRange(max(BRUSH_ERASE.y, 0));
		s.z = clamp(s.z + moved.x * (r1.y - r1.x), r1.x, r1.y);
		s.w = clamp(s.w + moved.y * (r2.y - r2.x), r2.x, r2.y);
	}
	return s;
}

// The colour map's frequency next frame: m is the next mode. Picking a
// map, a finger dragged right off the picker sets it - 1 just past the
// gap, MAX_FREQUENCY at the screen's edge, in eighths, whole numbers
// sticky.
vec4 rdNextMap(ivec2 grid, vec2 screen, vec4 m) {
	vec4 s = rdState(S_MAP, grid);
	if (m.z < 4.5 || m.z > 5.5 || pointerCount == 0) {
		return s;
	}
	float x = pointers[0].x;
	float start = rdFrequencyStart(screen);
	if (x < start) {
		return s;
	}
	float f = 1.0 + (MAX_FREQUENCY - 1.0) * clamp((x - start) / (rdFrequencyEnd(screen) - start), 0.0, 1.0);
	f = floor(f * 8.0 + 0.5) / 8.0;
	if (abs(f - floor(f + 0.5)) < 0.13) {
		f = floor(f + 0.5);
	}
	s.x = f;
	return s;
}

// Whether this frame ends a tap on the random button.
bool rdRandomTapped(ivec2 grid, vec2 screen) {
	vec4 pg = rdState(S_GESTURE, grid);
	vec4 pt = rdState(S_TOUCH, grid);
	return pointerCount == 0 && pg.x > 0.0 && pg.x < 1.5 && pt.z < 0.5 &&
			iTime - pg.y < TAP_TIME && pg.z > 0.5 && pg.z < 1.5 &&
			rdOnRandomButton(pt.xy, screen);
}

// The size texel next step: the grid's size, and whether the grid's
// blank - as the app leaves new buffers, (0, 0, 0, 1), at a few cells -
// which it is when the app's put back the state row alone (a rotation,
// the shader opened again), so the grid starts again (rdStarted()).
vec4 rdNextSize(ivec2 grid) {
	bool blank = true;
	for (int k = 0; k < 6; ++k) {
		ivec2 q = ivec2(vec2(0.13 + 0.15 * float(k), 0.71 - 0.11 * float(k)) * vec2(grid));
		blank = blank && rdChem(0, q) == 0.0 && rdChem(1, q) == 0.0 &&
				rdChem(2, q) == 0.0 && rdChem(3, q) == 1.0;
	}
	return vec4(vec2(grid), blank ? 0.0 : 1.0, 0.0);
}

// The brush size texel next frame: a tap on its button, the next size -
// a quarter, a half, one, then twice BRUSH_SIZE, and round again.
vec4 rdNextPaint(ivec2 grid, vec2 screen) {
	vec4 s = rdState(S_PAINT, grid);
	vec4 pg = rdState(S_GESTURE, grid);
	vec4 pt = rdState(S_TOUCH, grid);
	if (pointerCount == 0 && pg.x > 0.0 && pg.x < 1.5 && pt.z < 0.5 &&
			iTime - pg.y < TAP_TIME && pg.z > 0.5 && pg.z < 1.5 && rdOnPaintButton(pt.xy, screen)) {
		s.x = s.x > 1.5 ? 0.25 : s.x * 2.0;
	}
	return s;
}

// A new random look's seed - the same in every texel this frame.
float rdNewSeed() {
	return floor(fract(sin(iTime * 12.9898) * 43758.5453) * 500.0);
}

// The random texel next frame: m is the next mode.
vec4 rdNextRandom(ivec2 grid, vec4 m) {
	vec4 s = rdState(S_RANDOM, grid);
	float period = floor(iTime / RANDOM_REWIND);
	if (m.z > 5.5) {
		return s.z > 0.5 ? vec4(s.xyz, period) : vec4(rdNewSeed(), iTime, 1.0, period);
	}
	return vec4(s.xy, 0.0, period);
}

// A snapshot texel next frame: at each new RANDOM_REWIND period the newer
// snapshot becomes the older, and the chemical's look now the newer - so
// the older is always a period or two back.
vec4 rdNextSnapshot(int texel, ivec2 grid) {
	vec4 snap = rdState(texel, grid);
	if (floor(iTime / RANDOM_REWIND) == rdState(S_RANDOM, grid).w) {
		return snap;
	}
	int k = texel - S_SNAP;
	if (k < CHEMICALS) {
		vec4 now = rdState(S_COLOUR + k, grid);
		return vec4(now.x, now.w, snap.xy);
	}
	return vec4(rdState(S_POWER + k - CHEMICALS, grid).xy, snap.xy);
}

float rdHash(float seed, float k) {
	return fract(sin(dot(vec2(seed, k), vec2(12.9898, 78.233))) * 43758.5453);
}

// Chemical i's levels (0-1 across its range, rdShowRange()) over an 8x8 sample of
// the grid, each sample jittered off the lattice so a regular pattern
// can't fool it: their mean and standard deviation.
vec2 rdLevelStats(int i, ivec2 grid) {
	vec2 r = rdShowRange(i, grid);
	float sum = 0.0;
	float squares = 0.0;
	for (int y = 0; y < 8; ++y) {
		for (int x = 0; x < 8; ++x) {
			vec2 jitter = vec2(rdHash(float(x), float(y)), rdHash(float(y) + 7.0, float(x)));
			ivec2 q = ivec2((vec2(x, y) + jitter) / 8.0 * vec2(grid));
			float level = clamp((rdChem(i, q) - r.x) / (r.y - r.x), 0.0, 1.0);
			sum += level;
			squares += level * level;
		}
	}
	float mean = sum / 64.0;
	return vec2(mean, sqrt(max(squares / 64.0 - mean * mean, 0.0)));
}

// Chemical i's range texel next frame, from the same sample as
// rdLevelStats(): eased slowly towards the range of the values there now
// - a little faster out (RANGE_GROW) than in (RANGE_SHRINK) - so a
// passing spike doesn't make the whole view flash darker; values beyond
// it meanwhile just clamp. Measured outright the first time.
vec4 rdNextRange(int texel, ivec2 grid) {
	int i = texel - S_RANGE;
	float lo = 1e30;
	float hi = -1e30;
	for (int y = 0; y < 8; ++y) {
		for (int x = 0; x < 8; ++x) {
			vec2 jitter = vec2(rdHash(float(x), float(y)), rdHash(float(y) + 7.0, float(x)));
			float v = rdChem(i, ivec2((vec2(x, y) + jitter) / 8.0 * vec2(grid)));
			lo = min(lo, v);
			hi = max(hi, v);
		}
	}
	vec4 r = rdState(texel, grid);
	if (r.z < 0.5) {
		return vec4(lo, hi, 1.0, 0.0);
	}
	if (!rdNewFrame(grid)) {
		return r;
	}
	float easeLo = lo < r.x ? RANGE_GROW : RANGE_SHRINK;
	float easeHi = hi > r.y ? RANGE_GROW : RANGE_SHRINK;
	return vec4(mix(r.x, lo, easeLo), mix(r.y, hi, easeHi), 1.0, 0.0);
}

// How good the composite look is now, from the same sample as
// rdLevelStats(): its contrast (the spread of its brightness), how
// colourful it is, and how much its colour varies - less for samples
// crushed to black or blown to white. What wandering climbs.
float rdLookScore(ivec2 grid) {
	vec3 tint[CHEMICALS];
	vec2 power[CHEMICALS];
	vec2 range[CHEMICALS];
	for (int i = 0; i < CHEMICALS; ++i) {
		vec4 c = rdState(S_COLOUR + i, grid);
		tint[i] = c.w * hsv2rgb(c.xyz);
		power[i] = rdState(S_POWER + i, grid).xy;
		range[i] = rdShowRange(i, grid);
	}
	float l = 0.0;
	float ll = 0.0;
	float chroma = 0.0;
	vec3 hue = vec3(0.0);
	vec3 hues = vec3(0.0);
	float lost = 0.0;
	for (int y = 0; y < 8; ++y) {
		for (int x = 0; x < 8; ++x) {
			vec2 jitter = vec2(rdHash(float(x), float(y)), rdHash(float(y) + 7.0, float(x)));
			ivec2 q = ivec2((vec2(x, y) + jitter) / 8.0 * vec2(grid));
			vec3 c = vec3(0.0);
			for (int i = 0; i < CHEMICALS; ++i) {
				float level = clamp((rdChem(i, q) - range[i].x) / (range[i].y - range[i].x), 0.0, 1.0);
				level = power[i].y > 0.5 ? 1.0 - level : level;
				c += tint[i] * pow(level, power[i].x);
			}
			c = clamp(c, 0.0, 1.0);
			float lum = dot(c, vec3(0.299, 0.587, 0.114));
			l += lum;
			ll += lum * lum;
			chroma += max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
			hue += c - lum;
			hues += (c - lum) * (c - lum);
			lost += lum < 0.03 || lum > 0.97 ? 1.0 : 0.0;
		}
	}
	l /= 64.0;
	hue /= 64.0;
	float contrast = sqrt(max(ll / 64.0 - l * l, 0.0));
	vec3 spread = max(hues / 64.0 - hue * hue, 0.0);
	return contrast + 0.6 * chroma / 64.0 + 0.6 * sqrt(spread.r + spread.g + spread.b) -
			0.3 * lost / 64.0;
}

// The search texel next frame (m is the next mode). While random's held
// the look wanders a stretch at a time (SEARCH_STRETCH) along a direction
// picked at random; at each stretch's end, if the look scores better
// (rdLookScore()) it carries on that way, and if not it goes back the way
// it came - and from there, a new direction. So it climbs, slowly,
// towards looks with more contrast and more colour.
vec4 rdNextSearch(ivec2 grid, vec4 m) {
	vec4 s = rdState(S_SEARCH, grid);
	float stretch = floor(iTime / SEARCH_STRETCH);
	if (m.z < 5.5) {
		return vec4(stretch, stretch, 0.0, 1.0);
	}
	if (stretch == s.x) {
		return s;
	}
	float score = rdLookScore(grid);
	if (s.w < 0.0) {
		// Back where the last good direction left it: a new one.
		return vec4(stretch, stretch, score, 1.0);
	}
	return vec4(stretch, s.y, score, score > s.z ? 1.0 : -1.0);
}

// The way the search goes this stretch for one of chemical chem's values
// (j), -1 to 1.
float rdSearchStep(vec4 search, float k) {
	return (rdHash(search.y, k) - 0.5) * 2.0 * search.w;
}

// Spread round the hue circle by rank: 0, a half, a quarter, three
// quarters, an eighth... - so the two most varied chemicals get opposite
// (complementary) hues, and each next one the biggest gap left.
float rdHueSpread(int rank) {
	float v = 0.0;
	float f = 0.5;
	int n = rank;
	for (int k = 0; k < 3; ++k) {
		if (n - (n / 2) * 2 == 1) {
			v += f;
		}
		n /= 2;
		f *= 0.5;
	}
	return v;
}

// What would show chemical chem clearly, from a sample of the grid now:
// its rank by how much it varies (0 the most), that variation's share of
// all of theirs, and its levels' mean.
void rdGuide(int chem, ivec2 grid, out int rank, out float share, out float mean) {
	vec2 mine = rdLevelStats(chem, grid);
	float total = 0.0;
	rank = 0;
	for (int j = 0; j < CHEMICALS; ++j) {
		float sd = j == chem ? mine.y : rdLevelStats(j, grid).y;
		total += sd;
		if (sd > mine.y || (sd == mine.y && j < chem)) {
			++rank;
		}
	}
	share = mine.y / max(total, 1e-3);
	mean = mine.x;
}

// The weight that shows a chemical with `share` of the variation: some for
// every chemical, more for the varied ones, adding up to COMPOSITE_GAIN.
float rdGuidedWeight(float share) {
	return COMPOSITE_GAIN * mix(1.0 / float(CHEMICALS), share, 0.6);
}

// The power putting a typical level (0-1) about mid-way.
float rdGuidedPower(float typical) {
	return clamp(log(0.4) / log(clamp(typical, 0.03, 0.97)), 0.25, 6.0);
}

// Chemical chem's colour or power texel in a new random look, `seed`'s:
// hues spread round the circle by how much each chemical varies (see
// rdGuide()), each power putting its typical level about mid-way (its
// complement, if mostly high, so its features show bright), weights by
// variation - each nudged by chance.
vec4 rdRandomLook(int texel, int chem, float seed, ivec2 grid) {
	float k = float(chem) * 8.0;
	int rank;
	float share, mean;
	rdGuide(chem, grid, rank, share, mean);
	if (texel < S_POWER) {
		float hue = fract(rdHash(seed, 90.0) + rdHueSpread(rank) + (rdHash(seed, k) - 0.5) * 0.15);
		return vec4(hue, mix(0.75, 1.0, rdHash(seed, k + 1.0)), mix(0.85, 1.0, rdHash(seed, k + 2.0)),
				rdGuidedWeight(share) * mix(0.6, 1.4, rdHash(seed, k + 3.0)));
	}
	bool high = mean > 0.55;
	float power = rdGuidedPower(high ? 1.0 - mean : mean) * exp((rdHash(seed, k + 4.0) - 0.5) * 0.8);
	return vec4(power, high ? 1.0 : 0.0, 0.0, 0.0);
}

// Chemical chem's colour or power texel s, one step of wandering on: a
// little way along the search's direction (RANDOM_NUDGE, see
// rdNextSearch()), and a little way (RANDOM_PULL)
// towards what would show it clearly - its hue the biggest gap from the
// most varied chemical's (see rdHueSpread()), its weight and power as
// rdRandomLook() picks them, its complement now and then.
vec4 rdWanderStep(int texel, int chem, vec4 s, ivec2 grid) {
	float n = float(iFrame);
	float k = float(chem) * 8.0;
	vec4 search = S_SEARCH < grid.x ? rdState(S_SEARCH, grid) : vec4(n, n, 0.0, 1.0);
	int rank;
	float share, mean;
	rdGuide(chem, grid, rank, share, mean);
	if (texel < S_POWER) {
		int top = 0;
		for (int j = 0; j < CHEMICALS; ++j) {
			int r;
			float sh, me;
			if (j != chem) {
				rdGuide(j, grid, r, sh, me);
				if (r == 0) {
					top = j;
				}
			} else if (rank == 0) {
				top = j;
			}
		}
		float want = fract(rdState(S_COLOUR + top, grid).x + rdHueSpread(rank));
		float off = fract(want - s.x + 0.5) - 0.5;
		s.x = fract(s.x + rdSearchStep(search, k) * 0.5 * RANDOM_NUDGE * 4.0 +
				(top == chem ? 0.0 : off * RANDOM_PULL * 4.0));
		s.y = clamp(s.y + rdSearchStep(search, k + 1.0) * 0.5 * RANDOM_NUDGE +
				(0.9 - s.y) * RANDOM_PULL, 0.5, 1.0);
		s.z = clamp(s.z + rdSearchStep(search, k + 2.0) * 0.5 * RANDOM_NUDGE +
				(0.95 - s.z) * RANDOM_PULL, 0.6, 1.0);
		s.w += rdSearchStep(search, k + 3.0) * 0.5 * RANDOM_NUDGE * 2.0 +
				(rdGuidedWeight(share) - s.w) * RANDOM_PULL;
		return s;
	}
	bool high = mean > 0.55;
	float lp = log(max(s.x, 0.1));
	lp += rdSearchStep(search, k + 4.0) * 0.5 * RANDOM_NUDGE * 4.0 +
			(log(rdGuidedPower(high ? 1.0 - mean : mean)) - lp) * RANDOM_PULL;
	s.x = clamp(exp(lp), 0.1, 10.0);
	if ((s.y > 0.5) != high && rdHash(n, k + 5.0) < RANDOM_PULL) {
		s.y = high ? 1.0 : 0.0;
	}
	return s;
}

// A chemical's composite colour or power texel, next frame: m, d and f
// are the next mode, drag and fingers texels.
vec4 rdNextChemical(int texel, ivec2 grid, vec2 screen, vec4 m, vec4 d, vec4 f) {
	vec4 s = rdState(texel, grid);
	int chem = texel >= S_POWER ? texel - S_POWER : texel - S_COLOUR;
	// Random looks: wandering while random's held, then where it was a
	// moment before it was let go; a tap, a new one.
	vec4 r = rdState(S_RANDOM, grid);
	if (m.z > 5.5) {
		return rdNewFrame(grid) ? rdWanderStep(texel, chem, s, grid) : s;
	}
	if (r.z > 0.5) {
		// Just let go: back to the older snapshot, a moment before.
		vec4 snap = rdState(S_SNAP + (texel >= S_POWER ? CHEMICALS : 0) + chem, grid);
		return texel < S_POWER ? vec4(snap.z, s.y, s.z, snap.w) : vec4(snap.zw, 0.0, 0.0);
	}
	if (rdRandomTapped(grid, screen)) {
		return rdRandomLook(texel, chem, rdNewSeed(), grid);
	}
	if (texel >= S_POWER && abs(f.y - float(chem)) < 0.5) {
		s.y = 1.0 - s.y;
	}
	if (abs(m.w - float(chem)) > 0.5 || pointerCount == 0) {
		return s;
	}
	if (m.z > 0.5 && m.z < 1.5 && texel < S_POWER) {
		vec2 v = pointers[0].xy - d.xy;
		s.x = fract(atan(v.y, v.x) / 6.28318531);
		s.y = clamp(length(v) / (WHEEL_RADIUS * rdUnit(screen)), 0.0, 1.0);
	}
	// Two fingers: by how far their centre moved since last frame - not
	// across a change in how many there are.
	if (m.z > 1.5 && m.z < 2.5 && pointerCount >= 2) {
		vec2 moved = rdMoved(grid, screen);
		if (texel < S_POWER) {
			s.w = clamp(s.w + moved.y * WEIGHT_RATE, -2.0, 4.0);
		} else {
			s.x = clamp(s.x * exp(moved.x * POWER_RATE), 0.1, 10.0);
		}
	}
	return s;
}

// Whether v is NaN or infinite - from its bits (its exponent all ones),
// as compilers may take isnan()/isinf() to be always false.
bool rdBad(float v) {
	return (floatBitsToUint(v) & 0x7f800000u) == 0x7f800000u;
}

// Chemical value v, kept finite: gone NaN or infinite - which the
// Laplacian would spread to every neighbour, a stain taking over the
// screen - it goes back to what it was this step (or 0, were that bad
// too). Nothing else is touched.
float rdSane(float v, float before) {
	return rdBad(v) ? (rdBad(before) ? 0.0 : before) : v;
}

// The terrain view's camera (tools/samples/terrain/make_terrain.py), in
// spare texels of the state row, worked out in each frame's first step:
const int CAM_POSE = 53;  // where it is over the grid (x, y, in cells), heading, height
const int CAM_LOOK = 54;  // pitch (down from level), when last moved (iTime), last
                          // frame's finger count, and 2 once started (new buffers clear
                          // to an alpha of 1)
const int CAM_TOUCH = 55; // last frame's first two fingers (x, y, x, y)
const int CAM_TAPS = 56;  // 1 in the pattern's own interface (0 the terrain), taps so far,
                          // when the last ended, when this touch began
const int CAM_DOWN = 57;  // where this touch began (x, y), 1 once it's moved too far for a tap
#define CAM_TAP_TIME 0.3   // seconds a tap may last
#define CAM_TAP_GAP 0.45   // and the most between a triple tap's taps
#define CAM_SPEED 5.0      // cells a second it drifts forward
#define CAM_HEIGHT 10.0    // cells above the ground to start
#define CAM_PITCH 0.26     // radians down from level to start
#define CAM_TILT 2.2       // radians of tilt per screen height two fingers drag
#define LENS 0.18           // barrel curvature at the corners
#define FOCAL 1.25          // the lens's focal length, of the screen's height

void camStart(ivec2 grid, out vec4 pose, out vec4 look) {
	pose = vec4(vec2(grid) * 0.5, 0.0, CAM_HEIGHT);
	look = vec4(CAM_PITCH, iTime, 0.0, 2.0);
}

// Whether the pattern's own interface is up, not the terrain.
bool camInterface(ivec2 grid) {
	return CAM_TAPS < grid.x && rdState(CAM_TAPS, grid).x > 0.5;
}

// The camera's texels next frame, from last frame's and the fingers.
void camNext(ivec2 grid, vec2 screen, out vec4 pose, out vec4 look, out vec4 touch,
		out vec4 taps, out vec4 down) {
	pose = rdState(CAM_POSE, grid);
	look = rdState(CAM_LOOK, grid);
	touch = rdState(CAM_TOUCH, grid);
	taps = rdState(CAM_TAPS, grid);
	down = rdState(CAM_DOWN, grid);
	if (look.w < 1.5) {
		camStart(grid, pose, look);
		touch = vec4(0.0);
		taps = vec4(0.0);
		down = vec4(0.0);
		return;
	}
	if (!rdNewFrame(grid)) {
		return;
	}
	float dt = clamp(iTime - look.y, 0.0, 0.1);
	look.y = iTime;
	int n = pointerCount;
	int was = int(look.z + 0.5);
	vec2 p0 = pointers[0].xy;
	vec2 p1 = pointers[1].xy;
	// Taps, counted for a triple tap: short, one finger, hardly moving.
	if (n > 0 && was == 0) {
		taps.w = iTime;
		down = vec4(p0, 0.0, 0.0);
	}
	if (n > 1 || (n == 1 && distance(p0, down.xy) > 0.03 * min(screen.x, screen.y))) {
		down.z = 1.0;
	}
	if (n == 0 && was == 1) {
		if (down.z < 0.5 && iTime - taps.w < CAM_TAP_TIME) {
			taps.y = iTime - taps.z < CAM_TAP_GAP ? taps.y + 1.0 : 1.0;
			taps.z = iTime;
			if (taps.y >= 3.0) {
				taps.x = 1.0 - taps.x;
				taps.y = 0.0;
			}
		} else {
			taps.y = 0.0;
		}
	}
	look.z = float(n);
	if (taps.x > 0.5) {
		// The interface has the fingers; the camera waits where it is.
		touch = vec4(p0, p1);
		return;
	}
	if (n >= 2 && was >= 2) {
		vec2 mid0 = (touch.xy + touch.zw) * 0.5;
		vec2 mid1 = (p0 + p1) * 0.5;
		float len0 = max(distance(touch.xy, touch.zw), 1.0);
		float len1 = max(distance(p0, p1), 1.0);
		// Up and down tilts (straight down at most); across slides the
		// ground sideways with the fingers, about as far as it moves on
		// screen at this height.
		vec2 moved = mid1 - mid0;
		look.x = clamp(look.x - moved.y / screen.y * CAM_TILT, 0.0, 1.5707963);
		vec2 r = vec2(sin(pose.z), -cos(pose.z));
		pose.xy -= r * (moved.x / screen.y * pose.w * 1.6);
		pose.w = clamp(pose.w * len0 / len1, 2.0, 120.0);
		vec2 a = touch.zw - touch.xy;
		vec2 b = p1 - p0;
		pose.z += atan(a.x * b.y - a.y * b.x, dot(a, b));
	}
	pose.xy += vec2(cos(pose.z), sin(pose.z)) * CAM_SPEED * dt;
	// Kept to one copy of the grid (it wraps), so precision holds.
	pose.xy = mod(pose.xy, vec2(grid));
	touch = vec4(p0, p1);
}

// The view's ray through screen point s, as the terrain draws it.
vec3 camRay(vec2 s, vec2 screen, vec4 pose, vec4 look) {
	vec2 uv = (s - 0.5 * screen) / screen.y;
	uv *= 1.0 + LENS * dot(uv, uv);
	float pitch = look.x;
	vec3 f = vec3(cos(pose.z) * cos(pitch), sin(pose.z) * cos(pitch), -sin(pitch));
	// Right from the heading, so it holds looking straight down too.
	vec3 r = vec3(sin(pose.z), -cos(pose.z), 0.0);
	vec3 u = cross(r, f);
	return normalize(f * FOCAL + uv.x * r + uv.y * u);
}

// Where on the ground (in cells) a finger at s touches, taking the ground
// as flat at the middle of the hills - near enough for a brush. The copy
// nearest `near`, as the grid wraps.
vec2 camGround(vec2 s, vec2 near, ivec2 grid, vec2 screen) {
	vec4 pose = rdState(CAM_POSE, grid);
	vec4 look = rdState(CAM_LOOK, grid);
	vec3 rd = camRay(s, screen, pose, look);
	vec2 at = pose.xy + rd.xy * (pose.w / max(-rd.z, 0.02));
	vec2 g = vec2(grid);
	return near + mod(at - near + 0.5 * g, g) - 0.5 * g;
}

vec4 camTexel(int texel, ivec2 grid, vec2 screen) {
	vec4 pose, look, touch, taps, down;
	camNext(grid, screen, pose, look, touch, taps, down);
	return texel == CAM_POSE ? pose : texel == CAM_LOOK ? look : texel == CAM_TOUCH ? touch
			: texel == CAM_TAPS ? taps : down;
}
//// Buffer A
// Step 1 of 2 a frame: chemicals a, b, c, d.
///float32
///stateRow:top
///resize:tile
uniform sampler2D iChannel0; ///buf:C;
uniform sampler2D iChannel1; ///buf:D;

// The wide Laplacian (LAPLACIAN_SCALE above 1) is a Gaussian's: the blur's
// difference from the cell, over the blur's variance - which works out the
// Laplacian of what's smooth at the blur's scale. The blur widens with the
// scale, so it stays as stable as the 3x3 one, at the same timestep.
#define LAPLACIAN_SIGMA 0.7
#define LAPLACIAN_REACH 12
void rdWideBlur(ivec2 p, ivec2 grid, out vec4 blur0, out float variance) {
	float sigma = LAPLACIAN_SCALE * LAPLACIAN_SIGMA;
	int reach = min(int(ceil(2.5 * sigma)), LAPLACIAN_REACH);
	blur0 = vec4(0.0);
	float weights = 0.0;
	variance = 0.0;
	for (int y = -reach; y <= reach; ++y) {
		for (int x = -reach; x <= reach; ++x) {
			float w = exp(-0.5 * float(x * x + y * y) / (sigma * sigma));
			ivec2 q = rdCell(p + ivec2(x, y), grid);
			blur0 += w * texelFetch(iChannel0, q, 0);
			weights += w;
			variance += w * float(x * x);
		}
	}
	blur0 /= weights;
	variance /= weights;
}

vec4 rdState(int texel, ivec2 grid) {
	return texelFetch(iChannel0, ivec2(texel, grid.y), 0);
}

// Chemical i's range as the views show it (composite colour, its tile):
// the range its values have had lately (see rdNextRange()), so one that
// goes below 0 - a wave - or never near 1 still shows its whole swing;
// its mask range until that's been measured (or with too narrow a grid to
// hold it). Painting keeps to the mask range.
vec2 rdShowRange(int i, ivec2 grid) {
	if (S_RANGE + i >= grid.x) {
		return maskRange(i);
	}
	vec4 r = rdState(S_RANGE + i, grid);
	if (r.z < 0.5) {
		return maskRange(i);
	}
	// Not so narrow that a chemical that's all one value shows its
	// rounding as a pattern.
	float least = 1e-5 + 1e-3 * max(abs(r.x), abs(r.y));
	return vec2(r.x, max(r.y, r.x + least));
}

// How big the brush is now, of the grid's shorter side.
float rdBrushSize(ivec2 grid) {
	return BRUSH_SIZE * (S_PAINT < grid.x ? clamp(rdState(S_PAINT, grid).x, 0.25, 2.0) : 1.0);
}

// Whether this is a frame's first step: what drifts slowly (wandering,
// the ranges) moves once a frame, however many steps a frame takes
// (`///steps:`) - by iTime, the same for every step of a frame.
bool rdNewFrame(ivec2 grid) {
	return S_CLOCK >= grid.x || rdState(S_CLOCK, grid).x != iTime;
}

// Whether the state row is blank too - so the interface starts afresh.
bool rdFresh(ivec2 grid) {
	return rdState(S_FINGERS, grid).w < 1.5;
}

// Whether the simulation has started: a mark in the state row, rather than
// iFrame == 0 - the app's frame count goes back to 0 whenever its surface
// is made again (the screen timing out, the app coming back), though the
// buffers carry on; so only buffers that really are blank (new, or cleared
// by Restart) start the initial pattern again. The mark is 2, not 1: the
// app clears new buffers to black with an alpha of 1. And with its
// grid not blank: the app keeps the state row across a resize or a
// rotation (`///stateRow:top`) and tiles the old grid over the new one
// (`///resize:tile`), so both carry on; where it can't keep the grid
// (an older app, 8-bit buffers) it comes back blank, and starts again
// while the interface carries on (see rdFresh(), rdNextSize()).
bool rdStarted(ivec2 grid) {
	if (rdFresh(grid)) {
		return false;
	}
	if (S_SIZE >= grid.x) {
		return true;
	}
	// Not the size: the app tiles a kept grid over a new one
	// (`///resize:tile`), so a rotated one carries on.
	return rdState(S_SIZE, grid).z > 0.5;
}

// Chemical i (0 = a) at cell q, as the last step left it.
float rdChem(int i, ivec2 q) {
	return i < 4 ? texelFetch(iChannel0, q, 0)[i] : texelFetch(iChannel1, q, 0)[i - 4];
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	ivec2 size = textureSize(iChannel0, 0);
	vec2 screen = vec2(size);
	// The top row holds the interface's state (UI_COMMON's S_*); the
	// grid's below it.
	ivec2 grid = ivec2(size.x, size.y - 1);
	vec4 uiGesture, uiTouch, uiMode, uiDrag, uiFingers;
	bool starting = !rdStarted(grid);
	bool fresh = rdFresh(grid);
	if (fresh) {
		uiGesture = rdStartState(S_GESTURE);
		uiTouch = rdStartState(S_TOUCH);
		uiMode = rdStartState(S_MODE);
		uiDrag = rdStartState(S_DRAG);
		uiFingers = rdStartState(S_FINGERS);
	} else {
		rdNextState(grid, screen, uiGesture, uiTouch, uiMode, uiDrag, uiFingers);
	}
	if (p.y == grid.y) {
		int texel = p.x;
		if (texel == S_GESTURE) fragColor = uiGesture;
		else if (texel == S_TOUCH) fragColor = uiTouch;
		else if (texel == S_MODE) fragColor = uiMode;
		else if (texel == S_DRAG) fragColor = uiDrag;
		else if (texel == S_FINGERS) fragColor = uiFingers;
		else if (texel == S_SIZE) fragColor = rdNextSize(grid);
		else if (texel >= CAM_POSE && texel <= CAM_DOWN) fragColor = camTexel(texel, grid, screen);
		else if (texel == S_CLOCK) fragColor = vec4(iTime, 0.0, 0.0, 0.0);
		else if (!fresh && texel == S_PAINT) fragColor = rdNextPaint(grid, screen);
		else if (fresh) fragColor = rdStartState(texel);
		else if (texel == S_BRUSH || texel == S_BRUSH_ON) {
			fragColor = rdNextBrush(texel, grid, screen, uiMode, uiFingers);
		}
		else if (texel == S_RANDOM) fragColor = rdNextRandom(grid, uiMode);
		else if (texel == S_MAP) fragColor = rdNextMap(grid, screen, uiMode);
		else if (texel == S_SEARCH) fragColor = rdNextSearch(grid, uiMode);
		else if (texel >= S_RANGE && texel < S_RANGE + CHEMICALS) {
			fragColor = rdNextRange(texel, grid);
		}
		else if (texel >= S_SNAP && texel < S_SNAP + 2 * CHEMICALS) {
			fragColor = rdNextSnapshot(texel, grid);
		}
		else if ((texel >= S_COLOUR && texel < S_COLOUR + CHEMICALS) ||
				(texel >= S_POWER && texel < S_POWER + CHEMICALS)) {
			fragColor = rdNextChemical(texel, grid, screen, uiMode, uiDrag, uiFingers);
		} else fragColor = vec4(0.0);
		return;
	}
	if (starting) {
		// The start state.
		float a = 0.0;
		float b = 0.0;
		float c = 0.0;
		float d = 0.0;
		float e = 0.0;
		rdStart(p, a, b, c, d, e);
		fragColor = vec4(a, b, c, d);
		return;
	}

	// Cells needed:
	vec4 cell0_m1_m1 = texelFetch(iChannel0, rdCell(p + ivec2(-1, -1), grid), 0);
	vec4 cell0_m1_0 = texelFetch(iChannel0, rdCell(p + ivec2(-1, 0), grid), 0);
	vec4 cell0_m1_1 = texelFetch(iChannel0, rdCell(p + ivec2(-1, 1), grid), 0);
	vec4 cell0_0_m1 = texelFetch(iChannel0, rdCell(p + ivec2(0, -1), grid), 0);
	vec4 cell0_0_0 = texelFetch(iChannel0, p, 0);
	vec4 cell1_0_0 = texelFetch(iChannel1, p, 0);
	vec4 cell0_0_1 = texelFetch(iChannel0, rdCell(p + ivec2(0, 1), grid), 0);
	vec4 cell0_1_m1 = texelFetch(iChannel0, rdCell(p + ivec2(1, -1), grid), 0);
	vec4 cell0_1_0 = texelFetch(iChannel0, rdCell(p + ivec2(1, 0), grid), 0);
	vec4 cell0_1_1 = texelFetch(iChannel0, rdCell(p + ivec2(1, 1), grid), 0);
	float a = cell0_0_0.x;
	float b = cell0_0_0.y;
	float c = cell0_0_0.z;
	float d = cell0_0_0.w;
	float e = cell1_0_0.x;
	float a_sw = cell0_m1_m1.x;
	float b_sw = cell0_m1_m1.y;
	float c_sw = cell0_m1_m1.z;
	float d_sw = cell0_m1_m1.w;
	float a_w = cell0_m1_0.x;
	float b_w = cell0_m1_0.y;
	float c_w = cell0_m1_0.z;
	float d_w = cell0_m1_0.w;
	float a_nw = cell0_m1_1.x;
	float b_nw = cell0_m1_1.y;
	float c_nw = cell0_m1_1.z;
	float d_nw = cell0_m1_1.w;
	float a_s = cell0_0_m1.x;
	float b_s = cell0_0_m1.y;
	float c_s = cell0_0_m1.z;
	float d_s = cell0_0_m1.w;
	float a_n = cell0_0_1.x;
	float b_n = cell0_0_1.y;
	float c_n = cell0_0_1.z;
	float d_n = cell0_0_1.w;
	float a_se = cell0_1_m1.x;
	float b_se = cell0_1_m1.y;
	float c_se = cell0_1_m1.z;
	float d_se = cell0_1_m1.w;
	float a_e = cell0_1_0.x;
	float b_e = cell0_1_0.y;
	float c_e = cell0_1_0.z;
	float d_e = cell0_1_0.w;
	float a_ne = cell0_1_1.x;
	float b_ne = cell0_1_1.y;
	float c_ne = cell0_1_1.z;
	float d_ne = cell0_1_1.w;

	// Keywords needed:
	float laplacian_a = (-20.0 * a + a_nw + a_ne + a_sw + a_se + 4.0 * (a_n + a_w + a_e + a_s)) / (6.0 * dx * dx);
	float laplacian_b = (-20.0 * b + b_nw + b_ne + b_sw + b_se + 4.0 * (b_n + b_w + b_e + b_s)) / (6.0 * dx * dx);
	float laplacian_c = (-20.0 * c + c_nw + c_ne + c_sw + c_se + 4.0 * (c_n + c_w + c_e + c_s)) / (6.0 * dx * dx);
	float laplacian_d = (-20.0 * d + d_nw + d_ne + d_sw + d_se + 4.0 * (d_n + d_w + d_e + d_s)) / (6.0 * dx * dx);
	if (LAPLACIAN_SCALE > 1.0) {
		float variance;
		vec4 blur0;
		rdWideBlur(p, grid, blur0, variance);
		float wide = 2.0 * LAPLACIAN_SCALE * LAPLACIAN_SCALE / (variance * dx * dx);
		laplacian_a = wide * (blur0.x - a);
		laplacian_b = wide * (blur0.y - b);
		laplacian_c = wide * (blur0.z - c);
		laplacian_d = wide * (blur0.w - d);
	} else {
		laplacian_a *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;
		laplacian_b *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;
		laplacian_c *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;
		laplacian_d *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;
	}
	float delta_a = 0.0;
	float delta_b = 0.0;
	float delta_c = 0.0;
	float delta_d = 0.0;
	float delta_e = 0.0;

	// The formula:
	delta_a = (D_a + c*c_to_Da + d*wave_to_Da) * laplacian_a - a*b*b + (F + c_to_F*c + d*wave_to_F)*(1.0-a) + c*c_to_a + d*wave_to_a;
	delta_b = (D_b  + c*c_to_Db + d*wave_to_Db) * laplacian_b + a*b*b - ((F + c_to_F*c + d*wave_to_F)+(k_ + c*c_to_k + d*wave_to_k))*b + c*c_to_b + d*wave_to_b;
	delta_c = -c*cDamp + a*a_to_c + b*b_to_c + d*wave_to_c + D_a_to_c * laplacian_a + D_b_to_c * laplacian_b + D_c * laplacian_c;
	c = clamp( rdPow( float( fabs(c)/c_pow_norm ), float(c_pow) )*c_pow_norm, 0.0, 1.0 );
	d = sign(d) * rdPow( float( fabs(d) * wave_damp), float(wave_power) ) + a * a_wave + b * b_wave + c * c_wave;
	delta_e = laplacian_d * wave_diffusion;
	delta_d = e + delta_e;

	float before_a = a;
	float before_b = b;
	float before_c = c;
	float before_d = d;
	// Forward Euler:
	a += timestep * delta_a;
	b += timestep * delta_b;
	c += timestep * delta_c;
	d += timestep * delta_d;

	// Painting - see paint_code() in tools/ready/vti2enki.py.
	// Over the terrain one finger paints (two fly the camera).
	bool touching = (camInterface(grid) || uiGesture.x < 1.5) && uiGesture.x > 0.0 && uiGesture.x < 3.0 && iTime - uiGesture.y >= PAINT_DELAY && uiMode.z < 0.5;
	bool erasing = touching && uiGesture.x >= 2.0;
	bool painting = touching && !erasing;
	int view = int(uiGesture.z + 0.5);
	if (!camInterface(grid)) {
		view = 0;
	}
	int shown = int(uiGesture.w + 0.5);
	float init_a, init_b, init_c, init_d, init_e;
	if (painting) {
		rdInitial(p, grid, iFrame, init_a, init_b, init_c, init_d, init_e);
	}
	// The brush button's values, once it's been dragged (UI_COMMON).
	vec4 brush = rdState(S_BRUSH, grid);
	vec4 brushOn = rdState(S_BRUSH_ON, grid);
	for (int i = 0; i < pointerCount; ++i) {
		if (!touching) {
			break;
		}
		vec2 at = pointers[i].xy;
		if (!camInterface(grid)) {
			at = camGround(at, fragCoord, grid, screen);
		}
		if (view == 1 && rdOnButton(at, screen)) {
			continue;
		}
		int only = -1; // a chemical alone, or -1 for the colour view's
		if (view == 1) {
			int hit = rdTileAt(at, screen);
			if (hit < 0) {
				continue;
			}
			vec4 tile = rdTile(hit, screen);
			at = (at - tile.xy) / tile.zw * vec2(grid);
			only = hit < CHEMICALS ? hit : -1;
		} else if (view == 2 && shown < CHEMICALS) {
			only = shown;
		}
		float away = distance(at, fragCoord) / (rdBrushSize(grid) * float(min(grid.x, grid.y)));
		if (erasing && away < ERASE_SIZE) {
			if (only < 0) {
				a = ERASE_a;
				b = ERASE_b;
			}
			if (only == 0) a = (brushOn.y > 0.5 ? brush.z : ERASE_a);
			if (only == 1) b = (brushOn.y > 0.5 ? brush.w : ERASE_b);
			if (only == 2) c = MASK_LOW_c;
			if (only == 3) d = MASK_LOW_d;
		}
		if (painting && away < 1.0) {
			if (only < 0) {
				a = (brushOn.x > 0.5 ? brush.x : init_a);
				b = (brushOn.x > 0.5 ? brush.y : init_b);
			}
			if (only == 0) a = (brushOn.x > 0.5 ? brush.x : init_a);
			if (only == 1) b = (brushOn.x > 0.5 ? brush.y : init_b);
			if (only == 2) c = MASK_HIGH_c;
			if (only == 3) d = MASK_HIGH_d;
		}
	}
	// Kept finite - see rdSane().
	a = rdSane(a, before_a);
	b = rdSane(b, before_b);
	c = rdSane(c, before_c);
	d = rdSane(d, before_d);
	fragColor = vec4(a, b, c, d);
}
//// Buffer B
// Step 1 of 2 a frame: chemicals e.
///float32
///stateRow:top
///resize:tile
uniform sampler2D iChannel0; ///buf:C;
uniform sampler2D iChannel1; ///buf:D;

// The wide Laplacian (LAPLACIAN_SCALE above 1) is a Gaussian's: the blur's
// difference from the cell, over the blur's variance - which works out the
// Laplacian of what's smooth at the blur's scale. The blur widens with the
// scale, so it stays as stable as the 3x3 one, at the same timestep.
#define LAPLACIAN_SIGMA 0.7
#define LAPLACIAN_REACH 12
void rdWideBlur(ivec2 p, ivec2 grid, out vec4 blur0, out float variance) {
	float sigma = LAPLACIAN_SCALE * LAPLACIAN_SIGMA;
	int reach = min(int(ceil(2.5 * sigma)), LAPLACIAN_REACH);
	blur0 = vec4(0.0);
	float weights = 0.0;
	variance = 0.0;
	for (int y = -reach; y <= reach; ++y) {
		for (int x = -reach; x <= reach; ++x) {
			float w = exp(-0.5 * float(x * x + y * y) / (sigma * sigma));
			ivec2 q = rdCell(p + ivec2(x, y), grid);
			blur0 += w * texelFetch(iChannel0, q, 0);
			weights += w;
			variance += w * float(x * x);
		}
	}
	blur0 /= weights;
	variance /= weights;
}

vec4 rdState(int texel, ivec2 grid) {
	return texelFetch(iChannel0, ivec2(texel, grid.y), 0);
}

// Chemical i's range as the views show it (composite colour, its tile):
// the range its values have had lately (see rdNextRange()), so one that
// goes below 0 - a wave - or never near 1 still shows its whole swing;
// its mask range until that's been measured (or with too narrow a grid to
// hold it). Painting keeps to the mask range.
vec2 rdShowRange(int i, ivec2 grid) {
	if (S_RANGE + i >= grid.x) {
		return maskRange(i);
	}
	vec4 r = rdState(S_RANGE + i, grid);
	if (r.z < 0.5) {
		return maskRange(i);
	}
	// Not so narrow that a chemical that's all one value shows its
	// rounding as a pattern.
	float least = 1e-5 + 1e-3 * max(abs(r.x), abs(r.y));
	return vec2(r.x, max(r.y, r.x + least));
}

// How big the brush is now, of the grid's shorter side.
float rdBrushSize(ivec2 grid) {
	return BRUSH_SIZE * (S_PAINT < grid.x ? clamp(rdState(S_PAINT, grid).x, 0.25, 2.0) : 1.0);
}

// Whether this is a frame's first step: what drifts slowly (wandering,
// the ranges) moves once a frame, however many steps a frame takes
// (`///steps:`) - by iTime, the same for every step of a frame.
bool rdNewFrame(ivec2 grid) {
	return S_CLOCK >= grid.x || rdState(S_CLOCK, grid).x != iTime;
}

// Whether the state row is blank too - so the interface starts afresh.
bool rdFresh(ivec2 grid) {
	return rdState(S_FINGERS, grid).w < 1.5;
}

// Whether the simulation has started: a mark in the state row, rather than
// iFrame == 0 - the app's frame count goes back to 0 whenever its surface
// is made again (the screen timing out, the app coming back), though the
// buffers carry on; so only buffers that really are blank (new, or cleared
// by Restart) start the initial pattern again. The mark is 2, not 1: the
// app clears new buffers to black with an alpha of 1. And with its
// grid not blank: the app keeps the state row across a resize or a
// rotation (`///stateRow:top`) and tiles the old grid over the new one
// (`///resize:tile`), so both carry on; where it can't keep the grid
// (an older app, 8-bit buffers) it comes back blank, and starts again
// while the interface carries on (see rdFresh(), rdNextSize()).
bool rdStarted(ivec2 grid) {
	if (rdFresh(grid)) {
		return false;
	}
	if (S_SIZE >= grid.x) {
		return true;
	}
	// Not the size: the app tiles a kept grid over a new one
	// (`///resize:tile`), so a rotated one carries on.
	return rdState(S_SIZE, grid).z > 0.5;
}

// Chemical i (0 = a) at cell q, as the last step left it.
float rdChem(int i, ivec2 q) {
	return i < 4 ? texelFetch(iChannel0, q, 0)[i] : texelFetch(iChannel1, q, 0)[i - 4];
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	ivec2 size = textureSize(iChannel0, 0);
	vec2 screen = vec2(size);
	// The top row holds the interface's state (UI_COMMON's S_*); the
	// grid's below it.
	ivec2 grid = ivec2(size.x, size.y - 1);
	vec4 uiGesture, uiTouch, uiMode, uiDrag, uiFingers;
	bool starting = !rdStarted(grid);
	bool fresh = rdFresh(grid);
	if (fresh) {
		uiGesture = rdStartState(S_GESTURE);
		uiTouch = rdStartState(S_TOUCH);
		uiMode = rdStartState(S_MODE);
		uiDrag = rdStartState(S_DRAG);
		uiFingers = rdStartState(S_FINGERS);
	} else {
		rdNextState(grid, screen, uiGesture, uiTouch, uiMode, uiDrag, uiFingers);
	}
	if (p.y == grid.y) {
		fragColor = vec4(0.0);
		return;
	}
	if (starting) {
		// The start state.
		float a = 0.0;
		float b = 0.0;
		float c = 0.0;
		float d = 0.0;
		float e = 0.0;
		rdStart(p, a, b, c, d, e);
		fragColor = vec4(e, 0.0, 0.0, 0.0);
		return;
	}

	// Cells needed:
	vec4 cell0_m1_m1 = texelFetch(iChannel0, rdCell(p + ivec2(-1, -1), grid), 0);
	vec4 cell0_m1_0 = texelFetch(iChannel0, rdCell(p + ivec2(-1, 0), grid), 0);
	vec4 cell0_m1_1 = texelFetch(iChannel0, rdCell(p + ivec2(-1, 1), grid), 0);
	vec4 cell0_0_m1 = texelFetch(iChannel0, rdCell(p + ivec2(0, -1), grid), 0);
	vec4 cell0_0_0 = texelFetch(iChannel0, p, 0);
	vec4 cell1_0_0 = texelFetch(iChannel1, p, 0);
	vec4 cell0_0_1 = texelFetch(iChannel0, rdCell(p + ivec2(0, 1), grid), 0);
	vec4 cell0_1_m1 = texelFetch(iChannel0, rdCell(p + ivec2(1, -1), grid), 0);
	vec4 cell0_1_0 = texelFetch(iChannel0, rdCell(p + ivec2(1, 0), grid), 0);
	vec4 cell0_1_1 = texelFetch(iChannel0, rdCell(p + ivec2(1, 1), grid), 0);
	float a = cell0_0_0.x;
	float b = cell0_0_0.y;
	float c = cell0_0_0.z;
	float d = cell0_0_0.w;
	float e = cell1_0_0.x;
	float a_sw = cell0_m1_m1.x;
	float b_sw = cell0_m1_m1.y;
	float c_sw = cell0_m1_m1.z;
	float d_sw = cell0_m1_m1.w;
	float a_w = cell0_m1_0.x;
	float b_w = cell0_m1_0.y;
	float c_w = cell0_m1_0.z;
	float d_w = cell0_m1_0.w;
	float a_nw = cell0_m1_1.x;
	float b_nw = cell0_m1_1.y;
	float c_nw = cell0_m1_1.z;
	float d_nw = cell0_m1_1.w;
	float a_s = cell0_0_m1.x;
	float b_s = cell0_0_m1.y;
	float c_s = cell0_0_m1.z;
	float d_s = cell0_0_m1.w;
	float a_n = cell0_0_1.x;
	float b_n = cell0_0_1.y;
	float c_n = cell0_0_1.z;
	float d_n = cell0_0_1.w;
	float a_se = cell0_1_m1.x;
	float b_se = cell0_1_m1.y;
	float c_se = cell0_1_m1.z;
	float d_se = cell0_1_m1.w;
	float a_e = cell0_1_0.x;
	float b_e = cell0_1_0.y;
	float c_e = cell0_1_0.z;
	float d_e = cell0_1_0.w;
	float a_ne = cell0_1_1.x;
	float b_ne = cell0_1_1.y;
	float c_ne = cell0_1_1.z;
	float d_ne = cell0_1_1.w;

	// Keywords needed:
	float laplacian_a = (-20.0 * a + a_nw + a_ne + a_sw + a_se + 4.0 * (a_n + a_w + a_e + a_s)) / (6.0 * dx * dx);
	float laplacian_b = (-20.0 * b + b_nw + b_ne + b_sw + b_se + 4.0 * (b_n + b_w + b_e + b_s)) / (6.0 * dx * dx);
	float laplacian_c = (-20.0 * c + c_nw + c_ne + c_sw + c_se + 4.0 * (c_n + c_w + c_e + c_s)) / (6.0 * dx * dx);
	float laplacian_d = (-20.0 * d + d_nw + d_ne + d_sw + d_se + 4.0 * (d_n + d_w + d_e + d_s)) / (6.0 * dx * dx);
	if (LAPLACIAN_SCALE > 1.0) {
		float variance;
		vec4 blur0;
		rdWideBlur(p, grid, blur0, variance);
		float wide = 2.0 * LAPLACIAN_SCALE * LAPLACIAN_SCALE / (variance * dx * dx);
		laplacian_a = wide * (blur0.x - a);
		laplacian_b = wide * (blur0.y - b);
		laplacian_c = wide * (blur0.z - c);
		laplacian_d = wide * (blur0.w - d);
	} else {
		laplacian_a *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;
		laplacian_b *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;
		laplacian_c *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;
		laplacian_d *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;
	}
	float delta_a = 0.0;
	float delta_b = 0.0;
	float delta_c = 0.0;
	float delta_d = 0.0;
	float delta_e = 0.0;

	// The formula:
	delta_a = (D_a + c*c_to_Da + d*wave_to_Da) * laplacian_a - a*b*b + (F + c_to_F*c + d*wave_to_F)*(1.0-a) + c*c_to_a + d*wave_to_a;
	delta_b = (D_b  + c*c_to_Db + d*wave_to_Db) * laplacian_b + a*b*b - ((F + c_to_F*c + d*wave_to_F)+(k_ + c*c_to_k + d*wave_to_k))*b + c*c_to_b + d*wave_to_b;
	delta_c = -c*cDamp + a*a_to_c + b*b_to_c + d*wave_to_c + D_a_to_c * laplacian_a + D_b_to_c * laplacian_b + D_c * laplacian_c;
	c = clamp( rdPow( float( fabs(c)/c_pow_norm ), float(c_pow) )*c_pow_norm, 0.0, 1.0 );
	d = sign(d) * rdPow( float( fabs(d) * wave_damp), float(wave_power) ) + a * a_wave + b * b_wave + c * c_wave;
	delta_e = laplacian_d * wave_diffusion;
	delta_d = e + delta_e;

	float before_e = e;
	// Forward Euler:
	e += timestep * delta_e;

	// Painting - see paint_code() in tools/ready/vti2enki.py.
	// Over the terrain one finger paints (two fly the camera).
	bool touching = (camInterface(grid) || uiGesture.x < 1.5) && uiGesture.x > 0.0 && uiGesture.x < 3.0 && iTime - uiGesture.y >= PAINT_DELAY && uiMode.z < 0.5;
	bool erasing = touching && uiGesture.x >= 2.0;
	bool painting = touching && !erasing;
	int view = int(uiGesture.z + 0.5);
	if (!camInterface(grid)) {
		view = 0;
	}
	int shown = int(uiGesture.w + 0.5);
	// The brush button's values, once it's been dragged (UI_COMMON).
	vec4 brush = rdState(S_BRUSH, grid);
	vec4 brushOn = rdState(S_BRUSH_ON, grid);
	for (int i = 0; i < pointerCount; ++i) {
		if (!touching) {
			break;
		}
		vec2 at = pointers[i].xy;
		if (!camInterface(grid)) {
			at = camGround(at, fragCoord, grid, screen);
		}
		if (view == 1 && rdOnButton(at, screen)) {
			continue;
		}
		int only = -1; // a chemical alone, or -1 for the colour view's
		if (view == 1) {
			int hit = rdTileAt(at, screen);
			if (hit < 0) {
				continue;
			}
			vec4 tile = rdTile(hit, screen);
			at = (at - tile.xy) / tile.zw * vec2(grid);
			only = hit < CHEMICALS ? hit : -1;
		} else if (view == 2 && shown < CHEMICALS) {
			only = shown;
		}
		float away = distance(at, fragCoord) / (rdBrushSize(grid) * float(min(grid.x, grid.y)));
		if (erasing && away < ERASE_SIZE) {
			if (only < 0) {
			}
			if (only == 4) e = MASK_LOW_e;
		}
		if (painting && away < 1.0) {
			if (only < 0) {
			}
			if (only == 4) e = MASK_HIGH_e;
		}
	}
	// Kept finite - see rdSane().
	e = rdSane(e, before_e);
	fragColor = vec4(e, 0.0, 0.0, 0.0);
}
//// Buffer C
// Step 2 of 2 a frame: chemicals a, b, c, d.
///float32
///stateRow:top
///resize:tile
uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;

// The wide Laplacian (LAPLACIAN_SCALE above 1) is a Gaussian's: the blur's
// difference from the cell, over the blur's variance - which works out the
// Laplacian of what's smooth at the blur's scale. The blur widens with the
// scale, so it stays as stable as the 3x3 one, at the same timestep.
#define LAPLACIAN_SIGMA 0.7
#define LAPLACIAN_REACH 12
void rdWideBlur(ivec2 p, ivec2 grid, out vec4 blur0, out float variance) {
	float sigma = LAPLACIAN_SCALE * LAPLACIAN_SIGMA;
	int reach = min(int(ceil(2.5 * sigma)), LAPLACIAN_REACH);
	blur0 = vec4(0.0);
	float weights = 0.0;
	variance = 0.0;
	for (int y = -reach; y <= reach; ++y) {
		for (int x = -reach; x <= reach; ++x) {
			float w = exp(-0.5 * float(x * x + y * y) / (sigma * sigma));
			ivec2 q = rdCell(p + ivec2(x, y), grid);
			blur0 += w * texelFetch(iChannel0, q, 0);
			weights += w;
			variance += w * float(x * x);
		}
	}
	blur0 /= weights;
	variance /= weights;
}

vec4 rdState(int texel, ivec2 grid) {
	return texelFetch(iChannel0, ivec2(texel, grid.y), 0);
}

// Chemical i's range as the views show it (composite colour, its tile):
// the range its values have had lately (see rdNextRange()), so one that
// goes below 0 - a wave - or never near 1 still shows its whole swing;
// its mask range until that's been measured (or with too narrow a grid to
// hold it). Painting keeps to the mask range.
vec2 rdShowRange(int i, ivec2 grid) {
	if (S_RANGE + i >= grid.x) {
		return maskRange(i);
	}
	vec4 r = rdState(S_RANGE + i, grid);
	if (r.z < 0.5) {
		return maskRange(i);
	}
	// Not so narrow that a chemical that's all one value shows its
	// rounding as a pattern.
	float least = 1e-5 + 1e-3 * max(abs(r.x), abs(r.y));
	return vec2(r.x, max(r.y, r.x + least));
}

// How big the brush is now, of the grid's shorter side.
float rdBrushSize(ivec2 grid) {
	return BRUSH_SIZE * (S_PAINT < grid.x ? clamp(rdState(S_PAINT, grid).x, 0.25, 2.0) : 1.0);
}

// Whether this is a frame's first step: what drifts slowly (wandering,
// the ranges) moves once a frame, however many steps a frame takes
// (`///steps:`) - by iTime, the same for every step of a frame.
bool rdNewFrame(ivec2 grid) {
	return S_CLOCK >= grid.x || rdState(S_CLOCK, grid).x != iTime;
}

// Whether the state row is blank too - so the interface starts afresh.
bool rdFresh(ivec2 grid) {
	return rdState(S_FINGERS, grid).w < 1.5;
}

// Whether the simulation has started: a mark in the state row, rather than
// iFrame == 0 - the app's frame count goes back to 0 whenever its surface
// is made again (the screen timing out, the app coming back), though the
// buffers carry on; so only buffers that really are blank (new, or cleared
// by Restart) start the initial pattern again. The mark is 2, not 1: the
// app clears new buffers to black with an alpha of 1. And with its
// grid not blank: the app keeps the state row across a resize or a
// rotation (`///stateRow:top`) and tiles the old grid over the new one
// (`///resize:tile`), so both carry on; where it can't keep the grid
// (an older app, 8-bit buffers) it comes back blank, and starts again
// while the interface carries on (see rdFresh(), rdNextSize()).
bool rdStarted(ivec2 grid) {
	if (rdFresh(grid)) {
		return false;
	}
	if (S_SIZE >= grid.x) {
		return true;
	}
	// Not the size: the app tiles a kept grid over a new one
	// (`///resize:tile`), so a rotated one carries on.
	return rdState(S_SIZE, grid).z > 0.5;
}

// Chemical i (0 = a) at cell q, as the last step left it.
float rdChem(int i, ivec2 q) {
	return i < 4 ? texelFetch(iChannel0, q, 0)[i] : texelFetch(iChannel1, q, 0)[i - 4];
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	ivec2 size = textureSize(iChannel0, 0);
	vec2 screen = vec2(size);
	// The top row holds the interface's state (UI_COMMON's S_*); the
	// grid's below it.
	ivec2 grid = ivec2(size.x, size.y - 1);
	vec4 uiGesture, uiTouch, uiMode, uiDrag, uiFingers;
	// This frame's, from its first step.
	uiGesture = rdState(S_GESTURE, grid);
	uiTouch = rdState(S_TOUCH, grid);
	uiMode = rdState(S_MODE, grid);
	if (p.y == grid.y) {
		fragColor = texelFetch(iChannel0, p, 0);
		return;
	}
	// Cells needed:
	vec4 cell0_m1_m1 = texelFetch(iChannel0, rdCell(p + ivec2(-1, -1), grid), 0);
	vec4 cell0_m1_0 = texelFetch(iChannel0, rdCell(p + ivec2(-1, 0), grid), 0);
	vec4 cell0_m1_1 = texelFetch(iChannel0, rdCell(p + ivec2(-1, 1), grid), 0);
	vec4 cell0_0_m1 = texelFetch(iChannel0, rdCell(p + ivec2(0, -1), grid), 0);
	vec4 cell0_0_0 = texelFetch(iChannel0, p, 0);
	vec4 cell1_0_0 = texelFetch(iChannel1, p, 0);
	vec4 cell0_0_1 = texelFetch(iChannel0, rdCell(p + ivec2(0, 1), grid), 0);
	vec4 cell0_1_m1 = texelFetch(iChannel0, rdCell(p + ivec2(1, -1), grid), 0);
	vec4 cell0_1_0 = texelFetch(iChannel0, rdCell(p + ivec2(1, 0), grid), 0);
	vec4 cell0_1_1 = texelFetch(iChannel0, rdCell(p + ivec2(1, 1), grid), 0);
	float a = cell0_0_0.x;
	float b = cell0_0_0.y;
	float c = cell0_0_0.z;
	float d = cell0_0_0.w;
	float e = cell1_0_0.x;
	float a_sw = cell0_m1_m1.x;
	float b_sw = cell0_m1_m1.y;
	float c_sw = cell0_m1_m1.z;
	float d_sw = cell0_m1_m1.w;
	float a_w = cell0_m1_0.x;
	float b_w = cell0_m1_0.y;
	float c_w = cell0_m1_0.z;
	float d_w = cell0_m1_0.w;
	float a_nw = cell0_m1_1.x;
	float b_nw = cell0_m1_1.y;
	float c_nw = cell0_m1_1.z;
	float d_nw = cell0_m1_1.w;
	float a_s = cell0_0_m1.x;
	float b_s = cell0_0_m1.y;
	float c_s = cell0_0_m1.z;
	float d_s = cell0_0_m1.w;
	float a_n = cell0_0_1.x;
	float b_n = cell0_0_1.y;
	float c_n = cell0_0_1.z;
	float d_n = cell0_0_1.w;
	float a_se = cell0_1_m1.x;
	float b_se = cell0_1_m1.y;
	float c_se = cell0_1_m1.z;
	float d_se = cell0_1_m1.w;
	float a_e = cell0_1_0.x;
	float b_e = cell0_1_0.y;
	float c_e = cell0_1_0.z;
	float d_e = cell0_1_0.w;
	float a_ne = cell0_1_1.x;
	float b_ne = cell0_1_1.y;
	float c_ne = cell0_1_1.z;
	float d_ne = cell0_1_1.w;

	// Keywords needed:
	float laplacian_a = (-20.0 * a + a_nw + a_ne + a_sw + a_se + 4.0 * (a_n + a_w + a_e + a_s)) / (6.0 * dx * dx);
	float laplacian_b = (-20.0 * b + b_nw + b_ne + b_sw + b_se + 4.0 * (b_n + b_w + b_e + b_s)) / (6.0 * dx * dx);
	float laplacian_c = (-20.0 * c + c_nw + c_ne + c_sw + c_se + 4.0 * (c_n + c_w + c_e + c_s)) / (6.0 * dx * dx);
	float laplacian_d = (-20.0 * d + d_nw + d_ne + d_sw + d_se + 4.0 * (d_n + d_w + d_e + d_s)) / (6.0 * dx * dx);
	if (LAPLACIAN_SCALE > 1.0) {
		float variance;
		vec4 blur0;
		rdWideBlur(p, grid, blur0, variance);
		float wide = 2.0 * LAPLACIAN_SCALE * LAPLACIAN_SCALE / (variance * dx * dx);
		laplacian_a = wide * (blur0.x - a);
		laplacian_b = wide * (blur0.y - b);
		laplacian_c = wide * (blur0.z - c);
		laplacian_d = wide * (blur0.w - d);
	} else {
		laplacian_a *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;
		laplacian_b *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;
		laplacian_c *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;
		laplacian_d *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;
	}
	float delta_a = 0.0;
	float delta_b = 0.0;
	float delta_c = 0.0;
	float delta_d = 0.0;
	float delta_e = 0.0;

	// The formula:
	delta_a = (D_a + c*c_to_Da + d*wave_to_Da) * laplacian_a - a*b*b + (F + c_to_F*c + d*wave_to_F)*(1.0-a) + c*c_to_a + d*wave_to_a;
	delta_b = (D_b  + c*c_to_Db + d*wave_to_Db) * laplacian_b + a*b*b - ((F + c_to_F*c + d*wave_to_F)+(k_ + c*c_to_k + d*wave_to_k))*b + c*c_to_b + d*wave_to_b;
	delta_c = -c*cDamp + a*a_to_c + b*b_to_c + d*wave_to_c + D_a_to_c * laplacian_a + D_b_to_c * laplacian_b + D_c * laplacian_c;
	c = clamp( rdPow( float( fabs(c)/c_pow_norm ), float(c_pow) )*c_pow_norm, 0.0, 1.0 );
	d = sign(d) * rdPow( float( fabs(d) * wave_damp), float(wave_power) ) + a * a_wave + b * b_wave + c * c_wave;
	delta_e = laplacian_d * wave_diffusion;
	delta_d = e + delta_e;

	float before_a = a;
	float before_b = b;
	float before_c = c;
	float before_d = d;
	// Forward Euler:
	a += timestep * delta_a;
	b += timestep * delta_b;
	c += timestep * delta_c;
	d += timestep * delta_d;

	// Painting - see paint_code() in tools/ready/vti2enki.py.
	// Over the terrain one finger paints (two fly the camera).
	bool touching = (camInterface(grid) || uiGesture.x < 1.5) && uiGesture.x > 0.0 && uiGesture.x < 3.0 && iTime - uiGesture.y >= PAINT_DELAY && uiMode.z < 0.5;
	bool erasing = touching && uiGesture.x >= 2.0;
	bool painting = false;
	int view = int(uiGesture.z + 0.5);
	if (!camInterface(grid)) {
		view = 0;
	}
	int shown = int(uiGesture.w + 0.5);
	// The brush button's values, once it's been dragged (UI_COMMON).
	vec4 brush = rdState(S_BRUSH, grid);
	vec4 brushOn = rdState(S_BRUSH_ON, grid);
	for (int i = 0; i < pointerCount; ++i) {
		if (!touching) {
			break;
		}
		vec2 at = pointers[i].xy;
		if (!camInterface(grid)) {
			at = camGround(at, fragCoord, grid, screen);
		}
		if (view == 1 && rdOnButton(at, screen)) {
			continue;
		}
		int only = -1; // a chemical alone, or -1 for the colour view's
		if (view == 1) {
			int hit = rdTileAt(at, screen);
			if (hit < 0) {
				continue;
			}
			vec4 tile = rdTile(hit, screen);
			at = (at - tile.xy) / tile.zw * vec2(grid);
			only = hit < CHEMICALS ? hit : -1;
		} else if (view == 2 && shown < CHEMICALS) {
			only = shown;
		}
		float away = distance(at, fragCoord) / (rdBrushSize(grid) * float(min(grid.x, grid.y)));
		if (erasing && away < ERASE_SIZE) {
			if (only < 0) {
				a = ERASE_a;
				b = ERASE_b;
			}
			if (only == 0) a = (brushOn.y > 0.5 ? brush.z : ERASE_a);
			if (only == 1) b = (brushOn.y > 0.5 ? brush.w : ERASE_b);
			if (only == 2) c = MASK_LOW_c;
			if (only == 3) d = MASK_LOW_d;
		}
	}
	// Kept finite - see rdSane().
	a = rdSane(a, before_a);
	b = rdSane(b, before_b);
	c = rdSane(c, before_c);
	d = rdSane(d, before_d);
	fragColor = vec4(a, b, c, d);
}
//// Buffer D
// Step 2 of 2 a frame: chemicals e.
///float32
///stateRow:top
///resize:tile
uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;

// The wide Laplacian (LAPLACIAN_SCALE above 1) is a Gaussian's: the blur's
// difference from the cell, over the blur's variance - which works out the
// Laplacian of what's smooth at the blur's scale. The blur widens with the
// scale, so it stays as stable as the 3x3 one, at the same timestep.
#define LAPLACIAN_SIGMA 0.7
#define LAPLACIAN_REACH 12
void rdWideBlur(ivec2 p, ivec2 grid, out vec4 blur0, out float variance) {
	float sigma = LAPLACIAN_SCALE * LAPLACIAN_SIGMA;
	int reach = min(int(ceil(2.5 * sigma)), LAPLACIAN_REACH);
	blur0 = vec4(0.0);
	float weights = 0.0;
	variance = 0.0;
	for (int y = -reach; y <= reach; ++y) {
		for (int x = -reach; x <= reach; ++x) {
			float w = exp(-0.5 * float(x * x + y * y) / (sigma * sigma));
			ivec2 q = rdCell(p + ivec2(x, y), grid);
			blur0 += w * texelFetch(iChannel0, q, 0);
			weights += w;
			variance += w * float(x * x);
		}
	}
	blur0 /= weights;
	variance /= weights;
}

vec4 rdState(int texel, ivec2 grid) {
	return texelFetch(iChannel0, ivec2(texel, grid.y), 0);
}

// Chemical i's range as the views show it (composite colour, its tile):
// the range its values have had lately (see rdNextRange()), so one that
// goes below 0 - a wave - or never near 1 still shows its whole swing;
// its mask range until that's been measured (or with too narrow a grid to
// hold it). Painting keeps to the mask range.
vec2 rdShowRange(int i, ivec2 grid) {
	if (S_RANGE + i >= grid.x) {
		return maskRange(i);
	}
	vec4 r = rdState(S_RANGE + i, grid);
	if (r.z < 0.5) {
		return maskRange(i);
	}
	// Not so narrow that a chemical that's all one value shows its
	// rounding as a pattern.
	float least = 1e-5 + 1e-3 * max(abs(r.x), abs(r.y));
	return vec2(r.x, max(r.y, r.x + least));
}

// How big the brush is now, of the grid's shorter side.
float rdBrushSize(ivec2 grid) {
	return BRUSH_SIZE * (S_PAINT < grid.x ? clamp(rdState(S_PAINT, grid).x, 0.25, 2.0) : 1.0);
}

// Whether this is a frame's first step: what drifts slowly (wandering,
// the ranges) moves once a frame, however many steps a frame takes
// (`///steps:`) - by iTime, the same for every step of a frame.
bool rdNewFrame(ivec2 grid) {
	return S_CLOCK >= grid.x || rdState(S_CLOCK, grid).x != iTime;
}

// Whether the state row is blank too - so the interface starts afresh.
bool rdFresh(ivec2 grid) {
	return rdState(S_FINGERS, grid).w < 1.5;
}

// Whether the simulation has started: a mark in the state row, rather than
// iFrame == 0 - the app's frame count goes back to 0 whenever its surface
// is made again (the screen timing out, the app coming back), though the
// buffers carry on; so only buffers that really are blank (new, or cleared
// by Restart) start the initial pattern again. The mark is 2, not 1: the
// app clears new buffers to black with an alpha of 1. And with its
// grid not blank: the app keeps the state row across a resize or a
// rotation (`///stateRow:top`) and tiles the old grid over the new one
// (`///resize:tile`), so both carry on; where it can't keep the grid
// (an older app, 8-bit buffers) it comes back blank, and starts again
// while the interface carries on (see rdFresh(), rdNextSize()).
bool rdStarted(ivec2 grid) {
	if (rdFresh(grid)) {
		return false;
	}
	if (S_SIZE >= grid.x) {
		return true;
	}
	// Not the size: the app tiles a kept grid over a new one
	// (`///resize:tile`), so a rotated one carries on.
	return rdState(S_SIZE, grid).z > 0.5;
}

// Chemical i (0 = a) at cell q, as the last step left it.
float rdChem(int i, ivec2 q) {
	return i < 4 ? texelFetch(iChannel0, q, 0)[i] : texelFetch(iChannel1, q, 0)[i - 4];
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	ivec2 size = textureSize(iChannel0, 0);
	vec2 screen = vec2(size);
	// The top row holds the interface's state (UI_COMMON's S_*); the
	// grid's below it.
	ivec2 grid = ivec2(size.x, size.y - 1);
	vec4 uiGesture, uiTouch, uiMode, uiDrag, uiFingers;
	// This frame's, from its first step.
	uiGesture = rdState(S_GESTURE, grid);
	uiTouch = rdState(S_TOUCH, grid);
	uiMode = rdState(S_MODE, grid);
	if (p.y == grid.y) {
		fragColor = vec4(0.0);
		return;
	}
	// Cells needed:
	vec4 cell0_m1_m1 = texelFetch(iChannel0, rdCell(p + ivec2(-1, -1), grid), 0);
	vec4 cell0_m1_0 = texelFetch(iChannel0, rdCell(p + ivec2(-1, 0), grid), 0);
	vec4 cell0_m1_1 = texelFetch(iChannel0, rdCell(p + ivec2(-1, 1), grid), 0);
	vec4 cell0_0_m1 = texelFetch(iChannel0, rdCell(p + ivec2(0, -1), grid), 0);
	vec4 cell0_0_0 = texelFetch(iChannel0, p, 0);
	vec4 cell1_0_0 = texelFetch(iChannel1, p, 0);
	vec4 cell0_0_1 = texelFetch(iChannel0, rdCell(p + ivec2(0, 1), grid), 0);
	vec4 cell0_1_m1 = texelFetch(iChannel0, rdCell(p + ivec2(1, -1), grid), 0);
	vec4 cell0_1_0 = texelFetch(iChannel0, rdCell(p + ivec2(1, 0), grid), 0);
	vec4 cell0_1_1 = texelFetch(iChannel0, rdCell(p + ivec2(1, 1), grid), 0);
	float a = cell0_0_0.x;
	float b = cell0_0_0.y;
	float c = cell0_0_0.z;
	float d = cell0_0_0.w;
	float e = cell1_0_0.x;
	float a_sw = cell0_m1_m1.x;
	float b_sw = cell0_m1_m1.y;
	float c_sw = cell0_m1_m1.z;
	float d_sw = cell0_m1_m1.w;
	float a_w = cell0_m1_0.x;
	float b_w = cell0_m1_0.y;
	float c_w = cell0_m1_0.z;
	float d_w = cell0_m1_0.w;
	float a_nw = cell0_m1_1.x;
	float b_nw = cell0_m1_1.y;
	float c_nw = cell0_m1_1.z;
	float d_nw = cell0_m1_1.w;
	float a_s = cell0_0_m1.x;
	float b_s = cell0_0_m1.y;
	float c_s = cell0_0_m1.z;
	float d_s = cell0_0_m1.w;
	float a_n = cell0_0_1.x;
	float b_n = cell0_0_1.y;
	float c_n = cell0_0_1.z;
	float d_n = cell0_0_1.w;
	float a_se = cell0_1_m1.x;
	float b_se = cell0_1_m1.y;
	float c_se = cell0_1_m1.z;
	float d_se = cell0_1_m1.w;
	float a_e = cell0_1_0.x;
	float b_e = cell0_1_0.y;
	float c_e = cell0_1_0.z;
	float d_e = cell0_1_0.w;
	float a_ne = cell0_1_1.x;
	float b_ne = cell0_1_1.y;
	float c_ne = cell0_1_1.z;
	float d_ne = cell0_1_1.w;

	// Keywords needed:
	float laplacian_a = (-20.0 * a + a_nw + a_ne + a_sw + a_se + 4.0 * (a_n + a_w + a_e + a_s)) / (6.0 * dx * dx);
	float laplacian_b = (-20.0 * b + b_nw + b_ne + b_sw + b_se + 4.0 * (b_n + b_w + b_e + b_s)) / (6.0 * dx * dx);
	float laplacian_c = (-20.0 * c + c_nw + c_ne + c_sw + c_se + 4.0 * (c_n + c_w + c_e + c_s)) / (6.0 * dx * dx);
	float laplacian_d = (-20.0 * d + d_nw + d_ne + d_sw + d_se + 4.0 * (d_n + d_w + d_e + d_s)) / (6.0 * dx * dx);
	if (LAPLACIAN_SCALE > 1.0) {
		float variance;
		vec4 blur0;
		rdWideBlur(p, grid, blur0, variance);
		float wide = 2.0 * LAPLACIAN_SCALE * LAPLACIAN_SCALE / (variance * dx * dx);
		laplacian_a = wide * (blur0.x - a);
		laplacian_b = wide * (blur0.y - b);
		laplacian_c = wide * (blur0.z - c);
		laplacian_d = wide * (blur0.w - d);
	} else {
		laplacian_a *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;
		laplacian_b *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;
		laplacian_c *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;
		laplacian_d *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;
	}
	float delta_a = 0.0;
	float delta_b = 0.0;
	float delta_c = 0.0;
	float delta_d = 0.0;
	float delta_e = 0.0;

	// The formula:
	delta_a = (D_a + c*c_to_Da + d*wave_to_Da) * laplacian_a - a*b*b + (F + c_to_F*c + d*wave_to_F)*(1.0-a) + c*c_to_a + d*wave_to_a;
	delta_b = (D_b  + c*c_to_Db + d*wave_to_Db) * laplacian_b + a*b*b - ((F + c_to_F*c + d*wave_to_F)+(k_ + c*c_to_k + d*wave_to_k))*b + c*c_to_b + d*wave_to_b;
	delta_c = -c*cDamp + a*a_to_c + b*b_to_c + d*wave_to_c + D_a_to_c * laplacian_a + D_b_to_c * laplacian_b + D_c * laplacian_c;
	c = clamp( rdPow( float( fabs(c)/c_pow_norm ), float(c_pow) )*c_pow_norm, 0.0, 1.0 );
	d = sign(d) * rdPow( float( fabs(d) * wave_damp), float(wave_power) ) + a * a_wave + b * b_wave + c * c_wave;
	delta_e = laplacian_d * wave_diffusion;
	delta_d = e + delta_e;

	float before_e = e;
	// Forward Euler:
	e += timestep * delta_e;

	// Painting - see paint_code() in tools/ready/vti2enki.py.
	// Over the terrain one finger paints (two fly the camera).
	bool touching = (camInterface(grid) || uiGesture.x < 1.5) && uiGesture.x > 0.0 && uiGesture.x < 3.0 && iTime - uiGesture.y >= PAINT_DELAY && uiMode.z < 0.5;
	bool erasing = touching && uiGesture.x >= 2.0;
	bool painting = false;
	int view = int(uiGesture.z + 0.5);
	if (!camInterface(grid)) {
		view = 0;
	}
	int shown = int(uiGesture.w + 0.5);
	// The brush button's values, once it's been dragged (UI_COMMON).
	vec4 brush = rdState(S_BRUSH, grid);
	vec4 brushOn = rdState(S_BRUSH_ON, grid);
	for (int i = 0; i < pointerCount; ++i) {
		if (!touching) {
			break;
		}
		vec2 at = pointers[i].xy;
		if (!camInterface(grid)) {
			at = camGround(at, fragCoord, grid, screen);
		}
		if (view == 1 && rdOnButton(at, screen)) {
			continue;
		}
		int only = -1; // a chemical alone, or -1 for the colour view's
		if (view == 1) {
			int hit = rdTileAt(at, screen);
			if (hit < 0) {
				continue;
			}
			vec4 tile = rdTile(hit, screen);
			at = (at - tile.xy) / tile.zw * vec2(grid);
			only = hit < CHEMICALS ? hit : -1;
		} else if (view == 2 && shown < CHEMICALS) {
			only = shown;
		}
		float away = distance(at, fragCoord) / (rdBrushSize(grid) * float(min(grid.x, grid.y)));
		if (erasing && away < ERASE_SIZE) {
			if (only < 0) {
			}
			if (only == 4) e = MASK_LOW_e;
		}
	}
	// Kept finite - see rdSane().
	e = rdSane(e, before_e);
	fragColor = vec4(e, 0.0, 0.0, 0.0);
}
