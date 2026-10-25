// Rock Cliff - a weathered cliff face: blocky strata split by deep joints,
// angular chipped facets, rust and water stains, moss on the ledges, and
// grass and a few conifers along the top against a pale sky.
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
//   Buffer C - lighting from those heights: a normal from the neighbours,
//              a soft sun shadow marched across the height field, and
//              ambient occlusion from the heights round about - again only
//              when the view's moved.
//   Image    - Buffer C, dithered: a still cliff costs next to nothing.
//
// The cliff is a height field: rows of strata (each sticking out more or
// less, its blocks leaning so their tops overhang), split into blocks by
// slanting joints and long vertical ones, every edge bevelled and every
// joint a narrow, deep groove; within a block, two scales of flat facets
// (each cell of a Voronoi diagram a plane of its own, so neighbouring
// facets meet in sharp steps) and fine grit.

uniform sampler2D iChannel0; ///buf:C;

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
// How far (in cliff units, on average) facets' meetings are rounded off.
#define ROUNDING 0.012
// The grass: blades' spacing, and the soil lip under them.
#define BLADE 0.0013
#define SOIL 0.014
// Conifers along the top: one every so far, at most.
#define TREES 0.22

// ---- Lighting (Image).
#define SUN vec3(-0.55, 0.62, 0.6)
#define SUN_COLOUR vec3(1.75, 1.6, 1.38)
#define SKY_COLOUR vec3(0.5, 0.54, 0.6)
#define BOUNCE_COLOUR vec3(0.18, 0.15, 0.12)
#define SHADOW_STEPS 28
// The farthest a shadow's cast from, in cliff units.
#define SHADOW_REACH 0.6
#define AO_STRENGTH 1.15
#define CRACK_DARK 0.75
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

float noise(vec2 p) {
	vec2 i = floor(p);
	vec2 f = fract(p);
	f = f * f * (3.0 - 2.0 * f);
	ivec2 k = ivec2(i);
	return mix(mix(hash2(k), hash2(k + ivec2(1, 0)), f.x),
			mix(hash2(k + ivec2(0, 1)), hash2(k + ivec2(1, 1)), f.x), f.y);
}

