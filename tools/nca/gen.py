#!/usr/bin/env python3
# Builds a multipass Enki shader (Image + Buffers A-D, joined with
# "//// Buffer X" markers) and its weights texture from a Growing Neural
# Cellular Automata model, as exported for the Distill article's WebGL
# demo (github.com/distillpub/post--growing-ca, public/webgl_models8/).
# See README.md.
import argparse
import base64
import json

from PIL import Image

EXPERIMENTS = {
	# The article's three experiments: what each model was trained to do.
	'ex1': 'grow from a single cell into this shape',
	'ex2': 'grow from a single cell into this shape and keep it',
	'ex3': 'grow from a single cell into this shape, keep it, and regrow what gets cut away',
}

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('model', help='a webgl_models8 JSON file')
parser.add_argument('shader', help='where to write the shader')
parser.add_argument('weights', help='where to write the weights PNG')
parser.add_argument('--texture', required=True,
		help='the weights texture\'s name in the app (a GLSL identifier)')
parser.add_argument('--title', required=True, help='the shader\'s title, for its header')
parser.add_argument('--creature', required=True, help='what it grows, e.g. "lizard"')
parser.add_argument('--dilation', type=int, default=1,
		help='DILATION\'s starting value - see its comment in the shader')
parser.add_argument('--experiment', choices=sorted(EXPERIMENTS), default='ex3')
parser.add_argument('--cell-size', type=float, default=5.0,
		help='render pixels a cell (at the app\'s 1/4 quality, 5 gives 72 '
		'cells across a 1440-pixel-wide screen)')
args = parser.parse_args()

layers = json.load(open(args.model))
assert layers[0]['in_ch'] == 48 and layers[0]['out_ch'] == 128
assert layers[1]['in_ch'] == 128 and layers[1]['out_ch'] == 16


def layer_bytes(layer):
	data = base64.b64decode(layer['data_b64'])
	assert len(data) == (layer['in_ch'] + 1) * layer['out_ch']
	return data


# Both layers' bytes, 32 RGBA texels to a row, layer 1 then layer 2 - as
# the demo's own weight textures (texel (c, r) holds input r's weights
# for outputs 4c..4c+3, the last row the biases).
def write_weights_png(path):
	w1 = layer_bytes(layers[0])
	w2 = layer_bytes(layers[1])
	texels = len(w1) // 4 + len(w2) // 4
	rows = (texels + 31) // 32
	pixels = w1 + w2 + bytes(rows * 32 * 4 - len(w1) - len(w2))
	Image.frombytes('RGBA', (32, rows), pixels).save(path)
	return rows


TEXTURE = args.texture
WEIGHT_ROWS = write_weights_png(args.weights)

# 3x5 pixel glyphs for the settings labels, top row first, 3 bits a row
# - small ints, since the test phone's GPU mangles int constants above
# 2^24.
GLYPHS = {
	' ': '000 000 000 000 000',
	'A': '010 101 111 101 101',
	'B': '110 101 110 101 110',
	'D': '110 101 101 101 110',
	'E': '111 100 110 100 111',
	'F': '111 100 110 100 100',
	'H': '101 101 111 101 101',
	'I': '111 010 010 010 111',
	'N': '110 101 101 101 101',
	'O': '111 101 101 101 111',
	'P': '110 101 110 100 100',
	'R': '110 101 110 101 101',
	'S': '011 100 010 001 110',
	'T': '111 010 010 010 010',
	'U': '101 101 101 101 111',
}
# Top to bottom on screen; the longest sets where the bars start.
LABELS = ['ROTATION', 'SPEED', 'FIRE RATE', 'BRUSH']
LONGEST_LABEL = max(len(label) for label in LABELS)


def glyph_bits(ch):
	return int(GLYPHS[ch].replace(' ', ''), 2)


def label_arrays():
	lines = []
	for i, text in enumerate(LABELS):
		lines.append('const int LABEL_%d[%d] = int[](%s);' % (
				i, len(text), ', '.join(str(glyph_bits(c)) for c in text)))
	return '\n'.join(lines)


def label_lookup():
	# GLSL ES has no arrays of arrays, so one branch per label.
	branches = []
	for i in range(len(LABELS)):
		branches.append('\tif (which == %d) {\n\t\treturn index < LABEL_%d.length() ? '
				'LABEL_%d[index] : -1;\n\t}' % (i, i, i))
	return 'int labelGlyph(int which, int index) {\n%s\n\treturn -1;\n}' % '\n'.join(branches)


