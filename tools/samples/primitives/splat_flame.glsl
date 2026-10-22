// Splat Flame: a living fractal flame, drawn by Upscaling's 2D Gaussian
// Splats (picked by the ///upscale line). Double-tap for a new one - the
// old one morphs into it.
//
// Buffer B holds the particles: every frame each takes a few steps of the
// chaos game - a random one of the flame's transforms, an affine map bent
// by a blend of "variations" (swirl, sinusoidal, julia...) - so they stay
// on the attractor and drift through it, while the transforms themselves
// slowly turn and wander (ANIMATE). Each particle's last step is also
// worked out for the previous frame's flame: the difference is how fast
// its point moves, and its splat is stretched along that (motion blur),
// keeping its light. The splats add up with log density, as flame
// renderers do, and old frames fade out (KEEP) - a running set of samples,
// sharp however it moves. Its colour index moves halfway to each
// transform's own, so colours trace where the particles came from.
//
// A new flame is blended in over MORPH seconds: every number of the flame
// slides from the old one's to the new one's, each transform's variations
// cross-fade, and so do the palettes. Upscaling... > Adjust Splats Xform...
// pans, zooms and turns it all.
//
// The colours come from an OKLCH palette (perceptual lightness, chroma,
// hue), kept to natural tones: neighbouring hues from dark to light,
// sometimes with a quiet complementary accent.
///upscale:splats

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	// A dark ground, faintly lighter in the middle.
	vec2 q = fragCoord / iResolution.xy - 0.5;
	fragColor = vec4(vec3(0.012, 0.012, 0.016) * (1.4 - dot(q, q)), 1.0);
}

//// Buffer A
// The flame's seeds and the taps that change them, in the bottom-left
// texels: 0 (seed, last frame's finger count, taps so far, when the last
// tap ended); 1 (when this touch began, when the seed last changed, the
// seed before, a 2 once it's set).
///float32

uniform sampler2D iChannel0; ///buf:A;
uniform int pointerCount;

#define TAPS 2 // taps for a new flame
#define TAP_TIME 0.3 // the longest a tap is held, in seconds
#define TAP_GAP 0.4 // the longest between taps

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	if (p.y != 0 || p.x > 1) {
		fragColor = vec4(0.0);
		return;
	}
	vec4 s0 = texelFetch(iChannel0, ivec2(0, 0), 0);
	vec4 s1 = texelFetch(iChannel0, ivec2(1, 0), 0);
	if (s1.w != 2.0) {
		// A different flame each run.
		float seed = 1.0 + floor(fract(iDate.w * 0.6180339) * 99991.0);
		s0 = vec4(seed, 0.0, 0.0, -10.0);
		s1 = vec4(0.0, -100.0, seed, 2.0);
	}
	float count = float(pointerCount);
	if (count > 0.0 && s0.y == 0.0) {
		s1.x = iTime; // a touch begins
	} else if (count == 0.0 && s0.y > 0.0) {
		// A touch ends: a tap if it was quick.
		if (iTime - s1.x < TAP_TIME) {
			s0.z = iTime - s0.w < TAP_GAP ? s0.z + 1.0 : 1.0;
			s0.w = iTime;
			if (s0.z >= float(TAPS)) {
				s1.z = s0.x;
				s0.x = mod(s0.x * 7919.0 + 104729.0, 999983.0);
				s0.z = 0.0;
				s1.y = iTime;
			}
		} else {
			s0.z = 0.0;
		}
	}
	s0.y = count;
	fragColor = p.x == 0 ? s0 : s1;
}

//// Buffer B
// The particles, two texels each (in reading order, over the first half
// of the buffer): 2i (x, y, colour index, a 2 once started - a new buffer
// clears to 1) and 2i+1 (how far its point moved since the last frame,
// x and y, in plane units). Both texels play the same chaos game, from
// the particle's own random numbers.
///float32

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;

