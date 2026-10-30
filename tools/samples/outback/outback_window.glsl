// OUTBACK WINDOW - the edge of an outback highway streaming past the
// passenger window: bitumen, the edge line, red gravel and dirt, spinifex,
// saltbush, mulga and the odd ghost gum, termite mounds, red boulders,
// guide posts, and red ranges far off under a deep blue sky.
//
// HOW IT WORKS
// One pass. The view looks out sideways (the car drives to the right, on
// the left of the road), so the road's along the screen and everything
// streams past at SPEED, faster the nearer it is. Each pixel's ray:
//   - The ground, laid out in strips by how far out it is: the lane, the
//     edge line, the sealed shoulder, the gravel, the dirt. Its noise is
//     box-filtered along the road by the motion blur (SHUTTER at SPEED) and
//     the pixel's size: an octave finer than that is averaged (streaks
//     along the road, quieter the more it averages), so near the car the
//     ground's a blur of streaks and far off it's crisp.
//   - Plants, rocks and posts: flat cut-outs standing in rows (LANES of
//     them, further apart the further out), one at most in each cell of a
//     row. Each is a set of spans across, at each height (a dome, a cone,
//     a canopy...), so the blur's just how much of the pixel's stretch of
//     road a span covers - exact, at any speed, with no samples. Spinifex
//     needles are a radiating square wave, integrated the same way.
//   - Shadows: the sun's off to the right, so things cast them left onto
//     the ground, found from the same spans.
//   - Two ranges far off, then the sky; haze with distance.

#define PI 3.14159265359

// How fast the car goes, in km/h.
#define SPEED 110.0
// How long the shutter's open, in seconds: the motion blur.
#define SHUTTER 0.01
// The view: across a side the screen's area, in degrees (so a portrait
// screen sees further down, a landscape one further along); where the
// horizon is, down from the top of the screen; how high it is, in metres;
// how much the road shakes it.
#define FOV 58.0
#define HORIZON 0.25
#define EYE 1.25
#define SHAKE 1.0

// The road out from the window, in metres: the edge line (0 for none), the
// end of the bitumen, the end of the gravel.
#define EDGE_LINE 1
#define LINE_Z 1.35
#define LINE_W 0.13
#define SEAL_Z 2.2
#define GRAVEL_Z 3.4
// Guide posts: how far out, how far apart.
#define POST_Z 3.9
#define POST_GAP 45.0
// The rows of plants and rocks: the first's distance, how many, each this
// much further out than the last.
#define NEAR_Z 4.4
#define LANES 40
#define LANE_RATIO 1.16
// How thick the bush is (0-1).
#define BUSH 0.6
// The sun: towards it (to the right, high, a little ahead).
#define SUN normalize(vec3(0.9, 1.0, 0.35))
// How far until haze takes half, in metres.
#define HAZE 12000.0

// Colours.
#define BITUMEN vec3(0.16, 0.155, 0.15)
#define CHIPS vec3(0.38, 0.35, 0.33)
#define PAINT vec3(0.86, 0.86, 0.82)
#define GRAVEL vec3(0.56, 0.33, 0.21)
#define DIRT vec3(0.68, 0.29, 0.13)
#define DUST vec3(0.80, 0.50, 0.32)
#define SPINIFEX vec3(0.86, 0.72, 0.38)
#define SPINIFEX_CORE vec3(0.56, 0.57, 0.34)
#define SALTBUSH vec3(0.55, 0.58, 0.48)
#define MULGA vec3(0.34, 0.38, 0.23)
#define GUM_LEAVES vec3(0.44, 0.54, 0.26)
#define GUM_BARK vec3(0.93, 0.91, 0.86)
#define BARK vec3(0.27, 0.22, 0.19)
#define MOUND vec3(0.66, 0.32, 0.17)
#define ROCK vec3(0.50, 0.20, 0.11)
#define RANGE vec3(0.72, 0.33, 0.18)
#define SKY_TOP vec3(0.16, 0.36, 0.74)
#define SKY_LOW vec3(0.70, 0.79, 0.88)
#define HAZE_COLOUR vec3(0.68, 0.72, 0.82)
// The sunny side of things, the shady side, and shadow.
#define LIT 1.15
#define SHADY 0.68
#define SHADOW 0.45

