// Powder MPM - a Powder Toy-style sandbox on a 2D MPM solver (reintegration
// tracking, after Mykhailo Moroz's shadertoy MPM): dry sand that heaps and
// avalanches, soaks up water, crumbles in clumps when damp and slumps into
// mud when saturated; water that splashes and pools; wobbly jelly; rock
// that falls and tumbles, and fixed rock - with sound made from the
// simulation itself.
//
// Paint with a finger; the palette along the bottom picks what you paint
// (Sand, Water, Jelly, Rock, Erase) and the brush size (the last button: tap
// it for the next size, or drag out from it to any size). Hold Rock to swap
// it between moving rock and fixed (outlined blue, drawn as bricks); hold
// Erase to clear everything.
//
// How it works: the simulation grid is CELL screen pixels a cell, and every
// cell holds at most one blob of material (its centre of mass, velocity,
// mass, material, a few flags and how hard it's just been hit, packed into
// one float texel). Each step:
//   Buffer A - forces: pressure, cohesion and friction between neighbours,
//              the jelly's elastic stress, gravity; rock held to its shape
//              and moving as one; sand settling and rolling off, as in the
//              Powder Toy; walls (fixed rock); painting.
//   Buffer B - reintegration: each blob moves as a box of material, and
//              every cell gathers the part of each box landing on it.
//   Buffer C - what a blob carries besides, carried along the same way:
//              the jelly's deformation gradient (turned by the local
//              velocity gradient), where rock was in its shape (and how far
//              it's turned), sand's colour and wetness (soaked up from the
//              water beside it); and the weight on sand, water and rock
//              (so deep sand's held still harder); its top row holds the
//              palette's state.
//   Image    - each material's smooth field, lit by daylight as a surface
//              rising where there's more of it; water shaded by how hard
//              it's pressed and how fast it flows.
//   Buffer D - sound: grains taken from random cells - sand rumbles, water
//              sloshes and bubbles, jelly wobbles, rock knocks - as loud
//              as they move.
///steps:4
uniform sampler2D iChannel0; ///buf:B;
uniform sampler2D iChannel1; ///buf:C;

// The metaball field of every material at a point, for drawing: how much
// of it there is about there (1 for a cell's worth all round) - smooth
// enough not to show the grid where it's thinned out - and which way it
// rises (its gradient, x and y, for its surface's normal: see daylight());
// for water, how it flows and how hard it's pressed, and how high jelly
// bulges (see dome()), as smoothly (summed as the field is, with their
// gradients); the nearest grain of sand, and how far it is; and the
// nearest bit of rock.
void fields(vec2 at, ivec2 grid, int floorRow, out vec4 field, out vec4 gx, out vec4 gy, out vec2 flow,
		out float pressure, out vec3 bulge, out ivec2 grain, out float grainDist, out ivec2 stone) {
	field = gx = gy = vec4(0.0);
	flow = vec2(0.0);
	pressure = 0.0;
	bulge = vec3(0.0);
	grain = stone = ivec2(0);
	grainDist = 1e9;
	float stoneDist = 1e9;
	ivec2 c = ivec2(floor(at));
	for (int j = -2; j <= 2; ++j) {
		for (int i = -2; i <= 2; ++i) {
			ivec2 n = c + ivec2(i, j);
			if (!inside(n, grid, floorRow)) {
				continue;
			}
			Particle q = load(iChannel0, n);
			if (q.mat == EMPTY || q.mat == WALL) {
				continue;
			}
			vec2 d = at - q.X;
			float r2 = dot(d, d) / (BLOB * BLOB);
			if (r2 >= 1.0) {
				continue;
			}
			float scale = q.M * 3.0 / (3.14159265 * BLOB * BLOB);
			float k = (1.0 - r2) * (1.0 - r2) * scale;
			vec2 g = -4.0 * (1.0 - r2) * scale / (BLOB * BLOB) * d;
			int slot = q.mat - SAND;
			field[slot] += k;
			gx[slot] += g.x;
			gy[slot] += g.y;
			if (slot == 1) {
				// (Its pressure: the weight of the water on it, and how
				// much more it's packed than that makes it.)
				flow += k * q.V;
				vec4 e = texelFetch(iChannel1, n, 0);
				pressure += k * (e.z + max(q.M - 1.0 - GRAVITY * e.z / WATER_STIFFNESS, 0.0) * WATER_STIFFNESS / GRAVITY);
			} else if (slot == 2) {
				vec4 e = texelFetch(iChannel1, n, 0);
				bulge += vec3(k, g) * (notJelly(e) ? 0.0 : jellyDome(e));
			}
			float dist = length(d);
			if (slot == 0 && dist < grainDist) {
				grainDist = dist;
				grain = n;
			} else if (slot == 3 && dist < stoneDist) {
				stoneDist = dist;
				stone = n;
			}
		}
	}
}

// Daylight: the sun, up and to the left (and toward us), and the sky.
const vec3 SUN = vec3(-0.45, 0.62, 0.64);
const vec3 SUNLIGHT = vec3(1.0, 0.93, 0.8);
const vec3 SKYLIGHT = vec3(0.6, 0.73, 0.95);

// How a surface slopes that rises `rise` cells as a field f (of gradient
// g) goes from lo to hi - eased, so it's flat on top, rounded at its edges.
vec2 rounded(float f, vec2 g, float lo, float hi, float rise) {
	float t = clamp((f - lo) / (hi - lo), 0.0, 1.0);
	return rise * 6.0 * t * (1.0 - t) / (hi - lo) * g;
}

// A surface lit by daylight: its colour, how it slopes (its height's
// gradient), how shiny it is and how tight its highlights.
vec3 daylight(vec3 albedo, vec2 slope, float shine, float tight) {
	vec3 n = normalize(vec3(-slope, 1.0));
	// (The sky lighting it from above more than below; as bright, flat on,
	// as it was unlit.)
	vec3 light = 1.5 * (0.55 * SKYLIGHT * (0.8 + 0.2 * n.y) + 0.55 * SUNLIGHT * max(dot(n, SUN), 0.0));
	float highlight = shine * pow(max(dot(n, normalize(SUN + vec3(0.0, 0.0, 1.0))), 0.0), tight);
	return albedo * light + highlight * SUNLIGHT;
}

// Ripples at a point in water flowing at v (cells a step), carried along
// with it: two layers of noise, each moved along by the flow for a while
// and faded out before it's dragged too far, half a while apart.
float ripples(vec2 at, vec2 v) {
	float t = iTime / RIPPLE_TIME;
	vec2 drift = v * 240.0 * RIPPLE_TIME;
	float r = 0.0;
	for (int k = 0; k < 2; ++k) {
		float phase = fract(t + 0.5 * float(k));
		vec2 x = (at - drift * phase) / RIPPLE_SIZE + float(k) * vec2(17.3, 41.7) + floor(t + 0.5 * float(k)) * vec2(3.1, 7.9);
		// (Its ridges: thin bright lines, like light through ripples.)
		float n = 0.65 * smoothNoise2(x) + 0.35 * smoothNoise2(x * 2.3 + 5.0);
		n = 1.0 - abs(2.0 * n - 1.0);
		r += (1.0 - abs(2.0 * phase - 1.0)) * n * n * n;
	}
	return r;
}

// Sand of a shade, 0 to 1: dark to bright, the middle as it always was.
vec3 sandColour(float shade) {
	return shade < 0.5 ? mix(vec3(0.42, 0.29, 0.15), vec3(0.76, 0.58, 0.32), 2.0 * shade)
			: mix(vec3(0.76, 0.58, 0.32), vec3(0.97, 0.87, 0.64), 2.0 * shade - 1.0);
}

// Rock's surface, at a point in its shape (in cells): how high it stands
// - mottled lumps, now and then a pebble standing out of it or a pit worn
// into it (round, each somewhere in its own square of a grid), and a fine
// grain. Its colour's lighter as it's higher, and it's lit as it slopes
// (a bump map), so the two go together.
float stoneHeight(vec2 r) {
	float n = 0.6 * smoothNoise2(r * 0.25) + 0.4 * smoothNoise2(r * 0.7 + 7.0);
	vec2 g = r * 0.45;
	vec2 square = floor(g);
	float h = hash12(square + 0.5);
	vec2 spot = square + 0.3 + 0.4 * vec2(fract(h * 13.1), fract(h * 71.7));
	float bump = smoothstep(0.3, 0.05, length(g - spot) / (0.6 + 0.4 * fract(h * 37.3)));
	float kind = h > 0.8 ? 1.0 : h < 0.25 ? -1.0 : 0.0;
	return n + 0.4 * kind * bump + 0.1 * smoothNoise2(r * 2.2 + 3.0);
}

vec3 stoneColour(vec2 r) {
	return mix(vec3(0.27, 0.26, 0.28), vec3(0.68, 0.64, 0.58), clamp(stoneHeight(r), 0.0, 1.2));
}

// Which way rock's surface slopes at a point in its shape (per cell of its
// shape).
vec2 stoneSlope(vec2 r) {
	const float e = 0.25;
	return vec2(stoneHeight(r + vec2(e, 0.0)) - stoneHeight(r - vec2(e, 0.0)),
			stoneHeight(r + vec2(0.0, e)) - stoneHeight(r - vec2(0.0, e))) / (2.0 * e);
}

// Walls (fixed rock), at a point in cells: bricks.
vec3 bricks(vec2 at) {
	vec2 b = at * vec2(0.5, 1.0);
	b.x += 0.5 * mod(floor(b.y), 2.0);
	vec2 m = abs(fract(b) - 0.5);
	return vec3(0.42, 0.44, 0.5) * (0.85 + 0.15 * hash12(floor(b))) * (0.7 + 0.3 * smoothstep(0.5, 0.42, max(m.x, m.y * 0.5 + 0.25)));
}

vec3 toolColour(int tool, vec2 at) {
	if (tool == SAND) {
		return sandColour(newShade(floor(at * 0.25)));
	} else if (tool == WATER) {
		return vec3(0.15, 0.45, 0.95);
	} else if (tool == JELLY) {
		return vec3(0.95, 0.35, 0.65);
	} else if (tool == ROCK) {
		return stoneColour(at / 3.0);
	} else if (tool == WALL) {
		return bricks(at / 4.0);
	}
	return vec3(0.12);
}