#define TRANSFORMS 4 // at most; each flame uses 2 to this many
#define STEPS 4 // chaos-game steps a frame
#define RESEED 0.003 // the share of particles started afresh each frame
#define ANIMATE 1.0 // how much the transforms wander (0: still)
#define MORPH 2.5 // seconds to blend into a new flame

uint state;

uint pcg(uint v) {
	uint s = v * 747796405u + 2891336453u;
	uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
	return (w >> 22u) ^ w;
}

float rnd() {
	state = pcg(state);
	return float(state) / 4294967296.0;
}

// A flame's own numbers: the same for every texel, from its seed.
float param(float seed, int i) {
	return float(pcg(uint(seed) * 1013u + uint(i) * 7919u + 17u)) / 4294967296.0;
}

vec2 variation(int kind, vec2 p, float omega) {
	float r2 = max(dot(p, p), 1e-6);
	float r = sqrt(r2);
	float theta = atan(p.x, p.y);
	if (kind == 0) return p;                                          // linear
	if (kind == 1) return sin(p);                                     // sinusoidal
	if (kind == 2) return p / r2;                                     // spherical
	if (kind == 3) return vec2(p.x * sin(r2) - p.y * cos(r2), p.x * cos(r2) + p.y * sin(r2)); // swirl
	if (kind == 4) return vec2((p.x - p.y) * (p.x + p.y), 2.0 * p.x * p.y) / r; // horseshoe
	if (kind == 5) return vec2(theta / 3.14159265, r - 1.0);          // polar
	if (kind == 6) return r * vec2(sin(theta * r), -cos(theta * r));  // heart
	if (kind == 7) return theta / 3.14159265 * vec2(sin(3.14159265 * r), cos(3.14159265 * r)); // disc
	if (kind == 8) return sqrt(r) * vec2(cos(theta * 0.5 + omega), sin(theta * 0.5 + omega)); // julia
	return p * 4.0 / (r2 + 4.0);                                      // bubble
}

// How likely transform t is to be picked, in flame `seed` (0: unused).
float weight(float seed, int t) {
	int used = 2 + int(param(seed, 0) * float(TRANSFORMS - 1));
	return t < used ? 0.2 + param(seed, 10 + t) : 0.0;
}

// The affine part of transform t, at time `time`: turned, scaled (not too
// contracting, or the particles fall into a few tight cycles), sheared a
// little, moved - and each wandering slowly.
void affine(float seed, int t, float time, out float angle, out float scale, out float shear,
		out vec2 move) {
	int b = 100 + 20 * t;
	float phase = param(seed, b + 9) * 6.2831853;
	float rate = 0.6 + 0.8 * param(seed, b + 10);
	angle = param(seed, b) * 6.2831853 + ANIMATE * 0.35 * sin(time * 0.17 * rate + phase);
	scale = 0.7 + 0.35 * param(seed, b + 1) + ANIMATE * 0.04 * sin(time * 0.23 * rate + 2.0 * phase);
	shear = (param(seed, b + 2) - 0.5) * 0.6;
	move = vec2(param(seed, b + 3), param(seed, b + 4)) * 2.0 - 1.0 +
			ANIMATE * 0.15 * vec2(sin(time * 0.13 * rate + phase), cos(time * 0.11 * rate - phase));
}

