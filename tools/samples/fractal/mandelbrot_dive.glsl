// MANDELBROT DIVE - a fast, deep zoom into the Mandelbrot set, after the
// fractal explorers of the old KPT plug-ins.
//
// FINGERS
//   drag              pan (and fling) - steer while it zooms
//   pinch             zoom about the fingers
//   tap               start or stop zooming in, on the middle of the screen
//   two-finger tap    start or stop zooming out
//   three-finger tap  back to the start
// The thin bar along the bottom shows how deep you are, out of the deepest
// it can go (2^-44 a pixel, about 10^11 times): green while zooming in,
// orange while zooming out. A red bar along the top means this GPU's
// floats don't do the double-float tricks below, so deep views will be
// blocky.
//
// HOW IT'S FAST
// Each frame mostly reuses the last. Every pixel finds where its point was
// on the screen last frame and takes the nearest result there, as long as
// that was worked out near enough to where this pixel now looks. Only
// pixels whose reused result has drifted too far - the newly uncovered
// edges, and pixels blown up as it zooms in - are worked out afresh (with
// a random allowance, so they spread over frames), plus a few each frame
// to sharpen a still picture. So the cost follows how fast the view moves.
//
// HOW IT GOES DEEP (perturbation)
// A phone GPU's floats have about 7 digits: enough for 10^5 times or so.
// Instead:
//   - one reference point (the middle of the view when it was chosen) has
//     its orbit worked out in double-float arithmetic (each number two
//     floats, about 14 digits), REF_STEPS iterations a frame, and kept in
//     Buffer A, a texel an iteration;
//   - every pixel iterates only its tiny difference d from that orbit Z, in
//     plain floats: d' = (2Z + d) d + dc. Floats keep 7 digits of the
//     difference however small it is;
//   - when a pixel's orbit comes nearer 0 than its difference (or the
//     reference escapes), it starts again from the reference's start
//     (Zhuoran's rebasing), which keeps perturbation's glitches away with
//     a single reference.
// The view's middle is kept in double-float too, which is what limits the
// depth.
//
//   Buffer A - the view and the gesture in its top row, the reference
//              orbit in the rest
//   Buffer B - each pixel's smooth iteration count, reused or worked out
//              afresh, and how far from this pixel it was worked out
//   Image    - colouring: a gradient through the bands, cycling, and a
//              soft emboss

uniform sampler2D iChannel0; ///buf:B;
uniform sampler2D iChannel1; ///buf:A;

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

