#!/usr/bin/env python3
"""Converts a Ready (GollyGang/ready) reaction-diffusion pattern - a .vti
file with a 2D "formula" rule - into an Enki Shader Editor multipass
shader: the same text that Copy/Paste and samples use (the Image pass,
then `//// Buffer A` etc.), ready to paste, open, or put in a pack.

How it maps Ready onto buffers (see FormulaOpenCLImageRD.cpp, stencils.cpp,
overlays.cpp and scene_items.cpp in Ready's src/readybase, which this
follows):
- Each buffer holds four chemicals, one per channel, as 32-bit floats
  (`///float32`). Up to 4 chemicals: Buffers A-D each take one step,
  reading the one before (A reads D's previous frame), so 4 steps a
  frame. 5-8 chemicals: A+B hold one state and C+D the next, 2 steps a
  frame; each pass works out the whole formula and keeps its own four.
- One step is Ready's: every cell reads the previous state, the stencil
  keywords (laplacian_a...) are worked out from it, the formula runs, and
  each chemical becomes `chem + timestep * delta_chem`.
- Parameters become #defines (so Adjust Defines can change them live).
- The initial pattern generator runs on the first frame (Restart reseeds
  its noise), and the Image pass shows the active chemical through
  Ready's colour map, low-high range.
- The grid is the buffers' size - the screen times the quality setting -
  not the file's; positions in the initial pattern are relative, so they
  scale with it.

Not converted (reported, and the file skipped): kernel rules, inbuilt
rules other than Gray-Scott, meshes, 1D and 3D patterns, more than 8
chemicals. A pattern that starts from the state stored in the file
(apply_when_loading false) starts from its initial pattern generator, or
zeros, instead - also reported.

Painting: a finger on the shader paints within a brush radius, in each
frame's first step - by default a and b as the initial pattern sets
them, fresh noise and all (`--paint "a=init,b=init"`; or fixed values,
`--paint "a=0.5,b=0.25"`; `--paint ""` none). Two fingers erase
instead, in every step and a wider brush - by default a = 1, b = 0,
Gray-Scott's empty state (`--erase`). The values and radii are #defines.

Usage: vti2enki.py pattern.vti [-o out.glsl] [--colormap NAME] [--paint SPEC]
		[--erase SPEC] [--masks]
"""
import argparse
import math
import os
import re
import sys
import xml.etree.ElementTree as ET


class Unsupported(Exception):
	pass


# region Stencils - ported from Ready's stencils.cpp

def rot3x3(c1, c2, c3):
	return [[c1, c2, c1], [c2, c3, c2], [c1, c2, c1]]


def rot5x5(c1, c2, c3, c4, c5, c6):
	return [[c1, c2, c3, c2, c1],
			[c2, c4, c5, c4, c2],
			[c3, c5, c6, c5, c3],
			[c2, c4, c5, c4, c2],
			[c1, c2, c3, c2, c1]]


class Stencil:
	# points: [((dx, dy), weight)]; the code divides by
	# divisor * dx^dx_power.
	def __init__(self, label, points, divisor, dx_power):
		self.label = label
		self.points = points
		self.divisor = divisor
		self.dx_power = dx_power


def from_1d(label, arr, divisor, dx_power, dim):
	points = []
	n = len(arr)
	for i, w in enumerate(arr):
		if w:
			off = [0, 0]
			off[dim] = i - (n - 1) // 2
			points.append((tuple(off), w))
	return Stencil(label, points, divisor, dx_power)


