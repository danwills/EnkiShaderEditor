"""Enki shaders from a vtipack build: each kept pattern converted by
vti2enki, starting from its stored seed or state rather than the initial
pattern, and bundled into shader packs.

A start state goes in exact-pixel PNG textures (one 8-bit channel a
chemical, or two for a 16-bit copy; four channels a texture), each
chemical over its own range, which the shader's first step reads and
tiles over the grid (see vti2enki.start_code()). A seed is grown to a
tile of SEED_TILE cells, which wraps seamlessly, so its copies are
turned by any angle; a copy of the whole state keeps its size, and only
quarter turns. A chemical that's one value is a constant, with no channel.
"""
import datetime
import io
import json
import lzma
import os
import re
import sqlite3
import tempfile

import numpy as np
from PIL import Image

import vti2enki
import vtiseed
import vtistate

SEED_TILE = 128
THUMBNAIL_SIZE = 144
# The resolution a pack's shaders start at (the app's quality, a fraction
# of the screen's): 1/4, as these run slowly at full size on a phone.
QUALITY = 0.25
WHAT = {
	"synthetic": "noise fitted to the saved state's statistics",
	"patch": "copies of a typical window of the saved state",
	"patches4": "copies of four typical windows of the saved state",
	"patch_on_synth": "a typical window of the saved state scattered over fitted noise",
	"patch_on_rest": "the saved state's busiest window scattered over its background",
	"q8": "the saved state, 8-bit",
	"q16": "the saved state, 16-bit",
	"exact": "the saved state",
}

# The app's tables as of database version 13, as a pack file holds them
# (as tools/nca/make_pack.py writes them).
SCHEMA = '''
CREATE TABLE shaders (_id INTEGER PRIMARY KEY AUTOINCREMENT,shader TEXT NOT NULL,thumb BLOB,
	name TEXT,created DATETIME,modified DATETIME,quality REAL, source_modified DATETIME,
	compiled_binary BLOB, compiled_binary_format INTEGER, compiled_binary_source_hash TEXT,
	compiled_binary_driver_sig TEXT, compile_time_ms INTEGER, compile_time_source_length INTEGER,
	compile_time_source_modified DATETIME, last_fps INTEGER, last_fps_quality REAL, pack_id TEXT);
CREATE TABLE shader_buffers (_id INTEGER PRIMARY KEY AUTOINCREMENT,shader_id INTEGER NOT NULL,
	buffer INTEGER NOT NULL,source TEXT NOT NULL,compiled_binary BLOB,compiled_binary_format INTEGER,
	compiled_binary_source_hash TEXT,compiled_binary_driver_sig TEXT,UNIQUE(shader_id,buffer));
CREATE TABLE textures (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,
	width INTEGER,height INTEGER,ratio REAL,thumb BLOB,matrix BLOB);
CREATE TABLE pack_info (pack_id TEXT NOT NULL,title TEXT NOT NULL,description TEXT,author TEXT,
	version INTEGER,created DATETIME,format INTEGER);
CREATE TABLE pack_media (_id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,
	shader_created TEXT,image BLOB,caption TEXT);
PRAGMA user_version = 13;
'''


def _png(rgba):
	out = io.BytesIO()
	Image.fromarray(rgba, "RGBA" if rgba.shape[2] == 4 else "RGB").save(out, "PNG", optimize=True)
	return out.getvalue()


