// Metropolis Buddhabrot (points): the density of escaping Mandelbrot
// orbits, drawn by Upscaling's Accumulate Points (the ///upscale line
// below picks it), added up at the render quality's resolution - lower
// quality, smoother and faster.
///upscale:points
//
// The buffers are a conveyor. Every chain (SPAN texels in reading order)
// carries orbits one step further each frame: texel k holds step k of the
// orbit that started k frames ago, and Buffer D plots it - so every texel
// is a point every frame. A chain's first texel is where orbits start: a
// Metropolis chain over starting points c, mostly small steps, sometimes
// anywhere, kept with the odds new score / old, the score being how many
// of the orbit's points land on screen. Each orbit is plotted with weight
// 1 / score, which makes the total a fair picture of the buddhabrot.
// Colours are nebulabrot-style: red for every orbit, green for those
// escaping within LIMIT_G steps, blue within LIMIT_B.
//
// One finger pans, two pinch to zoom and turn, three go back to the start.
// The picture builds up while the view is still.

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	fragColor = vec4(0.0, 0.0, 0.0, 1.0);
}

//// Buffer A
// The view, in the first four texels of the bottom row: 0 the point of the
// plane at the screen's centre (xy), how much of the plane the screen's
// height spans (z) and its turn (w); 1 last frame's view; 2 last frame's
// first two touches; 3 last frame's finger count (x), frames since the
// view last moved (y), and a 2 once it's set (w).
///float32

uniform sampler2D iChannel0; ///buf:A;
uniform vec3 pointers[10];
uniform int pointerCount;

#define START_CENTRE vec2(-0.4, 0.0)
#define START_SPAN 3.2
#define START_ANGLE 1.5707963 // the real axis pointing up: the seated Buddha
#define MIN_SPAN 1e-5
#define MAX_SPAN 8.0

mat2 rot(float a) {
	float c = cos(a), s = sin(a);
	return mat2(c, s, -s, c);
}

vec2 toWorld(vec2 p, vec4 view, vec2 res) {
	return view.xy + rot(view.w) * ((p - 0.5 * res) / res.y * view.z);
}

float wrapAngle(float a) {
	return a - 6.2831853 * floor(a / 6.2831853 + 0.5);
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	if (p.y != 0 || p.x > 3) {
		fragColor = vec4(0.0);
		return;
	}
	vec2 res = iResolution.xy;
	vec4 view = texelFetch(iChannel0, ivec2(0, 0), 0);
	vec4 lastTouch = texelFetch(iChannel0, ivec2(2, 0), 0);
	vec4 misc = texelFetch(iChannel0, ivec2(3, 0), 0);
	bool started = misc.w == 2.0;
	if (!started) {
		view = vec4(START_CENTRE, START_SPAN, START_ANGLE);
		misc = vec4(0.0);
	}
	vec4 before = view;
	int count = pointerCount;
	vec2 now0 = pointers[0].xy;
	vec2 now1 = pointers[1].xy;
	if (count >= 3) {
		view = vec4(START_CENTRE, START_SPAN, START_ANGLE);
	} else if (count > 0 && int(misc.x) == count) {
		if (count == 1) {
			view.xy -= rot(view.w) * ((now0 - lastTouch.xy) / res.y * view.z);
		} else {
			// Keep the point that was between the fingers between them.
			vec2 mid0 = 0.5 * (lastTouch.xy + lastTouch.zw);
			vec2 mid1 = 0.5 * (now0 + now1);
			vec2 anchor = toWorld(mid0, view, res);
			vec2 d0 = lastTouch.zw - lastTouch.xy;
			vec2 d1 = now1 - now0;
			view.z = clamp(view.z * max(length(d0), 1.0) / max(length(d1), 1.0), MIN_SPAN, MAX_SPAN);
			view.w = wrapAngle(view.w + wrapAngle(atan(d0.y, d0.x) - atan(d1.y, d1.x)));
			view.xy = anchor - rot(view.w) * ((mid1 - 0.5 * res) / res.y * view.z);
		}
	}
	bool moved = !started || any(notEqual(view, before));
	if (p.x == 0) {
		fragColor = view;
	} else if (p.x == 1) {
		fragColor = before;
	} else if (p.x == 2) {
		fragColor = vec4(now0, now1);
	} else {
		fragColor = vec4(float(count), moved ? 0.0 : misc.y + 1.0, 0.0, 2.0);
	}
}

