// MANDELBROT DIVE - a fast, deep zoom into the Mandelbrot set and its
// Julia sets, after the fractal explorers of the old KPT plug-ins.
//
// FINGERS
//   drag              pan (and fling) - steer while it zooms
//   pinch             zoom about the fingers
//   tap               start or stop zooming in, on the middle of the screen
//   two-finger tap    start or stop zooming out
//   three-finger tap  back to the start
//   long press        the Julia set of the point under the finger; another
//                     long press goes back to the Mandelbrot set where you
//                     left it. (A point outside the Mandelbrot set makes a
//                     Julia set of dust: nothing inside, fast everywhere.)
// The thin bar along the bottom shows how deep you are, out of the deepest
// it can go (MAX_OCTAVES: 2^-107 a pixel, about 10^30 times): green while
// zooming in, orange while zooming out. A red bar along the top means this
// GPU's integer arithmetic isn't doing what the deep maths below needs.
//
// HOW IT'S FAST
// Each frame mostly reuses the last. Every pixel finds where its point was
// on the screen last frame and takes the nearest result there, as long as
// that was worked out near enough to where this pixel now looks. Only
// pixels whose reused result has drifted too far - the newly uncovered
// edges, and pixels blown up as it zooms in - are worked out afresh (with
// a random allowance, so they spread over frames), plus REFINE of the rest
// each frame. So the cost follows how fast the view moves.
// Inside the set an orbit never escapes, so it would run every iteration:
// instead a pixel whose four nearest last frame were all inside is still
// inside (the set has no holes), so only the edges of inside regions are
// redone; an orbit that's settled into a cycle (it comes back to where it
// was, and the cycle pulls in - Brent's cycle finding) stops there, and so
// do points in the two biggest blobs when zoomed out.
//
// HOW IT GOES DEEP (perturbation)
// A phone GPU's floats have about 7 digits: enough for 10^5 times or so.
// Instead:
//   - one reference point (the middle of the view when it was chosen) has
//     its orbit worked out in 128-bit fixed point (eight 16-bit integer
//     limbs, about 37 digits), REF_STEPS iterations a frame, and kept in
//     Buffer A as floats, a texel an iteration;
//   - every pixel iterates only its tiny difference d from that orbit Z, in
//     plain floats: d' = (2Z + d) d + dc. Floats keep 7 digits of the
//     difference however small it is;
//   - when a pixel's orbit comes nearer 0 than its difference (or the
//     reference escapes), it carries on from the start of the orbit of 0
//     (Zhuoran's rebasing), which keeps perturbation's glitches away with
//     one reference. For the Mandelbrot set that's the same orbit; a Julia
//     set has the orbit of 0 worked out too.
// The view's middle is kept in fixed point too. Depth needs the reference
// worked out finer than a pixel (its rounding error grows just as a
// pixel's difference from it does), so 128 bits go to about 2^-110 a
// pixel; past 2^-126, floats themselves run out.
//
// SOFT, NOT SPARKLY
// Each fresh result is worked out at a random spot up to SOFTNESS pixels
// from its pixel's middle, and Buffer C keeps a running average of the
// colours (up to SAMPLES of them), carried along as the view moves - so a
// still picture is many samples a pixel, and fine detail averages out
// instead of sparkling. Less noise still:
//   EDGE          the share of the colour, where the set's edge is nearer
//                 than EDGE_WIDTH pixels (by distance estimation), given
//                 to EDGE_COLOUR instead - detail finer than a pixel drawn
//                 as a calm line rather than noise
//   COLOUR_POWER  below 1, the bands widen as the counts grow, so deep
//                 views have fewer, broader bands
//
//   Buffer A - the view and the gesture in its top row, the reference
//              orbits in the rest
//   Buffer B - each pixel's latest result: its smooth iteration count, how
//              far from this pixel it was worked out, and how far it is
//              from the set's edge
//   Buffer C - each pixel's colour, averaged over its results
//   Image    - Buffer C, and the bars

uniform sampler2D iChannel0; ///buf:C;
uniform sampler2D iChannel1; ///buf:A;
uniform sampler2D iChannel2; ///buf:B;

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	vec4 acc = texelFetch(iChannel0, p, 0);
	vec3 col = clamp(acc.rgb / max(acc.w, 1e-6), 0.0, 1.5);
	if (acc.w <= 0.0) {
		// Nothing averaged yet: the latest result, or grey.
		vec4 b = texelFetch(iChannel2, p, 0);
		col = b.x != 0.0 ? colourOf(b.x, edgeDistance(b.w)) : vec3(0.06);
	}
	// Dither, so the gradients don't band.
	col += (hash13(vec3(fragCoord, float(iFrame % 256))) - 0.5) / 255.0;

	// The depth bar along the bottom.
	vec4 view = stateAt(iChannel1, S_VIEW);
	float thick = max(2.0, floor(iResolution.y / 400.0));
	if (fragCoord.y < thick) {
		bool deep = fragCoord.x < iResolution.x * clamp(view.x / MAX_OCTAVES, 0.0, 1.0);
		vec3 bar = view.y > 0.5 ? vec3(0.4, 1.0, 0.5) : view.y < -0.5 ? vec3(1.0, 0.6, 0.2) : vec3(1.0);
		col = mix(col * 0.5, bar, deep ? 0.8 : 0.0);
	}
	// A red bar along the top when the fixed point doesn't work here.
	if (fragCoord.y > iResolution.y - thick && !fxWorks()) {
		col = vec3(1.0, 0.1, 0.1);
	}
	fragColor = vec4(col, 1.0);
}

