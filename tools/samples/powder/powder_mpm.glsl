// Powder MPM - a Powder Toy-style sandbox on a 2D MPM solver (reintegration
// tracking, after Mykhailo Moroz's shadertoy MPM): sand that clumps into
// chunks and avalanches, water that splashes and pools, wobbly jelly, and
// walls - with sound made from the simulation itself.
//
// Paint with a finger; the palette along the bottom picks what you paint
// (Sand, Water, Jelly, Wall, Erase) and the brush size (the last button).
//
// How it works: the simulation grid is CELL screen pixels a cell, and every
// cell holds at most one blob of material (its centre of mass, velocity,
// mass, material, a colour and how hard it's just been hit, packed into
// one float texel). Each step:
//   Buffer A - forces: pressure, cohesion and friction between neighbours,
//              the jelly's elastic stress, gravity; walls; painting.
//   Buffer B - reintegration: each blob moves as a box of material, and
//              every cell gathers the part of each box landing on it.
//   Buffer C - the jelly's deformation gradient, carried along the same way
//              and updated by the local velocity gradient; its top row
//              holds the palette's state.
//   Buffer D - sound: grains taken from random cells - sand crackles, water
//              bubbles, jelly thrums - as loud as they've been hit.
///steps:4
uniform sampler2D iChannel0; ///buf:B;
uniform sampler2D iChannel1; ///buf:C;

// The metaball field of every material at a point, for drawing.
void fields(vec2 at, ivec2 grid, int floorRow, out vec4 field, out vec4 nearest) {
	field = vec4(0.0);
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
			k *= k * q.M;
			int slot = q.mat == SAND ? 0 : q.mat == WATER ? 1 : 2;
			field[slot] += k;
			float dist = length(d);
			if (dist < nearest[slot]) {
				nearest[slot] = dist;
				if (slot == 0) {
					nearest.w = float(q.tint);
				}
			}
		}
	}
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