//// Buffer B
// The conveyor's orbits: each texel is (z, c) - step k of an orbit and its
// starting point. A chain's first texel (k = 0) picks the next c (see the
// top) and, its z being 0 anyway, holds the orbit's escape step and score
// there instead: (escape, score, c). Every other texel takes the texel
// before it one step on.
///float32

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;

#define LIMIT 1000 // the longest orbit (red), in steps
#define SPAN (LIMIT + 1) // texels a chain
#define UNIFORM 0.2 // how often to propose anywhere instead of a step
#define PI 3.14159265

uint seed;

uint pcg(uint v) {
	uint s = v * 747796405u + 2891336453u;
	uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
	return (w >> 22u) ^ w;
}

float rnd() {
	seed = pcg(seed);
	return float(seed) / 4294967296.0;
}

vec2 gauss2() {
	float r = sqrt(-2.0 * log(max(rnd(), 1e-12)));
	float a = 2.0 * PI * rnd();
	return r * vec2(cos(a), sin(a));
}

mat2 rot(float a) {
	float c = cos(a), s = sin(a);
	return mat2(c, s, -s, c);
}

// Where on screen (0 to 1 each way) the plane's point w is.
vec2 toUv(vec2 w, vec4 view, vec2 res) {
	vec2 p = transpose(rot(view.w)) * (w - view.xy) / view.z;
	return 0.5 + p * vec2(res.y / res.x, 1.0);
}

bool neverEscapes(vec2 c) {
	// The main cardioid and the period-2 bulb.
	float x = c.x - 0.25;
	float q = x * x + c.y * c.y;
	return q * (q + x) <= 0.25 * c.y * c.y || dot(c + vec2(1.0, 0.0), c + vec2(1.0, 0.0)) <= 0.0625;
}

// c's orbit: when it escapes (x, LIMIT + 1 for never) and how many of its
// points land on screen (y).
vec2 orbit(vec2 c, vec4 view, vec2 res) {
	if (neverEscapes(c)) {
		return vec2(float(LIMIT + 1), 0.0);
	}
	vec2 z = vec2(0.0);
	float seen = 0.0;
	for (int k = 1; k <= LIMIT; ++k) {
		z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
		if (dot(z, z) > 4.0) {
			return vec2(float(k), seen);
		}
		vec2 uv = toUv(z, view, res);
		if (all(greaterThanEqual(uv, vec2(0.0))) && all(lessThan(uv, vec2(1.0)))) {
			seen += 1.0;
		}
	}
	return vec2(float(LIMIT + 1), 0.0);
}

vec2 anywhere() {
	float r = 2.0 * sqrt(rnd());
	float a = 2.0 * PI * rnd();
	return r * vec2(cos(a), sin(a));
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 size = ivec2(iResolution.xy);
	ivec2 p = ivec2(fragCoord);
	int index = p.y * size.x + p.x;
	int k = index % SPAN;
	if (index / SPAN >= size.x * size.y / SPAN) {
		fragColor = vec4(0.0); // past the last whole chain
		return;
	}
	if (k > 0) {
		int prev = index - 1;
		vec4 b = texelFetch(iChannel1, ivec2(prev % size.x, prev / size.x), 0);
		vec2 z = k == 1 ? vec2(0.0) : b.xy;
		if (dot(z, z) <= 4.0) {
			z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + b.zw;
		}
		fragColor = vec4(z, b.zw);
		return;
	}

	// A chain's start: Metropolis over c.
	seed = pcg(uint(index) + pcg(uint(iFrame)));
	vec2 res = iResolution.xy;
	vec4 view = texelFetch(iChannel0, ivec2(0, 0), 0);
	vec4 state = texelFetch(iChannel1, p, 0);
	vec2 c = state.zw;
	vec2 o = state.xy; // escape step, score
	vec2 cn = rnd() < UNIFORM || o.y <= 0.0
			? anywhere()
			: c + gauss2() * view.z * 0.1 * pow(10.0, -3.0 * rnd());
	vec2 on = orbit(cn, view, res);
	if (on.y > 0.0 && (o.y <= 0.0 || rnd() * o.y < on.y)) {
		c = cn;
		o = on;
	}
	fragColor = vec4(o, c);
}

