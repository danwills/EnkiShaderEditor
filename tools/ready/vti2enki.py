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
	# OpenCL's two-argument arctangent is GLSL's two-argument atan.
	"atan2": "float atan2(float y, float x) { return atan(y, x); }",
	"atan2pi": "float atan2pi(float y, float x) { return atan(y, x) * 0.3183098862; }",
	"sinpi": "float sinpi(float x) { return sin(x * 3.1415926536); }",
	"cospi": "float cospi(float x) { return cos(x * 3.1415926536); }",
	"tanpi": "float tanpi(float x) { return tan(x * 3.1415926536); }",
	"expm1": "float expm1(float x) { return exp(x) - 1.0; }",
	"log1p": "float log1p(float x) { return log(1.0 + x); }",
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


def formula_locals(formula):
	# Variables the formula declares itself ("float p = ...", "float x, y;"):
	# one called like mainImage()'s locals (`p`) is renamed too.
	names = set()
	for m in re.finditer(r"\b(?:float|double|int|bool)4?\s+([^;=(]+?)\s*(?:=|;)", formula):
		names.add(m.group(1).strip())
	for m in re.finditer(r"\b(?:float|double|int|bool)4?\s+[A-Za-z_]\w*(?:\s*=[^,;]*)?((?:\s*,\s*[A-Za-z_]\w*(?:\s*=[^,;]*)?)+)\s*;", formula):
		names |= set(re.findall(r",\s*([A-Za-z_]\w*)", m.group(1)))
	return {n for n in names if TOKEN.fullmatch(n)}


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


def points(node):
	# Ready reads <point3D> and <point3d> alike.
	return [point3(n) for n in node if n.tag.lower() == "point3d"]


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
	pts = points(fill)
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
	pts = points(shape)
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


# The interface, shared by every pass - so a touch means the same to the
# buffers, which act on it, as to the Image pass, which draws it. Its
# state lives along the buffers' top row (the state row), one texel each.
UI_COMMON = """\
// The interface. Views: 0 colour; 1 every chemical, and the colour view,
// as tiles; 2 one tile full screen. Buttons just above the tiles (in the
// tiles view only): the colour map (tap for the next, hold and drag up and
// down to pick one), random (tap for a random composite look; hold to
// wander through them, stopping a moment before letting go), the brush
// (drag to set what one finger paints; a second finger joining the drag
// sets what two erase; tap to go back to the file's own), and composite
// colour (tap to switch).
const int CHEMICALS = %(n)d;
const int TILES = CHEMICALS + 1; // the chemicals, then the colour view
const float MAPS = %(maps)d.0;   // 0 the file's own map, then colormaps' rows
// The state row's texels:
const int S_GESTURE = 0; // most fingers this touch, when it began, view, the tile shown
const int S_TOUCH = 1;   // where it began (x, y), moved too far for a tap, tile it began on
const int S_MODE = 2;    // composite, colour map, editing (1 colour, 2 weight, 3 brush,
                         // 4 eraser, 5 picking a colour map, 6 wandering), its chemical
const int S_DRAG = 3;    // the colour wheel's centre (x, y), last frame's finger centre (x, y)
const int S_FINGERS = 4; // last frame's finger count, a chemical whose complement a two-
                         // finger tap switched (-1 none), 1 if a tap reset the brush, and
                         // 2 once started - see rdStarted()
const int S_BRUSH = 5;   // what the brush paints (its two chemicals), and erases (two)
const int S_BRUSH_ON = 6; // 1 once the brush (x), eraser (y) has been set by dragging
const int S_RANDOM = 7;  // the random look's seed, when wandering began, 1 while wandering,
                         // the RANDOM_REWIND period its snapshots were last taken in
const int S_COLOUR = 8;  // + a chemical: its composite hue, saturation, value, weight
const int S_POWER = 16;  // + a chemical: its composite power, complement (1: 1 - level)
const int S_MAP = 24;    // the colour map's frequency: how many times it runs over the range
const int S_SNAP = 25;   // + a chemical: snapshots of its hue and weight; + CHEMICALS + a
                         // chemical: of its power and complement - each the newer (x, y)
                         // and older (z, w), for wandering's rewind
// The brush's chemicals (-1 none): what dragging it sets.
const ivec2 BRUSH_PAINT = ivec2(%(brush_paint)s);
const ivec2 BRUSH_ERASE = ivec2(%(brush_erase)s);

// A touch shorter than this, moving less than TAP_MOVE of the screen's
// shorter side, is a tap.
#define TAP_TIME PAINT_DELAY
#define TAP_MOVE 0.03
#define WHEEL_RADIUS 0.22 // of the screen's shorter side
#define WEIGHT_RATE 3.0   // weight per screen height dragged
// Composite colour adds its chemicals' parts: weights summing to about
// this make an average look. A weight of 0 hides a chemical; below, it
// darkens where it is.
#define COMPOSITE_GAIN 1.6
#define POWER_RATE 3.0    // power doubles in about a quarter screen's drag
#define BRUSH_RATE 1.0    // a chemical's whole range per screen width (or height) dragged
#define RANDOM_REWIND 0.25 // wandering stops this to twice this before letting go
#define RANDOM_NUDGE 0.004 // how far wandering moves a look by chance, a step
#define RANDOM_PULL 0.004  // and how far towards one that shows every chemical

float rdUnit(vec2 screen) {
	return min(screen.x, screen.y);
}

bool rdInBox(vec2 p, vec4 box) {
	return all(greaterThanEqual(p, box.xy)) && all(lessThan(p, box.xy + box.zw));
}

// The tiles' layout (see rdTile()): columns, rows, one tile's size, and the
// bottom left corner of them all.
void rdTiles(vec2 screen, out int cols, out int rows, out vec2 size, out vec2 origin) {
	float top = screen.y - 0.15 * rdUnit(screen);
	cols = int(ceil(sqrt(float(TILES))));
	rows = (TILES + cols - 1) / cols;
	float scale = min(1.0 / float(cols), top / (float(rows) * screen.y));
	size = screen * scale;
	origin = vec2(screen.x - size.x * float(cols), top - size.y * float(rows)) * 0.5;
}

// The bottom of the buttons' row: just above the tiles.
float rdButtonsBottom(vec2 screen) {
	int cols, rows;
	vec2 size, origin;
	rdTiles(screen, cols, rows, size, origin);
	float u = rdUnit(screen);
	return min(origin.y + size.y * float(rows) + 0.025 * u, screen.y - 0.12 * u);
}

// The colour map button, left: origin, size.
vec4 rdMapButton(vec2 screen) {
	float u = rdUnit(screen);
	return vec4(0.04 * u, rdButtonsBottom(screen), vec2(0.3, 0.07) * u);
}

// The composite colour button, right: centre, radius.
vec3 rdCompositeButton(vec2 screen) {
	float u = rdUnit(screen);
	float r = 0.045 * u;
	return vec3(screen.x - 0.04 * u - r, rdButtonsBottom(screen) + 0.035 * u, r);
}

bool rdOnMapButton(vec2 p, vec2 screen) {
	vec4 b = rdMapButton(screen);
	float pad = 0.02 * rdUnit(screen);
	return rdInBox(p, vec4(b.xy - pad, b.zw + 2.0 * pad));
}

bool rdOnCompositeButton(vec2 p, vec2 screen) {
	vec3 b = rdCompositeButton(screen);
	return distance(p, b.xy) < b.z * 1.5;
}

// The brush button, left of the composite one: centre, radius.
vec3 rdBrushButton(vec2 screen) {
	vec3 b = rdCompositeButton(screen);
	return vec3(b.x - b.z * 3.2, b.y, b.z);
}

bool rdOnBrushButton(vec2 p, vec2 screen) {
	vec3 b = rdBrushButton(screen);
	return distance(p, b.xy) < b.z * 1.5;
}

// The random button, left of the brush one: centre, radius.
vec3 rdRandomButton(vec2 screen) {
	vec3 b = rdBrushButton(screen);
	return vec3(b.x - b.z * 3.2, b.y, b.z);
}

bool rdOnRandomButton(vec2 p, vec2 screen) {
	vec3 b = rdRandomButton(screen);
	return distance(p, b.xy) < b.z * 1.5;
}

bool rdOnButton(vec2 p, vec2 screen) {
	return rdOnMapButton(p, screen) || rdOnCompositeButton(p, screen) ||
			rdOnBrushButton(p, screen) || rdOnRandomButton(p, screen);
}

// Held on the colour map button: a strip of every map, one a row from the
// file's own at the top, down from the button - origin, size.
vec4 rdMapPicker(vec2 screen) {
	float u = rdUnit(screen);
	vec4 b = rdMapButton(screen);
	float bottom = 0.04 * u;
	float top = b.y - 0.02 * u;
	return vec4(b.x, bottom, b.z, top - bottom);
}

// Where, dragging right off the picker, the map's frequency starts being
// set: 1 there, up to MAX_FREQUENCY at the screen's right edge.
#define MAX_FREQUENCY 4.0
float rdFrequencyStart(vec2 screen) {
	vec4 r = rdMapPicker(screen);
	return r.x + r.z + 0.1 * rdUnit(screen);
}

float rdFrequencyEnd(vec2 screen) {
	return screen.x - 0.04 * rdUnit(screen);
}

// The colour map slot at height y on the picker.
float rdMapPickerSlot(float y, vec2 screen) {
	vec4 r = rdMapPicker(screen);
	return clamp(floor((r.y + r.w - y) / r.w * MAPS), 0.0, MAPS - 1.0);
}

// Chemical i's range on its tile (MASK_LOW_, MASK_HIGH_), as composite
// colour and the brush use it too.
vec2 maskRange(int i) {
%(ranges)s
	return vec2(0.0, 1.0);
}

// Tile i in the tiles view (origin, size), below the buttons: each the
// screen's shape, as many across as down - or one more.
vec4 rdTile(int i, vec2 screen) {
	int cols, rows;
	vec2 size, origin;
	rdTiles(screen, cols, rows, size, origin);
	int row = i / cols;
	int col = i - row * cols;
	return vec4(origin + vec2(float(col), float(rows - 1 - row)) * size, size);
}

int rdTileAt(vec2 p, vec2 screen) {
	for (int i = 0; i < TILES; ++i) {
		if (rdInBox(p, rdTile(i, screen))) {
			return i;
		}
	}
	return -1;
}

vec4 rdState(int texel, ivec2 grid) {
	return texelFetch(iChannel0, ivec2(texel, grid.y), 0);
}

// Whether the simulation has started: a mark in the state row, rather than
// iFrame == 0 - the app's frame count goes back to 0 whenever its surface
// is made again (the screen timing out, the app coming back), though the
// buffers carry on; so only buffers that really are blank (new, or cleared
// by Restart) start the initial pattern again. The mark is 2, not 1: the
// app clears new buffers to black with an alpha of 1.
bool rdStarted(ivec2 grid) {
	return rdState(S_FINGERS, grid).w > 1.5;
}"""

