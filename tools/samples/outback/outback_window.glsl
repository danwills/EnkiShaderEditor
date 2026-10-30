// OUTBACK WINDOW - the edge of an outback highway streaming past the
// passenger window: bitumen and its markings, red gravel and dirt,
// spinifex, saltbush, buffel grass, mulga and the odd ghost gum, termite
// mounds, red boulders, guide posts, now and then power poles, and red
// ranges far off - through a day: the sun sets in front of you and the
// moon rises, the stars come out. Now and then a sharp corner to slow for,
// a crossroads to stop at, a glint off glass in the dirt.
//
// FINGERS
//   three fingers     hold them down and the car slows and pulls over
//                     onto the gravel (time stops); drag them left or
//                     right to scrub through time, back or forward; let go
//                     and it pulls out and drives on
//
// HOW IT WORKS
// Buffer A keeps the clock - how far along the road, the time of day, how
// fast time's going - and drives the car: as fast as the road allows
// (slowing for sharp corners, and to a crawl over stop signs' lines), pulled
// over when it's slowed by hand. Its suspension is a spring on each of
// bounce, pitch and roll, driven by bumps and potholes, braking and
// accelerating, and cornering. It also looks for glints: shards of glass or
// quartz scattered in the dirt, each a mirror tilted at random, glinting
// when the light reflects off one into the eye.
// Buffer B tables what stands in each cell near the car, once a frame.
// Buffer C is an atlas of trees, drawn once (branches, twigs, clumps of
// leaves with gaps, their colours and normals), each tile's rows summed
// from its left edge so a pixel's stretch of a row, however blurred, is
// two filtered reads.
// The Image pass looks out sideways (the car drives to the right, on the
// left of the road), so the road's along the screen and everything streams
// past, faster the nearer it is. The road has long straights and bends:
// the near things are laid out along the road, so they bend with it, and
// the far ones turn as the car does. Each pixel's ray:
//   - The ground, laid out by how far out from the road it is: the lane,
//     the edge line (painted well or badly, worn, dashed, missing), the
//     sealed shoulder with its potholes and broken edge, the gravel, the
//     dirt; now and then a turning lane with its arrows, by a dirt road
//     going off into the bush. Its noise is box-filtered along the road by
//     the motion blur (SHUTTER at the car's speed) and the pixel's size: an
//     octave finer than that is averaged (streaks along the road, quieter
//     the more it averages), so near the car the ground's a blur of streaks
//     and far off it's crisp. Pebbles are discs, box-filtered the same way.
//   - Plants, rocks and posts: flat cut-outs standing in rows (LANES of
//     them, further apart the further out), one at most in each cell of a
//     row. Each is a set of spans across, at each height (a dome, a cone,
//     a canopy...), so the blur's just how much of the pixel's stretch of
//     road a span covers - exact, at any speed, with no samples. Spinifex
//     needles are a radiating square wave, integrated the same way.
//     Trees are the atlas's, its sums read the same way and lit by its
//     normals.
//   - Shadows from the same spans, and a little darkness under each thing.
//   - The ranges: a height field marched out to 45 km, lit by the sun.
//   - Dust haze nearer, air haze far off.
//   - A glint's flare: its shard put on the screen, a hot core, a halo and
//     spikes, as bright as the light.
//   - The sky: by the sun's height, the sunset's glow round it, the sun
//     and moon, stars and the Milky Way.

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;
uniform sampler2D iChannel2; ///buf:C;

// The tree atlas: whether it's ready, and its layout (atlasLayout()).
bool gAtlas;
ivec3 gTiles;
vec2 gAtlasRes;

// ---- Light, set once a pixel by setLight(), in the car's frame: towards
// the sun and moon; the key light (the sun, or the moon at night) and its
// colour; the sky's light; the light on the left (shady) and right sides
// of things; what plants glow with, lit from behind; daylight (0-1); how
// much the picture's brightened (more at night).
vec3 gSun, gMoon, gSunCol, gLight, gLightCol, gAmbient, gLeft, gRight, gGlow;
float gDay, gExposure;
// How far out the car's pulled over.
float gPull;

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
	// (A box filter's sinc, near enough.)
	float a1 = amp / (1.0 + 3.0 * f * f * wx * wx), a2 = 0.4 * amp / (1.0 + 48.0 * f * f * wx * wx);
	float n = a1 * (noise(vec2(x * f, seed)) * 2.0 - 1.0) + a2 * (noise(vec2(x * f * 4.0, seed + 3.0)) * 2.0 - 1.0);
	return vec2(n, (amp - a1) + (0.4 * amp - a2));
}

float overlap(float a0, float a1, float b0, float b1) {
	return max(0.0, min(a1, b1) - max(a0, b0));
}

// A square wave in u, on for d of each period, summed up to u.
float wave(float u, float d) {
	return floor(u) * d + min(fract(u), d);
}

// How much of a to b is on, for dashes period long, on for duty of it.
float dashes(float a, float b, float period, float duty) {
	return (wave(b / period, duty) - wave(a / period, duty)) * period / max(b - a, 1e-5);
}

// ---- The ground.
// A stone's colour: ironstone, quartz, red jasper, grey, ochre, limestone.
vec3 stoneColour(float h) {
	vec3 c = h < 0.3 ? vec3(0.28, 0.14, 0.09) : h < 0.45 ? vec3(0.88, 0.85, 0.80)
			: h < 0.65 ? vec3(0.62, 0.20, 0.11) : h < 0.8 ? vec3(0.50, 0.48, 0.46)
			: h < 0.92 ? vec3(0.76, 0.56, 0.26) : vec3(0.80, 0.78, 0.70);
	return c * (0.85 + 0.3 * fract(h * 13.7));
}

// Pebbles on a grid of cells: in each, at chance `many`, a disc of radius
// up to rmax (mostly small: rmax times a random number squared) of a random
// stone's colour, box-filtered along the road over wx. Finer than the pixel
// or the blur, their mean. Their colour, and how much of the pixel they
// cover.
vec4 stones(float x, float z, float wx, float wz, float cell, float rmax, float many, float seed) {
	vec3 meanCol = vec3(0.58, 0.42, 0.33);
	float mean = many * PI * rmax * rmax / 5.0 / (cell * cell);
	float coarse = smoothstep(0.6, 1.5, max(wx, 2.0 * wz) / cell);
	vec4 acc = vec4(0.0);
	if (coarse < 1.0) {
		float zc = floor(z / cell);
		float first = floor((x - 0.5 * wx) / cell);
		for (int i = 0; i < 3; i++) {
			float xc = first + float(i);
			if (xc * cell > x + 0.5 * wx) {
				break;
			}
			vec4 h = hash42(vec2(xc, zc) + seed);
			if (h.x > many) {
				continue;
			}
			float r = rmax * h.y * h.y;
			vec2 c = (vec2(xc, zc) + 0.5) * cell + (0.5 * cell - r) * (hash42(vec2(zc, xc) + seed + 7.0).xy * 2.0 - 1.0);
			float dz = z - c.y;
			if (abs(dz) >= r) {
				continue;
			}
			float hw = sqrt(r * r - dz * dz);
			float o = overlap(x - 0.5 * wx, x + 0.5 * wx, c.x - hw, c.x + hw);
			// Lit on top: the far side.
			acc += vec4(stoneColour(h.w) * (0.75 + 0.3 * dz / r), 1.0) * o;
		}
		acc /= max(wx, 1e-5);
	}
	vec4 detail = acc.a > 0.0 ? vec4(acc.rgb / acc.a, min(acc.a, 1.0)) : vec4(meanCol, 0.0);
	return mix(detail, vec4(meanCol, min(mean, 1.0)), coarse);
}

// A turning arrow, ax along from its shaft's end, dz out from its shaft: the
// shaft along the road, then bending out (dir 1) or in (-1) to its head.
float arrowAt(float ax, float dz, float dir) {
	dz *= dir;
	float shaft = step(-3.0, ax) * step(ax, 0.08) * step(abs(dz), 0.08);
	float bend = step(abs(ax), 0.08) * step(0.0, dz) * step(dz, 0.7);
	float t = (dz - 0.7) / 0.6;
	float head = step(0.0, t) * step(t, 1.0) * step(abs(ax), 0.32 * (1.0 - t));
	return max(shaft, max(bend, head));
}

// The turning lane's arrows (side road at `at`, the lane's middle zm): two
// turning out before it, one merging back after; averaged over wx.
float arrows(float x, float z, float wx, float at, float zm) {
	float sum = 0.0;
	for (int i = 0; i < 4; i++) {
		float xs = x + wx * ((float(i) + 0.5) / 4.0 - 0.5);
		sum += arrowAt(xs - (at - 60.0), z - zm + 0.5, 1.0) + arrowAt(xs - (at - 140.0), z - zm + 0.5, 1.0)
				+ arrowAt(xs - (at + 110.0), z - zm - 0.5, -1.0);
	}
	return min(sum / 4.0, 1.0);
}

// The paint at x, z: how much (0-1), and its brightness. The edge line has
// a style for each 400 m: mostly well painted with raised ribs, but worn,
// badly painted (wobbly, uneven, oversprayed), dashed, or none. By a
// turning lane, the old edge becomes a dashed lane line, the edge line
// goes round the lane, and there are arrows; the side road breaks it.
vec2 markings(float x, float z, float wx, float wz, vec2 turn) {
	float a0 = x - 0.5 * wx, a1 = x + 0.5 * wx;
	float soft = 0.5 * wz + 0.004;
	vec4 h = hash42(vec2(floor(x / 400.0), 5.0));
	bool bad = h.x > 0.88;
	float wobble = (bad ? 0.05 : 0.012) * (noise(vec2(x / 7.0, 1.0)) * 2.0 - 1.0);
	float width = LINE_W * (bad ? 0.75 + 0.5 * noise(vec2(x / 3.0, 2.0)) : 1.0);
	// (Painted a little in and out, slowly.)
	wobble += 0.15 * (noise(vec2(x / 80.0, 12.0)) * 2.0 - 1.0) + 0.04 * (noise(vec2(x / 14.0, 13.0)) * 2.0 - 1.0);
	float z0 = LINE_Z + turn.y + wobble;
	float across = smoothstep(z0 - soft, z0 + soft, z) * smoothstep(z0 + width + soft, z0 + width - soft, z);
	if (bad) {
		// Overspray: a faint fringe.
		float s = soft + 0.03;
		across = max(across, 0.3 * smoothstep(z0 - s, z0, z) * smoothstep(z0 + width + s, z0 + width, z));
	}
	float along = h.x < 0.12 ? dashes(a0, a1, 12.0, 0.25) : h.x < 0.17 ? 0.0 : 1.0;
	float worn = h.x < 0.35 ? 0.55 + 0.3 * h.y : bad ? 0.3 : 0.12 * h.y;
	float wear = clamp(1.0 - worn + 0.8 * streaks(vec2(x, z * 4.0), 3.0, 3, wx, wz * 4.0), 0.0, 1.0);
	along *= 1.0 - overlap(a0, a1, turn.x - 6.0, turn.x + 6.0) / max(wx, 1e-5);
	float paint = across * along * wear;
	// Raised ribs, on the well painted lines.
	float ribs = h.x > 0.35 && !bad ? dashes(a0, a1, 0.5, 0.12) : 0.0;
	if (turn.y > 0.05) {
		float z1 = LINE_Z + wobble;
		float lane = smoothstep(z1 - soft, z1 + soft, z) * smoothstep(z1 + LINE_W + soft, z1 + LINE_W - soft, z);
		paint = max(paint, lane * dashes(a0, a1, 4.0, 0.25) * wear);
		float zm = LINE_Z + LINE_W + 0.5 * turn.y;
		paint = max(paint, arrows(x, z, wx, turn.x, zm) * wear * clamp(turn.y / TURN_W * 2.0 - 1.0, 0.0, 1.0));
	}
	return vec2(paint, 0.9 + 0.25 * ribs);
}