weights_glsl = f'''
// The network's weights - see the header comment in the Image pass.
// Bytes, decoded as (byte / 255 - 0.5) * scale. (In a texture rather
// than const arrays: the test phone's GPU corrupts values in big ones.)
uniform sampler2D {TEXTURE};

#define WEIGHT_ROWS {WEIGHT_ROWS}

vec4 weightBytes(int texel) {{
	// Saved textures are uploaded bottom row first.
	return texelFetch({TEXTURE}, ivec2(texel % 32, WEIGHT_ROWS - 1 - texel / 32), 0);
}}
'''

credits = f"""// {args.title}
//
// A port of "Growing Neural Cellular Automata" by Alexander Mordvintsev,
// Ettore Randazzo, Eyvind Niklasson and Michael Levin (Distill, 2020,
// https://distill.pub/2020/growing-ca/, CC-BY 4.0). Every cell holds 16
// numbers; each step it looks at its neighbours, runs a tiny neural
// network (48 -> 128 -> 16), and adds the result to itself. The
// network's weights - trained to {EXPERIMENTS[args.experiment]}
// ({args.creature}) - are the article's own, from its WebGL demo
// (github.com/distillpub/post--growing-ca, CC-BY 4.0), and the update
// follows that demo's ca.js (Apache 2.0) step for step.
//
// FINGERS
//   tap         plant a seed (a single black cell) - a new {args.creature} grows
//   drag        cut away part of it
//   2 fingers   cut under both, twice as wide - for clearing the field
//   3 fingers   adjust the settings (bars show them while you do):
//     twist       rotation - which way the cells think is up
//     drag ^/v    speed - steps per frame
//     pinch       fire rate - how many cells update each step (it
//                 learned at 0.5; lower is calmer, higher more jittery)
//     drag <->    brush - how wide the cuts are
//   Gestures add up, so twist, drag or pinch again to keep going.
// DILATION (in every pass) grows them bigger - see its comment.
// Rewind starts again from a single seed in the middle, with the
// settings back to their defaults (DEFAULT_* in Buffer A).
//
// The cells cover the whole frame, wrapping around at the edges (or
// make FULL_FRAME 0, in every pass, for a square grid in the middle).
// Each is CELL_SIZE render pixels, so a higher quality setting gives more
// room - and smaller creatures - at a cost in speed.
// Buffer A: the cells' state and the settings, Buffers B and C: the
// network's hidden layer, Buffer D: each step's update. Needs float
// buffers, and the weights texture {TEXTURE} (it comes with the shader
// pack).
"""

common = f"""// Each cell is CELL_SIZE render pixels, so the app's quality setting
// picks how many there are: higher quality, more (and smaller-looking)
// creatures. FULL_FRAME 1: cells fill the frame; 0: a square grid in
// the middle. Keep both the same in every pass.
#define CELL_SIZE {args.cell_size!r}
#define FULL_FRAME 1
// How far apart the cells a cell sees are: 1 is the model as trained.
// At k, the grid splits into k x k interleaved sub-grids that each run
// the model on their own; seeds are k x k blocks, one cell in each, so
// together they grow one creature k times as large (an experiment -
// the copies can drift apart a little). Keep it the same in every pass.
#define DILATION {args.dilation}

// Setting ranges - keep in step with every pass.
#define SPEED_RANGE vec2(0.02, 1.0)
#define FIRE_RATE_RANGE vec2(0.05, 1.0)
#define BRUSH_RANGE vec2(0.03, 0.4)

// How many cells the grid has, for buffers res pixels in size. Buffers
// B and C need 16 texels a cell each, so on a small enough buffer (a
// low quality setting) the grid shrinks to fit rather than break.
ivec2 gridSize(vec2 res) {{
	float shortSide = min(res.x, res.y);
	float longSide = max(res.x, res.y);
	// 2x2 texels a cell in Buffers A and D, plus a spare row past them.
	float cellsShort = min(floor(shortSide / CELL_SIZE), floor((shortSide - 1.0) / 2.0));
	float cellsLong = FULL_FRAME == 1
			? min(floor(cellsShort * longSide / shortSide), floor((longSide - 1.0) / 2.0))
			: cellsShort;
	float capacity = floor(res.x * res.y / 16.0);
	if (cellsShort * cellsLong > capacity) {{
		float f = sqrt(capacity / (cellsShort * cellsLong));
		cellsShort = floor(cellsShort * f);
		cellsLong = floor(cellsLong * f);
	}}
	// Whole sub-grids (see DILATION), so they line up across the wrap.
	cellsShort = floor(cellsShort / float(DILATION)) * float(DILATION);
	cellsLong = floor(cellsLong / float(DILATION)) * float(DILATION);
	return res.x <= res.y
			? ivec2(cellsShort, cellsLong)
			: ivec2(cellsLong, cellsShort);
}}

// Where the grid is on screen: its bottom left corner, and the size of a
// cell. Grid rows run top to bottom, as in the images the model learned.
vec2 gridOrigin(vec2 res, ivec2 grid, out vec2 cellSize) {{
	if (FULL_FRAME == 1) {{
		cellSize = res / vec2(grid);
		return vec2(0.0);
	}}
	cellSize = vec2(min(res.x, res.y) / float(grid.x));
	return (res - vec2(grid) * cellSize) * 0.5;
}}

vec2 screenToGrid(vec2 pos, vec2 res, ivec2 grid) {{
	vec2 cellSize;
	vec2 g = (pos - gridOrigin(res, grid, cellSize)) / cellSize;
	return vec2(g.x, float(grid.y) - g.y);
}}

// Only ever a cell or so out of range - and % of a negative is undefined
// in GLSL, so add first.
ivec2 wrapCell(ivec2 c, ivec2 grid) {{
	return (c + grid) % grid;
}}

// Buffer A keeps, in the row just past the grid: the touch, the
// settings and the 3-finger gesture - see Buffer A.
ivec2 spareTexel(int i, ivec2 grid) {{
	return ivec2(i, 2 * grid.y);
}}
#define TOUCH 0
#define SETTINGS 1
#define GESTURE 2
#define TWIST 3
#define SETTINGS2 4
"""