// ---- Hashes and noise. Float hashes (Hoskins' without sine): the phone's
// compiler mangles big integer constants.
float hash12(vec2 p) {
	vec3 p3 = fract(vec3(p.xyx) * 0.1031);
	p3 += dot(p3, p3.yzx + 33.33);
	return fract((p3.x + p3.y) * p3.z);
}

vec4 hash42(vec2 p) {
	vec4 p4 = fract(vec4(p.xyxy) * vec4(0.1031, 0.1030, 0.0973, 0.1099));
	p4 += dot(p4, p4.wzxy + 33.33);
	return fract((p4.xxyz + p4.yzzw) * p4.zywx);
}

float noise(vec2 p) {
	vec2 i = floor(p), f = fract(p);
	f = f * f * (3.0 - 2.0 * f);
	return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), f.x),
			mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), f.x), f.y);
}

// Noise along the road (x) and out from it (z), averaged over wx along and
// wz across: an octave finer than wx along is stretched to wx (a streak) and
// quietened by how many it averages; finer than wz across, it fades out.
// From -1 to 1, roughly.
float streaks(vec2 p, float f, int octaves, float wx, float wz) {
	float sum = 0.0, amp = 0.5;
	for (int i = 0; i < octaves; i++) {
		float fx = min(f, 1.0 / wx);
		float a = amp * sqrt(fx / f) * (1.0 - smoothstep(0.25, 1.0, f * wz));
		sum += a * (noise(vec2(p.x * fx, p.y * f) + float(i) * 17.3) * 2.0 - 1.0);
		f *= 2.0;
		amp *= 0.5;
	}
	return sum;
}

// A wavy boundary's offset (amp at frequency f, two octaves), with what the
// blur along the road smooths out of it, and how much that softens it.
vec2 ragged(float x, float f, float amp, float wx, float seed) {
	float a1 = amp / max(1.0, f * wx), a2 = 0.4 * amp / max(1.0, 4.0 * f * wx);
	float n = a1 * (noise(vec2(x * f, seed)) * 2.0 - 1.0) + a2 * (noise(vec2(x * f * 4.0, seed + 3.0)) * 2.0 - 1.0);
	return vec2(n, (amp - a1) + (0.4 * amp - a2));
}

// ---- Spans: how much of a stretch of road (a0 to a1) a cut-out covers at
// one height, by its left (shady) and right (sunny) halves.
float overlap(float a0, float a1, float b0, float b1) {
	return max(0.0, min(a1, b1) - max(a0, b0));
}

// A span from c - w to c + w of colour col, its halves shaded, into acc:
// colour times length, and length.
void span(inout vec4 acc, float a0, float a1, float c, float w, vec3 col) {
	float l = overlap(a0, a1, c - w, c), r = overlap(a0, a1, c, c + w);
	acc += vec4(col * (SHADY * l + LIT * r), l + r);
}

// The length of road from a to b covered by needles: a square wave in
// u = x s + phase, duty d of each period.
float wave(float u, float d) {
	return floor(u) * d + min(fract(u), d);
}
float needles(float a, float b, float s, float phase, float d) {
	if (b <= a || d <= 0.0) {
		return 0.0;
	}
	return (wave(b * s + phase, d) - wave(a * s + phase, d)) / s;
}
// Needles from b0 to b1 (within a0 to a1), into acc.
void needleSpan(inout vec4 acc, float a0, float a1, float b0, float b1, float s, float phase, float d, vec3 col, float shade) {
	float l = needles(max(a0, b0), min(a1, b1), s, phase, d);
	acc += vec4(col * shade * l, l);
}

// ---- The cut-outs. Each adds its spans at height y over a0 to a1
// (metres from its middle); seed varies it. Their kinds:
#define NONE 0
#define SPINIFEX_CLUMP 1
#define BUSH_CLUMP 2
#define MULGA_TREE 3
#define GHOST_GUM 4
#define TERMITES 5
#define BOULDER 6
#define TUFT 7
#define POST 8

