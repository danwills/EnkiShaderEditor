// Powder MPM - a Powder Toy-style sandbox on a 2D MPM solver (reintegration
// tracking, after Mykhailo Moroz's shadertoy MPM): dry sand that heaps and
// avalanches, soaks up water, crumbles in clumps when damp and slumps into
// mud when saturated; water that splashes and pools; wobbly jelly; walls -
// with sound made from the simulation itself.
//
// Paint with a finger; the palette along the bottom picks what you paint
// (Sand, Water, Jelly, Wall, Erase) and the brush size (the last button: tap
// it for the next size, or drag out from it to any size). Hold Erase to
// clear everything.
//
// How it works: the simulation grid is CELL screen pixels a cell, and every
// cell holds at most one blob of material (its centre of mass, velocity,
// mass, material, a colour and how hard it's just been hit, packed into
// one float texel). Each step:
//   Buffer A - forces: pressure, cohesion and friction between neighbours,
//              the jelly's elastic stress, gravity; sand settling and
//              rolling off, as in the Powder Toy; walls; painting.
//   Buffer B - reintegration: each blob moves as a box of material, and
//              every cell gathers the part of each box landing on it.
//   Buffer C - what a blob carries besides, carried along the same way:
//              the jelly's deformation gradient (turned by the local
//              velocity gradient), sand's wetness (soaked up from the water
//              beside it); and the weight on sand and water (so deep
//              sand's held still harder); its top row holds the palette's
//              state.
//   Buffer D - sound: grains taken from random cells - sand rumbles, water
//              sloshes and bubbles, jelly wobbles - as loud as they move.
///steps:4
uniform sampler2D iChannel0; ///buf:B;
uniform sampler2D iChannel1; ///buf:C;

// The metaball field of every material at a point, for drawing: how much
// of it there is about there (1 for a cell's worth all round) - smooth
// enough not to show the grid where it's thinned out.
void fields(vec2 at, ivec2 grid, int floorRow, out vec4 field, out vec4 nearest, out ivec2 grain, out vec2 flow) {
	field = vec4(0.0);
	// (And how the water there flows, as smoothly.)
	flow = vec2(0.0);
	grain = ivec2(0);
	// How far the nearest blob of each material is (sand, water, jelly),
	// and the nearest grain of sand's tint.
	nearest = vec4(1e9, 1e9, 1e9, 0.0);
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
			float k = max(0.0, 1.0 - r2);
			k *= k * q.M * 3.0 / (3.14159265 * BLOB * BLOB);
			int slot = q.mat == SAND ? 0 : q.mat == WATER ? 1 : 2;
			field[slot] += k;
			if (slot == 1) {
				flow += k * q.V;
			}
			float dist = length(d);
			if (dist < nearest[slot]) {
				nearest[slot] = dist;
				if (slot == 0) {
					nearest.w = float(q.tint);
					grain = n;
				}
			}
		}
	}
}

// Smooth noise, about 1 across a unit.
float smoothNoise2(vec2 x) {
	vec2 i = floor(x), f = fract(x);
	f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
	return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), f.x),
			mix(hash12(i + vec2(0.0, 1.0)), hash12(i + 1.0), f.x), f.y);
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

vec3 sandColour(float tint) {
	return mix(vec3(0.86, 0.68, 0.38), vec3(0.62, 0.45, 0.24), tint / 7.0);
}

vec3 toolColour(int tool, vec2 at) {
	if (tool == SAND) {
		return sandColour(floor(hash12(floor(at * 0.25)) * 8.0));
	} else if (tool == WATER) {
		return vec3(0.15, 0.45, 0.95);
	} else if (tool == JELLY) {
		return vec3(0.95, 0.35, 0.65);
	} else if (tool == WALL) {
		return vec3(0.5, 0.52, 0.58);
	}
	return vec3(0.12);
}

