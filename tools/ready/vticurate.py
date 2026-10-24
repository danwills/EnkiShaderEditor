#!/usr/bin/env python3
"""A little web app for curating a vtipack collection's shader packs:
OUT/packs.json (see vtipack.py plan) shown as packs of shader cards, to
drag between packs, retitle, describe and set the resolution of - each
card with thumbnails of its pattern as it starts and after a warm-up
run on the GPU - and a button that sends a shader to Enki debug on the
phone to look at properly.

  vticurate.py OUT [--port 8770] [--warm 1200] [--prefill DUTY]

then open http://127.0.0.1:PORT/. Changes save to OUT/packs.json as they're
made (the file as it was first kept as packs.json.bak). Thumbnails are made
as they're first asked for, one at a time on the GPU, and kept in
OUT/thumbs/. --prefill makes the rest in the background, the packs' first,
resting after each so the GPU's busy only DUTY of the time (0.55: about
9 s on the GPU a pair, so some 5-6 hours for 1,250 on metalucia alongside
a build; progress in OUT/thumbs/prefill.log).
Send to phone builds a one-shader pack and installs it with
tools/phone/add_pack.sh --replace --open: it replaces the last one sent
(the "VTI Curation" pack in Enki debug's library) and opens it.
"""
import argparse
import http.server
import io
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import traceback
import urllib.parse

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import rdheadless  # noqa: E402
import vtishader  # noqa: E402

gl = rdheadless.gl
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
ADD_PACK = os.path.join(REPO, "tools", "phone", "add_pack.sh")
# Thumbnails simulate at most this many cells a side: a window of a bigger
# grid, as the start textures tile over whatever grid they're given.
THUMB_GRID = 256
QUALITIES = [1 / 16, 1 / 8, 1 / 4, 1 / 2, 1]


class StartSim(rdheadless.Sim):
	"""rdheadless.Sim with the shader's start textures bound, so its first
	step lays down the start state as the app would."""

	def __init__(self, glsl, w, h, textures):
		super().__init__(glsl, w, h)
		self.extra = []
		for name, png in textures.items():
			with Image.open(io.BytesIO(png)) as im:
				rgba = np.ascontiguousarray(np.asarray(im.convert("RGBA")))
			t = gl.glGenTextures(1)
			gl.glBindTexture(gl.GL_TEXTURE_2D, t)
			gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MIN_FILTER, gl.GL_NEAREST)
			gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MAG_FILTER, gl.GL_NEAREST)
			gl.glTexImage2D(gl.GL_TEXTURE_2D, 0, gl.GL_RGBA8, rgba.shape[1], rgba.shape[0], 0,
					gl.GL_RGBA, gl.GL_UNSIGNED_BYTE, rgba)
			self.extra.append((name, t))

	def run(self):
		for b, prog, reads in self.passes:
			gl.glUseProgram(prog)
			unit = 0
			for unit, (ch, src) in enumerate(reads):
				gl.glActiveTexture(gl.GL_TEXTURE0 + unit)
				gl.glBindTexture(gl.GL_TEXTURE_2D, self.tex[src][self.cur[src]])
				gl.glUniform1i(gl.glGetUniformLocation(prog, ch), unit)
			for k, (name, t) in enumerate(self.extra):
				loc = gl.glGetUniformLocation(prog, name)
				if loc >= 0:
					gl.glActiveTexture(gl.GL_TEXTURE0 + len(reads) + k)
					gl.glBindTexture(gl.GL_TEXTURE_2D, t)
					gl.glUniform1i(loc, len(reads) + k)
			for name, setter, val in (("iResolution", gl.glUniform3f, (self.size[0], self.size[1], 1)),
					("iFrame", gl.glUniform1i, (self.frame,)),
					("iTime", gl.glUniform1f, (self.frame / 120.0,)),
					("startRandom", gl.glUniform1f, (self.seed,)),
					("pointerCount", gl.glUniform1i, (0,))):
				loc = gl.glGetUniformLocation(prog, name)
				if loc >= 0:
					setter(loc, *val)
			nxt = 1 - self.cur[b]
			self._target(self.tex[b][nxt])
			gl.glDrawArrays(gl.GL_TRIANGLE_STRIP, 0, 4)
			self.cur[b] = nxt
		self.frame += 1

	def close(self):
		gl.glDeleteTextures(len(self.extra), [t for _, t in self.extra])
		super().close()