//// Common
uniform vec3 pointers[10];
uniform int pointerCount;

#define TAU 6.28318530718

// Where it starts: the middle (START_X, START_Y, each plus its _FINE part,
// for points needing more digits than a float has), how wide the screen's
// shorter side is, and how deep (octaves of zoom past that). The middle's
// in Seahorse Valley (-0.743643887037151, 0.131825904205330), so a tap
// dives in there.
#define START_X -0.7436439
#define START_X_FINE -7.146709e-9
#define START_Y 0.13182591
#define START_Y_FINE -4.8131867e-9
#define START_WIDTH 3.0
#define START_OCTAVES 0.0
// Start on a Julia set instead (1), of the point JULIA_C (the START view
// then on that Julia set).
#define JULIA 0
#define JULIA_C vec2(-0.7436439, 0.13182591)
// How fast tapping zooms, in octaves (doublings) a second.
#define AUTO_ZOOM 1.0
// The deepest it goes, in octaves: a pixel's then about 2^-107.
#define MAX_OCTAVES 100.0
// How fast a fling slows (each frame's share kept).
#define INERTIA 0.92
// How long a long press is, in seconds.
#define HOLD_TIME 0.5

// Iterations: MAX_ITER at the start's depth, ITER_PER_OCTAVE more for each
// octave deeper, up to ITER_LIMIT (the reference orbits' length).
#define MAX_ITER 450
#define ITER_PER_OCTAVE 50.0
#define ITER_LIMIT 20000
// The reference orbits' iterations worked out a frame.
#define REF_STEPS 256
// A new reference once the view's middle is this many screens from it.
#define REF_MOVE 1.5
#define BAILOUT 1e6
// How near an orbit has to come back to where it was to count as a cycle
// (looser finds more insides sooner, but deep down starts calling points
// just outside the set inside: a pixel's z there is a float, too coarse to
// tell it from the reference's).
#define CYCLE_EPSILON 1e-4

// Reuse: a pixel's result is redone once it was worked out more than
// about REUSE pixels (0.5-1.5 times that, at random) further from where the
// pixel looks now than SOFTNESS allows. REFINE is the share of the others
// redone each frame anyway, each a new sample for the average.
#define REUSE 1.0
#define REFINE 0.15
// How far from a pixel's middle its samples are taken (pixels: 0.5 is
// the pixel itself, more is softer), and how many the average keeps.
#define SOFTNESS 0.7
#define SAMPLES 24.0

// Colouring: PALETTE 0 sapphire and gold, 1 rainbow, 2 fire, 3 electric,
// 4 ink. The gradient turns COLOUR_DENSITY times an iteration (of the count
// to the power COLOUR_POWER), and CYCLE times a second.
#define PALETTE 0
#define COLOUR_DENSITY 0.03
#define COLOUR_POWER 1.0
#define COLOUR_OFFSET 0.0
#define CYCLE 0.02
#define INSIDE vec3(0.0)
#define EDGE 0.35
#define EDGE_WIDTH 0.6
#define EDGE_COLOUR vec3(0.0)
#define EMBOSS 0.5
#define EMBOSS_DEPTH 0.6

// Buffer A's top row, texel by texel:
//   S_VIEW       octaves deep, zooming (1 in, -1 out, 0), iterations, MARK
//   S_PREV_VIEW  last frame's octaves, the size, and whether last frame's
//                results can be reused
//   S_MOVED      how far the middle moved since last frame, and the middle
//                less the reference point (complex, as floats)
//   S_REF_STATE  iterations of the reference orbits worked out, the size
//                they're laid out for
//   S_TOUCH      fingers last frame, when the touch started, most fingers,
//                how far it's moved
//   S_POINTS     last frame's two finger points
//   S_FLING      fling velocity (pixels a frame), where the touch started
//   S_MODE       Julia (1) or not, its c, whether this touch has long-pressed
//   S_SAVED_VIEW the Mandelbrot set's octaves, while on a Julia set
//   S_WHERE      the middle, as floats
// and complex numbers in fixed point, FX_TEXELS texels each:
//   S_CENTRE     the view's middle
//   S_REF        the reference point
//   S_SAVED      the Mandelbrot set's middle, while on a Julia set
//   S_CHECK      the reference orbit at its last iteration worked out
//   S_CHECK2     the orbit of 0's (a Julia set's)
#define S_VIEW 0
#define S_PREV_VIEW 1
#define S_MOVED 2
#define S_REF_STATE 3
#define S_TOUCH 4
#define S_POINTS 5
#define S_FLING 6
#define S_MODE 7
#define S_SAVED_VIEW 8
#define S_WHERE 9
#define S_CENTRE 10
#define S_REF (S_CENTRE + FX_TEXELS)
#define S_SAVED (S_REF + FX_TEXELS)
#define S_CHECK (S_SAVED + FX_TEXELS)
#define S_CHECK2 (S_CHECK + FX_TEXELS)
#define STATES (S_CHECK2 + FX_TEXELS)
#define MARK 0.777

vec4 stateAt(sampler2D a, int i) {
	return texelFetch(a, ivec2(i, int(iResolution.y) - 1), 0);
}