vec3 palette(vec2 fragCoord, vec2 res, vec4 ui) {
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
		}
		if (int(ui.x) == tool) {
			col = mix(col, vec3(1.0), smoothstep(4.0, 2.0, abs(box + 3.0)));
		}
	} else {
		// The brush: a disc as big as it paints.
		float r = ui.y * CELL;
		face = mix(face, vec3(0.9), smoothstep(1.5, 0.0, length(local) - min(r, halfSize.y * 0.9)));
	}
	col = mix(col, face, smoothstep(1.0, -1.0, box));
	return col;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 res = iResolution.xy;
	ivec2 grid = gridSize(res);
	int floorRow = floorRowOf(res);
	vec4 ui = texelFetch(iChannel1, ivec2(UI_TEXEL, int(res.y) - 1), 0);
	if (fragCoord.y < res.y * PALETTE) {
		fragColor = vec4(palette(fragCoord, res, ui), 1.0);
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
	fields(at, grid, floorRow, field, nearest);
	// Water under the rest: translucent, foamy where it's fast.
	if (field.y > 0.25) {
		float edge = smoothstep(0.25, 0.6, field.y);
		vec3 water = mix(vec3(0.1, 0.35, 0.85), vec3(0.2, 0.55, 1.0), smoothstep(0.6, 1.4, field.y));
		col = mix(col, water, 0.85 * edge);
		col += vec3(0.25) * smoothstep(0.6, 0.3, field.y) * edge;
	}
	if (field.z > 0.35) {
		vec3 jelly = vec3(0.95, 0.35, 0.65) * (0.75 + 0.35 * smoothstep(0.35, 1.2, field.z));
		col = mix(col, jelly, smoothstep(0.35, 0.55, field.z));
	}
	if (field.x > 0.35) {
		// Sand: each grain its own colour.
		vec3 sand = sandColour(nearest.w);
		sand *= 0.8 + 0.2 * smoothstep(0.35, 1.0, field.x);
		col = mix(col, sand, smoothstep(0.35, 0.5, field.x));
	}
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
// Pressure a unit of over-packing makes, per material.
#define WATER_STIFFNESS 0.5
#define SAND_STIFFNESS 0.5
#define JELLY_STIFFNESS 0.2
// How hard sand pulls on sand once stretched: it holds together in chunks.
#define COHESION 0.04
// Sand's Coulomb friction (on sand, jelly and walls): a heap's slope.
#define FRICTION 0.8
// How much jelly takes on its neighbours' velocity a step.
#define MAX_GRIP 0.12
#define WATER_VISCOSITY 0.05
// The jelly's stiffness against shear and against squashing.
#define JELLY_SHEAR 0.05
#define JELLY_BULK 0.1
// Pressure between different materials, so they keep apart.
#define APART 0.1
// How much bigger than a cell a blob's box is as it moves.
#define SPREAD 0.1
// How much pressure waves are damped.
#define DAMPING 0.15
// Cells a step, at most.
#define VMAX 0.85
// The brush's starting radius, in cells.
#define BRUSH 5.0
// How far a blob reaches when drawn, in cells.
#define BLOB 1.25

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
// The palette state's texel along C's top row: x = tool, y = brush radius,
// z = INIT once set up, w = 1 while a touch that began on the palette lasts;
// and the last step's pointer count (x).
const int UI_TEXEL = 0;
const int TOUCH_TEXEL = 1;
const float INIT = 7.0;

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
	int a = int(d.x + 0.5);
	int b = int(d.w + 0.5);
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
	return 1.0 + SPREAD * smoothstep(0.1, 0.5, p.M);
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
// friction and (jelly) elastic stress, gravity, and the walls; and
// painting.

mat2 loadF(ivec2 c) {
	vec4 f = texelFetch(iChannel1, c, 0);
	return mat2(f.xy, f.zw);
}

// The stress a blob pushes its neighbours away with (a pressure where it's
// a fluid): its over-packing, and the jelly's elastic stress (fixed
// corotated, from its deformation gradient F).
mat2 stress(Particle p, ivec2 c) {
	if (p.mat == WALL) {
		return mat2(0.0);
	} else if (p.mat == WATER) {
		return mat2(WATER_STIFFNESS * max(p.M - 1.0, -0.02));
	} else if (p.mat == SAND) {
		return mat2(SAND_STIFFNESS * max(p.M - 1.0, -COHESION));
	}
	mat2 F = loadF(c);
	float J = max(determinant(F), 0.2);
	// R, F's rotation (its polar decomposition, in 2D).
	float a = atan(F[0][1] - F[1][0], F[0][0] + F[1][1]);
	mat2 R = mat2(cos(a), sin(a), -sin(a), cos(a));
	mat2 cauchy = (2.0 * JELLY_SHEAR * (F - R) * transpose(F) + mat2(JELLY_BULK * (J - 1.0) * J)) / J;
	return -cauchy + mat2(JELLY_STIFFNESS * max(p.M - 1.0, 0.0));
}

bool solid(ivec2 c, ivec2 grid, int floorRow) {
	return !inside(c, grid, floorRow) || load(iChannel0, c).mat == WALL;
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
	// For sand's friction: how hard it's pressed on, and the velocity of
	// what it's pressed against.
	float pressed = 0.0;
	vec3 against = vec3(0.0);
	mat2 own = stress(p, c);
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
			vec2 dx = q.X - p.X;
			float w = exp(-dot(dx, dx));
			// A wall pushes back as hard as it's pushed (the floor holds
			// a heap up), and different materials push apart where they
			// overlap.
			mat2 pair = own + (q.mat == WALL ? mat2(max(own[0][0], 0.0)) : stress(q, n));
			if (q.mat != p.mat && q.mat != WALL) {
				pair += mat2(APART * max(0.0, 1.0 - length(dx)));
			}
			// Shared by the pair as their masses are, so a sliver of
			// mass isn't flung.
			float share = 2.0 * q.M / (p.M + q.M);
			vec2 f = -0.5 * w * share * (pair * dx);
			push += f;
			// Friction: sand's below; jelly grips, water's a little
			// viscous.
			vec2 dv = q.V - p.V;
			if (p.mat == SAND) {
				if (dot(f, dx) < 0.0 && q.mat != WATER) {
					pressed += length(f);
					against += vec3(q.V, 1.0) * w * share;
				}
			} else {
				grip += w * share * (p.mat == WATER || q.mat == WATER ? WATER_VISCOSITY : MAX_GRIP) * dv;
			}
			// Artificial bulk viscosity: pairs closing in on each other
			// are slowed, damping pressure waves (not the flow).
			float closing = dot(dv, dx) / max(dot(dx, dx), 1e-4);
			if (closing < 0.0 && q.mat != WALL) {
				grip += w * share * DAMPING * closing * dx;
			}
		}
	}
	vec2 before = p.V;
	p.V += push + grip + vec2(0.0, -GRAVITY);
	// Sand: Coulomb friction. Moving with what it's pressed against, give
	// or take FRICTION times how hard it's pressed, it sticks to it (so a
	// heap stands); past that, it slides, that much slowed.
	if (p.mat == SAND && against.z > 0.0) {
		vec2 slip = p.V - against.xy / against.z;
		float hold = FRICTION * pressed;
		float amount = length(slip);
		p.V -= amount <= hold ? slip : slip * (hold / amount);
	}
	float speed = length(p.V);
	if (speed > VMAX) {
		p.V *= VMAX / speed;
	}

	// Walls: it doesn't go into one (its centre stays out; what its box
	// lands on a wall, B gives the cell beside it). Their friction is
	// above, as anything's.
	vec2 to = p.X + p.V;
	vec2 lo = vec2(-1e9), hi = vec2(1e9);
	for (int s = 1; s <= 2; ++s) {
		if (lo.x < -1e8 && solid(c - ivec2(s, 0), grid, floorRow)) {
			lo.x = float(c.x - s + 1);
		}
		if (hi.x > 1e8 && solid(c + ivec2(s, 0), grid, floorRow)) {
			hi.x = float(c.x + s);
		}
		if (lo.y < -1e8 && solid(c - ivec2(0, s), grid, floorRow)) {
			lo.y = float(c.y - s + 1);
		}
		if (hi.y > 1e8 && solid(c + ivec2(0, s), grid, floorRow)) {
			hi.y = float(c.y + s);
		}
	}
	vec2 held = clamp(to, lo, max(lo, hi));
	if (held != to) {
		p.V = held - p.X;
	}

	// How hard it was hit: its change of speed, beyond gravity's.
	float hit = length(p.V - before - vec2(0.0, -GRAVITY));
	p.imp = int(clamp(log2(1.0 + hit * 200.0) * 1.4, 0.0, 7.0));
	fragColor = pack(p, c);
}