vec3 palette(vec2 fragCoord, vec2 res, vec4 ui, bool clearing, bool fixedRock) {
	float top = res.y * PALETTE;
	float w = res.x / float(TOOLS + 1);
	int b = int(fragCoord.x / w);
	vec2 local = vec2(fragCoord.x - (float(b) + 0.5) * w, fragCoord.y - 0.5 * top);
	vec2 halfSize = vec2(0.42 * w, 0.36 * top);
	vec2 q = abs(local) - halfSize + 6.0;
	float box = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - 6.0;
	vec3 col = vec3(0.07, 0.08, 0.1);
	vec3 face = vec3(0.16);
	if (b < TOOLS) {
		int tool = toolAt(b, fixedRock);
		face = toolColour(tool, fragCoord);
		if (tool == ERASE) {
			// A cross.
			vec2 a = abs(local);
			float x = abs(a.x - a.y) * 0.7071;
			face = mix(face, vec3(0.9, 0.3, 0.3), smoothstep(3.0, 1.5, x) * step(max(a.x, a.y), halfSize.y * 0.6));
			if (clearing) {
				face = mix(face, vec3(0.9, 0.2, 0.2), 0.5);
			}
		}
	} else {
		// The brush: a disc as big as it paints.
		float r = ui.y * CELL;
		face = mix(face, vec3(0.9), smoothstep(1.5, 0.0, length(local) - min(r, halfSize.y * 0.9)));
	}
	col = mix(col, face, smoothstep(1.0, -1.0, box));
	if (b < TOOLS && int(ui.x) == toolAt(b, fixedRock)) {
		// The tool in hand: outlined (blue for fixed rock).
		col = mix(col, int(ui.x) == WALL ? FIXED : SELECTED, smoothstep(2.0, 0.5, abs(box - 1.5)));
	}
	return col;
}

// What's drawn over the rest: while the brush is being sized, a circle as
// big as it, out from its button; while painting, its outline round each
// finger.
vec3 overlay(vec3 col, vec2 fragCoord, vec2 res, vec4 ui, vec4 sizing) {
	float r = ui.y * CELL;
	if (pointerCount > 0 && sizing.z > 0.5) {
		float d = abs(distance(fragCoord, brushButton(res)) - r);
		return mix(col, SELECTED, smoothstep(2.5, 1.0, d));
	}
	if (ui.w < 0.5) {
		for (int i = 0; i < 10; ++i) {
			if (i >= pointerCount) {
				break;
			}
			if (pointers[i].y >= res.y * PALETTE) {
				float d = abs(distance(fragCoord, pointers[i].xy) - r);
				col = mix(col, int(ui.x) == WALL ? FIXED : SELECTED, 0.5 * smoothstep(1.5, 0.5, d));
			}
		}
	}
	return col;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 res = iResolution.xy;
	ivec2 grid = gridSize(res);
	int floorRow = floorRowOf(res);
	vec4 ui = texelFetch(iChannel1, ivec2(UI_TEXEL, int(res.y) - 1), 0);
	vec4 sizing = texelFetch(iChannel1, ivec2(SIZE_TEXEL, int(res.y) - 1), 0);
	if (fragCoord.y < res.y * PALETTE) {
		bool clearing = texelFetch(iChannel1, ivec2(TOUCH_TEXEL, int(res.y) - 1), 0).w > 0.5;
		bool fixedRock = texelFetch(iChannel1, ivec2(ROCK_TEXEL, int(res.y) - 1), 0).x > 0.5;
		fragColor = vec4(palette(fragCoord, res, ui, clearing, fixedRock), 1.0);
		fragColor.rgb = overlay(fragColor.rgb, fragCoord, res, ui, sizing);
		return;
	}
	vec2 at = fragCoord / CELL;
	// The sky.
	vec3 col = mix(vec3(0.86, 0.87, 0.82), vec3(0.47, 0.64, 0.88), smoothstep(0.0, 1.0, fragCoord.y / res.y));
	ivec2 c = ivec2(floor(at));
	if (!inside(c, grid, floorRow) || load(iChannel0, c).mat == WALL) {
		// Walls (and past the grid's edges).
		fragColor = vec4(bricks(at), 1.0);
		return;
	}
	vec4 field, gx, gy;
	vec2 flow;
	float pressure, grainDist;
	vec3 bulge;
	ivec2 grain, stone;
	fields(at, grid, floorRow, field, gx, gy, flow, pressure, bulge, grain, grainDist, stone);
	// Water under the rest: translucent; a deeper blue the harder it's
	// pressed (as deep), paler and whiter the faster it flows; a little
	// glint of light where its surface curves, foamy at its edges with the
	// air; and faintly, ripples carried along with it. (Its edge counted
	// with the jelly and rock beside it, so there's no gap between them,
	// flickering.)
	float solids = field.z + field.w;
	if (field.y > 0.05) {
		float edge = smoothstep(0.2, 0.35, field.y + solids) * smoothstep(0.05, 0.15, field.y);
		vec2 v = flow / field.y;
		float pressed = smoothstep(0.0, PRESSED, pressure / field.y);
		vec3 water = mix(vec3(0.3, 0.62, 0.95), vec3(0.03, 0.2, 0.58), pressed);
		water = mix(water, vec3(0.78, 0.93, 1.0), 0.65 * smoothstep(0.03, 0.4, length(v)));
		water += RIPPLES * vec3(0.4, 0.6, 0.8) * ripples(at, v) * smoothstep(0.3, 0.8, field.y);
		water = daylight(water, rounded(field.y + solids, vec2(gx.y + gx.z + gx.w, gy.y + gy.z + gy.w), 0.15, 0.9, 1.5), 0.5, 60.0);
		col = mix(col, water, 0.85 * edge);
		col += vec3(0.25) * smoothstep(0.45, 0.25, field.y) * (1.0 - smoothstep(0.05, 0.25, solids)) * edge;
	}
	// Jelly: a smooth, glossy dome (its bulge, as smoothly as its field:
	// the square root of its average, and that's gradient), rounded off at
	// its edges.
	if (field.z > 0.25) {
		vec2 g = vec2(gx.z, gy.z);
		float h = bulge.x / field.z;
		vec2 dh = (bulge.yz - h * g) / field.z;
		vec2 slope = DOME * 0.5 * dh / sqrt(h + 0.5) + rounded(field.z, g, 0.2, 1.0, 1.0);
		vec3 jelly = daylight(vec3(0.93, 0.33, 0.62), slope, 0.8, 40.0);
		col = mix(col, jelly, smoothstep(0.25, 0.4, field.z));
	}
	// Rock: its colour where each point is in its shape (from the nearest
	// bit of it - where it was, and how far it's turned), so it moves and
	// turns with it; rounded, bumpy as its colour goes (its slope turned
	// back from its shape to the screen), and a little shiny.
	if (field.w > 0.3) {
		vec4 s = texelFetch(iChannel1, stone, 0);
		mat2 turn = notJelly(s) ? mat2(1.0) : mat2(cos(s.z), -sin(s.z), sin(s.z), cos(s.z));
		vec2 r = notJelly(s) ? at : s.xy + turn * (at - load(iChannel0, stone).X);
		vec2 slope = rounded(field.w, vec2(gx.w, gy.w), 0.3, 1.0, 3.0) + BUMPS * transpose(turn) * stoneSlope(r);
		vec3 rock = daylight(stoneColour(r), slope, 0.15, 12.0);
		col = mix(col, rock, smoothstep(0.3, 0.4, field.w));
	}
	// Sand: each grain its own colour (carried along with it, in C), darker
	// as it's wetter; matt, lit as the heap slopes - and where it's thinned
	// out (spraying), its grains apart, each as big as there's sand in its
	// cell. (Not the slivers - a haze.)
	float grainM = load(iChannel0, grain).M;
	float grainSize = GRAIN * sqrt(grainM);
	float cover = max(smoothstep(0.25, 0.38, field.x),
			smoothstep(0.1, 0.2, grainM) * smoothstep(0.5 / CELL, -0.5 / CELL, grainDist - grainSize));
	if (cover > 0.0) {
		vec4 e = texelFetch(iChannel1, grain, 0);
		vec3 sand = sandColour(e.y > 0.5 ? e.y - 1.0 : 0.5) * (1.0 - 0.45 * wetness(e));
		sand = daylight(sand, rounded(field.x, vec2(gx.x, gy.x), 0.2, 1.0, 2.0), 0.0, 1.0);
		col = mix(col, sand, cover);
	}
	col = overlay(col, fragCoord, res, ui, sizing);
	fragColor = vec4(col, 1.0);
}

//// Common
// The packing needs 24-bit ints, more than mediump promises.
precision highp float;
precision highp int;

