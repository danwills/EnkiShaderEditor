// Splat Flame: a fractal flame, drawn by Upscaling's 2D Gaussian Splats
// (picked by the ///upscale line). Double-tap for a new one.
//
// Buffer B holds the particles: every frame each takes a few steps of the
// chaos game - a random one of the flame's transforms, an affine map
// bent by a blend of "variations" (swirl, sinusoidal, julia...) - so they
// stay on the attractor, and drift through it. Its colour index moves
// halfway to each transform's own, so colours trace where the particles
// came from. Buffer C lists each particle as a small splat, and the splats
// are added up with log density, as flame renderers do: dense cores
// glow without the sparse wisps vanishing. Upscaling... > Adjust Splats
// Xform... pans, zooms and turns them without starting them again.
//
// The colours come from an OKLCH palette (perceptual lightness, chroma,
// hue), kept to natural, unsaturated tones: a run of neighbouring hues
// from dark to light, sometimes with a quiet complementary accent.
///upscale:splats

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	// A dark ground, faintly lighter in the middle.
	vec2 q = fragCoord / iResolution.xy - 0.5;
	fragColor = vec4(vec3(0.012, 0.012, 0.016) * (1.4 - dot(q, q)), 1.0);
}

//// Buffer A
// The flame's seed and the taps that change it, in the bottom-left
// texels: 0 (seed, last frame's finger count, taps so far, when the last
// tap ended); 1 (when this touch began, the frame the seed last changed,
// unused, a 2 once it's set).
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
		s0 = vec4(1.0 + floor(fract(iDate.w * 0.6180339) * 99991.0), 0.0, 0.0, -10.0);
		s1 = vec4(0.0, float(iFrame), 0.0, 2.0);
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
				s0.x = mod(s0.x * 7919.0 + 104729.0, 999983.0);
				s0.z = 0.0;
				s1.y = float(iFrame);
			}
		} else {
			s0.z = 0.0;
		}
	}
	s0.y = count;
	fragColor = p.x == 0 ? s0 : s1;
}

//// Buffer B
// The particles: (x, y) on the plane, the colour index, and a 2 once
// started (a new buffer clears to 1). Only
// the first half of the texels are particles - one per splat.
///float32

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;

#define TRANSFORMS 4 // at most; each flame uses 2 to this many
#define STEPS 4 // chaos-game steps a frame
#define RESEED 0.003 // the share of particles started afresh each frame

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

// The flame's own numbers: the same for every texel, from its seed.
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

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 size = ivec2(iResolution.xy);
	ivec2 p = ivec2(fragCoord);
	int index = p.y * size.x + p.x;
	if (index >= size.x * size.y / 2) {
		fragColor = vec4(0.0);
		return;
	}
	vec4 s0 = texelFetch(iChannel0, ivec2(0, 0), 0);
	vec4 s1 = texelFetch(iChannel0, ivec2(1, 0), 0);
	float seed = s0.x;
	state = pcg(uint(index) * 9781u + pcg(uint(iFrame)));
	vec4 part = texelFetch(iChannel1, p, 0);
	bool fresh = float(iFrame) - s1.y < 2.0 || rnd() < RESEED || any(isnan(part)) ||
			dot(part.xy, part.xy) > 1e4 || part.w != 2.0;
	if (fresh) {
		part = vec4(rnd() * 2.0 - 1.0, rnd() * 2.0 - 1.0, rnd(), 2.0);
	}
	int used = 2 + int(param(seed, 0) * float(TRANSFORMS - 1));
	for (int step = 0; step < STEPS; ++step) {
		// Each transform is picked with its own weight.
		float total = 0.0;
		for (int t = 0; t < TRANSFORMS; ++t) {
			total += t < used ? 0.2 + param(seed, 10 + t) : 0.0;
		}
		float pick = rnd() * total;
		int t = 0;
		for (int i = 0; i < TRANSFORMS; ++i) {
			if (i < used) {
				pick -= 0.2 + param(seed, 10 + i);
				if (pick <= 0.0) {
					t = i;
					break;
				}
				t = i;
			}
		}
		int b = 100 + 20 * t;
		// An affine map: turned, scaled (contracting), sheared a little, moved.
		float angle = param(seed, b) * 6.2831853;
		// Not too contracting, or the particles fall into a few tight
		// cycles and the flame clumps.
		float scale = 0.7 + 0.35 * param(seed, b + 1);
		float shear = (param(seed, b + 2) - 0.5) * 0.6;
		vec2 move = vec2(param(seed, b + 3), param(seed, b + 4)) * 2.0 - 1.0;
		mat2 m = mat2(cos(angle), sin(angle), -sin(angle), cos(angle)) * mat2(1.0, 0.0, shear, 1.0) * scale;
		vec2 q = m * part.xy + move;
		// Two variations, blended.
		int k1 = int(param(seed, b + 5) * 10.0);
		int k2 = int(param(seed, b + 6) * 10.0);
		float w = param(seed, b + 7);
		float omega = rnd() < 0.5 ? 0.0 : 3.14159265;
		part.xy = mix(variation(k1, q, omega), variation(k2, q, omega), w);
		part.z = 0.5 * (part.z + param(seed, b + 8));
	}
	fragColor = part;
}

