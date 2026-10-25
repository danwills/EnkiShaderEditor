// Rock Cliff - a weathered cliff face: blocky strata split by deep joints,
// angular chipped facets, rust and water stains, moss and grass on the
// ledges, and a shelf of rock along the top under its grass, against a
// pale sky.
//
// One finger drags the view (and flings it); two fingers pinch to zoom and
// turn it, about the point between them (after the gesture handling of
// PointerDiagnosticsXformTracking).
//
// How it works:
//   Buffer A - the view (offset, zoom, angle) and the gesture in progress,
//              in its top row.
//   Buffer B - the cliff where each pixel looks: its height (relief, in
//              cliff units), its colour, how deep in a crack it is, and
//              whether it's rock, plant or sky. Worked out only when the
//              view has moved: while it's still, the last frame's is kept.
//   Buffer C - a soft sun shadow marched across those heights, and
//              ambient occlusion from the heights round about.
//   Buffer D - lighting: a normal from the neighbours' heights, the
//              shadow smoothed a little, moss on what faces up.
//              C and D too are worked out only when the view's moved.
//   Image    - Buffer D, dithered: a still cliff costs next to nothing.
//
// The cliff is a height field: rows of strata (each sticking out more or
// less, its blocks leaning so their tops overhang), split into blocks by
// slanting joints and long vertical ones, every edge rounded over and
// every joint a narrow, deep groove; big masses and block-sized faces
// (each cell of a Voronoi diagram a plane of its own, so neighbouring
// faces meet in steps and creases, slightly rounded), chips and grit.

uniform sampler2D iChannel0; ///buf:D;

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 size = ivec2(iResolution.xy);
	vec3 col = texelFetch(iChannel0, clamp(ivec2(fragCoord), ivec2(0), size - ivec2(1, 2)), 0).rgb;
	// Dither, as the original.
	col += (hash2(ivec2(fragCoord) + ivec2(iFrame * 17, 0)) - 0.5) * (1.5 / 255.0);
	fragColor = vec4(col, 1.0);
}

//// Common
uniform vec3 pointers[10];
uniform int pointerCount;

#define PI 3.14159265359
#define TAU 6.28318530718

// ---- The view and the gesture (Buffer A), after
// PointerDiagnosticsXformTracking.
#define MIN_ZOOM 0.1
#define MAX_ZOOM 20.0
// How much a fling keeps of its speed each frame.
#define INERTIA 0.9
// How tall the screen is in cliff units, at zoom 1.
#define VIEW 2.4
// Where the screen's middle starts on the cliff (its top edge is at y = 1).
#define START vec2(0.0, 0.1)

// ---- The cliff (Buffer B).
// Where the cliff's top edge is.
#define TOP 1.0
// Strata: how tall a row is on average.
#define ROW 0.3
// Long vertical joints: how far apart on average.
#define MASTER 0.62
// The joints' grooves: how deep, and the bevel along every edge.
#define CRACK_DEPTH 0.16
#define BEVEL 0.045
// The shelf along the top: how thick (on average) and how far it sticks out.
#define SHELF 0.1
#define SHELF_OUT 0.2
// How far (in cliff units, on average) facets' meetings are rounded off.
#define ROUNDING 0.012
// The grass: blades' spacing, and the soil lip under them.
#define BLADE 0.0013
#define SOIL 0.014

// ---- Lighting (Image).
#define SUN vec3(-0.55, 0.62, 0.6)
#define SUN_COLOUR vec3(1.75, 1.6, 1.38)
#define SKY_COLOUR vec3(0.5, 0.54, 0.6)
#define BOUNCE_COLOUR vec3(0.18, 0.15, 0.12)
#define SHADOW_STEPS 28
// The farthest a shadow's cast from, in cliff units.
#define SHADOW_REACH 0.6
#define AO_STRENGTH 1.15
#define CRACK_DARK 0.45
// The share of pixel-sized crystals that glint, and how bright; their
// flares: a soft halo (halation) and thin spikes, in pixels.
#define GLINT_DENSITY 0.04
#define GLINT 1.4
#define HALO 0.035
#define SPIKES 0.03
#define SPIKE_LENGTH 20
// The darkest ambient occlusion gets: deep crevices still show a little.
#define AO_FLOOR 0.28
#define EXPOSURE 1.25

mat2 rot(float a) {
	return mat2(cos(a), sin(a), -sin(a), cos(a));
}

// The view: offset (pixels), zoom, angle - from Buffer A's top row.
vec4 viewState(sampler2D a) {
	vec4 v = texelFetch(a, ivec2(0, int(iResolution.y) - 1), 0);
	return v.z > 0.0 ? v : vec4(0.0, 0.0, 1.0, 0.0);
}

// A screen pixel's place on the cliff.
vec2 toCliff(vec2 fragCoord, vec4 view) {
	vec2 p = rot(-view.w) * (fragCoord - (iResolution.xy * 0.5 + view.xy));
	return p / (view.z * iResolution.y) * VIEW + START;
}

// Hashes without big integer constants (Dave Hoskins' "hash without
// sine"): the test phone's GPU compiler mangles constants past 2^24.
float hash12(vec2 p) {
	vec3 p3 = fract(vec3(p.xyx) * 0.1031);
	p3 += dot(p3, p3.yzx + 33.33);
	return fract((p3.x + p3.y) * p3.z);
}

float hash1(int a) {
	return hash12(vec2(float(a), 17.31));
}

float hash2(ivec2 a) {
	return hash12(vec2(a));
}

vec2 hash22(ivec2 a) {
	vec3 p3 = fract(vec3(vec2(a).xyx) * vec3(0.1031, 0.103, 0.0973));
	p3 += dot(p3, p3.yzx + 33.33);
	return fract((p3.xx + p3.yz) * p3.zy);
}

vec2 gradientAt(ivec2 k) {
	float a = TAU * hash2(k);
	return vec2(cos(a), sin(a));
}