class Thumbs:
	"""Makes and keeps each file's two thumbnails, on a GPU thread of its
	own (the EGL context belongs to it)."""

	def __init__(self, collection, out, warm):
		self.c = collection
		self.dir = os.path.join(out, "thumbs")
		os.makedirs(self.dir, exist_ok=True)
		self.warm = warm
		self.jobs = queue.Queue()
		self.waiting = {}
		self.lock = threading.Lock()
		# Seconds spent converting and on the GPU, all told.
		self.cpu = self.gpu = 0.0
		threading.Thread(target=self._work, daemon=True).start()

	def path(self, md5, which):
		return os.path.join(self.dir, "%s_%s_%d.jpg" % (md5, which, self.warm if which == "warm" else 0))

	def get(self, path, which):
		"""The thumbnail's JPEG, made now if need be; None if it can't be."""
		md5 = self.c.files[path][1]["md5"]
		info = self.c.info(path)
		if info is None or info["kind"] == "excluded":
			return None
		f = self.path(md5, which)
		if os.path.exists(f + ".failed"):
			return None
		if not os.path.exists(f):
			with self.lock:
				done = self.waiting.get(md5)
				if done is None:
					done = self.waiting[md5] = threading.Event()
					self.jobs.put((path, md5, done))
			done.wait(300)
		if not os.path.exists(f):
			return None
		return open(f, "rb").read()

	def _work(self):
		rdheadless.context()
		while True:
			path, md5, done = self.jobs.get()
			try:
				self._make(path, md5)
			except Exception:
				traceback.print_exc()
				# Marked, so it isn't tried again and again.
				for which in ("start", "warm"):
					if not os.path.exists(self.path(md5, which)):
						open(self.path(md5, which) + ".failed", "w").close()
			finally:
				with self.lock:
					self.waiting.pop(md5, None)
				done.set()

	def _make(self, path, md5):
		t = time.time()
		glsl, textures, p, kind, rec = self.c.convert(path)
		w, h = self.c.info(path)["grid"]
		w, h = min(w, THUMB_GRID), min(h, THUMB_GRID)
		self.cpu += time.time() - t
		t = time.time()
		sim = StartSim(glsl, w, h, textures)
		try:
			sim.run()
			self._save(md5, "start", vtishader.thumbnail(p, np.nan_to_num(sim.read())))
			for _ in range(self.warm):
				sim.run()
			self._save(md5, "warm", vtishader.thumbnail(p, np.nan_to_num(sim.read())))
		finally:
			sim.close()
			self.gpu += time.time() - t

	def _save(self, md5, which, jpeg):
		tmp = self.path(md5, which) + ".tmp"
		with open(tmp, "wb") as fh:
			fh.write(jpeg)
		os.replace(tmp, self.path(md5, which))


def prefill(app, duty):
	"""Makes every thumbnail not made yet, the packs' first, resting
	after each so the GPU's busy only duty of the time (converting, on the
	CPU at the server's niceness, doesn't count)."""
	log = open(os.path.join(app.thumbs.dir, "prefill.log"), "a", buffering=1)
	packs = json.load(open(app.packs_file))
	first = [p for pack in packs for p in pack["paths"]]
	order = list(dict.fromkeys(first + sorted(app.c.files)))
	todo = [p for p in order if p in app.c.files
			and not os.path.exists(app.thumbs.path(app.c.files[p][1]["md5"], "warm"))]
	log.write("%s: %d to make at duty %.2f\n" % (time.strftime("%H:%M"), len(todo), duty))
	start = time.time()
	for n, path in enumerate(todo, 1):
		gpu = app.thumbs.gpu
		app.thumbs.get(path, "warm")
		if n % 25 == 0 or n == len(todo):
			left = (time.time() - start) / n * (len(todo) - n)
			log.write("%s: %d of %d; %.0f s converting, %.0f s on the GPU, %.0f s in all; ~%.1f h to go\n" % (
					time.strftime("%H:%M"), n, len(todo), app.thumbs.cpu, app.thumbs.gpu,
					time.time() - start, left / 3600))
		time.sleep((app.thumbs.gpu - gpu) * (1 - duty) / duty)
	log.write("%s: done\n" % time.strftime("%H:%M"))