vec3 palette(vec2 fragCoord, vec2 res, vec4 ui, bool clearing) {
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
		int tool = toolAt(b);
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
	if (b < TOOLS && int(ui.x) == toolAt(b)) {
		// The tool in hand: outlined.
		col = mix(col, SELECTED, smoothstep(2.0, 0.5, abs(box - 1.5)));
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
				col = mix(col, SELECTED, 0.5 * smoothstep(1.5, 0.5, d));
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
		fragColor = vec4(palette(fragCoord, res, ui, clearing), 1.0);
		fragColor.rgb = overlay(fragColor.rgb, fragCoord, res, ui, sizing);
		return;
	}
	vec2 at = fragCoord / CELL;
	vec3 col = mix(vec3(0.05, 0.06, 0.09), vec3(0.11, 0.12, 0.17), fragCoord.y / res.y);
	ivec2 c = ivec2(floor(at));
	if (!inside(c, grid, floorRow) || load(iChannel0, c).mat == WALL) {
		// Walls (and past the grid's edges): bricks.
		vec2 b = at * vec2(0.5, 1.0);
		b.x += 0.5 * mod(floor(b.y), 2.0);
		vec2 m = abs(fract(b) - 0.5);
		col = vec3(0.42, 0.44, 0.5) * (0.85 + 0.15 * hash12(floor(b))) * (0.7 + 0.3 * smoothstep(0.5, 0.42, max(m.x, m.y * 0.5 + 0.25)));
		fragColor = vec4(col, 1.0);
		return;
	}
	vec4 field, nearest;
	ivec2 grain;
	vec2 flow;
	fields(at, grid, floorRow, field, nearest, grain, flow);
	// Water under the rest: translucent, foamy at its edges with the air,
	// its flow shown by ripples carried along with it. (Counted with the
	// jelly beside it, so there's no gap between them, flickering.)
	if (field.y > 0.05) {
		float edge = smoothstep(0.2, 0.35, field.y + field.z) * smoothstep(0.05, 0.15, field.y);
		vec3 water = mix(vec3(0.1, 0.35, 0.85), vec3(0.2, 0.55, 1.0), smoothstep(0.5, 1.2, field.y));
		water += RIPPLES * vec3(0.4, 0.6, 0.8) * ripples(at, flow / field.y) * smoothstep(0.3, 0.8, field.y);
		col = mix(col, water, 0.85 * edge);
		col += vec3(0.25) * smoothstep(0.45, 0.25, field.y) * (1.0 - smoothstep(0.05, 0.25, field.z)) * edge;
	}
	if (field.z > 0.25) {
		vec3 jelly = vec3(0.95, 0.35, 0.65) * (0.75 + 0.35 * smoothstep(0.3, 1.0, field.z));
		col = mix(col, jelly, smoothstep(0.25, 0.4, field.z));
	}
	// Sand: each grain its own colour, darker as it's wetter - and where
	// it's thinned out (spraying), its grains apart, each as big as there's
	// sand in its cell.
	// (Not the slivers - a haze.)
	float grainM = load(iChannel0, grain).M;
	float grainSize = GRAIN * sqrt(grainM);
	float cover = max(smoothstep(0.25, 0.38, field.x),
			smoothstep(0.1, 0.2, grainM) * smoothstep(0.5 / CELL, -0.5 / CELL, nearest.x - grainSize));
	if (cover > 0.0) {
		vec3 sand = sandColour(nearest.w);
		sand *= 0.8 + 0.2 * smoothstep(0.3, 0.8, field.x);
		sand *= 1.0 - 0.45 * wetness(texelFetch(iChannel1, grain, 0));
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
// How hard thinned-out jelly pulls itself back together.
#define JELLY_COHESION 0.8
#define MAX_GRIP 0.12
#define WATER_VISCOSITY 0.05
// The jelly's stiffness against shear, and how far it stretches (or
// squashes) before it gives.
#define JELLY_SHEAR 0.45
#define JELLY_SQUASH 0.4
#define JELLY_STRETCH 2.5
// How hard jelly's pulled to the jelly around it.
#define JELLY_PULL 0.045
#define LONE_PULL 0.15
// How much there has to be in a cell to keep the others out of it.
#define KEEP_OUT 0.5
// Pressure between different materials, so they keep apart.
#define APART 0.1
// How much bigger than a cell a blob's box is as it moves.
#define SPREAD 0.1
// How much pressure waves are damped.
#define DAMPING 0.15
// Cells a step, at most: anything, and jelly.
#define VMAX 0.85
#define JELLY_VMAX 0.3
// The brush's starting radius, and the biggest it's sized to, in cells.
#define BRUSH 5.0
#define BRUSH_MAX 40.0
// How far a blob reaches when drawn, in cells.
#define BLOB 1.8
// How big a grain of sand's drawn, in cells, with a cell's worth of sand.
#define GRAIN 0.6
// Water's ripples: how big (cells), how long each layer's carried along
// before it fades (seconds) and how strongly they shade it.
#define RIPPLE_SIZE 5.0
#define RIPPLE_TIME 0.8
#define RIPPLES 0.3

uniform vec3 pointers[10];
uniform int pointerCount;

const int EMPTY = 0;
const int WALL = 1;
const int SAND = 2;
const int WATER = 3;
const int JELLY = 4;
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
// The selected tool's outline, and the brush's.
const vec3 SELECTED = vec3(1.0, 0.85, 0.2);
const float INIT = 7.0;
// Water's tint (which it doesn't show) when its box isn't spread.
const int TIGHT = 1;
// How long Erase is held to clear everything, in seconds.
const float CLEAR_HOLD = 0.6;
// What C holds for anything but jelly (whose F fills all four): how far
// sand is from what holds it up in x (see support()); the weight on sand or
// water (its own and what's above it) in z; NOT_JELLY plus sand's wetness
// in w.
const float NOT_JELLY = 1000.0;
const float UNSUPPORTED = 99.0;

bool notJelly(vec4 c) {
	return c.w >= NOT_JELLY;
}

// Sand's wetness, 0 to 1, from its texel in C.
float wetness(vec4 c) {
	return notJelly(c) ? c.w - NOT_JELLY : 0.0;
}

// How far sand is from what holds it up, from its texel in C: 0 on a
// column of sand down to a wall or still jelly, a cell more for each it
// reaches out sideways from one (damp), or UNSUPPORTED.
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

int toolAt(int b) {
	return b == 0 ? SAND : b == 1 ? WATER : b == 2 ? JELLY : b == 3 ? WALL : ERASE;
}

struct Particle {
	vec2 X; // centre of mass, in cells
	vec2 V; // cells a step
	float M; // mass: 1 is a cell full at rest
	int mat;
	int tint; // 0-7, the grain's own colour
	int imp; // 0-7, how hard it's just been hit (for sound)
};

float hash12(vec2 p) {
	vec3 p3 = fract(vec3(p.xyx) * 0.1031);
	p3 += dot(p3, p3.yzx + 33.33);
	return fract((p3.x + p3.y) * p3.z);
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
	// apart where it's packed, so a falling block would swell up.
	if (p.mat == SAND) {
		return clamp(p.M, 0.3, 1.0);
	}
	return (p.mat == JELLY || p.mat == WATER) && p.tint != TIGHT ? 1.0 + SPREAD * smoothstep(0.1, 0.5, p.M) : 1.0;
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
	return notJelly(f) ? mat2(1.0) : mat2(f.xy, f.zw);
}

float wetAt(ivec2 c) {
	return wetness(texelFetch(iChannel1, c, 0));
}

// The stress a blob pushes its neighbours away with (a pressure where it's
// a fluid): its over-packing (stretched, damp sand and jelly pull), and the
// jelly's elastic resistance to shear (fixed corotated, from its
// deformation gradient F; its volume is its density's).
mat2 stress(Particle p, ivec2 c) {
	if (p.mat == WALL) {
		return mat2(0.0);
	} else if (p.mat == WATER) {
		return mat2(WATER_STIFFNESS * max(p.M - 1.0, -0.01));
	} else if (p.mat == SAND) {
		return mat2(sandPressure(p.M));
	}
	// (Only F's shape, its volume taken out: averaged as it moves, F
	// shrinks a little, and would push the jelly ever bigger.)
	mat2 F = loadF(c);
	F /= sqrt(max(determinant(F), 0.04));
	// R, F's rotation (its polar decomposition, in 2D).
	float a = atan(F[0][1] - F[1][0], F[0][0] + F[1][1]);
	mat2 R = mat2(cos(a), sin(a), -sin(a), cos(a));
	mat2 cauchy = 2.0 * JELLY_SHEAR * (F - R) * transpose(F);
	return -cauchy + mat2(JELLY_STIFFNESS * max(p.M - 1.0, -JELLY_COHESION));
}

bool solid(ivec2 c, ivec2 grid, int floorRow) {
	return !inside(c, grid, floorRow) || load(iChannel0, c).mat == WALL;
}

Particle at(ivec2 c, ivec2 grid, int floorRow) {
	return inside(c, grid, floorRow) ? load(iChannel0, c) : wallAt(c);
}

// Whether sand can settle on q: a wall, or most of a cell of sand or jelly.
bool holds(Particle q) {
	return q.mat == WALL || (q.mat == SAND || q.mat == JELLY) && q.M > 0.5;
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
		p = Particle(vec2(c) + 0.5, vec2(0.0), 1.0, SAND, int(h * 8.0), 0);
	} else if (u.x > 0.55 && u.x < 0.97 && u.y > 0.5 && u.y < 0.53) {
		p = wallAt(c);
	} else if (u.x > 0.62 && u.x < 0.97 && u.y > 0.53 && u.y < 0.75) {
		p = Particle(vec2(c) + 0.5, vec2(0.0), 1.0, WATER, int(h * 8.0), 0);
	} else if (abs(u.x - 0.42) < 0.08 && abs(u.y - 0.6) < 0.06) {
		p = Particle(vec2(c) + 0.5, vec2(0.0), 1.0, JELLY, int(h * 8.0), 0);
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

	// Painting, with every finger, unless the touch began on the palette.
	if (ui.w < 0.5) {
		int tool = int(ui.x);
		vec2 centre = vec2(c) + 0.5;
		for (int i = 0; i < 10; ++i) {
			if (i >= pointerCount) {
				break;
			}
			if (pointers[i].y < res.y * PALETTE || distance(centre, pointers[i].xy / CELL) > ui.y) {
				continue;
			}
			float h = hash12(vec2(c) + float(iFrame) * vec2(0.37, 0.71));
			if (tool == ERASE) {
				p = emptyAt(c);
			} else if (tool == WALL) {
				p = wallAt(c);
			} else if (p.mat == EMPTY && h < (tool == SAND ? 0.25 : 0.5)) {
				p = Particle(centre + (vec2(h, fract(h * 7.3)) - 0.5) * 0.3, vec2(0.0), 1.0, tool,
						int(fract(h * 13.7) * 8.0), 0);
			}
		}
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
	// For jelly: whether there's water or sand beside it.
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
				if (p.mat == WATER && (q.mat == SAND || q.mat == JELLY) || p.mat == JELLY && q.mat != JELLY) {
					beside = true;
				}
				if (p.mat == JELLY && (q.mat == WATER || q.mat == SAND)) {
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
		// Jelly that's thinned out, or in water or sand, is pulled to the
		// jelly around it, out to two cells: bits knocked off back in, gaps
		// closed, the rest kept out. (Not packed jelly in the open, which it
		// would only squash.)
		vec3 around2 = vec3(0.0);
		for (int j = -2; j <= 2; ++j) {
			for (int i = -2; i <= 2; ++i) {
				ivec2 n = c + ivec2(i, j);
				if (n == c || !inside(n, grid, floorRow)) {
					continue;
				}
				Particle q = load(iChannel0, n);
				if (q.mat == JELLY) {
					around2 += vec3(q.M * (q.X - p.X), q.M);
				}
			}
		}
		if (around2.z > 0.0) {
			// (Harder on a bit of jelly with little around it, coming off.)
			float pull = mix(JELLY_PULL, LONE_PULL, smoothstep(10.0, 3.0, around2.z));
			grip += pull * (amid ? 1.0 : smoothstep(1.0, 0.6, p.M)) * around2.xy / max(around2.z, 2.0);
		}
	}
	vec2 before = p.V;
	float weight = GRAVITY * (p.mat == SAND ? SAND_WEIGHT : 1.0);
	p.V += push + grip + vec2(0.0, -weight);
	if (p.mat == SAND && wateriness.x > 0.0) {
		// Sand in water sinks through it, gently, at its own pace.
		float among = wateriness.x / wateriness.y;
		p.V = mix(p.V, waterV / wateriness.x + vec2(0.0, -SINK), SAND_DRAG * among);
	}
	// Water the sand beside it soaks up.
	p.M *= 1.0 - min(soaked, 0.5);
	if (p.mat == WATER || p.mat == JELLY) {
		// Water beside sand or jelly, and jelly at its edge, aren't spread
		// (see spread()).
		p.tint = beside || p.mat == JELLY && around < 8 ? TIGHT : 0;
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
		// (Mud: saturated, and thinly spread through water - packed sand
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
	// (Jelly no faster than it can take a knock without flying apart.)
	float most = p.mat == JELLY ? JELLY_VMAX : VMAX;
	float speed = length(p.V);
	if (speed > most) {
		p.V *= most / speed;
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
	// Nor does jelly go into the cell of something else beside it, nor
	// anything else into jelly's, nor water into sand that's settled - but
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
		if (q.mat == EMPTY || q.mat == WALL || q.M < KEEP_OUT || (q.mat == JELLY) == (p.mat == JELLY) && !settled) {
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
	vec2 held = clamp(to, lo, max(lo, hi));
	if (held != to) {
		p.V = held - p.X;
	}

	// How hard it was hit: its change of speed, beyond gravity's.
	float hit = length(p.V - before - vec2(0.0, -weight));
	p.imp = int(clamp(log2(1.0 + hit * 200.0) * 1.4, 0.0, 7.0));
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
	// Each material's share of what lands, and how much of its own sand
	// stays.
	vec3 share = vec3(0.0);
	float stays = 0.0;
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
			if (n == c && k == 0) {
				stays = w;
			}
		}
	}
	// A cell holds one material: one of those landing, at random as
	// likely as its share of the mass - so each material keeps its mass
	// on average.
	float r = hash12(vec2(c) + fract(float(iFrame) * vec2(0.6180, 0.7548)) * 997.0) * m;
	int k = r < share.x ? 0 : r < share.x + share.y ? 1 : 2;
	p.mat = SAND + k;
	// Sand that mostly stays keeps its tint; sand that's moved takes a new
	// one. (Taking one from what lands, even at random, has a patch of one
	// tint grow, drawn out into stripes as sand flows.)
	if (k == 0 && self.mat == SAND && stays > 0.5 * share.x) {
		p.tint = self.tint;
	} else {
		p.tint = int(hash12(vec2(c) * 0.93 + fract(float(iFrame) * vec2(0.2207, 0.8812)) * 877.0) * 8.0);
	}
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
// gradient; sand's wetness (x, NOT_JELLY in z and w, as anything else's),
// carried along too, and soaked up from the water beside it; and, along the
// top row, the palette's state.

// The velocity gradient around jelly p at c (least squares, over the
// jelly next to it; a little less than that, where there's little).
mat2 velocityGradient(ivec2 c, Particle p, ivec2 grid, int floorRow) {
	mat2 dvdx = mat2(0.0), dxdx = mat2(0.2);
	for (int j = -1; j <= 1; ++j) {
		for (int i = -1; i <= 1; ++i) {
			ivec2 n = c + ivec2(i, j);
			if (n == c || !inside(n, grid, floorRow)) {
				continue;
			}
			Particle q = load(iChannel1, n);
			if (q.mat != JELLY) {
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

// The weight on sand or water at c: its own, and (as it was last step) the
// weight on what's just above it - so it reaches down a few cells a step.
float weightOn(ivec2 c, Particle p, ivec2 grid, int floorRow) {
	float own = p.M * (p.mat == SAND ? SAND_WEIGHT : 1.0);
	ivec2 up = c + ivec2(0, 1);
	if (!inside(up, grid, floorRow)) {
		return own;
	}
	Particle q = load(iChannel1, up);
	vec4 above = texelFetch(iChannel2, up, 0);
	return own + ((q.mat == SAND || q.mat == WATER) && notJelly(above) ? above.z : 0.0);
}

// How far sand at c is from what holds it up (see support()): down a
// column of sand (looking four cells down a step, so it's known soon after
// it lands, or falls) to a wall or still jelly; or, damp, out sideways
// from sand beside it that's held up, as far as it reaches.
float supportOf(ivec2 c, ivec2 grid, int floorRow, float wet, bool started) {
	float held = UNSUPPORTED;
	for (int k = 1; k <= 4; ++k) {
		ivec2 n = c - ivec2(0, k);
		Particle q = inside(n, grid, floorRow) ? load(iChannel1, n) : wallAt(n);
		if (q.mat == WALL || q.mat == JELLY && q.M > 0.5 && length(q.V) < SETTLED) {
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
				wet += w * (started ? wetness(texelFetch(iChannel2, n, 0)) : 0.0);
				total += w;
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
	return vec4(supportOf(c, grid, floorRow, wet, started), 0.0, weightOn(c, p, grid, floorRow), NOT_JELLY + wet);
}

vec4 palette(ivec2 c, vec2 res) {
	int top = int(res.y) - 1;
	vec4 ui = texelFetch(iChannel2, ivec2(UI_TEXEL, top), 0);
	vec4 touch = texelFetch(iChannel2, ivec2(TOUCH_TEXEL, top), 0);
	vec4 sizing = texelFetch(iChannel2, ivec2(SIZE_TEXEL, top), 0);
	if (ui.z != INIT) {
		ui = vec4(float(SAND), BRUSH, INIT, 0.0);
		touch = vec4(0.0, 0.0, -1.0, 0.0);
		sizing = vec4(0.0);
	}
	if (pointerCount > 0 && touch.x < 0.5) {
		// A new touch: on the palette, it picks a tool (or the brush).
		vec2 at = pointers[0].xy;
		touch.y = iTime;
		touch.z = -1.0;
		if (at.y < res.y * PALETTE) {
			int b = int(at.x / (res.x / float(TOOLS + 1)));
			touch.z = float(b);
			if (b < TOOLS) {
				ui.x = float(toolAt(b));
			}
			sizing = vec4(at, 0.0, 0.0);
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
	if (pointerCount == 0) {
		ui.w = 0.0;
		touch.z = -1.0;
		sizing.z = 0.0;
	}
	// Erase held: clear everything.
	touch.w = pointerCount > 0 && touch.z >= 0.0 && int(touch.z) < TOOLS && toolAt(int(touch.z)) == ERASE
			&& iTime - touch.y > CLEAR_HOLD ? 1.0 : 0.0;
	touch.x = float(pointerCount);
	return c.x == UI_TEXEL ? ui : c.x == TOUCH_TEXEL ? touch : c.x == SIZE_TEXEL ? sizing : vec4(0.0);
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
	if (p.mat != JELLY) {
		fragColor = none;
		return;
	}
	// F, carried with the jelly as B carried it.
	mat2 F = mat2(0.0);
	float total = 0.0;
	for (int j = -2; j <= 2; ++j) {
		for (int i = -2; i <= 2; ++i) {
			ivec2 n = c + ivec2(i, j);
			if (!inside(n, grid, floorRow)) {
				continue;
			}
			Particle q = load(iChannel0, n);
			if (q.mat != JELLY) {
				continue;
			}
			float w = q.M * landing(q, vec2(c), vec2(c) + 1.0).z;
			if (w > 0.0) {
				// (Until the scene's set up, C holds no F yet.)
				vec4 f = texelFetch(iChannel2, n, 0);
				if (!started || notJelly(f)) {
					f = vec4(1.0, 0.0, 0.0, 1.0);
				}
				F += w * mat2(f.xy, f.zw);
				total += w;
			}
		}
	}
	F = total > 0.0 ? F / total : mat2(1.0);
	// F turned by the velocity gradient around it.
	F = (mat2(1.0) + velocityGradient(c, p, grid, floorRow)) * F;
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
	mat2 Q = mat2(cos(t), sin(t), -sin(t), cos(t));
	F = R * Q * mat2(stretch.x, 0.0, 0.0, stretch.y) * transpose(Q);
	fragColor = vec4(F[0], F[1]);
}

//// Buffer D
uniform sampler2D iChannel0; ///buf:A;

// Sound, from the simulation: long, soft grains, each taken from a random
// cell - sand a low rumble, water a slosh with the odd bubble, jelly a
// low wobble - as loud as that cell moves (and has just been hit), panned
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