// The ground at x along the road and z out, averaged over wx along and wz
// across: its colour in the midday sun.
vec3 ground(float x, float z, float wx, float wz) {
	vec2 p = vec2(x, z);
	vec2 turn = turning(x);
	float cross = crossing(x);
	float rough = roughness(x);
	// Bitumen: chip seal, a darker polished wheel path, red dust towards
	// the edge.
	float chips = streaks(p, 6.0, 3, wx, wz);
	vec3 col = BITUMEN * (1.0 + 0.5 * chips);
	// The chips: small grey stones packed close, a few reddish.
	vec4 st = stones(x, z, wx, wz, 0.016, 0.0072, 0.85, 120.0);
	col = mix(col, mix(CHIPS, st.rgb * vec3(0.62, 0.66, 0.7), 0.4) * (1.0 + 0.3 * chips), st.a);
	col *= 1.0 - 0.25 * smoothstep(0.4, 0.0, abs(z - 0.75));
	// Fine stripes along it - wheel paths, drips, the chips' rows - which
	// the blur along the road leaves (faded as a pixel spans them).
	float lines = 0.45 * (noise(vec2(z * 23.0, x / 60.0)) - 0.5) * (1.0 - smoothstep(0.01, 0.04, wz))
			+ 0.35 * (noise(vec2(z * 71.0, x / 40.0 + 5.0)) - 0.5) * (1.0 - smoothstep(0.004, 0.014, wz));
	col *= 1.0 + lines;
	// The seal's edge: ragged, more so (and bitten into) where it's rough.
	vec2 seal = ragged(x, 2.5, 0.1 + 0.12 * rough, wx, 3.0);
	seal.x += 0.25 * (noise(vec2(x / 140.0, 21.0)) - 0.5);
	vec2 bites = ragged(x, 9.0, 0.1 * rough, wx, 5.0);
	float sealZ = SEAL_Z + turn.y + seal.x + bites.x;
	float dust = smoothstep(LINE_Z + turn.y, sealZ, z) * (0.5 + 0.5 * streaks(p, 2.0, 2, wx, wz));
	col = mix(col, DIRT * 0.7, 0.45 * dust);
	vec2 paint = markings(x, z, wx, wz, turn);
	// (The edge line stops at the crossroads; a stop line across the lane.)
	paint.x *= 1.0 - overlap(x - 0.5 * wx, x + 0.5 * wx, cross - 5.0, cross + 5.0) / max(wx, 1e-5);
	paint.x = max(paint.x, overlap(x - 0.5 * wx, x + 0.5 * wx, cross - 6.6, cross - 6.1) / max(wx, 1e-5)
			* smoothstep(LINE_Z + 0.1 + wz, LINE_Z + 0.1 - wz, z));
	col = mix(col, PAINT * paint.y * (0.9 + 0.1 * chips), paint.x);
	// A pothole: broken chips round it, dark and dusty in it.
	vec4 hole = pothole(x);
	if (hole.z > 0.0) {
		float dz = (z - hole.y) / hole.w;
		if (abs(dz) < 1.3) {
			float lumpy = 1.0 + 0.2 * (noise(vec2(z * 8.0, hole.x)) - 0.5);
			float inner = hole.z * lumpy * sqrt(max(0.0, 1.0 - dz * dz));
			float outer = hole.z * lumpy * 1.3 * sqrt(max(0.0, 1.0 - dz * dz / 1.69));
			float a0 = x - 0.5 * wx, a1 = x + 0.5 * wx;
			float in_ = overlap(a0, a1, hole.x - inner, hole.x + inner) / wx;
			float rim = overlap(a0, a1, hole.x - outer, hole.x + outer) / wx - in_;
			col = mix(col, CHIPS * 1.15, 0.6 * rim);
			col = mix(col, mix(BITUMEN * 0.4, DIRT * 0.45, 0.5 + 0.4 * dz), in_);
		}
	}
	// The gravel shoulder.
	float softSeal = seal.y + bites.y + 0.5 * wz + 0.01;
	float gravel = smoothstep(sealZ - softSeal, sealZ + softSeal, z);
	// Its stones' colours (red jasper, quartz, ironstone, ochre) streak out
	// along the road at speed.
	vec3 hue = mix(vec3(0.62, 0.20, 0.11), vec3(0.82, 0.78, 0.68), clamp(0.5 + 2.5 * streaks(p + 90.0, 30.0, 2, wx, wz), 0.0, 1.0));
	hue = mix(vec3(0.26, 0.14, 0.10), hue, clamp(0.6 + 2.5 * streaks(p + 130.0, 24.0, 2, wx, wz), 0.0, 1.0));
	hue = mix(hue, vec3(0.76, 0.56, 0.26), clamp(2.5 * streaks(p + 170.0, 20.0, 2, wx, wz), 0.0, 1.0));
	float vergeZ = verge(x) + turn.y;
	// Bands along it, graded in by the grader: sandy, grey, and here and
	// there a pale edge to the dirt.
	float out_ = (z - sealZ) / max(vergeZ - sealZ, 0.3);
	float bands = noise(vec2(out_ * 4.0 + noise(vec2(x / 300.0, 9.0)), x / 250.0)) * (1.0 - smoothstep(0.08, 0.3, wz));
	vec3 g = mix(GRAVEL, mix(vec3(0.74, 0.60, 0.42), vec3(0.50, 0.48, 0.45), smoothstep(0.3, 0.7, bands)), 0.6 * abs(bands * 2.0 - 1.0) + 0.1);
	g = mix(g, vec3(0.84, 0.74, 0.58), 0.6 * smoothstep(0.6, 0.8, noise(vec2(x / 200.0, 4.0))) * smoothstep(0.75, 0.95, out_) * smoothstep(1.1, 0.95, out_));
	col = mix(col, mix(g, hue, 0.5 * smoothstep(0.01, 0.15, wx)) * (0.85 + 0.4 * streaks(p + 40.0, 18.0, 3, wx, wz)), gravel);
	// The dirt: red, dusty patches, darker patches.
	vec2 edge = ragged(x, 1.2, 0.3, wx, 9.0) + ragged(x, 6.0, 0.1, wx, 19.0);
	float softDirt = edge.y + 0.5 * wz + 0.05;
	float dirt = smoothstep(vergeZ + edge.x - softDirt, vergeZ + edge.x + softDirt, z);
	float patches = streaks(p * 0.3, 1.0, 3, wx * 0.3, wz * 0.3);
	vec3 d = mix(DIRT, DUST, clamp(0.4 + patches, 0.0, 1.0));
	d *= 0.9 + 0.3 * streaks(p + 7.0, 10.0, 3, wx, wz);
	// Sandy country, and darker, greyer soil, over large regions.
	float soil = noise(vec2(x / 900.0, z / 260.0 + 11.0)) + 0.3 * noise(vec2(x / 230.0, z / 70.0 + 5.0)) - 0.15;
	d = mix(d, vec3(0.82, 0.64, 0.44) * (0.9 + 0.2 * patches), smoothstep(0.55, 0.8, soil));
	d = mix(d, vec3(0.40, 0.33, 0.29) * (0.9 + 0.2 * patches), 0.85 * smoothstep(0.4, 0.15, soil));
	// Further out the dirt's flecked with plants too small to stand up.
	d = mix(d, mix(SPINIFEX_CORE, SPINIFEX, 0.5) * 0.8, 0.3 * smoothstep(10.0, 80.0, z) * (0.6 + 0.4 * noise(p / 40.0)));
	col = mix(col, d, dirt);
	// The side road: a dirt track going off into the bush.
	float track = (overlap(x - 0.5 * wx, x + 0.5 * wx, turn.x - 3.5, turn.x + 3.5)
			+ overlap(x - 0.5 * wx, x + 0.5 * wx, cross - 4.0, cross + 4.0)) / wx;
	if (track > 0.0) {
		vec3 tr = TRACK * (0.85 + 0.3 * streaks(p.yx, 3.0, 3, wz, wx));
		col = mix(col, tr, track * smoothstep(sealZ - 0.1, sealZ + 0.3, z));
	}
	// Pebbles: on the gravel, and a few on the dirt.
	if (z > sealZ - 0.2 && z < vergeZ + 8.0) {
		float many = mix(1.0, 0.3, dirt) * (1.0 - track);
		vec4 st = stones(x, z, wx, wz, 0.045, 0.02, 0.55 * many, 0.0);
		col = mix(col, st.rgb, st.a);
		st = stones(x, z, wx, wz, 0.14, 0.063, 0.3 * many, 50.0);
		col = mix(col, st.rgb, st.a);
	}
	return col;
}

// ---- Spans: how much of a stretch of road (a0 to a1) a cut-out covers at
// one height, by its left (shady) and right (sunny) halves.
// A span from c - w to c + w of colour col, lit from its left (shady)
// side to its right (sunny) side, into acc: colour times length, and
// length. (The light's linear across it, so its mean over the overlap is
// the light at the overlap's middle.)
void span(inout vec4 acc, float a0, float a1, float c, float w, vec3 col) {
	float b0 = max(a0, c - w), b1 = min(a1, c + w);
	if (b1 > b0) {
		float u = clamp((0.5 * (b0 + b1) - c) / max(w, 1e-5), -1.0, 1.0);
		acc += vec4(col * mix(gLeft, gRight, 0.5 + 0.5 * u), 1.0) * (b1 - b0);
	}
}

// The length of road from a to b covered by needles: a square wave in
// u = x s + phase, duty d of each period.
float needles(float a, float b, float s, float phase, float d) {
	if (b <= a || d <= 0.0) {
		return 0.0;
	}
	return (wave(b * s + phase, d) - wave(a * s + phase, d)) / s;
}
// Needles from b0 to b1 (within a0 to a1), into acc: two patterns at
// different spacings, so they're not regular (where they cross, roughly).
void needleSpan(inout vec4 acc, float a0, float a1, float b0, float b1, float s, float phase, float d, vec3 col, vec3 light) {
	float lo = max(a0, b0), hi = min(a1, b1);
	if (hi <= lo) {
		return;
	}
	float f1 = needles(lo, hi, s, phase, 0.6 * d) / (hi - lo);
	float f2 = needles(lo, hi, 1.618 * s, phase * 1.7, 0.6 * d) / (hi - lo);
	float l = (f1 + f2 - f1 * f2) * (hi - lo);
	acc += vec4(col * light * l, l);
}

// The region the cut-out being drawn stands in (region()).
vec2 gRegion = vec2(0.5);

// A plant's own green: darker or lighter (by dark), bluer or yellower, and
// its region's - dry and golden, or dark and green.
vec3 plantTint(vec4 seed, float dark) {
	float a = fract(seed.y * 7.3 + seed.z * 3.1), b = fract(seed.x * 5.7 + seed.w * 2.3);
	vec3 own = mix(vec3(0.85, 1.0, 0.97), vec3(1.1, 1.02, 0.78), a) * mix(1.0 - dark, 1.0 - 0.4 * dark, b);
	return own * mix(vec3(1.15, 1.02, 0.72), vec3(0.62, 0.74, 0.62), smoothstep(0.25, 0.75, gRegion.y));
}

// A hummock of spinifex: a grey-green dome bristling with golden needles,
// which radiate from below its middle, tucked in at its foot over a few
// woody stems. A tuft is just the needles.
void spinifex(inout vec4 acc, float a0, float a1, float y, float r, float h, vec4 seed, bool tuft) {
	float he = h * (tuft ? 1.0 : 1.4);
	if (y >= he) {
		return;
	}
	float core = tuft ? 0.0 : h;
	float tuck = sqrt(smoothstep(0.0, 0.5 * h, y));
	float wc = y < core ? r * sqrt(1.0 - (y / core) * (y / core)) * (0.15 + 0.85 * tuck) : 0.0;
	float we = r * (tuft ? 1.0 : 1.2) * sqrt(1.0 - (y / he) * (y / he)) * (tuft ? 1.0 : 0.6 + 0.4 * tuck);
	// (Ragged: needles' tips at different heights.)
	we *= 0.85 + 0.15 * sin(y * 47.0 / h + seed.x * 20.0) * sin(y * 19.0 / h + seed.y * 9.0);
	if (!tuft && y < 0.35 * h) {
		// Stems: from the middle, forking out as they rise, seen through
		// the thinner foliage low down.
		for (int i = 0; i < 4; i++) {
			float k = float(i) - 1.5;
			float x = 0.1 * r * k + (0.5 + 0.4 * fract(seed.y * 13.0 + float(i) * 0.37)) * r * k * y / h * 2.0;
			span(acc, a0, a1, x, 0.012 + 0.015 * r * (1.0 - y / (0.35 * h)), BARK * 0.8);
		}
	}
	if (wc > 0.0) {
		vec3 col = mix(SPINIFEX_CORE * 0.75, SPINIFEX_CORE, smoothstep(0.0, core, y));
		col = mix(col, seed.z > 0.7 ? SPINIFEX * 0.8 : SPINIFEX_DARK, 0.35 * seed.w) * plantTint(seed, 0.45);
		vec4 c = vec4(0.0);
		// (Lit by span(), shady side to sunny. Shading the dome by its own
		// normal, or even by the light's height, tipped the phone's
		// compiler over: 16 fps to 2-3.)
		span(c, a0, a1, 0.0, wc, col);
		acc += c * (tuft ? 1.0 : mix(0.35, 1.0, tuck));
	}
	// Needles: spaced 0.03 of their slope out from below the middle.
	// (Finer or coarser, thicker or sparser, by region; patchy.)
	float s = 1.0 / (mix(0.02, 0.05, gRegion.x) * (y + 0.5 * h));
	float d = mix(0.65, 0.2, y / he) * mix(0.6, 1.2, gRegion.y) * (0.75 + 0.25 * sin(y * 23.0 / h + seed.z * 17.0));
	// Mostly dark grey-green; some dried golden.
	vec3 col = (seed.z > 0.7 ? mix(SPINIFEX, vec3(0.72, 0.68, 0.55), seed.y) * 0.8 : SPINIFEX_DARK * (0.7 + 0.3 * seed.y)) * plantTint(seed, 0.3)
			* mix(0.7, 1.0, y / he) * (0.85 + 0.15 * sin(y * 31.0 / h + seed.w * 12.0));
	float phase = seed.x * 7.0;
	needleSpan(acc, a0, a1, -we, -wc, s, phase, d, col, gLeft);
	needleSpan(acc, a0, a1, wc, we, s, phase, d, col, mix(gLeft, gRight, 0.6));
}