class App:
	def __init__(self, out, warm):
		self.out = out
		self.c = vtishader.Collection(out)
		self.thumbs = Thumbs(self.c, out, warm)
		self.packs_file = os.path.join(out, "packs.json")
		self.backed_up = False
		self.push_lock = threading.Lock()

	def state(self):
		self.c = vtishader.Collection(self.out)  # newly built states show up
		self.thumbs.c = self.c
		files = {}
		for path, (f, x) in self.c.files.items():
			info = self.c.info(path)
			files[path] = {
				"kind": info["kind"] if info else "pending",
				"why": info.get("why", "") if info else "not built yet",
				"grid": info.get("grid") if info else None,
				"rule": (f.get("names") or ["?"])[0],
			}
		return {"packs": json.load(open(self.packs_file)), "files": files,
				"qualities": QUALITIES, "default_quality": vtishader.QUALITY,
				"duplicates": self.c.idx.get("duplicates", {})}

	def save(self, packs):
		if not self.backed_up:
			shutil.copyfile(self.packs_file, self.packs_file + ".bak")
			self.backed_up = True
		tmp = self.packs_file + ".tmp"
		with open(tmp, "w") as fh:
			json.dump(packs, fh, indent=1)
		os.replace(tmp, self.packs_file)

	def push(self, path, quality):
		"""Sends the file at path to Enki debug on the phone; what happened."""
		if not self.push_lock.acquire(blocking=False):
			return False, "Still sending the last one"
		try:
			state = subprocess.run(["adb", "get-state"], capture_output=True, text=True)
			if state.stdout.strip() != "device":
				return False, "No phone connected (adb get-state: %s)" % (
						state.stdout.strip() or state.stderr.strip())
			glsl, textures, thumb = self.c.shader(path)
			name = os.path.splitext(os.path.basename(path))[0]
			dest = os.path.join(self.out, "push", "vti-curation.sqlite")
			os.makedirs(os.path.dirname(dest), exist_ok=True)
			vtishader.write_pack(dest, {"id": "vti-curation", "title": "VTI Curation",
					"description": "The last shader sent from vticurate.py."},
					[(name, glsl, textures, thumb, quality)])
			run = subprocess.run([ADD_PACK, "--replace", "--open", dest], capture_output=True,
					text=True, timeout=120)
			text = (run.stdout + run.stderr).strip()
			return run.returncode == 0, text or "sent"
		except Exception as e:
			return False, "%s: %s" % (type(e).__name__, e)
		finally:
			self.push_lock.release()


def handler(app):
	page = os.path.join(HERE, "vticurate.html")

	class Handler(http.server.BaseHTTPRequestHandler):
		def log_message(self, fmt, *args):
			pass

		def send(self, code, body, kind="application/json"):
			if isinstance(body, (dict, list)):
				body = json.dumps(body).encode()
			elif isinstance(body, str):
				body = body.encode()
			self.send_response(code)
			self.send_header("Content-Type", kind)
			self.send_header("Content-Length", str(len(body)))
			self.send_header("Cache-Control", "max-age=3600" if kind == "image/jpeg" else "no-store")
			self.end_headers()
			self.wfile.write(body)

		def do_GET(self):
			url = urllib.parse.urlparse(self.path)
			if url.path == "/":
				self.send(200, open(page, "rb").read(), "text/html; charset=utf-8")
			elif url.path == "/api/state":
				self.send(200, app.state())
			elif url.path.startswith("/thumb/"):
				q = urllib.parse.parse_qs(url.query)
				which = url.path.split("/")[2]
				path = q.get("path", [""])[0]
				if which not in ("start", "warm") or path not in app.c.files:
					self.send(404, {"error": "no such thumbnail"})
					return
				jpeg = app.thumbs.get(path, which)
				if jpeg is None:
					self.send(404, {"error": "couldn't make it"})
				else:
					self.send(200, jpeg, "image/jpeg")
			else:
				self.send(404, {"error": "not found"})

		def do_POST(self):
			body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"null")
			if self.path == "/api/packs":
				if not isinstance(body, list):
					self.send(400, {"error": "expected the packs"})
					return
				app.save(body)
				self.send(200, {"ok": True})
			elif self.path == "/api/push":
				ok, message = app.push(body["path"], body.get("quality"))
				self.send(200, {"ok": ok, "message": message})
			else:
				self.send(404, {"error": "not found"})

	return Handler


def main():
	ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
	ap.add_argument("out", help="the vtipack output folder")
	ap.add_argument("--port", type=int, default=8770)
	ap.add_argument("--warm", type=int, default=1200,
			help="runs of the buffers before the warm thumbnail (default 1200, ~10 s on the phone)")
	ap.add_argument("--prefill", type=float, metavar="DUTY",
			help="make every thumbnail in the background, the GPU busy this share of the time (0-1)")
	args = ap.parse_args()
	app = App(args.out, args.warm)
	if args.prefill:
		threading.Thread(target=prefill, args=(app, args.prefill), daemon=True).start()
	server = http.server.ThreadingHTTPServer(("127.0.0.1", args.port), handler(app))
	print("curating %s at http://127.0.0.1:%d/" % (os.path.join(args.out, "packs.json"), args.port))
	server.serve_forever()


if __name__ == "__main__":
	main()
