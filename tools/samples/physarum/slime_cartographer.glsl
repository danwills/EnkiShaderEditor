// SLIME CARTOGRAPHER - a Physarum (slime mould) colony that maps its world
//
// Every agent sniffs the trail ahead-left, ahead and ahead-right of
// itself, turns towards the strongest scent, steps forward and leaves
// more trail behind. The trail spreads and fades. Out of that, veins and
// networks grow, merge and reorganise. Up to three species can share the
// ground: each follows its own trail and shies away from the others'.
//
// FINGERS
//   tap                 show or hide the menu
//   hold or drag        drop food - the slime grows towards it
//   4 fingers           repel - push the slime away
// THE MENU - a bar for each setting: touch one to set it there, and drag
// along it. The colony on the left, top down:
//   sensor angle     - how wide agents look
//   sensor distance  - how far ahead they sniff
//   turning          - how sharply they turn
//   speed            - how far they move a step
//   trail life       - how long trails last
//   species          - how many (1-3): it changes when you let go, and
//                      every agent is dealt a species afresh
//   avoidance        - (more than one species) how much each shies away
//                      from the others' trails
//   scale            - the whole pattern, bigger or finer, about the
//                      middle of the screen (1 mid-way).
//                      Above 1 the agents live on a coarser grid, as many
//                      of them as at 1, while their trail - its sensing,
//                      deposits and spread - stays at full resolution, so
//                      it's the same colony with a finer trail. Below 1
//                      every distance shrinks instead
// and how it looks on the right:
//   glow             - how much of the trail you see
//   gamma            - brighter or darker mid-tones (1 mid-way)
//   trail opacity    - how strongly the trail shows
//   agent opacity    - how strongly the agents themselves show
//   colours          - the colour scheme (named on the bar), one for
//                      each species, drawn over each other in turn (the
//                      background is the first one's). Tap for the next
//   soften           - eases thin, just-laid trails into the trail round
//                      them, leaving broad veins and the gaps sharp (only
//                      how it looks)
//   zoom             - magnifies the colony about the middle of the
//                      screen: it runs on a smaller patch (faster), its
//                      trail smoothed as it's enlarged
// The colony measures itself by the screen's height, so at a higher
// quality setting it's the same colony with a finer trail (and slower).
// PRESETS - tap the top row (< PRESET NAME >) on its right half for the
// next preset, its left half for the one before. The colony carries on
// and grows into the new settings (the right-hand column and scale stay
// as they are). Each preset also sets how far the trail spreads (and how
// widely), how wobbly the turning is, and how fast new agents appear.
// Lace is the defines in Buffer A; the rest were found by searching for
// patterns like Softology's Physarum pictures
// (softologyblog.wordpress.com/2019/04/11/physarum-simulations), with
// tools/samples/physarum/search.py.
// Rewind starts a fresh colony with the PRESET define's settings.
//
// Buffer A: the agents (and settings), Buffer B: the trail.
// Needs float buffers. Runs best at 1/4 quality or below.

#define REPEL_TINT vec3(0.6, 0.05, 0.15)
// How far round (trail pixels, at scale 1) soften compares the trail with.
#define SOFTEN_RADIUS 1.5
// The trail fade a fresh colony starts with (keep in step with Buffer A).
#define FADE 0.03

uniform sampler2D iChannel0;///buf:B;
uniform sampler2D iChannel1;///buf:A;min:n;mag:n;

// The colour schemes: background, faint trail, strong trail, agents.
void palette(int i, out vec3 background, out vec3 low, out vec3 high, out vec3 agent) {
	if (i == 1) {
		// Grey
		background = vec3(0.0);
		low = vec3(0.38);
		high = vec3(1.0);
		agent = vec3(0.75);
	} else if (i == 2) {
		// Paper
		background = vec3(0.94, 0.92, 0.88);
		low = vec3(0.55, 0.53, 0.50);
		high = vec3(0.05, 0.05, 0.06);
		agent = vec3(0.35, 0.35, 0.40);
	} else if (i == 3) {
		// Fire
		background = vec3(0.02, 0.0, 0.0);
		low = vec3(0.65, 0.10, 0.02);
		high = vec3(1.0, 0.88, 0.45);
		agent = vec3(1.0, 0.55, 0.15);
	} else if (i == 4) {
		// Toxic
		background = vec3(0.0, 0.02, 0.0);
		low = vec3(0.10, 0.42, 0.06);
		high = vec3(0.88, 1.0, 0.35);
		agent = vec3(0.55, 1.0, 0.25);
	} else if (i == 5) {
		// Ice
		background = vec3(0.0, 0.01, 0.04);
		low = vec3(0.16, 0.36, 0.70);
		high = vec3(0.88, 0.96, 1.0);
		agent = vec3(0.55, 0.80, 1.0);
	} else if (i == 6) {
		// Neon
		background = vec3(0.03, 0.0, 0.05);
		low = vec3(0.55, 0.05, 0.55);
		high = vec3(1.0, 0.62, 0.92);
		agent = vec3(0.35, 0.95, 1.0);
	} else if (i == 7) {
		// Gold
		background = vec3(0.0, 0.02, 0.08);
		low = vec3(0.42, 0.26, 0.05);
		high = vec3(1.0, 0.82, 0.32);
		agent = vec3(0.95, 0.90, 0.60);
	} else if (i == 8) {
		// Sepia
		background = vec3(0.08, 0.05, 0.03);
		low = vec3(0.45, 0.30, 0.18);
		high = vec3(0.98, 0.90, 0.75);
		agent = vec3(0.85, 0.70, 0.50);
	} else if (i == 9) {
		// Abyss
		background = vec3(0.0);
		low = vec3(0.0, 0.28, 0.32);
		high = vec3(0.62, 1.0, 0.94);
		agent = vec3(0.30, 0.70, 1.0);
	} else if (i == 10) {
		// Rust
		background = vec3(0.94, 0.92, 0.88);
		low = vec3(0.86, 0.55, 0.42);
		high = vec3(0.55, 0.12, 0.05);
		agent = vec3(0.70, 0.25, 0.15);
	} else if (i == 11) {
		// Ink
		background = vec3(0.94, 0.92, 0.88);
		low = vec3(0.52, 0.64, 0.82);
		high = vec3(0.07, 0.15, 0.42);
		agent = vec3(0.20, 0.30, 0.65);
	} else {
		// Slime
		background = vec3(0.010, 0.012, 0.030);
		low = vec3(0.05, 0.30, 0.45);
		high = vec3(1.00, 0.85, 0.45);
		agent = vec3(0.6, 1.0, 0.8);
	}
}

vec3 ramp(float t, vec3 background, vec3 low, vec3 high) {
	vec3 col = mix(background, low, smoothstep(0.0, 0.5, t));
	return mix(col, high, smoothstep(0.45, 1.0, t));
}

