// Symmetric Icons: Field and Golubitsky's chaotic attractors with n-fold
// symmetry ("Symmetry in Chaos"), drawn bright on black by Upscaling's 2D
// Gaussian Splats (picked by the ///upscale line). Each particle follows
//
//   z' = (lambda + alpha |z|^2 + beta Re(z^n) + i omega) z + gamma conj(z)^(n-1)
//
// and the splats add up with log density, coloured by how far each point
// jumped. Drag to explore:
//   one finger      lambda (across) and alpha (up and down)
//   two fingers     beta (across) and gamma (up and down); twist for omega
//   three fingers   swipe up or down to change the symmetry n (2-12)
//   double tap      the next classic icon
// The numbers show while you touch, and a little after.
///upscale:splats

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D font;

#define TEXT_COLOR vec3(0.85, 0.82, 0.74)
#define SHOW_SECONDS 3.0 // the numbers stay this long after a touch

const int LABEL0[7] = int[](110, 32, 32, 32, 32, 32, 32);
const int LABEL1[7] = int[](108, 97, 109, 98, 100, 97, 32);
const int LABEL2[7] = int[](97, 108, 112, 104, 97, 32, 32);
const int LABEL3[7] = int[](98, 101, 116, 97, 32, 32, 32);
const int LABEL4[7] = int[](103, 97, 109, 109, 97, 32, 32);
const int LABEL5[7] = int[](111, 109, 101, 103, 97, 32, 32);

int labelChar(int label, int i) {
	if (label == 0) return LABEL0[i];
	if (label == 1) return LABEL1[i];
	if (label == 2) return LABEL2[i];
	if (label == 3) return LABEL3[i];
	if (label == 4) return LABEL4[i];
	return LABEL5[i];
}

// Character i (from the left) of v as "-12.345", right-aligned in 7.
int numberChar(float v, int i) {
	int a = int(floor(abs(v) * 1000.0 + 0.5));
	int pos = 6 - i; // from the right
	if (pos < 3) {
		int d = a;
		for (int k = 0; k < pos; ++k) d /= 10;
		return 48 + d % 10;
	}
	if (pos == 3) return 46;
	int whole = a / 1000;
	int digits = whole >= 10 ? 2 : 1;
	if (pos - 4 < digits) {
		int d = whole;
		for (int k = 0; k < pos - 4; ++k) d /= 10;
		return 48 + d % 10;
	}
	return pos - 4 == digits && v < 0.0 ? 45 : 32;
}

// The readout's character at (line, col): a label, then its value - one
// line each for n, lambda, alpha, beta, gamma and omega.
int readoutChar(int line, int col, vec4 p, vec4 q) {
	if (col < 7) {
		return labelChar(line, col);
	}
	col -= 7;
	if (line == 0) {
		int n = int(q.y + 0.5);
		return col == 5 ? (n >= 10 ? 48 + n / 10 : 32) : col == 6 ? 48 + n % 10 : 32;
	}
	float v = line == 1 ? p.x : line == 2 ? p.y : line == 3 ? p.z : line == 4 ? p.w : q.x;
	return numberChar(v, col);
}