//// Buffer C
// Rides along with Buffer B: each texel is the orbit's escape step (x) and
// its weight (y: 1 / its on-screen points, 0 for nothing to draw), taken
// at a chain's start from Buffer B's. Orbits with only a few points on
// screen would weigh nearly a whole orbit each and sparkle; MIN_SCORE caps
// their weight (0: no cap, the fair but noisier picture).
///float32

uniform sampler2D iChannel1; ///buf:B;
uniform sampler2D iChannel2; ///buf:C;

#define LIMIT 1000
#define SPAN (LIMIT + 1)
#define MIN_SCORE 24.0

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 size = ivec2(iResolution.xy);
	ivec2 p = ivec2(fragCoord);
	int index = p.y * size.x + p.x;
	int k = index % SPAN;
	if (index / SPAN >= size.x * size.y / SPAN) {
		fragColor = vec4(0.0);
		return;
	}
	if (k > 0) {
		int prev = index - 1;
		fragColor = texelFetch(iChannel2, ivec2(prev % size.x, prev / size.x), 0);
		return;
	}
	vec2 o = texelFetch(iChannel1, p, 0).xy; // this frame's escape step, score
	bool draw = o.x <= float(LIMIT) && o.y > 0.0;
	fragColor = vec4(o.x, draw ? 1.0 / max(o.y, MIN_SCORE) : 0.0, 0.0, 0.0);
}

//// Buffer D
// The points for Upscaling's Accumulate Points: each texel plots its orbit
// step (x, y on screen, intensity, colour 0xRRGGBB). Texel (0, 0) - a
// chain's start, never a point - is the control texel: keep adding while
// the view is still, the exposure, the point radius, tone-mapped.
///float32

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;
uniform sampler2D iChannel2; ///buf:C;

#define LIMIT 1000
#define SPAN (LIMIT + 1)
#define LIMIT_G 200 // green: orbits escaping within this many steps
#define LIMIT_B 40 // blue
#define GAIN vec3(0.45, 0.75, 1.0) // each colour's share
#define EXPOSURE 0.5 // also the Upscaling's amount
#define POINT_RADIUS 0.8 // a soft dot's sigma, in frame pixels (0: single pixels)

mat2 rot(float a) {
	float c = cos(a), s = sin(a);
	return mat2(c, s, -s, c);
}

vec2 toUv(vec2 w, vec4 view, vec2 res) {
	vec2 p = transpose(rot(view.w)) * (w - view.xy) / view.z;
	return 0.5 + p * vec2(res.y / res.x, 1.0);
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 size = ivec2(iResolution.xy);
	ivec2 p = ivec2(fragCoord);
	vec4 view = texelFetch(iChannel0, ivec2(0, 0), 0);
	if (p == ivec2(0)) {
		// Each frame's orbits add up to one per chain, spread over the
		// frame's pixels.
		float chains = float(size.x * size.y / SPAN);
		float pixels = float(size.x * size.y);
		float still = texelFetch(iChannel0, ivec2(3, 0), 0).y;
		fragColor = vec4(still > 1.0 ? 1.0 : 0.0, EXPOSURE * pixels / chains, -1.0 - POINT_RADIUS, 1.0);
		return;
	}
	int index = p.y * size.x + p.x;
	int k = index % SPAN;
	vec4 b = texelFetch(iChannel1, p, 0);
	vec4 c = texelFetch(iChannel2, p, 0);
	if (k == 0 || float(k) >= c.x || c.y <= 0.0 || index / SPAN >= size.x * size.y / SPAN) {
		fragColor = vec4(0.0);
		return;
	}
	vec3 rgb = floor(255.0 * GAIN * vec3(1.0,
			c.x <= float(LIMIT_G) ? 1.0 : 0.0,
			c.x <= float(LIMIT_B) ? 1.0 : 0.0) + 0.5);
	fragColor = vec4(toUv(b.xy, view, iResolution.xy), c.y,
			rgb.r * 65536.0 + rgb.g * 256.0 + rgb.b);
}