// THE MENU - its letters are a tiny bitmap font stored right here as
// numbers, so drawing them is a few integer operations, and only for
// pixels inside the menu.
#define GLYPH_COUNT 31
// FONT BEGIN
// 5x5 glyphs, five rows each (top first, leftmost pixel in the
// highest bit) - small numbers, exact on any GPU:  ACDEFGHILNOPRSTUVW23<>^BMZYX1K
const int GLYPHS[GLYPH_COUNT * 5] = int[](
		0, 0, 0, 0, 0,
		14, 17, 31, 17, 17,
		15, 16, 16, 16, 15,
		30, 17, 17, 17, 30,
		31, 16, 30, 16, 31,
		31, 16, 30, 16, 16,
		15, 16, 19, 17, 15,
		17, 17, 31, 17, 17,
		31, 4, 4, 4, 31,
		16, 16, 16, 16, 31,
		17, 25, 21, 19, 17,
		14, 17, 17, 17, 14,
		30, 17, 30, 16, 16,
		30, 17, 30, 18, 17,
		15, 16, 14, 1, 30,
		31, 4, 4, 4, 4,
		17, 17, 17, 17, 14,
		17, 17, 17, 10, 4,
		17, 17, 21, 21, 10,
		30, 1, 14, 16, 31,
		30, 1, 14, 1, 30,
		2, 4, 8, 4, 2,
		8, 4, 2, 4, 8,
		4, 10, 17, 0, 0,
		30, 17, 30, 17, 30,
		17, 27, 21, 17, 17,
		31, 2, 4, 8, 31,
		17, 10, 4, 4, 4,
		17, 10, 4, 10, 17,
		4, 12, 4, 4, 14,
		17, 18, 28, 18, 17);
// FONT END
// LABELS BEGIN
// The bars' labels, in bar order (made by make_presets.py from this line):
// SCALE | TRAIL LIFE | SPEED | TURNING | SENSOR DISTANCE | SENSOR ANGLE | ZOOM | COLOURS | AGENT OPACITY | TRAIL OPACITY | GAMMA | GLOW | SPECIES | AVOIDANCE | COLOURS 2 | COLOURS 3 | SOFTEN | COLOURS 1
const int LABELS[18 * LABEL_COLUMNS] = int[](
		14, 2, 1, 9, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
		15, 13, 1, 8, 9, 0, 9, 8, 5, 4, 0, 0, 0, 0, 0,
		14, 12, 4, 4, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
		15, 16, 13, 10, 8, 10, 6, 0, 0, 0, 0, 0, 0, 0, 0,
		14, 4, 10, 14, 11, 13, 0, 3, 8, 14, 15, 1, 10, 2, 4,
		14, 4, 10, 14, 11, 13, 0, 1, 10, 6, 9, 4, 0, 0, 0,
		26, 11, 11, 25, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
		2, 11, 9, 11, 16, 13, 14, 0, 0, 0, 0, 0, 0, 0, 0,
		1, 6, 4, 10, 15, 0, 11, 12, 1, 2, 8, 15, 27, 0, 0,
		15, 13, 1, 8, 9, 0, 11, 12, 1, 2, 8, 15, 27, 0, 0,
		6, 1, 25, 25, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
		6, 9, 11, 18, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
		14, 12, 4, 2, 8, 4, 14, 0, 0, 0, 0, 0, 0, 0, 0,
		1, 17, 11, 8, 3, 1, 10, 2, 4, 0, 0, 0, 0, 0, 0,
		2, 11, 9, 11, 16, 13, 14, 0, 19, 0, 0, 0, 0, 0, 0,
		2, 11, 9, 11, 16, 13, 14, 0, 20, 0, 0, 0, 0, 0, 0,
		14, 11, 5, 15, 4, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0,
		2, 11, 9, 11, 16, 13, 14, 0, 29, 0, 0, 0, 0, 0, 0);
// LABELS END

// COLOUR NAMES BEGIN
// The colour schemes' names, as palette() numbers them (made by make_presets.py from this line):
// SLIME | GREY | PAPER | FIRE | TOXIC | ICE | NEON | GOLD | SEPIA | ABYSS | RUST | INK
#define COLOUR_COLUMNS 7
const int COLOUR_NAMES[12 * COLOUR_COLUMNS] = int[](
		14, 9, 8, 25, 4, 0, 0,
		6, 13, 4, 27, 0, 0, 0,
		12, 1, 12, 4, 13, 0, 0,
		5, 8, 13, 4, 0, 0, 0,
		15, 11, 28, 8, 2, 0, 0,
		8, 2, 4, 0, 0, 0, 0,
		10, 4, 11, 10, 0, 0, 0,
		6, 11, 9, 3, 0, 0, 0,
		14, 4, 12, 8, 1, 0, 0,
		1, 24, 27, 14, 14, 0, 0,
		13, 16, 14, 15, 0, 0, 0,
		8, 10, 30, 0, 0, 0, 0);
// COLOUR NAMES END

// PRESET NAMES BEGIN
// The presets' names (made by make_presets.py).
#define PRESETS 14
#define NAME_COLUMNS 11
const int PRESET_NAMES[PRESETS * NAME_COLUMNS] = int[](
		9, 1, 2, 4, 0, 0, 0, 0, 0, 0, 0,
		2, 4, 9, 9, 14, 0, 0, 0, 0, 0, 0,
		17, 4, 8, 10, 14, 0, 0, 0, 0, 0, 0,
		18, 4, 24, 0, 0, 0, 0, 0, 0, 0, 0,
		18, 4, 1, 17, 4, 0, 0, 0, 0, 0, 0,
		5, 8, 24, 13, 4, 14, 0, 0, 0, 0, 0,
		13, 8, 17, 4, 13, 14, 0, 0, 0, 0, 0,
		14, 15, 1, 13, 14, 0, 0, 0, 0, 0, 0,
		6, 9, 11, 18, 0, 18, 11, 13, 25, 14, 0,
		25, 1, 26, 4, 0, 0, 0, 0, 0, 0, 0,
		18, 11, 13, 25, 14, 0, 0, 0, 0, 0, 0,
		7, 1, 8, 13, 14, 0, 0, 0, 0, 0, 0,
		14, 12, 11, 15, 14, 0, 0, 0, 0, 0, 0,
		5, 8, 10, 6, 4, 13, 12, 13, 8, 10, 15);
// PRESET NAMES END

// Where `value` sits in `range`, on a log scale, 0..1.
float level(float value, vec2 range) {
	return log(value / range.x) / log(range.y / range.x);
}

// The glyph for digit d (1 to MAX_SPECIES), from the colours bars' labels.
int digitGlyph(int d) {
	int label = d == 1 ? COLOURS1_LABEL : (d == 2 ? COLOURS2_BAR : COLOURS3_BAR);
	return LABELS[label * LABEL_COLUMNS + 8];
}

// The glyph at a column of an item's label: a bar's from LABELS (a
// colours bar adds the scheme's name - after its species' number when
// there's more than one - and species adds the count), the preset row
// "<" PRESET NAME ">".
int glyphAt(int item, int column, int presetIndex, int colours, int species, int shown) {
	if (item != PRESET_ROW) {
		if (colourSpecies(item) >= 0) {
			int at = species > 1 ? 10 : 8;
			if (column >= at && column < min(at + COLOUR_COLUMNS, LABEL_COLUMNS)) {
				return COLOUR_NAMES[colours * COLOUR_COLUMNS + column - at];
			}
			if (item == COLOURS_BAR && species > 1) {
				return LABELS[COLOURS1_LABEL * LABEL_COLUMNS + column];
			}
		}
		if (item == SPECIES_BAR && column == 8) {
			return digitGlyph(shown);
		}
		return LABELS[item * LABEL_COLUMNS + column];
	}
	if (column == 0) {
		return 21;
	}
	if (column == PRESET_COLUMNS - 1) {
		return 22;
	}
	if (column >= 2 && column < 8) {
		int word[6] = int[](12, 13, 4, 14, 4, 15);
		return word[column - 2];
	}
	if (column >= 9 && column < 9 + NAME_COLUMNS) {
		return PRESET_NAMES[presetIndex * NAME_COLUMNS + column - 9];
	}
	return 0;
}