//// Buffer B
///float32
uniform sampler2D iChannel0; ///buf:A;

// Reintegration: every blob moves as a box of material, and each cell
// gathers the parts of the boxes landing on it (their mass, centre of mass
// and momentum; the material, colour and impact of the biggest).

bool solid(ivec2 c, ivec2 grid, int floorRow) {
	return !inside(c, grid, floorRow) || load(iChannel0, c).mat == WALL;
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
	Particle self = load(iChannel0, c);
	if (self.mat == WALL) {
		fragColor = pack(self, c);
		return;
	}
	// What lands on a wall next to c comes to c (half a cell of a wall a
	// cell thick, the free cell on its other side taking the other half),
	// so no mass is lost on walls.
	vec2 cellLo = vec2(c), cellHi = vec2(c) + 1.0;
	for (int a = 0; a < 2; ++a) {
		ivec2 e = a == 0 ? ivec2(1, 0) : ivec2(0, 1);
		if (solid(c - e, grid, floorRow)) {
			cellLo[a] -= solid(c - 2 * e, grid, floorRow) ? 1.0 : 0.5;
		}
		if (solid(c + e, grid, floorRow)) {
			cellHi[a] += solid(c + 2 * e, grid, floorRow) ? 1.0 : 0.5;
		}
	}
	float m = 0.0;
	vec2 X = vec2(0.0), V = vec2(0.0);
	Particle p = emptyAt(c);
	int hit = 0;
	// Each material's share of what lands, and its biggest part's tint.
	vec3 share = vec3(0.0), biggest = vec3(0.0);
	ivec3 tints = ivec3(0);
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
			if (w > biggest[k]) {
				biggest[k] = w;
				tints[k] = q.tint;
			}
		}
	}
	// A cell holds one material: one of those landing, at random as
	// likely as its share of the mass - so each material keeps its mass
	// on average.
	float r = hash12(vec2(c) + fract(float(iFrame) * vec2(0.6180, 0.7548)) * 997.0) * m;
	int k = r < share.x ? 0 : r < share.x + share.y ? 1 : 2;
	p.mat = SAND + k;
	p.tint = tints[k];
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

// The jelly's deformation gradient F (identity elsewhere), carried along
// as B carries the blobs and turned by the local velocity gradient; and,
// along the top row, the palette's state.

