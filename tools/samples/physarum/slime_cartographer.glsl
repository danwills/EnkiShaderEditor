// SLIME CARTOGRAPHER - a Physarum (slime mould) colony that maps its world
//
// Every agent sniffs the trail ahead-left, ahead and ahead-right of
// itself, turns towards the strongest scent, steps forward and leaves
// more trail behind. The trail spreads and fades. Out of that, veins and
// networks grow, merge and reorganise.
//
// FINGERS - gestures nudge a setting by how far they move, so lift and
// repeat to keep going (pinch, pinch, pinch...). The labelled bars at the
// bottom show each setting; the ones you're adjusting light up.
//   1 finger            drop food - the slime grows towards it
//   2 fingers  drag <->  sensor angle     - how wide it looks (red)
//              drag ^/v   sensor distance  - how far ahead (yellow)
//              pinch    turning          - how sharply it turns (green)
//   3 fingers  drag <->  speed            (blue)
//              drag ^/v   trail life       - how long trails last (purple)
//              pinch    glow             - how much trail you see (cyan)
//   4 fingers           repel - push the slime away
// PRESETS - tap the top row (< PRESET NAME >) on its right half for the
// next preset, its left half for the one before. The colony carries on
// and grows into the new settings. Each preset also sets how far the trail
// spreads (and how widely), how wobbly the turning is, and how fast new
// agents appear. Lace is the defines in Buffer A; the rest were found by
// searching for patterns like Softology's Physarum pictures
// (softologyblog.wordpress.com/2019/04/11/physarum-simulations), with
// tools/samples/physarum/search.py.
// Rewind starts a fresh colony with the PRESET define's settings.
//
// Buffer A: the agents (and settings), Buffer B: the trail.
// Needs float buffers. Runs best at 1/4 quality or below.

#define BACKGROUND vec3(0.010, 0.012, 0.030)
#define TRAIL_LOW vec3(0.05, 0.30, 0.45)
#define TRAIL_HIGH vec3(1.00, 0.85, 0.45)
#define AGENT_TINT vec3(0.6, 1.0, 0.8)
#define REPEL_TINT vec3(0.6, 0.05, 0.15)
// The trail fade a fresh colony starts with (keep in step with Buffer A).
#define FADE 0.03

// Setting ranges - keep in step with Buffer A.
#define SENSOR_ANGLE_RANGE vec2(0.02, 2.5)
#define SENSOR_DISTANCE_RANGE vec2(0.002, 0.08)
#define TURN_RANGE vec2(0.01, 1.6)
#define SPEED_RANGE vec2(0.05, 1.0)
#define FADE_RANGE vec2(0.002, 0.3)
#define GLOW_RANGE vec2(0.02, 5.0)

uniform sampler2D iChannel0;///buf:B;
uniform sampler2D iChannel1;///buf:A;min:n;mag:n;
uniform int pointerCount;

// THE SETTINGS DISPLAY - one full-width bar per setting with its label
// (and the gesture that adjusts it) above. The letters are a tiny bitmap
// font stored right here as numbers, so drawing them is a few integer
// operations, and only for pixels inside the display.
#define HUD_MARGIN 0.03
#define HUD_BOTTOM 0.08
#define BAR_HEIGHT 0.016
#define HUD_GAP 0.35

#define LABEL_COLUMNS 21
#define GLYPH_COUNT 27
// 5x5 glyphs, five rows each (top first, leftmost pixel in the
// 5x5 glyphs, five rows each (top first, leftmost pixel in the
// 5x5 glyphs, five rows each (top first, leftmost pixel in the
// highest bit) - small numbers, exact on any GPU:  ACDEFGHILNOPRSTUVW23<>^BMZ
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
		31, 2, 4, 8, 31);