// Whether an item's label covers point p (relative to the label's
// bottom-left corner, `cell` x `height` per character).
bool labelPixel(int item, int presetIndex, int colours, int species, int shown, vec2 p, float cell, float height) {
	int column = int(p.x / cell);
	int columns = item == PRESET_ROW ? PRESET_COLUMNS : LABEL_COLUMNS;
	if (p.x < 0.0 || p.y < 0.0 || p.y >= height || column >= columns) {
		return false;
	}
	int glyph = glyphAt(item, column, presetIndex, colours, species, shown);
	// 5x5 glyph plus a blank column between letters.
	float pixel = height / 5.0;
	int gx = int((p.x - float(column) * cell) / pixel);
	int gy = 4 - int(p.y / pixel);
	if (gx > 4 || gy < 0) {
		return false;
	}
	int bits = GLYPHS[glyph * 5 + gy];
	return ((bits >> (4 - gx)) & 1) == 1;
}

// `touched` is the item a finger's on (-1 for none).
vec3 menu(vec2 fragCoord, vec4 s0, vec4 s1, vec4 s3, vec4 s5, vec4 s6, vec4 s7, vec4 s9, vec4 s10, int touched, vec3 col) {
	int species = clamp(int(s9.z + 0.5), 1, MAX_SPECIES);
	Layout m = menuLayout(species);
	// A dark band behind it all, so it reads over a bright trail.
	float top = m.bottom + float(m.rows + 1) * m.stride;
	if (fragCoord.y < m.bottom - m.gap || fragCoord.y > top) {
		return col;
	}
	col *= 0.4;

	float along;
	vec2 p;
	int item = menuItem(m, fragCoord, along, p);
	if (item < 0) {
		return col;
	}
	bool full = item == PRESET_ROW;
	float width = full ? m.width : m.columnWidth;
	if (p.x < 0.0 || p.x > width) {
		return col;
	}
	int presetIndex = int(s5.x + 0.5);
	int schemes[MAX_SPECIES] = int[](int(s6.z + 0.5), int(s9.x + 0.5), int(s9.y + 0.5));
	int sp = colourSpecies(item);
	int colours = schemes[max(sp, 0)];
	// While species is dragged, the count it'll be on letting go.
	int shown = touched == SPECIES_BAR ? clamp(int(s3.w + 0.5), 1, MAX_SPECIES) : species;
	float values[BARS + 1] = float[](
			level(s7.x, SCALE_RANGE),
			1.0 - level(s1.y, FADE_RANGE),
			level(s1.x, SPEED_RANGE),
			level(s0.z, TURN_RANGE),
			level(s0.y, SENSOR_DISTANCE_RANGE),
			level(s0.x, SENSOR_ANGLE_RANGE),
			level(s7.y, ZOOM_RANGE),
			(float(schemes[0]) + 0.5) / float(PALETTES),
			s6.y,
			s6.x,
			level(s6.w, GAMMA_RANGE),
			level(s1.z, GLOW_RANGE),
			float(shown) / float(MAX_SPECIES),
			s5.z / AVOID_MAX,
			(float(schemes[1]) + 0.5) / float(PALETTES),
			(float(schemes[2]) + 0.5) / float(PALETTES),
			s10.x,
			(float(presetIndex) + 1.0) / float(PRESETS));
	vec3 tints[BARS + 1] = vec3[](
			vec3(0.85),
			vec3(0.8, 0.5, 1.0),
			vec3(0.4, 0.65, 1.0),
			vec3(0.4, 1.0, 0.5),
			vec3(1.0, 0.85, 0.3),
			vec3(1.0, 0.35, 0.35),
			vec3(0.85),
			vec3(1.0),
			vec3(0.6, 1.0, 0.8),
			vec3(1.0, 0.85, 0.45),
			vec3(0.9, 0.9, 0.6),
			vec3(0.4, 1.0, 1.0),
			vec3(0.95, 0.6, 0.95),
			vec3(1.0, 0.6, 0.3),
			vec3(1.0),
			vec3(1.0),
			vec3(0.75, 0.85, 1.0),
			vec3(1.0));
	bool lit = item == touched;
	float alpha = lit ? 0.95 : 0.6;
	vec3 tint = tints[item];

	if (p.y < m.barHeight) {
		float x = p.x / width;
		float value = clamp(values[item], 0.0, 1.0);
		// The middle (1) marked on scale and gamma.
		if ((item == SCALE_BAR || item == GAMMA_BAR) && abs(p.x - 0.5 * width) < 1.0) {
			return mix(col, vec3(1.0), alpha);
		}
		if (sp >= 0) {
			// The scheme itself, with a notch where it is along the bar.
			vec3 background, low, high, agent;
			palette(colours, background, low, high, agent);
			bool notch = abs(x - value) * width < 1.5;
			return mix(col, notch ? vec3(1.0) : ramp(x, background, low, high), alpha);
		}
		return mix(col, x < value ? tint : tint * 0.2, alpha);
	}
	float cell = width / float(full ? PRESET_COLUMNS : LABEL_COLUMNS);
	if (labelPixel(item, presetIndex, colours, species, shown, vec2(p.x, p.y - m.barHeight - m.gap), cell, m.textHeight)) {
		return mix(col, mix(tint, vec3(1.0), 0.5), lit ? 1.0 : 0.85);
	}
	return col;
}