// Screen (render) pixels a simulation cell: bigger is faster and chunkier.
#define CELL 3.0
// The palette's height, as a share of the screen's.
#define PALETTE 0.08
#define GRAVITY 0.0015
// How much heavier sand is than water (so it settles out of it).
#define SAND_WEIGHT 2.6
// Pressure a unit of over-packing makes, per material.
#define WATER_STIFFNESS 0.5
#define SAND_STIFFNESS 0.6
#define JELLY_STIFFNESS 0.5
// Settled sand rolls off what it rests on, down to the side, where it's
// open: this fast (cells a step), and as likely a step - dry, damp (it
// stands up in clumps) and soaked (it slumps).
#define ROLL 0.1
#define DAMP_ROLL 0.003
#define SOAKED_ROLL 0.5
// How much flowing sand takes on the velocity of the sand (and walls) it
// rubs against, a step: so it pours, and doesn't splash.
#define SAND_GRIP 0.15
// How fast settled sand eases to the middle of its cell, a step.
#define CENTRE 0.2
// How hard water drags sand in it along, and how fast sand sinks
// through it (cells a step).
#define SAND_DRAG 0.25
#define SINK 0.02
// How slow sand moves to count as settled (for water and jelly beside it,
// and sand on jelly).
#define SETTLED 0.02
// Damp sand: how far it reaches out sideways from what holds it up, in
// cells (it stands in steep faces and overhangs, and crumbles), and how
// hard it grips the damp sand beside it (it holds together in clumps).
#define OVERHANG 3.0
#define DAMP_GRIP 0.35
// How much dry sand flying sprays out (of its speed, a step).
#define SPRAY 0.015
// How fast sand soaks up the water beside it, and how much water (in
// cells' worth) soaks a cell of sand.
#define SOAK 0.01
#define SOAKS_UP 0.4
// How much jelly takes on its neighbours' velocity a step: other jelly's
// (it wobbles, rather than bouncing apart), and anything else's.
#define JELLY_GRIP 0.45
// How hard thinned-out jelly pulls itself back together (inside it: at
// its edge, pulling made it clump into frills, so there it's its volume -
// J, see jellyF() - that holds it); how hard that volume's held, and how
// far below what it was it pulls back no harder; and how fast J's put
// right by how packed it is (its sum of velocity gradients drifts).
#define JELLY_COHESION 0.8
#define JELLY_BULK 0.5
#define JELLY_GIVE 0.8
#define JELLY_SETTLE 0.01
#define MAX_GRIP 0.12
#define WATER_VISCOSITY 0.05
// The jelly's stiffness against shear, and how far it stretches (or
// squashes) before it gives.
#define JELLY_SHEAR 0.45
#define JELLY_SQUASH 0.4
#define JELLY_STRETCH 2.5
// How hard jelly's pulled to the jelly around it, in something else.
#define JELLY_PULL 0.12
// Moving rock: how much heavier than water it is; its stiffness against
// packing; how much of its velocity that isn't the rock around it moving
// as one (out to two cells, turning and all) it loses a step, and how much
// of the way back to its place in its shape it's moved to go a step; how
// far apart in their shape bits of rock are, at most, to be the same rock;
// and its top speed (cells a step). And how much it turns as the rock
// around it's turned, as well as as the rock about it does, and how much
// that's evened out across it a step (see rock() in C).
#define ROCK_WEIGHT 2.5
#define ROCK_STIFFNESS 1.0
#define RIGID 0.8
#define SHAPE 0.3
#define ROCK_TURN 1.0
#define TURN_SMOOTH 0.5
#define ROCK_BODY 3.5
#define ROCK_VMAX 0.5
// How much of its bounce off what it lands on rock loses a step, and of
// its rolling (or sliding) on it.
#define ROCK_THUD 0.5
#define ROCK_DRAG 0.15
// How much of its turning rock loses a step (so it doesn't roll on and
// on); and how slow rock that's on something stops on it (cells a step).
#define ROCK_SPIN 0.01
#define ROCK_STICK 0.1
// And how slow it comes to rest on something still (see A).
#define ROCK_REST 0.04
// How hard a sliver of rock (or rock in something else) is pulled back to
// the rock around it.
#define ROCK_PULL 0.15
// How much there has to be in a cell to keep the others out of it.
#define KEEP_OUT 0.5
// Pressure between different materials, so they keep apart.
#define APART 0.1
// How much bigger than a cell a blob's box is as it moves (water's; packed
// jelly's).
#define SPREAD 0.1
#define JELLY_SPREAD 0.25
// How much pressure waves are damped.
#define DAMPING 0.15
// Cells a step, at most: anything, and jelly.
#define VMAX 0.85
#define JELLY_VMAX 0.3
// The brush's starting radius, and the biggest it's sized to, in cells;
// and how much of it rock's rough edge eats into, at most.
#define BRUSH 5.0
#define BRUSH_MAX 40.0
#define ROUGH 0.5
// How far a blob reaches when drawn, in cells.
#define BLOB 1.8
// How big a grain of sand's drawn, in cells, with a cell's worth of sand.
#define GRAIN 0.6
// Water's ripples: how big (cells), how long each layer's carried along
// before it fades (seconds) and how strongly they shade it (faintly: they
// don't quite move with it).
#define RIPPLE_SIZE 5.0
#define RIPPLE_TIME 0.8
#define RIPPLES 0.08
// How deep water's at its deepest blue (in cells' weight on it); and how
// high jelly's drawn bulging, as a share of how wide it is (see dome()).
#define PRESSED 40.0
#define DOME 0.6
// How deep rock's bumps look.
#define BUMPS 1.2

uniform vec3 pointers[10];
uniform int pointerCount;

const int EMPTY = 0;
const int WALL = 1;
const int SAND = 2;
const int WATER = 3;
const int JELLY = 4;
const int ROCK = 5;
// Not a material: the tool that empties cells.
const int ERASE = 15;
const int TOOLS = 5;
// The palette state's texels along C's top row: x = tool, y = brush radius,
// z = INIT once set up, w = 1 while a touch that began on the palette lasts;
// and the touch's: the last step's pointer count, when it began, the
// palette button it began on (-1 none), and 1 while it's clearing
// everything (Erase held).
const int UI_TEXEL = 0;
const int TOUCH_TEXEL = 1;
// And where a touch on the brush button began (xy), and 1 (z) once it's
// dragged out from it, sizing the brush.
const int SIZE_TEXEL = 2;
// And 1 (x) while the Rock button paints fixed rock (walls).
const int ROCK_TEXEL = 3;
// The selected tool's outline, and the brush's - fixed rock's.
const vec3 SELECTED = vec3(1.0, 0.85, 0.2);
const vec3 FIXED = vec3(0.35, 0.75, 1.0);
const float INIT = 7.0;
// Flags water, jelly and rock keep in their tint (which they don't show):
// its box isn't spread; it's beside something else; and (jelly and rock)
// it's been painted this touch, and is held where it was painted till the
// finger lifts.
const int TIGHT = 1;
const int AMID = 2;
const int HELD = 4;
// How long Erase is held to clear everything, in seconds.
const float CLEAR_HOLD = 0.6;
// And Rock, to swap it between moving and fixed.
const float SWAP_HOLD = 0.5;
// What C holds for rock: where it was in its shape (xy, in cells), how far
// it's turned since (z) and the weight on it (w). For anything else but
// jelly (whose F fills all four): how far sand is from what holds it up in
// x (see support()); sand's colour in y (1 + its shade, 0 to 1, dark to
// bright); the weight on sand or water (its own and what's above it) in z;
// NOT_JELLY plus sand's wetness in w.
const float NOT_JELLY = 1000.0;
const float UNSUPPORTED = 99.0;
// The highest jelly bulges (see dome()): a blob 40 cells across.
const float DOME_MAX = 400.0;

bool notJelly(vec4 c) {
	return c.w >= NOT_JELLY;
}

// What C holds for jelly: its deformation gradient F's shape, as its
// rotation and its stretch S (y, z: S's xx and xy - its yy is what makes
// its determinant 1); its volume, as a share of what it was (w, J -
// carried apart from F: F averaged as it moves shrinks, and would have
// pushed the jelly ever bigger); and how high it bulges there (see dome()),
// packed with the rotation into x: 8 x its square root (in 32nds) + the
// angle + 4.
float jellyAngle(vec4 e) {
	return mod(e.x, 8.0) - 4.0;
}

float jellyDome(vec4 e) {
	float s = floor(e.x / 8.0) / 32.0;
	return s * s;
}

vec4 jellyTexel(float angle, mat2 S, float J, float h) {
	return vec4(8.0 * floor(32.0 * sqrt(max(h, 0.0)) + 0.5) + angle + 4.0, S[0][0], S[1][0], J);
}

mat2 jellyF(vec4 e) {
	mat2 S = mat2(e.y, e.z, e.z, (1.0 + e.z * e.z) / e.y);
	float a = jellyAngle(e);
	return mat2(cos(a), sin(a), -sin(a), cos(a)) * S;
}

// Whether a material's firm - jelly and moving rock: it holds together,
// and keeps out of the rest's cells (and they out of its).
bool firm(int mat) {
	return mat == JELLY || mat == ROCK;
}

// Sand's wetness, 0 to 1, from its texel in C.
float wetness(vec4 c) {
	return notJelly(c) ? c.w - NOT_JELLY : 0.0;
}

// How far sand is from what holds it up, from its texel in C: 0 on a
// column of sand down to a wall or still jelly or rock, a cell more for
// each it reaches out sideways from one (damp), or UNSUPPORTED.
float support(vec4 c) {
	return notJelly(c) ? c.x : UNSUPPORTED;
}

// How damp sand is, for its crumbliness: not dry, most when damp, less
// again when it's saturated.
float damp(float wet) {
	return smoothstep(0.01, 0.08, wet) * (1.0 - smoothstep(0.8, 1.0, wet));
}

// The pressure in sand from its packing. (It doesn't pull: damp sand's
// cohesion, as tension, foamed - it holds together by its grip and its
// support instead.)
float sandPressure(float M) {
	return SAND_STIFFNESS * max(M - 1.0, 0.0);
}

// The brush button's centre.
vec2 brushButton(vec2 res) {
	float w = res.x / float(TOOLS + 1);
	return vec2((float(TOOLS) + 0.5) * w, 0.5 * res.y * PALETTE);
}

// The tool on palette button b (the Rock button's moving rock, or fixed
// when it's swapped).
int toolAt(int b, bool fixedRock) {
	return b == 0 ? SAND : b == 1 ? WATER : b == 2 ? JELLY : b == 3 ? (fixedRock ? WALL : ROCK) : ERASE;
}

struct Particle {
	vec2 X; // centre of mass, in cells
	vec2 V; // cells a step
	float M; // mass: 1 is a cell full at rest
	int mat;
	int tint; // 0-7: water's, jelly's and rock's flags (TIGHT, AMID)
	int imp; // 0-7, how hard it's just been hit (for sound)
};

float hash12(vec2 p) {
	vec3 p3 = fract(vec3(p.xyx) * 0.1031);
	p3 += dot(p3, p3.yzx + 33.33);
	return fract((p3.x + p3.y) * p3.z);
}

// Smooth noise, about 1 across a unit.
float smoothNoise2(vec2 x) {
	vec2 i = floor(x), f = fract(x);
	f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
	return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), f.x),
			mix(hash12(i + vec2(0.0, 1.0)), hash12(i + 1.0), f.x), f.y);
}

// A new grain of sand's shade, 0 to 1: mostly middling, now and then
// darker or brighter.
float newShade(vec2 seed) {
	return 0.5 + 0.5 * (hash12(seed * 0.71 + 3.1) + hash12(seed * 1.37 + 7.7) - 1.0);
}

// The grid: whole cells, below the buffers' top row (C's state).
ivec2 gridSize(vec2 res) {
	return ivec2(floor(res.x / CELL), floor((res.y - 1.0) / CELL));
}

// The first row above the palette.
int floorRowOf(vec2 res) {
	return int(ceil(res.y * PALETTE / CELL));
}

bool inside(ivec2 c, ivec2 grid, int floorRow) {
	return c.x >= 0 && c.y >= floorRow && c.x < grid.x && c.y < grid.y;
}

// Every texel holds integers below 2^24, exact in a float: x the centre's
// offset in the cell (12 bits a coordinate), y and z the velocity, w the
// mass (14 bits, 1/2048ths), material (4), tint (3) and impact (3).
vec4 pack(Particle p, ivec2 c) {
	if (p.mat == EMPTY) {
		return vec4(0.0);
	}
	vec2 o = clamp(p.X - (vec2(c) + 0.5), -0.5, 0.5);
	vec2 q = floor((o + 0.5) * 4095.0 + 0.5);
	// Rounded at random (afresh each step), up as often as its fraction
	// says, so slivers don't add mass on average.
	float m = clamp(floor(p.M * 2048.0 + hash12(vec2(c) + fract(float(iFrame) * vec2(0.7548, 0.5698)) * 4096.0)),
			0.0, 16383.0);
	if (m < 1.0) {
		return vec4(0.0);
	}
	return vec4(q.x + q.y * 4096.0, p.V,
			m + float(p.mat) * 16384.0 + float(p.tint) * 262144.0 + float(p.imp) * 2097152.0);
}