// A pixel's smooth count, for the emboss: inside counts as its neighbour.
float heightAt(ivec2 p, ivec2 size, float here) {
	vec4 v = texelFetch(iChannel0, clamp(p, ivec2(0), size - 1), 0);
	return v.w > 1.5 && v.x >= 0.0 ? v.x : here;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 size = ivec2(iResolution.xy);
	ivec2 p = ivec2(fragCoord);
	vec4 v = texelFetch(iChannel0, p, 0);
	vec3 col;
	if (v.w < 1.5) {
		// Not worked out yet.
		col = vec3(0.06);
	} else if (v.x < 0.0) {
		col = INSIDE;
	} else {
		float t = COLOUR_DENSITY * v.x + CYCLE * iTime + COLOUR_OFFSET;
		col = gradient(t);
		if (EMBOSS > 0.0) {
			float dx = heightAt(p + ivec2(1, 0), size, v.x) - heightAt(p - ivec2(1, 0), size, v.x);
			float dy = heightAt(p + ivec2(0, 1), size, v.x) - heightAt(p - ivec2(0, 1), size, v.x);
			// As slopes: a band a pixel wide is steep, however deep.
			vec3 n = normalize(vec3(-vec2(dx, dy) * EMBOSS_DEPTH, 1.0));
			vec3 l = normalize(vec3(-0.5, 0.6, 0.65));
			float lit = max(dot(n, l), 0.0);
			float spec = pow(max(dot(reflect(-l, n), vec3(0.0, 0.0, 1.0)), 0.0), 24.0);
			col = col * mix(1.0, 0.35 + 0.85 * lit, EMBOSS) + EMBOSS * 0.4 * spec;
		}
	}

	// The depth bar along the bottom.
	vec4 view = stateAt(iChannel1, S_VIEW);
	float thick = max(2.0, floor(iResolution.y / 400.0));
	if (fragCoord.y < thick) {
		bool deep = fragCoord.x < iResolution.x * clamp(view.x / MAX_OCTAVES, 0.0, 1.0);
		vec3 bar = view.y > 0.5 ? vec3(0.4, 1.0, 0.5) : view.y < -0.5 ? vec3(1.0, 0.6, 0.2) : vec3(1.0);
		col = mix(col * 0.5, bar, deep ? 0.8 : 0.0);
	}
	// A red bar along the top when double-float arithmetic doesn't work here.
	if (fragCoord.y > iResolution.y - thick && !dfWorks()) {
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
// How fast tapping zooms, in octaves (doublings) a second.
#define AUTO_ZOOM 0.7
// The deepest it goes: 2^-44 of the start's width... a pixel's 2^-44 or so.
#define MAX_OCTAVES 36.0
// How fast a fling slows (each frame's share kept).
#define INERTIA 0.92

// Iterations: MAX_ITER at the start's depth, ITER_PER_OCTAVE more for each
// octave deeper, up to ITER_LIMIT (the reference orbit's length).
#define MAX_ITER 300
#define ITER_PER_OCTAVE 50.0
#define ITER_LIMIT 20000
// The reference orbit's iterations worked out a frame.
#define REF_STEPS 512
// A new reference once the view's middle is this many screens from it.
#define REF_MOVE 1.5
#define BAILOUT 1e6

// Reuse: a pixel's result is redone once it was worked out more than
// about REUSE pixels from where the pixel looks now (0.5-1.5 times that,
// at random). REFINE is the share of still pixels redone each frame anyway,
// which sharpens a still picture.
#define REUSE 1.0
#define REFINE 0.06

// Colouring: PALETTE 0 sapphire and gold, 1 rainbow, 2 fire, 3 electric,
// 4 ink. COLOUR_DENSITY is the gradient's turns an iteration; CYCLE turns a
// second.
#define PALETTE 0
#define COLOUR_DENSITY 0.03
#define COLOUR_OFFSET 0.0
#define CYCLE 0.02
#define INSIDE vec3(0.0)
#define EMBOSS 0.5
#define EMBOSS_DEPTH 0.6

// Buffer A's top row, texel by texel:
//   S_CENTRE    the view's middle, double-float: re hi, re lo, im hi, im lo
//   S_VIEW      octaves deep, zooming (1 in, -1 out, 0), iterations, MARK
//   S_PREV      last frame's middle
//   S_PREV_VIEW last frame's octaves, the size, and whether last frame's
//               Buffer B can be reused
//   S_REF       the reference point, double-float
//   S_REF_STATE iterations of its orbit worked out, the size they're laid
//               out for
//   S_TOUCH     fingers last frame, when the touch started, most fingers,
//               how far it's moved
//   S_POINTS    last frame's two finger points
//   S_FLING     fling velocity (pixels a frame), where the touch started
#define S_CENTRE 0
#define S_VIEW 1
#define S_PREV 2
#define S_PREV_VIEW 3
#define S_REF 4
#define S_REF_STATE 5
#define S_TOUCH 6
#define S_POINTS 7
#define S_FLING 8
#define STATES 9
#define MARK 0.777

vec4 stateAt(sampler2D a, int i) {
	return texelFetch(a, ivec2(i, int(iResolution.y) - 1), 0);
}

// The reference orbit's iteration i, below the top row.
ivec2 refTexel(int i, int w) {
	return ivec2(i % w, i / w);
}

int refLimit() {
	return min(ITER_LIMIT, int(iResolution.x) * (int(iResolution.y) - 1));
}

// The complex plane's width a pixel, octaves deep.
float pixelScale(float octaves) {
	return START_WIDTH * exp2(-octaves) / min(iResolution.x, iResolution.y);
}

// Double-float arithmetic: a number is hi + lo, lo below hi's last digit.
// one() is 1 in a way the compiler can't see, so it can't simplify
// (a + b) - a to b and lose the rounding error these keep.
float one() {
	return iFrame < -1 ? 0.0 : 1.0;
}

vec2 twoSum(float a, float b) {
	float s = a + b;
	float v = s * one() - a;
	return vec2(s, (a - (s - v)) + (b - v));
}

vec2 quickTwoSum(float a, float b) {
	float s = a + b;
	return vec2(s, b - (s * one() - a));
}

// A float's top 12 significant bits and the rest, so each half times another
// is exact. Masked off rather than Dekker's 4097a - (4097a - a): GPUs fuse
// that into one multiply-add, which leaves the bottom half 0. (The mask is
// made at run time, as the test phone's compiler mangles big constants.)
vec2 split(float a) {
	uint low = uint(iFrame < -1 ? 0 : 4095);
	float hi = uintBitsToFloat(floatBitsToUint(a) & ~low);
	return vec2(hi, a - hi);
}

// a b exactly, as the rounded product and its rounding error. Times one(),
// so p can't be fused into the subtraction below (unrounded, the error
// comes out 0).
vec2 twoProd(float a, float b) {
	float p = a * b * one();
	vec2 x = split(a), y = split(b);
	return vec2(p, ((x.x * y.x - p) + x.x * y.y + x.y * y.x) + x.y * y.y);
}

vec2 dfAdd(vec2 a, vec2 b) {
	vec2 s = twoSum(a.x, b.x);
	vec2 t = twoSum(a.y, b.y);
	s.y += t.x;
	s = quickTwoSum(s.x, s.y);
	s.y += t.y;
	return quickTwoSum(s.x, s.y);
}

vec2 dfMul(vec2 a, vec2 b) {
	vec2 p = twoProd(a.x, b.x);
	p.y += a.x * b.y + a.y * b.x;
	return quickTwoSum(p.x, p.y);
}

// Two double-float complex numbers' difference, as plain floats.
vec2 dfDiff(vec4 a, vec4 b) {
	vec2 re = dfAdd(a.xy, -b.xy), im = dfAdd(a.zw, -b.zw);
	return vec2(re.x + re.y, im.x + im.y);
}

// Whether this GPU's floats round as double-float needs.
bool dfWorks() {
	float a = 1.0 + exp2(-12.0);
	vec2 p = twoProd(a, a);
	vec2 s = dfAdd(vec2(1.0, 0.0), vec2(exp2(-30.0), 0.0));
	return p.y == exp2(-24.0) && s.x == 1.0 && s.y == exp2(-30.0);
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

//// Buffer A
///float32
///stateRow:top
uniform sampler2D iChannel0; ///buf:A;

// Pointers come in pixels (or 0-1, from some sources).
vec2 scaled(vec2 p) {
	return p.x <= 1.5 && p.y <= 1.5 ? p * iResolution.xy : p;
}

// Moves a double-float complex point by a plain one.
vec4 moved(vec4 c, vec2 d) {
	return vec4(dfAdd(c.xy, vec2(d.x, 0.0)), dfAdd(c.zw, vec2(d.y, 0.0)));
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 c = ivec2(fragCoord);
	ivec2 size = ivec2(iResolution.xy);
	vec4 s[STATES];
	for (int i = 0; i < STATES; i++) {
		s[i] = stateAt(iChannel0, i);
	}
	bool fresh = abs(s[S_VIEW].w - MARK) > 0.01;
	bool laidOut = !fresh && int(s[S_REF_STATE].y) == size.x && int(s[S_REF_STATE].z) == size.y;

	if (c.y != size.y - 1) {
		// The reference orbit: last frame's reference, the next REF_STEPS
		// iterations after those already worked out, each texel from the
		// last one done.
		int i = c.x + c.y * size.x;
		int done = int(s[S_REF_STATE].x + 0.5);
		if (!laidOut || i < done || i >= done + REF_STEPS || i >= refLimit()) {
			fragColor = texelFetch(iChannel0, c, 0);
			return;
		}
		vec4 ref = s[S_REF];
		vec2 zr = vec2(0.0), zi = vec2(0.0);
		int from = 0;
		if (done > 0) {
			vec4 z = texelFetch(iChannel0, refTexel(done - 1, size.x), 0);
			zr = z.xz;
			zi = z.yw;
			from = done - 1;
		}
		for (int k = from; k < i; k++) {
			// Escaped: keep the first value past 2 from here on.
			if (zr.x * zr.x + zi.x * zi.x > 4.0) {
				break;
			}
			vec2 rr = dfMul(zr, zr), ii = dfMul(zi, zi), ri = dfMul(zr, zi);
			zr = dfAdd(dfAdd(rr, -ii), ref.xy);
			zi = dfAdd(2.0 * ri, ref.zw);
		}
		fragColor = vec4(zr.x, zi.x, zr.y, zi.y);
		return;
	}
	if (c.x >= STATES) {
		fragColor = vec4(0.0);
		return;
	}

	vec2 res = iResolution.xy;
	vec2 half_ = 0.5 * res;
	float dt = iTimeDelta > 0.0 ? min(iTimeDelta, 0.1) : 1.0 / 60.0;
	vec4 centre = s[S_CENTRE];
	float octaves = s[S_VIEW].x;
	float zooming = s[S_VIEW].y;
	int fingers = int(s[S_TOUCH].x + 0.5);
	float started = s[S_TOUCH].y;
	float most = s[S_TOUCH].z;
	float travel = s[S_TOUCH].w;
	vec2 last0 = s[S_POINTS].xy, last1 = s[S_POINTS].zw;
	vec2 velocity = s[S_FLING].xy;
	vec2 origin = s[S_FLING].zw;
	vec4 ref = s[S_REF];
	float done = s[S_REF_STATE].x;
	bool reset = fresh;

	// The touch: a tap is short and hardly moves.
	vec2 p0 = scaled(pointers[0].xy), p1 = scaled(pointers[1].xy);
	if (pointerCount > 0 && fingers == 0) {
		started = iTime;
		most = 0.0;
		travel = 0.0;
		origin = p0;
	}
	if (pointerCount > 0) {
		most = max(most, float(pointerCount));
		travel = max(travel, length(p0 - origin));
	} else if (fingers > 0 && iTime - started < 0.3 && travel < 0.03 * min(res.x, res.y)) {
		if (most < 1.5) {
			zooming = zooming > 0.5 ? 0.0 : 1.0;
		} else if (most < 2.5) {
			zooming = zooming < -0.5 ? 0.0 : -1.0;
		} else {
			reset = true;
		}
	}

	float scale = pixelScale(octaves);
	if (pointerCount >= 2) {
		if (fingers >= 2) {
			// Pinch: the point between the fingers stays between them.
			vec2 mid = 0.5 * (p0 + p1), lastMid = 0.5 * (last0 + last1);
			float grow = max(length(p1 - p0), 1.0) / max(length(last1 - last0), 1.0);
			float next = clamp(octaves + log2(grow), -2.0, MAX_OCTAVES);
			float nextScale = pixelScale(next);
			centre = moved(centre, (lastMid - half_) * scale - (mid - half_) * nextScale);
			octaves = next;
			scale = nextScale;
		}
		velocity = vec2(0.0);
	} else if (pointerCount == 1) {
		if (fingers == 1) {
			vec2 delta = p0 - last0;
			centre = moved(centre, -delta * scale);
			velocity = mix(velocity, delta, 0.5);
		} else {
			velocity = vec2(0.0);
		}
	} else if (length(velocity) > 0.05) {
		centre = moved(centre, -velocity * scale);
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

	vec4 prev = centre;
	float prevOctaves = octaves;
	if (reset) {
		// (Times one(): with constants, the compiler adds the parts in
		// higher precision and the fine part's lost.)
		centre = vec4(START_X, START_X_FINE, START_Y, START_Y_FINE) * one();
		centre = vec4(quickTwoSum(centre.x, centre.y), quickTwoSum(centre.z, centre.w));
		octaves = START_OCTAVES;
		zooming = 0.0;
		velocity = vec2(0.0);
		scale = pixelScale(octaves);
	}
	if (!fresh) {
		prev = s[S_CENTRE];
		prevOctaves = s[S_VIEW].x;
	}
	// Last frame's pixels are reusable if it was this size.
	bool reusable = !fresh && int(s[S_PREV_VIEW].y) == size.x && int(s[S_PREV_VIEW].z) == size.y;

	// A new reference when the view's gone too far from it.
	vec2 off = dfDiff(centre, ref);
	if (reset || !laidOut || length(off) > REF_MOVE * scale * max(res.x, res.y)) {
		ref = centre;
		done = 0.0;
	} else {
		done = min(done + float(REF_STEPS), float(refLimit()));
	}
	float iterations = clamp(float(MAX_ITER) + ITER_PER_OCTAVE * max(octaves, 0.0), 16.0, float(refLimit() - 1));

	vec4 o;
	if (c.x == S_CENTRE) o = centre;
	else if (c.x == S_VIEW) o = vec4(octaves, zooming, iterations, MARK);
	else if (c.x == S_PREV) o = prev;
	else if (c.x == S_PREV_VIEW) o = vec4(prevOctaves, res, reusable ? 1.0 : 0.0);
	else if (c.x == S_REF) o = ref;
	else if (c.x == S_REF_STATE) o = vec4(done, res, 0.0);
	else if (c.x == S_TOUCH) o = vec4(float(fingers), started, most, travel);
	else if (c.x == S_POINTS) o = vec4(last0, last1);
	else o = vec4(velocity, origin);
	fragColor = o;
}

//// Buffer B
///float32
uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;

// Each pixel: its smooth iteration count (-1 inside), how far from this
// pixel's middle (in pixels) that was worked out, and the iterations it
// was worked out with plus 2 (a new buffer's alpha of 1 isn't a result).
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 res = iResolution.xy;
	ivec2 size = ivec2(res);
	vec4 centre = stateAt(iChannel0, S_CENTRE);
	vec4 view = stateAt(iChannel0, S_VIEW);
	vec4 prev = stateAt(iChannel0, S_PREV);
	vec4 prevView = stateAt(iChannel0, S_PREV_VIEW);
	vec4 ref = stateAt(iChannel0, S_REF);
	int done = int(stateAt(iChannel0, S_REF_STATE).x + 0.5);
	float scale = pixelScale(view.x);
	int iterations = int(view.z);
	vec2 q = fragCoord - 0.5 * res;

	// The nearest of last frame's results, of the four round where this
	// pixel's point was.
	vec4 best = vec4(0.0);
	vec2 bestOff = vec2(0.0);
	float bestDist = 1e30;
	if (prevView.w > 0.5) {
		float prevScale = pixelScale(prevView.x);
		vec2 moved = dfDiff(centre, prev) / prevScale;
		vec2 then = moved + q * (scale / prevScale) + 0.5 * res;
		ivec2 b = ivec2(floor(then - 0.5));
		for (int k = 0; k < 4; k++) {
			ivec2 t = b + ivec2(k & 1, k >> 1);
			if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, size))) {
				continue;
			}
			vec4 v = texelFetch(iChannel1, t, 0);
			if (v.w < 1.5) {
				continue;
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

	float h = hash13(vec3(fragCoord, float(iFrame % 1024)));
	float h2 = hash13(vec3(fragCoord.yx, float(iFrame % 1024) + 0.5));
	float dist = sqrt(bestDist);
	bool redo = bestDist > 1e29 || dist > REUSE * (0.5 + h) || (dist > 0.05 && h2 < REFINE)
			|| (best.x < 0.0 && best.w - 2.0 < 0.9 * float(iterations) && h2 < 2.0 * REFINE);
	if (redo && done > 1) {
		vec2 dc = dfDiff(centre, ref) + q * scale;
		vec2 Z = vec2(0.0), d = vec2(0.0);
		int m = 0;
		float mu = -1.0;
		bool ok = true;
		for (int n = 0; n < iterations; n++) {
			d = cmul(2.0 * Z + d, d) + dc;
			m++;
			if (m >= done) {
				// The reference isn't worked out this far yet.
				ok = false;
				break;
			}
			Z = texelFetch(iChannel0, refTexel(m, size.x), 0).xy;
			vec2 z = Z + d;
			float r2 = dot(z, z);
			if (r2 > BAILOUT) {
				mu = max(float(n) + 2.0 - log2(log2(r2)), 0.0);
				break;
			}
			// Rebase: nearer 0 than the reference, or past its escape.
			if (r2 < dot(d, d) || dot(Z, Z) > 4.0) {
				d = z;
				m = 0;
				Z = vec2(0.0);
			}
		}
		if (ok) {
			fragColor = vec4(mu, 0.0, 0.0, float(iterations) + 2.0);
			return;
		}
	}
	fragColor = bestDist < 1e29 ? vec4(best.x, bestOff, best.w) : vec4(0.0);
}