// The reference orbits' length: the view's middle's orbit is texels
// 0 to refLimit() - 1, below the top row, and (on a Julia set) the orbit
// of 0 the next refLimit().
int refLimit() {
	return min(ITER_LIMIT, int(iResolution.x) * (int(iResolution.y) - 1) / 2);
}

ivec2 refTexel(int i, int w) {
	return ivec2(i % w, i / w);
}

// The complex plane's width a pixel, octaves deep.
float pixelScale(float octaves) {
	return START_WIDTH * exp2(-octaves) / min(iResolution.x, iResolution.y);
}

// Fixed point: LIMBS 16-bit limbs (each in a uint, most significant
// first), two's complement over all 16 LIMBS bits, the point 4 bits in -
// so -8 to 8, to 2^-(16 LIMBS - 4). Integers, so exact on any GPU (no
// fused multiply-adds or compiler shortcuts to dodge), and longer for
// deeper. A complex number's two parts are stored 4 limbs a texel, as
// floats (exact up to 2^24).
const int LIMBS = 8;
#define FX_TEXELS (LIMBS / 2)
#define LIMB 65536.0

void fxNeg(inout uint a[LIMBS]) {
	uint carry = 1u;
	for (int k = LIMBS - 1; k >= 0; k--) {
		uint t = (65535u - a[k]) + carry;
		a[k] = t & 65535u;
		carry = t >> 16;
	}
}

void fxAdd(uint a[LIMBS], uint b[LIMBS], out uint r[LIMBS]) {
	uint carry = 0u;
	for (int k = LIMBS - 1; k >= 0; k--) {
		uint t = a[k] + b[k] + carry;
		r[k] = t & 65535u;
		carry = t >> 16;
	}
}

void fxSub(uint a[LIMBS], uint b[LIMBS], out uint r[LIMBS]) {
	uint n[LIMBS] = b;
	fxNeg(n);
	fxAdd(a, n, r);
}

// Exactly: each step multiplies by 2^16 and takes off the whole part.
void fxFromFloat(float v, out uint r[LIMBS]) {
	float a = abs(v) / 16.0;
	for (int k = 0; k < LIMBS; k++) {
		a *= LIMB;
		float whole = floor(a);
		r[k] = uint(whole);
		a -= whole;
	}
	if (v < 0.0) {
		fxNeg(r);
	}
}

float fxToFloat(uint a[LIMBS]) {
	bool negative = a[0] >= 32768u;
	uint m[LIMBS] = a;
	if (negative) {
		fxNeg(m);
	}
	float v = 0.0;
	for (int k = LIMBS - 1; k >= 0; k--) {
		v = v / LIMB + float(m[k]);
	}
	v *= 16.0 / LIMB;
	return negative ? -v : v;
}

// a b, the bits below the last limb dropped (|a b| must be under 8).
void fxMul(uint a[LIMBS], uint b[LIMBS], out uint r[LIMBS]) {
	bool negative = (a[0] >= 32768u) != (b[0] >= 32768u);
	uint x[LIMBS] = a, y[LIMBS] = b;
	if (x[0] >= 32768u) {
		fxNeg(x);
	}
	if (y[0] >= 32768u) {
		fxNeg(y);
	}
	// Column sums of the 16x16-bit products, each split in its two halves.
	uint acc[LIMBS + 1];
	for (int k = 0; k <= LIMBS; k++) {
		acc[k] = 0u;
	}
	for (int i = 0; i < LIMBS; i++) {
		for (int j = 0; j < LIMBS - i; j++) {
			uint p = x[i] * y[j];
			acc[i + j] += p >> 16;
			acc[i + j + 1] += p & 65535u;
		}
	}
	for (int k = LIMBS; k > 0; k--) {
		acc[k - 1] += acc[k] >> 16;
		acc[k] &= 65535u;
	}
	// Times 16, for the point 4 bits in.
	for (int k = 0; k < LIMBS; k++) {
		r[k] = ((acc[k] << 4) | (acc[k + 1] >> 12)) & 65535u;
	}
	if (negative) {
		fxNeg(r);
	}
}

// One step of z^2 + c, on fixed-point parts.
void fxStep(inout uint zr[LIMBS], inout uint zi[LIMBS], uint cr[LIMBS], uint ci[LIMBS]) {
	uint rr[LIMBS], ii[LIMBS], ri[LIMBS], t[LIMBS];
	fxMul(zr, zr, rr);
	fxMul(zi, zi, ii);
	fxMul(zr, zi, ri);
	fxSub(rr, ii, t);
	fxAdd(t, cr, zr);
	fxAdd(ri, ri, t);
	fxAdd(t, ci, zi);
}

// A fixed-point complex number from Buffer A's top row, and texel t of one.
void fxLoad(sampler2D a, int at, out uint re[LIMBS], out uint im[LIMBS]) {
	for (int t = 0; t < FX_TEXELS / 2; t++) {
		vec4 v = stateAt(a, at + t), w = stateAt(a, at + FX_TEXELS / 2 + t);
		for (int k = 0; k < 4; k++) {
			re[4 * t + k] = uint(v[k]);
			im[4 * t + k] = uint(w[k]);
		}
	}
}