// The trail at q (in the colony's pixels), smoothed for magnifying by a
// cubic B-spline - near enough a Gaussian - from four bilinear taps.
vec4 smoothTrail(vec2 q, vec2 size) {
	vec2 st = q - 0.5;
	vec2 i = floor(st);
	vec2 f = st - i;
	vec2 f2 = f * f;
	vec2 f3 = f2 * f;
	vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
	vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
	vec2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
	vec2 w3 = f3 / 6.0;
	vec2 g0 = w0 + w1;
	vec2 g1 = w2 + w3;
	vec2 h0 = clamp(i - 0.5 + w1 / g0, vec2(0.5), size - 0.5) / iResolution.xy;
	vec2 h1 = clamp(i + 1.5 + w3 / g1, vec2(0.5), size - 0.5) / iResolution.xy;
	return g0.y * (g0.x * texture(iChannel0, h0) + g1.x * texture(iChannel0, vec2(h1.x, h0.y)))
			+ g1.y * (g0.x * texture(iChannel0, vec2(h0.x, h1.y)) + g1.x * texture(iChannel0, h1));
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec4 s0 = texelFetch(iChannel1, ivec2(0, 0), 0);
	vec4 s1 = texelFetch(iChannel1, ivec2(1, 0), 0);
	vec4 s3 = texelFetch(iChannel1, ivec2(3, 0), 0);
	vec4 s5 = texelFetch(iChannel1, ivec2(5, 0), 0);
	vec4 s6 = texelFetch(iChannel1, ivec2(6, 0), 0);
	vec4 s7 = texelFetch(iChannel1, ivec2(7, 0), 0);
	vec4 s9 = texelFetch(iChannel1, ivec2(9, 0), 0);
	vec4 s10 = texelFetch(iChannel1, ivec2(10, 0), 0);
	bool written = s1.w == 2.0;
	if (!written) {
		s6 = vec4(1.0, 0.0, 2.0, 1.0);
		s7 = vec4(1.0);
		s9 = vec4(10.0, 11.0, 3.0, 3.0);
		s10 = vec4(0.5, 1.0, 0.0, 0.0);
	}
	int species = clamp(int(s9.z + 0.5), 1, MAX_SPECIES);
	int schemes[MAX_SPECIES] = int[](int(s6.z + 0.5), int(s9.x + 0.5), int(s9.y + 0.5));
	float glow = written ? s1.z : 1.2;
	float fade = written ? s1.y : FADE;
	// The trail's pixels are `zoom` of the screen's, the agents' cells
	// cellPixels of the trail's (see Buffer A).
	float zoom = max(s7.z, 1.0);
	float cellPixels = cellSize(s7.w);
	vec2 size = vec2(gridSize(s7.z, s7.w)) * cellPixels;
	vec2 q = fragCoord / zoom;
	vec4 trail = zoom > 1.01
			? smoothTrail(q, size)
			: texture(iChannel0, clamp(q, vec2(0.5), size - 0.5) / iResolution.xy);
	// Soften: where the trail stands above the trail round about - a thin
	// ridge, just laid - bring it down toward it. Broad veins (as high as
	// what's round them) and the gaps between (lower) are left sharp.
	if (s10.x > 0.0) {
		float reach = SOFTEN_RADIUS * cellPixels;
		vec4 around = vec4(0.0);
		for (int i = 0; i < 8; ++i) {
			float a = float(i) * 0.785398 + 0.39;
			vec2 at = clamp(q + vec2(cos(a), sin(a)) * reach, vec2(0.5), size - 0.5);
			around += texture(iChannel0, at / iResolution.xy);
		}
		trail -= s10.x * max(trail - around / 8.0, 0.0);
	}

	// Each species' trail through its scheme, over the ones before (the
	// first over its background, with the food in it).
	vec3 background, low, high, agentTint;
	palette(schemes[0], background, low, high, agentTint);
	vec3 col = background;
	for (int i = 0; i < MAX_SPECIES; ++i) {
		if (i >= species) {
			break;
		}
		palette(schemes[i], background, low, high, agentTint);
		float value = i == 0 ? trail.r + trail.a : trail[i];
		// Trail builds up to about deposit / fade, so scale by the fade:
		// longer-lasting trails keep their detail instead of washing out.
		float t = 1.0 - exp(-max(value, 0.0) * glow * fade / 0.07);
		t = pow(t, 1.0 / max(s6.w, 0.01));
		col = mix(col, ramp(t, col, low, high), s6.x);
	}
	col += REPEL_TINT * clamp(-trail.a * 0.05, 0.0, 1.0);

	// The agents over it: a pixel each, or once they're bigger on screen,
	// a dot where in its cell it is.
	vec2 home = floor(q / cellPixels);
	ivec2 cell = ivec2(home);
	vec4 agent = texelFetch(iChannel1, cell, 0);
	int kind = speciesOf(agent);
	if (kind >= 0 && !(cell.y == 0 && cell.x < SETTINGS)) {
		palette(schemes[min(kind, MAX_SPECIES - 1)], background, low, high, agentTint);
		vec2 at = (home + agent.xy) * cellPixels;
		float across = cellPixels * zoom;
		float r = 0.3 * across;
		float spot = across < 1.5
				? (floor(fragCoord) == floor(at * zoom) ? 1.0 : 0.0)
				: smoothstep(r + 0.7, r - 0.7, length(q - at) * zoom);
		col = mix(col, agentTint, s6.y * spot);
	}

	if (!written || s3.y > 0.5) {
		col = menu(fragCoord, s0, s1, s3, s5, s6, s7, s9, s10, pointerCount > 0 ? int(floor(s3.x + 0.5)) : -1, col);
	}
	fragColor = vec4(col, 1.0);
}

//// Common
// What every pass shares: the settings' places and ranges, the menu's
// layout, and how the scale bar scales things.
//
// Pixels 0-10 of Buffer A's bottom row hold settings (w = 2 marks the
// ones a fresh colony checks):
//   (0,0) = sensor angle, sensor distance, turn angle, 2
//   (1,0) = speed, trail fade, glow, 2
//   (2,0) = the touch: where it began (xy), how many steps it's lasted
//           (z), and the finger count last step (w)
//   (3,0) = the menu item it began on (-1: none), 1 while the menu's
//           shown, 1 once it's moved (or had more fingers: not a tap),
//           and the species count the species bar's being dragged to
//   (4,0) = how much the trail spreads a step, the spread's radius
//           (pixels), turning jitter (radians), new agents a pixel a step
//   (5,0) = the preset, 1 while a finger's dropping food, avoidance, 2
//   (6,0) = trail opacity, agent opacity, colour scheme, gamma
//   (7,0) = scale, zoom, and the zoom and scale the agents ran at this
//           step
//   (8,0) = how far (in cells) this step moved the colony to keep its
//           middle in the middle as zoom or scale changed, and the half-cells
//           that rounding left over, for next time
//   (9,0) = the second and third species' colour schemes, how many
//           species there are, and how many the agents ran with this step
//   (10,0) = soften, the scale the agents ran at last step, 0, 0
// Distances and speeds are as at REFERENCE_HEIGHT and scale 1.
uniform vec3 pointers[10];
uniform int pointerCount;

#define PI 3.14159265
#define SETTINGS 11
#define MAX_SPECIES 3

#define SENSOR_ANGLE_RANGE vec2(0.02, 2.5)
#define SENSOR_DISTANCE_RANGE vec2(0.002, 0.08)
#define TURN_RANGE vec2(0.01, 1.6)
#define SPEED_RANGE vec2(0.05, 1.0)
#define FADE_RANGE vec2(0.002, 0.3)
#define GLOW_RANGE vec2(0.02, 5.0)
#define SCALE_RANGE vec2(0.25, 4.0)
#define ZOOM_RANGE vec2(1.0, 4.0)
#define GAMMA_RANGE vec2(0.25, 4.0)
#define AVOID_MAX 2.0
#define PALETTES 12

// The height the presets were found at (1/4 quality on a phone 3088
// pixels tall). At other heights everything measured in pixels scales
// with it, so the colony looks the same, in more or less detail.
#define REFERENCE_HEIGHT 772.0

// How many times bigger than at REFERENCE_HEIGHT (and scale 1)
// distances in pixels are.
float pixelScale(float scale) {
	return scale * iResolution.y / REFERENCE_HEIGHT;
}

// Scaled up, an agent's cell is this many trail pixels across: the
// agents stay as many as at REFERENCE_HEIGHT and scale 1, on a coarser
// grid (they never move more than a cell a step), and the trail they lay
// is finer. Scaled down, cells are pixels and distances shrink instead.
float cellSize(float scale) {
	return max(1.0, pixelScale(scale));
}

// The agents' grid (in cells) in the bottom-left of the buffers: all of
// them, or 1/zoom of them, magnified. The trail covers the same patch.
ivec2 gridSize(float zoom, float scale) {
	return ivec2(floor(iResolution.xy / max(zoom, 1.0) / cellSize(scale)));
}

// THE MENU: two columns of bars (colony settings on the left, the look
// on the right), each with its label above, and the preset row across
// the top. Bars are numbered up the left column, then up the right.
#define HUD_MARGIN 0.03
#define HUD_BOTTOM 0.05
// The menu's top, as a fraction of the screen's height.
#define MENU_HEIGHT 0.75
#define COLUMN_GAP 0.05
#define BARS 17
#define PRESET_ROW 17
#define SCALE_BAR 0
#define ZOOM_BAR 6
#define COLOURS_BAR 7
#define GAMMA_BAR 10
#define SPECIES_BAR 12
#define AVOID_BAR 13
#define COLOURS2_BAR 14
#define COLOURS3_BAR 15
#define SOFTEN_BAR 16
// Not a bar: the first colours bar's label when there's more than one.
#define COLOURS1_LABEL 17
#define LABEL_COLUMNS 15
#define PRESET_COLUMNS 21