// A hummock of spinifex: a grey-green dome bristling with golden needles,
// which radiate from below its middle. A tuft is just the needles.
void spinifex(inout vec4 acc, float a0, float a1, float y, float r, float h, vec4 seed, bool tuft) {
	float he = h * (tuft ? 1.0 : 1.7);
	if (y >= he) {
		return;
	}
	float core = tuft ? 0.0 : h;
	float wc = y < core ? r * sqrt(1.0 - (y / core) * (y / core)) : 0.0;
	float we = r * (tuft ? 1.0 : 1.2) * sqrt(1.0 - (y / he) * (y / he));
	if (wc > 0.0) {
		vec3 col = mix(SPINIFEX_CORE * 0.55, SPINIFEX_CORE, smoothstep(0.0, core, y));
		col = mix(col, SPINIFEX * 0.8, 0.35 * seed.w);
		span(acc, a0, a1, 0.0, wc, col);
	}
	// Needles: spaced 0.04 of their slope out from below the middle.
	float s = 1.0 / (0.04 * (y + 0.5 * h));
	float d = mix(0.65, 0.2, y / he);
	vec3 col = mix(SPINIFEX, vec3(0.72, 0.68, 0.55), seed.z) * mix(0.75, 1.05, y / he);
	float phase = seed.x * 7.0;
	needleSpan(acc, a0, a1, -we, -wc, s, phase, d, col, SHADY);
	needleSpan(acc, a0, a1, wc, we, s, phase, d, col, LIT);
}

// A lumpy outline's half width: an ellipse (half width r, from y0 to y1),
// lumpy by noise up it.
float lumpy(float y, float y0, float y1, float r, float lumps, float seed) {
	float t = (y - 0.5 * (y0 + y1)) / (0.5 * (y1 - y0));
	if (abs(t) >= 1.0) {
		return 0.0;
	}
	return r * pow(1.0 - t * t, 0.4) * (1.0 + lumps * (noise(vec2(y * 3.0 / r, seed * 50.0)) * 2.0 - 1.0));
}

// A saltbush or bluebush: a grey-green ball, gappy.
void bush(inout vec4 acc, float a0, float a1, float y, float r, float h, vec4 seed) {
	float w = lumpy(y, -0.1 * h, h, r, 0.18, seed.x);
	vec3 col = mix(SALTBUSH, SALTBUSH * vec3(0.85, 1.0, 1.05), seed.z) * mix(0.55, 1.0, smoothstep(0.0, h, y));
	vec4 b = vec4(0.0);
	span(b, a0, a1, 0.0, w, col);
	acc += b * 0.92;
}

// A mulga (or, darker and droopier, a desert oak): a few thin leaning
// trunks under a ragged canopy. A ghost gum: a white trunk forking under a
// bright, open canopy.
void tree(inout vec4 acc, float a0, float a1, float y, float r, float h, vec4 seed, bool gum) {
	float bottom = h * (gum ? 0.4 : (seed.w < 0.3 ? 0.25 : 0.45));
	float w = lumpy(y, bottom, h, r, 0.3, seed.x);
	// The canopy's middle wanders up it.
	float c = r * 0.25 * (noise(vec2(y * 1.3, seed.y * 40.0)) * 2.0 - 1.0);
	vec3 leaves = gum ? GUM_LEAVES : (seed.w < 0.3 ? MULGA * 0.7 : MULGA);
	leaves *= mix(0.7, 1.05, smoothstep(bottom, h, y));
	vec4 can = vec4(0.0);
	span(can, a0, a1, c, w, leaves);
	acc += can * (gum ? 0.8 : 0.9);
	// Trunks, up into the canopy.
	if (y < mix(bottom, h, 0.6)) {
		float lean = (seed.z - 0.5) * 0.5;
		if (gum) {
			span(acc, a0, a1, lean * y, mix(0.16, 0.08, y / h) * h / 8.0, GUM_BARK);
		} else {
			span(acc, a0, a1, lean * y, 0.05, BARK);
			span(acc, a0, a1, -lean * y * 1.3 + 0.1, 0.04, BARK);
		}
	}
}

// A termite mound: a lumpy red spire.
void termites(inout vec4 acc, float a0, float a1, float y, float r, float h, vec4 seed) {
	if (y >= h) {
		return;
	}
	float w = r * pow(1.0 - y / h, 0.6) * (1.0 + 0.2 * (noise(vec2(y * 3.0, seed.x * 50.0)) * 2.0 - 1.0));
	span(acc, a0, a1, 0.15 * r * (noise(vec2(y * 1.5, seed.y * 30.0)) - 0.5), w, MOUND * mix(0.85, 1.05, seed.z));
}

