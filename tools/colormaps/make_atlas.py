#!/usr/bin/env python3
"""Builds the built-in `colormaps` texture (app/src/main/res/raw/colormaps.png):
one colour map per row, 256 steps from low (left) to high (right), and its
row list, tools/colormaps/names.txt (row n is line n).

Rows, in order:
- Ready's colour maps (tools/colormaps/ready/, made from Ready's
  scene_items.cpp and colormaps.hpp: the Brewer maps from VTK's
  vtkColorSeries, matplotlib's inferno, Ready's terrain), each running low
  to high as Ready draws it - reversed ones already turned round;
- the CET Perceptually Uniform Colour Maps (Peter Kovesi, CC BY 4.0 -
  https://colorcet.com): Dan's copy, tools/ready/CETperceptual_csv_0_1, then
  the newer ones it lacks (tools/colormaps/cet-extra, from the colorcet
  package), in CET's own order (L, D, C, CB..., I, R).

Shorter maps (Brewer's 9-11 colours, terrain's 6) are spread linearly
across the 256 steps. Usage, from the repo root: tools/colormaps/make_atlas.py
"""
import csv
import os
import re

from PIL import Image

STEPS = 256
READY = "tools/colormaps/ready"
CET_DIRS = ["tools/ready/CETperceptual_csv_0_1", "tools/colormaps/cet-extra"]
OUT = "app/src/main/res/raw/colormaps.png"
NAMES = "tools/colormaps/names.txt"
FAMILIES = ["L", "D", "C", "CBL", "CBD", "CBC", "CBTL", "CBTD", "CBTC", "I", "R"]


def read(path):
	with open(path) as f:
		return [[float(v) for v in row] for row in csv.reader(f) if row]


def resample(colours):
	if len(colours) == STEPS:
		return colours
	out = []
	for i in range(STEPS):
		t = i / (STEPS - 1) * (len(colours) - 1)
		a = int(t)
		b = min(a + 1, len(colours) - 1)
		f = t - a
		out.append([colours[a][k] * (1 - f) + colours[b][k] * f for k in range(3)])
	return out


def cet_key(name):
	m = re.match(r"CET-([A-Z]+?)0*(\d+)([A-Za-z]*)$", name)
	family, number, suffix = m.group(1), int(m.group(2)), m.group(3)
	return FAMILIES.index(family) if family in FAMILIES else len(FAMILIES), number, suffix


def main():
	rows = []
	for f in sorted(os.listdir(READY)):
		rows.append((f[3:-4], read(os.path.join(READY, f))))
	cet = {}
	for directory in CET_DIRS:
		for f in os.listdir(directory):
			name = f[:-4]
			if f.endswith(".csv") and name not in cet:
				cet[name] = read(os.path.join(directory, f))
	for name in sorted(cet, key=cet_key):
		rows.append((name, cet[name]))
	image = Image.new("RGB", (STEPS, len(rows)))
	for y, (_, colours) in enumerate(rows):
		for x, c in enumerate(resample(colours)):
			image.putpixel((x, y), tuple(max(0, min(255, round(v * 255))) for v in c))
	image.save(OUT)
	with open(NAMES, "w") as f:
		f.write("".join(name + "\n" for name, _ in rows))
	print("%d maps -> %s" % (len(rows), OUT))


if __name__ == "__main__":
	main()
