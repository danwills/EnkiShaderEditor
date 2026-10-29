// Life Viewer: Conway's Game of Life running in Buffer A, drawn here
// through a view you can move. One finger drags it, two pinch to zoom
// (around the point between them), and three scatter a fresh random
// soup. The grid wraps round, so you can scroll forever. Buffer B
// keeps the view; this pass just draws.

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;

#define GRID 256 // cells across the screen's shorter side (more along the longer)
#define ALIVE_COLOR vec3(1.0, 0.86, 0.35)
#define TRAIL_COLOR vec3(0.12, 0.35, 0.85)
#define BACKGROUND vec3(0.02, 0.025, 0.04)
#define LINE_COLOR vec3(0.1, 0.11, 0.15)
#define LINES_FROM 6.0 // zoom (pixels a cell) where cell lines fade in

// The grid: GRID cells across the shorter side and the screen's shape,
// so the whole of it just fits (never more cells than texels).
ivec2 gridSize(vec2 res) {
	return min(ivec2(floor(float(GRID) * res / min(res.x, res.y) + 0.5)), ivec2(res));
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	vec2 res = iResolution.xy;
	ivec2 grid = gridSize(res);
	vec4 view = texelFetch(iChannel1, ivec2(0), 0);
	if (view.w != 2.0) {
		// Before Buffer B's first frame: the whole grid, centred.
		view.xy = vec2(grid) * 0.5;
		view.z = min(res.x / float(grid.x), res.y / float(grid.y));
	}
	vec2 g = view.xy + (fragCoord - res * 0.5) / view.z;
	ivec2 c = ivec2(floor(g)) % grid;
	c += grid * ivec2(lessThan(c, ivec2(0)));
	vec4 cell = texelFetch(iChannel0, c, 0);

	vec3 col = mix(BACKGROUND, TRAIL_COLOR, cell.g * cell.g);
	col = mix(col, ALIVE_COLOR, step(0.5, cell.r));

	// Cell lines once cells are big enough to see.
	vec2 f = fract(g);
	float edge = min(min(f.x, 1.0 - f.x), min(f.y, 1.0 - f.y)) * view.z;
	float lines = smoothstep(LINES_FROM, 2.0 * LINES_FROM, view.z);
	col = mix(col, LINE_COLOR, lines * (1.0 - smoothstep(0.5, 1.5, edge)));

	fragColor = vec4(col, 1.0);
}

//// Buffer A
// The Game of Life: R is 1 for a live cell, G its trail (1 while
// alive, fading after). Cells are texels in the bottom-left gridSize()
// rectangle, wrapping round at its edges.

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;
uniform int pointerCount;

#define GRID 256 // as in the Image pass
#define DENSITY 0.3 // how much of a fresh soup is alive
#define TRAIL_FADE 0.95 // a dead cell's trail, kept each frame

// The grid: GRID cells across the shorter side and the screen's shape,
// so the whole of it just fits (never more cells than texels).
ivec2 gridSize(vec2 res) {
	return min(ivec2(floor(float(GRID) * res / min(res.x, res.y) + 0.5)), ivec2(res));
}

float hash(vec2 p) {
	vec3 q = fract(vec3(p.xyx) * 0.1031);
	q += dot(q, q.yzx + 33.33);
	return fract((q.x + q.y) * q.z);
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 grid = gridSize(iResolution.xy);
	ivec2 p = ivec2(fragCoord);
	if (p.x >= grid.x || p.y >= grid.y) {
		fragColor = vec4(0.0);
		return;
	}
	// A soup when nothing has run yet (Buffer B hasn't marked its view)
	// or while three fingers are down.
	bool started = texelFetch(iChannel1, ivec2(0), 0).w == 2.0;
	if (!started || pointerCount >= 3) {
		float alive = step(hash(fragCoord + fract(iTime) * 517.0), DENSITY);
		fragColor = vec4(alive, alive, 0.0, 1.0);
		return;
	}
	int n = 0;
	for (int y = -1; y <= 1; ++y) {
		for (int x = -1; x <= 1; ++x) {
			if (x != 0 || y != 0) {
				ivec2 q = (p + ivec2(x, y) + grid) % grid;
				n += int(texelFetch(iChannel0, q, 0).r > 0.5);
			}
		}
	}
	vec4 cell = texelFetch(iChannel0, p, 0);
	bool alive = n == 3 || (n == 2 && cell.r > 0.5);
	fragColor = vec4(float(alive), alive ? 1.0 : cell.g * TRAIL_FADE, 0.0, 1.0);
}

//// Buffer B
// The view, in the first few texels of the bottom row (the rest is
// unused): texel 0 is the grid point at the screen's centre (in cells),
// the zoom (pixels a cell) and a 2 once set; texels 1 and 2 are last
// frame's first two touches, with how many there were in 1's z. A
// gesture moves the view only while its finger count stays the same,
// so adding or lifting a finger never makes it jump.
///float32

uniform sampler2D iChannel0; ///buf:B;
uniform vec3 pointers[10];
uniform int pointerCount;

#define GRID 256 // as in the Image pass
#define MIN_ZOOM 1.0
#define MAX_ZOOM 64.0

// The grid: GRID cells across the shorter side and the screen's shape,
// so the whole of it just fits (never more cells than texels).
ivec2 gridSize(vec2 res) {
	return min(ivec2(floor(float(GRID) * res / min(res.x, res.y) + 0.5)), ivec2(res));
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	if (p.y != 0 || p.x > 2) {
		fragColor = vec4(0.0);
		return;
	}
	vec2 res = iResolution.xy;
	vec2 grid = vec2(gridSize(res));
	vec4 view = texelFetch(iChannel0, ivec2(0, 0), 0);
	vec4 last0 = texelFetch(iChannel0, ivec2(1, 0), 0);
	vec4 last1 = texelFetch(iChannel0, ivec2(2, 0), 0);
	if (view.w != 2.0) {
		view = vec4(grid * 0.5, min(res.x / grid.x, res.y / grid.y), 2.0);
	}
	vec2 centre = view.xy;
	float zoom = view.z;

	// Three fingers make a soup (Buffer A), not a gesture.
	int count = pointerCount <= 2 ? pointerCount : 0;
	vec2 now0 = pointers[0].xy;
	vec2 now1 = pointers[1].xy;
	if (count > 0 && int(last0.z) == count) {
		if (count == 1) {
			centre -= (now0 - last0.xy) / zoom;
		} else {
			// Keep the grid point that was between the fingers between them.
			vec2 before = (last0.xy + last1.xy) * 0.5;
			vec2 after = (now0 + now1) * 0.5;
			vec2 anchor = centre + (before - res * 0.5) / zoom;
			float spread = max(distance(now0, now1), 1.0) / max(distance(last0.xy, last1.xy), 1.0);
			zoom = clamp(zoom * spread, MIN_ZOOM, MAX_ZOOM);
			centre = anchor - (after - res * 0.5) / zoom;
		}
	}
	centre = mod(centre, grid);

	if (p.x == 0) {
		fragColor = vec4(centre, zoom, 2.0);
	} else if (p.x == 1) {
		fragColor = vec4(now0, float(count), 0.0);
	} else {
		fragColor = vec4(now1, 0.0, 0.0);
	}
}