vec4 fxTexel(uint re[LIMBS], uint im[LIMBS], int t) {
	vec4 v;
	for (int k = 0; k < 4; k++) {
		int i = 4 * (t % (FX_TEXELS / 2)) + k;
		v[k] = float(t < FX_TEXELS / 2 ? re[i] : im[i]);
	}
	return v;
}

// Whether this GPU's integer arithmetic does what the fixed point needs:
// 1.5^2 + 2^-100 - 2.25 must leave just 2^-100.
bool fxWorks() {
	uint a[LIMBS], b[LIMBS], r[LIMBS];
	fxFromFloat(-1.5, a);
	fxMul(a, a, r);
	fxFromFloat(exp2(-100.0), b);
	fxAdd(r, b, r);
	fxFromFloat(2.25, a);
	fxSub(r, a, r);
	return fxToFloat(r) == exp2(-100.0);
}

vec2 cmul(vec2 a, vec2 b) {
	return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
}

// Dave Hoskins' hash without sine (no big integer constants: the test
// phone's GPU compiler mangles those).
float hash13(vec3 p) {
	p = fract(p * 0.1031);
	p += dot(p, p.zyx + 31.32);
	return fract((p.x + p.y) * p.z);
}

// A gradient through stops, round and round (t's whole part ignored).
vec3 stops(float t, vec3 a, vec3 b, vec3 c, vec3 d, vec3 e) {
	t = fract(t) * 5.0;
	float f = smoothstep(0.0, 1.0, fract(t));
	int i = int(t);
	if (i == 0) return mix(a, b, f);
	if (i == 1) return mix(b, c, f);
	if (i == 2) return mix(c, d, f);
	if (i == 3) return mix(d, e, f);
	return mix(e, a, f);
}

vec3 cosine(float t, vec3 a, vec3 b, vec3 c, vec3 d) {
	return a + b * cos(TAU * (c * t + d));
}

vec3 gradient(float t) {
	// Sapphire and gold
	if (PALETTE == 0) return stops(t, vec3(0.01, 0.02, 0.12), vec3(0.05, 0.25, 0.65), vec3(0.85, 0.95, 1.0),
			vec3(1.0, 0.75, 0.2), vec3(0.45, 0.12, 0.02));
	// Rainbow
	if (PALETTE == 1) return cosine(t, vec3(0.5), vec3(0.5), vec3(1.0), vec3(0.0, 0.33, 0.67));
	// Fire
	if (PALETTE == 2) return stops(t, vec3(0.0), vec3(0.5, 0.02, 0.0), vec3(1.0, 0.35, 0.0), vec3(1.0, 0.85, 0.3),
			vec3(1.0, 1.0, 0.9));
	// Electric
	if (PALETTE == 3) return cosine(t, vec3(0.5), vec3(0.5), vec3(2.0, 1.0, 0.0), vec3(0.5, 0.2, 0.25));
	// Ink
	return vec3(0.5 + 0.5 * cos(TAU * t));
}

// Buffer B's results: x is 0 for none, the smooth count + 1 outside, minus
// the iterations tried inside; w the distance to the set's edge in pixels,
// or -1 minus it when the result's reused rather than fresh this frame.
float edgeDistance(float w) {
	return w >= 0.0 ? w : -1.0 - w;
}

// A result's colour, before the emboss.
vec3 colourOf(float x, float edge) {
	if (x < 0.0) {
		return INSIDE;
	}
	float t = COLOUR_DENSITY * pow(x - 1.0, COLOUR_POWER) + CYCLE * iTime + COLOUR_OFFSET;
	vec3 col = gradient(t);
	return mix(col, EDGE_COLOUR, EDGE * (1.0 - smoothstep(0.0, EDGE_WIDTH, edge)));
}

//// Buffer A
///float32
///stateRow:top
uniform sampler2D iChannel0; ///buf:A;

// Pointers come in pixels (or 0-1, from some sources).
vec2 scaled(vec2 p) {
	return p.x <= 1.5 && p.y <= 1.5 ? p * iResolution.xy : p;
}

// Moves a fixed-point complex number by a float one.
void move(inout uint re[LIMBS], inout uint im[LIMBS], vec2 d) {
	uint t[LIMBS];
	fxFromFloat(d.x, t);
	fxAdd(re, t, re);
	fxFromFloat(d.y, t);
	fxAdd(im, t, im);
}

vec2 difference(uint ar[LIMBS], uint ai[LIMBS], uint br[LIMBS], uint bi[LIMBS]) {
	uint t[LIMBS];
	fxSub(ar, br, t);
	float x = fxToFloat(t);
	fxSub(ai, bi, t);
	return vec2(x, fxToFloat(t));
}

// The reference orbits, as last frame left them: orbit 0 is the view's
// middle's (Mandelbrot: from 0, c the reference point; Julia: from the
// reference point, c the Julia set's), orbit 1 a Julia set's orbit of 0.
// Its start, and its c.
void orbitStart(int orbit, vec4 mode, out uint zr[LIMBS], out uint zi[LIMBS], out uint cr[LIMBS],
		out uint ci[LIMBS]) {
	bool julia = mode.x > 0.5;
	uint rr[LIMBS], ri[LIMBS];
	fxLoad(iChannel0, S_REF, rr, ri);
	fxFromFloat(0.0, zr);
	fxFromFloat(0.0, zi);
	if (julia) {
		fxFromFloat(mode.y, cr);
		fxFromFloat(mode.z, ci);
		if (orbit == 0) {
			zr = rr;
			zi = ri;
		}
	} else {
		cr = rr;
		ci = ri;
	}
}