// Buffel grass: a tussock of arching stems, widening upwards, with seed
// heads (purplish, straw, or - a weed in flower - yellow).
void buffel(inout vec4 acc, float a0, float a1, float y, float r, float h, vec4 seed) {
	float t = y / h;
	if (t >= 1.0) {
		return;
	}
	float we = r * (0.3 + 0.7 * t) * (1.0 - 0.6 * smoothstep(0.8, 1.0, t));
	float s = 1.0 / (0.07 * (y + 0.3 * h));
	vec3 heads = seed.w > 0.8 ? vec3(0.95, 0.80, 0.22) : seed.w > 0.4 ? vec3(0.58, 0.46, 0.50) : vec3(0.82, 0.72, 0.50);
	vec3 col = mix(mix(BUFFEL * 0.65, BUFFEL, t), heads, smoothstep(0.65, 0.8, t));
	float d = mix(0.45, 0.22, t);
	float phase = seed.x * 5.0;
	needleSpan(acc, a0, a1, -we, 0.0, s, phase, d, col, gLeft);
	needleSpan(acc, a0, a1, 0.0, we, s, phase, d, col, gRight);
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

// The tree atlas's sums along a tile's rows at (x, y) texels in, from
// the tile's left edge: band 0's (coverage, colour) and band 1's (normal,
// leaves). Its texels hold the sum to their right edges, so a texel's
// middle is the sum to x + 0.5; filtering does the rest, linear along and
// between rows. (Four filtered reads: sixteen texelFetches took the phone
// from 13 to 1.6 fps.)
void atlasSum(vec2 o, float x, float y, float wgt, inout vec4 a, inout vec4 b) {
	vec2 p = (o + vec2(x - 0.5, y)) / gAtlasRes;
	a += wgt * texture(iChannel2, p);
	b += wgt * texture(iChannel2, p + vec2(0.0, float(gTiles.z) / gAtlasRes.y));
}

// The atlas tile for a tree or bush of that kind and seed.
int atlasTile(int kind, vec4 seed) {
	if (kind == BUSH_CLUMP) {
		return TREES + (seed.w < 0.2 ? BUSHES - 2 + int(seed.x * 2.0)
				: seed.w < 0.45 ? BUSHES - 4 + int(seed.x * 2.0)
				: int(seed.x * float(BUSHES - 4)));
	}
	return kind == GHOST_GUM ? MULGAS + OAKS + DEAD + int(seed.x * float(GUMS))
			: seed.w < 0.3 ? MULGAS + int(seed.x * float(OAKS))
			: seed.w > 0.82 ? MULGAS + OAKS + int(seed.x * float(DEAD))
			: int(seed.x * float(MULGAS));
}

// A tree or bush (size: radius, height) from the atlas (Buffer C), tile
// t, mirrored for some (seed): its tile's rows at height y, summed from a0
// to a1, so blurred exactly, and lit by its normals there.
void tree(inout vec4 acc, float a0, float a1, float y, vec2 size, vec4 seed, int t, float dark) {
	if (!gAtlas) {
		return;
	}
	float flip = fract(seed.x * 7.0) > 0.5 ? -1.0 : 1.0;
	if (flip < 0.0) {
		float s = a0;
		a0 = -a1;
		a1 = -s;
	}
	// Texels a metre across.
	float k = float(gTiles.x) / (2.0 * TREE_SPAN * size.x);
	// (Its first column's empty: nothing to sum before it.)
	float u0 = clamp((a0 + TREE_SPAN * size.x) * k, 1.0, float(gTiles.x));
	float u1 = clamp((a1 + TREE_SPAN * size.x) * k, 1.0, float(gTiles.x));
	if (u1 <= u0) {
		return;
	}
	float v = clamp(y / size.y * float(gTiles.y), 0.5, float(gTiles.y) - 0.5);
	vec2 o = vec2((t % TREE_COLS) * gTiles.x, (t / TREE_COLS) * gTiles.y);
	vec4 a = vec4(0.0), b = vec4(0.0);
	atlasSum(o, u1, v, 1.0, a, b);
	atlasSum(o, u0, v, -1.0, a, b);
	if (a.x <= 1e-5) {
		return;
	}
	// Lit as the other cut-outs are (by normals up and out a bit, as from
	// gLeft to gRight), turned by the atlas's, and wrapping round a little
	// (light through the leaves).
	vec2 n = vec2(flip * b.x, b.y) / a.x;
	vec3 light = gAmbient * (0.85 + 0.15 * n.y) + 1.1 * gLightCol * max(0.25 + 0.75 * dot(normalize(vec3(n.x, 0.6 + 0.4 * n.y, -0.35)), gLight), 0.0);
	// Each its own green (the leaves).
	vec3 tint = mix(vec3(1.0), plantTint(seed, dark), clamp(b.z / a.x, 0.0, 1.0));
	acc += vec4(a.yzw / a.x * tint * light, 1.0) * a.x / k;
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

// A stop sign: a red octagon with a white rim and lettering, on a grey
// post, standing slantwise to the road (so squashed across).
void stopSign(inout vec4 acc, float a0, float a1, float y) {
	if (y < 1.75) {
		span(acc, a0, a1, 0.0, 0.03, vec3(0.6, 0.6, 0.62));
	}
	float dy = abs(y - 2.05), a = 0.3, k = 0.55;
	if (dy >= a) {
		return;
	}
	vec3 red = vec3(0.72, 0.04, 0.04), white = vec3(0.92);
	span(acc, a0, a1, 0.0, min(a, a * 1.4142 - dy) * k, white);
	float ai = 0.88 * a;
	if (dy < ai) {
		float wi = min(ai, ai * 1.4142 - dy) * k;
		vec4 face = vec4(0.0), rim = vec4(0.0);
		span(rim, a0, a1, 0.0, wi, white);
		span(face, a0, a1, 0.0, wi, red);
		// The letters: white strokes across the middle.
		float lo = max(a0, -0.8 * wi), hi = min(a1, 0.8 * wi);
		if (dy < 0.3 * a && hi > lo) {
			float f = dashes(lo + 0.8 * wi, hi + 0.8 * wi, 0.4 * wi, 0.45) * (hi - lo);
			face.rgb += (white - red) * mix(gLeft, gRight, 0.5) * f;
		}
		acc += face - rim;
	}
}

// A power pole: a weathered post, and its crossarm and insulators - the
// arm reaches across (out from the road), so it's seen end on, stretched
// by how slantwise the view is (k: along per out).
void pole(inout vec4 acc, float a0, float a1, float y, float k) {
	if (y < 9.3) {
		span(acc, a0, a1, 0.0, mix(0.15, 0.1, y / 9.3), POLE_WOOD * mix(0.75, 1.0, y / 9.3));
	}
	if (y > 8.25 && y < 8.4) {
		span(acc, a0, a1, 0.0, 1.2 * abs(k) + 0.06, POLE_WOOD * 0.8);
	}
	vec3 glass = vec3(0.75, 0.72, 0.65);
	if (y > 8.4 && y < 8.56) {
		span(acc, a0, a1, 1.1 * k, 0.04, glass);
		span(acc, a0, a1, -1.1 * k, 0.04, glass);
	}
	if (y > 9.3 && y < 9.42) {
		span(acc, a0, a1, 0.0, 0.04, glass);
	}
}

// What stands in a cell, from Buffer B's table (worked out here if it's
// not in it).
int stood(int lane, float cell, float cw, float z, float along, out float x, out float zj, out vec2 size, out vec4 seed) {
	float first = firstCell(along, cw);
	if (cell < first || cell >= first + float(SLOTS)) {
		return standing(lane, cell, cw, z, x, zj, size, seed);
	}
	int slot = int(mod(cell, float(SLOTS)));
	vec4 a = texelFetch(iChannel1, ivec2(2 * slot, lane), 0);
	seed = texelFetch(iChannel1, ivec2(2 * slot + 1, lane), 0);
	x = a.x;
	zj = a.y;
	float kind = floor(a.w / 16.0);
	size = vec2(a.z, a.w - 16.0 * kind);
	return int(kind + 0.5);
}

// How far a cut-out can reach: across from its middle, and up.
vec2 extent(int kind, vec2 size) {
	if (kind == SPINIFEX_CLUMP) return vec2(1.25 * size.x, 1.4 * size.y);
	if (kind == MULGA_TREE || kind == GHOST_GUM || kind == BUSH_CLUMP) return vec2(TREE_SPAN * size.x, size.y);
	return vec2(1.25 * size.x, size.y);
}

bool isPlant(int kind) {
	return kind != TERMITES && kind != BOULDER;
}

// The spans of a cut-out of that kind, at height y, over a0 to a1.
void cutOut(inout vec4 acc, int kind, float a0, float a1, float y, vec2 size, vec4 seed) {
	if (kind == SPINIFEX_CLUMP) spinifex(acc, a0, a1, y, size.x, size.y, seed, false);
	else if (kind == TUFT) spinifex(acc, a0, a1, y, size.x, size.y, seed, true);
	else if (kind == WEED) buffel(acc, a0, a1, y, size.x, size.y, seed);
	else if (kind == MULGA_TREE || kind == GHOST_GUM || kind == BUSH_CLUMP) tree(acc, a0, a1, y, size, seed, atlasTile(kind, seed), kind == BUSH_CLUMP ? 0.45 : 0.0);
	else if (kind == TERMITES) termites(acc, a0, a1, y, size.x, size.y, seed);
	else if (kind == BOULDER) boulder(acc, a0, a1, y, size.x, size.y, seed);
}

// Leaves, grass and grain over a cut-out: brightness and gaps, from noise
// at the pixel's stretch of road (lx, w wide) and height y, quietened as
// the blur averages it.
vec2 texture2(int kind, float lx, float w, float y, vec4 seed) {
	if (kind == MULGA_TREE || kind == GHOST_GUM || kind == BUSH_CLUMP) {
		// (The atlas has its own.)
		return vec2(1.0);
	}
	float f = kind == BUSH_CLUMP ? 12.0 : kind == MULGA_TREE || kind == GHOST_GUM ? 5.0 : kind == BOULDER ? 9.0 : kind == TERMITES ? 7.0 : 18.0;
	float n = noise(vec2(lx * f, y * f + seed.x * 30.0)) * 2.0 - 1.0;
	n += 0.5 * (noise(vec2(lx * f * 2.7, y * f * 2.7 + seed.y * 30.0)) * 2.0 - 1.0);
	n /= max(1.0, f * w);
	bool leafy = kind == BUSH_CLUMP;
	return vec2(1.0 + 0.45 * n, leafy ? clamp(1.0 + 0.8 * n, 0.0, 1.0) : 1.0);
}

// ---- The road's bend. The car's on a bend of radius `bend` (+ left, huge
// when straight), `along` metres along the road. Where a ray (d: its x and
// z) meets the line z out from the road, bent with it: how far along the
// ray (0 or less if it doesn't) and along the road.
// (The car's pulled gPull out: it's on a bend of radius bend - gPull.)
vec2 meet(vec2 d, float z, float bend, float along) {
	float B = bendAt(bend, z);
	float bc = B - gPull, zc = z - gPull;
	float b = d.y * bc, c = zc * (2.0 * bc - zc);
	float disc = b * b - dot(d, d) * c;
	if (disc < 0.0) {
		return vec2(-1.0);
	}
	float sg = B >= 0.0 ? 1.0 : -1.0;
	float t = c / (b + sg * sqrt(disc));
	vec2 p = t * d;
	return vec2(t, along + abs(B) * atan(p.x, sg * (bc - p.y)));
}

// Where on the road (along, out) the ground point p (x along, z out, from
// the car) is.
vec2 onRoad(vec2 p, float bend, float along) {
	float B = bendAt(bend, length(p) + gPull);
	float bc = B - gPull;
	float sg = B >= 0.0 ? 1.0 : -1.0;
	float dist = length(vec2(p.x, p.y - bc));
	return vec2(along + abs(B) * atan(p.x, sg * (bc - p.y)), gPull + (2.0 * bc * p.y - dot(p, p)) / (bc + sg * dist));
}

// How much of the key light reaches the ground at x, z (over wx along the
// road), and how much the sky's does: the cut-outs cast their spans'
// shadows (things up to 8 m tall, from the rows that far towards the
// light), and darken the ground just round their feet.
vec2 sunlight(float x, float z, float wx, float along) {
	vec3 l = gLight;
	float shade = 0.0, dark = 0.0;
	if (l.y > 0.02) {
		float lz = abs(l.z) < 0.15 ? (l.z < 0.0 ? -0.15 : 0.15) : l.z;
		float reach = clamp(8.0 * lz / l.y, -25.0, 25.0);
		int first = max(laneOf(min(z, z + reach)) - 1, 0);
		int last = min(min(laneOf(max(z, z + reach)) + 1, LANES - 1), first + 6);
		for (int lane = first; lane <= last; lane++) {
			float zl = laneZ(lane);
			float cw = cellWidth(zl);
			float c0 = floor((x + (zl - z) * l.x / lz) / cw);
			for (int i = -1; i <= 1; i++) {
				float ox, zj;
				vec2 size;
				vec4 seed;
				int kind = stood(lane, c0 + float(i), cw, zl, along, ox, zj, size, seed);
				if (kind == NONE) {
					continue;
				}
				// The height whose shadow falls here (things have some
				// depth: their shadows start under them).
				float y = (zj + 0.4 * size.x * sign(lz) - z) * l.y / lz;
				float xs = x + y * l.x / l.y - ox;
				vec2 e = extent(kind, size);
				if (y < 0.0 || y > e.y || abs(xs) > e.x + 0.5 * wx) {
					continue;
				}
				// Softer the higher what casts it (the sky's light gets
				// round).
				float ws = wx + SHADOW_SOFT * y;
				vec4 acc = vec4(0.0);
				cutOut(acc, kind, xs - 0.5 * ws, xs + 0.5 * ws, y, size, seed);
				shade = max(shade, min(acc.w / ws, 1.0));
			}
		}
	}
	// Darker round the feet of things here.
	int here = laneOf(z);
	for (int lane = max(here - 1, 0); lane <= min(here + 1, LANES - 1); lane++) {
		float zl = laneZ(lane);
		float cw = cellWidth(zl);
		float c0 = floor(x / cw);
		for (int i = -1; i <= 1; i++) {
			float ox, zj;
			vec2 size;
			vec4 seed;
			int kind = stood(lane, c0 + float(i), cw, zl, along, ox, zj, size, seed);
			if (kind == NONE) {
				continue;
			}
			float r = size.x * (kind == MULGA_TREE || kind == GHOST_GUM ? 0.5 : 1.1);
			// (Moved away from the key light, more as it's lower.)
			vec2 away = l.y > 0.02 ? -vec2(l.x, l.z) / max(l.y, 0.3) * 0.3 * r : vec2(0.0);
			away *= min(1.0, 0.6 * r / max(length(away), 1e-4));
			vec2 q = vec2(max(abs(x - ox - away.x) - 0.5 * wx, 0.0) / r, (z - zj - away.y) / (0.8 * r + 0.1));
			dark = max(dark, (1.0 - smoothstep(0.0, 1.3, dot(q, q))) * (kind == TUFT ? 0.3 : 0.55));
		}
	}
	return vec2(1.0 - SHADOW * shade, 1.0 - dark);
}

// ---- The far ranges: a height field (p along and out, in the far
// hills' frame), mesas, ridges and low hills, none nearer than 2.5 km.
float terrain(vec2 p, bool fine) {
	// Fewer nearer, none nearer than about 4 km (they'd loom).
	float away = smoothstep(3500.0, 20000.0, p.y);
	float m = noise(p / 3500.0) * 0.65 + noise(p / 1400.0 + 3.1) * 0.35 - 0.35 * (1.0 - away);
	// Mesas: flat tops, cliffs, a step part way down.
	float h = 280.0 * smoothstep(0.55, 0.575, m) + 60.0 * smoothstep(0.47, 0.49, m);
	// Ridges.
	float r = 1.0 - abs(noise(p / 2600.0 + 7.7) * 2.0 - 1.0);
	h = max(h, 520.0 * r * r * r * smoothstep(0.45, 0.75, noise(p / 9000.0 + 1.3) - 0.4 * (1.0 - away)));
	// Gullies cut into the high ground; a gently rolling plain.
	h += 15.0 * noise(p / 700.0) * smoothstep(2500.0, 4000.0, p.y);
	if (fine) {
		float g = 1.0 - abs(noise(p / 260.0 + 2.2) * 2.0 - 1.0);
		h -= 22.0 * g * g * smoothstep(20.0, 120.0, h);
		float g2 = 1.0 - abs(noise(p / 70.0 + 5.5) * 2.0 - 1.0);
		h -= 10.0 * g2 * g2 * smoothstep(10.0, 60.0, h);
	}
	return h - 8.0;
}

// Haze: the dust's, warm, nearer (but never all of it), then the air's.
vec3 haze(vec3 col, float dist, vec3 hazeCol) {
	col = mix(col, hazeCol * vec3(1.1, 0.92, 0.78), DUSTY * (1.0 - exp2(-dist / DUST_HAZE)));
	return mix(col, hazeCol, 1.0 - exp2(-dist / HAZE));
}

// The ray (rd, in the far hills' frame) over the ranges, from 2.5 km out to
// 60 km: their colour, lit and hazed, and how much of the pixel they cover
// (from how many pixels the nearest skyline's above or below the ray).
vec4 ranges(vec3 rd, float eye, float along, float pa, float turn, vec3 hazeCol) {
	float flat_ = length(rd.xz);
	vec2 d = rd.xz / flat_;
	float rise = rd.y / flat_;
	if (rise > 0.09) {
		return vec4(0.0);
	}
	vec2 o = vec2(along, 0.0);
	float t = 2500.0, last = t;
	bool hit = false;
	// The most the ranges stand above the ray, in pixels, and where.
	float above = -1e9, top = t;
	for (int i = 0; i < 16; i++) {
		// (The earth's curve drops it away.)
		float y = eye + rise * t - t * t / 12.7e6;
		float ht = terrain(o + d * t, false);
		if ((ht - y) / (t * pa) > above) {
			above = (ht - y) / (t * pa);
			top = t;
		}
		if (y < ht) {
			hit = true;
			break;
		}
		if (y > 580.0) {
			break;
		}
		last = t;
		t *= 1.22;
	}
	if (!hit) {
		// Just over a skyline: lit there, partly covering.
		if (above < -0.5) {
			return vec4(0.0);
		}
		last = t = top;
	} else {
		// Just under one: how far the skyline beyond rises over the ray.
		for (int i = 1; i < 3; i++) {
			float tb = t * (1.0 + 0.15 * float(i));
			above = max(above, (terrain(o + d * tb, false) - eye - rise * tb + tb * tb / 12.7e6) / (tb * pa));
		}
	}
	float cover = clamp(0.5 + above, 0.0, 1.0);
	for (int i = 0; i < (hit ? 6 : 0); i++) {
		float mid = 0.5 * (last + t);
		if (eye + rise * mid - mid * mid / 12.7e6 < terrain(o + d * mid, true)) {
			t = mid;
		} else {
			last = mid;
		}
	}
	vec2 p = o + d * t;
	float h = terrain(p, true);
	float e = max(t * pa * 2.0, 15.0);
	vec3 n = normalize(vec3(h - terrain(p + vec2(e, 0.0), true), e, h - terrain(p + vec2(0.0, e), true)));
	float steep = 1.0 - n.y;
	// Red rock on the steep faces, gullies and strata in it; spinifex and
	// dirt on the flats; dark ironstone caps.
	float gully = noise(vec2(p.x / 25.0, h / 300.0));
	float band = noise(vec2(p.x / 900.0, h / 14.0));
	vec3 rock = RANGE * (0.75 + 0.35 * gully) * mix(vec3(0.8, 0.75, 0.75), vec3(1.1, 1.05, 0.95), band);
	vec3 plain = mix(DIRT, SPINIFEX_CORE, 0.45) * 0.85;
	vec3 col = mix(plain, rock, smoothstep(0.08, 0.3, steep));
	col = mix(col, vec3(0.38, 0.2, 0.13), 0.5 * smoothstep(0.1, 0.0, steep) * smoothstep(150.0, 250.0, h));
	vec3 l = rotY(gLight, turn);
	col *= gAmbient + gLightCol * max(dot(n, l), 0.0);
	return vec4(haze(col, 0.5 * t, hazeCol), cover);
}

// ---- The sky.
// Turns v about the unit axis a by angle.
vec3 turnAbout(vec3 v, vec3 a, float angle) {
	float c = cos(angle), s = sin(angle);
	return v * c + cross(a, v) * s + a * dot(a, v) * (1.0 - c);
}

// Stars and the Milky Way, turning with the sky about the south celestial
// pole once a day.
vec3 starfield(vec3 rd, float day, float turn, float pa) {
	vec3 q = turnAbout(rotY(rd, turn * CELESTIAL), normalize(vec3(0.3, 0.42, -1.0)), -day * TAU);
	float lat = asin(clamp(q.y, -1.0, 1.0)), lon = atan(q.z, q.x);
	float cells = 160.0;
	vec2 sp = vec2(lon * cos(lat), lat) * cells / TAU;
	vec2 cell = floor(sp);
	vec4 h = hash42(cell + 3.7);
	float dist = length(sp - cell - 0.25 - 0.5 * h.xy) * TAU / cells;
	// A handful bright; the rest faint, a few of them less so.
	float s = (h.z - 0.55) / 0.45;
	float bright = h.z < 0.55 ? 0.0 : (h.z > 0.985 ? 2.0 * pow(s, 8.0) : 0.006 + 0.12 * pow(s, 12.0)) * (0.75 + 0.25 * sin(iTime * (3.0 + 4.0 * h.w) + h.w * TAU));
	vec3 col = mix(vec3(1.0, 0.85, 0.7), vec3(0.8, 0.9, 1.0), h.w) * bright * smoothstep(1.3 * pa, 0.3 * pa, dist);
	// The Milky Way: a band, mottled, with dark lanes.
	float band = dot(q, normalize(vec3(0.2, 0.6, 0.75)));
	float glow = exp(-band * band / 0.03) * (0.4 + 0.8 * noise(vec2(lon, lat) * 9.0));
	glow *= 1.0 - 0.7 * smoothstep(0.55, 0.75, noise(vec2(lon, lat) * 5.0 + 4.0)) * exp(-band * band / 0.004);
	return col + vec3(0.75, 0.78, 0.9) * 0.035 * glow;
}

// The sky looking along rd (unit, the car's frame): by the sun's height,
// with a sunset's glow round it; with details, the sun and moon discs and
// the stars.
vec3 sky(vec3 rd, bool details, float day, float turn, float pa) {
	float e = gSun.y;
	vec3 zen = mix(vec3(0.004, 0.007, 0.018), vec3(0.16, 0.36, 0.74), gDay);
	vec3 hor = mix(vec3(0.012, 0.018, 0.035), vec3(0.70, 0.79, 0.88), gDay);
	float dusk = smoothstep(0.35, 0.02, e) * smoothstep(-0.25, -0.02, e);
	float facing = pow(0.5 + 0.5 * dot(normalize(rd.xz + 1e-5), normalize(gSun.xz + 1e-5)), 3.0);
	float fade = mix(0.25, 1.0, smoothstep(-0.18, 0.0, e));
	hor = mix(hor, mix(vec3(0.45, 0.35, 0.50), vec3(1.0, 0.52, 0.25), facing) * fade, dusk);
	zen = mix(zen, vec3(0.16, 0.24, 0.48) * fade, 0.6 * dusk);
	vec3 col = mix(hor, zen, pow(clamp(rd.y * 2.5, 0.0, 1.0), 0.6));
	float c = dot(rd, gSun);
	col += gSunCol * (0.18 * pow(max(c, 0.0), 6.0) + 0.5 * pow(max(c, 0.0), 80.0));
	if (details) {
		float r = radians(0.9);
		// The sun.
		float disc = smoothstep(cos(r + pa), cos(r - pa), c);
		col += gSunCol * 14.0 * disc;
		// The moon, full, with its seas.
		float m = dot(rd, gMoon);
		float moon = smoothstep(cos(r + pa), cos(r - pa), m);
		if (moon > 0.0) {
			vec3 tx = normalize(cross(gMoon, vec3(0.0, 1.0, 0.0)));
			vec3 ty = cross(tx, gMoon);
			vec2 uv = vec2(dot(rd, tx), dot(rd, ty)) / r;
			float seas = 0.72 + 0.28 * smoothstep(0.35, 0.6, noise(uv * 2.5 + 7.0));
			vec3 lit = vec3(0.95, 0.93, 0.86) * seas * mix(3.0, 0.5, gDay) * smoothstep(-0.02, 0.02, gMoon.y);
			col = mix(col, max(col, lit), moon);
		}
		float night = 1.0 - smoothstep(-0.25, -0.05, e);
		if (night > 0.0) {
			col += night * starfield(rd, day, turn, pa) * smoothstep(-0.02, 0.05, rd.y);
		}
	}
	return col;
}

// The light for the time of day (see sunMoon()).
void setLight(float day, float turn) {
	sunMoon(day, turn, gSun, gMoon);
	float e = gSun.y;
	gDay = smoothstep(-0.12, 0.2, e);
	gSunCol = mix(vec3(1.0, 0.42, 0.15), vec3(1.0, 0.95, 0.88), smoothstep(0.0, 0.4, e)) * smoothstep(-0.035, 0.03, e);
	if (e > -0.03 || gMoon.y < 0.0) {
		gLight = gSun;
		gLightCol = 0.8 * gSunCol;
	} else {
		gLight = gMoon;
		gLightCol = vec3(0.55, 0.62, 0.80) * 0.07 * smoothstep(-0.03, 0.1, gMoon.y);
	}
	// The sky's light, and the light bounced off the red ground.
	gAmbient = 0.2 * (sky(vec3(0.0, 1.0, 0.0), false, day, turn, 1.0) + sky(normalize(vec3(0.0, 0.15, 1.0)), false, day, turn, 1.0))
			+ 0.3 * DIRT * gLightCol * max(gLight.y, 0.0)
			// (and the night's airglow and starlight)
			+ vec3(0.008, 0.010, 0.016);
	gLeft = gAmbient + 1.1 * gLightCol * max(dot(normalize(vec3(-0.6, 0.7, -0.4)), gLight), 0.0);
	gRight = gAmbient + 1.1 * gLightCol * max(dot(normalize(vec3(0.6, 0.7, -0.4)), gLight), 0.0);
	gGlow = gLightCol * 0.7 * max(gLight.z, 0.0) * smoothstep(0.8, 0.05, gLight.y);
	gExposure = 1.0 / mix(0.09, 1.0, smoothstep(-0.2, 0.1, e));
}

// Into front-to-back colour: what's still showing through takes this.
void over(inout vec4 acc, vec3 col, float a) {
	acc.rgb += (1.0 - acc.a) * a * col;
	acc.a += (1.0 - acc.a) * a;
}

// Power poles, if along here: the pole, then the three wires sagging
// between them.
bool poled(float x) {
	return hash12(vec2(floor(x / 1500.0), 3.3)) < POLES;
}
void drawPoles(inout vec4 acc, vec3 rd, float eye, float bend, float along, float pa, float blur, float spin, vec3 hazeCol) {
	vec2 m = meet(rd.xz, POLE_Z, bend, along);
	if (m.x <= 0.0) {
		return;
	}
	float near = floor(m.y / POLE_GAP + 0.5) * POLE_GAP;
	float y = eye + rd.y * m.x;
	if (poled(near) && y > 0.0 && y < 9.5) {
		float lx = m.y - near, w = max(blur, m.x * pa) + m.x * spin;
		vec4 s = vec4(0.0);
		pole(s, lx - 0.5 * w, lx + 0.5 * w, y, rd.x / rd.z);
		if (s.w > 0.0) {
			over(acc, haze(s.rgb / s.w, m.x, hazeCol), min(s.w / w, 1.0));
		}
	}
	for (int j = 0; j < 3; j++) {
		vec2 mw = meet(rd.xz, POLE_Z + 1.1 * float(j - 1), bend, along);
		// (Only between two poles.)
		float last = floor(mw.y / POLE_GAP) * POLE_GAP;
		if (mw.x <= 0.0 || !poled(last) || !poled(last + POLE_GAP)) {
			continue;
		}
		// (Highest at the poles, sagging between.)
		float u = fract(mw.y / POLE_GAP);
		float hw = (j == 1 ? 9.42 : 8.55) - 4.4 * u * (1.0 - u);
		float pv = mw.x * pa;
		float a = min(0.03 / pv, 1.0) * max(0.0, 1.0 - abs(eye + rd.y * mw.x - hw) / pv);
		over(acc, haze(vec3(0.12) * (gAmbient + 0.5 * gLightCol), mw.x, hazeCol), a);
	}
}

// A glint's flare, o from it (in screen sizes): a hot core, a halo, and
// three spikes across.
float flare(vec2 o) {
	float r = length(o);
	float f = 8.0 * exp(-r * r / 1.2e-5) + 0.3 * exp(-r / 0.006) + 0.02 / (1.0 + r * r / 4e-4);
	for (int i = 0; i < 3; i++) {
		float a = 0.35 + float(i) * PI / 3.0;
		vec2 u = vec2(cos(a), sin(a));
		f += 0.7 * exp(-abs(dot(o, vec2(-u.y, u.x))) / 1.5e-3) * exp(-abs(dot(o, u)) / 0.05);
	}
	return f;
}

// What's seen at fragCoord.
vec3 render(vec2 fragCoord, vec2 res, vec4 clock, vec4 bounce, float roll, vec4 glint) {
	float along = clock.x, day = clock.y, v = clock.w;
	float spread = tan(radians(FOV) * 0.5);
	float side = sqrt(res.x * res.y);
	vec2 q = (fragCoord - 0.5 * res) / (0.5 * side) * spread;
	// The car's roll turns the view.
	q = mat2(cos(roll), sin(roll), -sin(roll), cos(roll)) * q;
	// A pixel's angle, roughly.
	float pa = 2.0 * spread / side;
	float t = mod(iTime, 1000.0);
	// The road's buzz, at speed, and the suspension's bounce and pitch.
	float buzz = SHAKE * min(abs(v) / 30.0, 1.0);
	float eye = EYE + bounce.x + buzz * 0.003 * (noise(vec2(t * 9.0, 0.0)) - 0.5);
	float pitch = atan((1.0 - 2.0 * HORIZON) * res.y / side * spread) + bounce.z
			+ buzz * radians(0.15) * (noise(vec2(t * 7.0, 4.0)) - 0.5);
	// The ray: x along the road, y up, z out (not normalised: z near 1).
	vec3 rd = vec3(q.x, q.y * cos(pitch) - sin(pitch), q.y * sin(pitch) + cos(pitch));
	vec2 hd = heading(along);
	float bend = 1.0 / (abs(hd.y) < 1e-6 ? 1e-6 : hd.y);
	// Blur along the road, and from turning (radians).
	float blur = abs(v) * SHUTTER;
	float spin = abs(v * hd.y) * SHUTTER;
	setLight(day, hd.x);
	vec3 hazeCol = sky(normalize(vec3(rd.x, 0.03 * length(rd.xz), rd.z)), false, day, hd.x, pa);
	vec2 d = rd.xz;

	vec4 acc = vec4(0.0);
	// The guide posts (out round a turning lane, none by a side road).
	{
		vec2 m = meet(d, POST_Z, bend, along);
		float near = floor(m.y / POST_GAP + 0.5) * POST_GAP;
		vec2 turn = turning(near);
		if (turn.y > 0.0) {
			m = meet(d, POST_Z + turn.y, bend, along);
		}
		float y = eye + rd.y * m.x;
		if (m.x > 0.0 && y > 0.0 && y < 1.0 && abs(near - turn.x) > 15.0 && abs(near - crossing(near)) > 15.0) {
			float lx = m.y - near, w = max(blur, m.x * pa) + m.x * spin;
			vec4 s = vec4(0.0);
			post(s, lx - 0.5 * w, lx + 0.5 * w, y);
			if (s.w > 0.0) {
				over(acc, s.rgb / s.w, min(s.w / w, 1.0));
			}
		}
	}
	// The stop sign at a crossroads, just before it.
	{
		vec2 m = meet(d, POST_Z + 2.5, bend, along);
		float sx = crossing(m.y) - 8.0;
		float y = eye + rd.y * m.x;
		if (m.x > 0.0 && y > 0.0 && y < 2.4 && abs(m.y - sx) < 3.0) {
			float lx = m.y - sx, w = max(blur, m.x * pa) + m.x * spin;
			vec4 s = vec4(0.0);
			stopSign(s, lx - 0.5 * w, lx + 0.5 * w, y);
			if (s.w > 0.0) {
				over(acc, s.rgb / s.w, min(s.w / w, 1.0));
			}
		}
	}
	// The rows of plants and rocks, near to far (and the power poles in
	// among them).
	bool poles = false;
	for (int lane = 0; lane < LANES; lane++) {
		float z = laneZ(lane);
		if (!poles && z > POLE_Z - 1.5) {
			poles = true;
			drawPoles(acc, rd, eye, bend, along, pa, blur, spin, hazeCol);
		}
		vec2 m = meet(d, z, bend, along);
		if (m.x <= 0.0) {
			continue;
		}
		float yl = eye + rd.y * m.x;
		// Below the ground here (and so beyond), or above the tallest.
		if (yl < -0.3 * z * (LANE_RATIO - 1.0) || (rd.y > 0.0 && yl > 12.0) || acc.a > 0.995) {
			break;
		}
		float cw = cellWidth(z);
		float f = m.y / cw;
		float c0 = floor(f);
		f -= c0;
		float w = max(blur, m.x * pa) + m.x * spin;
		// Things reach at most 0.17 of a cell into the next.
		float reach = 0.37 + 0.5 * w / cw;
		for (int i = -1; i <= 1; i++) {
			if ((i == -1 && f > reach) || (i == 1 && f < 1.0 - reach)) {
				continue;
			}
			float ox, zj;
			vec2 size;
			vec4 seed;
			int kind = stood(lane, c0 + float(i), cw, z, along, ox, zj, size, seed);
			if (kind == NONE) {
				continue;
			}
			float kj = m.x * zj / z;
			float y = eye + rd.y * kj;
			float lx = along + (m.y - along) * zj / z - ox;
			vec2 e = extent(kind, size);
			// A pixel's height there: small things far off are averaged over
			// it (two heights), or they flicker as the view bobs.
			float dy = kj * pa;
			int n = e.y < 12.0 * dy ? 2 : 1;
			if (y < -0.5 * dy || y > e.y + 0.5 * dy || abs(lx) > e.x + 0.5 * w) {
				continue;
			}
			gRegion = region(ox, zj);
			vec4 s = vec4(0.0);
			for (int k = 0; k < n; k++) {
				float yk = n == 1 ? y : y + (float(k) - 0.5) * 0.5 * dy;
				if (yk >= 0.0) {
					cutOut(s, kind, lx - 0.5 * w, lx + 0.5 * w, yk, size, seed);
				}
			}
			s /= float(n);
			if (s.w > 0.0) {
				vec2 tex = texture2(kind, lx, max(w, dy), y, seed);
				vec3 col = s.rgb / s.w * tex.x;
				if (isPlant(kind)) {
					col += col * gGlow / max(dot(gLeft + gRight, vec3(0.1667)), 0.02);
				}
				over(acc, haze(col, kj, hazeCol), min(s.w / w, 1.0) * tex.y);
			}
		}
	}

	// The ground, or else the ranges and the sky.
	vec3 back;
	float tg = rd.y < -1e-5 ? eye / -rd.y : 1e9;
	if (tg * length(d) < 2600.0) {
		vec2 r = onRoad(tg * d, bend, along);
		float wx = max(blur, tg * pa) + tg * spin;
		float wz = eye * pa / (rd.y * rd.y);
		vec2 lit = r.y < 200.0 ? sunlight(r.x, r.y, wx, along) : vec2(1.0);
		back = ground(r.x, r.y, wx, wz) * (gAmbient * lit.y + gLightCol * max(gLight.y, 0.0) * lit.x * mix(1.0, lit.y, 0.6));
		back = haze(back, tg, hazeCol);
	} else {
		vec4 far = ranges(rotY(rd, hd.x), eye, along, pa, hd.x, hazeCol);
		back = far.w < 1.0 ? sky(normalize(rd), true, day, hd.x, pa) : vec3(0.0);
		back = mix(back, far.rgb, far.w);
	}
	over(acc, back, 1.0);
	// A glint: where the shard is on the screen, and its flare.
	if (glint.z > 0.003) {
		vec2 p = fromRoad(glint.xy, bend, along, gPull);
		vec3 g = vec3(p.x, -eye, p.y);
		g = vec3(g.x, g.y * cos(pitch) + g.z * sin(pitch), g.z * cos(pitch) - g.y * sin(pitch));
		if (g.z > 0.0) {
			vec2 at = mat2(cos(roll), -sin(roll), sin(roll), cos(roll)) * (g.xy / g.z) / spread * 0.5 * side + 0.5 * res;
			// (Dimmer by moonlight: the night's exposure lifts them.)
			acc.rgb += gLightCol * GLINT_POWER * mix(GLINT_NIGHT, 1.0, gDay) * glint.z * flare((fragCoord - at) / side);
		}
	}
	// Brightened at night, and bright lights eased in.
	vec3 col = acc.rgb * gExposure;
	vec3 k = max(col - 0.8, 0.0);
	return col - k + 0.2 * (1.0 - exp(-k / 0.2));
}

// While the atlas is made: a progress bar (how far, 0-1), white on black,
// filling a hollow box.
float progressBar(vec2 fragCoord, vec2 res, float done) {
	float u = min(res.x, res.y) / 100.0;
	vec2 o = abs(fragCoord - 0.5 * res);
	vec2 box = vec2(32.0, 3.0) * u;
	float line = max(1.0, 0.5 * u), gap = max(1.0, 0.8 * u);
	// The box's outline.
	if (o.x < box.x && o.y < box.y && (o.x > box.x - line || o.y > box.y - line)) {
		return 1.0;
	}
	// The bar inside it, growing from the left.
	vec2 in_ = box - line - gap;
	float x = fragCoord.x - (0.5 * res.x - in_.x);
	return o.y < in_.y && x >= 0.0 ? clamp(2.0 * in_.x * done - x, 0.0, 1.0) : 0.0;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 res = iResolution.xy;
	vec4 clock = texelFetch(iChannel0, ivec2(S_CLOCK, 0), 0);
	vec4 bounce = texelFetch(iChannel0, ivec2(S_BOUNCE, 0), 0);
	vec4 roll = texelFetch(iChannel0, ivec2(S_ROLL, 0), 0);
	gPull = texelFetch(iChannel0, ivec2(S_STOP, 0), 0).z;
	gTiles = atlasLayout(res);
	gAtlasRes = res;
	vec4 mark = texelFetch(iChannel2, ivec2(0, int(res.y) - 1), 0);
	gAtlas = mark.x == ATLAS_MARK && mark.y == res.x && mark.z == res.y && mark.w >= atlasDone(gTiles.x);
	if (!gAtlas) {
		float done = mark.x == ATLAS_MARK && mark.y == res.x && mark.z == res.y ? mark.w / atlasDone(gTiles.x) : 0.0;
		fragColor = vec4(vec3(progressBar(fragCoord, res, done)), 1.0);
		return;
	}
	vec4 glint = texelFetch(iChannel0, ivec2(S_GLINT, 0), 0);
	if (abs(roll.w - MARK) > 0.01) {
		// Not started yet.
		clock = vec4(START_ALONG, START_TIME, 1.0, SPEED / 3.6);
		bounce = vec4(0.0);
		roll = vec4(0.0);
		gPull = 0.0;
		glint = vec4(0.0);
	}
	fragColor = vec4(render(fragCoord, res, clock, bounce, roll.x, glint), 1.0);
}

//// Common
uniform vec3 pointers[10];
uniform int pointerCount;

#define PI 3.14159265359
#define TAU 6.28318530718

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

// The day: how long it lasts, in seconds; when it starts (0 midnight, 0.25
// sunrise, 0.5 noon, 0.75 sunset).
#define DAY_LENGTH 480.0
#define START_TIME 0.56
// Where along the road it starts, in metres.
#define START_ALONG 0.0
// Three fingers: how long time takes to stop (and start again), in
// seconds; how much time a drag across the screen scrubs through.
#define EASE 3.0
// ...down to this much of the time (and speed): always a little blur.
#define MIN_RATE 0.15
#define SCRUB 60.0

// The road: a stretch every ROAD_CELL metres, turning at its start by up to
// MAX_TURN radians (from where the last one headed), through a bend of
// radius MIN_RADIUS or more - so long straights and bends.
#define ROAD_CELL 3000.0
#define MAX_TURN 0.6
#define MIN_RADIUS 450.0
// Sharp corners: now and then (a chance each other stretch) the bend's a
// corner of radius SHARP_RADIUS or a bit more, turning 50-85 degrees. The
// car takes bends at up to CORNERING m/s^2 sideways, braking at BRAKING and
// speeding up at PICKUP (m/s^2).
#define SHARP 0.35
#define SHARP_RADIUS 70.0
#define CORNERING 3.5
#define BRAKING 2.5
#define PICKUP 1.5
// Crossroads, with a stop sign (a rolling stop): a chance each stretch.
#define STOPS 0.2
// How far over the car pulls (onto the gravel) when three fingers stop it.
#define PULL_Z 2.3
// How far it drifts in and out in its lane, in metres.
#define DRIFT 0.15
// Glints off glass or quartz in the dirt: the chance of a shard in an empty
// cell, how near its face must be to the mirror angle (radians), how bright.
#define GLINTS 0.13
#define GLINT_SPREAD 0.035
#define GLINT_POWER 3.0
// How bright, by moonlight, of that.
#define GLINT_NIGHT 0.35
// The suspension: how bouncy (Hz), how damped, how much it pitches under
// braking (radians per m/s^2) and rolls in bends.
#define BOUNCE_HZ 1.4
#define DAMPING 0.35
#define DIVE 0.003
#define LEAN 0.006

// The road out from the window, in metres: the edge line, the end of the
// bitumen, the end of the gravel (it wanders by GRAVEL_WANDER).
#define LINE_Z 1.35
#define LINE_W 0.13
#define SEAL_Z 2.2
#define GRAVEL_Z 3.4
#define GRAVEL_WANDER 1.6
// Turning lanes: how wide, how often (a chance each 2.5 km).
#define TURN_W 3.3
#define TURNS 0.4
// Potholes: how many on the rough stretches.
#define POTHOLES 0.3
// Guide posts: how far out, how far apart.
#define POST_Z 3.9
#define POST_GAP 45.0
// Power poles: how far out, how far apart, how much of the way they're
// there.
#define POLE_Z 45.0
#define POLE_GAP 85.0
// How much shadows take of the key light, and how they soften with the
// height of what casts them (metres wider a metre up).
#define SHADOW 0.6
#define SHADOW_SOFT 0.12
#define POLES 0.1
// The rows of plants and rocks: the first's distance, how many, each this
// much further out than the last.
#define NEAR_Z 4.4
#define LANES 40
#define LANE_RATIO 1.16
// How thick the bush is (0-1).
#define BUSH 0.6
// How far until haze takes half, in metres; the dust's, nearer, and how
// much it takes at most.
#define HAZE 12000.0
#define DUST_HAZE 400.0
#define DUSTY 0.3

// Colours (what they look like in the midday sun).
#define BITUMEN vec3(0.16, 0.155, 0.15)
#define CHIPS vec3(0.38, 0.35, 0.33)
#define PAINT vec3(0.86, 0.86, 0.82)
#define GRAVEL vec3(0.56, 0.33, 0.21)
#define DIRT vec3(0.68, 0.29, 0.13)
#define DUST vec3(0.80, 0.50, 0.32)
#define TRACK vec3(0.76, 0.42, 0.25)
#define SPINIFEX vec3(0.86, 0.72, 0.38)
#define SPINIFEX_DARK vec3(0.36, 0.38, 0.27)
#define SPINIFEX_CORE vec3(0.40, 0.42, 0.27)
#define SALTBUSH vec3(0.55, 0.58, 0.48)
#define BUFFEL vec3(0.60, 0.62, 0.48)
#define MULGA vec3(0.34, 0.38, 0.23)
#define GUM_LEAVES vec3(0.44, 0.54, 0.26)
#define GUM_BARK vec3(0.93, 0.91, 0.86)
#define BARK vec3(0.27, 0.22, 0.19)

// ---- The tree atlas (Buffer C): TREE_COLS x TREE_ROWS tiles, each a tree
// or bush TREE_SPAN of its radius either side of its trunk and its height
// tall: MULGAS mulgas, then OAKS desert oaks, DEAD dead trees, GUMS ghost
// gums and BUSHES bushes.
#define TREE_COLS 6
#define TREE_ROWS 4
#define MULGAS 6
#define OAKS 4
#define DEAD 3
#define GUMS 5
#define TREES (MULGAS + OAKS + DEAD + GUMS)
// Bushes after the trees: saltbushes, darker-leaved ones, then bare, sticky
// ones (BUSHES - 4, 2, 2).
#define BUSHES 6
#define TREE_SPAN 1.6
// Atlas trees' height over radius (each's stretched to its own).
#define MULGA_TALL 2.3
#define GUM_TALL 2.9
#define BUSH_TALL 1.3
// How far branches wander, at the tips (in radii), less nearer the root.
#define WOBBLE 0.45
// Leaves across a tree's radius, about.
#define LEAF_FREQ 14.0
// What the atlas's progress texel starts with (a new value redraws it).
#define ATLAS_MARK 7.5
// The atlas's biggest tile, in texels.
#define TILE_MAX ivec2(96, 200)

// The atlas in a buffer res big: a tile's width and height, and a band's
// height (there are two, the same tiles in each, below the progress row).
// No bigger than TILE_MAX: a screen-sized one (70 MB at the phone's full
// size) was slow to read.
ivec3 atlasLayout(vec2 res) {
	ivec2 tile = min(ivec2(int(res.x) / TREE_COLS, (int(res.y) - 1) / (2 * TREE_ROWS)), TILE_MAX);
	return ivec3(tile, tile.y * TREE_ROWS);
}

// The atlas's step once it's done: a tile drawn each step (so the progress
// bar moves, and no one frame's slow), summed in steps up to the log2 of a
// tile's width, then copied to the other target.
#define ATLAS_DRAWS float(TREE_COLS * TREE_ROWS)
float atlasDone(int tileW) {
	return ATLAS_DRAWS + ceil(log2(float(max(tileW, 2)))) + 1.0;
}
#define POLE_WOOD vec3(0.42, 0.38, 0.33)
#define MOUND vec3(0.66, 0.32, 0.17)
#define ROCK vec3(0.50, 0.20, 0.11)
#define RANGE vec3(0.72, 0.33, 0.18)

// Buffer A's texels.
#define S_CLOCK 0
#define S_TOUCH 1
#define S_BOUNCE 2
#define S_ROLL 3
#define S_STOP 4
#define S_GLINT 5
#define MARK 0.777
// How far along the road wraps (float precision).
#define WRAP 60000.0

// How much the sun and moon turn with the road (they'd turn as much as the
// far hills do, but then the sunset could be off the screen).
#define CELESTIAL 0.25

// Turns v by a about the vertical (x towards z).
vec3 rotY(vec3 v, float a) {
	float c = cos(a), s = sin(a);
	return vec3(v.x * c - v.z * s, v.y, v.x * s + v.z * c);
}

// ---- Hashes and noise. Float hashes (Hoskins' without sine): the phone's
// compiler mangles big integer constants.
float hash11(float p) {
	p = fract(p * 0.1031);
	p *= p + 33.33;
	p *= p + p;
	return fract(p);
}

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

// ---- The road along its length (x, metres from the start), in stretches
// ROAD_CELL long, each starting with a bend.
// Whether stretch n's bend is a sharp corner (only odd ones: so the one
// before never is).
bool sharp(float n) {
	return mod(n, 2.0) > 0.5 && hash11(n + 0.25) < SHARP;
}

// Which way the road heads after stretch n's bend (radians, left positive):
// a sharp corner turns hard, back across the way it was heading.
float headTo(float n) {
	if (sharp(n)) {
		float from = (hash11(n - 1.0) - 0.5) * 2.0 * MAX_TURN;
		return from - (from >= 0.0 ? 1.0 : -1.0) * (0.9 + 0.6 * hash11(n + 0.75));
	}
	return (hash11(n) - 0.5) * 2.0 * MAX_TURN;
}

// Stretch n's bend: the heading from and to, and how long it is.
vec3 bendOf(float n) {
	float from = headTo(n - 1.0), to = headTo(n);
	float radius = sharp(n) ? SHARP_RADIUS * (1.0 + 0.8 * hash11(n + 0.5)) : MIN_RADIUS * (1.0 + 2.0 * hash11(n + 0.5));
	return vec3(from, to, max(abs(to - from) * radius, 1.0));
}

// Which way it heads at x, and how sharply it's turning (1/radius).
vec2 heading(float x) {
	float n = floor(x / ROAD_CELL);
	vec3 b = bendOf(n);
	float u = clamp((x - n * ROAD_CELL) / b.z, 0.0, 1.0);
	return vec2(mix(b.x, b.y, u * u * (3.0 - 2.0 * u)), (b.y - b.x) * 6.0 * u * (1.0 - u) / b.z);
}

// Where the car stops for the stop sign in stretch n (along), or -1: at a
// crossroads, well after a gentle bend.
float stopIn(float n) {
	return !sharp(n) && hash11(n + 0.6) < STOPS && bendOf(n).z < 2000.0 ? n * ROAD_CELL + 2500.0 : -1.0;
}

// The middle of the crossroads in x's stretch (along), or far away.
float crossing(float x) {
	float s = stopIn(floor(x / ROAD_CELL));
	return s > 0.0 ? s + 9.0 : -1e9;
}

// How fast the car can go at x (m/s): slowing for sharp corners and stop
// signs (but not the last one it passed, `passed`), speeding up after.
float speedLimit(float x, float passed) {
	float v = SPEED / 3.6;
	float n = floor(x / ROAD_CELL);
	for (int i = -1; i <= 1; i++) {
		float m = n + float(i);
		if (sharp(m)) {
			vec3 b = bendOf(m);
			float s0 = m * ROAD_CELL, s1 = s0 + b.z;
			// (Its tightest is 1.5 times its mean.)
			float vc2 = CORNERING * b.z / (1.5 * abs(b.y - b.x));
			v = min(v, sqrt(vc2 + 2.0 * (x < s0 ? BRAKING * (s0 - x) : PICKUP * max(x - s1, 0.0))));
		}
		// A rolling stop: down to a crawl at the line (never stopped, so
		// always a little blurred), and away again.
		float s = stopIn(m), crawl = MIN_RATE * SPEED / 3.6;
		if (s > 0.0) {
			if (s > passed + 0.5) {
				v = min(v, x < s ? sqrt(crawl * crawl + 2.0 * BRAKING * (s - x)) : crawl);
			} else if (x > s) {
				v = min(v, sqrt(crawl * crawl + 2.0 * PICKUP * (x - s)));
			}
		}
	}
	return v;
}

// A turning lane: where its side road is (along), and how wide the lane is
// at x.
vec2 turning(float x) {
	float n = floor(x / 2500.0);
	vec4 h = hash42(vec2(n, 91.0));
	if (h.x > TURNS) {
		return vec2(-1e9, 0.0);
	}
	float at = n * 2500.0 + 400.0 + 1700.0 * h.y;
	// (None by a crossroads.)
	if (abs(at - crossing(at)) < 600.0 || abs(at - crossing(at - 600.0)) < 600.0) {
		return vec2(-1e9, 0.0);
	}
	float w = TURN_W * clamp((x - (at - 230.0)) / 50.0, 0.0, 1.0) * clamp((at + 170.0 - x) / 50.0, 0.0, 1.0);
	return vec2(at, w);
}

// How rough the road is at x (0-1): stretches of it.
float roughness(float x) {
	return smoothstep(0.5, 0.8, noise(vec2(x / 700.0, 3.0)));
}

// The pothole in x's cell (along, out, half length, half width), if any
// (half length 0 if not).
vec4 pothole(float x) {
	float n = floor(x / 30.0);
	vec4 h = hash42(vec2(n, 17.0));
	if (h.x > POTHOLES * (0.05 + roughness(n * 30.0))) {
		return vec4(0.0);
	}
	vec4 g = hash42(vec2(n, 29.0));
	return vec4(n * 30.0 + 2.0 + 26.0 * h.y, mix(0.25, SEAL_Z - 0.2, h.z), 0.15 + 0.5 * g.x * g.x, 0.12 + 0.3 * g.y);
}

// Where the dirt starts, out from the road: wandering in and out.
float verge(float x) {
	float v = GRAVEL_Z + GRAVEL_WANDER * (noise(vec2(x / 120.0, 7.0)) - 0.35) + 1.6 * (noise(vec2(x / 500.0, 8.0)) - 0.4) + 0.6 * (noise(vec2(x / 35.0, 6.0)) - 0.5) + 0.25 * (noise(vec2(x / 9.0, 4.0)) - 0.5);
	return max(v, SEAL_Z + 0.4);
}

// Slow noise over the land (x along the road, z out): what varies over
// large regions - the plants' size, colour and texture, the soil's.
vec2 region(float x, float z) {
	return vec2(noise(vec2(x / 700.0, z / 220.0 + 3.0)), noise(vec2(x / 450.0 + 7.0, z / 160.0)));
}

// The cut-outs (see the Image pass): their kinds.
#define NONE 0
#define SPINIFEX_CLUMP 1
#define BUSH_CLUMP 2
#define MULGA_TREE 3
#define GHOST_GUM 4
#define TERMITES 5
#define BOULDER 6
#define TUFT 7
#define WEED 8

float laneZ(int lane) {
	return NEAR_Z * pow(LANE_RATIO, float(lane));
}
int laneOf(float z) {
	return int(floor(log(max(z, 1e-3) / NEAR_Z) / log(LANE_RATIO) + 0.5));
}
float cellWidth(float z) {
	return 2.6 + 0.12 * z;
}

// What stands in cell `cell` of row `lane` (z its distance out): its kind,
// its middle along the road, its distance out, and its size (half width,
// height) and seed. Nothing on the gravel or the side roads.
int standing(int lane, float cell, float cw, float z, out float x, out float zj, out vec2 size, out vec4 seed) {
	seed = hash42(vec2(cell, float(lane) * 7.31));
	x = (cell + 0.5 + 0.6 * (seed.x - 0.5)) * cw;
	zj = z * (1.0 + (LANE_RATIO - 1.0) * (seed.y - 0.5) * 0.9);
	size = vec2(0.0);
	if (z < 12.0) {
		vec2 turn = turning(x);
		if (zj < verge(x) + turn.y + 0.5 || abs(x - turn.x) < 6.0) {
			return NONE;
		}
	} else if (abs(x - turning(x).x) < 6.0) {
		return NONE;
	}
	if (abs(x - crossing(x)) < 10.0) {
		return NONE;
	}
	vec4 more = hash42(vec2(cell + 0.5, float(lane) * 3.17 + 11.0));
	// Patches thick and thin, slowly changing along the road.
	float thick = BUSH * (0.4 + 1.2 * noise(vec2(x / 60.0, float(lane) * 0.37)));
	float k = more.x / max(thick, 1e-3);
	// Bigger plants in some regions, smaller in others.
	vec2 reg = region(x, zj);
	float big = 0.55 + 1.0 * reg.x;
	// Near the road, now and then a big tree or a big dark bush.
	float spin = 0.9, bush = z < 15.0 ? 0.06 + 0.1 * smoothstep(0.5, 0.8, reg.y) : 0.06;
	float trees = z > 15.0 ? 0.12 : z > 6.0 ? 0.04 : 0.0, gums = z > 30.0 ? 0.02 : z > 6.0 ? 0.01 : 0.0;
	float mounds = z > 6.0 ? 0.05 : 0.0, rocks = 0.06, tufts = z < 12.0 ? 0.5 : 0.0;
	// Mulga thickets here and there; weeds by the road, in patches.
	trees *= 0.3 + 2.5 * smoothstep(0.55, 0.8, noise(vec2(x / 300.0, 5.0)));
	float weeds = (z < 25.0 ? 0.35 : 0.04) * smoothstep(0.35, 0.7, noise(vec2(x / 90.0, 6.0)));
	if ((k -= tufts) < 0.0) {
		size = vec2(0.15 + 0.2 * more.y, 0.2 + 0.25 * more.z) * big;
		return TUFT;
	}
	if ((k -= weeds) < 0.0) {
		size = vec2(0.2 + 0.3 * more.y, 0.45 + 0.5 * more.z) * mix(1.0, big, 0.6);
		return WEED;
	}
	if ((k -= spin) < 0.0) {
		size = vec2(min((0.4 + 0.7 * more.y) * big, 0.35 * cw), (0.25 + 0.3 * more.z) * big);
		return SPINIFEX_CLUMP;
	}
	if ((k -= bush) < 0.0) {
		// (Near the road, some big.)
		float b = z < 15.0 ? 1.0 + 1.2 * more.w * more.w : big;
		size = vec2(min((0.4 + 0.5 * more.y) * b, 0.35 * cw), (0.5 + 0.7 * more.z) * b);
		return BUSH_CLUMP;
	}
	if ((k -= trees) < 0.0) {
		size = vec2(min(1.2 + 1.5 * more.y, 0.3 * cw), (3.0 + 3.0 * more.z) * (z < 15.0 ? 1.3 : 1.0));
		return MULGA_TREE;
	}
	if ((k -= gums) < 0.0) {
		size = vec2(min(2.0 + 1.5 * more.y, 0.3 * cw), 6.0 + 4.0 * more.z);
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

// Buffer B's table of what stands: for each row, SLOTS cells round the
// car's, from this one on.
#define SLOTS 64
float firstCell(float along, float cw) {
	return floor(along / cw) - float(SLOTS / 2);
}

// Towards the sun and the moon at time of day `day`, in the car's frame
// (the road heading `turn`): the sun rises behind, peaks high to the right
// and sets in front, slantwise; the moon rises in front, a little to the
// left, soon after (full, as it would be), and climbs slowly across to the
// right before going over and setting behind.
void sunMoon(float day, float turn, out vec3 sun, out vec3 moon) {
	vec3 w = normalize(vec3(0.25, 0.0, 1.0));
	vec3 u = normalize(vec3(0.7, 1.0, -0.3));
	u = normalize(u - dot(u, w) * w);
	float p = (day - 0.25) * TAU;
	vec3 wm = normalize(vec3(-0.22, 0.0, 1.0));
	vec3 um = normalize(vec3(1.0, 0.5, 0.2));
	um = normalize(um - dot(um, wm) * wm);
	float pm = (day - 0.75) * TAU - 0.35;
	sun = rotY(-cos(p) * w + sin(p) * u, -turn * CELESTIAL);
	moon = rotY(cos(pm) * wm + sin(pm) * um, -turn * CELESTIAL);
}

// The key light: towards the sun, or the moon at night, and how strong.
vec4 keyLight(float day, float turn) {
	vec3 sun, moon;
	sunMoon(day, turn, sun, moon);
	if (sun.y > -0.03 || moon.y < 0.0) {
		return vec4(sun, smoothstep(-0.035, 0.03, sun.y));
	}
	return vec4(moon, 0.09 * smoothstep(-0.03, 0.1, moon.y));
}

// The bend a thing z out bends with: on the inside of a tight bend, things
// further out than its middle bend less (rather than wrap round it).
float bendAt(float bend, float z) {
	return bend > 0.0 ? max(bend, 1.5 * z) : bend;
}

// Where the road point r (along, out) is from the car (x along, z out), the
// car `along` the road and pulled `pull` out, on a bend of radius `bend`.
vec2 fromRoad(vec2 r, float bend, float along, float pull) {
	float b = bendAt(bend, r.y);
	float sg = b >= 0.0 ? 1.0 : -1.0;
	float th = (r.x - along) / abs(b);
	float d = sg * (b - r.y);
	float h = sin(0.5 * th);
	return vec2(d * sin(th), r.y - pull + sg * d * 2.0 * h * h);
}

// How far the wheel's dropped at x: road roughness and potholes in the
// wheels' track (out about 0.3 m).
float bump(float x) {
	float b = 0.004 * (0.3 + roughness(x)) * (noise(vec2(x * 1.7, 0.0)) - 0.5);
	vec4 p = pothole(x);
	if (p.z > 0.0 && p.y < 0.55) {
		float u = (x - p.x) / p.z;
		b -= 0.03 * max(0.0, 1.0 - u * u);
	}
	return b;
}

//// Buffer A
///float32
uniform sampler2D iChannel0; ///buf:A;

// Pointers come in pixels (or 0-1, from some sources).
vec2 scaled(vec2 p) {
	return p.x <= 1.5 && p.y <= 1.5 ? p * iResolution.xy : p;
}

// The brightest glint the key light makes off a shard of glass or quartz in
// the dirt, seen from the car: (along, out, how bright, 0). Shards lie in
// the rows' empty cells, their faces tilted every which way; one glints
// when it's near the mirror angle between the light and the eye.
vec4 findGlint(float along, float day, float pull) {
	vec2 hd = heading(along);
	vec4 key = keyLight(day, hd.x);
	if (key.w < 0.01 || key.y < 0.005) {
		return vec4(0.0);
	}
	float bend = 1.0 / (abs(hd.y) < 1e-6 ? 1e-6 : hd.y);
	vec4 best = vec4(0.0);
	// The rows from about 8 m out to 120 m, the stretch of each in view.
	for (int lane = 4; lane <= 22; lane++) {
		float z = laneZ(lane), cw = cellWidth(z);
		float c0 = floor((along - 1.3 * z) / cw);
		float cells = 2.6 * z / cw;
		for (int i = 0; i < 40; i++) {
			if (float(i) > cells) {
				break;
			}
			float cell = c0 + float(i);
			vec4 h = hash42(vec2(cell, float(lane) * 5.13 + 77.0));
			if (h.x > GLINTS) {
				continue;
			}
			float x, zj;
			vec2 size;
			vec4 seed;
			if (standing(lane, cell, cw, z, x, zj, size, seed) != NONE) {
				continue;
			}
			vec2 r = vec2((cell + h.y) * cw, z * (1.0 + (LANE_RATIO - 1.0) * (h.z - 0.5)));
			vec2 p = fromRoad(r, bend, along, pull);
			vec3 eye = normalize(vec3(-p.x, EYE, -p.y));
			vec4 g = hash42(vec2(cell, float(lane) * 2.71 + 33.0));
			float tilt = 1.1 * sqrt(g.x), az = g.y * TAU;
			vec3 n = vec3(sin(tilt) * cos(az), cos(tilt), sin(tilt) * sin(az));
			float b = exp(-2.0 * (1.0 - dot(n, normalize(key.xyz + eye))) / (GLINT_SPREAD * GLINT_SPREAD));
			if (b > best.z) {
				best = vec4(r, b, 0.0);
			}
		}
	}
	return best;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 c = ivec2(fragCoord);
	if (c.y != 0 || c.x > S_GLINT) {
		fragColor = vec4(0.0);
		return;
	}
	// The clock: how far along, the time of day, how fast time's going, the
	// car's speed (m/s, as it actually moved).
	vec4 clock = texelFetch(iChannel0, ivec2(S_CLOCK, 0), 0);
	// The touch: fingers last frame, their middle across.
	vec4 touch = texelFetch(iChannel0, ivec2(S_TOUCH, 0), 0);
	// Bounce and pitch, and how fast they're changing.
	vec4 bounce = texelFetch(iChannel0, ivec2(S_BOUNCE, 0), 0);
	// Roll, how fast it's changing, acceleration, MARK.
	vec4 roll = texelFetch(iChannel0, ivec2(S_ROLL, 0), 0);
	// The last stop sign passed (along), unused; how far it's pulled over,
	// and how fast it's going over.
	vec4 stop = texelFetch(iChannel0, ivec2(S_STOP, 0), 0);
	if (abs(roll.w - MARK) > 0.01) {
		clock = vec4(START_ALONG, START_TIME, 1.0, SPEED / 3.6);
		touch = vec4(0.0);
		bounce = vec4(0.0);
		roll = vec4(0.0, 0.0, 0.0, MARK);
		stop = vec4(-1.0, 0.0, 0.0, 0.0);
	}
	float dt = iTimeDelta > 0.0 ? min(iTimeDelta, 0.1) : 1.0 / 60.0;

	// Three fingers stop time (easing), and dragging them scrubs it.
	int n = min(pointerCount, 3);
	float across = 0.0;
	for (int i = 0; i < 3; i++) {
		if (i < n) {
			across += scaled(pointers[i].xy).x / float(n);
		}
	}
	bool three = pointerCount >= 3;
	float scrub = three && touch.x > 2.5 ? (across - touch.y) / iResolution.x * SCRUB : 0.0;
	touch = vec4(float(pointerCount), across, 0.0, 0.0);
	clock.z += clamp((three ? MIN_RATE : 1.0) - clock.z, -dt / EASE, dt / EASE);
	float rate = clock.z;

	// How far it goes: as fast as the road lets it, crawling over a stop
	// sign's line (which then counts as passed).
	float from = clock.x;
	float go = rate * dt * speedLimit(from, stop.x) + scrub * SPEED / 3.6;
	float to = from + go;
	float cell = floor(from / ROAD_CELL);
	for (int i = 0; i <= 1; i++) {
		float s = stopIn(cell + float(i));
		if (s > stop.x + 0.5 && s > from - 0.5) {
			if (to >= s) {
				stop.xy = vec2(s, 0.0);
			}
			break;
		}
	}
	go = to - from;
	if (to >= WRAP || to < 0.0) {
		to = mod(to, WRAP);
		stop.x = -1.0;
	}
	clock.x = to;
	clock.y = fract(clock.y + (rate * dt + scrub) / DAY_LENGTH);
	float v = mix(go / dt, clock.w, exp(-dt / 0.08));
	float accel = mix(clamp((v - clock.w) / dt, -12.0, 12.0), roll.z, exp(-dt / 0.25));
	clock.w = v;
	roll.z = accel;

	// Pulling over as the fingers stop it, and back out as it goes again.
	float pull = PULL_Z * smoothstep(0.0, 1.0 - MIN_RATE, 1.0 - rate);
	// (And drifting a little in and out in its lane.)
	pull += DRIFT * (noise(vec2(clock.x / 220.0, 41.0)) * 2.0 - 1.0);
	float pv = (pull - stop.z) / dt;
	float sideways = clamp((pv - stop.w) / dt, -3.0, 3.0);
	stop.zw = vec2(pull, pv);

	// The suspension: springs to where the bumps, braking and bends push -
	// within reason when scrubbing fast (the bumps blur out, the car only
	// leans so far); rougher on the gravel.
	float calm = min(40.0 / max(abs(v), 1.0), 1.0) * (1.0 + 2.0 * pull / PULL_Z);
	float front = calm * bump(clock.x + 1.3), back = calm * bump(clock.x - 1.4);
	float lift = 0.5 * (front + back);
	float nose = clamp((back - front) / 2.7 * 0.6 - accel * DIVE, -0.05, 0.05);
	float vs = clamp(v, -40.0, 40.0);
	float lean = clamp((vs * vs * heading(clock.x).y + sideways) * LEAN, -0.06, 0.06);
	float k = pow(TAU * BOUNCE_HZ, 2.0), damp = 2.0 * DAMPING * TAU * BOUNCE_HZ;
	for (int i = 0; i < 4; i++) {
		float h = 0.25 * dt;
		bounce.y += (-k * (bounce.x - lift) - damp * bounce.y) * h;
		bounce.x += bounce.y * h;
		bounce.w += (-k * (bounce.z - nose) - damp * bounce.w) * h;
		bounce.z += bounce.w * h;
		roll.y += (-k * 0.7 * (roll.x - lean) - damp * roll.y) * h;
		roll.x += roll.y * h;
	}
	bounce = clamp(bounce, vec4(-0.08, -1.0, -0.08, -1.0), vec4(0.08, 1.0, 0.08, 1.0));
	roll.xy = clamp(roll.xy, vec2(-0.1, -1.0), vec2(0.1, 1.0));

	if (c.x == S_CLOCK) fragColor = clock;
	else if (c.x == S_TOUCH) fragColor = touch;
	else if (c.x == S_BOUNCE) fragColor = bounce;
	else if (c.x == S_ROLL) fragColor = roll;
	else if (c.x == S_STOP) fragColor = stop;
	else fragColor = findGlint(clock.x, clock.y, pull);
}

//// Buffer B
///float32
// What stands in each cell (see standing()) of each row of plants and
// rocks, for SLOTS cells round the car: a row a lane, two texels a cell -
// (along, out, half width, kind x 16 + height), and its seed.
uniform sampler2D iChannel0; ///buf:A;

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 c = ivec2(fragCoord);
	if (c.y >= LANES || c.x >= 2 * SLOTS) {
		fragColor = vec4(0.0);
		return;
	}
	vec4 clock = texelFetch(iChannel0, ivec2(S_CLOCK, 0), 0);
	float along = abs(texelFetch(iChannel0, ivec2(S_ROLL, 0), 0).w - MARK) > 0.01 ? START_ALONG : clock.x;
	int lane = c.y;
	float z = laneZ(lane), cw = cellWidth(z);
	float first = firstCell(along, cw);
	float cell = first + mod(float(c.x / 2) - first, float(SLOTS));
	float x, zj;
	vec2 size;
	vec4 seed;
	int kind = standing(lane, cell, cw, z, x, zj, size, seed);
	fragColor = c.x % 2 == 0 ? vec4(x, zj, size.x, float(kind) * 16.0 + size.y) : seed;
}

//// Buffer C
///float32
// The tree atlas: TREE_COLS x TREE_ROWS trees and bushes - mulgas, desert
// oaks, dead trees, ghost gums, saltbushes and others - drawn once into tiles, then summed along each tile's
// rows, so the Image pass gets any stretch of a row (a pixel's, however
// blurred) from two sums. Two bands of the same tiles: (coverage, colour x
// coverage), and (normal across, normal up, leaves) x coverage with the
// distance to the outline in texels (not summed; not used yet). Built over
// a few frames - drawn, summed in doubling steps, copied to the pass's
// other target - then left be. Texel (0, top) says how far it's got:
// (ATLAS_MARK, width, height, step).
uniform sampler2D iChannel0; ///buf:C;

// Atlas tree t: whether it's a ghost gum, and its seed (seed.w: a mulga,
// desert oak or dead tree).
void treeTile(int t, out bool gum, out vec4 seed) {
	seed = hash42(vec2(float(t) * 1.37 + 0.5, 9.1));
	gum = t >= MULGAS + OAKS + DEAD;
	seed.w = t < MULGAS ? 0.3 + 0.5 * seed.w : t < MULGAS + OAKS ? 0.29 * seed.w : 0.83 + 0.17 * seed.w;
}

// Atlas tile t's height over its radius.
float tileTall(int t) {
	return t >= TREES ? BUSH_TALL : t >= MULGAS + OAKS + DEAD ? GUM_TALL : MULGA_TALL;
}

// Where q (h the tree's height) is on the tree before its branches
// wandered: two octaves of noise, nothing at the root (the origin) and
// WOBBLE at the top.
vec2 wander(vec2 q, float h, vec2 seed) {
	vec2 f = q * 2.5 + seed * 40.0;
	vec2 n = vec2(noise(f), noise(f + 17.0)) - 0.5 + 0.4 * (vec2(noise(f * 2.8 + 5.0), noise(f * 2.8 + 23.0)) - 0.5);
	return q + WOBBLE * min(length(q) / h, 1.0) * n;
}

// How far q is outside the branch from p0 to p1 (half width w, tapering
// to 0.6 w), keeping the nearest so far in d, and where across it q is
// (out from its middle, 1 at its edge) in across.
void branch(vec2 q, vec2 p0, vec2 p1, float w, inout float d, inout vec2 across) {
	vec2 e = p1 - p0;
	float s = clamp(dot(q - p0, e) / dot(e, e), 0.0, 1.0);
	float ws = w * mix(1.0, 0.6, s);
	vec2 o = q - (p0 + s * e);
	float dq = length(o) - ws;
	if (dq < d) {
		d = dq;
		across = o / ws;
	}
}

// A clump of leaves round c (half sizes r): a few ragged blobs. Keeps the
// one q's deepest in so far: e (under 1 inside it) and where in it q is
// (p, its normal out, roughly).
void clump(vec2 q, vec2 c, vec2 r, float seed, inout float e, inout vec2 p) {
	for (int k = 0; k < 5; k++) {
		vec4 hk = hash42(vec2(seed + float(k) * 3.1, 2.7));
		vec2 rb = r * (0.45 + 0.25 * hk.z);
		vec2 pb = (q - c - (hk.xy - 0.5) * r * 1.1) / rb;
		float a = atan(pb.y, pb.x);
		float eb = length(pb) / (1.0 + 0.18 * sin(5.0 * a + 6.28 * hk.w) + 0.1 * sin(11.0 * a + 3.0 * hk.z));
		if (eb < e) {
			e = eb;
			p = pb;
		}
	}
}

// The colour (darker where it's shaded within) and coverage at q, and
// its normal's x and y, and whether it's leaves: in leaves if e < 1 (p:
// where in the clump) and the noise (freq a radius) leaves no gap there,
// or else on a branch if db < 0 (across: where across it). shade: how
// much light gets down to the leaves there.
void surface(vec2 q, vec4 seed, float e, vec2 p, float db, vec2 across, vec3 green, vec3 bark, float gaps, float freq, float shade, bool gum, out vec4 col, out vec3 nl) {
	// More gaps towards the clumps' edges.
	vec2 f = q * freq + seed.xy * 50.0;
	float n = 0.65 * noise(f) + 0.35 * noise(f * 2.3 + 7.0);
	if (e < 1.0 && n > gaps + 0.35 * e * e) {
		col = vec4(green * (0.75 + 0.5 * noise(f * 0.5 + 17.0)) * (0.55 + 0.45 * smoothstep(-0.9, 0.7, p.y)) * shade, 1.0);
		vec2 tilt = vec2(noise(f + 31.0), noise(f + 57.0)) - 0.5;
		vec2 nxy = 0.75 * p + 0.5 * tilt;
		nl = vec3(nxy / max(1.0, length(nxy) / 0.95), 1.0);
		return;
	}
	if (db < 0.0) {
		// Bark: streaky up it (a gum's in patches), darker in the leaves.
		float g = noise(q * vec2(25.0, 3.0) + seed.zw * 20.0);
		vec3 c = gum ? bark * mix(0.75, 1.0, smoothstep(0.3, 0.6, noise(q * 6.0 + 3.0))) : bark * (0.8 + 0.35 * g);
		col = vec4(c * mix(0.6, 1.0, smoothstep(0.9, 1.5, e)), 1.0);
		nl = vec3(0.9 * across, 0.0);
		return;
	}
	col = vec4(0.0);
	nl = vec3(0.0);
}

// What's at q (x in the bush's radius, y up from its foot) on atlas bush
// b: five stems fanning out from the root, a twig on each (two, bare),
// and clumps of leaves along them - a grey-green saltbush, a darker one,
// or bare and sticky.
void bushAt(int b, vec2 q, out vec4 col, out vec3 nl, out float dist) {
	vec4 seed = hash42(vec2(float(b) * 2.13 + 0.5, 4.4));
	float h = BUSH_TALL;
	bool bare = b >= BUSHES - 2, dark = !bare && b >= BUSHES - 4;
	vec3 stem = bare ? vec3(0.40, 0.34, 0.30) : BARK * 0.9;
	vec3 green = dark ? MULGA * vec3(0.6, 0.65, 0.62) : mix(SALTBUSH, SALTBUSH * vec3(0.85, 1.0, 1.05), seed.z);
	float tw = 0.03;
	vec2 qb = wander(q, h, seed.xy);
	float db = 1e9, e = 9.0;
	vec2 across = vec2(0.0), p = vec2(0.0);
	for (int k = 0; k < 5; k++) {
		vec4 hh = hash42(vec2(seed.x * 53.0 + float(k), seed.y * 17.0));
		float side = 0.5 * float(k) - 1.0;
		vec2 root = vec2(0.1 * side, 0.0);
		vec2 tip = vec2(0.75 * side + 0.25 * (hh.x - 0.5), h * (0.9 - 0.3 * abs(side) + 0.1 * (hh.y - 0.5)));
		vec2 at = mix(root, tip, 0.5 + 0.2 * hh.z);
		vec2 twig = at + vec2(0.25 * side + 0.3 * (hh.w - 0.5), h * (0.15 + 0.15 * hh.y));
		branch(qb, root, tip, tw, db, across);
		branch(qb, at, twig, 0.6 * tw, db, across);
		if (bare) {
			vec2 at2 = mix(root, tip, 0.75);
			branch(qb, at2, at2 + vec2(0.2 * (hh.x - 0.5) - 0.2 * side, 0.15 * h), 0.5 * tw, db, across);
		} else {
			clump(qb, tip, vec2(0.34, 0.26 * h) * (0.8 + 0.4 * hh.w), hh.x * 40.0, e, p);
			clump(qb, twig, vec2(0.28, 0.22 * h) * (0.8 + 0.4 * hh.z), hh.y * 40.0, e, p);
			clump(qb, mix(root, tip, 0.3), vec2(0.3, 0.2 * h), hh.z * 40.0, e, p);
		}
	}
	dist = min(db, (e - 1.0) * 0.1 * h);
	surface(q, seed, e, p, db, across, green, stem, 0.2, LEAF_FREQ * 1.2, mix(0.75, 1.0, smoothstep(0.0, h, q.y)), false, col, nl);
}

// What's at q (x in the tree's radius, y up from its foot) on atlas tree
// t: see surface(); and how far outside it q is.
void treeAt(int t, vec2 q, out vec4 col, out vec3 nl, out float dist) {
	if (t >= TREES) {
		bushAt(t - TREES, q, col, nl, dist);
		return;
	}
	bool gum;
	vec4 seed;
	treeTile(t, gum, seed);
	float h = gum ? GUM_TALL : MULGA_TALL;
	bool oak = !gum && seed.w < 0.3, dead = !gum && seed.w > 0.82;
	vec3 bark = gum ? GUM_BARK : dead ? vec3(0.42, 0.40, 0.38) : BARK * mix(0.6, 1.0, seed.z);
	vec3 green = gum ? GUM_LEAVES : oak ? MULGA * vec3(0.5, 0.55, 0.6) : MULGA * 0.75;
	float fork = h * (gum ? 0.45 : 0.25 + 0.2 * seed.y);
	float tw = h * (gum ? 0.024 : 0.018);
	vec2 top = vec2((seed.z - 0.5) * 0.3 * fork, fork);
	vec2 sag = vec2(0.0, oak ? 0.06 * h : 0.0);
	// The trunk, flaring at its foot; three limbs, each with two twigs; and
	// clumps of leaves on their ends - all wandering, more towards the top.
	vec2 qb = wander(q, h, seed.xy);
	float db = 1e9, e = 9.0;
	vec2 across = vec2(0.0), p = vec2(0.0);
	branch(qb, vec2(0.0), top, tw * (1.0 + 1.2 * (1.0 - smoothstep(0.0, 0.1 * h, q.y))), db, across);
	for (int i = 0; i < 3; i++) {
		vec4 hh = hash42(vec2(seed.x * 91.0 + float(i), seed.y * 37.0));
		float side = float(i) - 1.0;
		vec2 tip = vec2(0.7 * side + 0.3 * (hh.x - 0.5), h * (0.68 + 0.2 * hh.y));
		vec2 at0 = mix(top, tip, 0.45), at1 = mix(top, tip, 0.75);
		float spread = 0.6 + 0.6 * hh.z, lift = h * (0.1 + 0.12 * hh.w);
		vec2 twig0 = at0 + vec2(spread * (-0.28 + 0.1 * side), lift);
		vec2 twig1 = at1 + vec2(spread * (0.28 + 0.1 * side), lift);
		branch(qb, top, tip, 0.6 * tw, db, across);
		branch(qb, at0, twig0, 0.3 * tw, db, across);
		branch(qb, at1, twig1, 0.3 * tw, db, across);
		if (!dead) {
			vec2 small = vec2(0.26, 0.1 * h) * (0.8 + 0.4 * hh.z);
			clump(qb, twig0 - sag, small, hh.x * 40.0, e, p);
			clump(qb, twig1 - sag, small, hh.x * 40.0 + 9.0, e, p);
			clump(qb, tip - sag, vec2(0.32, 0.12 * h) * (0.8 + 0.4 * hh.w), hh.y * 40.0, e, p);
		}
	}
	dist = min(db, (e - 1.0) * 0.1 * h);
	surface(q, seed, e, p, db, across, green, bark, gum ? 0.4 : 0.3, LEAF_FREQ, mix(0.8, 1.0, smoothstep(fork, h, q.y)), gum, col, nl);
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 c = ivec2(fragCoord);
	vec2 res = iResolution.xy;
	ivec3 L = atlasLayout(res);
	float done = atlasDone(L.x);
	ivec2 at = ivec2(0, int(res.y) - 1);
	vec4 mark = texelFetch(iChannel0, at, 0);
	float step = mark.x == ATLAS_MARK && mark.y == res.x && mark.z == res.y ? mark.w : 0.0;
	if (c == at) {
		fragColor = vec4(ATLAS_MARK, res, min(step + 1.0, done));
		return;
	}
	if (step >= done) {
		discard;
	}
	int band = c.y / L.z, ly = c.y - band * L.z;
	int col = c.x / L.x, row = ly / L.y;
	if (band > 1 || col >= TREE_COLS || row >= TREE_ROWS) {
		fragColor = vec4(0.0);
		return;
	}
	ivec2 p = ivec2(c.x - col * L.x, ly - row * L.y);
	int t = row * TREE_COLS + col;
	if (step < ATLAS_DRAWS && t != int(step)) {
		// Not this step's tile: kept if drawn, or still empty.
		fragColor = t < int(step) ? texelFetch(iChannel0, c, 0) : vec4(0.0);
	} else if (step < ATLAS_DRAWS && p.x == 0) {
		// Each tile's first column's left empty (see the Image pass's
		// atlasSum()).
		fragColor = vec4(0.0);
	} else if (step < ATLAS_DRAWS) {
		// Drawn: 3 x 3 samples a texel.
		vec2 texel = vec2(2.0 * TREE_SPAN / float(L.x), tileTall(t) / float(L.y));
		vec2 q0 = vec2(-TREE_SPAN, 0.0) + vec2(p) * texel;
		vec4 a = vec4(0.0), b = vec4(0.0);
		for (int i = 0; i < 9; i++) {
			vec4 s;
			vec3 nl;
			float d;
			treeAt(t, q0 + (vec2(i % 3, i / 3) + 0.5) / 3.0 * texel, s, nl, d);
			a += vec4(s.a, s.rgb * s.a) / 9.0;
			b.xyz += nl * s.a / 9.0;
		}
		vec4 s;
		vec3 nl;
		float d;
		treeAt(t, q0 + 0.5 * texel, s, nl, d);
		b.w = d / texel.x;
		fragColor = band == 0 ? a : b;
	} else if (step < done - 1.0) {
		// Summed along the row: each step adds what's d to the left.
		int d = 1 << int(step - ATLAS_DRAWS);
		fragColor = texelFetch(iChannel0, c, 0);
		if (p.x >= d) {
			vec4 left = texelFetch(iChannel0, c - ivec2(d, 0), 0);
			fragColor += band == 0 ? left : vec4(left.xyz, 0.0);
		}
	} else {
		fragColor = texelFetch(iChannel0, c, 0);
	}
}