float glyph(int code, vec2 at) {
	vec2 cell = vec2(float(code % 16), 15.0 - float(code / 16));
	return texture(font, (cell + at) / 16.0).r;
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	vec2 res = iResolution.xy;
	vec2 q = fragCoord / res - 0.5;
	vec3 col = vec3(0.01, 0.01, 0.014) * (1.3 - dot(q, q));

	vec4 p0 = texelFetch(iChannel0, ivec2(0, 0), 0);
	vec4 p1 = texelFetch(iChannel0, ivec2(1, 0), 0);
	float shown = texelFetch(iChannel0, ivec2(4, 0), 0).w;
	float fade = clamp((SHOW_SECONDS - (iTime - shown)) / 0.5, 0.0, 1.0);
	if (fade > 0.0) {
		// Six lines of 14 cells, bottom left, big enough to read at a low
		// render quality; a dark halo keeps them clear of the icon.
		float cellW = min(res.x, res.y) * 0.032;
		vec2 cellSize = vec2(cellW, cellW * 1.9);
		vec2 origin = vec2(res.x * 0.05, res.y * 0.07);
		vec2 t = (fragCoord - origin) / cellSize;
		int line = 5 - int(floor(t.y));
		int column = int(floor(t.x));
		if (t.y >= 0.0 && t.y < 6.0 && column >= 0 && column < 14) {
			int code = readoutChar(line, column, p0, p1);
			if (code > 32) {
				vec2 at = vec2(fract(t.x) * 0.55 + 0.225, fract(t.y) * 0.9 + 0.05);
				float d = glyph(code, at);
				col = mix(col, vec3(0.0), smoothstep(0.3, 0.45, d) * fade * 0.8);
				col = mix(col, TEXT_COLOR, smoothstep(0.42, 0.58, d) * fade);
			}
		}
	}
	fragColor = vec4(col, 1.0);
}

//// Buffer A
// The parameters and the gestures that change them, in the bottom row:
// 0 (lambda, alpha, beta, gamma); 1 (omega, n, the attractor's size, a 2
// once set); 2 last frame's first two touches; 3 (last frame's finger
// count, taps so far, when the last tap ended, when this touch began);
// 4 (the preset, the frame the parameters last changed, the three-finger
// swipe so far, when the numbers were last asked for).
///float32

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;
uniform vec3 pointers[10];
uniform int pointerCount;

#define SPEED 2.5 // parameter change per screen height dragged
#define N_STEP 0.08 // screen heights of three-finger swipe per step of n
#define TAP_TIME 0.3
#define TAP_GAP 0.4
#define PRESETS 10