// A red boulder: blocky, with a dark varnish low down.
void boulder(inout vec4 acc, float a0, float a1, float y, float r, float h, vec4 seed) {
	if (y >= h) {
		return;
	}
	float t = y / h;
	float w = r * pow(1.0 - t * t, 0.3) * (1.0 + 0.12 * (noise(vec2(y * 5.0, seed.x * 50.0)) * 2.0 - 1.0));
	vec3 col = ROCK * mix(0.8, 1.15, seed.z) * mix(0.6, 1.0, smoothstep(0.0, 0.5, t));
	span(acc, a0, a1, 0.1 * r * (seed.y - 0.5), w, col);
}

// A guide post: white, a red reflector near the top.
void post(inout vec4 acc, float a0, float a1, float y) {
	if (y < 1.0) {
		bool reflector = y > 0.8 && y < 0.93;
		span(acc, a0, a1, 0.0, 0.05, reflector ? vec3(0.95, 0.06, 0.04) : PAINT * mix(0.75, 1.0, y));
	}
}

// What stands in cell `cell` of row `lane` (z its distance out): its kind,
// its middle along the road, its distance out, and its size (half width,
// height) and seed.
int standing(int lane, float cell, float cw, float z, out float x, out float zj, out vec2 size, out vec4 seed) {
	seed = hash42(vec2(cell, float(lane) * 7.31));
	vec4 more = hash42(vec2(cell + 0.5, float(lane) * 3.17 + 11.0));
	x = (cell + 0.5 + 0.6 * (seed.x - 0.5)) * cw;
	zj = z * (1.0 + (LANE_RATIO - 1.0) * (seed.y - 0.5) * 0.9);
	// Patches thick and thin, slowly changing along the road.
	float thick = BUSH * (0.4 + 1.2 * noise(vec2(x / 60.0, float(lane) * 0.37)));
	float k = more.x / max(thick, 1e-3);
	float spin = 0.8, bush = 0.15, trees = z > 15.0 ? 0.12 : 0.0, gums = z > 30.0 ? 0.02 : 0.0;
	float mounds = z > 6.0 ? 0.05 : 0.0, rocks = 0.06, tufts = z < 12.0 ? 0.5 : 0.0;
	// Mulga thickets here and there.
	trees *= 0.3 + 2.5 * smoothstep(0.55, 0.8, noise(vec2(x / 300.0, 5.0)));
	if ((k -= tufts) < 0.0) {
		size = vec2(0.15 + 0.2 * more.y, 0.2 + 0.25 * more.z);
		return TUFT;
	}
	if ((k -= spin) < 0.0) {
		size = vec2(0.4 + 0.7 * more.y, 0.25 + 0.3 * more.z);
		return SPINIFEX_CLUMP;
	}
	if ((k -= bush) < 0.0) {
		size = vec2(0.4 + 0.5 * more.y, 0.5 + 0.7 * more.z);
		return BUSH_CLUMP;
	}
	if ((k -= trees) < 0.0) {
		size = vec2(1.2 + 1.5 * more.y, 3.0 + 3.0 * more.z);
		size = min(size, vec2(cw, 1e9));
		return MULGA_TREE;
	}
	if ((k -= gums) < 0.0) {
		size = vec2(2.0 + 1.5 * more.y, 6.0 + 4.0 * more.z);
		size = min(size, vec2(cw, 1e9));
		return GHOST_GUM;
	}
	if ((k -= mounds) < 0.0) {
		size = vec2(0.25 + 0.3 * more.y, 0.7 + 1.3 * more.z);
		return TERMITES;
	}
	if ((k -= rocks) < 0.0) {
		size = vec2(0.2 + 0.6 * more.y, 0.15 + 0.5 * more.z * more.y);
		return BOULDER;
	}
	return NONE;
}

// The spans of a cut-out of that kind, at height y, over a0 to a1.
void cutOut(inout vec4 acc, int kind, float a0, float a1, float y, vec2 size, vec4 seed) {
	if (y < 0.0) {
		return;
	}
	if (kind == SPINIFEX_CLUMP) spinifex(acc, a0, a1, y, size.x, size.y, seed, false);
	else if (kind == TUFT) spinifex(acc, a0, a1, y, size.x, size.y, seed, true);
	else if (kind == BUSH_CLUMP) bush(acc, a0, a1, y, size.x, size.y, seed);
	else if (kind == MULGA_TREE) tree(acc, a0, a1, y, size.x, size.y, seed, false);
	else if (kind == GHOST_GUM) tree(acc, a0, a1, y, size.x, size.y, seed, true);
	else if (kind == TERMITES) termites(acc, a0, a1, y, size.x, size.y, seed);
	else if (kind == BOULDER) boulder(acc, a0, a1, y, size.x, size.y, seed);
}