vec4 palette(ivec2 c, vec2 res) {
	int top = int(res.y) - 1;
	vec4 ui = texelFetch(iChannel2, ivec2(UI_TEXEL, top), 0);
	vec4 touch = texelFetch(iChannel2, ivec2(TOUCH_TEXEL, top), 0);
	if (ui.z != INIT) {
		ui = vec4(float(SAND), BRUSH, INIT, 0.0);
		touch = vec4(0.0);
	}
	if (pointerCount > 0 && touch.x < 0.5) {
		// A new touch: on the palette, it picks a tool (or the brush).
		vec2 at = pointers[0].xy;
		if (at.y < res.y * PALETTE) {
			int b = int(at.x / (res.x / float(TOOLS + 1)));
			if (b < TOOLS) {
				ui.x = float(toolAt(b));
			} else {
				ui.y = ui.y < 3.0 ? 5.0 : ui.y < 6.0 ? 10.0 : ui.y < 11.0 ? 20.0 : 2.0;
			}
			ui.w = 1.0;
		} else {
			ui.w = 0.0;
		}
	}
	if (pointerCount == 0) {
		ui.w = 0.0;
	}
	touch.x = float(pointerCount);
	return c.x == UI_TEXEL ? ui : c.x == TOUCH_TEXEL ? touch : vec4(0.0);
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
	vec4 identity = vec4(1.0, 0.0, 0.0, 1.0);
	if (!inside(c, grid, floorRow)) {
		fragColor = identity;
		return;
	}
	Particle p = load(iChannel1, c);
	if (p.mat != JELLY) {
		fragColor = identity;
		return;
	}
	bool started = texelFetch(iChannel2, ivec2(UI_TEXEL, int(res.y) - 1), 0).z == INIT;
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
				vec4 f = started ? texelFetch(iChannel2, n, 0) : vec4(1.0, 0.0, 0.0, 1.0);
				F += w * mat2(f.xy, f.zw);
				total += w;
			}
		}
	}
	F = total > 0.0 ? F / total : mat2(1.0);
	// The velocity gradient around it (least squares, over the jelly next
	// to it), and F turned by it.
	mat2 dvdx = mat2(0.0), dxdx = mat2(1e-3);
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
	F = (mat2(1.0) + dvdx * inverse(dxdx)) * F;
	// Kept from turning inside out or blowing up.
	float J = determinant(F);
	if (J < 0.4 || J > 2.5) {
		F *= sqrt(clamp(J, 0.4, 2.5) / max(abs(J), 1e-3));
	}
	fragColor = vec4(F[0], F[1]);
}

//// Buffer D
uniform sampler2D iChannel0; ///buf:A;

// Sound, from the simulation: grains, each taken from a random cell -
// sand a crackle, water a bubble's plink, jelly a low thrum - as loud as
// that cell's just been hit, panned by where it is. So it's as busy as the
// simulation is, and quiet when nothing moves.

#define VOICES 24
#define GRAIN_RATE 90.0
#define VOLUME 0.35

const float TAU = 6.2831853;

float hash11(float p) {
	p = fract(p * 0.1031);
	p *= p + 33.33;
	p *= p + p;
	return fract(p);
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
			float since = phase / GRAIN_RATE;
			float speed = length(p.V);
			float loud = float(p.imp) / 7.0;
			float h = hash12(vec2(slot, float(v)) * 1.7);
			float wave;
			if (p.mat == SAND) {
				// A crackle: a short ring at a high, random pitch, struck.
				float f = 1800.0 + 4000.0 * h;
				wave = sin(TAU * f * since) * exp(-since * 900.0) * (0.4 + loud * 1.6);
				loud += speed * 0.4;
			} else if (p.mat == WATER) {
				// A bubble: a rising plink, more as it churns.
				float f = 350.0 + 900.0 * h;
				wave = sin(TAU * f * since * (1.0 + 2.5 * since * GRAIN_RATE)) * exp(-since * 160.0);
				loud = loud * 0.8 + speed * 0.9;
			} else {
				// Jelly: a low, soft thrum.
				float f = 70.0 + 90.0 * h;
				wave = sin(TAU * f * t) * 0.8;
				loud = loud * 0.5 + speed * 1.2;
			}
			float x = pick.x;
			sum += window * clamp(loud, 0.0, 1.5) * wave * vec2(sqrt(1.0 - x), sqrt(x));
		}
	}
	return tanh(sum * VOLUME);
}