struct Layout {
	float margin;
	float width;
	float columnWidth;
	float barHeight;
	float gap;
	float textHeight;
	float stride;
	float bottom;
	int rows;
	int species;
};

// An agent's species (0 to MAX_SPECIES - 1), or -1 for none.
int speciesOf(vec4 agent) {
	return agent.w < 0.5 ? -1 : int(agent.w + 0.5) - 1;
}

// Which species a colours bar is for, or -1 if it isn't one.
int colourSpecies(int bar) {
	return bar == COLOURS_BAR ? 0 : (bar == COLOURS2_BAR ? 1 : (bar == COLOURS3_BAR ? 2 : -1));
}

// The bar at a row (from the bottom) of a column, or -1: the colony's
// settings on the left, the look on the right, with avoidance and a
// colours bar for each species only when there are that many.
int barAt(int column, int row, int species) {
	int left[9] = int[](0, 1, 2, 3, 4, 5, AVOID_BAR, SPECIES_BAR, -1);
	int right[9] = int[](ZOOM_BAR, COLOURS_BAR, COLOURS2_BAR, COLOURS3_BAR, 8, 9, GAMMA_BAR, 11, SOFTEN_BAR);
	int k = 0;
	for (int i = 0; i < 9; ++i) {
		int bar = column == 0 ? left[i] : right[i];
		if (bar < 0) {
			break;
		}
		bool shown = bar == AVOID_BAR || bar == COLOURS2_BAR ? species > 1 : (bar == COLOURS3_BAR ? species > 2 : true);
		if (shown) {
			if (k == row) {
				return bar;
			}
			++k;
		}
	}
	return -1;
}

int barColumn(int bar) {
	return bar < ZOOM_BAR || bar == SPECIES_BAR || bar == AVOID_BAR ? 0 : 1;
}

Layout menuLayout(int species) {
	Layout m;
	m.species = species;
	m.rows = max(species > 1 ? 8 : 7, 6 + species);
	m.margin = HUD_MARGIN * iResolution.x;
	m.width = iResolution.x - 2.0 * m.margin;
	m.columnWidth = 0.5 * (m.width - COLUMN_GAP * iResolution.x);
	m.bottom = HUD_BOTTOM * iResolution.y;
	// The rows and the preset row share the screen up to MENU_HEIGHT.
	m.stride = (MENU_HEIGHT * iResolution.y - m.bottom) / float(m.rows + 1);
	m.barHeight = max(2.0, 0.3 * m.stride);
	// Letters as tall as the bars - unless the longest label wouldn't
	// fit across a column, then as big as fits.
	m.textHeight = min(m.barHeight, m.columnWidth / float(LABEL_COLUMNS) * 5.0 / 6.0);
	// The rest between: bar, gap, label, two gaps.
	m.gap = (m.stride - m.barHeight - m.textHeight) / 3.0;
	return m;
}

// What's at point p in the menu: a bar (0 to BARS - 1), PRESET_ROW, or
// -1 (nothing, or a gap in the shorter column). `along` is how far across it p is (0..1), and `local` is p from
// its bottom-left corner.
int menuItem(Layout m, vec2 p, out float along, out vec2 local) {
	vec2 q = p - vec2(m.margin, m.bottom);
	along = 0.0;
	local = vec2(0.0);
	if (q.y < 0.0 || q.y >= m.stride * float(m.rows + 1) || q.x < -m.margin || q.x > m.width + m.margin) {
		return -1;
	}
	int row = int(q.y / m.stride);
	local.y = q.y - float(row) * m.stride;
	if (row == m.rows) {
		local.x = q.x;
		along = clamp(q.x / m.width, 0.0, 1.0);
		return PRESET_ROW;
	}
	int column = q.x < 0.5 * m.width ? 0 : 1;
	local.x = q.x - (column == 0 ? 0.0 : m.width - m.columnWidth);
	along = clamp(local.x / m.columnWidth, 0.0, 1.0);
	return barAt(column, row, m.species);
}

//// Buffer A
// Three steps a frame: the colony grows and settles faster.
///steps:3
// The agents, on a grid of cells (pixels, unless scaled up: see
// cellSize()). Each cell holds at most one: xy = where inside the cell it
// is, z = its heading, w = 1 if there is one. Every step each cell works
// out where the agents around it move to, and keeps the one (if any)
// that lands inside it - so agents never move more than a cell.
// The bottom row's first pixels hold the settings instead (see Common);
// a bar sets its setting from where the finger is, not by how far it's
// moved, so 16-bit rounding can't creep in.
//
// ZOOM: above 1, the colony runs in the bottom-left 1/zoom of the
// buffers, wrapping there, and Image magnifies it. A step's agents go by
// (7,0)'s y (and scale by its x) as last step left it, and everything
// after them - the trail, Image, the next step's new ground - by its z and
// w, the zoom and scale they ran at. When zoom changes the colony moves
// by half the change in the patch's size, so what was in the middle of
// the screen stays there: (8,0) says how far, for the trail too.
#define DENSITY 0.35
// A fresh colony's settings: sensor angle and turn (radians), sensor
// distance (screen heights), speed (pixels a step), trail fade (a step),
// glow.
#define SENSOR_ANGLE 0.40
#define SENSOR_DISTANCE 0.007
#define TURN 0.8
#define SPEED 1.0
#define FADE 0.03
#define GLOW 1.2
#define DIFFUSE 0.1
#define BLUR 1.0
#define JITTER 0.0
#define SPAWN 0.0004
// The preset a fresh colony starts with (0: the settings above).
#define PRESET 0
// What presets leave alone: the look (0-1 opacities, a colour scheme
// from palette() in Image, gamma), scale and zoom.
#define TRAIL_OPACITY 1.0
#define AGENT_OPACITY 0.0
#define COLOURS 2
// How many species a fresh colony has (1 to MAX_SPECIES), the second and
// third ones' colour schemes, and how much each avoids the others'
// trails (0 to AVOID_MAX).
#define SPECIES 3
#define COLOURS_2 10
#define COLOURS_3 11
#define AVOID 1.0
// How much thin, just-laid trails are eased into the trail round them
// (0-1, only how it looks: see Image).
#define SOFTEN 0.5
#define GAMMA 1.0
#define SCALE 1.0
#define ZOOM 1.0
// A tap: lifted within this many steps (3 a frame), having moved less
// than this (screen heights). Food starts after it, or once it moves.
#define TAP_STEPS 45.0
#define TAP_MOVE 0.02

uniform sampler2D iChannel0;///buf:A;min:n;mag:n;
uniform sampler2D iChannel1;///buf:B;s:r;t:r;

float hash(vec2 p) {
	vec3 p3 = fract(vec3(p.xyx) * 0.1031);
	p3 += dot(p3, p3.yzx + 33.33);
	return fract((p3.x + p3.y) * p3.z);
}

// The trail `reach` pixels away at `angle`, wrapped round the colony's
// `size`.
// Every species' trail, and the food in alpha.
vec4 sniff(vec2 pos, float angle, float reach, vec2 size) {
	vec2 p = mod(pos + vec2(cos(angle), sin(angle)) * reach, size);
	return texture(iChannel1, p / iResolution.xy);
}

// What species `kind` makes of the trail's channels: its own and the
// food draw it, the other species' put it off.
vec4 scent(int kind, int species, float avoid) {
	vec4 w = vec4(0.0, 0.0, 0.0, 1.0);
	for (int i = 0; i < MAX_SPECIES; ++i) {
		w[i] = i == kind ? 1.0 : (i < species ? -avoid : 0.0);
	}
	return w;
}