//// Buffer C
// The splats for Upscaling's 2D Gaussian Splats: particle i is splat i, in
// texels 2i (centre, major axis) and 2i+1 (colour, minor axis). Texel
// (0, 0) is the control texel instead (splat 0 isn't drawn): keep adding
// unless the flame just changed, the exposure, log density.
///float32

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;

#define VIEW 0.28 // frame heights a plane unit spans
#define SPLAT_PX 0.6 // each splat's sigma, in frame pixels
#define EXPOSURE 2.0

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

// The flame's palette along the colour index: four OKLCH stops, blended
// in OKLab - neighbouring hues dark to light, low chroma, sometimes a
// quiet complementary accent.
vec3 palette(float seed, float t) {
	float hue = param(seed, 300) * 360.0;
	float spread = 35.0 + 55.0 * param(seed, 301);
	float accent = param(seed, 302) < 0.5 ? 160.0 + 40.0 * param(seed, 304) : spread * 1.6;
	float chroma = 0.10 + 0.07 * param(seed, 303);
	vec3 s0 = oklch(0.34, chroma * 0.8, hue - spread);
	vec3 s1 = oklch(0.6, chroma, hue);
	vec3 s2 = oklch(0.74, chroma * 0.9, hue + accent);
	vec3 s3 = oklch(0.92, chroma * 0.4, hue + spread * 0.6);
	t = clamp(t, 0.0, 1.0) * 3.0;
	vec3 lab = t < 1.0 ? mix(s0, s1, t) : t < 2.0 ? mix(s1, s2, t - 1.0) : mix(s2, s3, t - 2.0);
	return linearToSrgb(oklabToLinear(lab));
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 size = ivec2(iResolution.xy);
	ivec2 p = ivec2(fragCoord);
	vec4 s0 = texelFetch(iChannel0, ivec2(0, 0), 0);
	vec4 s1 = texelFetch(iChannel0, ivec2(1, 0), 0);
	if (p == ivec2(0)) {
		bool changed = float(iFrame) - s1.y < 3.0;
		fragColor = vec4(changed ? 0.0 : 1.0, EXPOSURE, -1.0, 2.0);
		return;
	}
	int index = p.y * size.x + p.x;
	int splat = index / 2;
	if (splat >= size.x * size.y / 2) {
		fragColor = vec4(0.0);
		return;
	}
	vec4 part = texelFetch(iChannel1, ivec2(splat % size.x, splat / size.x), 0);
	float sigma = SPLAT_PX / iResolution.y; // frame heights
	if (index % 2 == 0) {
		vec2 uv = 0.5 + part.xy * VIEW * vec2(iResolution.y / iResolution.x, 1.0);
		fragColor = vec4(uv, sigma, 0.0);
	} else {
		fragColor = vec4(max(palette(s0.x, part.z), vec3(1e-4)), sigma);
	}
}