// Octaves stop where they'd be finer than a pixel (ps).
float fbm(vec2 p, float ps, int octaves) {
	float sum = 0.0;
	float amp = 0.5;
	float freq = 1.0;
	for (int i = 0; i < octaves; i++) {
		if (freq * ps > 0.6) {
			sum += amp * 0.5;
		} else {
			sum += amp * noise(p * freq + float(i) * 17.3);
		}
		freq *= 2.03;
		amp *= 0.5;
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
float rowEdge(int k) {
	return float(k) * ROW + (hash1(k * 7 + 1) - 0.5) * 0.55 * ROW;
}

float masterEdge(int m) {
	return float(m) * MASTER + (hash1(m * 11 + 3) - 0.5) * 0.5 * MASTER;
}

// Grass: a fuzz of thin blades rooted along the ground - the cliff's top
// edge (top) or a ledge's line at y = ground - growing up only, leaning a
// little, in clumps as thick as `thick` says where they're rooted (top:
// clumps along the top; ledge: patches along it, as the moss).
bool blades(vec2 q, bool top, float ground, float tallest, float seed, out vec3 colour) {
	colour = vec3(0.0);
	int i0 = int(floor(q.x / BLADE));
	for (int n = -8; n <= 8; n++) {
		int i = i0 + n;
		float r1 = hash2(ivec2(i, int(seed)));
		float r2 = hash2(ivec2(i + 7, int(seed) + 3));
		float r3 = hash2(ivec2(i + 13, int(seed) + 5));
		float root = (float(i) + r1) * BLADE;
		float thick = top ? smoothstep(0.2, 0.7, noise(vec2(root * 6.0, 1.7)))
				: smoothstep(0.4, 0.65, noise(vec2(root * 9.0, ground * 4.0) + 8.0));
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
			vec3 dry = vec3(0.7, 0.66, 0.36);
			vec3 green = vec3(0.4, 0.48, 0.15);
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
	ivec2 ip = ivec2(floor(p));
	float best = 1e9;
	float second = 1e9;
	vec2 centre = vec2(0.0);
	vec2 centre2 = vec2(0.0);
	ivec2 id = ivec2(0);
	ivec2 id2 = ivec2(0);
	for (int y = -1; y <= 1; y++) {
		for (int x = -1; x <= 1; x++) {
			ivec2 k = ip + ivec2(x, y);
			vec2 o = vec2(k) + 0.1 + 0.8 * hash22(k + ivec2(seed, seed * 3));
			float d = dot(p - o, p - o);
			if (d < best) {
				second = best;
				centre2 = centre;
				id2 = id;
				best = d;
				centre = o;
				id = k;
			} else if (d < second) {
				second = d;
				centre2 = o;
				id2 = k;
			}
		}
	}
	float e = (sqrt(second) - sqrt(best)) * 0.5;
	edge = e * size;
	float wobble = noise(q * (12.0 / size) + float(seed));
	float round = ROUNDING / size * (0.4 + 1.2 * noise(q * (3.0 / size) + float(seed) * 1.7));
	float w = 0.5 + 0.5 * smoothstep(0.0, round, e + (wobble - 0.5) * round);
	return mix(facetPlane(p, centre2, id2, size, slope, seed), facetPlane(p, centre, id, size, slope, seed), w);
}

// The rock: its height, colour and crackedness at q.
// How much row k sticks out: its boundary with the row below is strong
// (a step and a crack), weak (a step) or gone (one taller block).
float rowStrength(int k) {
	float s = hash1(k * 23 + 9);
	return s < 0.45 ? 1.0 : s < 0.75 ? 0.45 : 0.0;
}

float rowStick(int k) {
	float own = (hash1(k * 31 + 7) - 0.5) * 0.3;
	float below = (hash1((k - 1) * 31 + 7) - 0.5) * 0.3;
	return mix(below, own, max(rowStrength(k), 0.15));
}

float rock(vec2 q, float ps, out vec3 colour, out float crack, out float plant) {
	plant = 0.0;
	// Everything's warped a little, so no edge runs straight.
	vec2 warp = vec2(noise(q * 2.2 + 3.0), noise(q * 2.2 + 11.0)) - 0.5;
	warp += 0.35 * (vec2(noise(q * 9.0 + 5.0), noise(q * 9.0 + 19.0)) - 0.5);
	vec2 qw = q + 0.07 * warp;
	q = qw;
	// Strata, wavy and dipping.
	float yw = q.y + 0.08 * (noise(vec2(q.x * 0.7, 1.3)) - 0.5) + 0.05 * q.x;
	int k = int(floor(yw / ROW));
	if (yw < rowEdge(k)) {
		k--;
	} else if (yw >= rowEdge(k + 1)) {
		k++;
	}
	float lo = rowEdge(k);
	float hi = rowEdge(k + 1);
	float stick = rowStick(k);
	float aboveStick = rowStick(k + 1);

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
	float width = mix(0.2, 0.6, pow(hash1(k * 17 + 3), 1.4));
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
	bool openL = hash2(ivec2(k, j) + 77) > 0.55;
	bool openR = hash2(ivec2(k, j + 1) + 77) > 0.55;
	if (!openL && xs - jl < jr - xs || !openR && jr - xs <= xs - jl) {
		jointDist = 1.0;
	}
	bool masterHere = hash2(ivec2(k, m) + 33) > 0.15;
	// A boundary's crack is as strong as the boundary, and opens and
	// closes along its length; so do the joints'.
	float open = smoothstep(0.25, 0.6, noise(q * vec2(5.0, 3.0) + 31.0));
	// (And a boundary fades in and out along the row.)
	float alongLo = smoothstep(0.25, 0.5, noise(vec2(q.x * 1.4, float(k) * 3.7)));
	float alongHi = smoothstep(0.25, 0.5, noise(vec2(q.x * 1.4, float(k + 1) * 3.7)));
	float rowDist = min((yw - lo) / max(rowStrength(k) * open * alongLo, 1e-3),
			(hi - yw) / max(rowStrength(k + 1) * open * alongHi, 1e-3));
	float jointOpen = 0.3 + 0.7 * smoothstep(0.2, 0.55, noise(q * vec2(2.0, 6.0) + 47.0));
	float edge = min(rowDist, min(jointDist, masterHere ? masterDist : 1.0) / jointOpen);

	// The block: sticking out by its row's and its own amounts, leaning
	// so its top overhangs.
	ivec2 block = ivec2(k * 64 + m, j);
	vec2 tilt = vec2((hash2(block + 5) - 0.5) * 0.6, 0.12 + 0.5 * hash2(block + 6));
	float h = stick + (hash2(block) - 0.5) * 0.12
			+ tilt.x * (xs - 0.5 * (jl + jr)) + tilt.y * (yw - 0.5 * (lo + hi));

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
	h += 1.6 * facets(q * vec2(1.0, 0.7), 0.55, 0.4, 77, eBig);
	float massCrack = (1.0 - smoothstep(0.0, 0.012, eBig)) * smoothstep(0.3, 0.6, noise(q * 3.0 + 61.0));
	h -= 0.1 * massCrack;
	crack = max(crack, massCrack);
	float e1;
	h += facets(q + vec2(block) * 3.1, 0.2, 0.55, k + 3, e1);
	float hair = (1.0 - smoothstep(0.0, 0.003, e1)) * step(0.7, noise(q * 4.0));
	h -= 0.02 * hair;
	crack = max(crack, 0.5 * hair);
	h += 0.08 * (fbm(q * 1.3, ps * 1.3, 3) - 0.5);
	float chips = 1.0 - abs(2.0 * fbm(q * 9.0 + 3.0, ps * 9.0, 4) - 1.0);
	h += 0.03 * chips * chips;
	float grit = fbm(q * 70.0, ps * 70.0, 4);
	h += 0.006 * (grit - 0.5);

	// Colour: warm and cool greys by block, mottled; pale patches; rust
	// streaks down some stretches; dark water stains running down from
	// the tops of rows; dark in the cracks.
	float tone = hash2(block + 13);
	vec3 base = tone < 0.4 ? vec3(0.66, 0.64, 0.6) : tone < 0.75 ? vec3(0.7, 0.62, 0.52) : vec3(0.55, 0.53, 0.5);
	base *= 0.85 + 0.3 * fbm(q * 3.0 + 5.0, ps * 3.0, 4);
	float pale = smoothstep(0.55, 0.75, fbm(q * vec2(7.0, 4.0) + 21.0, ps * 7.0, 4));
	base = mix(base, vec3(0.86, 0.85, 0.81), 0.55 * pale);
	float rustBand = smoothstep(0.5, 0.75, noise(vec2(q.x * 1.6, 2.9)));
	float rust = rustBand * smoothstep(0.35, 0.8, fbm(vec2(q.x * 14.0, q.y * 1.8) + 41.0, ps * 14.0, 4));
	base = mix(base, vec3(0.62, 0.39, 0.24), 0.75 * rust);
	float stainLength = 0.08 + 0.25 * hash2(block + 17);
	float stain = exp(-(hi - yw) / stainLength) * smoothstep(0.45, 0.8, fbm(vec2(q.x * 22.0, q.y * 2.0), ps * 22.0, 4));
	base = mix(base, vec3(0.26, 0.24, 0.22), 0.7 * stain);
	base *= 0.92 + 0.16 * grit;
	// Moss and grass on a ledge: where this row sticks out past the next.
	float ledge = smoothstep(0.02, 0.06, stick - aboveStick) * (1.0 - smoothstep(0.0, 0.05, hi - yw));
	float moss = ledge * smoothstep(0.4, 0.65, noise(vec2(q.x * 9.0, hi * 4.0) + 8.0));
	base = mix(base, mix(vec3(0.5, 0.55, 0.22), vec3(0.68, 0.66, 0.3), noise(q * 90.0)), moss);
	h += 0.02 * moss;
	base *= 1.0 - 0.6 * crack;
	colour = base;
	// Grass growing up from the ledge below, where the row below sticks
	// out past this one (in the moss's patches), standing in front.
	float below = rowStick(k - 1);
	float ledgeBelow = smoothstep(0.02, 0.06, below - stick) * step(0.01, rowStrength(k));
	vec3 blade;
	if (ledgeBelow > 0.0 && yw - lo < 0.09
			&& blades(vec2(q.x, yw), false, lo, 0.085 * ledgeBelow, float(k), blade)) {
		colour = blade;
		crack = 0.0;
		plant = 1.0;
		h += below - stick;
	}
	return h;
}

// Conifers standing back from the edge, in tiers, lit from the left.
bool conifer(vec2 q, out vec3 colour) {
	int i0 = int(floor(q.x / TREES));
	for (int n = -1; n <= 1; n++) {
		int t = i0 + n;
		if (hash1(t * 3 + 1) > 0.6) {
			continue;
		}
		float x0 = (float(t) + 0.2 + 0.6 * hash1(t * 5 + 2)) * TREES;
		float tall = 0.12 + 0.45 * pow(hash1(t * 7 + 3), 1.3);
		float base = topEdge(x0) + 0.01 + 0.03 * hash1(t * 11 + 5);
		float u = (q.y - base) / tall;
		if (u < -0.1 || u > 1.0) {
			continue;
		}
		float dx = q.x - x0;
		// Tiers of branches, drooping toward their tips.
		float layers = 8.0 + 6.0 * hash1(t * 9 + 4);
		float v = u + 1.2 * abs(dx) / tall;
		float tier = fract(v * layers + hash1(t));
		float halfWidth = tall * 0.17 * pow(1.0 - max(u, 0.0), 0.9) * (0.7 + 0.3 * tier);
		// Fuzzy, needly edges.
		halfWidth *= 0.6 + 0.6 * noise(q * vec2(300.0, 160.0) + float(t) * 13.0)
				+ 0.25 * (noise(q * 900.0) - 0.5);
		bool trunk = abs(dx) < 0.0035 * tall / 0.3 && u < 0.25;
		if (abs(dx) < halfWidth && u > 0.05 || trunk) {
			float lit = 0.5 + 0.5 * clamp(-dx / max(halfWidth, 1e-4), -1.0, 1.0);
			// Dark, near a silhouette: a little lighter at the sunny edge.
			float needles = noise(q * 380.0 + float(t) * 7.0);
			colour = mix(vec3(0.05, 0.08, 0.05), vec3(0.2, 0.25, 0.13),
					lit * lit * (0.4 + 0.6 * needles));
			if (abs(dx) >= halfWidth || u <= 0.05) {
				colour = vec3(0.12, 0.1, 0.08);
			}
			// A touch hazy, the further back the more.
			colour = mix(colour, vec3(0.8, 0.82, 0.8), 0.12 * hash1(t * 13 + 6));
			return true;
		}
	}
	return false;
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
	if (q.y > edge - 0.6 * SOIL - 0.01 && q.y < edge + 0.12 && blades(q, true, 0.0, 0.085, 1.0, blade)) {
		what = 1.0;
		colour = blade;
		h = 0.2;
	} else if (q.y > edge) {
		// Sky, and conifers standing back from the edge.
		colour = mix(vec3(0.9, 0.9, 0.86), vec3(0.97, 0.97, 0.95), smoothstep(0.0, 0.6, q.y - edge));
		vec3 tree;
		if (conifer(q, tree)) {
			what = 1.0;
			colour = tree;
		}
	} else if (q.y > edge - SOIL) {
		// The soil lip the grass grows from.
		what = 1.0;
		colour = mix(vec3(0.2, 0.17, 0.12), vec3(0.36, 0.3, 0.2), noise(q * 150.0));
		h = 0.12;
	} else {
		h = rock(q, ps, colour, crack, what);
		// Soil washed down the top of the face.
		float wash = smoothstep(0.25, 0.0, edge - q.y) * noise(vec2(q.x * 30.0, q.y * 3.0));
		colour = mix(colour, vec3(0.42, 0.34, 0.25), 0.5 * wash);
	}
	fragColor = vec4(h, packColour(colour), crack, what);
}

//// Buffer C
///float32
// The cliff lit (see the top): worked out only when Buffer B's been, for
// a new view; its top row keeps the view it was lit for.
uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;
uniform sampler2D iChannel2; ///buf:C;

vec4 cliffAt(ivec2 c) {
	ivec2 size = ivec2(iResolution.xy);
	return texelFetch(iChannel1, clamp(c, ivec2(0), size - ivec2(1, 2)), 0);
}

vec3 unpackColour(float v) {
	return vec3(mod(v, 256.0), mod(floor(v / 256.0), 256.0), floor(v / 65536.0)) / 255.0;
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
	vec3 albedo = unpackColour(here.y);
	vec3 col;
	if (here.w > 1.5) {
		col = albedo;
	} else {
		// The light, in screen space (the view may be turned).
		vec3 sun = normalize(SUN);
		vec2 sunXY = rot(view.w) * sun.xy;
		float h0 = here.x;
		float dx = (cliffAt(c + ivec2(1, 0)).x - cliffAt(c - ivec2(1, 0)).x) / (2.0 * ps);
		float dy = (cliffAt(c + ivec2(0, 1)).x - cliffAt(c - ivec2(0, 1)).x) / (2.0 * ps);
		// Sky beside it isn't a cliff to slope down to.
		dx = clamp(dx, -6.0, 6.0);
		dy = clamp(dy, -6.0, 6.0);
		vec3 n = normalize(vec3(-dx, -dy, 1.0));
		vec3 l = vec3(sunXY, sun.z);

		// Soft shadow: march toward the sun, the ray rising as it goes.
		float shadow = 1.0;
		vec2 dir = normalize(sunXY);
		float rise = sun.z / length(sun.xy);
		// (Each pixel's steps start at a different point, else a shadow's
		// edge came out in sawteeth.)
		float t = (1.0 + 1.5 * hash2(c + ivec2(0, 7))) * ps;
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
		float ao = 0.0;
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
		ao = clamp(ao, 0.0, 1.0);

		// Moss on whatever faces up, in patches.
		if (here.w < 0.5) {
			float up = (rot(-view.w) * n.xy).y;
			vec2 q = toCliff(fragCoord, view);
			float moss = smoothstep(0.5, 0.8, up) * smoothstep(0.5, 0.72, noise(q * 11.0 + 5.0));
			albedo = mix(albedo, mix(vec3(0.42, 0.5, 0.17), vec3(0.62, 0.62, 0.28), noise(q * 120.0)), 0.85 * moss);
		}
		vec3 a = pow(albedo, vec3(2.2));
		float diffuse = max(dot(n, l), 0.0);
		float skyLight = 0.55 + 0.45 * n.y * 0.5 + 0.25 * n.z;
		vec3 lit = SUN_COLOUR * diffuse * shadow + SKY_COLOUR * skyLight * ao
				+ BOUNCE_COLOUR * (0.5 - 0.5 * n.y) * ao;
		col = a * lit;
		if (here.w > 0.5) {
			// Plants: no hard sun, just soft and a little translucent.
			col = a * (SUN_COLOUR * (0.35 + 0.4 * shadow) + SKY_COLOUR * 0.7 * ao);
		}
		col = 1.0 - exp(-col * EXPOSURE);
		col = pow(col, vec3(1.0 / 2.2));
	}
	fragColor = vec4(col, 1.0);
}