// An agent of any of `species` species (w = 1 + its species).
vec4 randomAgent(vec2 fragCoord, int species) {
	return vec4(
			hash(fragCoord + 0.31),
			hash(fragCoord + 7.17),
			hash(fragCoord + 3.73) * 2.0 * PI,
			1.0 + floor(hash(fragCoord + 5.29) * float(species)));
}

// PRESETS BEGIN
// Preset i's settings, as pixels (0,0), (1,0) and (4,0) hold them;
// 0 is the defines above, the rest were found by a search for
// patterns like Softology's (made by make_presets.py).
#define PRESETS 14
void preset(int i, out vec4 s0, out vec4 s1, out vec4 s4) {
	if (i == 1) {
		// Cells
		s0 = vec4(0.06451, 0.00823, 0.35444, 2.0);
		s1 = vec4(0.86844, 0.01185, 1.494, 2.0);
		s4 = vec4(0.02573, 4.2525, 0.28657, 0.00075);
		return;
	}
	if (i == 2) {
		// Veins
		s0 = vec4(0.17208, 0.01422, 0.34554, 2.0);
		s1 = vec4(0.64295, 0.01489, 1.172, 2.0);
		s4 = vec4(0.23816, 1.0708, 0.0, 0.00025);
		return;
	}
	if (i == 3) {
		// Web
		s0 = vec4(0.09244, 0.03106, 0.0555, 2.0);
		s1 = vec4(0.67654, 0.00957, 1.984, 2.0);
		s4 = vec4(0.08216, 3.2149, 0.0, 0.0001);
		return;
	}
	if (i == 4) {
		// Weave
		s0 = vec4(0.08, 0.01, 0.08, 2.0);
		s1 = vec4(1.0, 0.005, 1.491, 2.0);
		s4 = vec4(0.02, 1.0, 0.0, 0.0004);
		return;
	}
	if (i == 5) {
		// Fibres
		s0 = vec4(0.08, 0.03, 0.08, 2.0);
		s1 = vec4(1.0, 0.005, 1.197, 2.0);
		s4 = vec4(0.02, 1.0, 0.0, 0.0004);
		return;
	}
	if (i == 6) {
		// Rivers
		s0 = vec4(0.18691, 0.03391, 0.72019, 2.0);
		s1 = vec4(0.96755, 0.00538, 1.577, 2.0);
		s4 = vec4(0.12934, 2.5434, 0.0, 0.00032);
		return;
	}
	if (i == 7) {
		// Stars
		s0 = vec4(1.5086, 0.03304, 0.4726, 2.0);
		s1 = vec4(0.26859, 0.04105, 0.45, 2.0);
		s4 = vec4(0.05394, 3.9556, 0.07946, 0.00327);
		return;
	}
	if (i == 8) {
		// Glow Worms
		s0 = vec4(1.0, 0.025, 0.8, 2.0);
		s1 = vec4(1.0, 0.01, 1.625, 2.0);
		s4 = vec4(0.3, 3.0, 0.0, 0.0004);
		return;
	}
	if (i == 9) {
		// Maze
		s0 = vec4(0.11969, 0.00222, 0.6105, 2.0);
		s1 = vec4(0.92321, 0.00621, 1.35, 2.0);
		s4 = vec4(0.03779, 2.3904, 0.0, 0.00199);
		return;
	}
	if (i == 10) {
		// Worms
		s0 = vec4(1.5309, 0.00887, 1.3188, 2.0);
		s1 = vec4(0.68766, 0.00717, 0.602, 2.0);
		s4 = vec4(0.0294, 1.1089, 0.0, 0.00324);
		return;
	}
	if (i == 11) {
		// Hairs
		s0 = vec4(0.2662, 0.00444, 0.21213, 2.0);
		s1 = vec4(0.91676, 0.08826, 0.73, 2.0);
		s4 = vec4(0.03003, 3.212, 0.03542, 0.00021);
		return;
	}
	if (i == 12) {
		// Spots
		s0 = vec4(2.0, 0.01, 0.8, 2.0);
		s1 = vec4(1.0, 0.01, 2.597, 2.0);
		s4 = vec4(0.3, 3.0, 0.0, 0.0004);
		return;
	}
	if (i == 13) {
		// Fingerprint
		s0 = vec4(0.63487, 0.00983, 0.548, 2.0);
		s1 = vec4(0.84806, 0.01983, 0.641, 2.0);
		s4 = vec4(0.02274, 4.0676, 0.0414, 0.00367);
		return;
	}
	s0 = vec4(SENSOR_ANGLE, SENSOR_DISTANCE, TURN, 2.0);
	s1 = vec4(SPEED, FADE, GLOW, 2.0);
	s4 = vec4(DIFFUSE, BLUR, JITTER, SPAWN);
}
// PRESETS END

// The value at `level` (0..1) of `range`, on a log scale.
float fromLevel(float level, vec2 range) {
	return range.x * pow(range.y / range.x, clamp(level, 0.0, 1.0));
}

// Sets `bar` (numbered as in Common) to `v` (0..1).
void setBar(int bar, float v, inout vec4 s0, inout vec4 s1, inout vec4 s3, inout vec4 s5, inout vec4 s6, inout vec4 s7, inout vec4 s9, inout vec4 s10) {
	// Scale and gamma snap to 1 near the middle.
	bool middle = abs(v - 0.5) < 0.025;
	if (bar == 0) {
		s7.x = middle ? 1.0 : fromLevel(v, SCALE_RANGE);
	} else if (bar == 1) {
		s1.y = fromLevel(1.0 - v, FADE_RANGE);
	} else if (bar == 2) {
		s1.x = fromLevel(v, SPEED_RANGE);
	} else if (bar == 3) {
		s0.z = fromLevel(v, TURN_RANGE);
	} else if (bar == 4) {
		s0.y = fromLevel(v, SENSOR_DISTANCE_RANGE);
	} else if (bar == 5) {
		s0.x = fromLevel(v, SENSOR_ANGLE_RANGE);
	} else if (bar == 6) {
		s7.y = fromLevel(v, ZOOM_RANGE);
	} else if (bar == 7) {
		s6.z = min(floor(v * float(PALETTES)), float(PALETTES - 1));
	} else if (bar == 8) {
		s6.y = v;
	} else if (bar == 9) {
		s6.x = v;
	} else if (bar == 10) {
		s6.w = middle ? 1.0 : fromLevel(v, GAMMA_RANGE);
	} else if (bar == 11) {
		s1.z = fromLevel(v, GLOW_RANGE);
	} else if (bar == SPECIES_BAR) {
		// Only noted: it changes when the finger lifts.
		s3.w = min(floor(v * float(MAX_SPECIES)), float(MAX_SPECIES - 1)) + 1.0;
	} else if (bar == AVOID_BAR) {
		s5.z = v * AVOID_MAX;
	} else if (bar == COLOURS2_BAR) {
		s9.x = min(floor(v * float(PALETTES)), float(PALETTES - 1));
	} else if (bar == COLOURS3_BAR) {
		s9.y = min(floor(v * float(PALETTES)), float(PALETTES - 1));
	} else if (bar == SOFTEN_BAR) {
		s10.x = v;
	}
}