// Iteration k of an orbit, from the last one worked out (S_CHECK, at
// done - 1) or its start: none past its escape.
void orbitAt(int orbit, int k, int done, vec4 mode, out uint zr[LIMBS], out uint zi[LIMBS]) {
	uint cr[LIMBS], ci[LIMBS];
	orbitStart(orbit, mode, zr, zi, cr, ci);
	int from = 0;
	if (done > 0) {
		fxLoad(iChannel0, orbit == 0 ? S_CHECK : S_CHECK2, zr, zi);
		from = done - 1;
	}
	for (int n = from; n < k; n++) {
		float x = fxToFloat(zr), y = fxToFloat(zi);
		if (x * x + y * y > 4.0) {
			break;
		}
		fxStep(zr, zi, cr, ci);
	}
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 c = ivec2(fragCoord);
	ivec2 size = ivec2(iResolution.xy);
	vec4 view = stateAt(iChannel0, S_VIEW);
	vec4 refState = stateAt(iChannel0, S_REF_STATE);
	vec4 mode = stateAt(iChannel0, S_MODE);
	bool fresh = abs(view.w - MARK) > 0.01;
	bool laidOut = !fresh && int(refState.y) == size.x && int(refState.z) == size.y;
	int limit = refLimit();
	int done = int(refState.x + 0.5);
	bool julia = mode.x > 0.5;
	int next = min(done + REF_STEPS, limit);

	if (c.y != size.y - 1) {
		// The reference orbits, as floats for the pixels: the next
		// REF_STEPS iterations after those already worked out.
		int i = c.x + c.y * size.x;
		int orbit = julia && i >= limit ? 1 : 0;
		int k = i - orbit * limit;
		if (!laidOut || k < done || k >= next) {
			fragColor = texelFetch(iChannel0, c, 0);
			return;
		}
		uint zr[LIMBS], zi[LIMBS];
		orbitAt(orbit, k, done, mode, zr, zi);
		fragColor = vec4(fxToFloat(zr), fxToFloat(zi), 0.0, 0.0);
		return;
	}
	if (c.x >= STATES) {
		fragColor = vec4(0.0);
		return;
	}
	if (c.x >= S_CHECK) {
		// Each orbit's last iteration worked out, in fixed point, to carry
		// on from next frame.
		int orbit = c.x >= S_CHECK2 ? 1 : 0;
		uint zr[LIMBS], zi[LIMBS];
		if (laidOut && next > 0) {
			orbitAt(orbit, next - 1, done, mode, zr, zi);
		}
		fragColor = fxTexel(zr, zi, c.x - (orbit == 0 ? S_CHECK : S_CHECK2));
		return;
	}

	vec2 res = iResolution.xy;
	vec2 half_ = 0.5 * res;
	float dt = iTimeDelta > 0.0 ? min(iTimeDelta, 0.1) : 1.0 / 60.0;
	uint cr[LIMBS], ci[LIMBS], rr[LIMBS], ri[LIMBS], sr[LIMBS], si[LIMBS];
	fxLoad(iChannel0, S_CENTRE, cr, ci);
	fxLoad(iChannel0, S_REF, rr, ri);
	fxLoad(iChannel0, S_SAVED, sr, si);
	uint lastR[LIMBS] = cr, lastI[LIMBS] = ci;
	float octaves = view.x;
	float zooming = view.y;
	vec4 touch = stateAt(iChannel0, S_TOUCH);
	int fingers = int(touch.x + 0.5);
	float started = touch.y;
	float most = touch.z;
	float travel = touch.w;
	vec4 points = stateAt(iChannel0, S_POINTS);
	vec2 last0 = points.xy, last1 = points.zw;
	vec4 fling = stateAt(iChannel0, S_FLING);
	vec2 velocity = fling.xy;
	vec2 origin = fling.zw;
	float savedOctaves = stateAt(iChannel0, S_SAVED_VIEW).x;
	bool reset = fresh;
	bool switched = false;

	// The touch: a tap is short and hardly moves; a long press is long and
	// hardly moves.
	vec2 p0 = scaled(pointers[0].xy), p1 = scaled(pointers[1].xy);
	if (pointerCount > 0 && fingers == 0) {
		started = iTime;
		most = 0.0;
		travel = 0.0;
		origin = p0;
		mode.w = 0.0;
	}
	bool still = travel < 0.03 * min(res.x, res.y);
	float scale = pixelScale(octaves);
	if (pointerCount > 0) {
		most = max(most, float(pointerCount));
		travel = max(travel, length(p0 - origin));
		if (pointerCount == 1 && most < 1.5 && still && mode.w < 0.5 && iTime - started > HOLD_TIME) {
			mode.w = 1.0;
			switched = true;
			if (mode.x < 0.5) {
				// To the Julia set of the point under the finger.
				vec2 point = vec2(fxToFloat(cr), fxToFloat(ci)) + (p0 - half_) * scale;
				sr = cr;
				si = ci;
				savedOctaves = octaves;
				mode.xyz = vec3(1.0, point);
				fxFromFloat(0.0, cr);
				fxFromFloat(0.0, ci);
				octaves = 0.0;
			} else {
				mode.x = 0.0;
				cr = sr;
				ci = si;
				octaves = savedOctaves;
			}
			zooming = 0.0;
			velocity = vec2(0.0);
			scale = pixelScale(octaves);
		}
	} else if (fingers > 0 && iTime - started < 0.35 && still) {
		if (most < 1.5) {
			zooming = zooming > 0.5 ? 0.0 : 1.0;
		} else if (most < 2.5) {
			zooming = zooming < -0.5 ? 0.0 : -1.0;
		} else {
			reset = true;
		}
	}

	if (pointerCount >= 2) {
		if (fingers >= 2) {
			// Pinch: the point between the fingers stays between them.
			vec2 mid = 0.5 * (p0 + p1), lastMid = 0.5 * (last0 + last1);
			float grow = max(length(p1 - p0), 1.0) / max(length(last1 - last0), 1.0);
			float next = clamp(octaves + log2(grow), -2.0, MAX_OCTAVES);
			float nextScale = pixelScale(next);
			move(cr, ci, (lastMid - half_) * scale - (mid - half_) * nextScale);
			octaves = next;
			scale = nextScale;
		}
		velocity = vec2(0.0);
	} else if (pointerCount == 1) {
		if (fingers == 1 && !switched) {
			vec2 delta = p0 - last0;
			move(cr, ci, -delta * scale);
			velocity = mix(velocity, delta, 0.5);
		} else {
			velocity = vec2(0.0);
		}
	} else if (length(velocity) > 0.05) {
		move(cr, ci, -velocity * scale);
		velocity *= INERTIA;
	} else {
		velocity = vec2(0.0);
	}
	if (pointerCount < 2 && zooming != 0.0) {
		octaves += AUTO_ZOOM * dt * zooming;
		if (octaves >= MAX_OCTAVES || octaves <= -2.0) {
			zooming = 0.0;
		}
		octaves = clamp(octaves, -2.0, MAX_OCTAVES);
		scale = pixelScale(octaves);
	}
	fingers = pointerCount;
	last0 = p0;
	last1 = p1;
	// Inside fixed point's -8 to 8 (and well past the set).
	vec2 where = vec2(fxToFloat(cr), fxToFloat(ci));
	if (abs(where.x) > 4.0) {
		fxFromFloat(clamp(where.x, -4.0, 4.0), cr);
	}
	if (abs(where.y) > 4.0) {
		fxFromFloat(clamp(where.y, -4.0, 4.0), ci);
	}

	if (reset) {
		uint t[LIMBS];
		fxFromFloat(START_X, cr);
		fxFromFloat(START_X_FINE, t);
		fxAdd(cr, t, cr);
		fxFromFloat(START_Y, ci);
		fxFromFloat(START_Y_FINE, t);
		fxAdd(ci, t, ci);
		octaves = START_OCTAVES;
		zooming = 0.0;
		velocity = vec2(0.0);
		mode.xyz = vec3(float(JULIA), JULIA_C);
		sr = cr;
		si = ci;
		savedOctaves = octaves;
		if (JULIA > 0) {
			// The START view's on the Julia set; a long press goes to the
			// whole Mandelbrot set round JULIA_C.
			fxFromFloat(JULIA_C.x, sr);
			fxFromFloat(JULIA_C.y, si);
			savedOctaves = 0.0;
		}
		scale = pixelScale(octaves);
	}
	float prevOctaves = fresh ? octaves : view.x;
	// Last frame's results are reusable if it was this size and the same set.
	bool reusable = !fresh && !reset && !switched && int(stateAt(iChannel0, S_PREV_VIEW).y) == size.x
			&& int(stateAt(iChannel0, S_PREV_VIEW).z) == size.y;
	vec2 moved = reusable ? difference(cr, ci, lastR, lastI) : vec2(0.0);

	// A new reference when the view's gone too far from it.
	vec2 off = difference(cr, ci, rr, ri);
	float doneNow;
	if (reset || switched || !laidOut || length(off) > REF_MOVE * scale * max(res.x, res.y)) {
		rr = cr;
		ri = ci;
		off = vec2(0.0);
		doneNow = 0.0;
	} else {
		doneNow = float(next);
	}
	float iterations = clamp(float(MAX_ITER) + ITER_PER_OCTAVE * max(octaves, 0.0), 16.0, float(limit - 1));

	vec4 o;
	if (c.x == S_VIEW) o = vec4(octaves, zooming, iterations, MARK);
	else if (c.x == S_PREV_VIEW) o = vec4(prevOctaves, res, reusable ? 1.0 : 0.0);
	else if (c.x == S_MOVED) o = vec4(moved, off);
	else if (c.x == S_REF_STATE) o = vec4(doneNow, res, 0.0);
	else if (c.x == S_TOUCH) o = vec4(float(fingers), started, most, travel);
	else if (c.x == S_POINTS) o = vec4(last0, last1);
	else if (c.x == S_FLING) o = vec4(velocity, origin);
	else if (c.x == S_MODE) o = mode;
	else if (c.x == S_SAVED_VIEW) o = vec4(savedOctaves, 0.0, 0.0, 0.0);
	else if (c.x == S_WHERE) o = vec4(fxToFloat(cr), fxToFloat(ci), 0.0, 0.0);
	else if (c.x < S_REF) o = fxTexel(cr, ci, c.x - S_CENTRE);
	else if (c.x < S_SAVED) o = fxTexel(rr, ri, c.x - S_REF);
	else o = fxTexel(sr, si, c.x - S_SAVED);
	fragColor = o;
}