// One step of transform t, blending flame `from` into `to` by `f`, at
// `time`.
vec2 apply(float from, float to, float f, int t, float time, vec2 p, float omega) {
	float a0, s0, h0, a1, s1, h1;
	vec2 m0, m1;
	affine(from, t, time, a0, s0, h0, m0);
	affine(to, t, time, a1, s1, h1, m1);
	// The shorter way round.
	float da = a1 - a0;
	da -= 6.2831853 * floor(da / 6.2831853 + 0.5);
	float angle = a0 + da * f;
	float scale = mix(s0, s1, f);
	mat2 m = mat2(cos(angle), sin(angle), -sin(angle), cos(angle)) *
			mat2(1.0, 0.0, mix(h0, h1, f), 1.0) * scale;
	vec2 q = m * p + mix(m0, m1, f);
	int b = 100 + 20 * t;
	vec2 v0 = mix(variation(int(param(from, b + 5) * 10.0), q, omega),
			variation(int(param(from, b + 6) * 10.0), q, omega), param(from, b + 7));
	if (f <= 0.0) {
		return v0;
	}
	vec2 v1 = mix(variation(int(param(to, b + 5) * 10.0), q, omega),
			variation(int(param(to, b + 6) * 10.0), q, omega), param(to, b + 7));
	return mix(v0, v1, f);
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 size = ivec2(iResolution.xy);
	ivec2 p = ivec2(fragCoord);
	int index = p.y * size.x + p.x;
	int particle = index / 2;
	if (particle >= size.x * size.y / 4) {
		fragColor = vec4(0.0);
		return;
	}
	vec4 s0 = texelFetch(iChannel0, ivec2(0, 0), 0);
	vec4 s1 = texelFetch(iChannel0, ivec2(1, 0), 0);
	float to = s0.x;
	float from = s1.z;
	float f = smoothstep(0.0, 1.0, (iTime - s1.y) / MORPH);
	float dt = clamp(iTimeDelta, 1.0 / 120.0, 0.1);
	// The particle's own random numbers, the same in both its texels.
	state = pcg(uint(particle) * 9781u + pcg(uint(iFrame)));
	int home = 2 * particle;
	vec4 part = texelFetch(iChannel1, ivec2(home % size.x, home / size.x), 0);
	if (part.w != 2.0 || rnd() < RESEED || any(isnan(part)) || dot(part.xy, part.xy) > 1e4) {
		part = vec4(rnd() * 2.0 - 1.0, rnd() * 2.0 - 1.0, rnd(), 2.0);
	}
	vec2 moved = vec2(0.0);
	for (int step = 0; step < STEPS; ++step) {
		// Each transform is picked with its own (blended) weight.
		float total = 0.0;
		for (int t = 0; t < TRANSFORMS; ++t) {
			total += mix(weight(from, t), weight(to, t), f);
		}
		float pick = rnd() * total;
		int t = 0;
		for (int i = 0; i < TRANSFORMS; ++i) {
			float w = mix(weight(from, i), weight(to, i), f);
			if (w > 0.0) {
				t = i;
				pick -= w;
				if (pick <= 0.0) {
					break;
				}
			}
		}
		float omega = rnd() < 0.5 ? 0.0 : 3.14159265;
		vec2 next = apply(from, to, f, t, iTime, part.xy, omega);
		if (step == STEPS - 1) {
			// The same step in the last frame's flame: how fast it moves.
			float fBefore = smoothstep(0.0, 1.0, (iTime - dt - s1.y) / MORPH);
			moved = next - apply(from, to, fBefore, t, iTime - dt, part.xy, omega);
		}
		part.xy = next;
		int b = 100 + 20 * t;
		part.z = 0.5 * (part.z + mix(param(from, b + 8), param(to, b + 8), f));
	}
	fragColor = index % 2 == 0 ? part : vec4(moved, 0.0, 0.0);
}

//// Buffer C
// The splats for Upscaling's 2D Gaussian Splats: particle i is splat i, in
// texels 2i (centre, major axis - along its motion, stretched by it) and
// 2i+1 (colour, minor axis). Texel (0, 0) is the control texel instead
// (splat 0 isn't drawn): how much of the sum each frame keeps, the
// exposure, log density.
///float32

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;

#define VIEW 0.28 // frame heights a plane unit spans
#define SPLAT_PX 0.6 // each splat's sigma, in frame pixels
#define EXPOSURE 5.0
#define KEEP 0.85 // of the sum each frame (1: all, for a still flame)
#define BLUR 0.5 // how much a splat stretches along its motion, per frame moved
#define MORPH 2.5 // as Buffer B's