def from_2d(label, arr, divisor, dx_power):
	points = []
	m = len(arr)
	n = len(arr[0])
	for j in range(m):
		for i in range(n):
			if arr[j][i]:
				# Rows are in reading order, heading south (negative y).
				points.append(((i - (n - 1) // 2, -j + (m - 1) // 2), arr[j][i]))
	return Stencil(label, points, divisor, dx_power)


def known_stencils(accuracy):
	s = []
	if accuracy in ("low", "medium"):
		s += [from_1d("x_gradient", [-1, 0, 1], 2, 1, 0),
				from_1d("y_gradient", [-1, 0, 1], 2, 1, 1),
				from_1d("x_deriv2", [1, -2, 1], 1, 2, 0),
				from_1d("y_deriv2", [1, -2, 1], 1, 2, 1),
				from_1d("x_deriv3", [-1, 2, 0, -2, 1], 2, 3, 0),
				from_1d("y_deriv3", [-1, 2, 0, -2, 1], 2, 3, 1)]
	else:
		s += [from_1d("x_gradient", [1, -8, 0, 8, -1], 12, 1, 0),
				from_1d("y_gradient", [1, -8, 0, 8, -1], 12, 1, 1),
				from_1d("x_deriv2", [-1, 16, -30, 16, -1], 12, 2, 0),
				from_1d("y_deriv2", [-1, 16, -30, 16, -1], 12, 2, 1),
				from_1d("x_deriv3", [1, -8, 13, 0, -13, 8, -1], 8, 3, 0),
				from_1d("y_deriv3", [1, -8, 13, 0, -13, 8, -1], 8, 3, 1)]
	s.append(from_2d("gaussian", rot5x5(1, 4, 7, 16, 26, 41), 273, 0))
	if accuracy == "low":
		s.append(from_2d("laplacian", rot3x3(0, 1, -4), 1, 2))
	elif accuracy == "medium":
		# "Mehrstellen"
		s.append(from_2d("laplacian", rot3x3(1, 4, -20), 6, 2))
	else:
		s.append(from_2d("laplacian", rot5x5(0, -2, -1, 16, 52, -252), 60, 2))
	s.append(from_2d("bilaplacian", rot5x5(0, 1, 1, -2, -10, 36), 3, 4))
	s.append(from_2d("trilaplacian", [
			[0, 1, 5, 6, 5, 1, 0],
			[1, 6, -33, -56, -33, 6, 1],
			[5, -33, 6, 314, 6, -33, 5],
			[6, -56, 314, -888, 314, -56, 6],
			[5, -33, 6, 314, 6, -33, 5],
			[1, 6, -33, -56, -33, 6, 1],
			[0, 1, 5, 6, 5, 1, 0]], 18, 6))
	s.append(from_2d("sobelN", [[1, 2, 1], [0, 0, 0], [-1, -2, -1]], 1, 0))
	s.append(from_2d("sobelS", [[-1, -2, -1], [0, 0, 0], [1, 2, 1]], 1, 0))
	s.append(from_2d("sobelE", [[-1, 0, 1], [-2, 0, 2], [-1, 0, 1]], 1, 0))
	s.append(from_2d("sobelW", [[1, 0, -1], [2, 0, -2], [1, 0, -1]], 1, 0))
	s.append(from_2d("sobelNW", [[2, 1, 0], [1, 0, -1], [0, -1, -2]], 1, 0))
	s.append(from_2d("sobelNE", [[0, 1, 2], [-1, 0, 1], [-2, -1, 0]], 1, 0))
	s.append(from_2d("sobelSW", [[0, -1, -2], [1, 0, -1], [2, 1, 0]], 1, 0))
	s.append(from_2d("sobelSE", [[-2, -1, 0], [-1, 0, 1], [0, 1, 2]], 1, 0))
	return s


def point_name(off):
	# As Ready's Point::GetName(): "ne", "n2", "sw3"...
	dx, dy = off
	out = ""
	if dy > 0:
		out += "n" + (str(dy) if dy > 1 else "")
	elif dy < 0:
		out += "s" + (str(-dy) if dy < -1 else "")
	if dx > 0:
		out += "e" + (str(dx) if dx > 1 else "")
	elif dx < 0:
		out += "w" + (str(-dx) if dx < -1 else "")
	return out


def cell_name(chem, off):
	name = point_name(off)
	return chem + ("_" + name if name else "")


def stencil_code(stencil, chem):
	# As Ready's AppliedStencil::GetCode(), with float weights for GLSL.
	weights = {}
	for off, w in stencil.points:
		weights.setdefault(w, []).append(off)
	terms = []
	for w in sorted(weights):
		names = [cell_name(chem, off) for off in weights[w]]
		sum_code = " + ".join(names)
		if w == 1:
			terms.append(sum_code)
		elif len(names) > 1:
			terms.append("%s * (%s)" % (glsl_float(w), sum_code))
		else:
			terms.append("%s * %s" % (glsl_float(w), sum_code))
	code = " + ".join(terms)
	if stencil.divisor != 1 or stencil.dx_power > 0:
		div = glsl_float(stencil.divisor) + "".join(" * dx" for _ in range(stencil.dx_power))
		code = "(%s) / (%s)" % (code, div)
	return "%s_%s = %s" % (stencil.label, chem, code)

# endregion


# region Formula translation: OpenCL C -> GLSL ES 3.00

OPENCL_FUNCTIONS = {
	"fabs": "float fabs(float x) { return abs(x); }",
	"fmin": "float fmin(float x, float y) { return min(x, y); }",
	"fmax": "float fmax(float x, float y) { return max(x, y); }",
	# OpenCL's fmod truncates, GLSL's mod floors.
	"fmod": "float fmod(float x, float y) { return x - y * trunc(x / y); }",
	"mad": "float mad(float a, float b, float c) { return a * b + c; }",
	"hypot": "float hypot(float x, float y) { return sqrt(x * x + y * y); }",
	"rsqrt": "float rsqrt(float x) { return inversesqrt(x); }",
	"cbrt": "float cbrt(float x) { return sign(x) * pow(abs(x), 1.0 / 3.0); }",
	"exp10": "float exp10(float x) { return pow(10.0, x); }",
	"log10": "float log10(float x) { return log(x) * 0.4342944819; }",
	"powr": "float powr(float x, float y) { return pow(x, y); }",
	"pown": "float pown(float x, float n) { return rdPow(x, n); }",
	"rootn": "float rootn(float x, float n) { return rdPow(x, 1.0 / n); }",
	"native_divide": "float native_divide(float x, float y) { return x / y; }",
	"native_recip": "float native_recip(float x) { return 1.0 / x; }",
	"native_exp": "float native_exp(float x) { return exp(x); }",
	"native_log": "float native_log(float x) { return log(x); }",
	"native_sqrt": "float native_sqrt(float x) { return sqrt(x); }",
	"native_rsqrt": "float native_rsqrt(float x) { return inversesqrt(x); }",
	"native_sin": "float native_sin(float x) { return sin(x); }",
	"native_cos": "float native_cos(float x) { return cos(x); }",
	"native_tan": "float native_tan(float x) { return tan(x); }",
	"native_powr": "float native_powr(float x, float y) { return pow(x, y); }",
	"half_exp": "float half_exp(float x) { return exp(x); }",
	"half_sqrt": "float half_sqrt(float x) { return sqrt(x); }",
}
OPENCL_CONSTANTS = {
	"M_PI_F": "3.14159265", "M_PI": "3.14159265",
	"M_E_F": "2.71828183", "M_E": "2.71828183",
	"M_SQRT2_F": "1.41421356", "M_SQRT2": "1.41421356",
}
# pow() of a negative number is undefined in GLSL but fine in OpenCL for
# whole powers - pow(x, 2.0f) on a gradient, say.
RD_POW = ("float rdPow(float x, float y) {\n"
		"\tif (x < 0.0 && floor(y) == y) {\n"
		"\t\treturn mod(y, 2.0) == 0.0 ? pow(-x, y) : -pow(-x, y);\n"
		"\t}\n"
		"\treturn pow(x, y);\n"
		"}")
GLSL_RESERVED = {
	"input", "output", "sample", "filter", "active", "common", "partition",
	"texture", "attribute", "varying", "buffer", "shared", "flat", "smooth",
	"layout", "uniform", "in", "out", "inout", "precision", "lowp", "mediump",
	"highp", "discard", "struct", "switch", "default", "union", "enum",
	"half", "fixed", "long", "short", "double", "unsigned", "superp", "cast",
	"namespace", "using", "template", "this", "packed", "goto", "inline",
	"noinline", "volatile", "public", "static", "extern", "external",
	"interface", "resource", "patch", "subroutine", "row_major", "sizeof",
	"main", "mainImage", "fragColor", "fragCoord", "iFrame", "iTime",
	"iResolution", "iChannel0", "iChannel1", "startRandom",
	# mainImage()'s own locals. The parameters' #defines come first, so
	# every identifier in the helper functions is added too (see
	# HELPER_NAMES below): a parameter called `u` mustn't rewrite them.
	"p", "size", "rel", "val", "grid", "state", "i", "painting", "erasing",
	"touching", "away", "seed", "rdInitial",
	"LOW", "HIGH",
}
TOKEN = re.compile(r"[A-Za-z_]\w*")


def glsl_float(v):
	# A GLSL float literal for v.
	if isinstance(v, str):
		v = float(v)
	if v == int(v) and abs(v) < 1e15:
		return "%d.0" % int(v)
	r = repr(float(v))
	return r if ("." in r or "e" in r) else r + ".0"


def safe_name(name):
	return name + "_" if name in GLSL_RESERVED else name


def rename_reserved(code, names):
	if not names:
		return code
	return TOKEN.sub(lambda m: m.group(0) + "_" if m.group(0) in names else m.group(0), code)


def translate_formula(formula, renamed):
	code = formula
	# Casts: "(float4)(x)" -> "float(x)", "(float4)x" -> "float(x)".
	code = re.sub(r"\(\s*(?:float|double)4?\s*\)\s*\(", "float(", code)
	code = re.sub(r"\(\s*(?:float|double)4?\s*\)\s*([A-Za-z_]\w*|[0-9][\w.]*)",
			r"float(\1)", code)
	code = re.sub(r"\b(?:float|double)4\b", "float", code)
	code = re.sub(r"\bdouble\b", "float", code)
	code = re.sub(r"\bint4\b", "int", code)
	# Number suffixes, then integer literals as floats (GLSL ES won't
	# mix ints into float maths).
	code = re.sub(r"\b(\d+\.\d*(?:[eE][+-]?\d+)?|\.\d+(?:[eE][+-]?\d+)?|\d+[eE][+-]?\d+|\d+)[fF]\b",
			r"\1", code)
	code = re.sub(r"(?<![\w.])(\d+)(?![\w.])", r"\1.0", code)
	code = re.sub(r"\bpow\s*\(", "rdPow(", code)
	for name, value in OPENCL_CONSTANTS.items():
		code = re.sub(r"\b%s\b" % name, value, code)
	return rename_reserved(code, renamed)

# endregion


# region Colour maps - see Ready's scene_items.cpp GetColorMap()

def hex_rgb(h):
	return ((h >> 16) / 255.0, ((h >> 8) & 255) / 255.0, (h & 255) / 255.0)


BREWER = {
	"spectral": [0xD53E4F, 0xF46D43, 0xFDAE61, 0xFEE08B, 0xFFFFBF, 0xE6F598, 0xABDDA4,
			0x66C2A5, 0x3288BD],
	"purple-orange": [0x7F3B08, 0xB35806, 0xE08214, 0xFDB863, 0xFEE0B6, 0xF7F7F7, 0xD8DAEB,
			0xB2ABD2, 0x8073AC, 0x542788, 0x2D004B],
	"brown-teal": [0x543005, 0x8C510A, 0xBF812D, 0xDFC27D, 0xF6E8C3, 0xF5F5F5, 0xC7EAE5,
			0x80CDC1, 0x35978F, 0x01665E, 0x003C30],
}
TERRAIN = [(0.2, 0.2, 0.6), (0.0, 0.6, 1.0), (0.0, 0.8, 0.4), (1.0, 1.0, 0.6),
		(0.5, 0.36, 0.33), (1.0, 1.0, 1.0)]
# Every 16th of Ready's 256 (matplotlib's inferno), and the last.
INFERNO = [(0.001462, 0.000466, 0.013866), (0.042253, 0.028139, 0.141141),
		(0.129285, 0.047293, 0.290788), (0.238273, 0.036621, 0.396353),
		(0.341500, 0.062325, 0.429425), (0.441207, 0.099338, 0.431594),
		(0.541039, 0.134952, 0.418476), (0.640135, 0.171438, 0.390886),
		(0.735683, 0.215906, 0.330245), (0.822386, 0.275197, 0.262138),
		(0.894305, 0.353399, 0.184025), (0.946965, 0.449819, 0.099203),
		(0.975082, 0.557528, 0.033426), (0.977497, 0.677796, 0.093425),
		(0.950018, 0.802095, 0.249217), (0.948683, 0.917397, 0.453720),
		(0.988362, 0.998364, 0.644924)]


def colour_stops(name):
	# (stops, reversed): stops evenly spaced from low to high; reversed
	# runs them from high to low, as Ready does.
	if name == "spectral":
		return [hex_rgb(h) for h in BREWER["spectral"]], True
	if name == "spectral reversed":
		return [hex_rgb(h) for h in BREWER["spectral"]], False
	if name == "orange-purple":
		return [hex_rgb(h) for h in BREWER["purple-orange"]], False
	if name == "purple-orange":
		return [hex_rgb(h) for h in BREWER["purple-orange"]], True
	if name == "brown-teal":
		return [hex_rgb(h) for h in BREWER["brown-teal"]], False
	if name == "teal-brown":
		return [hex_rgb(h) for h in BREWER["brown-teal"]], True
	if name == "inferno":
		return INFERNO, False
	if name == "inferno reversed":
		return INFERNO, True
	if name == "terrain":
		return TERRAIN, False
	if name == "terrain reversed":
		return TERRAIN, True
	raise Unsupported("colour map %r" % name)


def vec3(c):
	return "vec3(%s, %s, %s)" % tuple(glsl_float(round(x, 4)) for x in c)

# endregion


# region Reading the file

class Pattern:
	pass


def text_of(node):
	return (node.text or "").strip() if node is not None else ""


def read_vti(path):
	root = ET.parse(path).getroot()
	if root.get("type") != "ImageData":
		raise Unsupported("not image data (a mesh?)")
	rd = root.find("RD")
	if rd is None:
		raise Unsupported("no RD section")
	p = Pattern()
	p.description = text_of(rd.find("description"))
	rule = rd.find("rule")
	p.rule_name = rule.get("name", "")
	p.rule_type = rule.get("type", "")
	p.wrap = rule.get("wrap", "1") == "1"
	p.params = [(param.get("name").strip(), float(text_of(param)))
			for param in rule.findall("param")]
	if p.rule_type == "formula":
		formula = rule.find("formula")
		p.n_chemicals = int(formula.get("number_of_chemicals"))
		p.accuracy = formula.get("accuracy", "medium")
		p.formula = text_of(formula)
	elif p.rule_type == "inbuilt" and p.rule_name == "Gray-Scott":
		# GrayScottImageRD, as a formula.
		p.n_chemicals = 2
		p.accuracy = "medium"
		k = "k" if any(n == "k" for n, _ in p.params) else "K"
		p.formula = ("delta_a = D_a * laplacian_a - a*b*b + F*(1.0f-a);\n"
				"delta_b = D_b * laplacian_b + a*b*b - (F+%s)*b;" % k)
	else:
		raise Unsupported("%s rule %r" % (p.rule_type, p.rule_name))
	if p.n_chemicals > 8:
		raise Unsupported("%d chemicals (8 at most)" % p.n_chemicals)

	image = root.find("ImageData")
	extent = [int(v) for v in image.get("WholeExtent").split()]
	p.size = (extent[1] - extent[0] + 1, extent[3] - extent[2] + 1, extent[5] - extent[4] + 1)
	if p.size[2] > 1:
		raise Unsupported("3D (%dx%dx%d)" % p.size)
	if p.size[1] == 1:
		raise Unsupported("1D (%d cells)" % p.size[0])

	ipg = rd.find("initial_pattern_generator")
	p.notes = []
	p.overlays = []
	p.zero_first = True
	if ipg is not None:
		p.zero_first = ipg.get("zero_first", "true") != "false"
		applies = ipg.get("apply_when_loading", "true") == "true"
		if not applies:
			p.notes.append("Ready starts this one from the state saved in the file; "
					"this starts from its initial pattern generator instead.")
		p.overlays = list(ipg.findall("overlay"))
	else:
		p.notes.append("No initial pattern generator: everything starts at 0.")
	if not p.zero_first:
		p.notes.append("The initial pattern is laid over the saved state in Ready; "
				"here over zeros.")

	settings = {}
	render = rd.find("render_settings")
	if render is not None:
		for node in render:
			settings[node.tag] = node
	p.render = settings
	return p


def setting(p, name, default):
	node = p.render.get(name)
	return node.get("value", default) if node is not None else default


def colour_setting(p, name, default):
	node = p.render.get(name)
	if node is None:
		return default
	return (float(node.get("r")), float(node.get("g")), float(node.get("b")))

# endregion


# region Initial pattern - see Ready's overlays.cpp

def chem_index(name):
	return ord(name) - ord("a")


def point3(node):
	return tuple(float(node.get(axis)) for axis in "xyz")


def fill_code(fill, p, target, salt):
	tag = fill.tag
	if tag == "constant":
		return glsl_float(fill.get("value"))
	if tag == "white_noise":
		lo = float(fill.get("low"))
		hi = float(fill.get("high"))
		return "mix(%s, %s, rdRandom(p, seed + %d))" % (glsl_float(lo), glsl_float(hi), salt)
	if tag == "perlin_noise":
		scale = float(fill.get("scale", 64))
		octaves = int(fill.get("num_octaves", 8))
		return "rdPerlin(vec2(p) / %s, %d, seed + %d)" % (glsl_float(scale), octaves, salt)
	if tag == "other_chemical":
		return fill.get("chemical")
	if tag == "parameter":
		return safe_name(fill.get("name").strip())
	pts = [point3(n) for n in fill.findall("point3D")]
	if tag == "linear_gradient":
		(x1, y1, _), (x2, y2, _) = pts
		return ("rdLinear(rel, vec2(%s, %s), vec2(%s, %s), %s, %s)" % (
				glsl_float(x1), glsl_float(y1), glsl_float(x2), glsl_float(y2),
				glsl_float(fill.get("val1")), glsl_float(fill.get("val2"))))
	if tag == "radial_gradient":
		(x1, y1, _), (x2, y2, _) = pts
		return ("rdRadial(vec2(p), grid, vec2(%s, %s), vec2(%s, %s), %s, %s)" % (
				glsl_float(x1), glsl_float(y1), glsl_float(x2), glsl_float(y2),
				glsl_float(fill.get("val1")), glsl_float(fill.get("val2"))))
	if tag == "gaussian":
		(x, y, _), = pts
		return "rdGaussian(vec2(p), grid, vec2(%s, %s), %s, %s)" % (
				glsl_float(x), glsl_float(y), glsl_float(fill.get("height")),
				glsl_float(fill.get("sigma")))
	if tag == "sine":
		(x1, y1, _), (x2, y2, _) = pts
		return ("rdSine(rel, vec2(%s, %s), vec2(%s, %s), %s, %s)" % (
				glsl_float(x1), glsl_float(y1), glsl_float(x2), glsl_float(y2),
				glsl_float(fill.get("phase")), glsl_float(fill.get("amplitude"))))
	raise Unsupported("initial pattern fill %r" % tag)


def shape_code(shape, p):
	tag = shape.tag
	if tag == "everywhere":
		return "true"
	pts = [point3(n) for n in shape.findall("point3D")]
	if tag == "rectangle":
		(x1, y1, _), (x2, y2, _) = pts
		return "rdInRect(rel, vec2(%s, %s), vec2(%s, %s))" % (
				glsl_float(x1), glsl_float(y1), glsl_float(x2), glsl_float(y2))
	if tag == "circle":
		(x, y, _), = pts
		return "rdInCircle(vec2(p), grid, vec2(%s, %s), %s)" % (
				glsl_float(x), glsl_float(y), glsl_float(shape.get("radius")))
	if tag == "pixel":
		# Scaled from the file's grid to ours.
		px = float(shape.get("x")) / p.size[0]
		py = float(shape.get("y")) / p.size[1]
		return "all(equal(p, ivec2(floor(vec2(%s, %s) * vec2(grid) + 0.5))))" % (
				glsl_float(px), glsl_float(py))
	raise Unsupported("initial pattern shape %r" % tag)


OPS = {"overwrite": "val = %s;", "add": "val += %s;", "subtract": "val -= %s;",
		"multiply": "val *= %s;", "divide": "val /= %s;"}


def overlay_code(p, chems):
	lines = []
	for i, overlay in enumerate(p.overlays):
		target = overlay.get("chemical")
		if chem_index(target) >= p.n_chemicals:
			continue  # As Ready, silently.
		op = fill = None
		shapes = []
		for node in overlay:
			if node.tag in OPS:
				op = node.tag
			elif node.tag in ("constant", "white_noise", "perlin_noise", "other_chemical",
					"parameter", "linear_gradient", "radial_gradient", "gaussian", "sine"):
				fill = node
			else:
				shapes.append(node)
		if op is None or fill is None or not shapes:
			raise Unsupported("an incomplete overlay")
		lines.append("\t{ // %s %s" % (target, op))
		lines.append("\t\tfloat val = %s;" % target)
		for j, shape in enumerate(shapes):
			# Ready's fill sees the value as changed so far (val), so a
			# chemical's own value reads back as val.
			value = fill_code(fill, p, target, i * 16 + j)
			if fill.tag == "other_chemical" and value == target:
				value = "val"
			lines.append("\t\tif (%s) {" % shape_code(shape, p))
			lines.append("\t\t\t" + OPS[op] % value)
			lines.append("\t\t}")
		lines.append("\t\t%s = val;" % target)
		lines.append("\t}")
	return lines


# Gestures, kept in the state row's first texel from frame to frame.
GESTURE = """\
// The touch and view state (the state row's first texel): x the most
// fingers down at once in this touch (0: none), y when it started
// (iTime), z the view (0 colour, 1 every chemical as a mask) - which
// a third finger switches.
vec4 rdNextState(vec4 s) {
	if (pointerCount == 0) {
		return vec4(0.0, 0.0, s.z, 0.0);
	}
	float most = max(s.x, float(pointerCount));
	float start = s.x == 0.0 ? iTime : s.y;
	float view = most >= 3.0 && s.x < 3.0 ? 1.0 - s.z : s.z;
	return vec4(most, start, view, 0.0);
}"""

# The Image pass's other view: every chemical side by side, each as a
# black-and-white mask, labelled.
MASK_VIEW = """\
const int CHEMICALS = %(n)d;

vec3 maskView(vec2 fragCoord, vec2 screen, ivec2 grid) {
	// Tiles the screen's shape, as many across as down - or one more.
	int cols = int(ceil(sqrt(float(CHEMICALS))));
	int rows = (CHEMICALS + cols - 1) / cols;
	float scale = 1.0 / float(max(cols, rows));
	vec2 tile = screen * scale;
	vec2 origin = (screen - tile * vec2(cols, rows)) * 0.5;
	vec2 at = fragCoord - origin;
	ivec2 t = ivec2(floor(at / tile));
	// Rows from the top.
	int i = (rows - 1 - t.y) * cols + t.x;
	if (any(lessThan(at, vec2(0.0))) || t.x >= cols || t.y >= rows || i >= CHEMICALS) {
		return vec3(0.0);
	}
	vec2 uv = fract(at / tile);
	vec2 inTile = uv * tile;
	if (any(lessThan(inTile, vec2(1.5))) || any(greaterThan(inTile, tile - 1.5))) {
		return vec3(0.25); // the gap between tiles
	}
	ivec2 q = ivec2(uv * vec2(grid));
	vec2 range = maskRange(i);
	vec3 col = vec3(clamp((chemical(i, q) - range.x) / (range.y - range.x), 0.0, 1.0));
	// Its letter, top left.
	float h = tile.y * 0.12;
	vec2 g = (inTile - vec2(h * 0.3, tile.y - h * 1.15)) / h;
	if (all(greaterThanEqual(g, vec2(0.0))) && all(lessThan(g, vec2(1.0)))) {
		float code = float(97 + i);
		vec2 cell = vec2(mod(code, 16.0), 15.0 - floor(code / 16.0));
		float d = texture(font, (cell + g) / 16.0).r;
		float w = 0.5 / h * 16.0;
		col = mix(col, vec3(0.0), smoothstep(0.35, 0.45, d));
		col = mix(col, vec3(1.0, 0.85, 0.2), smoothstep(0.5 - w, 0.5 + w, d));
	}
	return col;
}"""

INIT_HELPERS = """\
// The initial pattern's pieces - see Ready's overlays.cpp. rel: the
// cell's position as a fraction of the grid.
float rdRandom(ivec2 p, int salt) {
	uvec3 v = uvec3(uvec2(p), uint(salt) * 7919u + uint(startRandom * 65535.0));
	v = v * 1664525u + 1013904223u;
	v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
	v ^= v >> 16u;
	v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
	return float(v.x & 0xffffffu) / 16777216.0;
}

float rdNoise(vec2 x, int salt) {
	vec2 i = floor(x);
	vec2 f = fract(x);
	vec2 u = f * f * (3.0 - 2.0 * f);
	float a = rdRandom(ivec2(i), salt);
	float b = rdRandom(ivec2(i) + ivec2(1, 0), salt);
	float c = rdRandom(ivec2(i) + ivec2(0, 1), salt);
	float d = rdRandom(ivec2(i) + ivec2(1, 1), salt);
	return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Like Ready's octave3D_01: octaves of noise, 0 to 1.
float rdPerlin(vec2 x, int octaves, int salt) {
	float sum = 0.0;
	float amp = 0.5;
	float total = 0.0;
	for (int o = 0; o < octaves; ++o) {
		sum += amp * rdNoise(x, salt + o);
		total += amp;
		x *= 2.0;
		amp *= 0.5;
	}
	return sum / total;
}

bool rdInRect(vec2 rel, vec2 lo, vec2 hi) {
	return all(greaterThanEqual(rel, lo)) && all(lessThanEqual(rel, hi));
}

bool rdInCircle(vec2 x, ivec2 size, vec2 c, float r) {
	return distance(x, c * vec2(size)) < r * float(max(size.x, size.y));
}

float rdLinear(vec2 rel, vec2 p1, vec2 p2, float v1, float v2) {
	float len = distance(p1, p2);
	float u = dot(rel - p1, (p2 - p1) / len) / len;
	return v1 + (v2 - v1) * u;
}

float rdRadial(vec2 x, ivec2 size, vec2 p1, vec2 p2, float v1, float v2) {
	vec2 a = p1 * vec2(size);
	vec2 b = p2 * vec2(size);
	return v1 + (v2 - v1) * distance(x, a) / distance(b, a);
}

float rdGaussian(vec2 x, ivec2 size, vec2 c, float height, float sigma) {
	float s = sigma * float(max(size.x, size.y));
	float d = distance(x, c * vec2(size));
	return height * exp(-d * d / (2.0 * s * s));
}

float rdSine(vec2 rel, vec2 p1, vec2 p2, float phase, float amplitude) {
	float len = distance(p1, p2);
	float u = dot(rel - p1, (p2 - p1) / len) / len;
	return amplitude * sin(u * 6.28318531 - phase);
}
"""

# Every identifier the helper functions use - a parameter named like one
# is renamed, as their #defines come before them.
GLSL_RESERVED |= set(TOKEN.findall(
		RD_POW + "\n".join(OPENCL_FUNCTIONS.values()) + GESTURE + INIT_HELPERS +
		"ivec2 rdCell(ivec2 p, ivec2 size)"))

# endregion


# region Writing the shader

def chem_names(n):
	return [chr(ord("a") + i) for i in range(n)]


CHANNELS = "xyzw"
BUFFERS = "ABCD"


def offset_suffix(off):
	dx, dy = off
	return "%s%d_%s%d" % ("m" if dx < 0 else "", abs(dx), "m" if dy < 0 else "", abs(dy))


def inputs_needed(p, chems, stencils):
	tokens = set(TOKEN.findall(p.formula))
	used_stencils = []
	cells = set((c, (0, 0)) for c in chems)
	gms = []
	for c in chems:
		deps = set()
		if "gradient_mag_squared_" + c in tokens:
			gms.append(c)
			deps |= {"x_gradient_" + c, "y_gradient_" + c}
		for s in stencils:
			key = s.label + "_" + c
			if key in tokens or key in deps:
				used_stencils.append((s, c))
				for off, _ in s.points:
					cells.add((c, off))
		for t in tokens:
			m = re.fullmatch(re.escape(c) + r"_([ns]\d*)?([ew]\d*)?", t)
			if m and (m.group(1) or m.group(2)):
				dy = dx = 0
				if m.group(1):
					n = int(m.group(1)[1:] or 1)
					dy = n if m.group(1)[0] == "n" else -n
				if m.group(2):
					n = int(m.group(2)[1:] or 1)
					dx = n if m.group(2)[0] == "e" else -n
				cells.add((c, (dx, dy)))
		if any(t in tokens for t in ("z_pos",)) or any(
				t.startswith(("z_gradient_", "z_deriv")) for t in tokens):
			raise Unsupported("z keywords in a 2D pattern")
	return used_stencils, cells, gms, tokens


def step_pass(p, chems, stage, group, groups, stages, params_block, is_first_stage):
	stencils = known_stencils(p.accuracy)
	used_stencils, cells, gms, tokens = inputs_needed(p, chems, stencils)
	src_stage = (stage - 1) % stages
	src_buffers = [BUFFERS[src_stage * groups + g] for g in range(groups)]
	renamed = {n for n, _ in p.params if n in GLSL_RESERVED}
	formula = translate_formula(p.formula, renamed)
	ftokens = set(TOKEN.findall(formula))
	out = []
	out.append("///float32")
	for g, b in enumerate(src_buffers):
		out.append("uniform sampler2D iChannel%d; ///buf:%s;" % (g, b))
	if is_first_stage and p.overlays:
		out.append("uniform float startRandom;")
	paint = [(c, v) for c, v in p.paint if c in chems[group * 4:group * 4 + 4]]
	erase = [(c, v) for c, v in p.erase if c in chems[group * 4:group * 4 + 4]]
	# Every pass - two fingers erase in every step.
	out.append("uniform vec3 pointers[10];")
	out.append("uniform int pointerCount;")
	out.append("")
	# Ready's parameters and the paint controls first - a parameter whose
	# name the code below uses is renamed (see GLSL_RESERVED).
	out.extend(params_block)
	out.append("")
	if "rdPow" in ftokens or any(f in ftokens for f in ("pown", "rootn")):
		out.append(RD_POW)
		out.append("")
	for name, code in OPENCL_FUNCTIONS.items():
		if name in ftokens:
			out.append(code)
	if any(name in ftokens for name in OPENCL_FUNCTIONS):
		out.append("")
	if is_first_stage and p.overlays:
		out.append(INIT_HELPERS)
		out.append("// The initial pattern at cell p: Ready's initial pattern generator,")
		out.append("// its noise salted by seed (0 the first frame; painting takes a new")
		out.append("// one each frame).")
		out.append("void rdInitial(ivec2 p, ivec2 grid, int seed, %s) {" %
				", ".join("out float " + c for c in chems))
		out.append("\tvec2 rel = vec2(p) / vec2(grid);")
		for c in chems:
			out.append("\t%s = 0.0;" % c)
		out.extend(overlay_code(p, chems))
		out.append("}")
		out.append("")
	# Wrap or clamp at the edges, as Ready's wrap attribute says.
	out.append("ivec2 rdCell(ivec2 p, ivec2 size) {")
	if p.wrap:
		out.append("\tp.x += p.x < 0 ? size.x : 0;")
		out.append("\tp.x -= p.x >= size.x ? size.x : 0;")
		out.append("\tp.y += p.y < 0 ? size.y : 0;")
		out.append("\tp.y -= p.y >= size.y ? size.y : 0;")
		out.append("\treturn p;")
	else:
		out.append("\treturn clamp(p, ivec2(0), size - 1);")
	out.append("}")
	out.append("")
	if is_first_stage:
		out.append(GESTURE)
		out.append("")
	out.append("void mainImage(out vec4 fragColor, vec2 fragCoord) {")
	out.append("\tivec2 p = ivec2(fragCoord);")
	out.append("\tivec2 size = textureSize(iChannel0, 0);")
	out.append("\t// The top row holds state (see rdNextState()); the grid's below it.")
	out.append("\tivec2 grid = ivec2(size.x, size.y - 1);")
	if is_first_stage:
		out.append("\tvec4 state = iFrame == 0 ? vec4(0.0, 0.0, %s, 0.0) :" %
				("1.0" if p.start_masks else "0.0"))
		out.append("\t\t\trdNextState(texelFetch(iChannel0, ivec2(0, grid.y), 0));")
	else:
		out.append("\t// This frame's, from its first step.")
		out.append("\tvec4 state = texelFetch(iChannel0, ivec2(0, grid.y), 0);")
	out.append("\tif (p.y == grid.y) {")
	if group > 0:
		out.append("\t\tfragColor = vec4(0.0);")
	elif is_first_stage:
		out.append("\t\tfragColor = state;")
	else:
		out.append("\t\tfragColor = texelFetch(iChannel0, p, 0);")
	out.append("\t\treturn;")
	out.append("\t}")
	mine = chems[group * 4:group * 4 + 4]
	result = "vec4(%s)" % ", ".join(mine + ["0.0"] * (4 - len(mine)))
	if is_first_stage:
		out.append("\tif (iFrame == 0) {")
		out.append("\t\t// The initial pattern.")
		for c in chems:
			out.append("\t\tfloat %s = 0.0;" % c)
		if p.overlays:
			out.append("\t\trdInitial(p, grid, 0, %s);" % ", ".join(chems))
		out.append("\t\tfragColor = %s;" % result)
		out.append("\t\treturn;")
		out.append("\t}")
		out.append("")
	# Fetch each needed offset's texel(s) once.
	offsets = sorted({off for _, off in cells})
	out.append("\t// Cells needed:")
	for off in offsets:
		gs = sorted({chem_index(c) // 4 for c, o in cells if o == off})
		for g in gs:
			pos = "p" if off == (0, 0) else "rdCell(p + ivec2(%d, %d), grid)" % off
			out.append("\tvec4 cell%d_%s = texelFetch(iChannel%d, %s, 0);" % (
					g, offset_suffix(off), g, pos))
	for c, off in sorted(cells, key=lambda x: (x[1] != (0, 0), x[1], x[0])):
		i = chem_index(c)
		out.append("\tfloat %s = cell%d_%s.%s;" % (
				cell_name(c, off), i // 4, offset_suffix(off), CHANNELS[i % 4]))
	out.append("")
	if used_stencils or "x_pos" in tokens or "y_pos" in tokens or gms:
		out.append("\t// Keywords needed:")
	for s, c in used_stencils:
		out.append("\tfloat %s;" % stencil_code(s, c))
	if "x_pos" in tokens:
		out.append("\tfloat x_pos = float(p.x) / float(grid.x);")
	if "y_pos" in tokens:
		out.append("\tfloat y_pos = float(p.y) / float(grid.y);")
	for c in gms:
		out.append("\tfloat gradient_mag_squared_%s = x_gradient_%s * x_gradient_%s + "
				"y_gradient_%s * y_gradient_%s;" % (c, c, c, c, c))
	for c in chems:
		out.append("\tfloat delta_%s = 0.0;" % c)
	out.append("")
	out.append("\t// The formula:")
	for line in formula.splitlines():
		out.append("\t" + line.strip() if line.strip() else "")
	out.append("")
	out.append("\t// Forward Euler:")
	for c in mine:
		out.append("\t%s += timestep * delta_%s;" % (c, c))
	if erase or (is_first_stage and paint):
		out.append("")
		out.append("\t// Painting, once a touch has lasted PAINT_DELAY seconds (so the")
		out.append("\t// first of two fingers doesn't paint before the second lands). One")
		out.append("\t// finger paints within PAINT_RADIUS cells, in each frame's first")
		out.append("\t// step; two erase within ERASE_RADIUS, in every step, for the rest")
		out.append("\t// of that touch; three do neither (they switch views).")
		out.append("\tbool touching = state.x > 0.0 && state.x < 3.0 && "
				"iTime - state.y >= PAINT_DELAY;")
		out.append("\tbool erasing = touching && state.x >= 2.0;")
		if is_first_stage and paint:
			out.append("\tbool painting = touching && !erasing;")
		inits = [c for c, v in paint if v == "init"] if is_first_stage else []
		if inits:
			out.append("\tfloat %s;" % ", ".join("init_" + c for c in chems))
			out.append("\tif (painting) {")
			out.append("\t\trdInitial(p, grid, iFrame, %s);" %
					", ".join("init_" + c for c in chems))
			out.append("\t}")
		out.append("\tfor (int i = 0; i < pointerCount; ++i) {")
		out.append("\t\tfloat away = distance(pointers[i].xy, fragCoord);")
		if erase:
			out.append("\t\tif (erasing && away < ERASE_RADIUS) {")
			for c, _ in erase:
				out.append("\t\t\t%s = ERASE_%s;" % (c, c))
			out.append("\t\t}")
		if is_first_stage and paint:
			out.append("\t\tif (painting && away < PAINT_RADIUS) {")
			for c, v in paint:
				out.append("\t\t\t%s = %s;" % (c, "init_" + c if v == "init" else "PAINT_" + c))
			out.append("\t\t}")
		out.append("\t}")
	out.append("\tfragColor = %s;" % result)
	out.append("}")
	return "\n".join(out)


def colour_function(p, colormap):
	low = float(setting(p, "low", "0"))
	high = float(setting(p, "high", "1"))
	out = []
	if colormap == "HSV blend":
		lo = colour_setting(p, "color_low", (0.0, 0.0, 1.0))
		hi = colour_setting(p, "color_high", (1.0, 0.0, 0.0))
		out.append("vec3 rgb2hsv(vec3 c) {")
		out.append("\tvec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);")
		out.append("\tvec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));")
		out.append("\tvec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));")
		out.append("\tfloat d = q.x - min(q.w, q.y);")
		out.append("\treturn vec3(abs(q.z + (q.w - q.y) / (6.0 * d + 1e-10)), "
				"d / (q.x + 1e-10), q.x);")
		out.append("}")
		out.append("")
		out.append("vec3 hsv2rgb(vec3 c) {")
		out.append("\tvec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);")
		out.append("\treturn c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);")
		out.append("}")
		out.append("")
		out.append("// Ready's \"HSV blend\" from color_low to color_high.")
		out.append("vec3 colourMap(float v) {")
		out.append("\tfloat t = clamp((v - LOW) / (HIGH - LOW), 0.0, 1.0);")
		out.append("\treturn hsv2rgb(mix(rgb2hsv(%s), rgb2hsv(%s), t));" % (vec3(lo), vec3(hi)))
		out.append("}")
	else:
		stops, reverse = colour_stops(colormap)
		n = len(stops)
		out.append("// Ready's \"%s\" colour map: %d stops, evenly spaced from %s." % (
				colormap, n, "HIGH to LOW" if reverse else "LOW to HIGH"))
		out.append("vec3 colourMap(float v) {")
		if reverse:
			out.append("\tfloat t = clamp((v - HIGH) / (LOW - HIGH), 0.0, 1.0) * %s;" %
					glsl_float(n - 1))
		else:
			out.append("\tfloat t = clamp((v - LOW) / (HIGH - LOW), 0.0, 1.0) * %s;" %
					glsl_float(n - 1))
		out.append("\tvec3 c = %s;" % vec3(stops[0]))
		for i in range(1, n):
			out.append("\tc = mix(c, %s, clamp(t - %s, 0.0, 1.0));" % (
					vec3(stops[i]), glsl_float(i - 1)))
		out.append("\treturn c;")
		out.append("}")
	return low, high, "\n".join(out)


# "a=0.5,b=0.25" -> [("a", 0.5), ("b", 0.25)].
def parse_paint(spec):
	paint = []
	for part in spec.split(","):
		if part.strip():
			chem, value = part.split("=")
			value = value.strip()
			paint.append((chem.strip(), value if value == "init" else float(value)))
	return paint


def convert(path, colormap_override=None, paint_spec="a=init,b=init", erase_spec="a=1,b=0",
		start_masks=False, steps=4):
	p = read_vti(path)
	p.start_masks = start_masks
	chems = chem_names(p.n_chemicals)
	p.paint = [(c, v) for c, v in parse_paint(paint_spec) if c in chems]
	if not p.overlays and any(v == "init" for _, v in p.paint):
		# Nothing to paint "as it starts" - Gray-Scott's usual seed.
		seed = {"a": 0.5, "b": 0.25}
		p.paint = [(c, seed.get(c, 0.0) if v == "init" else v) for c, v in p.paint]
	p.erase = [(c, v) for c, v in parse_paint(erase_spec) if c in chems]
	groups = (p.n_chemicals + 3) // 4
	stages = 4 // groups
	last = stages - 1
	colormap = colormap_override or setting(p, "colormap", "spectral")

	params = list(p.params)
	if not any(n == "timestep" for n, _ in params):
		params.append(("timestep", 1.0))
	params_block = ["// Ready's parameters (Adjust Defines changes them live):"]
	for name, value in params:
		params_block.append("#define %s %s" % (safe_name(name), glsl_float(value)))
	if not any(n == "dx" for n, _ in params):
		params_block.append("#define dx 1.0 // grid spacing")
	if p.paint or p.erase:
		params_block.append("")
		params_block.append("// Painting - what one finger sets, what two or more set (erasing),")
		params_block.append("// and how far round each (cells):")
		params_block.append("#define PAINT_RADIUS 25.0")
		params_block.append("#define ERASE_RADIUS 37.5")
		params_block.append("#define PAINT_DELAY 0.15 // seconds")
		for c, v in p.paint:
			if v != "init":
				params_block.append("#define PAINT_%s %s" % (c, glsl_float(v)))
		for c, v in p.erase:
			params_block.append("#define ERASE_%s %s" % (c, glsl_float(v)))

	active = setting(p, "active_chemical", "a")
	if chem_index(active) >= p.n_chemicals:
		active = "a"
	ai = chem_index(active)
	low, high, colour_code = colour_function(p, colormap)

	title = os.path.splitext(os.path.basename(path))[0]
	image = []
	image.append("// %s - converted from Ready (GollyGang/ready) by tools/ready/vti2enki.py." %
			title)
	image.append("// Rule: %s (%s), %d chemicals, %s accuracy, %s edges; %d steps a frame" % (
			p.rule_name, p.rule_type, p.n_chemicals, p.accuracy,
			"wrapping" if p.wrap else "clamped", stages * steps))
	image.append("// (%d per run of the buffers, which run %d times a frame - the ///steps" % (
			stages, steps))
	image.append("// line below; Ready ran %s steps a render)." % setting(p, "timesteps_per_render", "100"))
	image.append("///steps:%d" % steps)
	if p.description:
		image.append("//")
		for line in p.description.splitlines():
			image.append(("// " + line.strip()).rstrip())
	for note in p.notes:
		image.append("//")
		image.append("// Note: " + note)
	if p.paint:
		image.append("// Paint with a finger: it sets %s (PAINT_* in Buffer A's #defines)." %
				", ".join("%s = %s" % (c, "the initial pattern's" if v == "init"
						else glsl_float(v)) for c, v in p.paint))
	if p.erase:
		image.append("// Two fingers erase: they set %s (ERASE_*)." %
				", ".join("%s = %s" % (c, glsl_float(v)) for c, v in p.erase))
	image.append("// Three fingers switch to every chemical as a black-and-white mask (MASK_*)")
	image.append("// and back.")
	image.append("// Shows chemical %s. Buffers %s hold the state." % (
			active, ", ".join(BUFFERS[:stages * groups])))
	image.append("")
	for g in range(groups):
		image.append("uniform sampler2D iChannel%d; ///buf:%s;" % (g, BUFFERS[last * groups + g]))
	image.append("uniform sampler2D font;")
	image.append("")
	image.append("#define LOW %s" % glsl_float(low))
	image.append("#define HIGH %s" % glsl_float(high))
	image.append("// The mask view's range for each chemical: black at LOW, white at HIGH.")
	for c in chems:
		lo, hi = (low, high) if c == active else (0.0, 1.0)
		image.append("#define MASK_LOW_%s %s" % (c, glsl_float(lo)))
		image.append("#define MASK_HIGH_%s %s" % (c, glsl_float(hi)))
	image.append("")
	image.append(colour_code)
	image.append("")
	image.append("// Chemical i (0 = a) at cell q.")
	image.append("float chemical(int i, ivec2 q) {")
	if groups > 1:
		image.append("\treturn i < 4 ? texelFetch(iChannel0, q, 0)[i] : "
				"texelFetch(iChannel1, q, 0)[i - 4];")
	else:
		image.append("\treturn texelFetch(iChannel0, q, 0)[i];")
	image.append("}")
	image.append("")
	image.append("vec2 maskRange(int i) {")
	for k, c in enumerate(chems):
		image.append("\tif (i == %d) return vec2(MASK_LOW_%s, MASK_HIGH_%s);" % (k, c, c))
	image.append("\treturn vec2(0.0, 1.0);")
	image.append("}")
	image.append("")
	image.append(MASK_VIEW % {"n": p.n_chemicals})
	image.append("")
	image.append("void mainImage(out vec4 fragColor, vec2 fragCoord) {")
	image.append("\tivec2 size = textureSize(iChannel0, 0);")
	image.append("\t// The top row is state (see the buffers' rdNextState()).")
	image.append("\tivec2 grid = ivec2(size.x, size.y - 1);")
	image.append("\tif (texelFetch(iChannel0, ivec2(0, grid.y), 0).z > 0.5) {")
	image.append("\t\tfragColor = vec4(maskView(fragCoord, vec2(size), grid), 1.0);")
	image.append("\t\treturn;")
	image.append("\t}")
	image.append("\tivec2 q = ivec2(fragCoord.x, fragCoord.y * float(grid.y) / float(size.y));")
	image.append("\tfragColor = vec4(colourMap(chemical(%d, q)), 1.0);" % ai)
	image.append("}")

	text = "\n".join(image) + "\n"
	for stage in range(stages):
		for group in range(groups):
			buffer = BUFFERS[stage * groups + group]
			text += "//// Buffer %s\n" % buffer
			holds = ", ".join(chems[group * 4:group * 4 + 4])
			text += "// Step %d of %d a frame: chemicals %s.\n" % (stage + 1, stages, holds)
			text += step_pass(p, chems, stage, group, groups, stages, params_block,
					stage == 0) + "\n"
	return text, p


def main():
	parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
	parser.add_argument("vti", nargs="+")
	parser.add_argument("-o", "--output",
			help="output file (one input), or folder (several); default: next to each input")
	parser.add_argument("--colormap", help="override the file's colour map")
	parser.add_argument("--paint", default="a=init,b=init",
			help='what a finger paints: "a=init,b=init" (the default: as the initial '
			'pattern), values ("a=0.5,b=0.25"), or "" for none')
	parser.add_argument("--erase", default="a=1,b=0",
			help='what two fingers paint, e.g. "a=1,b=0" (the default); "" for none')
	parser.add_argument("--masks", action="store_true",
			help="start in the every-chemical mask view (three fingers switch)")
	parser.add_argument("--steps", type=int, default=4,
			help="times the buffers run per frame drawn (a ///steps line; default 4)")
	args = parser.parse_args()
	failed = 0
	for path in args.vti:
		try:
			text, p = convert(path, args.colormap, args.paint, args.erase, args.masks, args.steps)
		except (Unsupported, ET.ParseError, KeyError, ValueError, AttributeError) as e:
			print("%s: skipped - %s" % (path, e), file=sys.stderr)
			failed += 1
			continue
		base = os.path.splitext(os.path.basename(path))[0] + ".glsl"
		if args.output and len(args.vti) == 1 and not os.path.isdir(args.output):
			out = args.output
		elif args.output:
			out = os.path.join(args.output, base)
		else:
			out = os.path.splitext(path)[0] + ".glsl"
		with open(out, "w") as f:
			f.write(text)
		print("%s -> %s%s" % (path, out, "".join("\n  note: " + n for n in p.notes)))
	sys.exit(1 if failed else 0)


if __name__ == "__main__":
	main()