Particle unpack(vec4 d, ivec2 c) {
	Particle p;
	// (Exact already: past 2^23 adding 0.5 would round odd ones up.)
	int a = int(d.x);
	int b = int(d.w);
	p.X = vec2(c) + vec2(float(a & 4095), float(a >> 12)) / 4095.0;
	p.V = d.yz;
	p.mat = (b >> 14) & 15;
	p.M = p.mat == EMPTY ? 0.0 : float(b & 16383) / 2048.0;
	p.tint = (b >> 18) & 7;
	p.imp = (b >> 21) & 7;
	return p;
}

Particle load(sampler2D ch, ivec2 c) {
	return unpack(texelFetch(ch, c, 0), c);
}

Particle emptyAt(ivec2 c) {
	return Particle(vec2(c) + 0.5, vec2(0.0), 0.0, EMPTY, 0, 0);
}

Particle wallAt(ivec2 c) {
	return Particle(vec2(c) + 0.5, vec2(0.0), 1.0, WALL, 0, 0);
}

// The box a blob spreads over as it moves: a little more than a cell, so a
// little of every cell's mass spreads to its neighbours each step - which
// evens out dense and sparse rows (that the pressure, a central
// difference, can't see) - but less for a lone sliver, which would only
// fray.
float spread(Particle p) {
	// (Water's and jelly's: not sand's, which would blur a heap; nor
	// water's beside sand or jelly - each cell holds one material, picked
	// at random by how much of each lands on it, so what's spread on them
	// would turn their cells to water and back, now and then, all the
	// time.)
	// Sand's is only as big as the sand in it: a thinned-out cell's full
	// box would overhang what's next to it, packing it, and sand pushed
	// apart where it's packing, so a falling block would swell up.
	// (Rock's and jelly's too: they'd fray, a halo of slivers spread out
	// round them. Packed jelly's is spread, though, as water's is but more:
	// else packed and sparse columns of it grew side by side, till deep
	// jelly broke up into bubbles.)
	if (p.mat == SAND || p.mat == ROCK) {
		return clamp(p.M, 0.3, 1.0);
	} else if (p.mat == JELLY) {
		return p.M < 1.0 ? max(p.M, 0.3) : 1.0 + JELLY_SPREAD * smoothstep(1.0, 1.05, p.M);
	}
	return p.mat == WATER && (p.tint & TIGHT) == 0 ? 1.0 + SPREAD * smoothstep(0.1, 0.5, p.M) : 1.0;
}

// The part of q's box, moved by its velocity, landing on cell c - or on
// what c gathers of the walls next to it, lo to hi (see reach()): (centre
// of that part, share of q's mass).
vec3 landing(Particle q, vec2 cellLo, vec2 cellHi) {
	vec2 x = q.X + q.V;
	float k = spread(q);
	vec2 lo = max(x - 0.5 * k, cellLo);
	vec2 hi = min(x + 0.5 * k, cellHi);
	vec2 size = max(hi - lo, 0.0);
	return vec3(0.5 * (lo + hi), size.x * size.y / (k * k));
}

//// Buffer A
///float32
uniform sampler2D iChannel0; ///buf:B;
uniform sampler2D iChannel1; ///buf:C;

// Forces: each blob's velocity from its neighbours' pressure, cohesion,
// friction and (jelly) elastic stress, gravity; sand settling; the walls
// (and jelly and the rest keeping apart); and painting.

mat2 loadF(ivec2 c) {
	vec4 f = texelFetch(iChannel1, c, 0);
	return notJelly(f) ? mat2(1.0) : jellyF(f);
}

float wetAt(ivec2 c) {
	return wetness(texelFetch(iChannel1, c, 0));
}

// The stress a blob pushes its neighbours away with (a pressure where it's
// a fluid): its over-packing (stretched jelly pulls), and the jelly's
// elastic resistance to shear (fixed corotated, from its deformation
// gradient F; its volume is its density's).
mat2 stress(Particle p, ivec2 c) {
	if (p.mat == WALL) {
		return mat2(0.0);
	} else if (p.mat == WATER) {
		return mat2(WATER_STIFFNESS * max(p.M - 1.0, -0.01));
	} else if (p.mat == SAND) {
		return mat2(sandPressure(p.M));
	} else if (p.mat == ROCK) {
		// (Its shape's held below.)
		return mat2(ROCK_STIFFNESS * max(p.M - 1.0, 0.0));
	}
	// (F's only its shape - see jellyF().)
	mat2 F = loadF(c);
	// R, F's rotation (its polar decomposition, in 2D).
	float a = atan(F[0][1] - F[1][0], F[0][0] + F[1][1]);
	mat2 R = mat2(cos(a), sin(a), -sin(a), cos(a));
	mat2 cauchy = 2.0 * JELLY_SHEAR * (F - R) * transpose(F);
	vec4 e = texelFetch(iChannel1, c, 0);
	float J = notJelly(e) ? 1.0 : e.w;
	// (Packed, it's mostly J that pushes back: its packing pushing as hard
	// as well, it was stiffer than it could take - deep jelly broke up.)
	return -cauchy + mat2(JELLY_STIFFNESS * max(p.M - 1.0, (p.tint & TIGHT) != 0 ? 0.0 : -JELLY_COHESION) * (p.M > 1.0 ? 0.5 : 1.0)
			+ JELLY_BULK * max(1.0 / J - 1.0, -JELLY_GIVE));
}

// Where rock q at c was in its shape (C's xy - or, just painted, where it
// is).
vec2 restOf(ivec2 c, Particle q) {
	vec4 s = texelFetch(iChannel1, c, 0);
	return notJelly(s) ? q.X : s.xy;
}

// Whether rock p at c and q at n are the same rock: next to each other in
// its shape. (Two rocks, landing on each other, don't stick together.)
bool sameRock(ivec2 c, Particle p, ivec2 n, Particle q) {
	vec2 r = restOf(n, q) - restOf(c, p);
	return dot(r, r) < ROCK_BODY * ROCK_BODY;
}

bool solid(ivec2 c, ivec2 grid, int floorRow) {
	return !inside(c, grid, floorRow) || load(iChannel0, c).mat == WALL;
}

Particle at(ivec2 c, ivec2 grid, int floorRow) {
	return inside(c, grid, floorRow) ? load(iChannel0, c) : wallAt(c);
}

// How hard jelly (or rock) p and q beside it pull each other together:
// where either's thinned out, or in something else - as a pair, so they
// don't move off together, nor hold themselves up. (Pulled only one way, a
// blob falling - thinned out where it leads - held itself up, hanging in
// the air; and jelly and rock in water, pulled in where they're under it,
// floated up out of it.) Shared by the pair as their masses are, as
// pressure is.
vec2 pull(Particle p, Particle q) {
	float pa = (p.tint & AMID) != 0 ? 1.0 : p.mat == ROCK ? smoothstep(1.0, 0.6, p.M) : 0.0;
	float qa = (q.tint & AMID) != 0 ? 1.0 : q.mat == ROCK ? smoothstep(1.0, 0.6, q.M) : 0.0;
	return max(pa, qa) * 2.0 * q.M / (p.M + q.M) * (q.X - p.X) / 12.0;
}

// Whether sand can settle on q: a wall, or most of a cell of anything but
// water.
bool holds(Particle q) {
	return q.mat == WALL || q.mat != EMPTY && q.mat != WATER && q.M > 0.5;
}

// Whether sand can roll into q: (next to) empty, or water.
bool open(Particle q) {
	return q.mat == EMPTY || q.mat == WATER || q.mat != WALL && q.M <= 0.5;
}