// Bottom row first: GLOW | TRAIL LIFE | SPEED | TURNING | SENSOR DISTANCE | SENSOR ANGLE
const int LABELS[6 * LABEL_COLUMNS] = int[](
		6, 9, 11, 18, 0, 0, 0, 0, 0, 0, 0, 0, 0, 20, 5, 0, 12, 8, 10, 2, 7,
		15, 13, 1, 8, 9, 0, 9, 8, 5, 4, 0, 0, 0, 0, 0, 0, 20, 5, 0, 23, 17,
		14, 12, 4, 4, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 20, 5, 0, 21, 22,
		15, 16, 13, 10, 8, 10, 6, 0, 0, 0, 0, 0, 0, 19, 5, 0, 12, 8, 10, 2, 7,
		14, 4, 10, 14, 11, 13, 0, 3, 8, 14, 15, 1, 10, 2, 4, 0, 19, 5, 0, 23, 17,
		14, 4, 10, 14, 11, 13, 0, 1, 10, 6, 9, 4, 0, 0, 0, 0, 19, 5, 0, 21, 22);

// Where `value` sits in `range`, on a log scale (settings are adjusted
// multiplicatively), 0..1.
float level(float value, vec2 range) {
	return log(value / range.x) / log(range.y / range.x);
}

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

