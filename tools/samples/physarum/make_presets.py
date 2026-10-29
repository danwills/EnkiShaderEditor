#!/usr/bin/env python3
"""Writes presets (presets.py: PRESETS = [(name, {setting: value}), ...])
into the shader between its PRESET markers, and the menu's labels and
colour scheme names (each from the "A | B | ..." line between its LABELS
or COLOUR NAMES markers), adding any glyphs they all need to the font.

  make_presets.py shader.glsl presets.py
"""
import re
import runpy
import sys

# 5x5, top row first, leftmost pixel in the highest bit.
FONT = {
	"B": (30, 17, 30, 17, 30), "J": (1, 1, 1, 17, 14), "K": (17, 18, 28, 18, 17),
	"M": (17, 27, 21, 17, 17), "Q": (14, 17, 21, 18, 13), "X": (17, 10, 4, 10, 17),
	"Y": (17, 10, 4, 4, 4), "Z": (31, 2, 4, 8, 31), "0": (14, 19, 21, 25, 14),
	"1": (4, 12, 4, 4, 14), "4": (18, 18, 31, 2, 2), "5": (31, 16, 30, 1, 30),
	"6": (14, 16, 30, 17, 14), "7": (31, 1, 2, 4, 4), "8": (14, 17, 14, 17, 14),
	"9": (14, 17, 15, 1, 14), "-": (0, 0, 31, 0, 0),
}
ORDER = ["SENSOR_ANGLE", "SENSOR_DISTANCE", "TURN", "SPEED", "FADE", "GLOW",
		"DIFFUSE", "BLUR", "JITTER", "SPAWN"]
NAME_COLUMNS = 11
LABEL_COLUMNS = 15
COLOUR_COLUMNS = 7


def f(v):
	return ("%.5g" % v) if "." in ("%.5g" % v) or "e" in ("%.5g" % v) else ("%.5g.0" % v)


def main():
	path, presets = sys.argv[1], runpy.run_path(sys.argv[2])["PRESETS"]
	s = open(path).read()
	m = re.search(r"// FONT BEGIN\n.*?exact on any GPU:  ([^\n]*)\n"
			r"const int GLYPHS\[GLYPH_COUNT \* 5\] = int\[\]\(\n(.*?)\);\n// FONT END", s, re.S)
	chars = " " + m.group(1)
	rows = [int(x) for x in m.group(2).replace("\n", " ").split(",")]
	labels = re.search(r"// LABELS BEGIN\n//[^\n]*\n// ([^\n]*)\n", s).group(1).split(" | ")
	colours = re.search(r"// COLOUR NAMES BEGIN\n//[^\n]*\n// ([^\n]*)\n", s).group(1).split(" | ")
	text = "".join(name for name, _ in presets).upper() + "".join(labels) + "".join(colours)
	for c in sorted({c for c in text if c not in chars}):
		chars += c
		rows += list(FONT[c])
	lines = ",\n".join("\t\t" + ", ".join(str(r) for r in rows[k:k + 5]) for k in range(0, len(rows), 5))
	s = s[:m.start()] + ("// FONT BEGIN\n// 5x5 glyphs, five rows each (top first, leftmost pixel in the\n"
			"// highest bit) - small numbers, exact on any GPU:  %s\n"
			"const int GLYPHS[GLYPH_COUNT * 5] = int[](\n%s);\n// FONT END" % (chars[1:], lines)) + s[m.end():]
	s = re.sub(r"#define GLYPH_COUNT \d+", "#define GLYPH_COUNT %d" % len(chars), s)

	codes = [", ".join(str(chars.index(c)) for c in label[:LABEL_COLUMNS].ljust(LABEL_COLUMNS)) for label in labels]
	block = ("// LABELS BEGIN\n// The bars' labels, in bar order (made by make_presets.py from this line):\n"
			"// %s\nconst int LABELS[%d * LABEL_COLUMNS] = int[](\n%s);\n// LABELS END") % (
			" | ".join(labels), len(labels), ",\n".join("\t\t" + c for c in codes))
	s = re.sub(r"// LABELS BEGIN.*?// LABELS END", lambda _: block, s, flags=re.S)

	codes = [", ".join(str(chars.index(c)) for c in name[:COLOUR_COLUMNS].ljust(COLOUR_COLUMNS)) for name in colours]
	block = ("// COLOUR NAMES BEGIN\n// The colour schemes' names, as palette() numbers them (made by make_presets.py from this line):\n"
			"// %s\n#define COLOUR_COLUMNS %d\nconst int COLOUR_NAMES[%d * COLOUR_COLUMNS] = int[](\n%s);\n// COLOUR NAMES END") % (
			" | ".join(colours), COLOUR_COLUMNS, len(colours), ",\n".join("\t\t" + c for c in codes))
	s = re.sub(r"// COLOUR NAMES BEGIN.*?// COLOUR NAMES END", lambda _: block, s, flags=re.S)

	names = []
	for name, _ in presets:
		n = name.upper()[:NAME_COLUMNS].ljust(NAME_COLUMNS)
		names.append(", ".join(str(chars.index(c)) for c in n))
	block = ("// PRESET NAMES BEGIN\n// The presets' names (made by make_presets.py).\n"
			"#define PRESETS %d\n#define NAME_COLUMNS %d\n"
			"const int PRESET_NAMES[PRESETS * NAME_COLUMNS] = int[](\n%s);\n// PRESET NAMES END") % (
			len(presets), NAME_COLUMNS, ",\n".join("\t\t" + n for n in names))
	s = re.sub(r"// PRESET NAMES BEGIN.*?// PRESET NAMES END", lambda _: block, s, flags=re.S)

	body = []
	for k, (name, v) in enumerate(presets):
		if k == 0:
			continue
		body.append("\tif (i == %d) {\n\t\t// %s\n\t\ts0 = vec4(%s, %s, %s, 2.0);\n\t\ts1 = vec4(%s, %s, %s, 2.0);\n"
				"\t\ts4 = vec4(%s, %s, %s, %s);\n\t\treturn;\n\t}" % ((k, name) + tuple(f(v[o]) for o in ORDER)))
	block = ("// PRESETS BEGIN\n// Preset i's settings, as pixels (0,0), (1,0) and (4,0) hold them;\n"
			"// 0 is the defines above, the rest were found by a search for\n"
			"// patterns like Softology's (made by make_presets.py).\n#define PRESETS %d\n"
			"void preset(int i, out vec4 s0, out vec4 s1, out vec4 s4) {\n%s\n"
			"\ts0 = vec4(SENSOR_ANGLE, SENSOR_DISTANCE, TURN, 2.0);\n"
			"\ts1 = vec4(SPEED, FADE, GLOW, 2.0);\n"
			"\ts4 = vec4(DIFFUSE, BLUR, JITTER, SPAWN);\n}\n// PRESETS END") % (len(presets), "\n".join(body))
	s = re.sub(r"// PRESETS BEGIN.*?// PRESETS END", lambda _: block, s, flags=re.S)
	open(path, "w").write(s)


main()