// What the opening scene has at c: a sand heap, a pool of water held up on
// a shelf, a block of jelly.
Particle scene(ivec2 c, ivec2 grid, int floorRow) {
	vec2 u = (vec2(c) + 0.5 - vec2(0.0, float(floorRow))) / vec2(grid - ivec2(0, floorRow));
	float h = hash12(vec2(c) * 1.37);
	Particle p = emptyAt(c);
	if (u.y < 0.3 * (1.0 - abs(u.x - 0.22) / 0.2)) {
		p = Particle(vec2(c) + 0.5, vec2(0.0), 1.0, SAND, 0, 0);
	} else if (u.x > 0.55 && u.x < 0.97 && u.y > 0.5 && u.y < 0.53) {
		p = wallAt(c);
	} else if (u.x > 0.62 && u.x < 0.97 && u.y > 0.53 && u.y < 0.75) {
		p = Particle(vec2(c) + 0.5, vec2(0.0), 1.0, WATER, 0, 0);
	} else if (abs(u.x - 0.42) < 0.08 && abs(u.y - 0.6) < 0.06) {
		p = Particle(vec2(c) + 0.5, vec2(0.0), 1.0, JELLY, 0, 0);
	}
	return p;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 c = ivec2(fragCoord);
	vec2 res = iResolution.xy;
	ivec2 grid = gridSize(res);
	int floorRow = floorRowOf(res);
	if (!inside(c, grid, floorRow)) {
		fragColor = vec4(0.0);
		return;
	}
	vec4 ui = texelFetch(iChannel1, ivec2(UI_TEXEL, int(res.y) - 1), 0);
	if (ui.z != INIT) {
		fragColor = pack(scene(c, grid, floorRow), c);
		return;
	}
	if (texelFetch(iChannel1, ivec2(TOUCH_TEXEL, int(res.y) - 1), 0).w > 0.5) {
		// Erase held: everything goes.
		fragColor = vec4(0.0);
		return;
	}
	Particle p = load(iChannel0, c);

	// Painting, with every finger, unless the touch began on the palette:
	// over whatever's there (but walls, which only fixed rock and Erase
	// change), a step at a time - emptied, then painted, so C has nothing
	// of what was there to carry over to what's painted. Jelly and rock fill
	// every cell, held where they're painted till the finger lifts, so
	// they're placed as they're drawn: a smooth blob of jelly; a rock, its
	// edge left rough (the brush eaten into here and there, differently
	// each touch).
	int tool = int(ui.x);
	bool touching = ui.w < 0.5 && pointerCount > 0;
	if (touching) {
		vec2 centre = vec2(c) + 0.5;
		vec2 seed = centre + 97.0 * fract(texelFetch(iChannel1, ivec2(TOUCH_TEXEL, int(res.y) - 1), 0).y * vec2(0.731, 0.377));
		float rough = 0.7 * smoothNoise2(seed * 0.22) + 0.3 * smoothNoise2(seed * 0.7);
		for (int i = 0; i < 10; ++i) {
			if (i >= pointerCount) {
				break;
			}
			float reach = tool == ROCK ? ui.y * (1.0 - ROUGH * rough) : ui.y;
			if (pointers[i].y < res.y * PALETTE || distance(centre, pointers[i].xy / CELL) > reach) {
				continue;
			}
			float h = hash12(vec2(c) + float(iFrame) * vec2(0.37, 0.71));
			if (tool == ERASE) {
				p = emptyAt(c);
			} else if (tool == WALL) {
				p = wallAt(c);
			} else if (p.mat == tool || p.mat == WALL) {
				continue;
			} else if (p.mat != EMPTY) {
				p = emptyAt(c);
			} else if (firm(tool)) {
				p = Particle(centre, vec2(0.0), 1.0, tool, HELD, 0);
			} else if (h < (tool == SAND ? 0.25 : 0.5)) {
				p = Particle(centre + (vec2(h, fract(h * 7.3)) - 0.5) * 0.3, vec2(0.0), 1.0, tool, 0, 0);
			}
		}
	}
	// (Let go once the finger lifts, or paints something else. Till then,
	// it's as painted - a whole cell's worth, in its middle, its box not
	// spread: else the jelly's mass, spread out from inside it and not
	// pushed back, piles up round its edge, and bursts out into frills
	// when it's let go.)
	int held = touching && p.mat == tool ? p.tint & HELD : 0;
	if (held != 0) {
		p = Particle(vec2(c) + 0.5, vec2(0.0), 1.0, p.mat, TIGHT + HELD, 0);
	}
	if (p.mat == EMPTY || p.mat == WALL) {
		fragColor = pack(p, c);
		return;
	}

	vec2 push = vec2(0.0);
	vec2 grip = vec2(0.0);
	// For sand: how much of what's around it is water.
	vec2 wateriness = vec2(0.0);
	// And how the water around it moves.
	vec2 waterV = vec2(0.0);
	// For water: how much the sand beside it soaks up, and whether there's
	// sand or jelly beside it.
	float soaked = 0.0;
	bool beside = false;
	// For jelly and rock: whether there's something else beside it (but
	// walls).
	bool amid = false;
	// How many neighbours it has.
	int around = 0;
	mat2 own = stress(p, c);
	// For sand: how far it's flying, dry (not held up): it doesn't grip the
	// sand beside it, and sprays out (see below).
	vec4 e = texelFetch(iChannel1, c, 0);
	float flying = p.mat == SAND && support(e) >= UNSUPPORTED ? 1.0 - damp(wetness(e)) : 0.0;
	for (int j = -1; j <= 1; ++j) {
		for (int i = -1; i <= 1; ++i) {
			ivec2 n = c + ivec2(i, j);
			if (n == c) {
				continue;
			}
			Particle q = inside(n, grid, floorRow) ? load(iChannel0, n) : wallAt(n);
			if (q.mat == EMPTY) {
				continue;
			}
			++around;
			vec2 dx = q.X - p.X;
			float w = exp(-dot(dx, dx));
			// A wall pushes back as hard as it's pushed (the floor holds
			// a heap up) but never pulls (so water slides off it), and
			// different materials push apart where they overlap.
			float squeeze = 0.5 * (own[0][0] + own[1][1]);
			mat2 pair = q.mat == WALL ? own + mat2(2.0 * max(squeeze, 0.0) - squeeze) : own + stress(q, n);
			if (q.mat != p.mat && q.mat != WALL) {
				pair += mat2(APART * max(0.0, 1.0 - length(dx)));
			}
			// Shared by the pair as their masses are, so a sliver of
			// mass isn't flung.
			float share = 2.0 * q.M / (p.M + q.M);
			vec2 f = -0.5 * w * share * (pair * dx);
			push += f;
			// Friction: sand grips sand; jelly grips, water's a little
			// viscous; water soaks into sand.
			vec2 dv = q.V - p.V;
			if (p.mat == SAND) {
				wateriness += vec2(q.mat == WATER ? w : 0.0, w);
				// Grains rubbing on grains (and walls) as it flows.
				if (q.mat == WATER) {
					waterV += w * q.V;
				} else {
					grip += w * share * (q.mat == SAND ? mix(SAND_GRIP * (1.0 - flying), DAMP_GRIP, damp(wetAt(c)) * damp(wetAt(n))) : SAND_GRIP) * dv;
				}
			} else {
				if (p.mat == WATER && q.mat == SAND) {
					soaked += SOAK * w * (1.0 - wetAt(n));
				}
				// (Water slides off walls.)
				if (p.mat != WATER || q.mat != WALL) {
					grip += w * share * (p.mat == WATER || q.mat == WATER ? WATER_VISCOSITY
							: q.mat == JELLY && p.mat == JELLY ? JELLY_GRIP : MAX_GRIP) * dv;
				}
				if (p.mat == WATER && q.mat != WATER && q.mat != WALL || p.mat == JELLY && q.mat != JELLY) {
					beside = true;
				}
				if (firm(p.mat) && q.mat != p.mat && q.mat != WALL) {
					amid = true;
				}
			}
			// Artificial bulk viscosity: pairs closing in on each other
			// are slowed, damping pressure waves (not the flow).
			float closing = dot(dv, dx) / max(dot(dx, dx), 1e-4);
			if (closing < 0.0 && q.mat != WALL) {
				grip += w * share * DAMPING * closing * dx;
			}
		}
	}
	if (p.mat == JELLY) {
		// Jelly that's thinned out, or in something else, and the jelly
		// around it, out to two cells, pull each other together: bits
		// knocked off back in, gaps closed, the rest kept out. (Not packing
		// jelly in the open, which it would only squash.)
		for (int j = -2; j <= 2; ++j) {
			for (int i = -2; i <= 2; ++i) {
				ivec2 n = c + ivec2(i, j);
				if (n == c || !inside(n, grid, floorRow)) {
					continue;
				}
				Particle q = load(iChannel0, n);
				if (q.mat == JELLY) {
					grip += JELLY_PULL * pull(p, q);
				}
			}
		}
	} else if (p.mat == ROCK) {
		// Rock keeps its shape: moved back toward its place in the shape
		// of the rock around it (the same rock, out to two cells), as
		// that's turned (shape matching); and it moves as one with it.
		// Their mass, and (about p, and where p was in its shape) their
		// centre and its in their shape, momentum, angular momentum and
		// moment of inertia, and how they're placed against their shape.
		vec2 rest = restOf(c, p);
		float total = p.M;
		vec2 centre = vec2(0.0), shaped = vec2(0.0), momentum = p.M * p.V;
		float spin = 0.0, inertia = 0.0;
		mat2 placed = mat2(0.0);
		for (int j = -2; j <= 2; ++j) {
			for (int i = -2; i <= 2; ++i) {
				ivec2 n = c + ivec2(i, j);
				if (n == c || !inside(n, grid, floorRow)) {
					continue;
				}
				Particle q = load(iChannel0, n);
				if (q.mat != ROCK) {
					continue;
				}
				vec2 r = restOf(n, q) - rest;
				if (dot(r, r) > ROCK_BODY * ROCK_BODY) {
					continue;
				}
				vec2 d = q.X - p.X;
				total += q.M;
				centre += q.M * d;
				shaped += q.M * r;
				momentum += q.M * q.V;
				spin += q.M * (d.x * q.V.y - d.y * q.V.x);
				inertia += q.M * dot(d, d);
				placed += q.M * outerProduct(d, r);
				// (A sliver of it - its place in the shape's only where it's
				// been spread to - and the rock beside it pull each other
				// together; as does rock in something else, so it's kept
				// out.)
				grip += ROCK_PULL * pull(p, q);
			}
		}
		if (total > p.M) {
			centre /= total;
			shaped /= total;
			vec2 v = momentum / total;
			// (About their centres, and their rotation from their shape -
			// with how far the whole rock's turned, as C has it: rotations
			// fitted only to the rock about it let a big rock bend, bit by
			// bit, slumping like dough as it landed on something.)
			placed -= total * outerProduct(centre, shaped);
			float a = atan(placed[0][1] - placed[1][0], placed[0][0] + placed[1][1]);
			vec4 turned = texelFetch(iChannel1, c, 0);
			if (!notJelly(turned)) {
				a = atan(sin(a) + ROCK_TURN * sin(turned.z), cos(a) + ROCK_TURN * cos(turned.z));
			}
			vec2 back = centre - mat2(cos(a), sin(a), -sin(a), cos(a)) * shaped;
			// (Its turning slowed a little, so it doesn't roll on and on.)
			float omega = (1.0 - ROCK_SPIN) * (spin - total * (centre.x * v.y - centre.y * v.x))
					/ max(inertia - total * dot(centre, centre), 0.5);
			p.V = mix(p.V, v + omega * vec2(centre.y, -centre.x) + SHAPE * back, RIGID);
			// Hardly bouncing back up off what it lands on, as it takes back
			// its shape (what's on it doesn't spring back up off it); nor
			// rolling on and on; and, slow, it stops on it.
			ivec2 n = c - ivec2(0, 1);
			Particle q = at(n, grid, floorRow);
			if (holds(q) && (q.mat != ROCK || !sameRock(c, p, n, q))) {
				p.V.x = mix(p.V.x, q.V.x, ROCK_DRAG);
				if (p.V.y > q.V.y) {
					p.V.y = mix(p.V.y, q.V.y, ROCK_THUD);
				}
				if (distance(p.V, q.V) < ROCK_STICK) {
					p.V = q.V;
				}
			}
		}
	}
	vec2 before = p.V;
	float weight = GRAVITY * (p.mat == SAND ? SAND_WEIGHT : p.mat == ROCK ? ROCK_WEIGHT : 1.0);
	p.V += push + grip + vec2(0.0, -weight);
	if (p.mat == SAND && wateriness.x > 0.0) {
		// Sand in water sinks through it, gently, at its own pace.
		float among = wateriness.x / wateriness.y;
		p.V = mix(p.V, waterV / wateriness.x + vec2(0.0, -SINK), SAND_DRAG * among);
	}
	// Water the sand beside it soaks up.
	p.M *= 1.0 - min(soaked, 0.5);
	if (p.mat != SAND) {
		// Water beside something else, and jelly at its edge, aren't spread
		// (see spread()); jelly and rock beside something else are pulled
		// in harder (see pull()).
		p.tint = (beside || p.mat == JELLY && around < 8 || held != 0 ? TIGHT : 0) + (amid ? AMID : 0) + held;
	}
	// Sand, settled - held up by what's below it, down to something solid
	// (or, damp, from the side; see support()): it rolls off it, down to
	// the side, where that's open; otherwise it stays put on it - unless
	// it's knocked harder than the weight on it holds it there, when it
	// slides on it, slowed as hard. Damp sand seldom rolls, soaked sand
	// more; saturated sand thinly spread through water doesn't settle
	// (mud). (Not on sand that's only nearly still: a falling block would
	// peel off it row by row.)
	if (p.mat == SAND) {
		float wet = wetness(e);
		if (flying > 0.0) {
			// Dry sand flying sprays out: each grain a little off its
			// neighbours' way, the faster it goes.
			vec2 r = vec2(hash12(vec2(c) * 1.91 + fract(float(iFrame) * vec2(0.1312, 0.4381)) * 731.0),
					hash12(vec2(c) * 2.57 + fract(float(iFrame) * vec2(0.6541, 0.2754)) * 613.0)) - 0.5;
			p.V += SPRAY * flying * length(p.V) * r;
		}
		// (Mud: saturated, and thinly spread through water - packing sand
		// settles, under water or not.)
		float mud = smoothstep(0.8, 1.0, wet) * smoothstep(0.5, 0.75, wateriness.x / max(wateriness.y, 1e-4));
		Particle below = at(c - ivec2(0, 1), grid, floorRow);
		if (mud < 0.5 && support(e) < UNSUPPORTED) {
			// (On what's below it, or held up from the side.)
			vec2 on = holds(below) ? below.V : vec2(0.0);
			vec2 slip = p.V - on;
			float hold = GRAVITY * max(e.z, p.M * SAND_WEIGHT) / max(p.M, 0.25);
			if (length(slip) <= hold) {
				bool left = open(at(c + ivec2(-1, -1), grid, floorRow)) && open(at(c + ivec2(-1, 0), grid, floorRow));
				bool right = open(at(c + ivec2(1, -1), grid, floorRow)) && open(at(c + ivec2(1, 0), grid, floorRow));
				float h = hash12(vec2(c) + fract(float(iFrame) * vec2(0.4142, 0.7321)) * 1013.0);
				float chance = mix(mix(1.0, DAMP_ROLL, damp(wet)), SOAKED_ROLL, smoothstep(0.8, 1.0, wet));
				// (Eased to its cell's middle: a box off it leaks slivers into
				// the cells beside it, where water's would turn to sand and
				// back.)
				p.V = on + CENTRE * (vec2(c) + 0.5 - p.X);
				if ((left || right) && h < chance) {
					float side = left && right ? (fract(h * 17.0) < 0.5 ? -1.0 : 1.0) : left ? -1.0 : 1.0;
					p.V.x += side * ROLL;
				}
			} else {
				// Sliding on it: slowed as much.
				p.V -= slip * (hold / length(slip));
			}
		}
	}
	// Rock resting on something still (the floor, or rock under it at
	// rest itself), and as good as still on it, is still: so a rock comes
	// to rest from the bottom up. (Else its weight, held up by its packing
	// - which only pushes back a step behind - kept it churning, never
	// quite still; a big rock seemed to boil.) Knocked, it's off again.
	if (p.mat == ROCK) {
		Particle q = at(c - ivec2(0, 1), grid, floorRow);
		if (holds(q) && length(q.V) < ROCK_REST && distance(p.V, q.V) < ROCK_REST) {
			p.V = q.V;
		}
	}
	// (Jelly no faster than it can take a knock without flying apart.)
	float most = p.mat == JELLY ? JELLY_VMAX : p.mat == ROCK ? ROCK_VMAX : VMAX;
	float speed = length(p.V);
	if (speed > most) {
		p.V *= most / speed;
	}

	if (held != 0) {
		p.V = vec2(0.0);
	}

	// Walls: it doesn't go into one - nor does its box (as it moves, B
	// spreads it over), so none of it lands on one. Their friction is
	// above.
	vec2 to = p.X + p.V;
	vec2 lo = vec2(-1e9), hi = vec2(1e9);
	float reach = 0.5 * spread(p);
	for (int s = 1; s <= 2; ++s) {
		if (lo.x < -1e8 && solid(c - ivec2(s, 0), grid, floorRow)) {
			lo.x = float(c.x - s + 1) + reach;
		}
		if (hi.x > 1e8 && solid(c + ivec2(s, 0), grid, floorRow)) {
			hi.x = float(c.x + s) - reach;
		}
		if (lo.y < -1e8 && solid(c - ivec2(0, s), grid, floorRow)) {
			lo.y = float(c.y - s + 1) + reach;
		}
		if (hi.y > 1e8 && solid(c + ivec2(0, s), grid, floorRow)) {
			hi.y = float(c.y + s) - reach;
		}
	}
	// Nor does jelly or rock go into the cell of something else beside it
	// (another rock too: their mass, landing on one cell, would be one
	// rock's, so bits of one broke off into the other, and squeezed into
	// one cell, it pushed them apart hard, flinging bits off), nor anything
	// else into theirs, nor water into sand that's settled - but
	// as far as that's moving away: they keep apart (each cell holds one
	// material, so where they'd mix, bits of jelly would be scattered
	// through the rest, and settled sand would keep turning to water and
	// back). Only kept from going further in, though, not put back out:
	// snapped back in a step, they'd jitter.
	vec2 apartLo = vec2(-1e9), apartHi = vec2(1e9);
	for (int k = 0; k < 4; ++k) {
		ivec2 e = k == 0 ? ivec2(1, 0) : k == 1 ? ivec2(-1, 0) : k == 2 ? ivec2(0, 1) : ivec2(0, -1);
		ivec2 n = c + e;
		if (!inside(n, grid, floorRow)) {
			continue;
		}
		Particle q = load(iChannel0, n);
		bool settled = p.mat == WATER && q.mat == SAND && length(q.V) < SETTLED;
		if (q.mat == EMPTY || q.mat == WALL || q.M < KEEP_OUT || q.mat == p.mat && (p.mat != ROCK || sameRock(c, p, n, q))
				|| !firm(p.mat) && !firm(q.mat) && !settled) {
			continue;
		}
		if (k == 0) {
			apartHi.x = min(apartHi.x, float(n.x) - reach + max(q.V.x, 0.0));
		} else if (k == 1) {
			apartLo.x = max(apartLo.x, float(c.x) + reach + min(q.V.x, 0.0));
		} else if (k == 2) {
			apartHi.y = min(apartHi.y, float(n.y) - reach + max(q.V.y, 0.0));
		} else {
			apartLo.y = max(apartLo.y, float(c.y) + reach + min(q.V.y, 0.0));
		}
	}
	lo = max(lo, min(apartLo, p.X));
	hi = min(hi, max(apartHi, p.X));
	vec2 kept = clamp(to, lo, max(lo, hi));
	if (kept != to) {
		p.V = kept - p.X;
	}

	// How hard it was hit: its change of speed, beyond gravity's.
	float hit = length(p.V - before - vec2(0.0, -weight));
	p.imp = held != 0 ? 0 : int(clamp(log2(1.0 + hit * 200.0) * 1.4, 0.0, 7.0));
	fragColor = pack(p, c);
}