# How Buffers B and C pack 16 texels for each cell: cell by cell, row
# by row, filling the buffer (so a tall grid fits a wide buffer too).
hidden_packing = """
// Buffers B and C: 16 texels a cell (4 hidden values each), cell after
// cell along the buffer's rows - see hiddenTexel().
ivec2 hiddenTexel(ivec2 cell, int k, ivec2 grid, int width) {
	int t = (cell.y * grid.x + cell.x) * 16 + k;
	return ivec2(t % width, t / width);
}
"""

image = credits + "\nuniform sampler2D iChannel0; ///buf:A;\n\n" + common + f"""
{label_arrays()}

{label_lookup()}
""" + """
// How cells are blended into pixels: 0 nearest (blocks), 1 bilinear,
// 2 Catmull-Rom cubic (smooth but sharp), 3 B-spline cubic (softest).
// All of them wrap around the grid's edges, so it still tiles.
#define INTERPOLATION 2

// The colour (channels 0-3, premultiplied) of a cell, wrapping.
vec4 cellColour(ivec2 c, ivec2 grid) {
	return texelFetch(iChannel0, wrapCell(c, grid) * 2, 0);
}

// The 4 weights for cells -1, 0, 1 and 2 around a point t of the way
// from cell 0 to cell 1.
vec4 cubicWeights(float t) {
	float t2 = t * t;
	float t3 = t2 * t;
	if (INTERPOLATION == 3) {
		return vec4(
				(1.0 - t) * (1.0 - t) * (1.0 - t),
				4.0 - 6.0 * t2 + 3.0 * t3,
				1.0 + 3.0 * t + 3.0 * t2 - 3.0 * t3,
				t3) / 6.0;
	}
	return vec4(
			-0.5 * t3 + t2 - 0.5 * t,
			1.5 * t3 - 2.5 * t2 + 1.0,
			-1.5 * t3 + 2.0 * t2 + 0.5 * t,
			0.5 * t3 - 0.5 * t2);
}

// One of the DILATION x DILATION interleaved sub-grids (see DILATION),
// sampled at sg in its own cells: sub-grid cell n is grid cell
// n * DILATION + phase. With DILATION 1 that's just the grid.
vec4 subCell(ivec2 n, ivec2 phase, ivec2 grid) {
	return cellColour(n * DILATION + phase, grid);
}

vec4 sampleSubGrid(vec2 sg, ivec2 phase, ivec2 grid) {
	if (INTERPOLATION == 0) {
		return subCell(ivec2(floor(sg + 0.5)), phase, grid);
	}
	ivec2 c = ivec2(floor(sg));
	vec2 f = fract(sg);
	if (INTERPOLATION == 1) {
		return mix(
				mix(subCell(c, phase, grid), subCell(c + ivec2(1, 0), phase, grid), f.x),
				mix(subCell(c + ivec2(0, 1), phase, grid), subCell(c + ivec2(1, 1), phase, grid),
						f.x),
				f.y);
	}
	vec4 wx = cubicWeights(f.x);
	vec4 wy = cubicWeights(f.y);
	vec4 sum = vec4(0.0);
	for (int y = 0; y < 4; ++y) {
		vec4 row = wx.x * subCell(c + ivec2(-1, y - 1), phase, grid) +
				wx.y * subCell(c + ivec2(0, y - 1), phase, grid) +
				wx.z * subCell(c + ivec2(1, y - 1), phase, grid) +
				wx.w * subCell(c + ivec2(2, y - 1), phase, grid);
		sum += row * wy[y];
	}
	return sum;
}

// g: a point on the grid, in cells. Each sub-grid is interpolated on
// its own - its cells are DILATION apart, and neighbouring grid cells
// belong to different copies - then the copies are averaged, so a
// dilated creature is as smooth as an undilated one, just bigger.
vec4 sampleCells(vec2 g, ivec2 grid) {
	vec4 sum = vec4(0.0);
	for (int py = 0; py < DILATION; ++py) {
		for (int px = 0; px < DILATION; ++px) {
			ivec2 phase = ivec2(px, py);
			sum += sampleSubGrid((g - 0.5 - vec2(phase)) / float(DILATION), phase, grid);
		}
	}
	return sum / float(DILATION * DILATION);
}

// The cells' colour over white - as the demo draws it.
vec3 field(vec2 fragCoord, ivec2 grid) {
	vec2 g = screenToGrid(fragCoord, iResolution.xy, grid);
	if (any(lessThan(g, vec2(0.0))) || any(greaterThanEqual(g, vec2(grid)))) {
		return vec3(0.92, 0.92, 0.9);
	}
	// Clamped: Catmull-Rom overshoots a little at hard edges.
	vec4 rgba = clamp(sampleCells(g, grid), 0.0, 1.0);
	return clamp(1.0 - rgba.a + rgba.rgb, 0.0, 1.0);
}

// Whether p (in glyph pixels from the glyph's bottom left) is lit in
// glyph `bits` - see GLYPHS in the generator.
bool glyphPixel(int bits, ivec2 p) {
	if (p.x < 0 || p.x > 2 || p.y < 0 || p.y > 4) {
		return false;
	}
	// The top row is the highest 3 bits; p.y counts up from the bottom.
	return ((bits >> (p.y * 3 + (2 - p.x))) & 1) == 1;
}

// One settings row (0 = the bottom one): its label, then a bar filled to
// `fraction`. Coverage of fragCoord: x = text, y = bar fill, z = outline.
vec3 settingRow(vec2 fragCoord, int row, float fraction, int which) {
	vec2 res = iResolution.xy;
	float rowHeight = res.y * 0.035;
	float y0 = res.y * 0.1 + float(row) * rowHeight * 1.4;
	if (fragCoord.y < y0 || fragCoord.y >= y0 + rowHeight) {
		return vec3(0.0);
	}
	float px = rowHeight / 7.0;
	vec2 local = (fragCoord - vec2(res.x * 0.05, y0 + px)) / px;
	ivec2 glyphPx = ivec2(floor(local));
	float text = 0.0;
	if (glyphPx.x >= 0 && glyphPx.x % 4 < 3) {
		int bits = labelGlyph(which, glyphPx.x / 4);
		if (bits >= 0 && glyphPixel(bits, ivec2(glyphPx.x % 4, glyphPx.y))) {
			text = 1.0;
		}
	}
""" + f"""	// Past the longest label ({LONGEST_LABEL} glyphs, 4 glyph pixels each).
	float barX0 = res.x * 0.05 + {4 * LONGEST_LABEL + 2}.0 * px;
""" + """	float barX1 = res.x * 0.95;
	float fill = 0.0;
	float outline = 0.0;
	if (fragCoord.x >= barX0 && fragCoord.x < barX1) {
		float t = (fragCoord.x - barX0) / (barX1 - barX0);
		fill = t <= fraction ? 1.0 : 0.0;
		float edge = min(min(fragCoord.x - barX0, barX1 - fragCoord.x),
				min(fragCoord.y - y0, y0 + rowHeight - fragCoord.y));
		outline = edge < 2.0 ? 1.0 : 0.0;
	}
	return vec3(text, fill, outline);
}

float logFraction(float v, vec2 range) {
	return log(v / range.x) / log(range.y / range.x);
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 grid = gridSize(iResolution.xy);
	vec3 colour = field(fragCoord, grid);
	if (texelFetch(iChannel0, spareTexel(GESTURE, grid), 0).w > 1.5) {
		vec4 settings = texelFetch(iChannel0, spareTexel(SETTINGS, grid), 0);
		vec4 settings2 = texelFetch(iChannel0, spareTexel(SETTINGS2, grid), 0);
		float fractions[4] = float[](
				(settings.x + 180.0) / 360.0,
				logFraction(settings.y, SPEED_RANGE),
				(settings.z - FIRE_RATE_RANGE.x) / (FIRE_RATE_RANGE.y - FIRE_RATE_RANGE.x),
				logFraction(settings2.x, BRUSH_RANGE));
		vec3 tints[4] = vec3[](vec3(0.2, 0.5, 1.0), vec3(0.2, 0.75, 0.3),
				vec3(1.0, 0.45, 0.1), vec3(0.6, 0.35, 0.9));
		for (int i = 0; i < 4; ++i) {
			vec3 c = settingRow(fragCoord, 3 - i, clamp(fractions[i], 0.0, 1.0), i);
			colour = mix(colour, tints[i], c.y * 0.6);
			colour = mix(colour, vec3(0.15), max(c.x, c.z));
		}
	}
	fragColor = vec4(colour, 1.0);
}
"""

