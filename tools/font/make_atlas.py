#!/usr/bin/env python3
"""Makes the app's built-in `font` texture: a signed-distance-field glyph
atlas, 16x16 cells of character codes 0-255 (row 0 at the top holds
codes 0-15), the same grid as ShaderToy's font texture.

Each glyph is rendered SCALE times larger than its cell, turned into a
distance field (scipy's Euclidean distance transform, inside and out),
and averaged down to the cell. The value is 0.5 on the glyph's edge,
higher inside and lower outside, reaching 0 and 1 at SPREAD cell pixels
away - so a shader draws crisp text at any size with
`smoothstep(0.5 - w, 0.5 + w, value)`.

Writes an 8-bit greyscale PNG; the app copies the value into all four
channels when it loads it (opengl/FontTexture.java).

Usage (needs numpy, scipy and pillow):
  make_atlas.py --font IBMPlexMono-Regular.ttf \\
      --out app/src/main/res/raw/font_atlas.png
"""

import argparse

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt

GRID = 16
# Codes with glyphs: printable ASCII and Latin-1. The rest stay empty.
CODES = list(range(32, 127)) + list(range(161, 256))


def glyph_sdf(font, char, cell, scale, spread, baseline, left):
	big = cell * scale
	image = Image.new('L', (big, big), 0)
	ImageDraw.Draw(image).text((left, baseline), char, font=font, fill=255, anchor='ls')
	inside = np.asarray(image) >= 128
	if not inside.any():
		return np.zeros((cell, cell), np.float32)
	outside_dist = distance_transform_edt(~inside)
	inside_dist = distance_transform_edt(inside)
	# Signed distance in cell pixels, positive inside.
	signed = (inside_dist - outside_dist) / scale
	value = np.clip(0.5 + signed / (2.0 * spread), 0.0, 1.0)
	return value.reshape(cell, scale, cell, scale).mean(axis=(1, 3))


def main():
	parser = argparse.ArgumentParser(description=__doc__,
			formatter_class=argparse.RawDescriptionHelpFormatter)
	parser.add_argument('--font', required=True)
	parser.add_argument('--out', required=True)
	parser.add_argument('--cell', type=int, default=64)
	parser.add_argument('--scale', type=int, default=8)
	parser.add_argument('--spread', type=float, default=6.0,
			help='cell pixels from the edge to 0 or 1')
	parser.add_argument('--fill', type=float, default=0.8,
			help='how much of the cell height ascent + descent fills')
	args = parser.parse_args()

	big = args.cell * args.scale
	# Size the font so ascent + descent fills `fill` of the cell, and
	# put every glyph on the same baseline, centred on its advance.
	probe = ImageFont.truetype(args.font, 1000)
	ascent, descent = probe.getmetrics()
	size = round(1000 * big * args.fill / (ascent + descent))
	font = ImageFont.truetype(args.font, size)
	ascent, descent = font.getmetrics()
	baseline = (big - (ascent + descent)) / 2 + ascent
	advance = font.getlength('M')
	left = (big - advance) / 2

	atlas = np.zeros((GRID * args.cell, GRID * args.cell), np.float32)
	for code in CODES:
		row, column = divmod(code, GRID)
		y, x = row * args.cell, column * args.cell
		atlas[y:y + args.cell, x:x + args.cell] = glyph_sdf(
				font, chr(code), args.cell, args.scale, args.spread, baseline, left)
	Image.fromarray(np.round(atlas * 255).astype(np.uint8), 'L').save(args.out, optimize=True)


if __name__ == '__main__':
	main()