# The interface's state, worked out in each frame's first step (the same
# way by every pass of it) - see UI_COMMON's S_* texels.
UI_UPDATE = """\
// Where the fingers are centred.
vec2 rdCentre() {
	vec2 c = vec2(0.0);
	for (int i = 0; i < 10; ++i) {
		if (i >= pointerCount) {
			break;
		}
		c += pointers[i].xy;
	}
	return c / max(float(pointerCount), 1.0);
}

// Each texel's start: the first view, every chemical's composite colour
// round the hue circle, weight and power 1.
vec4 rdStartState(int texel) {
	if (texel == S_GESTURE) return vec4(0.0, 0.0, %(view)s, 0.0);
	if (texel == S_TOUCH || texel == S_MODE) return vec4(0.0, 0.0, 0.0, -1.0);
	if (texel >= S_COLOUR && texel < S_COLOUR + CHEMICALS) {
		return vec4(float(texel - S_COLOUR) / float(CHEMICALS), 0.8, 1.0,
				COMPOSITE_GAIN / float(CHEMICALS));
	}
	if (texel >= S_POWER && texel < S_POWER + CHEMICALS) return vec4(1.0, 0.0, 0.0, 0.0);
	if (texel == S_BRUSH) return %(brush_start)s;
	if (texel == S_FINGERS) return vec4(0.0, -1.0, 0.0, 2.0);
	if (texel == S_MAP) return vec4(1.0, 0.0, 0.0, 0.0);
	return vec4(0.0);
}

// The next frame's gesture, touch, mode, drag and fingers texels.
void rdNextState(ivec2 grid, vec2 screen, out vec4 g, out vec4 t, out vec4 m, out vec4 d,
		out vec4 f) {
	vec4 pg = rdState(S_GESTURE, grid);
	vec4 pt = rdState(S_TOUCH, grid);
	vec4 pm = rdState(S_MODE, grid);
	g = pg;
	t = pt;
	m = pm;
	d = rdState(S_DRAG, grid);
	int n = pointerCount;
	vec2 first = n > 0 ? pointers[0].xy : vec2(0.0);
	vec2 centre = rdCentre();
	f = vec4(float(n), -1.0, 0.0, 2.0);
	bool tiles = pg.z > 0.5 && pg.z < 1.5;
	bool tapped = pt.z < 0.5 && iTime - pg.y < TAP_TIME;
	if (n == 0 && pg.x > 1.5 && pg.x < 2.5 && tapped && tiles) {
		// A two-finger tap on a chemical's tile: its complement in
		// composite colour, on or off.
		if (pt.w > -0.5 && pt.w < float(CHEMICALS) - 0.5) {
			f.y = pt.w;
		}
	}
	if (n == 0) {
		if (pg.x > 0.0 && pg.x < 1.5 && tapped) {
			// A tap: in the tiles view a button or a tile, full screen back
			// to the tiles.
			vec2 at = pt.xy;
			if (tiles && rdOnBrushButton(at, screen)) {
				f.z = 1.0;
			} else if (tiles && rdOnRandomButton(at, screen)) {
				// A random look (see rdNextChemical()) - in composite.
				m.x = 1.0;
			} else if (tiles && rdOnMapButton(at, screen)) {
				m.y = mod(pm.y + 1.0, MAPS);
			} else if (tiles && rdOnCompositeButton(at, screen)) {
				m.x = 1.0 - pm.x;
			} else if (tiles) {
				int hit = rdTileAt(at, screen);
				if (hit >= 0) {
					g.z = 2.0;
					g.w = float(hit);
				}
			} else if (pg.z > 1.5) {
				g.z = 1.0;
			}
		}
		g.x = 0.0;
		m.z = 0.0;
	} else {
		if (pg.x == 0.0) {
			g.y = iTime;
			t = vec4(first, 0.0, float(rdTileAt(first, screen)));
		}
		g.x = max(pg.x, float(n));
		if (distance(first, t.xy) > TAP_MOVE * rdUnit(screen)) {
			t.z = 1.0;
		}
		// Three fingers: into the tiles, or out of them to colour.
		if (g.x >= 3.0 && pg.x < 3.0) {
			g.z = pg.z < 0.5 ? 1.0 : 0.0;
		}
		// The brush, dragged from its button: what one finger paints; a
		// second finger joining in, what two erase.
		if (tiles && rdOnBrushButton(t.xy, screen) && g.x < 3.0) {
			m.z = n >= 2 ? 4.0 : 3.0;
		}
		bool held1 = g.x < 1.5 && iTime - g.y >= PAINT_DELAY;
		// Held on the colour map button: the map under the finger, on the
		// picker.
		if (tiles && held1 && rdOnMapButton(t.xy, screen)) {
			m.z = 5.0;
		}
		// (Off its right, past a gap, the finger sets the map's frequency
		// instead - see rdNextMap().)
		if (m.z > 4.5 && m.z < 5.5 && first.x < rdFrequencyStart(screen)) {
			m.y = rdMapPickerSlot(first.y, screen);
		}
		// Held on random: wandering through random looks, in composite.
		if (tiles && held1 && rdOnRandomButton(t.xy, screen)) {
			m.z = 6.0;
			m.x = 1.0;
		}
		// Composite colour is edited on the tiles: hold one finger on a
		// chemical for its colour wheel; two drag its weight (up/down) and
		// power (sideways).
		bool held = g.x < 3.0 && iTime - g.y >= PAINT_DELAY;
		bool onChemical = t.w > -0.5 && t.w < float(CHEMICALS) - 0.5;
		if (pm.x > 0.5 && g.z > 0.5 && g.z < 1.5 && held && onChemical) {
			if (n == 1 && pm.z < 0.5) {
				m.z = 1.0;
				m.w = t.w;
				// Centred so the finger starts on the chemical's colour.
				vec4 c = rdState(S_COLOUR + int(t.w + 0.5), grid);
				float a = c.x * 6.28318531;
				d.xy = t.xy - WHEEL_RADIUS * rdUnit(screen) * c.y * vec2(cos(a), sin(a));
			} else if (n >= 2) {
				m.z = 2.0;
				m.w = t.w;
			}
		}
	}
	d.zw = centre;
}

// How far the fingers' centre moved since last frame, in screen
// heights - nothing across a change in how many there are.
vec2 rdMoved(ivec2 grid, vec2 screen) {
	if (pointerCount == 0 || float(pointerCount) != rdState(S_FINGERS, grid).x) {
		return vec2(0.0);
	}
	return (rdCentre() - rdState(S_DRAG, grid).zw) / rdUnit(screen);
}

// The brush texels, next frame: m and f are the next mode and fingers.
vec4 rdNextBrush(int texel, ivec2 grid, vec2 screen, vec4 m, vec4 f) {
	vec4 s = rdState(texel, grid);
	if (f.z > 0.5) {
		// A tap on the brush: back to the file's own.
		return rdStartState(texel);
	}
	if (texel == S_BRUSH_ON) {
		if (m.z > 2.5 && m.z < 3.5) s.x = 1.0;
		if (m.z > 3.5) s.y = 1.0;
		return s;
	}
	vec2 moved = rdMoved(grid, screen) * BRUSH_RATE;
	if (m.z > 2.5 && m.z < 3.5) {
		vec2 r1 = maskRange(max(BRUSH_PAINT.x, 0));
		vec2 r2 = maskRange(max(BRUSH_PAINT.y, 0));
		s.x = clamp(s.x + moved.x * (r1.y - r1.x), r1.x, r1.y);
		s.y = clamp(s.y + moved.y * (r2.y - r2.x), r2.x, r2.y);
	} else if (m.z > 3.5) {
		vec2 r1 = maskRange(max(BRUSH_ERASE.x, 0));
		vec2 r2 = maskRange(max(BRUSH_ERASE.y, 0));
		s.z = clamp(s.z + moved.x * (r1.y - r1.x), r1.x, r1.y);
		s.w = clamp(s.w + moved.y * (r2.y - r2.x), r2.x, r2.y);
	}
	return s;
}

// The colour map's frequency next frame: m is the next mode. Picking a
// map, a finger dragged right off the picker sets it - 1 just past the
// gap, MAX_FREQUENCY at the screen's edge, in eighths, whole numbers
// sticky.
vec4 rdNextMap(ivec2 grid, vec2 screen, vec4 m) {
	vec4 s = rdState(S_MAP, grid);
	if (m.z < 4.5 || m.z > 5.5 || pointerCount == 0) {
		return s;
	}
	float x = pointers[0].x;
	float start = rdFrequencyStart(screen);
	if (x < start) {
		return s;
	}
	float f = 1.0 + (MAX_FREQUENCY - 1.0) * clamp((x - start) / (rdFrequencyEnd(screen) - start), 0.0, 1.0);
	f = floor(f * 8.0 + 0.5) / 8.0;
	if (abs(f - floor(f + 0.5)) < 0.13) {
		f = floor(f + 0.5);
	}
	s.x = f;
	return s;
}

// Whether this frame ends a tap on the random button.
bool rdRandomTapped(ivec2 grid, vec2 screen) {
	vec4 pg = rdState(S_GESTURE, grid);
	vec4 pt = rdState(S_TOUCH, grid);
	return pointerCount == 0 && pg.x > 0.0 && pg.x < 1.5 && pt.z < 0.5 &&
			iTime - pg.y < TAP_TIME && pg.z > 0.5 && pg.z < 1.5 &&
			rdOnRandomButton(pt.xy, screen);
}

// A new random look's seed - the same in every texel this frame.
float rdNewSeed() {
	return floor(fract(sin(iTime * 12.9898) * 43758.5453) * 500.0);
}

// The random texel next frame: m is the next mode.
vec4 rdNextRandom(ivec2 grid, vec4 m) {
	vec4 s = rdState(S_RANDOM, grid);
	float period = floor(iTime / RANDOM_REWIND);
	if (m.z > 5.5) {
		return s.z > 0.5 ? vec4(s.xyz, period) : vec4(rdNewSeed(), iTime, 1.0, period);
	}
	return vec4(s.xy, 0.0, period);
}

// A snapshot texel next frame: at each new RANDOM_REWIND period the newer
// snapshot becomes the older, and the chemical's look now the newer - so
// the older is always a period or two back.
vec4 rdNextSnapshot(int texel, ivec2 grid) {
	vec4 snap = rdState(texel, grid);
	if (floor(iTime / RANDOM_REWIND) == rdState(S_RANDOM, grid).w) {
		return snap;
	}
	int k = texel - S_SNAP;
	if (k < CHEMICALS) {
		vec4 now = rdState(S_COLOUR + k, grid);
		return vec4(now.x, now.w, snap.xy);
	}
	return vec4(rdState(S_POWER + k - CHEMICALS, grid).xy, snap.xy);
}

float rdHash(float seed, float k) {
	return fract(sin(dot(vec2(seed, k), vec2(12.9898, 78.233))) * 43758.5453);
}

// Chemical i's levels (0-1 across its mask range) over an 8x8 sample of
// the grid, each sample jittered off the lattice so a regular pattern
// can't fool it: their mean and standard deviation.
vec2 rdLevelStats(int i, ivec2 grid) {
	vec2 r = maskRange(i);
	float sum = 0.0;
	float squares = 0.0;
	for (int y = 0; y < 8; ++y) {
		for (int x = 0; x < 8; ++x) {
			vec2 jitter = vec2(rdHash(float(x), float(y)), rdHash(float(y) + 7.0, float(x)));
			ivec2 q = ivec2((vec2(x, y) + jitter) / 8.0 * vec2(grid));
			float level = clamp((rdChem(i, q) - r.x) / (r.y - r.x), 0.0, 1.0);
			sum += level;
			squares += level * level;
		}
	}
	float mean = sum / 64.0;
	return vec2(mean, sqrt(max(squares / 64.0 - mean * mean, 0.0)));
}

// Spread round the hue circle by rank: 0, a half, a quarter, three
// quarters, an eighth... - so the two most varied chemicals get opposite
// (complementary) hues, and each next one the biggest gap left.
float rdHueSpread(int rank) {
	float v = 0.0;
	float f = 0.5;
	int n = rank;
	for (int k = 0; k < 3; ++k) {
		if (n - (n / 2) * 2 == 1) {
			v += f;
		}
		n /= 2;
		f *= 0.5;
	}
	return v;
}

// What would show chemical chem clearly, from a sample of the grid now:
// its rank by how much it varies (0 the most), that variation's share of
// all of theirs, and its levels' mean.
void rdGuide(int chem, ivec2 grid, out int rank, out float share, out float mean) {
	vec2 mine = rdLevelStats(chem, grid);
	float total = 0.0;
	rank = 0;
	for (int j = 0; j < CHEMICALS; ++j) {
		float sd = j == chem ? mine.y : rdLevelStats(j, grid).y;
		total += sd;
		if (sd > mine.y || (sd == mine.y && j < chem)) {
			++rank;
		}
	}
	share = mine.y / max(total, 1e-3);
	mean = mine.x;
}

// The weight that shows a chemical with `share` of the variation: some for
// every chemical, more for the varied ones, adding up to COMPOSITE_GAIN.
float rdGuidedWeight(float share) {
	return COMPOSITE_GAIN * mix(1.0 / float(CHEMICALS), share, 0.6);
}

// The power putting a typical level (0-1) about mid-way.
float rdGuidedPower(float typical) {
	return clamp(log(0.4) / log(clamp(typical, 0.03, 0.97)), 0.25, 6.0);
}

// Chemical chem's colour or power texel in a new random look, `seed`'s:
// hues spread round the circle by how much each chemical varies (see
// rdGuide()), each power putting its typical level about mid-way (its
// complement, if mostly high, so its features show bright), weights by
// variation - each nudged by chance.
vec4 rdRandomLook(int texel, int chem, float seed, ivec2 grid) {
	float k = float(chem) * 8.0;
	int rank;
	float share, mean;
	rdGuide(chem, grid, rank, share, mean);
	if (texel < S_POWER) {
		float hue = fract(rdHash(seed, 90.0) + rdHueSpread(rank) + (rdHash(seed, k) - 0.5) * 0.15);
		return vec4(hue, mix(0.75, 1.0, rdHash(seed, k + 1.0)), mix(0.85, 1.0, rdHash(seed, k + 2.0)),
				rdGuidedWeight(share) * mix(0.6, 1.4, rdHash(seed, k + 3.0)));
	}
	bool high = mean > 0.55;
	float power = rdGuidedPower(high ? 1.0 - mean : mean) * exp((rdHash(seed, k + 4.0) - 0.5) * 0.8);
	return vec4(power, high ? 1.0 : 0.0, 0.0, 0.0);
}

// Chemical chem's colour or power texel s, one step of wandering on: a
// little way by chance (RANDOM_NUDGE), and a little way (RANDOM_PULL)
// towards what would show it clearly - its hue the biggest gap from the
// most varied chemical's (see rdHueSpread()), its weight and power as
// rdRandomLook() picks them, its complement now and then.
vec4 rdWanderStep(int texel, int chem, vec4 s, ivec2 grid) {
	float n = float(iFrame);
	float k = float(chem) * 8.0;
	int rank;
	float share, mean;
	rdGuide(chem, grid, rank, share, mean);
	if (texel < S_POWER) {
		int top = 0;
		for (int j = 0; j < CHEMICALS; ++j) {
			int r;
			float sh, me;
			if (j != chem) {
				rdGuide(j, grid, r, sh, me);
				if (r == 0) {
					top = j;
				}
			} else if (rank == 0) {
				top = j;
			}
		}
		float want = fract(rdState(S_COLOUR + top, grid).x + rdHueSpread(rank));
		float off = fract(want - s.x + 0.5) - 0.5;
		s.x = fract(s.x + (rdHash(n, k) - 0.5) * RANDOM_NUDGE * 4.0 +
				(top == chem ? 0.0 : off * RANDOM_PULL * 4.0));
		s.y = clamp(s.y + (rdHash(n, k + 1.0) - 0.5) * RANDOM_NUDGE + (0.9 - s.y) * RANDOM_PULL, 0.5, 1.0);
		s.z = clamp(s.z + (rdHash(n, k + 2.0) - 0.5) * RANDOM_NUDGE + (0.95 - s.z) * RANDOM_PULL, 0.6, 1.0);
		s.w += (rdHash(n, k + 3.0) - 0.5) * RANDOM_NUDGE * 2.0 +
				(rdGuidedWeight(share) - s.w) * RANDOM_PULL;
		return s;
	}
	bool high = mean > 0.55;
	float lp = log(max(s.x, 0.1));
	lp += (rdHash(n, k + 4.0) - 0.5) * RANDOM_NUDGE * 4.0 +
			(log(rdGuidedPower(high ? 1.0 - mean : mean)) - lp) * RANDOM_PULL;
	s.x = clamp(exp(lp), 0.1, 10.0);
	if ((s.y > 0.5) != high && rdHash(n, k + 5.0) < RANDOM_PULL) {
		s.y = high ? 1.0 : 0.0;
	}
	return s;
}

// A chemical's composite colour or power texel, next frame: m, d and f
// are the next mode, drag and fingers texels.
vec4 rdNextChemical(int texel, ivec2 grid, vec2 screen, vec4 m, vec4 d, vec4 f) {
	vec4 s = rdState(texel, grid);
	int chem = texel >= S_POWER ? texel - S_POWER : texel - S_COLOUR;
	// Random looks: wandering while random's held, then where it was a
	// moment before it was let go; a tap, a new one.
	vec4 r = rdState(S_RANDOM, grid);
	if (m.z > 5.5) {
		return rdWanderStep(texel, chem, s, grid);
	}
	if (r.z > 0.5) {
		// Just let go: back to the older snapshot, a moment before.
		vec4 snap = rdState(S_SNAP + (texel >= S_POWER ? CHEMICALS : 0) + chem, grid);
		return texel < S_POWER ? vec4(snap.z, s.y, s.z, snap.w) : vec4(snap.zw, 0.0, 0.0);
	}
	if (rdRandomTapped(grid, screen)) {
		return rdRandomLook(texel, chem, rdNewSeed(), grid);
	}
	if (texel >= S_POWER && abs(f.y - float(chem)) < 0.5) {
		s.y = 1.0 - s.y;
	}
	if (abs(m.w - float(chem)) > 0.5 || pointerCount == 0) {
		return s;
	}
	if (m.z > 0.5 && m.z < 1.5 && texel < S_POWER) {
		vec2 v = pointers[0].xy - d.xy;
		s.x = fract(atan(v.y, v.x) / 6.28318531);
		s.y = clamp(length(v) / (WHEEL_RADIUS * rdUnit(screen)), 0.0, 1.0);
	}
	// Two fingers: by how far their centre moved since last frame - not
	// across a change in how many there are.
	if (m.z > 1.5 && m.z < 2.5 && pointerCount >= 2) {
		vec2 moved = rdMoved(grid, screen);
		if (texel < S_POWER) {
			s.w = clamp(s.w + moved.y * WEIGHT_RATE, -2.0, 4.0);
		} else {
			s.x = clamp(s.x * exp(moved.x * POWER_RATE), 0.1, 10.0);
		}
	}
	return s;
}"""