// (lambda, alpha, beta, gamma), (omega, n) - from "Symmetry in Chaos".
void preset(int i, out vec4 a, out vec2 b) {
	if (i == 0) { a = vec4(-2.5, 5.0, -1.9, 1.0); b = vec2(0.188, 5.0); return; }   // sand dollar
	if (i == 1) { a = vec4(-1.86, 2.0, 0.0, 1.0); b = vec2(0.1, 4.0); return; }     // emperor's cloak
	if (i == 2) { a = vec4(-2.08, 1.0, -0.1, 0.167); b = vec2(0.0, 7.0); return; }  // Mayan bracelet
	if (i == 3) { a = vec4(-1.806, 1.806, 0.0, 1.0); b = vec2(0.0, 5.0); return; }  // swirling
	if (i == 4) { a = vec4(-2.7, 5.0, 1.5, 1.0); b = vec2(0.0, 6.0); return; }      // Halloween
	if (i == 5) { a = vec4(1.56, -1.0, 0.1, -0.82); b = vec2(0.12, 3.0); return; }  // clam triple
	if (i == 6) { a = vec4(2.6, -2.0, 0.0, -0.5); b = vec2(0.0, 5.0); return; }     // star
	if (i == 7) { a = vec4(-2.05, 3.0, -16.79, 1.0); b = vec2(0.0, 9.0); return; }  // fish
	if (i == 8) { a = vec4(1.5, -1.0, 0.1, -0.805); b = vec2(0.0, 3.0); return; }   // trampoline
	a = vec4(-2.34, 2.0, 0.2, 0.1); b = vec2(0.0, 5.0);                             // flower
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	if (p.y != 0 || p.x > 4) {
		fragColor = vec4(0.0);
		return;
	}
	vec2 res = iResolution.xy;
	vec4 s0 = texelFetch(iChannel0, ivec2(0, 0), 0);
	vec4 s1 = texelFetch(iChannel0, ivec2(1, 0), 0);
	vec4 s2 = texelFetch(iChannel0, ivec2(2, 0), 0);
	vec4 s3 = texelFetch(iChannel0, ivec2(3, 0), 0);
	vec4 s4 = texelFetch(iChannel0, ivec2(4, 0), 0);
	if (s1.w != 2.0) {
		vec2 b;
		preset(0, s0, b);
		s1 = vec4(b, 1.5, 2.0);
		s2 = vec4(0.0);
		s3 = vec4(0.0, 0.0, -10.0, 0.0);
		s4 = vec4(0.0, float(iFrame), 0.0, -10.0);
	}
	vec4 before0 = s0;
	vec2 before1 = s1.xy;
	int count = pointerCount;
	vec2 now0 = pointers[0].xy / res.y;
	vec2 now1 = pointers[1].xy / res.y;
	vec2 now2 = pointers[2].xy / res.y;
	if (count > 0) {
		s4.w = iTime;
	}
	if (count > 0 && int(s3.x) == count) {
		if (count == 1) {
			s0.xy += (now0 - s2.xy) * SPEED;
		} else if (count == 2) {
			vec2 mid0 = 0.5 * (s2.xy + s2.zw);
			vec2 mid1 = 0.5 * (now0 + now1);
			s0.zw += (mid1 - mid0) * SPEED;
			vec2 d0 = s2.zw - s2.xy;
			vec2 d1 = now1 - now0;
			float turn = atan(d1.y, d1.x) - atan(d0.y, d0.x);
			turn -= 6.2831853 * floor(turn / 6.2831853 + 0.5);
			s1.x += turn * 0.25;
		} else if (count == 3) {
			float mid = (now0.y + now1.y + now2.y) / 3.0;
			s4.z += mid - s2.x;
			if (abs(s4.z) > N_STEP) {
				s1.y = clamp(s1.y + sign(s4.z), 2.0, 12.0);
				s4.z = 0.0;
			}
		}
	}
	if (count != int(s3.x)) {
		s4.z = 0.0;
	}
	// Taps: a quick touch; two close together are a double tap.
	if (count > 0 && s3.x == 0.0) {
		s3.w = iTime;
	} else if (count == 0 && s3.x > 0.0) {
		if (iTime - s3.w < TAP_TIME) {
			s3.y = iTime - s3.z < TAP_GAP ? s3.y + 1.0 : 1.0;
			s3.z = iTime;
			if (s3.y >= 2.0) {
				s4.x = mod(s4.x + 1.0, float(PRESETS));
				vec2 b;
				preset(int(s4.x), s0, b);
				s1.xy = b;
				s3.y = 0.0;
				s4.w = iTime;
			}
		} else {
			s3.y = 0.0;
		}
	}
	if (any(notEqual(s0, before0)) || any(notEqual(s1.xy, before1))) {
		s4.y = float(iFrame);
	}
	// The attractor's size, from a few particles: smoothed, so the
	// picture doesn't jump.
	ivec2 size = textureSize(iChannel1, 0);
	int particles = size.x * size.y / 2;
	float far = 0.0;
	for (int i = 0; i < 64; ++i) {
		int k = (i * 7919 + iFrame * 131) % particles;
		vec2 z = texelFetch(iChannel1, ivec2(k % size.x, k / size.x), 0).xy;
		float r = length(z);
		if (r < 50.0) {
			far = max(far, r);
		}
	}
	s1.z = far > 0.0 ? mix(s1.z, far, 0.1) : s1.z;
	s2 = vec4(count == 3 ? vec2((now0.y + now1.y + now2.y) / 3.0, 0.0) : now0, now1);
	s3.x = float(count);
	fragColor = p.x == 0 ? s0 : p.x == 1 ? s1 : p.x == 2 ? s2 : p.x == 3 ? s3 : s4;
}

//// Buffer B
// The particles: z (xy), how far the last step jumped (z), and a 2 once
// started (a new buffer clears to 1). Only the first half of the texels
// are particles - one per splat.
///float32

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;

#define STEPS 6 // steps of the map a frame
#define RESEED 0.002 // the share of particles started afresh each frame

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