buffer_a = """// Buffer A: each cell's 16 state channels, as a 2x2 block of texels
// (channels 0-3, 4-7 / 8-11, 12-15). Adds Buffer D's update to last
// step's state, then clears any cell with no living cell around it,
// before or after the update - as the demo's "update" pass. In the row
// past the grid it also keeps the touch, the settings and the gesture.
uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:D;
uniform int pointerCount;
uniform vec3 pointers[10];

""" + common + """
// Settings at start (and after Rewind) - see the header comment.
#define DEFAULT_ROTATION 0.0
#define DEFAULT_SPEED 1.0
#define DEFAULT_FIRE_RATE 0.5
// The 1-finger cut's radius, as a share of the grid's short side.
#define DEFAULT_BRUSH 0.12

// A cell lives while it, or a neighbour, has alpha above this.
#define ALIVE_THRESHOLD 0.1
// How far (in cells) a finger may wander and still count as a tap.
#define TAP_SLOP 1.5

// 3-finger gesture sensitivity: speed scaling (e^n) for a drag up the
// screen's height, fire rate for a pinch as wide as the screen is tall,
// brush scaling (e^n) for a drag across its width. A twist turns the
// rotation by as much as the fingers turn.
#define SPEED_SENSITIVITY 4.0
#define FIRE_RATE_SENSITIVITY 1.5
#define BRUSH_SENSITIVITY 2.0

// The spare texels (see spareTexel()):
//   TOUCH      xy = where the finger went down (grid cells), z = 1 once
//              it moved past TAP_SLOP (or more fingers joined), w = 1
//              while it's down
//   SETTINGS   x = rotation (degrees), y = speed (steps a frame), z =
//              fire rate, w = step accumulator
//   SETTINGS2  x = brush
//   GESTURE    xy = the three fingers' centre, z = their spread, w = 2
//              while three are down, 1 once they were this touch
//   TWIST      xyz = each finger's angle around the centre (radians)

vec4 state(ivec2 c, int group, ivec2 grid) {
	return texelFetch(iChannel0, wrapCell(c, grid) * 2 + ivec2(group & 1, group >> 1), 0);
}

vec4 update(ivec2 c, int group, ivec2 grid) {
	return texelFetch(iChannel1, wrapCell(c, grid) * 2 + ivec2(group & 1, group >> 1), 0);
}

vec4 spare(int i, ivec2 grid) {
	return texelFetch(iChannel0, spareTexel(i, grid), 0);
}

// A seed: alpha and every hidden channel at 1, colour 0.
vec4 seed(int group) {
	return group == 0 ? vec4(0.0, 0.0, 0.0, 1.0) : vec4(1.0);
}

// Whether cell is within radius (cells) of a finger at screen position
// pos. The grid wraps around, so a cut near one edge reaches the other.
bool underFinger(ivec2 cell, vec2 pos, float radius, vec2 res, ivec2 grid) {
	vec2 d = vec2(cell) + 0.5 - screenToGrid(pos, res, grid);
	d = min(abs(d), vec2(grid) - abs(d));
	return length(d) < radius;
}

// Whether cell is in the DILATION x DILATION seed block at gridPos.
bool inSeed(ivec2 cell, vec2 gridPos, ivec2 grid) {
	ivec2 d = wrapCell(cell - ivec2(floor(gridPos)) + DILATION / 2, grid);
	return d.x < DILATION && d.y < DILATION;
}

vec4 nextTouch(vec4 touch, vec2 res, ivec2 grid) {
	if (pointerCount == 0) {
		return vec4(touch.xyz, 0.0);
	}
	vec2 g = screenToGrid(pointers[0].xy, res, grid);
	if (touch.w < 0.5) {
		return vec4(g, 0.0, 1.0);
	}
	if (pointerCount > 1 || distance(g, touch.xy) > TAP_SLOP) {
		touch.z = 1.0;
	}
	return touch;
}

// The three fingers' centre (xy) and average distance from it (z).
vec3 threeFingers() {
	vec2 c = (pointers[0].xy + pointers[1].xy + pointers[2].xy) / 3.0;
	float spread = (distance(pointers[0].xy, c) + distance(pointers[1].xy, c) +
			distance(pointers[2].xy, c)) / 3.0;
	return vec3(c, spread);
}

vec3 fingerAngles(vec2 centre) {
	vec2 a = pointers[0].xy - centre;
	vec2 b = pointers[1].xy - centre;
	vec2 c = pointers[2].xy - centre;
	return vec3(atan(a.y, a.x), atan(b.y, b.x), atan(c.y, c.x));
}

vec4 nextGesture(vec4 gesture) {
	if (pointerCount >= 3) {
		return vec4(threeFingers(), 2.0);
	}
	if (pointerCount == 0) {
		return vec4(0.0);
	}
	// Fingers lifting one by one: remember three were down, so the two
	// or one still touching don't cut.
	return vec4(gesture.xyz, gesture.w > 0.5 ? 1.0 : 0.0);
}

// How far the fingers turned since last frame (radians, anticlockwise
// on screen), averaged over the three.
float twisted(vec3 before, vec3 now) {
	vec3 d = now - before;
	d = mod(d + 3.14159265, 6.2831853) - 3.14159265;
	return (d.x + d.y + d.z) / 3.0;
}

// Relative: each frame adds how far the fingers moved since the last,
// so a gesture can be repeated to keep going.
void nextSettings(inout vec4 settings, inout vec4 settings2, vec4 gesture, vec4 twist,
		vec2 res) {
	if (pointerCount >= 3 && gesture.w > 1.5) {
		vec3 now = threeFingers();
		vec2 moved = now.xy - gesture.xy;
		// The cells' "up" turns with the fingers - so does whatever grows
		// from here on.
		float turn = degrees(twisted(twist.xyz, fingerAngles(now.xy)));
		settings.x = mod(settings.x + turn + 180.0, 360.0) - 180.0;
		settings.y = clamp(settings.y * exp(moved.y / res.y * SPEED_SENSITIVITY),
				SPEED_RANGE.x, SPEED_RANGE.y);
		settings.z = clamp(settings.z + (now.z - gesture.z) / res.y * FIRE_RATE_SENSITIVITY,
				FIRE_RATE_RANGE.x, FIRE_RATE_RANGE.y);
		settings2.x = clamp(settings2.x * exp(moved.x / res.x * BRUSH_SENSITIVITY),
				BRUSH_RANGE.x, BRUSH_RANGE.y);
	}
	// Steps happen whenever the accumulator passes 1 - see stepsNow().
	settings.w = fract(settings.w + settings.y);
}

// Whether this frame takes a step: speed adds up, a step at each whole
// number. Uses last frame's settings, as every cell sees them.
bool stepsNow(vec4 settings) {
	return settings.w + settings.y >= 1.0;
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	vec2 res = iResolution.xy;
	ivec2 grid = gridSize(res);
	// A fresh start: the first frame, or buffers that came back empty
	// (changing the quality setting recreates them, while the frame
	// count carries on) - the speed setting is never 0 otherwise.
	bool first = iFrame == 0 || spare(SETTINGS, grid).y <= 0.0;
	vec4 touch = first ? vec4(0.0) : spare(TOUCH, grid);
	vec4 gesture = first ? vec4(0.0) : spare(GESTURE, grid);
	vec4 twist = first ? vec4(0.0) : spare(TWIST, grid);
	vec4 settings = first
			? vec4(DEFAULT_ROTATION, DEFAULT_SPEED, DEFAULT_FIRE_RATE, 0.0)
			: spare(SETTINGS, grid);
	vec4 settings2 = first ? vec4(DEFAULT_BRUSH, 0.0, 0.0, 0.0) : spare(SETTINGS2, grid);
	if (p.y == 2 * grid.y && p.x <= SETTINGS2) {
		if (p.x == TOUCH) {
			fragColor = nextTouch(touch, res, grid);
		} else if (p.x == GESTURE) {
			fragColor = nextGesture(gesture);
		} else if (p.x == TWIST) {
			fragColor = pointerCount >= 3 ? vec4(fingerAngles(threeFingers().xy), 0.0) : twist;
		} else {
			if (!first) {
				nextSettings(settings, settings2, gesture, twist, res);
			}
			fragColor = p.x == SETTINGS ? settings : settings2;
		}
		return;
	}
	ivec2 cell = p / 2;
	int group = (p.x & 1) + 2 * (p.y & 1);
	if (cell.x >= grid.x || cell.y >= grid.y) {
		fragColor = vec4(0.0);
		return;
	}
	if (first) {
		fragColor = inSeed(cell, vec2(grid / 2), grid) ? seed(group) : vec4(0.0);
		return;
	}

	vec4 s = state(cell, group, grid);
	if (stepsNow(settings)) {
		float preMaxAlpha = 0.0;
		float postMaxAlpha = 0.0;
		for (int y = -1; y <= 1; ++y) {
			for (int x = -1; x <= 1; ++x) {
				ivec2 n = cell + ivec2(x, y) * DILATION;
				float preAlpha = state(n, 0, grid).a;
				preMaxAlpha = max(preMaxAlpha, preAlpha);
				postMaxAlpha = max(postMaxAlpha, preAlpha + update(n, 0, grid).a);
			}
		}
		s += update(cell, group, grid);
		if (min(preMaxAlpha, postMaxAlpha) < ALIVE_THRESHOLD) {
			s = vec4(0.0);
		}
	}

	// A drag cuts (once the finger has moved - so a tap doesn't), a tap
	// plants a seed where it went down once the finger lifts. Nothing
	// cuts during (or after) a 3-finger gesture until every finger lifts.
	float brush = settings2.x * float(min(grid.x, grid.y));
	bool adjusting = gesture.w > 0.5 || pointerCount >= 3;
	bool dragging = pointerCount == 1 && touch.w > 0.5 &&
			(touch.z > 0.5 ||
			distance(screenToGrid(pointers[0].xy, res, grid), touch.xy) > TAP_SLOP);
	bool tapped = pointerCount == 0 && touch.w > 0.5 && touch.z < 0.5;
	if (adjusting) {
		// Leave the cells be.
	} else if (pointerCount == 2) {
		if (underFinger(cell, pointers[0].xy, brush * 2.0, res, grid) ||
				underFinger(cell, pointers[1].xy, brush * 2.0, res, grid)) {
			s = vec4(0.0);
		}
	} else if (dragging) {
		if (underFinger(cell, pointers[0].xy, brush, res, grid)) {
			s = vec4(0.0);
		}
	} else if (tapped && inSeed(cell, touch.xy, grid)) {
		s = seed(group);
	}
	fragColor = s;
}
"""