// Leaves, grass and grain over a cut-out: brightness and gaps, from noise
// at the pixel's stretch of road (lx, w wide) and height y, quietened as
// the blur averages it.
vec2 texture2(int kind, float lx, float w, float y, vec4 seed) {
	float f = kind == BUSH_CLUMP ? 12.0 : kind == MULGA_TREE || kind == GHOST_GUM ? 5.0 : kind == BOULDER ? 9.0 : kind == TERMITES ? 7.0 : 18.0;
	float n = noise(vec2(lx * f, y * f + seed.x * 30.0)) * 2.0 - 1.0;
	n += 0.5 * (noise(vec2(lx * f * 2.7, y * f * 2.7 + seed.y * 30.0)) * 2.0 - 1.0);
	n /= max(1.0, f * w);
	bool leafy = kind == BUSH_CLUMP || kind == MULGA_TREE || kind == GHOST_GUM;
	return vec2(1.0 + 0.45 * n, leafy ? clamp(1.0 + 0.8 * n, 0.0, 1.0) : 1.0);
}

float laneZ(int lane) {
	return NEAR_Z * pow(LANE_RATIO, float(lane));
}
float cellWidth(float z) {
	return 2.6 + 0.12 * z;
}

// How much sun reaches the ground at x, z (over wx along the road): the
// cut-outs a little further out cast their spans' shadows onto it.
float sunlight(float x, float z, float wx) {
	float shade = 0.0;
	// Things up to 8 m tall: their shadows reach this far towards the car.
	float reach = 8.0 * SUN.z / SUN.y;
	int first = int(floor(log(max(z, 1e-3) / NEAR_Z) / log(LANE_RATIO)));
	for (int lane = max(first - 1, 0); lane < min(first + 8, LANES); lane++) {
		float lz = laneZ(lane);
		if (lz * (1.0 - 0.5 * (LANE_RATIO - 1.0)) > z + reach) {
			break;
		}
		float cw = cellWidth(lz);
		// Where along the road the shadow's coming from, for a 1 m height.
		float c0 = floor((x + SUN.x / SUN.z * (lz - z)) / cw);
		for (int i = -1; i <= 1; i++) {
			float ox, zj;
			vec2 size;
			vec4 seed;
			int kind = standing(lane, c0 + float(i), cw, lz, ox, zj, size, seed);
			if (kind == NONE) {
				continue;
			}
			// The height whose shadow falls here (things have some depth:
			// shadows start under them).
			float y = (zj + 0.4 * size.x - z) * SUN.y / SUN.z;
			float xs = x + y * SUN.x / SUN.y - ox;
			vec4 acc = vec4(0.0);
			cutOut(acc, kind, xs - 0.5 * wx, xs + 0.5 * wx, y, size, seed);
			shade = max(shade, min(acc.w / wx, 1.0));
		}
	}
	return 1.0 - (1.0 - SHADOW) * shade;
}