//// Buffer B
///float32
uniform sampler2D iChannel0; ///buf:A;

// Reintegration: every blob moves as a box of material, and each cell
// gathers the parts of the boxes landing on it (their mass, centre of mass
// and momentum; the material, colour and impact of the biggest).

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 c = ivec2(fragCoord);
	vec2 res = iResolution.xy;
	ivec2 grid = gridSize(res);
	int floorRow = floorRowOf(res);
	if (!inside(c, grid, floorRow)) {
		fragColor = vec4(0.0);
		return;
	}
	Particle self = load(iChannel0, c);
	if (self.mat == WALL) {
		fragColor = pack(self, c);
		return;
	}
	// (A keeps the boxes out of walls - but for the odd corner, whose
	// sliver's lost.)
	vec2 cellLo = vec2(c), cellHi = vec2(c) + 1.0;
	float m = 0.0;
	vec2 X = vec2(0.0), V = vec2(0.0);
	Particle p = emptyAt(c);
	int hit = 0;
	// Each material's share of what lands.
	vec4 share = vec4(0.0);
	for (int j = -2; j <= 2; ++j) {
		for (int i = -2; i <= 2; ++i) {
			ivec2 n = c + ivec2(i, j);
			if (!inside(n, grid, floorRow)) {
				continue;
			}
			Particle q = load(iChannel0, n);
			if (q.mat == EMPTY || q.mat == WALL) {
				continue;
			}
			vec3 part = landing(q, cellLo, cellHi);
			float w = q.M * part.z;
			if (w <= 0.0) {
				continue;
			}
			m += w;
			X += w * part.xy;
			V += w * q.V;
			hit = max(hit, q.imp);
			int k = q.mat - SAND;
			share[k] += w;
		}
	}
	// A cell holds one material: one of those landing, at random as
	// likely as its share of the mass - so each material keeps its mass
	// on average.
	float r = hash12(vec2(c) + fract(float(iFrame) * vec2(0.6180, 0.7548)) * 997.0) * m;
	// (Never one with none landing - rounded, r can come out as much as all
	// of it, past the last share's end: water would turn to jelly.)
	int k = r < share.x || share.y + share.z + share.w <= 0.0 ? 0
			: r < share.x + share.y || share.z + share.w <= 0.0 ? 1
			: r < share.x + share.y + share.z || share.w <= 0.0 ? 2 : 3;
	p.mat = SAND + k;
	// (Its tint's its flags, which it keeps - see A. Sand's colour's in C.)
	p.tint = self.mat == p.mat ? self.tint : 0;
	if (m < 0.5 / 2048.0) {
		fragColor = pack(emptyAt(c), c);
		return;
	}
	p.X = X / m;
	p.V = V / m;
	p.M = m;
	p.imp = hit;
	fragColor = pack(p, c);
}

//// Buffer C
///float32
///stateRow:top
uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;
uniform sampler2D iChannel2; ///buf:C;

// What a blob carries besides its texel: the jelly's deformation gradient
// F, carried along as B carries the blobs and turned by the local velocity
// gradient; where rock was in its shape, and how far it's turned, carried
// along the same way; sand's wetness (x, NOT_JELLY in z and w, as anything
// else's),
// carried along too, and soaked up from the water beside it; and, along the
// top row, the palette's state.

// The velocity gradient around jelly (or rock) p at c (least squares, over
// the same next to it; a little less than that, where there's little).
mat2 velocityGradient(ivec2 c, Particle p, ivec2 grid, int floorRow) {
	mat2 dvdx = mat2(0.0), dxdx = mat2(0.2);
	for (int j = -1; j <= 1; ++j) {
		for (int i = -1; i <= 1; ++i) {
			ivec2 n = c + ivec2(i, j);
			if (n == c || !inside(n, grid, floorRow)) {
				continue;
			}
			Particle q = load(iChannel1, n);
			if (q.mat != p.mat) {
				continue;
			}
			vec2 dx = q.X - p.X;
			float w = exp(-dot(dx, dx));
			dvdx += w * outerProduct(q.V - p.V, dx);
			dxdx += w * outerProduct(dx, dx);
		}
	}
	return dvdx * inverse(dxdx);
}

