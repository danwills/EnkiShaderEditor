#!/usr/bin/env python3
"""Renders Ultra Rest Area's launcher icon: three metaballs merging, lit as
glossy domes - the same field and shading as its menu shader
(app/src/ultrarestarea/res/raw/rest_menu.glsl). Writes the adaptive
icon's background at every density and the legacy square icons (also into
src/ultrarestareaDebug, which src/debug's icon would otherwise win over).

Usage, from the repo root: tools/ura/make_icon.py [preview.png]
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

RES = "app/src/ultrarestarea/res"
DEBUG_RES = "app/src/ultrarestareaDebug/res"
DENSITIES = {"ldpi": 0.75, "mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
# (centre, radius, hue) in -1..1 across the icon.
BLOBS = [((-0.28, 0.22), 0.30, 0.55), ((0.30, 0.16), 0.26, 0.88), ((0.0, -0.30), 0.28, 1.2)]


def hue(h):
	return 0.5 + 0.5 * np.cos(6.28318 * (h + np.array([0.0, 0.33, 0.67])))


def render(n, rounded=False):
	s = 4
	size = n * s
	y, x = np.mgrid[0:size, 0:size].astype(np.float64)
	px = (x + 0.5) / size * 2 - 1
	py = -((y + 0.5) / size * 2 - 1)
	band = py * 1.3 + 0.4 * np.sin(px * 1.7)
	t = 0.5 + 0.5 * np.sin(band * 3.0)
	bg = (1 - t)[..., None] * np.array([0.03, 0.02, 0.08]) + t[..., None] * np.array([0.06, 0.10, 0.16])
	field = np.zeros((size, size))
	gx = np.zeros_like(field)
	gy = np.zeros_like(field)
	tint = np.zeros((size, size, 3))
	weight = np.zeros_like(field)
	for (cx, cy), r, h in BLOBS:
		dx = px - cx
		dy = py - cy
		dd = dx * dx + dy * dy + 1e-5
		f = r * r / dd
		field += f
		gx += -2 * r * r * dx / dd ** 2
		gy += -2 * r * r * dy / dd ** 2
		tint += hue(h) * f[..., None]
		weight += f
	tint /= weight[..., None]
	inside = (field > 1.0).astype(float)
	# The dome: height sqrt(1 - 1/field), as in the menu shader.
	inverse = 1 / np.maximum(field, 1e-4)
	height = np.sqrt(np.maximum(1 - inverse, 1e-3))
	sx = gx * inverse * inverse / (2 * height)
	sy = gy * inverse * inverse / (2 * height)
	normal = np.stack([-sx * 0.35, -sy * 0.35, np.ones_like(sx)], -1)
	normal /= np.linalg.norm(normal, axis=-1, keepdims=True)
	light = np.array([-0.4, 0.6, 0.8])
	light /= np.linalg.norm(light)
	diffuse = np.clip((normal * light).sum(-1), 0, None)
	reflected = 2 * (normal * light).sum(-1)[..., None] * normal - light
	shine = np.clip(reflected[..., 2], 0, None) ** 24
	rim = 1 - np.clip((field - 1.0) / 0.6, 0, 1)
	blob = tint * (0.25 + 0.75 * diffuse)[..., None] + 0.54 * shine[..., None] + tint * rim[..., None] * 0.5
	glow = np.clip((field - 0.35) / 0.65, 0, 1)
	col = bg + tint * glow[..., None] * 0.25 * (1 - inside)[..., None]
	col = col * (1 - inside)[..., None] + blob * inside[..., None]
	img = Image.fromarray((np.clip(col, 0, 1) * 255).astype(np.uint8)).resize((n, n), Image.LANCZOS)
	if rounded:
		mask = Image.new("L", (size, size), 0)
		ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.22), fill=255)
		img = img.convert("RGBA")
		img.putalpha(mask.resize((n, n), Image.LANCZOS))
	return img


def main():
	for root in (RES, DEBUG_RES):
		for name, k in DENSITIES.items():
			os.makedirs(f"{root}/mipmap-{name}", exist_ok=True)
			render(int(48 * k), rounded=True).save(f"{root}/mipmap-{name}/ic_launcher.png")
	for name, k in DENSITIES.items():
		os.makedirs(f"{RES}/drawable-{name}", exist_ok=True)
		render(int(108 * k)).save(f"{RES}/drawable-{name}/ura_icon_background.png")
	if len(sys.argv) > 1:
		render(512).save(sys.argv[1])


if __name__ == "__main__":
	main()