// The glyph at a column of a label: rows 0-5 from LABELS, row 6 the
// preset row, "<" PRESET NAME ">".
int labelGlyph(int row, int column, int presetIndex) {
	if (row < 6) {
		return LABELS[row * LABEL_COLUMNS + column];
	}
	if (column == 0) {
		return 21;
	}
	if (column == LABEL_COLUMNS - 1) {
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

// Whether label `row`'s text covers point p (relative to the label's
// bottom-left corner, `cell` x `height` per character).
bool labelPixel(int row, int presetIndex, vec2 p, float cell, float height) {
	int column = int(p.x / cell);
	if (p.x < 0.0 || p.y < 0.0 || p.y >= height || column >= LABEL_COLUMNS) {
		return false;
	}
	int glyph = labelGlyph(row, column, presetIndex);
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

vec3 settingsDisplay(vec2 fragCoord, vec4 s0, vec4 s1, vec4 s5, float glow, vec3 col) {
	float margin = HUD_MARGIN * iResolution.x;
	float width = iResolution.x - 2.0 * margin;
	float barHeight = max(2.0, BAR_HEIGHT * iResolution.y);
	float gap = barHeight * HUD_GAP;
	// Letters as tall as the bars - unless the longest label wouldn't
	// fit across the screen, then as big as fits.
	float cell = width / float(LABEL_COLUMNS);
	float textHeight = min(barHeight, cell * 5.0 / 6.0);
	float stride = barHeight + gap + textHeight + gap * 2.0;
	vec2 p = fragCoord - vec2(margin, HUD_BOTTOM * iResolution.y);
	if (p.x < 0.0 || p.x > width || p.y < 0.0 || p.y >= stride * 7.0) {
		return col;
	}
	int row = int(p.y / stride);
	float y = p.y - float(row) * stride;

	int presetIndex = int(s5.x + 0.5);
	float values[7] = float[](
			level(glow, GLOW_RANGE),
			1.0 - level(s1.y, FADE_RANGE),
			level(s1.x, SPEED_RANGE),
			level(s0.z, TURN_RANGE),
			level(s0.y, SENSOR_DISTANCE_RANGE),
			level(s0.x, SENSOR_ANGLE_RANGE),
			(float(presetIndex) + 1.0) / float(PRESETS));
	vec3 tints[7] = vec3[](
			vec3(0.4, 1.0, 1.0),
			vec3(0.8, 0.5, 1.0),
			vec3(0.4, 0.65, 1.0),
			vec3(0.4, 1.0, 0.5),
			vec3(1.0, 0.85, 0.3),
			vec3(1.0, 0.35, 0.35),
			vec3(1.0, 1.0, 1.0));
	bool lit = row == 6 ? s5.y > 0.5 : row >= 3 ? pointerCount == 2 : pointerCount == 3;
	float alpha = lit ? 0.9 : 0.3;
	vec3 tint = tints[row];

	if (y < barHeight) {
		bool filled = p.x < clamp(values[row], 0.0, 1.0) * width;
		return mix(col, filled ? tint : tint * 0.2, alpha);
	}
	if (labelPixel(row, presetIndex, vec2(p.x, y - barHeight - gap), cell, textHeight)) {
		// Labels a little stronger than idle bars, so they stay readable.
		return mix(col, mix(tint, vec3(1.0), 0.5), lit ? 0.95 : 0.55);
	}
	return col;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 uv = fragCoord / iResolution.xy;
	vec4 s0 = texelFetch(iChannel1, ivec2(0, 0), 0);
	vec4 s1 = texelFetch(iChannel1, ivec2(1, 0), 0);
	vec4 s5 = texelFetch(iChannel1, ivec2(5, 0), 0);
	float glow = s1.w == 2.0 ? s1.z : 1.2;

	vec4 b = texture(iChannel0, uv);
	// Trail builds up to about deposit / fade, so scale by the fade:
	// longer-lasting trails keep their detail instead of washing out.
	float fade = s1.w == 2.0 ? s1.y : FADE;
	float t = 1.0 - exp(-max(b.r, 0.0) * glow * fade / 0.07);
	vec3 col = mix(BACKGROUND, TRAIL_LOW, smoothstep(0.0, 0.5, t));
	col = mix(col, TRAIL_HIGH, smoothstep(0.45, 1.0, t));
	col += AGENT_TINT * b.g * 0.15;
	col += REPEL_TINT * clamp(-b.r * 0.05, 0.0, 1.0);

	col = settingsDisplay(fragCoord, s0, s1, s5, glow, col);
	fragColor = vec4(col, 1.0);
}

//// Buffer A
// Three steps a frame: the colony grows and settles faster.
///steps:3
// The agents. Each pixel holds at most one: xy = where inside the pixel
// it is, z = its heading, w = 1 if there is one. Every frame each pixel
// works out where the agents around it move to, and keeps the one (if
// any) that lands inside it - so agents never move more than a pixel.
//
// Pixels 0-5 of the bottom row hold settings instead (w = 2 marks them
// as written):
//   (0,0) = sensor angle, sensor distance, turn angle, 2
//   (1,0) = speed, trail fade, glow, 2
//   (2,0) = the current gesture's starting finger centre (xy), spread
//           (z), and finger count (w)
//   (3,0) = the settings being adjusted, as they were when it started
//   (4,0) = how much the trail spreads a step, the spread's radius
//           (pixels), turning jitter (radians), new agents a pixel a step
//   (5,0) = the preset, 1 while a finger's on the preset row (no food
//           then), 0, 2
// Each frame works out the settings afresh from the gesture's start, so
// 16-bit rounding can't creep in however slowly you move.
#define DENSITY 0.35
#define DRAG 2.5
#define PINCH 1.5
#define PI 3.14159265
#define SETTINGS 6
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

// The settings display's layout (as Image draws it), for the preset row.
#define HUD_MARGIN 0.03
#define HUD_BOTTOM 0.08
#define BAR_HEIGHT 0.016
#define HUD_GAP 0.35
#define LABEL_COLUMNS 21

#define SENSOR_ANGLE_RANGE vec2(0.02, 2.5)
#define SENSOR_DISTANCE_RANGE vec2(0.002, 0.08)
#define TURN_RANGE vec2(0.01, 1.6)
#define SPEED_RANGE vec2(0.05, 1.0)
#define FADE_RANGE vec2(0.002, 0.3)
#define GLOW_RANGE vec2(0.02, 5.0)

uniform sampler2D iChannel0;///buf:A;min:n;mag:n;
uniform sampler2D iChannel1;///buf:B;s:r;t:r;
uniform vec3 pointers[10];
uniform int pointerCount;

float hash(vec2 p) {
	vec3 p3 = fract(vec3(p.xyx) * 0.1031);
	p3 += dot(p3, p3.yzx + 33.33);
	return fract((p3.x + p3.y) * p3.z);
}

float sniff(vec2 pos, float angle, float reach) {
	vec2 p = pos + vec2(cos(angle), sin(angle)) * reach;
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

// Which half of the preset row (the display's top one) a touch is on:
// -1 left, 1 right, 0 neither. Generous above and below.
int presetTap(vec2 p) {
	float margin = HUD_MARGIN * iResolution.x;
	float width = iResolution.x - 2.0 * margin;
	float barHeight = max(2.0, BAR_HEIGHT * iResolution.y);
	float gap = barHeight * HUD_GAP;
	float cell = width / float(LABEL_COLUMNS);
	float textHeight = min(barHeight, cell * 5.0 / 6.0);
	float stride = barHeight + gap + textHeight + gap * 2.0;
	vec2 q = p - vec2(margin, HUD_BOTTOM * iResolution.y + 6.0 * stride);
	if (q.x < 0.0 || q.x > width || q.y < -2.0 * gap || q.y > stride + 2.0 * gap) {
		return 0;
	}
	return q.x < 0.5 * width ? -1 : 1;
}

// Scales `value` by e^amount, kept inside `range`.
float nudge(float value, float amount, vec2 range) {
	return clamp(value * exp(amount), range.x, range.y);
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 cell = ivec2(fragCoord);
	ivec2 size = ivec2(iResolution.xy);
	vec4 s0 = texelFetch(iChannel0, ivec2(0, 0), 0);
	vec4 s1 = texelFetch(iChannel0, ivec2(1, 0), 0);
	vec4 s2 = texelFetch(iChannel0, ivec2(2, 0), 0);
	vec4 s3 = texelFetch(iChannel0, ivec2(3, 0), 0);
	vec4 s4 = texelFetch(iChannel0, ivec2(4, 0), 0);
	vec4 s5 = texelFetch(iChannel0, ivec2(5, 0), 0);
	// No settings marker: first frame, rewound, or the buffer was just
	// cleared by a resize - start a new colony with default settings.
	bool fresh = iFrame < 2 || s0.w != 2.0 || s1.w != 2.0 || s5.w != 2.0;
	if (fresh) {
		preset(PRESET, s0, s1, s4);
		s2 = vec4(0.0);
		s3 = vec4(0.0);
		s5 = vec4(float(PRESET), 0.0, 0.0, 2.0);
	}

	if (cell.y == 0 && cell.x < SETTINGS) {
		// Finger centre (0..1 of the screen) and spread (average distance
		// from the centre, in screen heights).
		int count = pointerCount;
		vec2 centre = vec2(0.0);
		float spread = 0.0;
		if (count == 2 || count == 3) {
			for (int i = 0; i < 3; ++i) {
				if (i < count) {
					centre += pointers[i].xy;
				}
			}
			centre /= float(count);
			for (int i = 0; i < 3; ++i) {
				if (i < count) {
					spread += length(pointers[i].xy - centre);
				}
			}
			spread /= float(count) * iResolution.y;
			centre /= iResolution.xy;
		}
		bool adjusting = count == 2 || count == 3;
		if (adjusting && count == int(s2.w + 0.5)) {
			// Same gesture as last frame: settings = where they were when
			// it started, nudged by how far it's moved since.
			vec2 drag = (centre - s2.xy) * DRAG;
			float pinch = log(max(spread, 0.001) / max(s2.z, 0.001)) * PINCH;
			if (count == 2) {
				s0.x = nudge(s3.x, drag.x, SENSOR_ANGLE_RANGE);
				s0.y = nudge(s3.y, drag.y, SENSOR_DISTANCE_RANGE);
				s0.z = nudge(s3.z, pinch, TURN_RANGE);
			} else {
				s1.x = nudge(s3.x, drag.x, SPEED_RANGE);
				s1.y = nudge(s3.y, -drag.y, FADE_RANGE);
				s1.z = nudge(s3.z, pinch, GLOW_RANGE);
			}
		} else if (adjusting) {
			// A new gesture (or a finger joined/left): start from here.
			s2 = vec4(centre, spread, float(count));
			s3 = vec4(count == 2 ? s0.xyz : s1.xyz, 0.0);
		} else {
			// A finger landing on the preset row: the previous or next
			// one (the colony carries on and grows into it).
			if (count == 1 && int(s2.w + 0.5) == 0) {
				int side = presetTap(pointers[0].xy);
				if (side != 0) {
					int i = (int(s5.x + 0.5) + side + PRESETS) % PRESETS;
					preset(i, s0, s1, s4);
					s5.x = float(i);
					s5.y = 1.0;
				}
			}
			if (count == 0) {
				s5.y = 0.0;
			}
			s2.w = float(count);
		}
		vec4 all[SETTINGS] = vec4[](s0, s1, s2, s3, s4, s5);
		fragColor = all[cell.x];
		return;
	}

	if (fresh) {
		fragColor = hash(fragCoord + 0.7) < DENSITY ? randomAgent(fragCoord) : vec4(0.0);
		return;
	}

	float sensorAngle = s0.x;
	float sensorDistance = s0.y * iResolution.y;
	float turnAngle = s0.z;
	float speed = s1.x;
	float jitter = s4.z;

	vec4 result = vec4(0.0);
	float bestKey = -1.0;
	for (int dy = -1; dy <= 1; ++dy) {
		for (int dx = -1; dx <= 1; ++dx) {
			ivec2 from = cell + ivec2(dx, dy);
			ivec2 q = (from + size) % size;
			if (q.y == 0 && q.x < SETTINGS) {
				continue;
			}
			vec4 agent = texelFetch(iChannel0, q, 0);
			if (agent.w < 0.5 || agent.w > 1.5) {
				continue;
			}
			// Unwrapped, so "did it land in this pixel" is a plain test.
			vec2 pos = vec2(from) + agent.xy;
			float heading = agent.z;
			float left = sniff(pos, heading + sensorAngle, sensorDistance);
			float ahead = sniff(pos, heading, sensorDistance);
			float right = sniff(pos, heading - sensorAngle, sensorDistance);
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
			if (floor(next) == vec2(cell) && rnd > bestKey) {
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
// The trail: last frame's, spread over its neighbours and faded, plus a
// deposit wherever there's an agent - and food or repellent from fingers.
#define DEPOSIT 1.0
#define FOOD 6.0
#define REPEL 30.0
#define FINGER_SIZE 0.03
#define SETTINGS 6

uniform sampler2D iChannel0;///buf:A;min:n;mag:n;
uniform sampler2D iChannel1;///buf:B;min:n;mag:n;
uniform sampler2D iChannel2;///buf:B;s:r;t:r;
uniform vec3 pointers[10];
uniform int pointerCount;

float finger(vec2 fragCoord, int i) {
	vec2 d = fragCoord - pointers[i].xy;
	float r = FINGER_SIZE * iResolution.y;
	return exp(-dot(d, d) / (r * r));
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 cell = ivec2(fragCoord);
	ivec2 size = ivec2(iResolution.xy);
	vec4 s1 = texelFetch(iChannel0, ivec2(1, 0), 0);
	vec4 s4 = texelFetch(iChannel0, ivec2(4, 0), 0);
	vec4 s5 = texelFetch(iChannel0, ivec2(5, 0), 0);
	float decay = s1.w == 2.0 ? 1.0 - s1.y : 0.97;
	float diffuse = s1.w == 2.0 ? s4.x : 0.1;
	float radius = s1.w == 2.0 ? s4.y : 1.0;

	// The spread: two rings of eight (at the radius and half of it),
	// filtered between pixels, so any radius from 1 to 5 or so is smooth.
	float centre = texelFetch(iChannel1, cell, 0).r;
	float sum = centre;
	for (int i = 0; i < 8; ++i) {
		float a = float(i) * 0.785398;
		vec2 d = vec2(cos(a), sin(a)) * radius;
		sum += texture(iChannel2, (fragCoord + d) / iResolution.xy).r;
		sum += texture(iChannel2, (fragCoord + 0.5 * d) / iResolution.xy).r;
	}
	float trail = mix(centre, sum / 17.0, diffuse) * decay;

	vec4 agent = texelFetch(iChannel0, cell, 0);
	bool here = agent.w > 0.5 && agent.w < 1.5 && !(cell.y == 0 && cell.x < SETTINGS);
	if (here) {
		trail += DEPOSIT;
	}

	if (pointerCount == 1 && s5.y < 0.5) {
		trail += FOOD * finger(fragCoord, 0);
	} else if (pointerCount >= 4) {
		for (int i = 0; i < 10; ++i) {
			if (i < pointerCount) {
				trail -= REPEL * finger(fragCoord, i);
			}
		}
	}

	fragColor = vec4(clamp(trail, -1000.0, 1000.0), here ? 1.0 : 0.0, 0.0, 1.0);
}