// The weight on sand, water or rock at c: its own, and (as it was last
// step) the weight on what's just above it - so it reaches down a few cells
// a step. (Rock's, so sand under it holds it up - not knowing it's there,
// it'd slip out from under it, and the rock would sink down through it.)
float weightOn(ivec2 c, Particle p, ivec2 grid, int floorRow) {
	float own = p.M * (p.mat == SAND ? SAND_WEIGHT : p.mat == ROCK ? ROCK_WEIGHT : 1.0);
	ivec2 up = c + ivec2(0, 1);
	if (!inside(up, grid, floorRow)) {
		return own;
	}
	Particle q = load(iChannel1, up);
	vec4 above = texelFetch(iChannel2, up, 0);
	return own + ((q.mat == SAND || q.mat == WATER) && notJelly(above) ? above.z
			: q.mat == ROCK && !notJelly(above) ? above.w : 0.0);
}

// How far sand at c is from what holds it up (see support()): down a
// column of sand (looking four cells down a step, so it's known soon after
// it lands, or falls) to a wall or still jelly or rock; or, damp, out
// sideways from sand beside it that's held up, as far as it reaches.
float supportOf(ivec2 c, ivec2 grid, int floorRow, float wet, bool started) {
	float held = UNSUPPORTED;
	for (int k = 1; k <= 4; ++k) {
		ivec2 n = c - ivec2(0, k);
		Particle q = inside(n, grid, floorRow) ? load(iChannel1, n) : wallAt(n);
		if (q.mat == WALL || firm(q.mat) && q.M > 0.5 && length(q.V) < SETTLED) {
			held = 0.0;
			break;
		}
		if (q.mat != SAND || q.M <= 0.5 || k == 4) {
			// (How far the lowest sand of the column is.)
			ivec2 lowest = q.mat == SAND && q.M > 0.5 ? n : n + ivec2(0, 1);
			if (lowest != c && started) {
				held = support(texelFetch(iChannel2, lowest, 0));
			}
			break;
		}
	}
	float reach = floor(OVERHANG * damp(wet) + 0.5);
	for (int s = -1; s <= 1; s += 2) {
		ivec2 n = c + ivec2(s, 0);
		if (!started || !inside(n, grid, floorRow)) {
			continue;
		}
		Particle q = load(iChannel1, n);
		float far = support(texelFetch(iChannel2, n, 0)) + 1.0;
		if (q.mat == SAND && q.M > 0.5 && far <= reach) {
			held = min(held, far);
		}
	}
	return held;
}

// Sand's wetness, carried along with it as B carried it, and soaked up
// from the water beside it (as A has that water lose it); how far it is
// from what holds it up; and the weight on it.
vec4 sand(ivec2 c, Particle p, ivec2 grid, int floorRow, bool started) {
	float wet = 0.0, total = 0.0;
	// Its colour: one of the bits of sand landing's, at random as likely
	// as its share (each taking the place of the last as likely as it's
	// this share of what's landed so far) - so each grain keeps its own as
	// it goes. (Averaged, they'd all soon fade to the same.) Sand just
	// painted has none yet: it gets its own.
	float shade = 0.0;
	for (int j = -2; j <= 2; ++j) {
		for (int i = -2; i <= 2; ++i) {
			ivec2 n = c + ivec2(i, j);
			if (!inside(n, grid, floorRow)) {
				continue;
			}
			Particle q = load(iChannel0, n);
			if (q.mat != SAND) {
				continue;
			}
			float w = q.M * landing(q, vec2(c), vec2(c) + 1.0).z;
			if (w > 0.0) {
				vec4 was = texelFetch(iChannel2, n, 0);
				wet += w * (started ? wetness(was) : 0.0);
				total += w;
				vec2 seed = vec2(n) * 1.13 + vec2(c) * 0.29 + fract(float(iFrame) * vec2(0.3819, 0.9271)) * 557.0;
				if (hash12(seed) * total < w) {
					shade = started && notJelly(was) && was.y > 0.5 ? was.y : 1.0 + newShade(seed);
				}
			}
		}
	}
	wet = total > 0.0 ? wet / total : 0.0;
	float water = 0.0;
	for (int j = -1; j <= 1; ++j) {
		for (int i = -1; i <= 1; ++i) {
			ivec2 n = c + ivec2(i, j);
			if (n == c || !inside(n, grid, floorRow)) {
				continue;
			}
			Particle q = load(iChannel1, n);
			if (q.mat == WATER) {
				vec2 dx = q.X - p.X;
				water += SOAK * exp(-dot(dx, dx)) * q.M;
			}
		}
	}
	wet = clamp(wet + water * (1.0 - wet) / SOAKS_UP, 0.0, 1.0);
	return vec4(supportOf(c, grid, floorRow, wet, started), shade, weightOn(c, p, grid, floorRow), NOT_JELLY + wet);
}

// How high jelly at c bulges, for drawing it (squared, in cells): from
// what it was beside it, settling a little each step to h = R^2 - r^2 in a
// round blob of radius R (h's laplacian -4: each cell its neighbours'
// average plus one), 0 past its edge - so its square root, drawn, is a
// dome as high as the blob's wide.
float dome(ivec2 c, ivec2 grid, int floorRow, bool started) {
	float h = 0.0;
	for (int k = 0; k < 4; ++k) {
		ivec2 n = c + (k == 0 ? ivec2(1, 0) : k == 1 ? ivec2(-1, 0) : k == 2 ? ivec2(0, 1) : ivec2(0, -1));
		if (!inside(n, grid, floorRow) || load(iChannel1, n).mat != JELLY) {
			continue;
		}
		// (What was there may have been something else, by its texel
		// in C - rock's weight, say: kept within reason.)
		vec4 e = texelFetch(iChannel2, n, 0);
		h += started && !notJelly(e) ? min(jellyDome(e), DOME_MAX) : 0.0;
	}
	return 0.25 * h + 1.0;
}

// Where rock q at n (in A) was in its shape, and how far it's turned since
// (rock just painted has no shape yet: it's as it is).
vec2 shapeOf(ivec2 n, Particle q, bool started, out float turn) {
	vec4 s = texelFetch(iChannel2, n, 0);
	bool none = !started || notJelly(s);
	turn = none ? 0.0 : s.z;
	return none ? q.X : s.xy;
}

// Where rock at c was in its shape, and how far it's turned since: each
// part of the rock landing on it where it was in its shape (from where it
// was, turned back as far as it's turned), and turned as the rock around it
// turns. (Only the rock most of it's from: where two rocks meet, their
// places in their shapes, averaged, would be nowhere in either.)
vec4 rock(ivec2 c, Particle p, ivec2 grid, int floorRow, bool started) {
	float most = 0.0;
	vec2 whose = p.X;
	for (int j = -2; j <= 2; ++j) {
		for (int i = -2; i <= 2; ++i) {
			ivec2 n = c + ivec2(i, j);
			if (!inside(n, grid, floorRow)) {
				continue;
			}
			Particle q = load(iChannel0, n);
			float w = q.mat == ROCK ? q.M * landing(q, vec2(c), vec2(c) + 1.0).z : 0.0;
			if (w > most) {
				float turn;
				most = w;
				whose = shapeOf(n, q, started, turn);
			}
		}
	}
	vec2 rest = vec2(0.0), turned = vec2(0.0);
	float total = 0.0;
	for (int j = -2; j <= 2; ++j) {
		for (int i = -2; i <= 2; ++i) {
			ivec2 n = c + ivec2(i, j);
			if (!inside(n, grid, floorRow)) {
				continue;
			}
			Particle q = load(iChannel0, n);
			if (q.mat != ROCK) {
				continue;
			}
			vec3 part = landing(q, vec2(c), vec2(c) + 1.0);
			float w = q.M * part.z;
			float turn;
			vec2 at = shapeOf(n, q, started, turn);
			if (w <= 0.0 || distance(at, whose) > ROCK_BODY) {
				continue;
			}
			vec2 d = part.xy - (q.X + q.V);
			rest += w * (at + mat2(cos(turn), -sin(turn), sin(turn), cos(turn)) * d);
			turned += w * vec2(cos(turn), sin(turn));
			total += w;
		}
	}
	float weight = weightOn(c, p, grid, floorRow);
	if (total <= 0.0) {
		return vec4(p.X, 0.0, weight);
	}
	mat2 L = velocityGradient(c, p, grid, floorRow);
	float turn = atan(turned.y, turned.x) + 0.5 * (L[0][1] - L[1][0]);
	// Evened out with how far the same rock beside it's turned, so it's
	// much the same across the rock - which A then holds it to.
	vec2 around = vec2(0.0);
	for (int j = -1; j <= 1; ++j) {
		for (int i = -1; i <= 1; ++i) {
			ivec2 n = c + ivec2(i, j);
			if (n == c || !inside(n, grid, floorRow) || load(iChannel1, n).mat != ROCK) {
				continue;
			}
			vec4 s = texelFetch(iChannel2, n, 0);
			if (started && !notJelly(s) && distance(s.xy, rest / total) < ROCK_BODY) {
				around += vec2(cos(s.z), sin(s.z));
			}
		}
	}
	if (dot(around, around) > 0.0) {
		vec2 u = mix(vec2(cos(turn), sin(turn)), normalize(around), TURN_SMOOTH);
		turn = atan(u.y, u.x);
	}
	return vec4(rest / total, turn, weight);
}