# The Image pass: the views, the buttons and the composite colour wheel.
IMAGE_VIEWS = """\
const int ACTIVE = %(active)d; // the chemical the colour view shows

vec3 rgb2hsv(vec3 c) {
	vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
	vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
	vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
	float d = q.x - min(q.w, q.y);
	return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + 1e-10)), d / (q.x + 1e-10), q.x);
}

vec3 hsv2rgb(vec3 c) {
	vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
	return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

// Chemical i at q, 0-1 across its mask range.
float rdLevel(int i, ivec2 q) {
	vec2 range = maskRange(i);
	return clamp((chemical(i, q) - range.x) / (range.y - range.x), 0.0, 1.0);
}

// Colour map slot `slot` at t (0-1): 0 the file's own, then colormaps'
// rows.
vec3 rdMap(float t, float slot) {
	if (slot < 0.5) {
		return colourMap(mix(LOW, HIGH, t));
	}
	return texture(colormaps, vec2(t, 1.0 - (slot - 0.5) / float(colormapCount))).rgb;
}

// Composite colour: each chemical's level (or its complement, 1 - level),
// raised to its power, times its colour and weight, added up - so it can
// be as bright as the weights make it; a weight of 0 hides a chemical, and
// a negative one darkens where it is.
vec3 rdComposite(ivec2 q, ivec2 grid) {
	vec3 sum = vec3(0.0);
	for (int i = 0; i < CHEMICALS; ++i) {
		vec4 c = rdState(S_COLOUR + i, grid);
		vec4 power = rdState(S_POWER + i, grid);
		float level = rdLevel(i, q);
		// Its complement first, if a two-finger tap asked - levels are 0-1,
		// so it stays 0-1.
		level = power.y > 0.5 ? 1.0 - level : level;
		sum += c.w * hsv2rgb(c.xyz) * pow(level, power.x);
	}
	return clamp(sum, 0.0, 1.0);
}

// Chemical i's part in composite colour at q: its level (or complement)
// to its power, in its colour, as bright as its weight makes it next to
// an average one (COMPOSITE_GAIN over the chemicals) - a negative weight
// shown as bright as its size.
vec3 rdContribution(int i, ivec2 q, ivec2 grid) {
	vec4 c = rdState(S_COLOUR + i, grid);
	vec4 power = rdState(S_POWER + i, grid);
	float level = rdLevel(i, q);
	level = power.y > 0.5 ? 1.0 - level : level;
	return hsv2rgb(c.xyz) * pow(level, power.x) *
			clamp(abs(c.w) * float(CHEMICALS) / COMPOSITE_GAIN, 0.0, 1.0);
}

// t (0-1) run through the colour map S_MAP's frequency times over.
float rdRepeat(float t, ivec2 grid) {
	float f = rdState(S_MAP, grid).x;
	return f > 1.001 ? fract(t * f) : t;
}

vec3 rdColour(ivec2 q, ivec2 grid, vec4 mode) {
	if (mode.x > 0.5) {
		return rdComposite(q, grid);
	}
	vec2 range = vec2(LOW, HIGH);
	float t = clamp((chemical(ACTIVE, q) - range.x) / (range.y - range.x), 0.0, 1.0);
	return rdMap(rdRepeat(t, grid), mode.y);
}

// Tile k at uv (0-1 across it): a chemical's level (in composite, its
// part in it), or the colour view.
vec3 rdTileColour(int k, vec2 uv, ivec2 grid, vec4 mode) {
	ivec2 q = ivec2(clamp(uv, 0.0, 0.999) * vec2(grid));
	if (k < CHEMICALS) {
		return mode.x > 0.5 ? rdContribution(k, q, grid) : vec3(rdLevel(k, q));
	}
	return rdColour(q, grid, mode);
}

// The coverage of `code`'s glyph, at g (0-1 across its cell), drawn h
// pixels high.
float rdGlyph(float code, vec2 g, float h) {
	if (any(lessThan(g, vec2(0.0))) || any(greaterThanEqual(g, vec2(1.0)))) {
		return 0.0;
	}
	vec2 cell = vec2(mod(code, 16.0), 15.0 - floor(code / 16.0));
	float d = texture(font, (cell + g) / 16.0).r;
	float w = 8.0 / h;
	return smoothstep(0.5 - w, 0.5 + w, d);
}

// The colour wheel - hue round it, saturation out from the middle - with
// a ring where `current` is; alpha in w.
vec4 rdWheel(vec2 p, vec2 centre, float radius, vec3 current) {
	vec2 v = p - centre;
	float r = length(v) / radius;
	float edge = 1.5 / radius;
	if (r > 1.0 + edge * 4.0) {
		return vec4(0.0);
	}
	float hue = fract(atan(v.y, v.x) / 6.28318531);
	vec3 col = hsv2rgb(vec3(hue, min(r, 1.0), current.z));
	float a = current.x * 6.28318531;
	vec2 mark = centre + radius * current.y * vec2(cos(a), sin(a));
	float ring = abs(distance(p, mark) - radius * 0.08);
	col = mix(col, vec3(1.0), 1.0 - smoothstep(0.0, radius * 0.02, ring));
	float rim = smoothstep(1.0 + edge, 1.0, r);
	col = mix(vec3(0.0), col, smoothstep(1.0 + edge * 4.0, 1.0 + edge, r) > 0.0 && r > 1.0 ? 0.0 : 1.0);
	return vec4(col, max(rim, smoothstep(1.0 + edge * 4.0, 1.0, r) * 0.6));
}

// The colour map button: the current map (or, in composite, each
// chemical's colour) along it, framed.
vec4 rdMapButtonColour(vec2 p, vec2 screen, ivec2 grid, vec4 mode) {
	vec4 b = rdMapButton(screen);
	float frame = 0.006 * rdUnit(screen);
	vec4 outer = vec4(b.xy - frame, b.zw + 2.0 * frame);
	if (!rdInBox(p, outer)) {
		return vec4(0.0);
	}
	if (!rdInBox(p, b)) {
		return vec4(vec3(0.9), 1.0);
	}
	float t = (p.x - b.x) / b.z;
	if (mode.x > 0.5) {
		int i = int(min(t * float(CHEMICALS), float(CHEMICALS) - 0.5));
		return vec4(hsv2rgb(rdState(S_COLOUR + i, grid).xyz), 1.0);
	}
	return vec4(rdMap(rdRepeat(t, grid), mode.y), 1.0);
}

// The composite button: a rainbow ring, filled in while composite is on.
vec4 rdCompositeButtonColour(vec2 p, vec2 screen, vec4 mode) {
	vec3 b = rdCompositeButton(screen);
	vec2 v = p - b.xy;
	float r = length(v) / b.z;
	if (r > 1.05) {
		return vec4(0.0);
	}
	vec3 rainbow = hsv2rgb(vec3(fract(atan(v.y, v.x) / 6.28318531), 0.9, 1.0));
	float ring = smoothstep(0.62, 0.68, r);
	vec3 inside = mode.x > 0.5 ? hsv2rgb(vec3(fract(iTime * 0.1), 0.25, 1.0)) : vec3(0.08);
	return vec4(mix(inside, rainbow, ring), smoothstep(1.05, 0.98, r));
}

// The random button: a die's five, the face lit up while wandering.
vec4 rdRandomButtonColour(vec2 p, vec2 screen, vec4 mode) {
	vec3 b = rdRandomButton(screen);
	vec2 v = (p - b.xy) / b.z;
	float r = length(v);
	if (r > 1.05) {
		return vec4(0.0);
	}
	vec3 col = mode.z > 5.5 ? hsv2rgb(vec3(fract(iTime * 0.7), 0.6, 0.9)) : vec3(0.08);
	vec2 pip = abs(v) - vec2(0.38);
	float d = min(length(v), length(pip));
	col = mix(col, vec3(0.95), smoothstep(0.17, 0.12, d));
	col = mix(col, vec3(0.9), smoothstep(0.8, 0.86, r));
	return vec4(col, smoothstep(1.05, 0.98, r));
}

// Picking a colour map: every map, a row each, the current one framed.
vec4 rdMapPickerColour(vec2 p, vec2 screen, vec4 mode) {
	vec4 r = rdMapPicker(screen);
	float frame = 0.006 * rdUnit(screen);
	if (!rdInBox(p, vec4(r.xy - frame, r.zw + 2.0 * frame))) {
		return vec4(0.0);
	}
	if (!rdInBox(p, r)) {
		return vec4(vec3(0.9), 1.0);
	}
	float slot = rdMapPickerSlot(p.y, screen);
	float row = r.w / MAPS;
	float bottom = r.y + r.w - (slot + 1.0) * row;
	vec3 col = rdMap((p.x - r.x) / r.z, slot);
	if (abs(slot - mode.y) < 0.5 && (p.y - bottom < frame * 1.5 || bottom + row - p.y < frame * 1.5)) {
		col = vec3(1.0);
	}
	return vec4(col, 1.0);
}

// Picking a map: off the picker's right, the frequency zone - a tick at
// each whole frequency, a bright line at the map's, and it written out
// (like "x2.5") at the top.
vec4 rdFrequencyColour(vec2 p, vec2 screen, ivec2 grid) {
	vec4 r = rdMapPicker(screen);
	float start = rdFrequencyStart(screen);
	float end = rdFrequencyEnd(screen);
	if (p.x < start || p.x > end || p.y < r.y || p.y > r.y + r.w) {
		return vec4(0.0);
	}
	float f = rdState(S_MAP, grid).x;
	float px = 1.5;
	vec3 col = vec3(0.05);
	for (int i = 1; i <= int(MAX_FREQUENCY); ++i) {
		float x = start + (end - start) * (float(i) - 1.0) / (MAX_FREQUENCY - 1.0);
		col = mix(col, vec3(0.45), step(abs(p.x - x), px));
	}
	float at = start + (end - start) * (f - 1.0) / (MAX_FREQUENCY - 1.0);
	col = mix(col, vec3(1.0, 0.85, 0.2), step(abs(p.x - at), px * 2.0));
	// "x" and the frequency to two places.
	float h = 0.06 * rdUnit(screen);
	vec2 at0 = vec2(start + h * 0.3, r.y + r.w - h * 1.3);
	int hundredths = int(f * 100.0 + 0.5);
	float codes[5] = float[5](120.0, float(48 + hundredths / 100), 46.0,
			float(48 + (hundredths / 10) %% 10), float(48 + hundredths %% 10));
	for (int c = 0; c < 5; ++c) {
		float ink = rdGlyph(codes[c], (p - at0 - vec2(float(c) * h * 0.55, 0.0)) / h, h);
		col = mix(col, vec3(1.0), ink);
	}
	return vec4(col, 0.85);
}

// A chemical's level in its range, 0-1.
float rdInRange(int i, float v) {
	vec2 r = maskRange(max(i, 0));
	return clamp((v - r.x) / (r.y - r.x), 0.0, 1.0);
}

// The brush button: the top half what it paints, the bottom half what it
// erases, each as its chemicals' two levels side by side (the top
// sparkling while it's still the file's own initial pattern).
vec4 rdBrushButtonColour(vec2 p, vec2 screen, ivec2 grid) {
	vec3 b = rdBrushButton(screen);
	vec2 v = (p - b.xy) / b.z;
	float r = length(v);
	if (r > 1.05) {
		return vec4(0.0);
	}
	vec4 brush = rdState(S_BRUSH, grid);
	vec4 on = rdState(S_BRUSH_ON, grid);
	vec3 col = vec3(0.08);
	bool top = v.y > 0.0;
	bool left = v.x < 0.0;
	float level = top ? (left ? rdInRange(BRUSH_PAINT.x, brush.x) : rdInRange(BRUSH_PAINT.y, brush.y))
			: (left ? rdInRange(BRUSH_ERASE.x, brush.z) : rdInRange(BRUSH_ERASE.y, brush.w));
	if (top && on.x < 0.5) {
		vec2 cell = floor(p / (b.z * 0.18));
		level = fract(sin(dot(cell, vec2(12.9898, 78.233)) + floor(iTime * 6.0)) * 43758.5453);
	}
	col = mix(col, top ? vec3(1.0) : vec3(1.0, 0.35, 0.3), level * 0.9);
	col = mix(col, vec3(0.0), smoothstep(0.03, 0.0, abs(v.y)) + smoothstep(0.03, 0.0, abs(v.x)));
	col = mix(col, vec3(0.9), smoothstep(0.8, 0.86, r));
	return vec4(col, smoothstep(1.05, 0.98, r));
}

// While the brush is dragged: a pad below the buttons, across for its
// first chemical, up for its second, with a dot where they are.
vec4 rdBrushPad(vec2 p, vec2 screen, ivec2 grid, bool erase) {
	float u = rdUnit(screen);
	float side = 0.42 * u;
	vec2 origin = vec2(screen.x * 0.5 - side * 0.5, rdButtonsBottom(screen) - 0.03 * u - side);
	vec2 uv = (p - origin) / side;
	if (any(lessThan(uv, vec2(-0.02))) || any(greaterThan(uv, vec2(1.02)))) {
		return vec4(0.0);
	}
	vec4 brush = rdState(S_BRUSH, grid);
	ivec2 ids = erase ? BRUSH_ERASE : BRUSH_PAINT;
	vec2 at = erase ? vec2(rdInRange(ids.x, brush.z), rdInRange(ids.y, brush.w))
			: vec2(rdInRange(ids.x, brush.x), rdInRange(ids.y, brush.y));
	vec3 frame = erase ? vec3(1.0, 0.35, 0.3) : vec3(1.0);
	vec3 col = vec3(uv * 0.35, 0.12);
	if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
		col = frame;
	}
	float d = distance(uv, at) * side;
	col = mix(col, frame, smoothstep(0.035 * side, 0.025 * side, d));
	col = mix(col, vec3(0.0), smoothstep(0.018 * side, 0.01 * side, d));
	// The chemicals' letters, along the axes.
	float h = 0.1 * side;
	if (ids.x >= 0) {
		col = mix(col, frame, rdGlyph(float(97 + ids.x), (p - origin - vec2(side - h, -h * 1.1)) / h, h));
	}
	if (ids.y >= 0) {
		col = mix(col, frame, rdGlyph(float(97 + ids.y), (p - origin - vec2(-h * 1.0, side - h)) / h, h));
	}
	return vec4(col, 0.92);
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 size = textureSize(iChannel0, 0);
	vec2 screen = vec2(size);
	// The buffers' top row is the interface's state - see UI_COMMON.
	ivec2 grid = ivec2(size.x, size.y - 1);
	vec4 gesture = rdState(S_GESTURE, grid);
	vec4 mode = rdState(S_MODE, grid);
	vec4 drag = rdState(S_DRAG, grid);
	int view = int(gesture.z + 0.5);
	int shown = int(gesture.w + 0.5);
	vec2 p = fragCoord;
	float u = rdUnit(screen);
	vec3 col;
	if (view == 1) {
		col = vec3(0.04);
		int k = rdTileAt(p, screen);
		if (k >= 0) {
			vec4 tile = rdTile(k, screen);
			vec2 uv = (p - tile.xy) / tile.zw;
			vec2 inTile = p - tile.xy;
			if (any(lessThan(inTile, vec2(2.0))) || any(greaterThan(inTile, tile.zw - 2.0))) {
				col = vec3(0.25);
			} else {
				col = rdTileColour(k, uv, grid, mode);
				// While two fingers drag its weight (white) and power (yellow,
				// 1 at the middle): them, along its bottom.
				if (k < CHEMICALS && mode.z > 1.5 && mode.z < 2.5 && abs(mode.w - float(k)) < 0.5) {
					vec4 c = rdState(S_COLOUR + k, grid);
					float power = rdState(S_POWER + k, grid).x;
					float bar = tile.w * 0.04;
					if (inTile.y < bar * 2.2 && inTile.y > bar * 1.2) {
						col = mix(col, vec3(1.0), step(uv.x, (c.w + 2.0) / 6.0) * 0.85);
					}
					if (inTile.y < bar && inTile.y > 0.0) {
						float level = 0.5 + log(power) / log(10.0) * 0.5;
						col = mix(col, vec3(1.0, 0.85, 0.2), step(uv.x, level) * 0.85);
					}
				}
			}
		}
		// The colour wheel while a chemical's colour is being edited - kept
		// off the colour tile.
		if (mode.z > 0.5 && mode.z < 1.5 && !rdInBox(p, rdTile(CHEMICALS, screen))) {
			vec4 c = rdState(S_COLOUR + int(mode.w + 0.5), grid);
			vec4 wheel = rdWheel(p, drag.xy, WHEEL_RADIUS * u, c.xyz);
			col = mix(col, wheel.rgb, wheel.a);
		}
	} else if (view == 2) {
		col = rdTileColour(shown, p / screen, grid, mode);
	} else {
		// One screen row per grid row: the screen has one more than the
		// grid (the state row), and stretching the grid over it showed
		// its middle row twice. The extra row at the top wraps (or
		// clamps) instead.
		ivec2 q = rdCell(ivec2(p), grid);
		col = rdColour(q, grid, mode);
	}
	if (view == 1) {
		vec4 b = rdMapButtonColour(p, screen, grid, mode);
		col = mix(col, b.rgb, b.a);
		b = rdBrushButtonColour(p, screen, grid);
		col = mix(col, b.rgb, b.a);
		b = rdCompositeButtonColour(p, screen, mode);
		col = mix(col, b.rgb, b.a);
		b = rdRandomButtonColour(p, screen, mode);
		col = mix(col, b.rgb, b.a);
		if (mode.z > 2.5 && mode.z < 4.5) {
			b = rdBrushPad(p, screen, grid, mode.z > 3.5);
			col = mix(col, b.rgb, b.a);
		}
		if (mode.z > 4.5 && mode.z < 5.5) {
			b = rdMapPickerColour(p, screen, mode);
			col = mix(col, b.rgb, b.a);
			b = rdFrequencyColour(p, screen, grid);
			col = mix(col, b.rgb, b.a);
		}
	}
	fragColor = vec4(col, 1.0);
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
		RD_POW + "\n".join(OPENCL_FUNCTIONS.values()) + UI_COMMON + UI_UPDATE + INIT_HELPERS +
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
	renamed = {n for n, _ in p.params if n in GLSL_RESERVED} | (formula_locals(p.formula) & GLSL_RESERVED)
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
	out.extend(cell_code(p))
	out.extend(wide_blur_code(used_stencils, groups))
	ui = dict(p.ui, view="1.0" if p.start_masks else "0.0")
	out.append(UI_COMMON % ui)
	out.append("")
	out.extend(SANE_CODE.splitlines())
	out.append("")
	if is_first_stage:
		out.append("// Chemical i (0 = a) at cell q, as the last step left it.")
		out.append("float rdChem(int i, ivec2 q) {")
		if groups > 1:
			out.append("\treturn i < 4 ? texelFetch(iChannel0, q, 0)[i] : "
					"texelFetch(iChannel1, q, 0)[i - 4];")
		else:
			out.append("\treturn texelFetch(iChannel0, q, 0)[i];")
		out.append("}")
		out.append("")
		out.append(UI_UPDATE % ui)
		out.append("")
	out.append("void mainImage(out vec4 fragColor, vec2 fragCoord) {")
	out.append("\tivec2 p = ivec2(fragCoord);")
	out.append("\tivec2 size = textureSize(iChannel0, 0);")
	out.append("\tvec2 screen = vec2(size);")
	out.append("\t// The top row holds the interface's state (UI_COMMON's S_*); the")
	out.append("\t// grid's below it.")
	out.append("\tivec2 grid = ivec2(size.x, size.y - 1);")
	out.append("\tvec4 uiGesture, uiTouch, uiMode, uiDrag, uiFingers;")
	if is_first_stage:
		out.append("\tbool starting = !rdStarted(grid);")
		out.append("\tif (starting) {")
		out.append("\t\tuiGesture = rdStartState(S_GESTURE);")
		out.append("\t\tuiTouch = rdStartState(S_TOUCH);")
		out.append("\t\tuiMode = rdStartState(S_MODE);")
		out.append("\t\tuiDrag = rdStartState(S_DRAG);")
		out.append("\t\tuiFingers = rdStartState(S_FINGERS);")
		out.append("\t} else {")
		out.append("\t\trdNextState(grid, screen, uiGesture, uiTouch, uiMode, uiDrag, uiFingers);")
		out.append("\t}")
	else:
		out.append("\t// This frame's, from its first step.")
		out.append("\tuiGesture = rdState(S_GESTURE, grid);")
		out.append("\tuiTouch = rdState(S_TOUCH, grid);")
		out.append("\tuiMode = rdState(S_MODE, grid);")
	out.append("\tif (p.y == grid.y) {")
	if group > 0:
		out.append("\t\tfragColor = vec4(0.0);")
	elif is_first_stage:
		out.append("\t\tint texel = p.x;")
		out.append("\t\tif (texel == S_GESTURE) fragColor = uiGesture;")
		out.append("\t\telse if (texel == S_TOUCH) fragColor = uiTouch;")
		out.append("\t\telse if (texel == S_MODE) fragColor = uiMode;")
		out.append("\t\telse if (texel == S_DRAG) fragColor = uiDrag;")
		out.append("\t\telse if (texel == S_FINGERS) fragColor = uiFingers;")
		out.append("\t\telse if (starting) fragColor = rdStartState(texel);")
		out.append("\t\telse if (texel == S_BRUSH || texel == S_BRUSH_ON) {")
		out.append("\t\t\tfragColor = rdNextBrush(texel, grid, screen, uiMode, uiFingers);")
		out.append("\t\t}")
		out.append("\t\telse if (texel == S_RANDOM) fragColor = rdNextRandom(grid, uiMode);")
		out.append("\t\telse if (texel == S_MAP) fragColor = rdNextMap(grid, screen, uiMode);")
		out.append("\t\telse if (texel >= S_SNAP && texel < S_SNAP + 2 * CHEMICALS) {")
		out.append("\t\t\tfragColor = rdNextSnapshot(texel, grid);")
		out.append("\t\t}")
		out.append("\t\telse if ((texel >= S_COLOUR && texel < S_COLOUR + CHEMICALS) ||")
		out.append("\t\t\t\t(texel >= S_POWER && texel < S_POWER + CHEMICALS)) {")
		out.append("\t\t\tfragColor = rdNextChemical(texel, grid, screen, uiMode, uiDrag, uiFingers);")
		out.append("\t\t} else fragColor = vec4(0.0);")
	else:
		out.append("\t\tfragColor = texelFetch(iChannel0, p, 0);")
	out.append("\t\treturn;")
	out.append("\t}")
	mine = chems[group * 4:group * 4 + 4]
	result = "vec4(%s)" % ", ".join(mine + ["0.0"] * (4 - len(mine)))
	if is_first_stage:
		out.append("\tif (starting) {")
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
	out.extend(wide_laplacian_code(used_stencils, groups))
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
	for c in mine:
		out.append("\tfloat before_%s = %s;" % (c, c))
	out.append("\t// Forward Euler:")
	for c in mine:
		out.append("\t%s += timestep * delta_%s;" % (c, c))
	out.extend(paint_code(p, chems, mine, paint, erase, is_first_stage))
	out.append("\t// Kept finite - see rdSane().")
	for c in mine:
		out.append("\t%s = rdSane(%s, before_%s);" % (c, c, c))
	out.append("\tfragColor = %s;" % result)
	out.append("}")
	return "\n".join(out)


COLORMAP_NAMES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "colormaps",
		"names.txt")


# The rows of the app's built-in `colormaps` texture - see
# tools/colormaps/make_atlas.py.
def colormap_names():
	with open(COLORMAP_NAMES) as f:
		return [line.strip() for line in f if line.strip()]


def wide_laplacians(used_stencils):
	return [c for s, c in used_stencils if s.label == "laplacian"]


def wide_blur_code(used_stencils, groups):
	"""rdWideBlur(): the Gaussian blur of each needed group's cells that the
	wide Laplacian (LAPLACIAN_SCALE > 1) is worked out from."""
	needed = sorted({chem_index(c) // 4 for c in wide_laplacians(used_stencils)})
	if not needed:
		return []
	outs = ", ".join("out vec4 blur%d" % g for g in needed)
	out = [
		"// The wide Laplacian (LAPLACIAN_SCALE above 1) is a Gaussian's: the blur's",
		"// difference from the cell, over the blur's variance - which works out the",
		"// Laplacian of what's smooth at the blur's scale. The blur widens with the",
		"// scale, so it stays as stable as the 3x3 one, at the same timestep.",
		"#define LAPLACIAN_SIGMA 0.7",
		"#define LAPLACIAN_REACH 12",
		"void rdWideBlur(ivec2 p, ivec2 grid, %s, out float variance) {" % outs,
		"	float sigma = LAPLACIAN_SCALE * LAPLACIAN_SIGMA;",
		"	int reach = min(int(ceil(2.5 * sigma)), LAPLACIAN_REACH);",
	]
	for g in needed:
		out.append("	blur%d = vec4(0.0);" % g)
	out += [
		"	float weights = 0.0;",
		"	variance = 0.0;",
		"	for (int y = -reach; y <= reach; ++y) {",
		"		for (int x = -reach; x <= reach; ++x) {",
		"			float w = exp(-0.5 * float(x * x + y * y) / (sigma * sigma));",
		"			ivec2 q = rdCell(p + ivec2(x, y), grid);",
	]
	for g in needed:
		out.append("			blur%d += w * texelFetch(iChannel%d, q, 0);" % (g, g))
	out += [
		"			weights += w;",
		"			variance += w * float(x * x);",
		"		}",
		"	}",
	]
	for g in needed:
		out.append("	blur%d /= weights;" % g)
	out += ["	variance /= weights;", "}", ""]
	return out


def wide_laplacian_code(used_stencils, groups):
	"""In the pass's main: the Laplacians at LAPLACIAN_SCALE - scaled down as
	they are, below 1; the Gaussian's (rdWideBlur()), above."""
	chems = wide_laplacians(used_stencils)
	if not chems:
		return []
	needed = sorted({chem_index(c) // 4 for c in chems})
	out = [
		"	if (LAPLACIAN_SCALE > 1.0) {",
		"		float variance;",
	]
	for g in needed:
		out.append("		vec4 blur%d;" % g)
	out.append("		rdWideBlur(p, grid, %s, variance);" % ", ".join(
			"blur%d" % g for g in needed))
	out.append("		float wide = 2.0 * LAPLACIAN_SCALE * LAPLACIAN_SCALE / (variance * dx * dx);")
	for c in chems:
		i = chem_index(c)
		out.append("		laplacian_%s = wide * (blur%d.%s - %s);" % (
				c, i // 4, CHANNELS[i % 4], cell_name(c, (0, 0))))
	out.append("	} else {")
	for c in chems:
		out.append("		laplacian_%s *= LAPLACIAN_SCALE * LAPLACIAN_SCALE;" % c)
	out.append("	}")
	return out


SANE_CODE = """\
// Whether v is NaN or infinite - from its bits (its exponent all ones),
// as compilers may take isnan()/isinf() to be always false.
bool rdBad(float v) {
	return (floatBitsToUint(v) & 0x7f800000u) == 0x7f800000u;
}

// Chemical value v, kept finite: gone NaN or infinite - which the
// Laplacian would spread to every neighbour, a stain taking over the
// screen - it goes back to what it was this step (or 0, were that bad
// too). Nothing else is touched.
float rdSane(float v, float before) {
	return rdBad(v) ? (rdBad(before) ? 0.0 : before) : v;
}"""


def cell_code(p):
	"""rdCell(): wraps or clamps a cell at the grid's edges, as Ready's wrap
	attribute says."""
	out = ["ivec2 rdCell(ivec2 p, ivec2 size) {"]
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
	return out


def paint_code(p, chems, mine, paint, erase, is_first_stage):
	# Painting, as the interface's state says: in the colour view (or the
	# colour tile, full screen or tiled) one finger paints PAINT_* (or the
	# initial pattern) and two erase to ERASE_*; on a chemical's tile, or
	# a chemical full screen, just that chemical - to its paint value, or
	# else its mask view's HIGH, and to its erase value, or else LOW. Only
	# once a touch has lasted PAINT_DELAY (so a tap, or the first of two
	# fingers, doesn't), and not while editing composite colour. Painting
	# is each frame's first step's; erasing every step's, within the wider
	# ERASE_SIZE (both by BRUSH_SIZE of the grid's shorter side).
	painted = dict(paint)
	erased = dict(erase)
	out = [""]
	out.append("\t// Painting - see paint_code() in tools/ready/vti2enki.py.")
	out.append("\tbool touching = uiGesture.x > 0.0 && uiGesture.x < 3.0 && iTime - uiGesture.y >= PAINT_DELAY && "
			"uiMode.z < 0.5;")
	out.append("\tbool erasing = touching && uiGesture.x >= 2.0;")
	paints = is_first_stage
	if paints:
		out.append("\tbool painting = touching && !erasing;")
	else:
		out.append("\tbool painting = false;")
	out.append("\tint view = int(uiGesture.z + 0.5);")
	out.append("\tint shown = int(uiGesture.w + 0.5);")
	if paints and any(v == "init" for v in painted.values()) and p.overlays:
		out.append("\tfloat %s;" % ", ".join("init_" + c for c in chems))
		out.append("\tif (painting) {")
		out.append("\t\trdInitial(p, grid, iFrame, %s);" % ", ".join("init_" + c for c in chems))
		out.append("\t}")

	out.append("\t// The brush button's values, once it's been dragged (UI_COMMON).")
	out.append("\tvec4 brush = rdState(S_BRUSH, grid);")
	out.append("\tvec4 brushOn = rdState(S_BRUSH_ON, grid);")

	def value(c, table, fallback):
		v = table.get(c)
		if v == "init":
			own = "init_" + c
		elif v is not None:
			own = ("PAINT_" if table is painted else "ERASE_") + c
		else:
			own = fallback + c
		ids = p.brush_paint if table is painted else p.brush_erase
		k = chem_index(c)
		if k in ids:
			comp = ("xy" if table is painted else "zw")[ids.index(k)]
			on = "x" if table is painted else "y"
			return "(brushOn.%s > 0.5 ? brush.%s : %s)" % (on, comp, own)
		return own

	out.append("\tfor (int i = 0; i < pointerCount; ++i) {")
	out.append("\t\tif (!touching) {")
	out.append("\t\t\tbreak;")
	out.append("\t\t}")
	out.append("\t\tvec2 at = pointers[i].xy;")
	out.append("\t\tif (view == 1 && rdOnButton(at, screen)) {")
	out.append("\t\t\tcontinue;")
	out.append("\t\t}")
	out.append("\t\tint only = -1; // a chemical alone, or -1 for the colour view's")
	out.append("\t\tif (view == 1) {")
	out.append("\t\t\tint hit = rdTileAt(at, screen);")
	out.append("\t\t\tif (hit < 0) {")
	out.append("\t\t\t\tcontinue;")
	out.append("\t\t\t}")
	out.append("\t\t\tvec4 tile = rdTile(hit, screen);")
	out.append("\t\t\tat = (at - tile.xy) / tile.zw * vec2(grid);")
	out.append("\t\t\tonly = hit < CHEMICALS ? hit : -1;")
	out.append("\t\t} else if (view == 2 && shown < CHEMICALS) {")
	out.append("\t\t\tonly = shown;")
	out.append("\t\t}")
	out.append("\t\tfloat away = distance(at, fragCoord) / (BRUSH_SIZE * float(min(grid.x, grid.y)));")
	out.append("\t\tif (erasing && away < ERASE_SIZE) {")
	out.append("\t\t\tif (only < 0) {")
	for c in mine:
		if c in erased:
			out.append("\t\t\t\t%s = ERASE_%s;" % (c, c))
	out.append("\t\t\t}")
	for c in mine:
		out.append("\t\t\tif (only == %d) %s = %s;" % (chem_index(c), c,
				value(c, erased, "MASK_LOW_")))
	out.append("\t\t}")
	if paints:
		out.append("\t\tif (painting && away < 1.0) {")
		out.append("\t\t\tif (only < 0) {")
		for c in mine:
			if c in painted:
				out.append("\t\t\t\t%s = %s;" % (c, value(c, painted, "MASK_HIGH_")))
		out.append("\t\t\t}")
		for c in mine:
			out.append("\t\t\tif (only == %d) %s = %s;" % (chem_index(c), c,
					value(c, painted, "MASK_HIGH_")))
		out.append("\t\t}")
	out.append("\t}")
	return out


def colour_function(p, colormap):
	low = float(setting(p, "low", "0"))
	high = float(setting(p, "high", "1"))
	out = []
	if colormap == "HSV blend":
		lo = colour_setting(p, "color_low", (0.0, 0.0, 1.0))
		hi = colour_setting(p, "color_high", (1.0, 0.0, 0.0))
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
	# Colour map slots: the file's own, then the app's `colormaps` rows.
	p.maps = 1 + len(colormap_names())
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
	params_block.append("// The Laplacian's scale: patterns grow (or, below 1, shrink) by about")
	params_block.append("// this. Above 1 it's a Gaussian's, wider the larger - see rdWideBlur() -")
	params_block.append("// and slower, as it reads more cells.")
	params_block.append("#define LAPLACIAN_SCALE 1.0")
	active = setting(p, "active_chemical", "a")
	if chem_index(active) >= p.n_chemicals:
		active = "a"
	ai = chem_index(active)
	low, high, colour_code = colour_function(p, colormap)

	params_block.append("")
	params_block.append("// Painting - what one finger sets, what two set (erasing), how far")
	params_block.append("// round each (the brush's radius, of the grid's shorter side - the")
	params_block.append("// same on screen at any quality; the eraser's, times that), and how")
	params_block.append("// long a touch lasts before it paints:")
	params_block.append("#define BRUSH_SIZE 0.05")
	params_block.append("#define ERASE_SIZE 1.5")
	params_block.append("#define PAINT_DELAY 0.2 // seconds - a quicker touch is a tap")
	for c, v in p.paint:
		if v != "init":
			params_block.append("#define PAINT_%s %s" % (c, glsl_float(v)))
	for c, v in p.erase:
		params_block.append("#define ERASE_%s %s" % (c, glsl_float(v)))
	params_block.append("")
	params_block.append("// The colour view's range (Ready's low and high), and each chemical's")
	params_block.append("// on its tile - black at LOW, white at HIGH; a chemical painted on its")
	params_block.append("// own is painted to HIGH, erased to LOW, unless PAINT_/ERASE_ say:")
	display_start = len(params_block) - 3
	params_block.append("#define LOW %s" % glsl_float(low))
	params_block.append("#define HIGH %s" % glsl_float(high))
	for c in chems:
		lo, hi = (low, high) if c == active else (0.0, 1.0)
		params_block.append("#define MASK_LOW_%s %s" % (c, glsl_float(lo)))
		params_block.append("#define MASK_HIGH_%s %s" % (c, glsl_float(hi)))
	display_block = params_block[display_start:]

	# The brush's chemicals: the first two each of what it paints and erases.
	def two(table):
		ids = [chem_index(c) for c, _ in table][:2]
		return ids + [-1] * (2 - len(ids))
	brush_paint = two(p.paint)
	brush_erase = two(p.erase)
	ranges = {c: ((low, high) if c == active else (0.0, 1.0)) for c in chems}
	def start(table, ids, i):
		if ids[i] < 0:
			return 0.0
		c = chems[ids[i]]
		v = dict(table).get(c)
		# The initial pattern's noise has no one value: the middle of the range.
		return v if isinstance(v, float) else sum(ranges[c]) / 2
	brush_start = "vec4(%s)" % ", ".join(glsl_float(round(x, 6)) for x in (
			start(p.paint, brush_paint, 0), start(p.paint, brush_paint, 1),
			start(p.erase, brush_erase, 0), start(p.erase, brush_erase, 1)))
	p.ui = {"n": p.n_chemicals, "maps": p.maps,
			"brush_paint": "%d, %d" % tuple(brush_paint),
			"brush_erase": "%d, %d" % tuple(brush_erase),
			"brush_start": brush_start,
			"ranges": "\n".join("\tif (i == %d) return vec2(MASK_LOW_%s, MASK_HIGH_%s);" % (k, c, c)
					for k, c in enumerate(chems))}
	p.brush_paint = brush_paint
	p.brush_erase = brush_erase

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
	image.append("uniform sampler2D colormaps; // the app's built-in colour maps, one per row")
	image.append("uniform int colormapCount;")
	image.append("")
	image.extend(display_block)
	image.append("")
	image.append(colour_code)
	image.append("")
	image.append(UI_COMMON % p.ui)
	image.append("")
	image.extend(cell_code(p))
	image.append("// Chemical i (0 = a) at cell q.")
	image.append("float chemical(int i, ivec2 q) {")
	if groups > 1:
		image.append("\treturn i < 4 ? texelFetch(iChannel0, q, 0)[i] : "
				"texelFetch(iChannel1, q, 0)[i - 4];")
	else:
		image.append("\treturn texelFetch(iChannel0, q, 0)[i];")
	image.append("}")
	image.append("")
	image.append(IMAGE_VIEWS % {"active": ai})
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