// A tap on a colours bar: that species' next scheme.
void nextColours(int bar, inout vec4 s6, inout vec4 s9) {
	if (bar == COLOURS_BAR) {
		s6.z = mod(s6.z + 1.0, float(PALETTES));
	} else if (bar == COLOURS2_BAR) {
		s9.x = mod(s9.x + 1.0, float(PALETTES));
	} else if (bar == COLOURS3_BAR) {
		s9.y = mod(s9.y + 1.0, float(PALETTES));
	}
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 cell = ivec2(fragCoord);
	vec4 s0 = texelFetch(iChannel0, ivec2(0, 0), 0);
	vec4 s1 = texelFetch(iChannel0, ivec2(1, 0), 0);
	vec4 s2 = texelFetch(iChannel0, ivec2(2, 0), 0);
	vec4 s3 = texelFetch(iChannel0, ivec2(3, 0), 0);
	vec4 s4 = texelFetch(iChannel0, ivec2(4, 0), 0);
	vec4 s5 = texelFetch(iChannel0, ivec2(5, 0), 0);
	vec4 s6 = texelFetch(iChannel0, ivec2(6, 0), 0);
	vec4 s7 = texelFetch(iChannel0, ivec2(7, 0), 0);
	vec4 s8 = texelFetch(iChannel0, ivec2(8, 0), 0);
	vec4 s9 = texelFetch(iChannel0, ivec2(9, 0), 0);
	vec4 s10 = texelFetch(iChannel0, ivec2(10, 0), 0);
	// No settings marker: blank buffers (the first frame, Rewind, or a
	// resize cleared them) - start a new colony with default settings.
	// Not on iFrame: a run carries on whatever the frame count does.
	bool fresh = s0.w != 2.0 || s1.w != 2.0 || s5.w != 2.0;
	if (fresh) {
		preset(PRESET, s0, s1, s4);
		s2 = vec4(0.0);
		s3 = vec4(-1.0, 1.0, 0.0, 0.0);
		s5 = vec4(float(PRESET), 0.0, AVOID, 2.0);
		s6 = vec4(TRAIL_OPACITY, AGENT_OPACITY, float(COLOURS), GAMMA);
		s7 = vec4(SCALE, ZOOM, ZOOM, SCALE);
		s8 = vec4(0.0);
		s9 = vec4(float(COLOURS_2), float(COLOURS_3), float(SPECIES), float(SPECIES));
		s10 = vec4(SOFTEN, SCALE, 0.0, 0.0);
	}
	int species = clamp(int(s9.z + 0.5), 1, MAX_SPECIES);
	// The species count just changed: deal every agent a species afresh.
	bool reshuffle = s9.z != s9.w;
	// The grid this step runs on, and the last step's.
	float cellPixels = cellSize(s7.x);
	ivec2 size = gridSize(s7.y, s7.x);
	ivec2 before = gridSize(s7.z, s7.w);
	// Zoom or scale changed: this cell's agents come from `shift` cells
	// along, half the grid's change (with what last time's halving left
	// over), so the middle stays in the middle.
	bool regrid = s7.y != s7.z || s7.x != s7.w;
	ivec2 change = regrid ? before - size + ivec2(s8.zw) : ivec2(0);
	ivec2 shift = change / 2;
	ivec2 leftOver = regrid ? change - shift * 2 : ivec2(s8.zw);
	// The trail they sense is last step's, in its cells' pixels.
	float lastCellPixels = cellSize(s7.w);

	if (cell.y == 0 && cell.x < SETTINGS) {
		int count = pointerCount;
		int previous = int(s2.w + 0.5);
		vec2 finger = pointers[0].xy;
		Layout m = menuLayout(species);
		float along;
		vec2 local;
		s10.y = s7.w;
		s7.zw = vec2(s7.y, s7.x);
		s9.w = s9.z;
		if (count > 0 && previous == 0) {
			// A touch begins: on the menu (if it's shown), or the colony.
			int item = s3.y > 0.5 ? menuItem(m, finger, along, local) : -1;
			s2.xyz = vec3(finger, 0.0);
			s3.x = float(item);
			s3.z = 0.0;
			if (item == PRESET_ROW) {
				// The previous or next preset (the colony carries on and
				// grows into it).
				int side = along < 0.5 ? -1 : 1;
				int i = (int(s5.x + 0.5) + side + PRESETS) % PRESETS;
				preset(i, s0, s1, s4);
				s5.x = float(i);
			}
		}
		int item = int(floor(s3.x + 0.5));
		if (count > 0) {
			s2.z = min(s2.z + 1.0, 1000.0);
			if (count > 1 || length(finger - s2.xy) > TAP_MOVE * iResolution.y) {
				s3.z = 1.0;
			}
			// Along the bar it began on, wherever the finger's gone - a
			// colours bar only once it's not a tap (that's the next scheme).
			bool tapping = s3.z < 0.5 && s2.z < TAP_STEPS;
			if (count == 1 && item >= 0 && item < BARS && !(colourSpecies(item) >= 0 && tapping)) {
				float left = m.margin + (barColumn(item) == 0 ? 0.0 : m.width - m.columnWidth);
				setBar(item, clamp((finger.x - left) / m.columnWidth, 0.0, 1.0), s0, s1, s3, s5, s6, s7, s9, s10);
			}
		} else if (previous > 0) {
			// Lifted: a quick tap off the menu shows or hides it.
			if (s3.z < 0.5 && s2.z < TAP_STEPS) {
				if (item < 0) {
					s3.y = 1.0 - s3.y;
				}
				nextColours(item, s6, s9);
			}
			// The species count changes only now, so the menu's rows
			// don't move under the finger.
			if (item == SPECIES_BAR) {
				s9.z = s3.w;
			}
			s3.x = -1.0;
		}
		// Food from one finger on the colony, once it's not a tap.
		s5.y = count == 1 && item < 0 && (s3.z > 0.5 || s2.z >= TAP_STEPS) ? 1.0 : 0.0;
		s2.w = float(count);
		s8 = vec4(vec2(shift), vec2(leftOver));
		vec4 all[SETTINGS] = vec4[](s0, s1, s2, s3, s4, s5, s6, s7, s8, s9, s10);
		fragColor = all[cell.x];
		return;
	}

	// Outside the colony: nothing. Ground it's just grown onto (zoom or
	// scale went down): a fresh scatter of agents.
	if (cell.x >= size.x || cell.y >= size.y) {
		fragColor = vec4(0.0);
		return;
	}
	ivec2 source = cell + shift;
	if (fresh || any(lessThan(source, ivec2(0))) || any(greaterThanEqual(source, before))) {
		fragColor = hash(fragCoord + 0.7 + fract(iTime)) < DENSITY ? randomAgent(fragCoord + iTime, species) : vec4(0.0);
		return;
	}

	// Sensing in trail pixels; scaled down, speed (in cells) shrinks too.
	float pixels = pixelScale(s7.x);
	vec2 trailSize = vec2(before) * lastCellPixels;
	float sensorAngle = s0.x;
	float sensorDistance = s0.y * REFERENCE_HEIGHT * pixels;
	float turnAngle = s0.z;
	float speed = s1.x * min(pixels, 1.0);
	float jitter = s4.z;
	float avoid = s5.z;

	vec4 result = vec4(0.0);
	float bestKey = -1.0;
	for (int dy = -1; dy <= 1; ++dy) {
		for (int dx = -1; dx <= 1; ++dx) {
			ivec2 from = source + ivec2(dx, dy);
			ivec2 q = (from + before) % before;
			if (q.y == 0 && q.x < SETTINGS) {
				continue;
			}
			vec4 agent = texelFetch(iChannel0, q, 0);
			int kind = speciesOf(agent);
			if (kind < 0) {
				continue;
			}
			// Unwrapped, so "did it land in this cell" is a plain test.
			vec2 pos = vec2(from) + agent.xy;
			float heading = agent.z;
			vec4 w = scent(kind, species, avoid);
			float left = dot(w, sniff(pos * lastCellPixels, heading + sensorAngle, sensorDistance, trailSize));
			float ahead = dot(w, sniff(pos * lastCellPixels, heading, sensorDistance, trailSize));
			float right = dot(w, sniff(pos * lastCellPixels, heading - sensorAngle, sensorDistance, trailSize));
			float rnd = hash(pos * 1.37 + float(iFrame) * 0.618);
			heading += (hash(pos * 2.71 + float(iFrame) * 0.377) - 0.5) * 2.0 * jitter;
			if (ahead > left && ahead > right) {
				// Keep going.
			} else if (ahead < left && ahead < right) {
				heading += (rnd - 0.5) * 2.0 * turnAngle;
			} else if (left > right) {
				heading += turnAngle;
			} else {
				heading -= turnAngle;
			}
			vec2 next = pos + vec2(cos(heading), sin(heading)) * speed;
			if (floor(next) == vec2(source) && rnd > bestKey) {
				bestKey = rnd;
				if (reshuffle) {
					kind = int(hash(pos * 4.13 + 0.5) * float(species));
				}
				result = vec4(fract(next), mod(heading, 2.0 * PI), 1.0 + float(min(kind, species - 1)));
			}
		}
	}

	// Two agents landing in one pixel loses one - top the colony back up.
	if (result.w < 0.5 && hash(fragCoord + fract(iTime) * 113.0) < s4.w) {
		result = randomAgent(fragCoord + iTime, species);
	}
	fragColor = result;
}