//// Buffer B
///float32
uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;

// Each pixel's latest result (see edgeDistance()): the count, where it was
// worked out (pixels from this pixel's middle), and the edge's distance.
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 res = iResolution.xy;
	ivec2 size = ivec2(res);
	vec4 view = stateAt(iChannel0, S_VIEW);
	vec4 prevView = stateAt(iChannel0, S_PREV_VIEW);
	vec4 moves = stateAt(iChannel0, S_MOVED);
	vec4 mode = stateAt(iChannel0, S_MODE);
	int done = int(stateAt(iChannel0, S_REF_STATE).x + 0.5);
	float scale = pixelScale(view.x);
	int iterations = int(view.z);
	vec2 q = fragCoord - 0.5 * res;

	// The nearest of last frame's results, of the four round where this
	// pixel's point was.
	vec4 best = vec4(0.0);
	vec2 bestOff = vec2(0.0);
	float bestDist = 1e30;
	int insides = 0;
	if (prevView.w > 0.5) {
		float prevScale = pixelScale(prevView.x);
		vec2 moved = moves.xy / prevScale;
		vec2 then = moved + q * (scale / prevScale) + 0.5 * res;
		ivec2 b = ivec2(floor(then - 0.5));
		for (int k = 0; k < 4; k++) {
			ivec2 t = b + ivec2(k & 1, k >> 1);
			if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, size))) {
				continue;
			}
			vec4 v = texelFetch(iChannel1, t, 0);
			if (v.x == 0.0) {
				continue;
			}
			if (v.x < 0.0) {
				insides++;
			}
			vec2 at = (vec2(t) + 0.5 + v.yz - 0.5 * res - moved) * (prevScale / scale) - q;
			float d = dot(at, at);
			if (d < bestDist) {
				bestDist = d;
				best = v;
				bestOff = at;
			}
		}
	}

	float frame = float(iFrame % 1024);
	float h = hash13(vec3(fragCoord, frame));
	float h2 = hash13(vec3(fragCoord.yx, frame + 0.5));
	float dist = sqrt(bestDist);
	// Inside with all four round it inside: still inside (the set has no
	// holes), so only the edges of inside regions are redone as it zooms.
	bool solid = insides == 4;
	bool redo = bestDist > 1e29 || (!solid && dist > SOFTNESS + REUSE * (0.5 + h)) || h2 < (solid ? 0.1 : 1.0) * REFINE
			|| (!solid && best.x < 0.0 && -best.x < 0.9 * float(iterations) && h2 < 2.0 * REFINE);
	if (redo && done > 1) {
		bool julia = mode.x > 0.5;
		int limit = refLimit();
		// A random spot up to SOFTNESS pixels from the middle.
		float a = TAU * hash13(vec3(fragCoord, frame + 0.25));
		vec2 jitter = SOFTNESS * sqrt(hash13(vec3(fragCoord.yx, frame + 0.75))) * vec2(cos(a), sin(a));
		vec2 d0 = moves.zw + (q + jitter) * scale;
		// The two biggest blobs, when zoomed out far enough for floats.
		if (!julia && scale > 1e-6) {
			vec2 c = stateAt(iChannel0, S_WHERE).xy + (q + jitter) * scale;
			float r = length(c - vec2(0.25, 0.0));
			if (c.x < r - 2.0 * r * r + 0.25 || dot(c + vec2(1.0, 0.0), c + vec2(1.0, 0.0)) < 0.0625) {
				fragColor = vec4(-float(iterations), jitter, 0.0);
				return;
			}
		}
		// z = Z + d, Z from the reference orbit at m (from base): the
		// Mandelbrot set's from 0 with dc, a Julia set's from the middle
		// with d its offset. dz is z's rate of change across the screen, a
		// pixel's worth: for the distance to the edge.
		vec2 dc = julia ? vec2(0.0) : d0;
		vec2 d = julia ? d0 : vec2(0.0);
		int base = 0, m = 0;
		vec2 Z = julia ? texelFetch(iChannel0, refTexel(0, size.x), 0).xy : vec2(0.0);
		vec2 z = Z + d;
		vec2 dz = julia ? vec2(scale, 0.0) : vec2(0.0);
		// Cycle finding: where z and d were at the last checkpoint, and
		// the cycle's pull since (the product of 2z).
		vec2 zSaved = vec2(1e9), dSaved = vec2(0.0), pull = vec2(1.0, 0.0);
		int checkpoint = 8;
		float x = -float(iterations);
		float edge = 0.0;
		bool ok = true;
		for (int n = 0; n < iterations; n++) {
			dz = 2.0 * cmul(z, dz) + (julia ? vec2(0.0) : vec2(scale, 0.0));
			pull = 2.0 * cmul(pull, z);
			d = cmul(2.0 * Z + d, d) + dc;
			m++;
			if (m >= done) {
				// The reference isn't worked out this far yet.
				ok = false;
				break;
			}
			Z = texelFetch(iChannel0, refTexel(base + m, size.x), 0).xy;
			z = Z + d;
			float r2 = dot(z, z);
			if (r2 > BAILOUT) {
				x = max(float(n) + 2.0 - log2(log2(r2)), 0.0) + 1.0;
				// Distance estimation, in pixels: |z| log|z| / |dz|.
				edge = sqrt(r2) * 0.5 * log(r2) / max(length(dz), 1e-30);
				break;
			}
			// Back where it was at the checkpoint, the difference from the
			// reference holding steady (not just following a reference
			// that's in a cycle), and pulled in: inside.
			// (As the largest part, not squared: deep down d's square is
			// below the smallest float, and 0 <= 0 would call it steady.)
			vec2 dd = abs(d - dSaved), ad = abs(d);
			if (dot(z - zSaved, z - zSaved) < CYCLE_EPSILON * CYCLE_EPSILON
					&& max(dd.x, dd.y) <= 0.1 * max(ad.x, ad.y) && dot(pull, pull) < 1.0) {
				break;
			}
			if (n == checkpoint) {
				zSaved = z;
				dSaved = d;
				pull = vec2(1.0, 0.0);
				checkpoint *= 2;
			}
			if (dot(pull, pull) > 1e20) {
				pull = normalize(pull) * 1e10;
			}
			// Rebase: nearer 0 than the reference, or past its escape.
			if (r2 < dot(d, d) || dot(Z, Z) > 4.0) {
				d = z;
				m = 0;
				base = julia ? limit : 0;
				Z = vec2(0.0);
			}
		}
		if (ok) {
			fragColor = vec4(x, jitter, edge);
			return;
		}
	}
	fragColor = bestDist < 1e29 ? vec4(best.x, bestOff, -1.0 - edgeDistance(best.w)) : vec4(0.0);
}