// The ground at x along the road and z out, averaged over wx along and wz
// across.
vec3 ground(float x, float z, float wx, float wz) {
	vec2 p = vec2(x, z);
	// Bitumen: chip seal, a darker polished wheel path, red dust towards
	// the edge.
	float chips = streaks(p, 25.0, 4, wx, wz);
	vec3 col = mix(BITUMEN, CHIPS, clamp(0.35 + 0.9 * chips, 0.0, 1.0));
	col *= 1.0 - 0.25 * smoothstep(0.4, 0.0, abs(z - 0.75));
	vec2 seal = ragged(x, 2.5, 0.05, wx, 3.0);
	float dust = smoothstep(LINE_Z, seal.x + SEAL_Z, z) * (0.5 + 0.5 * streaks(p, 2.0, 2, wx, wz));
	col = mix(col, DIRT * 0.7, 0.45 * dust);
	// The edge line, worn.
	if (EDGE_LINE > 0) {
		float soft = 0.5 * wz + 0.004;
		float line = smoothstep(LINE_Z - soft, LINE_Z + soft, z) * smoothstep(LINE_Z + LINE_W + soft, LINE_Z + LINE_W - soft, z);
		float wear = clamp(0.85 + 0.5 * streaks(p, 12.0, 3, wx, wz), 0.0, 1.0);
		col = mix(col, PAINT * (0.9 + 0.1 * chips), line * wear);
	}
	// The gravel shoulder: red-brown, quartz and ironstone pebbles.
	float softSeal = seal.y + 0.5 * wz + 0.01;
	float gravel = smoothstep(SEAL_Z + seal.x - softSeal, SEAL_Z + seal.x + softSeal, z);
	float pebbles = streaks(p + 40.0, 18.0, 3, wx, wz);
	vec3 g = GRAVEL * (0.85 + 0.4 * pebbles);
	g = mix(g, vec3(0.78, 0.70, 0.64), 0.6 * clamp(streaks(p + 90.0, 30.0, 2, wx, wz) * 2.0 - 0.5, 0.0, 1.0));
	col = mix(col, g, gravel);
	// The dirt: red, dusty patches, darker patches.
	vec2 edge = ragged(x, 1.2, 0.12, wx, 9.0);
	float softDirt = edge.y + 0.5 * wz + 0.05;
	float dirt = smoothstep(GRAVEL_Z + edge.x - softDirt, GRAVEL_Z + edge.x + softDirt, z);
	float patches = streaks(p * 0.3, 1.0, 3, wx * 0.3, wz * 0.3);
	vec3 d = mix(DIRT, DUST, clamp(0.4 + patches, 0.0, 1.0));
	d *= 0.9 + 0.3 * streaks(p + 7.0, 10.0, 3, wx, wz);
	// Pebbles: ironstone dark, quartz pale.
	float stones = streaks(p + 23.0, 35.0, 2, wx, wz);
	d *= 1.0 - 0.5 * smoothstep(0.15, 0.4, stones);
	d = mix(d, vec3(0.85, 0.74, 0.64), 0.6 * smoothstep(0.2, 0.45, -stones));
	// Further out the dirt's flecked with plants too small to stand up.
	d = mix(d, mix(SPINIFEX_CORE, SPINIFEX, 0.5) * 0.8, 0.3 * smoothstep(10.0, 80.0, z) * (0.6 + 0.4 * noise(p / 40.0)));
	col = mix(col, d, dirt);
	return col;
}

// The far ranges: a height at x (m along), for range 0 (near) or 1 (far).
float rangeHeight(float x, float which) {
	float big = noise(vec2(x / 3000.0, which * 10.0)) * 0.6 + noise(vec2(x / 1200.0, which * 10.0 + 3.0)) * 0.4;
	// Mesas and ridges: flat tops, steep sides.
	float h = smoothstep(0.42, 0.62, big) * (which > 0.5 ? 420.0 : 260.0);
	h += 40.0 * noise(vec2(x / 300.0, which * 10.0 + 5.0)) + 12.0 * noise(vec2(x / 70.0, which));
	return h - 20.0;
}

vec3 rangeColour(float x, float y, float which) {
	// Facing the sun or away, from the slope.
	float slope = rangeHeight(x + 30.0, which) - rangeHeight(x - 30.0, which);
	float light = clamp(0.85 + slope / 120.0, 0.55, 1.2);
	// Gullies running down, bands of strata.
	float gully = noise(vec2(x / 25.0, y / 400.0));
	float band = noise(vec2(x / 900.0, y / 18.0));
	vec3 col = RANGE * light * (0.75 + 0.35 * gully) * (0.9 + 0.2 * band);
	// Scree and scrub at the foot.
	return mix(col, mix(DIRT, MULGA, 0.4), 0.5 * smoothstep(40.0, 0.0, y));
}

vec3 haze(vec3 col, float dist) {
	return mix(col, HAZE_COLOUR, 1.0 - exp2(-dist / HAZE));
}

