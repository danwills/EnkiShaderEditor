// SLIME CARTOGRAPHER - a Physarum (slime mould) colony that maps its world
//
// Every agent sniffs the trail ahead-left, ahead and ahead-right of
// itself, turns towards the strongest scent, steps forward and leaves
// more trail behind. The trail spreads and fades. Out of that, veins and
// networks grow, merge and reorganise.
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
//   scale            - the whole pattern, bigger or finer (1 mid-way).
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
//   colours          - the colour scheme (named on the bar)
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
#define GLYPH_COUNT 29
// FONT BEGIN
// 5x5 glyphs, five rows each (top first, leftmost pixel in the
// highest bit) - small numbers, exact on any GPU:  ACDEFGHILNOPRSTUVW23<>^BMZYX
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
		17, 10, 4, 10, 17);
// FONT END
// LABELS BEGIN
// The bars' labels, in bar order (made by make_presets.py from this line):
// SCALE | TRAIL LIFE | SPEED | TURNING | SENSOR DISTANCE | SENSOR ANGLE | ZOOM | COLOURS | AGENT OPACITY | TRAIL OPACITY | GAMMA | GLOW
const int LABELS[12 * LABEL_COLUMNS] = int[](
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
		6, 9, 11, 18, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
// LABELS END

// COLOUR NAMES BEGIN
// The colour schemes' names, as palette() numbers them (made by make_presets.py from this line):
// SLIME | GREY | PAPER | FIRE | TOXIC | ICE | NEON | GOLD | SEPIA | ABYSS
#define COLOUR_COLUMNS 7
const int COLOUR_NAMES[10 * COLOUR_COLUMNS] = int[](
		14, 9, 8, 25, 4, 0, 0,
		6, 13, 4, 27, 0, 0, 0,
		12, 1, 12, 4, 13, 0, 0,
		5, 8, 13, 4, 0, 0, 0,
		15, 11, 28, 8, 2, 0, 0,
		8, 2, 4, 0, 0, 0, 0,
		10, 4, 11, 10, 0, 0, 0,
		6, 11, 9, 3, 0, 0, 0,
		14, 4, 12, 8, 1, 0, 0,
		1, 24, 27, 14, 14, 0, 0);
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

// The glyph at a column of an item's label: a bar's from LABELS (the
// colours bar adds the scheme's name), the preset row "<" PRESET NAME ">".
int glyphAt(int item, int column, int presetIndex, int colours) {
	if (item != PRESET_ROW) {
		if (item == COLOURS_BAR && column >= 8 && column < 8 + COLOUR_COLUMNS) {
			return COLOUR_NAMES[colours * COLOUR_COLUMNS + column - 8];
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
bool labelPixel(int item, int presetIndex, int colours, vec2 p, float cell, float height) {
	int column = int(p.x / cell);
	int columns = item == PRESET_ROW ? PRESET_COLUMNS : LABEL_COLUMNS;
	if (p.x < 0.0 || p.y < 0.0 || p.y >= height || column >= columns) {
		return false;
	}
	int glyph = glyphAt(item, column, presetIndex, colours);
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
vec3 menu(vec2 fragCoord, vec4 s0, vec4 s1, vec4 s5, vec4 s6, vec4 s7, int touched, vec3 col) {
	Layout m = menuLayout();
	// A dark band behind it all, so it reads over a bright trail.
	float top = m.bottom + float(MENU_ROWS + 1) * m.stride;
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
	int colours = int(s6.z + 0.5);
	float values[BARS + 1] = float[](
			level(s7.x, SCALE_RANGE),
			1.0 - level(s1.y, FADE_RANGE),
			level(s1.x, SPEED_RANGE),
			level(s0.z, TURN_RANGE),
			level(s0.y, SENSOR_DISTANCE_RANGE),
			level(s0.x, SENSOR_ANGLE_RANGE),
			level(s7.y, ZOOM_RANGE),
			(float(colours) + 0.5) / float(PALETTES),
			s6.y,
			s6.x,
			level(s6.w, GAMMA_RANGE),
			level(s1.z, GLOW_RANGE),
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
		if (item == COLOURS_BAR) {
			// The scheme itself, with a notch where it is along the bar.
			vec3 background, low, high, agent;
			palette(colours, background, low, high, agent);
			bool notch = abs(x - value) * width < 1.5;
			return mix(col, notch ? vec3(1.0) : ramp(x, background, low, high), alpha);
		}
		return mix(col, x < value ? tint : tint * 0.2, alpha);
	}
	float cell = width / float(full ? PRESET_COLUMNS : LABEL_COLUMNS);
	if (labelPixel(item, presetIndex, colours, vec2(p.x, p.y - m.barHeight - m.gap), cell, m.textHeight)) {
		return mix(col, mix(tint, vec3(1.0), 0.5), lit ? 1.0 : 0.85);
	}
	return col;
}

// The trail at q (in the colony's pixels), smoothed for magnifying by a
// cubic B-spline - near enough a Gaussian - from four bilinear taps.
float smoothTrail(vec2 q, vec2 size) {
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
	return g0.y * (g0.x * texture(iChannel0, h0).r + g1.x * texture(iChannel0, vec2(h1.x, h0.y)).r)
			+ g1.y * (g0.x * texture(iChannel0, vec2(h0.x, h1.y)).r + g1.x * texture(iChannel0, h1).r);
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec4 s0 = texelFetch(iChannel1, ivec2(0, 0), 0);
	vec4 s1 = texelFetch(iChannel1, ivec2(1, 0), 0);
	vec4 s3 = texelFetch(iChannel1, ivec2(3, 0), 0);
	vec4 s5 = texelFetch(iChannel1, ivec2(5, 0), 0);
	vec4 s6 = texelFetch(iChannel1, ivec2(6, 0), 0);
	vec4 s7 = texelFetch(iChannel1, ivec2(7, 0), 0);
	bool written = s1.w == 2.0;
	if (!written) {
		s6 = vec4(1.0, 0.0, 2.0, 1.0);
		s7 = vec4(1.0);
	}
	float glow = written ? s1.z : 1.2;
	float fade = written ? s1.y : FADE;
	// The trail's pixels are `zoom` of the screen's, the agents' cells
	// cellPixels of the trail's (see Buffer A).
	float zoom = max(s7.z, 1.0);
	float cellPixels = cellSize(s7.w);
	vec2 size = vec2(gridSize(s7.z, s7.w)) * cellPixels;
	vec2 q = fragCoord / zoom;
	float trail = zoom > 1.01
			? smoothTrail(q, size)
			: texture(iChannel0, clamp(q, vec2(0.5), size - 0.5) / iResolution.xy).r;

	vec3 background, low, high, agentTint;
	palette(int(s6.z + 0.5), background, low, high, agentTint);
	// Trail builds up to about deposit / fade, so scale by the fade:
	// longer-lasting trails keep their detail instead of washing out.
	float t = 1.0 - exp(-max(trail, 0.0) * glow * fade / 0.07);
	t = pow(t, 1.0 / max(s6.w, 0.01));
	vec3 col = mix(background, ramp(t, background, low, high), s6.x);
	col += REPEL_TINT * clamp(-trail * 0.05, 0.0, 1.0);

	// The agents over it: a pixel each, or once they're bigger on screen,
	// a dot where in its cell it is.
	vec2 home = floor(q / cellPixels);
	ivec2 cell = ivec2(home);
	vec4 agent = texelFetch(iChannel1, cell, 0);
	if (agent.w > 0.5 && agent.w < 1.5 && !(cell.y == 0 && cell.x < SETTINGS)) {
		vec2 at = (home + agent.xy) * cellPixels;
		float across = cellPixels * zoom;
		float r = 0.3 * across;
		float spot = across < 1.5
				? (floor(fragCoord) == floor(at * zoom) ? 1.0 : 0.0)
				: smoothstep(r + 0.7, r - 0.7, length(q - at) * zoom);
		col = mix(col, agentTint, s6.y * spot);
	}

	if (!written || s3.y > 0.5) {
		col = menu(fragCoord, s0, s1, s5, s6, s7, pointerCount > 0 ? int(floor(s3.x + 0.5)) : -1, col);
	}
	fragColor = vec4(col, 1.0);
}

//// Common
// What every pass shares: the settings' places and ranges, the menu's
// layout, and how the scale bar scales things.
//
// Pixels 0-8 of Buffer A's bottom row hold settings (w = 2 marks the
// ones a fresh colony checks):
//   (0,0) = sensor angle, sensor distance, turn angle, 2
//   (1,0) = speed, trail fade, glow, 2
//   (2,0) = the touch: where it began (xy), how many steps it's lasted
//           (z), and the finger count last step (w)
//   (3,0) = the menu item it began on (-1: none), 1 while the menu's
//           shown, 1 once it's moved (or had more fingers: not a tap), 0
//   (4,0) = how much the trail spreads a step, the spread's radius
//           (pixels), turning jitter (radians), new agents a pixel a step
//   (5,0) = the preset, 1 while a finger's dropping food, 0, 2
//   (6,0) = trail opacity, agent opacity, colour scheme, gamma
//   (7,0) = scale, zoom, and the zoom and scale the agents ran at this
//           step
//   (8,0) = how far (in cells) this step moved the colony to keep its
//           middle in the middle as zoom changed, and the half-cells
//           that rounding left over, for next time
// Distances and speeds are as at REFERENCE_HEIGHT and scale 1.
uniform vec3 pointers[10];
uniform int pointerCount;

#define PI 3.14159265
#define SETTINGS 9

#define SENSOR_ANGLE_RANGE vec2(0.02, 2.5)
#define SENSOR_DISTANCE_RANGE vec2(0.002, 0.08)
#define TURN_RANGE vec2(0.01, 1.6)
#define SPEED_RANGE vec2(0.05, 1.0)
#define FADE_RANGE vec2(0.002, 0.3)
#define GLOW_RANGE vec2(0.02, 5.0)
#define SCALE_RANGE vec2(0.25, 4.0)
#define ZOOM_RANGE vec2(1.0, 4.0)
#define GAMMA_RANGE vec2(0.25, 4.0)
#define PALETTES 10

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
#define BAR_HEIGHT 0.014
#define HUD_GAP 0.35
#define COLUMN_GAP 0.05
#define MENU_ROWS 6
#define BARS 12
#define PRESET_ROW 12
#define SCALE_BAR 0
#define ZOOM_BAR 6
#define COLOURS_BAR 7
#define GAMMA_BAR 10
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
};

Layout menuLayout() {
	Layout m;
	m.margin = HUD_MARGIN * iResolution.x;
	m.width = iResolution.x - 2.0 * m.margin;
	m.columnWidth = 0.5 * (m.width - COLUMN_GAP * iResolution.x);
	m.barHeight = max(2.0, BAR_HEIGHT * iResolution.y);
	m.gap = m.barHeight * HUD_GAP;
	// Letters as tall as the bars - unless the longest label wouldn't
	// fit across a column, then as big as fits.
	m.textHeight = min(m.barHeight, m.columnWidth / float(LABEL_COLUMNS) * 5.0 / 6.0);
	m.stride = m.barHeight + m.gap + m.textHeight + m.gap * 2.0;
	m.bottom = HUD_BOTTOM * iResolution.y;
	return m;
}

// What's at point p in the menu: a bar (0 to BARS - 1), PRESET_ROW, or
// -1. `along` is how far across it p is (0..1), and `local` is p from
// its bottom-left corner.
int menuItem(Layout m, vec2 p, out float along, out vec2 local) {
	vec2 q = p - vec2(m.margin, m.bottom);
	along = 0.0;
	local = vec2(0.0);
	if (q.y < 0.0 || q.y >= m.stride * float(MENU_ROWS + 1) || q.x < -m.margin || q.x > m.width + m.margin) {
		return -1;
	}
	int row = int(q.y / m.stride);
	local.y = q.y - float(row) * m.stride;
	if (row == MENU_ROWS) {
		local.x = q.x;
		along = clamp(q.x / m.width, 0.0, 1.0);
		return PRESET_ROW;
	}
	int column = q.x < 0.5 * m.width ? 0 : 1;
	local.x = q.x - (column == 0 ? 0.0 : m.width - m.columnWidth);
	along = clamp(local.x / m.columnWidth, 0.0, 1.0);
	return column * MENU_ROWS + row;
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
float sniff(vec2 pos, float angle, float reach, vec2 size) {
	vec2 p = mod(pos + vec2(cos(angle), sin(angle)) * reach, size);
	return texture(iChannel1, p / iResolution.xy).r;
}

vec4 randomAgent(vec2 fragCoord) {
	return vec4(
			hash(fragCoord + 0.31),
			hash(fragCoord + 7.17),
			hash(fragCoord + 3.73) * 2.0 * PI,
			1.0);
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
void setBar(int bar, float v, inout vec4 s0, inout vec4 s1, inout vec4 s6, inout vec4 s7) {
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
	// No settings marker: blank buffers (the first frame, Rewind, or a
	// resize cleared them) - start a new colony with default settings.
	// Not on iFrame: a run carries on whatever the frame count does.
	bool fresh = s0.w != 2.0 || s1.w != 2.0 || s5.w != 2.0;
	if (fresh) {
		preset(PRESET, s0, s1, s4);
		s2 = vec4(0.0);
		s3 = vec4(-1.0, 1.0, 0.0, 0.0);
		s5 = vec4(float(PRESET), 0.0, 0.0, 2.0);
		s6 = vec4(TRAIL_OPACITY, AGENT_OPACITY, float(COLOURS), GAMMA);
		s7 = vec4(SCALE, ZOOM, ZOOM, SCALE);
		s8 = vec4(0.0);
	}
	// The grid this step runs on, and the last step's.
	float cellPixels = cellSize(s7.x);
	ivec2 size = gridSize(s7.y, s7.x);
	ivec2 before = gridSize(s7.z, s7.w);
	// Zoom changed: this cell's agents come from `shift` cells along, half
	// the patch's change (with what last time's halving left over).
	ivec2 change = s7.y != s7.z ? before - size + ivec2(s8.zw) : ivec2(0);
	ivec2 shift = change / 2;
	ivec2 leftOver = s7.y != s7.z ? change - shift * 2 : ivec2(s8.zw);

	if (cell.y == 0 && cell.x < SETTINGS) {
		int count = pointerCount;
		int previous = int(s2.w + 0.5);
		vec2 finger = pointers[0].xy;
		Layout m = menuLayout();
		float along;
		vec2 local;
		s7.zw = vec2(s7.y, s7.x);
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
			if (count == 1 && item >= 0 && item < BARS) {
				// Along the bar it began on, wherever the finger's gone.
				float left = m.margin + (item < MENU_ROWS ? 0.0 : m.width - m.columnWidth);
				setBar(item, clamp((finger.x - left) / m.columnWidth, 0.0, 1.0), s0, s1, s6, s7);
			}
		} else if (previous > 0) {
			// Lifted: a quick tap off the menu shows or hides it.
			if (item < 0 && s3.z < 0.5 && s2.z < TAP_STEPS) {
				s3.y = 1.0 - s3.y;
			}
			s3.x = -1.0;
		}
		// Food from one finger on the colony, once it's not a tap.
		s5.y = count == 1 && item < 0 && (s3.z > 0.5 || s2.z >= TAP_STEPS) ? 1.0 : 0.0;
		s2.w = float(count);
		s8 = vec4(vec2(shift), vec2(leftOver));
		vec4 all[SETTINGS] = vec4[](s0, s1, s2, s3, s4, s5, s6, s7, s8);
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
		fragColor = hash(fragCoord + 0.7 + fract(iTime)) < DENSITY ? randomAgent(fragCoord + iTime) : vec4(0.0);
		return;
	}

	// Sensing in trail pixels; scaled down, speed (in cells) shrinks too.
	float pixels = pixelScale(s7.x);
	vec2 trailSize = vec2(before) * cellPixels;
	float sensorAngle = s0.x;
	float sensorDistance = s0.y * REFERENCE_HEIGHT * pixels;
	float turnAngle = s0.z;
	float speed = s1.x * min(pixels, 1.0);
	float jitter = s4.z;

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
			if (agent.w < 0.5 || agent.w > 1.5) {
				continue;
			}
			// Unwrapped, so "did it land in this cell" is a plain test.
			vec2 pos = vec2(from) + agent.xy;
			float heading = agent.z;
			float left = sniff(pos * cellPixels, heading + sensorAngle, sensorDistance, trailSize);
			float ahead = sniff(pos * cellPixels, heading, sensorDistance, trailSize);
			float right = sniff(pos * cellPixels, heading - sensorAngle, sensorDistance, trailSize);
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
				result = vec4(fract(next), mod(heading, 2.0 * PI), 1.0);
			}
		}
	}

	// Two agents landing in one pixel loses one - top the colony back up.
	if (result.w < 0.5 && hash(fragCoord + fract(iTime) * 113.0) < s4.w) {
		result = randomAgent(fragCoord + iTime);
	}
	fragColor = result;
}

//// Buffer B
// The trail: last step's, spread over its neighbours and faded, plus
// each agent's deposit - and food or repellent from fingers.
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
	bool written = s1.w == 2.0;
	if (!written) {
		s1 = vec4(1.0, 0.03, 1.2, 2.0);
		s4 = vec4(0.1, 1.0, 0.0, 0.0);
		s7 = vec4(1.0);
		s8 = vec4(0.0);
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
	float trail;
	ivec2 shift = ivec2(round(s8.xy * cellPixels));
	if (shift != ivec2(0)) {
		// Zoom moved the colony (see Buffer A): the trail moves with it,
		// unspread this once. Past the old patch's edge there was none.
		ivec2 from = cell + shift;
		trail = any(lessThan(from, ivec2(0))) ? 0.0 : texelFetch(iChannel1, from, 0).r * decay;
	} else {
		float centre = texelFetch(iChannel1, cell, 0).r;
		float sum = centre;
		for (int i = 0; i < 8; ++i) {
			float a = float(i) * 0.785398;
			vec2 d = vec2(cos(a), sin(a)) * radius;
			sum += texture(iChannel2, mod(fragCoord + d, size) / iResolution.xy).r;
			sum += texture(iChannel2, mod(fragCoord + 0.5 * d, size) / iResolution.xy).r;
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
			if (agent.w < 0.5 || agent.w > 1.5) {
				continue;
			}
			vec2 at = (from + agent.xy) * cellPixels;
			if (cellPixels < 1.01) {
				if (floor(at) == floor(fragCoord)) {
					trail += DEPOSIT;
				}
			} else {
				vec2 tent = max(1.0 - abs(fragCoord - at) / cellPixels, 0.0);
				trail += DEPOSIT * tent.x * tent.y;
			}
		}
	}

	if (s5.y > 0.5) {
		trail += FOOD * finger(fragCoord, 0, s7.z);
	} else if (pointerCount >= 4) {
		for (int i = 0; i < 10; ++i) {
			if (i < pointerCount) {
				trail -= REPEL * finger(fragCoord, i, s7.z);
			}
		}
	}

	// A bad value would spread over everything and stay: drop it.
	if (isnan(trail) || isinf(trail)) {
		trail = 0.0;
	}
	fragColor = vec4(clamp(trail, -1000.0, 1000.0), 0.0, 0.0, 1.0);
}