vec2 cmul(vec2 a, vec2 b) {
	return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
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
	float lambda = s0.x, alpha = s0.y, beta = s0.z, gamma = s0.w, omega = s1.x;
	int n = int(s1.y + 0.5);
	state = pcg(uint(index) * 9781u + pcg(uint(iFrame)));
	vec4 part = texelFetch(iChannel1, p, 0);
	if (part.w != 2.0 || rnd() < RESEED || any(isnan(part.xy)) || dot(part.xy, part.xy) > 400.0) {
		part = vec4((vec2(rnd(), rnd()) - 0.5) * 0.6, 0.0, 2.0);
	}
	vec2 z = part.xy;
	vec2 last = z;
	for (int step = 0; step < STEPS; ++step) {
		// z^(n-1), then z^n and conj(z)^(n-1).
		vec2 zn1 = vec2(1.0, 0.0);
		for (int k = 1; k < 12; ++k) {
			if (k < n) {
				zn1 = cmul(zn1, z);
			}
		}
		vec2 zn = cmul(zn1, z);
		vec2 conjN1 = vec2(zn1.x, -zn1.y);
		float a = lambda + alpha * dot(z, z) + beta * zn.x;
		last = z;
		z = cmul(vec2(a, omega), z) + gamma * conjN1;
	}
	fragColor = vec4(z, length(z - last), 2.0);
}

//// Buffer C
// The splats for Upscaling's 2D Gaussian Splats: particle i is splat i, in
// texels 2i (centre, major axis) and 2i+1 (colour, minor axis). Texel
// (0, 0) is the control texel instead (splat 0 isn't drawn): how much of
// the sum to keep (all while still, a fading few frames while being
// dragged), the exposure, log density.
///float32

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;

#define FIT 0.42 // the attractor's radius, as a share of the frame's shorter side
#define SPLAT_PX 0.6 // each splat's sigma, in frame pixels
#define EXPOSURE 1.5
#define HUE 70.0 // the palette's hue, in degrees (OKLCH)

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

// Slow points warm and pale, fast ones cooler: four OKLCH stops in OKLab,
// the hue turning with each preset.
vec3 palette(float t, float hue) {
	vec3 s0 = oklch(0.93, 0.05, hue + 20.0);
	vec3 s1 = oklch(0.78, 0.12, hue);
	vec3 s2 = oklch(0.62, 0.11, hue - 60.0);
	vec3 s3 = oklch(0.48, 0.09, hue - 140.0);
	t = clamp(t, 0.0, 1.0) * 3.0;
	vec3 lab = t < 1.0 ? mix(s0, s1, t) : t < 2.0 ? mix(s1, s2, t - 1.0) : mix(s2, s3, t - 2.0);
	return linearToSrgb(oklabToLinear(lab));
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 size = ivec2(iResolution.xy);
	ivec2 p = ivec2(fragCoord);
	vec4 s1 = texelFetch(iChannel0, ivec2(1, 0), 0);
	vec4 s4 = texelFetch(iChannel0, ivec2(4, 0), 0);
	if (p == ivec2(0)) {
		bool still = float(iFrame) - s4.y > 2.0;
		fragColor = vec4(still ? 1.0 : 0.5, EXPOSURE, -1.0, 2.0);
		return;
	}
	int index = p.y * size.x + p.x;
	int splat = index / 2;
	if (splat >= size.x * size.y / 2) {
		fragColor = vec4(0.0);
		return;
	}
	vec4 part = texelFetch(iChannel1, ivec2(splat % size.x, splat / size.x), 0);
	float radius = max(s1.z, 0.05);
	float sigma = SPLAT_PX / iResolution.y; // frame heights
	if (index % 2 == 0) {
		float fit = FIT * min(iResolution.x, iResolution.y) / iResolution.y; // frame heights
		vec2 uv = 0.5 + part.xy * (fit / radius) * vec2(iResolution.y / iResolution.x, 1.0);
		fragColor = vec4(uv, sigma, 0.0);
	} else {
		float t = pow(clamp(part.z / (1.6 * radius), 0.0, 1.0), 0.7);
		fragColor = vec4(max(palette(t, HUE + 47.0 * s4.x), vec3(1e-4)), sigma);
	}
}