def hidden_buffer(letter, half):
	first_unit = half * 16
	return f"""// Buffer {letter}: half of the network's hidden layer - hidden values
// {half * 64}-{half * 64 + 63} of each cell's 128, 16 texels a cell (see hiddenTexel()).
// Each texel works out 4 of them: it "perceives" the cell (its 16
// channels, plus their Sobel x and y gradients, turned by the rotation
// setting - 48 inputs) and applies the first dense layer, then ReLU.
uniform sampler2D iChannel0; ///buf:A;

""" + common + hidden_packing + weights_glsl + f"""
#define W1_SCALE {layers[0]['weight_scale']!r}
#define B1_SCALE {layers[0]['bias_scale']!r}
// This buffer's first group of 4 hidden values (of 32).
#define FIRST_UNIT {first_unit}

vec4 weight(int i) {{
	return (weightBytes(i) - 0.5) * W1_SCALE;
}}
""" + """
vec4 state(ivec2 c, int group, ivec2 grid) {
	return texelFetch(iChannel0, wrapCell(c, grid) * 2 + ivec2(group & 1, group >> 1), 0);
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	ivec2 grid = gridSize(iResolution.xy);
	int width = int(iResolution.x);
	int t = p.y * width + p.x;
	int cellIndex = t / 16;
	if (cellIndex >= grid.x * grid.y) {
		fragColor = vec4(0.0);
		return;
	}
	ivec2 cell = ivec2(cellIndex % grid.x, cellIndex / grid.x);
	int unit = FIRST_UNIT + t % 16;

	// Sobel filters, /8, over grid rows running downwards - the demo's.
	vec4 id[4];
	vec4 dx[4];
	vec4 dy[4];
	for (int g = 0; g < 4; ++g) {
		id[g] = state(cell, g, grid);
		dx[g] = vec4(0.0);
		dy[g] = vec4(0.0);
	}
	for (int y = -1; y <= 1; ++y) {
		for (int x = -1; x <= 1; ++x) {
			float wx = float(x) * (y == 0 ? 2.0 : 1.0) / 8.0;
			float wy = float(y) * (x == 0 ? 2.0 : 1.0) / 8.0;
			if (wx == 0.0 && wy == 0.0) {
				continue;
			}
			for (int g = 0; g < 4; ++g) {
				vec4 a = state(cell + ivec2(x, y) * DILATION, g, grid);
				dx[g] += wx * a;
				dy[g] += wy * a;
			}
		}
	}
	// Turned by the rotation setting, as the demo's angle slider does.
	float angle = radians(texelFetch(iChannel0, spareTexel(SETTINGS, grid), 0).x);
	float s = sin(angle);
	float c = cos(angle);
	for (int g = 0; g < 4; ++g) {
		vec4 gx = dx[g];
		dx[g] = gx * c - dy[g] * s;
		dy[g] = gx * s + dy[g] * c;
	}

	// Inputs in the demo's order: the 16 channels, then their x
	// gradients, then their y gradients. Each input's weights for this
	// texel's 4 hidden values are one vec4.
	vec4 h = (weightBytes(48 * 32 + unit) - 0.5) * B1_SCALE;
	for (int g = 0; g < 4; ++g) {
		int i = g * 4;
		h += id[g].x * weight((i + 0) * 32 + unit) + id[g].y * weight((i + 1) * 32 + unit)
				+ id[g].z * weight((i + 2) * 32 + unit) + id[g].w * weight((i + 3) * 32 + unit);
		i += 16;
		h += dx[g].x * weight((i + 0) * 32 + unit) + dx[g].y * weight((i + 1) * 32 + unit)
				+ dx[g].z * weight((i + 2) * 32 + unit) + dx[g].w * weight((i + 3) * 32 + unit);
		i += 16;
		h += dy[g].x * weight((i + 0) * 32 + unit) + dy[g].y * weight((i + 1) * 32 + unit)
				+ dy[g].z * weight((i + 2) * 32 + unit) + dy[g].w * weight((i + 3) * 32 + unit);
	}
	fragColor = max(h, 0.0);
}
"""