float param(float seed, int i) {
	uint v = uint(seed) * 1013u + uint(i) * 7919u + 17u;
	uint s = v * 747796405u + 2891336453u;
	uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
	return float((w >> 22u) ^ w) / 4294967296.0;
}

vec3 oklabToLinear(vec3 c) {
	float l = c.x + 0.3963377774 * c.y + 0.2158037573 * c.z;
	float m = c.x - 0.1055613458 * c.y - 0.0638541728 * c.z;
	float s = c.x - 0.0894841775 * c.y - 1.2914855480 * c.z;
	l = l * l * l;
	m = m * m * m;
	s = s * s * s;
	return vec3(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
			-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
			-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
}

vec3 linearToSrgb(vec3 c) {
	c = clamp(c, 0.0, 1.0);
	return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

vec3 oklch(float l, float c, float hueDegrees) {
	float h = radians(hueDegrees);
	return vec3(l, c * cos(h), c * sin(h));
}

// A flame's palette along the colour index, in OKLab: four OKLCH stops -
// neighbouring hues dark to light, sometimes a quiet complementary accent.
vec3 paletteLab(float seed, float t) {
	float hue = param(seed, 300) * 360.0;
	float spread = 35.0 + 55.0 * param(seed, 301);
	float accent = param(seed, 302) < 0.5 ? 160.0 + 40.0 * param(seed, 304) : spread * 1.6;
	float chroma = 0.10 + 0.07 * param(seed, 303);
	vec3 s0 = oklch(0.34, chroma * 0.8, hue - spread);
	vec3 s1 = oklch(0.6, chroma, hue);
	vec3 s2 = oklch(0.74, chroma * 0.9, hue + accent);
	vec3 s3 = oklch(0.92, chroma * 0.4, hue + spread * 0.6);
	t = clamp(t, 0.0, 1.0) * 3.0;
	return t < 1.0 ? mix(s0, s1, t) : t < 2.0 ? mix(s1, s2, t - 1.0) : mix(s2, s3, t - 2.0);
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 size = ivec2(iResolution.xy);
	ivec2 p = ivec2(fragCoord);
	vec4 s0 = texelFetch(iChannel0, ivec2(0, 0), 0);
	vec4 s1 = texelFetch(iChannel0, ivec2(1, 0), 0);
	float f = smoothstep(0.0, 1.0, (iTime - s1.y) / MORPH);
	if (p == ivec2(0)) {
		fragColor = vec4(KEEP, EXPOSURE, -1.0, 2.0);
		return;
	}
	int index = p.y * size.x + p.x;
	int splat = index / 2;
	if (splat >= size.x * size.y / 4) {
		fragColor = vec4(0.0);
		return;
	}
	int home = 2 * splat;
	vec4 part = texelFetch(iChannel1, ivec2(home % size.x, home / size.x), 0);
	vec2 moved = texelFetch(iChannel1, ivec2((home + 1) % size.x, (home + 1) / size.x), 0).xy;
	vec2 aspect = vec2(iResolution.y / iResolution.x, 1.0);
	float sigma = SPLAT_PX / iResolution.y; // frame heights
	// Motion in frame heights, and the splat stretched along it.
	vec2 motion = moved * VIEW;
	float along = length(motion);
	float major = sqrt(sigma * sigma + (BLUR * along) * (BLUR * along));
	vec2 axis = along > 1e-7 ? motion / along : vec2(1.0, 0.0);
	if (index % 2 == 0) {
		fragColor = vec4(0.5 + part.xy * VIEW * aspect, axis * major);
	} else {
		vec3 lab = mix(paletteLab(s1.z, part.z), paletteLab(s0.x, part.z), f);
		// Stretched, it keeps its light: spread over more, each bit dimmer.
		vec3 colour = linearToSrgb(oklabToLinear(lab)) * (sigma / major);
		fragColor = vec4(max(colour, vec3(1e-4)), sigma);
	}
}