//// Buffer C
uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;
uniform sampler2D iChannel2; ///buf:C;

// Last frame's sums at a point, by Catmull-Rom over the 4x4 texels round
// it: bilinear, repeated every frame as it zooms, blurs it away.
vec4 history(vec2 at, ivec2 size) {
	vec2 t = at - 0.5;
	ivec2 b = ivec2(floor(t));
	vec2 f = t - vec2(b);
	vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
	vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
	vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
	vec2 w3 = f * f * (-0.5 + 0.5 * f);
	vec4 wx = vec4(w0.x, w1.x, w2.x, w3.x), wy = vec4(w0.y, w1.y, w2.y, w3.y);
	vec4 sum = vec4(0.0);
	for (int j = 0; j < 4; j++) {
		for (int i = 0; i < 4; i++) {
			sum += wx[i] * wy[j] * texelFetch(iChannel2, clamp(b + ivec2(i - 1, j - 1), ivec2(0), size - 1), 0);
		}
	}
	return max(sum, vec4(0.0));
}

// A result's count, for the emboss: inside counts as the one it's beside.
float heightAt(ivec2 p, ivec2 size, float here) {
	vec4 v = texelFetch(iChannel1, clamp(p, ivec2(0), size - 1), 0);
	return v.x > 0.0 ? v.x : here;
}