vec4 palette(ivec2 c, vec2 res) {
	int top = int(res.y) - 1;
	vec4 ui = texelFetch(iChannel2, ivec2(UI_TEXEL, top), 0);
	vec4 touch = texelFetch(iChannel2, ivec2(TOUCH_TEXEL, top), 0);
	vec4 sizing = texelFetch(iChannel2, ivec2(SIZE_TEXEL, top), 0);
	vec4 rock = texelFetch(iChannel2, ivec2(ROCK_TEXEL, top), 0);
	if (ui.z != INIT) {
		ui = vec4(float(SAND), BRUSH, INIT, 0.0);
		touch = vec4(0.0, 0.0, -1.0, 0.0);
		sizing = vec4(0.0);
		rock = vec4(0.0);
	}
	bool fixedRock = rock.x > 0.5;
	if (pointerCount > 0 && touch.x < 0.5) {
		// A new touch: on the palette, it picks a tool (or the brush).
		vec2 at = pointers[0].xy;
		touch.y = iTime;
		touch.z = -1.0;
		if (at.y < res.y * PALETTE) {
			int b = int(at.x / (res.x / float(TOOLS + 1)));
			touch.z = float(b);
			if (b < TOOLS) {
				ui.x = float(toolAt(b, fixedRock));
			}
			sizing = vec4(at, 0.0, 0.0);
			rock.y = 0.0;
			ui.w = 1.0;
		} else {
			ui.w = 0.0;
		}
	}
	if (int(touch.z) == TOOLS) {
		// On the brush button: dragged out from it, the brush is as big as
		// the finger's far from it; just tapped, it's the next size.
		if (pointerCount > 0) {
			if (distance(pointers[0].xy, sizing.xy) > 0.25 * res.y * PALETTE) {
				sizing.z = 1.0;
			}
			if (sizing.z > 0.5) {
				ui.y = clamp(distance(pointers[0].xy, brushButton(res)) / CELL, 1.0, BRUSH_MAX);
			}
		} else if (sizing.z < 0.5) {
			ui.y = ui.y < 3.0 ? 5.0 : ui.y < 6.0 ? 10.0 : ui.y < 11.0 ? 20.0 : 2.0;
		}
	}
	// Rock held: swapped between moving and fixed (once a touch).
	int held = pointerCount > 0 && touch.z >= 0.0 && int(touch.z) < TOOLS ? toolAt(int(touch.z), fixedRock) : EMPTY;
	if ((held == ROCK || held == WALL) && rock.y < 0.5 && iTime - touch.y > SWAP_HOLD) {
		rock = vec4(fixedRock ? 0.0 : 1.0, 1.0, 0.0, 0.0);
		ui.x = float(toolAt(int(touch.z), !fixedRock));
	}
	if (pointerCount == 0) {
		ui.w = 0.0;
		touch.z = -1.0;
		sizing.z = 0.0;
	}
	// Erase held: clear everything.
	touch.w = held == ERASE && iTime - touch.y > CLEAR_HOLD ? 1.0 : 0.0;
	touch.x = float(pointerCount);
	return c.x == UI_TEXEL ? ui : c.x == TOUCH_TEXEL ? touch : c.x == SIZE_TEXEL ? sizing : c.x == ROCK_TEXEL ? rock : vec4(0.0);
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 c = ivec2(fragCoord);
	vec2 res = iResolution.xy;
	if (c.y == int(res.y) - 1) {
		fragColor = palette(c, res);
		return;
	}
	ivec2 grid = gridSize(res);
	int floorRow = floorRowOf(res);
	vec4 none = vec4(0.0, 0.0, 0.0, NOT_JELLY);
	if (!inside(c, grid, floorRow)) {
		fragColor = none;
		return;
	}
	Particle p = load(iChannel1, c);
	bool started = texelFetch(iChannel2, ivec2(UI_TEXEL, int(res.y) - 1), 0).z == INIT;
	if (p.mat == SAND) {
		fragColor = sand(c, p, grid, floorRow, started);
		return;
	}
	if (p.mat == WATER) {
		fragColor = vec4(0.0, 0.0, weightOn(c, p, grid, floorRow), NOT_JELLY);
		return;
	}
	if (p.mat == ROCK) {
		fragColor = rock(c, p, grid, floorRow, started);
		return;
	}
	if (p.mat != JELLY) {
		fragColor = none;
		return;
	}
	// F, carried with the jelly as B carried it.
	mat2 F = mat2(0.0);
	float total = 0.0, J = 0.0;
	for (int j = -2; j <= 2; ++j) {
		for (int i = -2; i <= 2; ++i) {
			ivec2 n = c + ivec2(i, j);
			if (!inside(n, grid, floorRow)) {
				continue;
			}
			Particle q = load(iChannel0, n);
			if (q.mat != p.mat) {
				continue;
			}
			float w = q.M * landing(q, vec2(c), vec2(c) + 1.0).z;
			if (w > 0.0) {
				// (Until the scene's set up, C holds no F yet.)
				vec4 f = texelFetch(iChannel2, n, 0);
				bool none = !started || notJelly(f);
				F += w * (none ? mat2(1.0) : jellyF(f));
				J += w * (none ? 1.0 : f.w);
				total += w;
			}
		}
	}
	F = total > 0.0 ? F / total : mat2(1.0);
	// F turned by the velocity gradient around it, and J grown or shrunk
	// with it - and inside the jelly, put right a little by how packed it
	// is.
	mat2 L = velocityGradient(c, p, grid, floorRow);
	F = (mat2(1.0) + L) * F;
	J = (total > 0.0 ? J / total : 1.0) * determinant(mat2(1.0) + L);
	if ((p.tint & TIGHT) == 0) {
		J = mix(J, 1.0 / clamp(p.M, 0.5, 2.0), JELLY_SETTLE);
	}
	J = clamp(J, 0.3, 3.0);
	F /= sqrt(max(determinant(F), 0.04));
	// Stretched or squashed past what it takes, it gives (so it's dented
	// for good, not flung apart): F = R S, its rotation and stretch, with
	// S's stretches kept within reach.
	float a = atan(F[0][1] - F[1][0], F[0][0] + F[1][1]);
	mat2 R = mat2(cos(a), sin(a), -sin(a), cos(a));
	mat2 S = transpose(R) * F;
	float sxx = S[0][0], syy = S[1][1], sxy = 0.5 * (S[1][0] + S[0][1]);
	float mid = 0.5 * (sxx + syy);
	float r = length(vec2(0.5 * (sxx - syy), sxy));
	float t = 0.5 * atan(2.0 * sxy, sxx - syy);
	vec2 stretch = clamp(vec2(mid + r, mid - r), JELLY_SQUASH, JELLY_STRETCH);
	stretch /= sqrt(stretch.x * stretch.y);
	mat2 Q = mat2(cos(t), sin(t), -sin(t), cos(t));
	S = Q * mat2(stretch.x, 0.0, 0.0, stretch.y) * transpose(Q);
	fragColor = jellyTexel(a, S, J, dome(c, grid, floorRow, started));
}

//// Buffer D
uniform sampler2D iChannel0; ///buf:A;

// Sound, from the simulation: long, soft grains, each taken from a random
// cell - sand a low rumble, water a slosh with the odd bubble, jelly a
// low wobble, rock a deep thud and grinding - as loud as that cell moves (and has just been hit), panned
// by where it is. So it's as busy as the simulation is, and quiet when
// nothing moves.

#define VOICES 20
// Grains a second a voice (two overlapping): long enough to carry a low
// note.
#define GRAIN_RATE 9.0
#define VOLUME 0.25

const float TAU = 6.2831853;

// Smooth noise: random values at `at`'s whole numbers, eased between - so
// about as high-pitched as `at` counts a second.
float smoothNoise(float at, float seed) {
	float i = floor(at);
	float f = at - i;
	f = f * f * (3.0 - 2.0 * f);
	return mix(hash12(vec2(i, seed)), hash12(vec2(i + 1.0, seed)), f) * 2.0 - 1.0;
}

vec2 mainSound(int s, float t) {
	ivec2 size = textureSize(iChannel0, 0);
	vec2 res = vec2(size);
	ivec2 grid = gridSize(res);
	int floorRow = floorRowOf(res);
	vec2 sum = vec2(0.0);
	for (int v = 0; v < VOICES; ++v) {
		for (int o = 0; o < 2; ++o) {
			// Two grains a voice overlapping by half, their windows
			// adding to 1.
			float u = t * GRAIN_RATE + float(v) * 0.618 + 0.5 * float(o);
			float slot = floor(u);
			float phase = u - slot;
			float window = sin(3.14159265 * phase);
			window *= window;
			vec2 pick = vec2(hash12(vec2(slot, float(v * 2 + o) + 0.5)),
					hash12(vec2(float(v * 2 + o) + 17.0, slot)));
			ivec2 c = ivec2(pick * vec2(grid - ivec2(0, floorRow))) + ivec2(0, floorRow);
			Particle p = load(iChannel0, c);
			if (p.mat == EMPTY || p.mat == WALL) {
				continue;
			}
			// Seconds into the grain, and its own random numbers.
			float since = phase / GRAIN_RATE;
			float seed = hash12(vec2(slot, float(v * 2 + o)) * 1.7);
			float speed = length(p.V);
			float hit = float(p.imp) / 7.0;
			float wave, loud;
			if (p.mat == SAND) {
				// A rumble: low noise (noise around a low note).
				float f = 110.0 + 110.0 * seed;
				wave = smoothNoise(since * 70.0, seed * 97.0) * sin(TAU * f * since);
				wave += 0.4 * smoothNoise(since * 140.0, seed * 53.0) * sin(TAU * 2.1 * f * since);
				loud = speed * 2.5 + hit * 0.5;
			} else if (p.mat == WATER) {
				// A slosh: soft, swelling noise, and now and then a bubble
				// rising gently.
				float f = 300.0 + 400.0 * seed;
				wave = smoothNoise(since * 160.0, seed * 31.0) * sin(TAU * f * since)
						* (0.6 + 0.4 * sin(TAU * (3.0 + 4.0 * seed) * since));
				if (fract(seed * 7.13) < 0.25) {
					float b = 300.0 + 400.0 * fract(seed * 3.7);
					wave += 0.6 * sin(TAU * b * since * (1.0 + 1.5 * since * GRAIN_RATE)) * exp(-since * 25.0);
				}
				loud = speed * 1.6 + hit * 0.4;
			} else if (p.mat == ROCK) {
				// Rock: a deep, noisy thud as it's hit (a burst of low
				// noise round a low note falling in pitch, and a little
				// crunch), and a deep grinding rumble - all noise - as it
				// slides. (The thud struck mid-grain, where its window's
				// open.)
				float f = 45.0 + 35.0 * seed;
				float k = since - 0.5 / GRAIN_RATE;
				float thud = k < 0.0 ? 0.0
						: (0.7 * smoothNoise(k * 300.0, seed * 11.0) + 0.6 * sin(TAU * f * k * (1.0 - 2.0 * k))) * exp(-k * 14.0)
						+ 0.35 * smoothNoise(k * 900.0, seed * 23.0) * exp(-k * 45.0);
				float grind = smoothNoise(since * 160.0, seed * 71.0) * (0.6 + 0.4 * smoothNoise(since * 25.0, seed * 5.0))
						+ 0.35 * smoothNoise(since * 420.0, seed * 37.0);
				wave = 1.3 * thud * min(hit * 3.0, 1.0) + 0.8 * grind * min(speed * 8.0, 1.0);
				loud = hit * 0.9 + speed * 1.5;
			} else {
				// Jelly: a low note (with its overtones, for small
				// speakers), wobbling in and out and a little in pitch.
				float f = 70.0 + 50.0 * seed;
				float wobble = 0.5 + 0.5 * sin(TAU * (5.0 + 4.0 * seed) * since);
				float phase = TAU * f * since + 1.5 * sin(TAU * 6.0 * since);
				wave = (sin(phase) + 0.5 * sin(2.0 * phase) + 0.25 * sin(3.0 * phase)) * 0.6 * wobble;
				loud = speed * 3.0 + hit * 0.3;
			}
			float x = pick.x;
			sum += window * clamp(loud, 0.0, 1.2) * wave * vec2(sqrt(1.0 - x), sqrt(x));
		}
	}
	return tanh(sum * VOLUME);
}