def start_textures(kind, rec, w, h, prefix):
	"""(start, {texture name: PNG bytes}) for vti2enki.convert(start=...),
	from a stored record and its file's grid (w x h)."""
	copy = kind in ("q8", "q16", "exact")
	tw, th = (w, h) if copy else (min(SEED_TILE, w), min(SEED_TILE, h))
	state = vtiseed.grow(kind, rec, tw, th).astype("f8")
	means = np.asarray(rec["means"], "f8")
	size = 2 if kind in ("q16", "exact") else 1
	top = (1 << (8 * size)) - 1
	layout, ranges, channels = [], [], []
	for i, a in enumerate(state):
		lo, hi = float(a.min()), float(a.max())
		if hi - lo < 1e-12 * max(1.0, abs(lo)):
			layout.append(None)
			ranges.append((float(means[i]), float(means[i])))
			continue
		codes = np.round((a - lo) / (hi - lo) * top)
		# Shifted so the mean comes out exact: a chemical that only
		# diffuses keeps its mean, and an offset there never dies away.
		shift = means[i] - (lo + codes.mean() / top * (hi - lo))
		ranges.append((lo + shift, hi + shift))
		slot = len(channels)
		if size == 2 and slot % 4 == 3:
			channels.append(None)  # a 16-bit value keeps to one texture
			slot += 1
		layout.append((slot // 4, slot % 4, size))
		codes = codes.astype("u4")
		if size == 1:
			channels.append(codes)
		else:
			channels += [codes >> 8, codes & 255]
	textures = {}
	names = []
	for k in range(0, len(channels), 4):
		rgba = np.zeros((th, tw, 4), "u1")
		rgba[..., 3] = 255
		for j, c in enumerate(channels[k:k + 4]):
			if c is not None:
				rgba[..., j] = c
		name = "%s_%d" % (prefix, k // 4)
		names.append(name)
		textures[name] = _png(rgba)
	layout = [None if v is None else (v[0], v[1], v[2]) for v in layout]
	return {"what": WHAT[kind], "textures": names, "size": (tw, th), "layout": layout,
			"range": ranges, "turn": not copy}, textures


def thumbnail(p, state):
	"""The active chemical through the file's colour map, as Ready shows
	it, from a (n, h, w) state; JPEG bytes."""
	ai = vti2enki.chem_index(vti2enki.setting(p, "active_chemical", "a"))
	a = state[ai if ai < len(state) else 0].astype("f8")
	low, high = float(vti2enki.setting(p, "low", "0")), float(vti2enki.setting(p, "high", "1"))
	t = np.clip((a - low) / ((high - low) or 1.0), 0, 1)
	name = vti2enki.setting(p, "colormap", "spectral")
	if name == "HSV blend":
		lo = np.array(vti2enki.colour_setting(p, "color_low", (0.0, 0.0, 1.0)))
		hi = np.array(vti2enki.colour_setting(p, "color_high", (1.0, 0.0, 0.0)))
		rgb = lo + t[..., None] * (hi - lo)
	else:
		try:
			stops, reverse = vti2enki.colour_stops(name)
		except vti2enki.Unsupported:
			stops, reverse = vti2enki.colour_stops("spectral")
		stops = np.array(stops)
		x = (1 - t if reverse else t) * (len(stops) - 1)
		i = np.minimum(x.astype(int), len(stops) - 2)
		f = (x - i)[..., None]
		rgb = stops[i] * (1 - f) + stops[i + 1] * f
	# Ready's y runs up the screen.
	img = Image.fromarray((np.flipud(rgb) * 255 + 0.5).astype("u1"), "RGB")
	s = min(img.size)
	img = img.crop(((img.size[0] - s) // 2, (img.size[1] - s) // 2,
			(img.size[0] + s) // 2, (img.size[1] + s) // 2))
	img = img.resize((THUMBNAIL_SIZE, THUMBNAIL_SIZE), Image.LANCZOS)
	out = io.BytesIO()
	# JPEG: a pattern's thumbnail is all fine detail, which PNG keeps at
	# five times the size.
	img.save(out, "JPEG", quality=88)
	return out.getvalue()


class Collection:
	"""A vtipack output folder: its index, headers and built states."""

	def __init__(self, out, src=None):
		self.out = out
		self.idx = json.load(open(os.path.join(out, "index.json")))
		self.src = src or self.idx["source"]
		with lzma.open(os.path.join(out, "headers.json.xz"), "rt") as fh:
			self.headers = json.load(fh)
		self.files = {x["path"]: (f, x) for f in self.idx["families"] for x in f["files"]}

	def info(self, path):
		p = os.path.join(self.out, "states", self.files[path][1]["md5"] + ".json")
		return json.load(open(p)) if os.path.exists(p) else None

	def shader(self, path, title=None):
		"""(glsl, {texture: PNG}, thumbnail JPEG or None) for the file at path."""
		f, x = self.files[path]
		info = self.info(path)
		if info is None:
			raise ValueError("not built yet")
		if info["kind"] == "excluded":
			raise ValueError("left out: " + info["why"])
		md5 = x["md5"]
		kind, rec = vtiseed.decode(open(os.path.join(self.out, "states", md5 + ".seed"), "rb").read())
		w, h = info["grid"]
		start, textures = None, {}
		if kind != "none":
			start, textures = start_textures(kind, rec, w, h, "rd_" + md5[:12])
		with tempfile.TemporaryDirectory() as work:
			# The rule alone: vti2enki needs no state.
			vti = os.path.join(work, "rule.vti")
			vtistate.write(vti, self.headers[md5].encode(), {}, (h, w))
			glsl, p = vti2enki.convert(vti, start=start,
					title=title or os.path.splitext(os.path.basename(path))[0])
		thumb = None
		try:
			_, _, _, arrays = vtistate.read(os.path.join(self.src, path))
			names = sorted(k for k, a in arrays.items() if a is not None and re.fullmatch("[a-h]", k))
			if names:
				thumb = thumbnail(p, np.nan_to_num(np.stack([arrays[k] for k in names])))
		except (OSError, ValueError, KeyError):
			pass
		if thumb is None and start:
			thumb = thumbnail(p, vtiseed.grow(kind, rec, min(w, 512), min(h, 512)))
		return glsl, textures, thumb


COMMON = -1  # the Common section's row in shader_buffers - see the app's BufferDao


def split_passes(text):
	"""The Image pass and {buffer: source}, Common at COMMON - from the
	Image pass, then "//// Common" and "//// Buffer A"... as vti2enki
	writes them."""
	parts = re.split(r"^//// (Common|Buffer [A-D])\n", text, flags=re.M)
	buffers = {}
	for name, source in zip(parts[1::2], parts[2::2]):
		buffers[COMMON if name == "Common" else ord(name[-1]) - ord("A")] = source
	return parts[0], buffers


def write_pack(path, pack, shaders, now=None):
	"""A pack file at path: pack = {id, title, description, author,
	version}; shaders = [(name, glsl, {texture: PNG}, thumb)]."""
	if os.path.exists(path):
		os.remove(path)
	db = sqlite3.connect(path)
	db.executescript(SCHEMA)
	now = now or datetime.datetime.now().replace(microsecond=0)
	db.execute("INSERT INTO pack_info VALUES (?, ?, ?, ?, ?, ?, 1)",
			(pack["id"], pack["title"], pack.get("description", ""), pack.get("author", ""),
			pack.get("version", 1), now.strftime("%Y-%m-%d %H:%M:%S")))
	seen = set()
	for k, (name, glsl, textures, thumb) in enumerate(shaders):
		# Distinct dates: the installer tells a pack's shaders apart by them.
		created = (now - datetime.timedelta(seconds=len(shaders) - k)).strftime("%Y-%m-%d %H:%M:%S")
		image, buffers = split_passes(glsl)
		sid = db.execute("INSERT INTO shaders (shader, thumb, name, created, modified, quality, "
				"source_modified) VALUES (?, ?, ?, ?, ?, ?, ?)",
				(image, thumb, name, created, created, QUALITY, created)).lastrowid
		for b, source in sorted(buffers.items()):
			db.execute("INSERT INTO shader_buffers (shader_id, buffer, source) VALUES (?, ?, ?)",
					(sid, b, source))
		for tname, png in textures.items():
			if tname in seen:
				continue
			seen.add(tname)
			with Image.open(io.BytesIO(png)) as im:
				tw, th = im.size
				preview = im.convert("RGB").resize((48, 48), Image.NEAREST)
			out = io.BytesIO()
			preview.save(out, "PNG")
			db.execute("INSERT INTO textures (name, width, height, ratio, thumb, matrix) "
					"VALUES (?, ?, ?, ?, ?, ?)", (tname, tw, th, round(th / tw, 2), out.getvalue(), png))
		if thumb:
			db.execute("INSERT INTO pack_media (kind, shader_created, image, caption) "
					"VALUES ('thumb', ?, ?, ?)", (created, thumb, name))
	db.commit()
	db.close()