// Each pixel's colour, averaged over its results: rgb is their sum, w how
// many (so blending neighbours as it's carried along with the view weighs
// each by its count).
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 res = iResolution.xy;
	ivec2 size = ivec2(res);
	ivec2 p = ivec2(fragCoord);
	vec4 view = stateAt(iChannel0, S_VIEW);
	vec4 prevView = stateAt(iChannel0, S_PREV_VIEW);
	float scale = pixelScale(view.x);

	vec4 acc = vec4(0.0);
	if (prevView.w > 0.5) {
		float prevScale = pixelScale(prevView.x);
		vec2 then = stateAt(iChannel0, S_MOVED).xy / prevScale + (fragCoord - 0.5 * res) * (scale / prevScale) + 0.5 * res;
		if (all(greaterThanEqual(then, vec2(0.0))) && all(lessThan(then, res))) {
			acc = history(then, size);
			// Zooming in, each old pixel's spread over more new ones: worth
			// that much less.
			float grow = min(scale / prevScale, 1.0);
			acc *= grow * grow;
		}
	}

	vec4 b = texelFetch(iChannel1, p, 0);
	if (b.x != 0.0 && (b.w >= 0.0 || acc.w <= 0.0)) {
		vec3 col = colourOf(b.x, edgeDistance(b.w));
		if (EMBOSS > 0.0 && b.x > 0.0) {
			float dx = heightAt(p + ivec2(1, 0), size, b.x) - heightAt(p - ivec2(1, 0), size, b.x);
			float dy = heightAt(p + ivec2(0, 1), size, b.x) - heightAt(p - ivec2(0, 1), size, b.x);
			// As slopes: a band a pixel wide is steep, however deep.
			vec3 n = normalize(vec3(-vec2(dx, dy) * EMBOSS_DEPTH, 1.0));
			vec3 l = normalize(vec3(-0.5, 0.6, 0.65));
			float lit = max(dot(n, l), 0.0);
			float spec = pow(max(dot(reflect(-l, n), vec3(0.0, 0.0, 1.0)), 0.0), 24.0);
			col = col * mix(1.0, 0.35 + 0.85 * lit, EMBOSS) + EMBOSS * 0.4 * spec;
		}
		// A reused result counts only when there's nothing else.
		float w = b.w >= 0.0 ? 1.0 : 0.01;
		acc += vec4(col, 1.0) * w;
		acc *= min(SAMPLES / acc.w, 1.0);
	}
	fragColor = acc;
}