//// Buffer B
// The trail: last step's, spread over its neighbours and faded, plus
// each agent's deposit - and food or repellent from fingers. A species'
// trail in each of r, g and b, the food (shared by them all) in alpha.
#define DEPOSIT 1.0
#define FOOD 6.0
#define REPEL 30.0
#define FINGER_SIZE 0.03

uniform sampler2D iChannel0;///buf:A;min:n;mag:n;
uniform sampler2D iChannel1;///buf:B;min:n;mag:n;
uniform sampler2D iChannel2;///buf:B;s:r;t:r;

// Finger i's touch, at pixel p of a colony magnified `zoom` times.
float finger(vec2 p, int i, float zoom) {
	zoom = max(zoom, 1.0);
	vec2 d = p - pointers[i].xy / zoom;
	float r = FINGER_SIZE * iResolution.y / zoom;
	return exp(-dot(d, d) / (r * r));
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 cell = ivec2(fragCoord);
	vec4 s1 = texelFetch(iChannel0, ivec2(1, 0), 0);
	vec4 s4 = texelFetch(iChannel0, ivec2(4, 0), 0);
	vec4 s5 = texelFetch(iChannel0, ivec2(5, 0), 0);
	vec4 s7 = texelFetch(iChannel0, ivec2(7, 0), 0);
	vec4 s8 = texelFetch(iChannel0, ivec2(8, 0), 0);
	vec4 s10 = texelFetch(iChannel0, ivec2(10, 0), 0);
	bool written = s1.w == 2.0;
	if (!written) {
		s1 = vec4(1.0, 0.03, 1.2, 2.0);
		s4 = vec4(0.1, 1.0, 0.0, 0.0);
		s7 = vec4(1.0);
		s8 = vec4(0.0);
		s10 = vec4(0.5, 1.0, 0.0, 0.0);
	}
	// The grid this step's agents ran on (see Buffer A), and the trail's
	// patch of the buffers, the same.
	float cellPixels = cellSize(s7.w);
	ivec2 grid = gridSize(s7.z, s7.w);
	vec2 size = vec2(grid) * cellPixels;
	if (fragCoord.x >= size.x || fragCoord.y >= size.y) {
		fragColor = vec4(0.0);
		return;
	}
	float pixels = pixelScale(s7.w);
	float decay = 1.0 - s1.y;

	// The spread: two rings of eight (at the radius and half of it),
	// filtered between pixels, so any radius from 1 to 6 or so is smooth.
	// Scaled, it spreads pixels^2 times as far (in area) a step: by a
	// wider radius where it can, the rest by spreading more.
	float area = s4.x * s4.y * s4.y * pixels * pixels;
	float radius = clamp(s4.y * pixels, min(s4.y, 1.0), 6.0);
	radius = max(radius, sqrt(area));
	float spread = min(1.0, area / (radius * radius));
	vec4 trail;
	if (s8.xy != vec2(0.0) || s10.y != s7.w) {
		// Zoom or scale moved the colony (see Buffer A): the trail goes
		// with it, resampled from last step's cells, unspread this once.
		// Past the old patch's edge there was none.
		vec2 from = (fragCoord / cellPixels + s8.xy) * cellSize(s10.y);
		bool outside = any(lessThan(from, vec2(0.0))) || any(greaterThanEqual(from, iResolution.xy));
		trail = outside ? vec4(0.0) : texture(iChannel2, from / iResolution.xy) * decay;
	} else {
		vec4 centre = texelFetch(iChannel1, cell, 0);
		vec4 sum = centre;
		for (int i = 0; i < 8; ++i) {
			float a = float(i) * 0.785398;
			vec2 d = vec2(cos(a), sin(a)) * radius;
			sum += texture(iChannel2, mod(fragCoord + d, size) / iResolution.xy);
			sum += texture(iChannel2, mod(fragCoord + 0.5 * d, size) / iResolution.xy);
		}
		trail = mix(centre, sum / 17.0, spread) * decay;
	}

	// Deposits from the agents in this pixel's cell and the ones round it,
	// where they are in it: into the pixel each is in on cells a pixel
	// across (as the presets were found), or else a tent as wide as two
	// cells, smooth however big. Either way an agent lays cellPixels^2
	// times a pixel's deposit, so the trail's as strong.
	vec2 home = floor(fragCoord / cellPixels);
	for (int dy = -1; dy <= 1; ++dy) {
		for (int dx = -1; dx <= 1; ++dx) {
			vec2 from = home + vec2(dx, dy);
			ivec2 q = (ivec2(from) + grid) % grid;
			if (q.y == 0 && q.x < SETTINGS) {
				continue;
			}
			vec4 agent = texelFetch(iChannel0, q, 0);
			int kind = speciesOf(agent);
			if (kind < 0) {
				continue;
			}
			vec4 lay = vec4(0.0);
			lay[min(kind, MAX_SPECIES - 1)] = DEPOSIT;
			vec2 at = (from + agent.xy) * cellPixels;
			if (cellPixels < 1.01) {
				if (floor(at) == floor(fragCoord)) {
					trail += lay;
				}
			} else {
				vec2 tent = max(1.0 - abs(fragCoord - at) / cellPixels, 0.0);
				trail += lay * tent.x * tent.y;
			}
		}
	}

	if (s5.y > 0.5) {
		trail.a += FOOD * finger(fragCoord, 0, s7.z);
	} else if (pointerCount >= 4) {
		for (int i = 0; i < 10; ++i) {
			if (i < pointerCount) {
				trail.a -= REPEL * finger(fragCoord, i, s7.z);
			}
		}
	}

	// A bad value would spread over everything and stay: drop it.
	if (any(isnan(trail)) || any(isinf(trail))) {
		trail = vec4(0.0);
	}
	fragColor = clamp(trail, -1000.0, 1000.0);
}