// Gradient (Perlin) noise, about -0.7 to 0.7: no grid showing through,
// as value noise's can.
float gnoise(vec2 p) {
	vec2 i = floor(p);
	vec2 f = fract(p);
	vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
	ivec2 k = ivec2(i);
	float a = dot(gradientAt(k), f);
	float b = dot(gradientAt(k + ivec2(1, 0)), f - vec2(1.0, 0.0));
	float c = dot(gradientAt(k + ivec2(0, 1)), f - vec2(0.0, 1.0));
	float d = dot(gradientAt(k + ivec2(1, 1)), f - vec2(1.0, 1.0));
	return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// The same, 0 to 1.
float noise(vec2 p) {
	return clamp(0.5 + 0.75 * gnoise(p), 0.0, 1.0);
}

// Mineral grain: gradient noise in octaves fixed on the rock, but only
// those near a pixel's size blended in (ps: cliff units a pixel - q's
// derivative across the screen), so it's always a fine speckle, never
// blobs however far in the view's zoomed. -0.7 to 0.7 or so.
float pixelGrain(vec2 q, float ps) {
	float k0 = floor(log2(0.2 / ps));
	float sum = 0.0;
	float weights = 0.0;
	for (int i = 0; i < 4; i++) {
		float k = k0 + float(i);
		float f = exp2(k);
		float cycles = f * ps;
		float w = smoothstep(0.2, 0.4, cycles) * (1.0 - smoothstep(0.75, 1.1, cycles));
		sum += w * gnoise(q * f + vec2(17.3, 5.1) * mod(k, 16.0));
		weights += w;
	}
	return sum / max(weights, 1e-4);
}

// Glints: here and there a crystal (a cell about a pixel across, on
// the rock) with a facet of its own, flashing where it turns the sun
// toward the eye. Two sizes of cell, blended as the zoom goes between
// them, so it's always pixel-sized; fading as the view zooms far out
// (a crystal's then much smaller than a pixel). x: how bright; y: its
// thin-film phase (see filmColour()), from the crystal and its tilt.
vec2 glints(vec2 q, float ps, vec3 n, vec3 l) {
	float level = -log2(ps);
	float k0 = floor(level);
	vec3 halfway = normalize(l + vec3(0.0, 0.0, 1.0));
	float sum = 0.0;
	float phase = 0.0;
	for (int i = 0; i < 2; i++) {
		float k = k0 + float(i);
		ivec2 cell = ivec2(floor(q * exp2(k)));
		ivec2 seed = cell + ivec2(int(k) * 7919, int(k) * 104);
		vec2 r = hash22(seed);
		if (r.x < GLINT_DENSITY) {
			vec2 tip = hash22(seed + 31) - 0.5;
			vec3 facet = normalize(n + vec3(tip * 1.4, 0.0));
			float flash = pow(max(dot(facet, halfway), 0.0), 120.0) * (0.4 + 0.6 * r.y / GLINT_DENSITY);
			flash *= i == 0 ? 1.0 - fract(level) : fract(level);
			if (flash > sum) {
				phase = hash2(seed + 57) + 2.5 * length(tip);
			}
			sum = max(sum, flash);
		}
	}
	return vec2(sum * (1.0 - 0.85 * smoothstep(0.004, 0.02, ps)), phase);
}

// A thin film's colour (as on oil, or a mineral's tarnish) at a phase:
// soft bands of colour round the hues, half white.
vec3 filmColour(float phase) {
	return mix(vec3(1.0), 0.5 + 0.5 * cos(TAU * (phase + vec3(0.0, 0.33, 0.67))), 0.8);
}

// Broad variation across the cliff, so from afar it doesn't repeat:
// slow noise steering how things are made, never where (so straight
// lines stay straight). x: relief - how far rows and overhangs stick
// out; y: tone, cool to warm; z: weathering - rust, stains, moss and
// grass; w: blockiness - how many joints and strata go through.
// (Stretched to fill 0 to 1: gradient noise keeps mostly to the middle.)
vec4 region(vec2 q) {
	vec4 n = vec4(noise(q * 0.3 + 3.1), noise(q * 0.22 + 7.7), noise(q * 0.27 + 13.9), noise(q * 0.35 + 21.3));
	return smoothstep(0.28, 0.72, n);
}

// At least `octaves` octaves, and more as the view zooms in, down to a
// pixel's size (ps), so it never runs out; each fading out as it nears
// a pixel.
float fbm(vec2 p, float ps, int octaves) {
	float sum = 0.0;
	float amp = 0.5;
	float freq = 1.0;
	for (int i = 0; i < 12; i++) {
		float fine = freq * ps;
		if (i >= octaves && fine > 0.5) {
			break;
		}
		float fade = 1.0 - smoothstep(0.3, 0.6, fine);
		sum += amp * mix(0.5, noise(p * freq + float(i) * 17.3), fade);
		freq *= 2.03;
		amp *= 0.5;
	}
	return sum;
}

// Rough relief, about -1 to 1 (times amplitude at frequency f0): octaves
// of gradient noise, half of each creased (folded at its zero, as broken
// stone), from f0 down to a pixel's size however far the view's zoomed
// in, the finer ones only a little smaller (roughness), so close up it's
// still rough, never smooth lumps.
float rough(vec2 q, float ps, float f0) {
	float sum = 0.0;
	float amp = 1.0;
	float freq = f0;
	for (int i = 0; i < 16; i++) {
		float fine = freq * ps;
		if (fine > 0.6) {
			break;
		}
		float n = gnoise(q * freq + vec2(float(i) * 17.3, float(i) * 5.9));
		n = mix(n, 0.45 - 1.3 * abs(n), 0.5);
		sum += amp * (1.0 - smoothstep(0.3, 0.6, fine)) * n;
		freq *= 2.03;
		amp *= 0.6;
	}
	return sum;
}

//// Buffer A
///float32
///stateRow:top
uniform sampler2D iChannel0; ///buf:A;
// The view and the gesture, in the top row:
//   0: offset (pixels), zoom, angle
//   1: fling velocity (pixels a frame), last touch point
//   2: fingers last frame, pinch's starting distance, zoom and angle
//   3: the cliff point the pinch holds (world pixels), its fingers' starting
//      angle, and a mark that it's been set up
vec4 state(int i) {
	return texelFetch(iChannel0, ivec2(i, int(iResolution.y) - 1), 0);
}

// Pointers come in pixels (or 0-1, from some sources).
vec2 scaled(vec2 p) {
	return p.x <= 1.5 && p.y <= 1.5 ? p * iResolution.xy : p;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 c = ivec2(fragCoord);
	if (c.y != int(iResolution.y) - 1 || c.x > 3) {
		fragColor = vec4(0.0);
		return;
	}
	vec2 res = iResolution.xy;
	vec4 s0 = state(0), s1 = state(1), s2 = state(2), s3 = state(3);
	if (abs(s3.w - 0.777) > 0.01) {
		s0 = vec4(0.0, 0.0, 1.0, 0.0);
		s1 = vec4(0.0);
		s2 = vec4(0.0, 0.0, 1.0, 0.0);
		s3 = vec4(0.0, 0.0, 0.0, 0.777);
	}
	vec2 offset = s0.xy;
	float zoom = s0.z;
	float angle = s0.w;
	vec2 velocity = s1.xy;
	vec2 last = s1.zw;
	int fingers = int(s2.x + 0.5);

	if (pointerCount >= 2) {
		vec2 p0 = scaled(pointers[0].xy);
		vec2 p1 = scaled(pointers[1].xy);
		vec2 d = p1 - p0;
		float dist = max(length(d), 1e-4);
		float fingerAngle = atan(d.y, d.x);
		vec2 mid = 0.5 * (p0 + p1);
		if (fingers < 2) {
			// The pinch starts: hold the cliff point between the fingers.
			s2.yzw = vec3(dist, zoom, angle);
			s3.xy = rot(-angle) * (mid - res * 0.5 - offset) / zoom;
			s3.z = fingerAngle;
		}
		zoom = clamp(s2.z * dist / s2.y, MIN_ZOOM, MAX_ZOOM);
		float turn = fingerAngle - s3.z;
		turn -= TAU * floor((turn + PI) / TAU);
		angle = s2.w + turn;
		offset = mid - res * 0.5 - rot(angle) * s3.xy * zoom;
		velocity = vec2(0.0);
		last = mid;
	} else if (pointerCount == 1) {
		vec2 p = scaled(pointers[0].xy);
		if (fingers != 1) {
			last = p;
		}
		vec2 delta = p - last;
		offset += delta;
		velocity = delta;
		last = p;
	} else {
		if (length(velocity) > 0.05) {
			offset += velocity;
			velocity *= INERTIA;
		} else {
			velocity = vec2(0.0);
		}
	}
	fingers = min(pointerCount, 2);
	angle -= TAU * floor((angle + PI) / TAU);

	if (c.x == 0) {
		fragColor = vec4(offset, zoom, angle);
	} else if (c.x == 1) {
		fragColor = vec4(velocity, last);
	} else if (c.x == 2) {
		fragColor = vec4(float(fingers), s2.yzw);
	} else {
		fragColor = s3;
	}
}

//// Buffer B
///float32
uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;
// The cliff where each pixel looks: x its height, y its colour (8 bits a
// channel, packed), z how deep in a crack, w what it is (0 rock, 1 plant,
// 2 sky). Its top row keeps the view it was made for: while the view's the
// same, every texel's kept as it was.

float packColour(vec3 c) {
	vec3 b = floor(clamp(c, 0.0, 1.0) * 255.0 + 0.5);
	return b.r + 256.0 * b.g + 65536.0 * b.b;
}

// The cliff's top edge, at x.
float topEdge(float x) {
	return TOP + 0.05 * (noise(vec2(x * 0.9, 3.1)) - 0.5) + 0.025 * (noise(vec2(x * 4.0, 7.7)) - 0.5);
}

// A row of strata's bottom edge (row k runs from rowEdge(k) to rowEdge(k + 1)).
// Rows are found in a stretched height, so they're taller up some
// stretches of the cliff and thinner up others; it only stretches (it
// always rises), so rows stay as straight as they were.
float rowSpace(float y) {
	return y + 1.6 * (noise(vec2(y * 0.3, 4.4)) - 0.5);
}

float rowEdge(int k) {
	return float(k) * ROW + (hash1(k * 7 + 1) - 0.5) * 0.8 * ROW;
}

float masterEdge(int m) {
	return float(m) * MASTER + (hash1(m * 11 + 3) - 0.5) * 0.5 * MASTER;
}

// Grass: a fuzz of thin blades rooted along the ground - the cliff's top
// edge (top) or a ledge's line at y = ground - growing up only, leaning a
// little, in clumps as thick as `thick` says where they're rooted (top:
// clumps along the top; ledge: patches along it, as the moss).
// (wet: the region's shift to the ledge patches' threshold, as the moss's.)
bool blades(vec2 q, bool top, float ground, float tallest, float seed, float wet, out vec3 colour) {
	colour = vec3(0.0);
	int i0 = int(floor(q.x / BLADE));
	for (int n = -8; n <= 8; n++) {
		int i = i0 + n;
		float r1 = hash2(ivec2(i, int(seed)));
		float r2 = hash2(ivec2(i + 7, int(seed) + 3));
		float r3 = hash2(ivec2(i + 13, int(seed) + 5));
		float root = (float(i) + r1) * BLADE;
		float thick = top ? smoothstep(0.2, 0.7, noise(vec2(root * 6.0, 1.7)))
				: smoothstep(0.4 + wet, 0.65 + wet, noise(vec2(root * 9.0, ground * 4.0) + 8.0));
		if (r3 > 0.15 + 0.85 * thick) {
			continue;
		}
		float base = top ? topEdge(root) - 0.6 * SOIL : ground - 0.004;
		float len = tallest * (0.15 + 0.85 * r2 * r2) * (0.3 + 0.7 * thick);
		float t = (q.y - base) / len;
		if (t < 0.0 || t > 1.0) {
			continue;
		}
		float lean = (hash2(ivec2(i + 3, int(seed) + 9)) - 0.5) * 1.2;
		float x = root + lean * len * t * t;
		float halfWidth = 0.0016 * (1.0 - 0.8 * t);
		if (abs(q.x - x) < halfWidth) {
			vec3 dry = vec3(0.56, 0.52, 0.28);
			vec3 green = vec3(0.28, 0.35, 0.1);
			float tone = hash2(ivec2(i + 21, int(seed)));
			colour = mix(green, dry, tone * tone) * (0.45 + 0.55 * t) * (0.8 + 0.4 * r1);
			return true;
		}
	}
	return false;
}

// One facet's plane (cell id, centre o), at p.
float facetPlane(vec2 p, vec2 o, ivec2 id, float size, float slope, int seed) {
	vec2 g = (hash22(id + ivec2(seed * 7 + 5, 1)) - 0.5) * 2.0 * slope;
	return dot(p - o, g) * size + (hash2(id + ivec2(seed, 9)) - 0.5) * 0.02 * size;
}

// Facets: the cell of a Voronoi diagram q's in, each cell a plane of its
// own (slope up to `slope`), so facets meet in steps and creases -
// rounded off over a little way (ROUNDING of a cell, varying), the
// rounding's edge wobbling, so no corner's knife-sharp. edge: how far
// from the cell's border.
float facets(vec2 q, float size, float slope, int seed, out float edge) {
	vec2 p = q / size;
	float round = ROUNDING / size * (0.4 + 1.2 * noise(q * (3.0 / size) + float(seed) * 1.7));
	// The edges wobble: the cells are found about a point moved a little.
	vec2 wobble = vec2(noise(q * (12.0 / size) + float(seed)), noise(q * (12.0 / size) + float(seed) + 5.3)) - 0.5;
	vec2 pc = p + 2.0 * round * wobble;
	ivec2 ip = ivec2(floor(pc));
	float r[9];
	vec2 o[9];
	float best = 1e9;
	float second = 1e9;
	for (int i = 0; i < 9; i++) {
		ivec2 k = ip + ivec2(i % 3 - 1, i / 3 - 1);
		o[i] = vec2(k) + 0.1 + 0.8 * hash22(k + ivec2(seed, seed * 3));
		r[i] = length(pc - o[i]);
		if (r[i] < best) {
			second = best;
			best = r[i];
		} else if (r[i] < second) {
			second = r[i];
		}
	}
	float e = (second - best) * 0.5;
	edge = e * size;
	// Every cell's plane, weighted by how near the cell's edge is: all
	// near a corner, where three meet (only the nearest two left a step
	// where the second nearest changed - a crease a pixel wide close up).
	float sum = 0.0;
	float weights = 0.0;
	for (int i = 0; i < 9; i++) {
		ivec2 k = ip + ivec2(i % 3 - 1, i / 3 - 1);
		float w = 1.0 - smoothstep(0.0, round, (r[i] - best) * 0.5);
		if (w > 0.0) {
			sum += w * facetPlane(p, o[i], k, size, slope, seed);
			weights += w;
		}
	}
	return sum / weights;
}

// The rock: its height, colour and crackedness at q.
// How much row k sticks out: its boundary with the row below is strong
// (a step and a crack), weak (a step) or gone (one taller block).
// (blocky: the region's blockiness - more boundaries go through.)
float rowStrength(int k, float blocky) {
	float s = hash1(k * 23 + 9);
	float strong = mix(0.2, 0.6, blocky);
	return s < strong ? 1.0 : s < strong + 0.25 ? 0.45 : 0.0;
}

// (relief: the region's - how far rows stick out.)
float rowStick(int k, float relief, float blocky) {
	float amount = 0.3 * mix(0.4, 1.7, relief);
	float own = (hash1(k * 31 + 7) - 0.5) * amount;
	float below = (hash1((k - 1) * 31 + 7) - 0.5) * amount;
	return mix(below, own, max(rowStrength(k, blocky), 0.15));
}

// A distance to a boundary of a strength (0 to 1): a weaker one's
// groove is narrower; one too faint isn't there at all (one a few
// millionths wide still caught whole rows of pixels when zoomed in).
float faded(float d, float strength) {
	return strength < 0.03 ? 1.0 : d / strength;
}

float rock(vec2 q, float ps, out vec3 colour, out float crack, out float plant) {
	plant = 0.0;
	// Everything's warped a little, so no edge runs straight.
	vec2 warp = vec2(noise(q * 2.2 + 3.0), noise(q * 2.2 + 11.0)) - 0.5;
	warp += 0.35 * (vec2(noise(q * 9.0 + 5.0), noise(q * 9.0 + 19.0)) - 0.5);
	vec2 qw = q + 0.07 * warp;
	q = qw;
	vec4 r = region(q);
	// Strata, wavy and dipping.
	float yw = q.y + 0.08 * (noise(vec2(q.x * 0.7, 1.3)) - 0.5) + 0.18 * (noise(vec2(q.x * 0.25, 9.1)) - 0.5) + 0.05 * q.x;
	float ys = rowSpace(yw);
	int k = int(floor(ys / ROW));
	if (ys < rowEdge(k)) {
		k--;
	} else if (ys >= rowEdge(k + 1)) {
		k++;
	}
	// The row's bounds, back in the cliff's height.
	float stretch = (rowSpace(yw + 0.005) - rowSpace(yw - 0.005)) / 0.01;
	float lo = yw - (ys - rowEdge(k)) / stretch;
	float hi = yw + (rowEdge(k + 1) - ys) / stretch;
	// How tall the row is, the same all along it (worked out at its
	// middle), so its joints can be spaced to match.
	float ysMid = 0.5 * (rowEdge(k) + rowEdge(k + 1));
	float yMid = ysMid - 1.6 * (noise(vec2(ysMid * 0.3, 4.4)) - 0.5);
	float rowHeight = (rowEdge(k + 1) - rowEdge(k))
			/ ((rowSpace(yMid + 0.005) - rowSpace(yMid - 0.005)) / 0.01);
	// Where blocks are split again, in halves - and where they're big.
	float fine = smoothstep(0.35, 0.75, noise(q * 0.2 + 91.0));
	float stick = rowStick(k, r.x, r.w);
	float aboveStick = rowStick(k + 1, r.x, r.w);

	// Long vertical joints, wandering a little, through most rows.
	float xm = q.x + 0.05 * (noise(vec2(3.7, q.y * 1.6)) - 0.5);
	int m = int(floor(xm / MASTER));
	if (xm < masterEdge(m)) {
		m--;
	} else if (xm >= masterEdge(m + 1)) {
		m++;
	}
	float masterDist = min(xm - masterEdge(m), masterEdge(m + 1) - xm);

	// The row's own joints, slanting.
	float slant = (hash1(k * 13 + 5) - 0.5) * 0.7;
	float width = mix(0.2, 0.6, pow(hash1(k * 17 + 3), 1.4)) * pow(clamp(rowHeight / ROW, 0.4, 2.5), 0.8);
	float xs = q.x - slant * (yw - lo) + hash1(k * 19 + 2) * width;
	int j = int(floor(xs / width));
	float jl = (float(j) + 0.3 * (hash2(ivec2(k, j)) - 0.5)) * width;
	if (xs < jl) {
		j--;
	}
	jl = (float(j) + 0.3 * (hash2(ivec2(k, j)) - 0.5)) * width;
	float jr = (float(j + 1) + 0.3 * (hash2(ivec2(k, j + 1)) - 0.5)) * width;
	float jointDist = min(xs - jl, jr - xs) / sqrt(1.0 + slant * slant);
	// Some of a row's joints don't go through: wider blocks.
	float through = clamp(mix(0.8, 0.35, r.w) + 0.35 * (0.5 - fine), 0.2, 0.95);
	bool openL = hash2(ivec2(k, j) + 77) > through;
	bool openR = hash2(ivec2(k, j + 1) + 77) > through;
	if (!openL && xs - jl < jr - xs || !openR && jr - xs <= xs - jl) {
		jointDist = 1.0;
	}
	bool masterHere = hash2(ivec2(k, m) + 33) > mix(0.4, 0.05, r.w);
	// A boundary's crack is as strong as the boundary, and opens and
	// closes along its length; so do the joints'.
	float open = smoothstep(0.25, 0.6, noise(q * vec2(5.0, 3.0) + 31.0));
	// (And a boundary fades in and out along the row.)
	float alongLo = smoothstep(0.25, 0.5, noise(vec2(q.x * 1.4, float(k) * 3.7)));
	float alongHi = smoothstep(0.25, 0.5, noise(vec2(q.x * 1.4, float(k + 1) * 3.7)));
	float rowDist = min(faded(yw - lo, rowStrength(k, r.w) * open * alongLo),
			faded(hi - yw, rowStrength(k + 1, r.w) * open * alongHi));
	// Split in halves where it's fine: a straight line across the row's
	// middle, and one down the middle of the block, each fading in and
	// out with how fine it is there.
	float mid = 0.5 * (lo + hi) + (hash1(k * 37 + 4) - 0.5) * 0.2 * (hi - lo);
	// (Switching on over a short way: a faint split would be a hairline
	// all along it.)
	float splitRow = smoothstep(0.42, 0.5, fine) * step(0.17, rowHeight);
	rowDist = min(rowDist, faded(abs(yw - mid), splitRow * open));
	float jm = 0.5 * (jl + jr) + (hash2(ivec2(k, j) + 91) - 0.5) * 0.2 * (jr - jl);
	float splitJoint = smoothstep(0.62, 0.7, fine) * step(0.18, jr - jl);
	jointDist = min(jointDist, faded(abs(xs - jm) / sqrt(1.0 + slant * slant), splitJoint));
	float jointOpen = 0.3 + 0.7 * smoothstep(0.2, 0.55, noise(q * vec2(2.0, 6.0) + 47.0));
	float edge = min(rowDist, min(jointDist, masterHere ? masterDist : 1.0) / jointOpen);

	// The block: sticking out by its row's and its own amounts, leaning
	// so its top overhangs.
	ivec2 block = ivec2(k * 64 + m, j);
	vec2 tilt = vec2((hash2(block + 5) - 0.5) * 0.6, (0.12 + 0.5 * hash2(block + 6)) * mix(0.45, 1.6, r.x));
	float h = stick + (hash2(block) - 0.5) * 0.12
			+ tilt.x * (xs - 0.5 * (jl + jr)) + tilt.y * (yw - 0.5 * (lo + hi));
	// The halves stand out by their own amounts.
	ivec2 halves = ivec2(xs > jm ? 1 : 0, yw > mid ? 1 : 0);
	h += splitRow * (hash2(block * 2 + ivec2(0, halves.y) + 101) - 0.5) * 0.08
			+ splitJoint * (hash2(block * 2 + ivec2(halves.x, 0) + 103) - 0.5) * 0.08;

	// Grooves along the joints, and bevelled edges.
	float grooveWidth = mix(0.006, 0.02, hash2(block + 9));
	crack = 1.0 - smoothstep(0.0, grooveWidth, edge);
	h -= CRACK_DEPTH * crack;
	// Every edge rounded over (the tops' more), by a varying amount, and
	// chipped: the edge wobbles.
	float roundOver = 0.06 * (0.6 + 0.8 * noise(q * 7.0 + 13.0));
	float chipped = edge + 0.012 * (noise(q * 45.0 + 3.0) - 0.5);
	float over = 1.0 - smoothstep(0.0, roundOver, chipped);
	h -= BEVEL * 1.4 * over * over * (hi - yw < 0.06 ? 1.6 : 1.0);

	// Big angular faces, crossing the blocks, and a few hairline
	// fractures along them; then chipped, ridged roughness, and grit.
	// Big masses first: whole stretches of the face set forward or back,
	// in steps, their faces turned this way and that.
	float eBig;
	h += 1.6 * mix(0.55, 1.45, r.x) * facets(q * vec2(1.0, 0.7), 0.55, 0.4, 77, eBig);
	float massCrack = (1.0 - smoothstep(0.0, 0.012, eBig)) * smoothstep(0.3, 0.6, noise(q * 3.0 + 61.0));
	h -= 0.1 * massCrack;
	crack = max(crack, massCrack);
	float e1;
	h += facets(q + vec2(block) * 3.1, 0.2, 0.55, k + 3, e1);
	float hair = (1.0 - smoothstep(0.0, 0.003, e1)) * step(0.82, noise(q * 4.0));
	h -= 0.02 * hair;
	crack = max(crack, 0.5 * hair);
	h += 0.08 * (fbm(q * 1.3, ps * 1.3, 3) - 0.5);
	// Chips: small angular faces, as the big ones.
	float e2;
	h += 0.6 * facets(q, 0.06, 0.3, 19, e2);
	float grit = rough(q, ps, 9.0);
	h += 0.012 * grit;
	// Fine stone: mineral grain, faint, in the relief and the colour -
	// always about a pixel across (see pixelGrain()).
	float grain = pixelGrain(q, ps);
	h += 0.12 * ps * grain;

	// Colour: warm and cool greys by block, mottled; pale patches; rust
	// streaks down some stretches; dark water stains running down from
	// the tops of rows; dark in the cracks.
	float tone = hash2(block + 13);
	float halfTone = 1.0 + 0.14 * (hash2(block * 2 + halves + 107) - 0.5) * max(splitRow, splitJoint);
	vec3 base = tone < 0.4 ? vec3(0.66, 0.64, 0.6) : tone < 0.75 ? vec3(0.7, 0.62, 0.52) : vec3(0.55, 0.53, 0.5);
	base *= (0.85 + 0.3 * fbm(q * 3.0 + 5.0, ps * 3.0, 4)) * halfTone;
	// The region's tone, cool grey to warm, and broad light and dark
	// zones, at two scales - what shows from far off.
	base *= mix(vec3(0.9, 0.96, 1.06), vec3(1.1, 0.97, 0.82), r.y);
	base *= mix(0.78, 1.25, smoothstep(0.25, 0.75, noise(q * 0.12 + 40.0)))
			* mix(0.88, 1.12, smoothstep(0.25, 0.75, noise(q * 0.45 + 52.0)));
	float pale = smoothstep(0.55, 0.75, fbm(q * vec2(7.0, 4.0) + 21.0, ps * 7.0, 4));
	base = mix(base, vec3(0.86, 0.85, 0.81), 0.55 * pale);
	float rustBand = smoothstep(0.62 - 0.4 * r.z, 0.88 - 0.4 * r.z, noise(vec2(q.x * 1.6, 2.9)));
	float rust = rustBand * smoothstep(0.3, 0.7, fbm(vec2(q.x * 14.0, q.y * 1.2) + 41.0, ps * 14.0, 4));
	base = mix(base, vec3(0.58, 0.39, 0.26), 0.7 * rust);
	float stainLength = 0.08 + 0.25 * hash2(block + 17);
	float stain = exp(-(hi - yw) / stainLength) * smoothstep(0.4, 0.75, fbm(vec2(q.x * 22.0, q.y * 1.2), ps * 22.0, 4));
	base = mix(base, vec3(0.24, 0.22, 0.2), min(0.85 * stain * mix(0.35, 1.3, r.z), 0.9));
	// Long dark streaks, where water's run down the face for ages, across
	// many rows - in the weathered regions.
	float streak = smoothstep(0.55, 0.8, noise(vec2(q.x * 1.1, q.y * 0.06) + 71.0))
			* smoothstep(0.3, 0.7, noise(vec2(q.x * 5.0, q.y * 0.3) + 83.0));
	base = mix(base, vec3(0.22, 0.2, 0.18), 0.55 * streak * smoothstep(0.3, 0.8, r.z));
	base *= 1.0 + 0.12 * clamp(grit, -1.0, 1.0);
	base *= 1.0 + 0.2 * grain;
	// Dark mineral grains, pixel-sized too.
	float fleck = smoothstep(0.4, 0.6, pixelGrain(q * 1.37 + 9.0, ps));
	base = mix(base, vec3(0.2, 0.19, 0.18), 0.3 * fleck);
	// Moss and grass on a ledge: where this row sticks out past the next.
	float ledge = smoothstep(0.02, 0.06, stick - aboveStick) * (1.0 - smoothstep(0.0, 0.05, hi - yw));
	float wet = 0.6 * (0.5 - r.z);
	float moss = ledge * smoothstep(0.4 + wet, 0.65 + wet, noise(vec2(q.x * 9.0, hi * 4.0) + 8.0));
	base = mix(base, mix(vec3(0.3, 0.35, 0.12), vec3(0.44, 0.44, 0.19), noise(q * 90.0)), moss);
	h += 0.02 * moss;
	base *= 1.0 - 0.35 * crack;
	colour = base;
	// Grass growing up from the ledge below, where the row below sticks
	// out past this one (in the moss's patches), standing in front.
	float below = rowStick(k - 1, r.x, r.w);
	float ledgeBelow = smoothstep(0.02, 0.06, below - stick) * step(0.01, rowStrength(k, r.w));
	vec3 blade;
	if (ledgeBelow > 0.0 && yw - lo < 0.09
			&& blades(vec2(q.x, yw), false, lo, 0.085 * ledgeBelow, float(k), wet, blade)) {
		colour = blade;
		crack = 0.0;
		plant = 1.0;
		h += below - stick;
	}
	return h;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 c = ivec2(fragCoord);
	int top = int(iResolution.y) - 1;
	vec4 view = viewState(iChannel0);
	// Its view: the same as last time, everything's kept.
	vec4 made = texelFetch(iChannel1, ivec2(0, top), 0);
	if (iFrame > 2 && made == view) {
		fragColor = texelFetch(iChannel1, c, 0);
		return;
	}
	if (c == ivec2(0, top)) {
		fragColor = view;
		return;
	}
	float ps = VIEW / (view.z * iResolution.y);
	vec2 q = toCliff(fragCoord, view);
	float edge = topEdge(q.x);

	vec3 colour;
	float crack = 0.0;
	float h = -10.0;
	float what = 2.0;
	vec3 blade;
	if (q.y > edge - 0.6 * SOIL - 0.01 && q.y < edge + 0.12 && blades(q, true, 0.0, 0.085, 1.0, 0.0, blade)) {
		what = 1.0;
		colour = blade;
		h = 0.2 + SHELF_OUT;
	} else if (q.y > edge) {
		colour = mix(vec3(0.9, 0.9, 0.86), vec3(0.97, 0.97, 0.95), smoothstep(0.0, 0.6, q.y - edge));
	} else if (q.y > edge - SOIL) {
		// The soil lip the grass grows from.
		what = 1.0;
		colour = mix(vec3(0.2, 0.17, 0.12), vec3(0.36, 0.3, 0.2), noise(q * 150.0));
		h = 0.12 + SHELF_OUT;
	} else {
		h = rock(q, ps, colour, crack, what);
		// The shelf along the top, sticking out over an undercut.
		float below = edge - q.y;
		float thick = SHELF * (0.6 + 0.8 * noise(vec2(q.x * 3.0, 5.5)));
		h += SHELF_OUT * (1.0 - smoothstep(thick - 0.015, thick, below));
		h -= 0.1 * smoothstep(thick, thick + 0.03, below) * (1.0 - smoothstep(thick + 0.05, thick + 0.2, below));
		crack = max(crack, 0.5 * (1.0 - smoothstep(0.0, 0.012, abs(below - thick - 0.006))));
		// Soil washed down the top of the face.
		float wash = smoothstep(0.25, 0.0, edge - q.y) * noise(vec2(q.x * 30.0, q.y * 3.0));
		colour = mix(colour, vec3(0.42, 0.34, 0.25), 0.5 * wash);
	}
	fragColor = vec4(h, packColour(colour), crack, what);
}

//// Buffer C
///float32
// The sun's shadow (x) and ambient occlusion (y) on the cliff (see the
// top), and glints (z, how bright; w, their colour's phase): worked out
// only for a new view; its top row keeps the view.
uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;
uniform sampler2D iChannel2; ///buf:C;

vec4 cliffAt(ivec2 c) {
	ivec2 size = ivec2(iResolution.xy);
	return texelFetch(iChannel1, clamp(c, ivec2(0), size - ivec2(1, 2)), 0);
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 res = iResolution.xy;
	vec4 view = viewState(iChannel0);
	ivec2 top = ivec2(0, int(res.y) - 1);
	// Lit for this view already: kept.
	if (iFrame > 2 && texelFetch(iChannel2, top, 0) == view) {
		fragColor = texelFetch(iChannel2, ivec2(fragCoord), 0);
		return;
	}
	if (ivec2(fragCoord) == top) {
		fragColor = view;
		return;
	}
	float ps = VIEW / (view.z * res.y);
	ivec2 c = ivec2(fragCoord);
	vec4 here = cliffAt(c);
	float shadow = 1.0;
	float ao = 1.0;
	vec2 glint = vec2(0.0);
	if (here.w < 1.5) {
		vec3 sun = normalize(SUN);
		vec2 sunXY = rot(view.w) * sun.xy;
		float h0 = here.x;

		// Soft shadow: march toward the sun, the ray rising as it goes.
		vec2 dir = normalize(sunXY);
		float rise = sun.z / length(sun.xy);
		// (Each pixel's steps start at a different point, else a shadow's
		// edge came out in sawteeth - by interleaved gradient noise, which
		// spreads neighbours' starts evenly: white noise left the soft
		// edges grainy.)
		float ign = fract(52.9829189 * fract(dot(fragCoord, vec2(0.06711056, 0.00583715))));
		float t = (1.0 + 1.5 * ign) * ps;
		for (int i = 0; i < SHADOW_STEPS; i++) {
			vec2 at = fragCoord + dir * (t / ps);
			if (at.x < 0.0 || at.y < 0.0 || at.x >= res.x || at.y >= res.y - 1.0) {
				break;
			}
			float hs = cliffAt(ivec2(at)).x;
			float ray = h0 + 0.003 + t * rise;
			shadow = min(shadow, clamp(1.0 - (hs - ray) / (0.7 * t), 0.0, 1.0));
			t *= 1.2;
			if (t > SHADOW_REACH) {
				break;
			}
		}

		// Ambient occlusion: how much rises above it, round about.
		ao = 0.0;
		float weight = 0.0;
		for (int ring = 0; ring < 3; ring++) {
			float r = ring == 0 ? 0.012 : ring == 1 ? 0.04 : 0.12;
			for (int k = 0; k < 6; k++) {
				float a = (float(k) + 0.5 * float(ring)) * TAU / 6.0;
				vec2 at = clamp(fragCoord + vec2(cos(a), sin(a)) * (r / ps), vec2(0.0), res - 2.0);
				vec4 s = cliffAt(ivec2(at));
				float hs = s.w > 1.5 ? h0 : s.x;
				ao += clamp((hs - h0) / r, 0.0, 1.0) * (3.0 - float(ring));
				weight += 3.0 - float(ring);
			}
		}
		ao = 1.0 - AO_STRENGTH * ao / weight;
		ao *= 1.0 - CRACK_DARK * here.z;
		ao = clamp(ao, AO_FLOOR, 1.0);

		// Glints, on rock in the sun.
		if (here.w < 0.5) {
			float dx = (cliffAt(c + ivec2(1, 0)).x - cliffAt(c - ivec2(1, 0)).x) / (2.0 * ps);
			float dy = (cliffAt(c + ivec2(0, 1)).x - cliffAt(c - ivec2(0, 1)).x) / (2.0 * ps);
			vec3 n = normalize(vec3(-clamp(dx, -6.0, 6.0), -clamp(dy, -6.0, 6.0), 1.0));
			glint = glints(toCliff(fragCoord, view), ps, n, vec3(sunXY, sun.z));
			glint.x *= shadow;
		}
	}
	fragColor = vec4(shadow, ao, glint);
}

//// Buffer D
///float32
// The cliff lit (see the top), from B's heights and colours and C's
// shadow - smoothed over 3x3 pixels, as the marched shadow's soft edges
// come out grainy - and ambient occlusion; worked out only for a new
// view, its top row keeping the view.
uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;
uniform sampler2D iChannel2; ///buf:C;
uniform sampler2D iChannel3; ///buf:D;

vec4 cliffAt(ivec2 c) {
	ivec2 size = ivec2(iResolution.xy);
	return texelFetch(iChannel1, clamp(c, ivec2(0), size - ivec2(1, 2)), 0);
}

vec4 shadeAt(ivec2 c) {
	ivec2 size = ivec2(iResolution.xy);
	return texelFetch(iChannel2, clamp(c, ivec2(0), size - ivec2(1, 2)), 0);
}

// C's glints (z, w), none off the edge.
vec4 glintAt(ivec2 c) {
	ivec2 size = ivec2(iResolution.xy);
	if (any(lessThan(c, ivec2(0))) || any(greaterThan(c, size - ivec2(1, 2)))) {
		return vec4(0.0);
	}
	return texelFetch(iChannel2, c, 0);
}

vec3 unpackColour(float v) {
	return vec3(mod(v, 256.0), mod(floor(v / 256.0), 256.0), floor(v / 65536.0)) / 255.0;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 res = iResolution.xy;
	vec4 view = viewState(iChannel0);
	ivec2 top = ivec2(0, int(res.y) - 1);
	if (iFrame > 2 && texelFetch(iChannel3, top, 0) == view) {
		fragColor = texelFetch(iChannel3, ivec2(fragCoord), 0);
		return;
	}
	if (ivec2(fragCoord) == top) {
		fragColor = view;
		return;
	}
	float ps = VIEW / (view.z * res.y);
	ivec2 c = ivec2(fragCoord);
	vec4 here = cliffAt(c);
	vec3 albedo = unpackColour(here.y);
	vec3 col;
	if (here.w > 1.5) {
		col = albedo;
	} else {
		vec3 sun = normalize(SUN);
		vec3 l = vec3(rot(view.w) * sun.xy, sun.z);
		float dx = (cliffAt(c + ivec2(1, 0)).x - cliffAt(c - ivec2(1, 0)).x) / (2.0 * ps);
		float dy = (cliffAt(c + ivec2(0, 1)).x - cliffAt(c - ivec2(0, 1)).x) / (2.0 * ps);
		// Sky beside it isn't a cliff to slope down to.
		dx = clamp(dx, -6.0, 6.0);
		dy = clamp(dy, -6.0, 6.0);
		vec3 n = normalize(vec3(-dx, -dy, 1.0));
		float shadow = 0.0;
		for (int y = -1; y <= 1; y++) {
			for (int x = -1; x <= 1; x++) {
				shadow += shadeAt(c + ivec2(x, y)).x * (x == 0 && y == 0 ? 4.0 : x == 0 || y == 0 ? 2.0 : 1.0);
			}
		}
		shadow /= 16.0;
		float ao = shadeAt(c).y;

		// Moss on whatever faces up, in patches.
		if (here.w < 0.5) {
			float up = (rot(-view.w) * n.xy).y;
			vec2 q = toCliff(fragCoord, view);
			float moss = smoothstep(0.5, 0.8, up) * smoothstep(0.5, 0.72, noise(q * 11.0 + 5.0));
			albedo = mix(albedo, mix(vec3(0.27, 0.32, 0.1), vec3(0.41, 0.41, 0.18), noise(q * 120.0)), 0.85 * moss);
		}
		vec3 a = pow(albedo, vec3(2.2));
		float diffuse = max(dot(n, l), 0.0);
		float skyLight = 0.55 + 0.45 * n.y * 0.5 + 0.25 * n.z;
		vec3 lit = SUN_COLOUR * diffuse * shadow + SKY_COLOUR * skyLight * ao
				+ BOUNCE_COLOUR * (0.5 - 0.5 * n.y) * ao;
		col = a * lit;
		vec4 g = shadeAt(c);
		col += GLINT * SUN_COLOUR * g.z * filmColour(g.w);
		if (here.w > 0.5) {
			// Plants: no hard sun, just soft and a little translucent.
			col = a * (SUN_COLOUR * (0.35 + 0.4 * shadow) + SKY_COLOUR * 0.7 * ao);
		}
		col = 1.0 - exp(-col * EXPOSURE);
		col = pow(col, vec3(1.0 / 2.2));
	}
	// The glints' flares: a broad soft halo, going warm at its edge
	// (halation), and eight thin spikes alike all round, both going
	// through the colours outward (dispersion), gathered from the glints
	// round about. On sky and plants too: it's in the eye, not the rock.
	vec3 flare = vec3(0.0);
	for (int y = -5; y <= 5; y++) {
		for (int x = -5; x <= 5; x++) {
			float r2 = float(x * x + y * y);
			if (r2 > 0.0 && r2 <= 25.0) {
				vec4 g = glintAt(c + ivec2(x, y));
				if (g.z > 0.0) {
					vec3 tint = mix(filmColour(g.w + 0.06 * r2), vec3(1.0, 0.5, 0.3), smoothstep(4.0, 25.0, r2));
					flare += HALO * g.z * exp(-r2 / 8.0) * tint;
				}
			}
		}
	}
	for (int i = 0; i < 4; i++) {
		// Four lines through the glint: eight spikes, 45 degrees apart.
		float a = float(i) * TAU / 8.0;
		vec2 dir = vec2(cos(a), sin(a));
		for (int s = 1; s <= SPIKE_LENGTH; s++) {
			float t = float(s);
			float fade = SPIKES * pow(1.0 - t / float(SPIKE_LENGTH + 1), 2.0);
			vec4 g1 = glintAt(c + ivec2(round(dir * t)));
			vec4 g2 = glintAt(c - ivec2(round(dir * t)));
			flare += fade * (g1.z * filmColour(g1.w + 0.05 * t) + g2.z * filmColour(g2.w + 0.05 * t));
		}
	}
	flare *= SUN_COLOUR;
	col = 1.0 - (1.0 - col) * exp(-flare);
	fragColor = vec4(col, 1.0);
}