// Into front-to-back colour: what's still showing through takes this.
void over(inout vec4 acc, vec3 col, float a) {
	acc.rgb += (1.0 - acc.a) * a * col;
	acc.a += (1.0 - acc.a) * a;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 res = iResolution.xy;
	float spread = tan(radians(FOV) * 0.5);
	float side = sqrt(res.x * res.y);
	vec2 uv = (fragCoord - 0.5 * res) / (0.5 * side) * spread;
	// A pixel's angle, roughly.
	float pa = 2.0 * spread / side;
	float speed = SPEED / 3.6;
	// Wrapped every 1000 s (fine detail needs float precision).
	float t = mod(iTime, 1000.0);
	float travel = t * speed;
	float blur = speed * SHUTTER;

	// The road's bumps.
	float eye = EYE + SHAKE * 0.006 * (noise(vec2(t * 9.0, 0.0)) - 0.5);
	float pitch = atan((1.0 - 2.0 * HORIZON) * res.y / side * spread)
			+ radians(SHAKE * 0.25 * (noise(vec2(t * 6.0, 4.0)) - 0.5));
	// The ray: x along the road, y up, z out (not normalised: z near 1).
	vec3 rd = vec3(uv.x, uv.y * cos(pitch) - sin(pitch), uv.y * sin(pitch) + cos(pitch));

	vec4 acc = vec4(0.0);
	// The guide posts.
	{
		float k = POST_Z / rd.z;
		float y = eye + rd.y * k;
		if (y > 0.0 && y < 1.0) {
			float x = rd.x * k + travel;
			float lx = x - (floor(x / POST_GAP + 0.5)) * POST_GAP;
			float w = max(blur, k * pa);
			vec4 s = vec4(0.0);
			post(s, lx - 0.5 * w, lx + 0.5 * w, y);
			if (s.w > 0.0) {
				over(acc, s.rgb / s.w, min(s.w / w, 1.0));
			}
		}
	}
	// The rows of plants and rocks, near to far.
	for (int lane = 0; lane < LANES; lane++) {
		float z = laneZ(lane);
		float k = z / rd.z;
		float yl = eye + rd.y * k;
		// Below the ground here (and so beyond), or above the tallest.
		if (yl < -0.3 * z * (LANE_RATIO - 1.0) || (rd.y > 0.0 && yl > 12.0) || acc.a > 0.995) {
			break;
		}
		float cw = cellWidth(z);
		float c0 = floor((rd.x * k + travel) / cw);
		for (int i = -1; i <= 1; i++) {
			float ox, zj;
			vec2 size;
			vec4 seed;
			int kind = standing(lane, c0 + float(i), cw, z, ox, zj, size, seed);
			if (kind == NONE) {
				continue;
			}
			float kj = zj / rd.z;
			float y = eye + rd.y * kj;
			if (y < 0.0) {
				continue;
			}
			float lx = rd.x * kj + travel - ox;
			float w = max(blur, kj * pa);
			vec4 s = vec4(0.0);
			cutOut(s, kind, lx - 0.5 * w, lx + 0.5 * w, y, size, seed);
			if (s.w > 0.0) {
				vec2 tex = texture2(kind, lx, w, y, seed);
				over(acc, haze(s.rgb / s.w * tex.x, zj), min(s.w / w, 1.0) * tex.y);
			}
		}
	}

	// The ground, or else the ranges and the sky.
	vec3 back;
	if (rd.y < -1e-4) {
		float k = eye / -rd.y;
		float z = rd.z * k;
		float x = rd.x * k + travel;
		float wx = max(blur, k * pa);
		float wz = eye * pa / (rd.y * rd.y);
		back = ground(x, z, wx, wz) * (z < 200.0 ? sunlight(x, z, wx) : 1.0);
		back = haze(back, z);
	} else {
		float e = rd.y / length(rd);
		back = mix(SKY_LOW, SKY_TOP, pow(clamp(e * 2.5, 0.0, 1.0), 0.6));
	}
	// The ranges, beyond: far one first, as the near hides it.
	for (int r = 1; r >= 0; r--) {
		float z = r == 1 ? 16000.0 : 5500.0;
		float k = z / rd.z;
		float y = eye + rd.y * k;
		float x = rd.x * k + travel;
		float h = rangeHeight(x, float(r));
		float a = smoothstep(h + k * pa, h - k * pa, y) * step(0.0, y);
		if (rd.y < -1e-4 && eye / -rd.y < k) {
			a = 0.0;
		}
		back = mix(back, haze(rangeColour(x, y, float(r)), z), a);
	}
	over(acc, back, 1.0);
	fragColor = vec4(acc.rgb, 1.0);
}