buffer_d = """// Buffer D: each cell's update - the second dense layer (128 hidden
// values, from Buffers B and C, -> 16 channels), laid out like Buffer
// A. Like the demo, only some of the cells update each step: the fire
// rate setting's share.
uniform sampler2D iChannel0; ///buf:B;
uniform sampler2D iChannel1; ///buf:C;
uniform sampler2D iChannel2; ///buf:A;

""" + common + hidden_packing + weights_glsl + f"""
#define W2_SCALE {layers[1]['weight_scale']!r}
#define B2_SCALE {layers[1]['bias_scale']!r}

vec4 weight(int i) {{
	return (weightBytes(1568 + i) - 0.5) * W2_SCALE;
}}
""" + """
// "Hash without Sine" by David Hoskins (https://www.shadertoy.com/view/4djSRW)
float hash13(vec3 p3) {
	p3 = fract(p3 * 0.1031);
	p3 += dot(p3, p3.yzx + 33.33);
	return fract((p3.x + p3.y) * p3.z);
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	ivec2 grid = gridSize(iResolution.xy);
	ivec2 cell = p / 2;
	int group = (p.x & 1) + 2 * (p.y & 1);
	if (cell.x >= grid.x || cell.y >= grid.y) {
		fragColor = vec4(0.0);
		return;
	}
	float fireRate = texelFetch(iChannel2, spareTexel(SETTINGS, grid), 0).z;
	float stepSeed = fract(float(iFrame) * 0.6180339887) * 1000.0;
	if (hash13(vec3(vec2(cell) + 0.5, stepSeed)) > fireRate) {
		fragColor = vec4(0.0);
		return;
	}
	int width = int(iResolution.x);
	vec4 u = (weightBytes(1568 + 128 * 4 + group) - 0.5) * B2_SCALE;
	for (int j = 0; j < 32; ++j) {
		ivec2 at = hiddenTexel(cell, j % 16, grid, width);
		vec4 h = j < 16 ? texelFetch(iChannel0, at, 0) : texelFetch(iChannel1, at, 0);
		int i = j * 4;
		u += h.x * weight((i + 0) * 4 + group) + h.y * weight((i + 1) * 4 + group)
				+ h.z * weight((i + 2) * 4 + group) + h.w * weight((i + 3) * 4 + group);
	}
	fragColor = u;
}
"""

with open(args.shader, 'w') as f:
	f.write(image + '//// Buffer A\n' + buffer_a +
			'//// Buffer B\n' + hidden_buffer('B', 0) +
			'//// Buffer C\n' + hidden_buffer('C', 1) +
			'//// Buffer D\n' + buffer_d)
print('wrote', args.shader, 'and', args.weights)
