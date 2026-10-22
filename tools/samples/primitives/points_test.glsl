// Points test: a Clifford attractor for Upscaling's Accumulate Points.
// Buffer A is the point list - each texel starts somewhere random every
// frame, takes STEPS steps onto the attractor and is drawn where it lands.
// Texel (0, 0) is the control texel: keep adding, exposure, tone-mapped.
///upscale:points

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	fragColor = vec4(0.0, 0.0, 0.0, 1.0);
}

//// Buffer A
///float32

#define A -1.4
#define B 1.6
#define C 1.0
#define D 0.7
#define STEPS 40
#define EXPOSURE 3.0

float hash(vec2 p) {
	vec3 q = fract(vec3(p.xyx) * 0.1031);
	q += dot(q, q.yzx + 33.33);
	return fract((q.x + q.y) * q.z);
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	if (ivec2(fragCoord) == ivec2(0)) {
		fragColor = vec4(1.0, EXPOSURE, -1.0, 1.0);
		return;
	}
	vec2 seed = fragCoord + float(iFrame % 4096) * vec2(17.13, 3.71);
	vec2 p = vec2(hash(seed), hash(seed + 7.7)) * 4.0 - 2.0;
	for (int i = 0; i < STEPS; ++i) {
		p = vec2(sin(A * p.y) + C * cos(A * p.x), sin(B * p.x) + D * cos(B * p.y));
	}
	vec2 res = iResolution.xy;
	vec2 uv = 0.5 + p / 5.0 * vec2(res.y / res.x, 1.0);
	// Colour by where it is: 0xRRGGBB.
	float r = floor(255.0 * (0.5 + 0.5 * sin(p.x * 2.0)));
	float g = floor(255.0 * (0.4 + 0.3 * cos(p.y * 2.5)));
	float b = 255.0;
	fragColor = vec4(uv, 0.2, r * 65536.0 + g * 256.0 + b);
}
