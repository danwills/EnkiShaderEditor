#!/usr/bin/env python3
"""Runs powder_mpm.glsl (or another buffer shader with a Sound pass) on the
desktop GPU, headless, as the app would: its buffers in order, ///steps of
them a frame, then the Image pass - with touches - saving frames as PNGs
and the Sound pass as a WAV.

  run_powder.py shader.glsl OUT_DIR [--size 360x720] [--frames 240]
      [--every 30] [--touch "FRAME-FRAME:X,Y[:X2,Y2]" ...] [--sound]

Touches are in render pixels, y up, from the bottom-left (as the app's
`pointers`); X2,Y2 makes it move there over those frames. --sound renders
the Sound pass for each frame's 1/60 s, after that frame, into sound.wav.
"""
import argparse
import os
import re
import sys
import wave

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "ready"))
sys.path.insert(0, os.path.join(HERE, ".."))
import rdheadless  # noqa: E402
import check_shader  # noqa: E402

gl = rdheadless.gl
RATE = 44100
SOUND_W = 512


class Run:
	def __init__(self, text, w, h):
		rdheadless.context()
		self.w, self.h = w, h
		common = check_shader.common(text)
		m = re.search(r"^///steps:(\d+)", text, re.M)
		self.steps = int(m.group(1)) if m else 1
		self.passes, self.image, self.sound = [], None, None
		self.modes = {}
		for name, src in check_shader.passes(text):
			# (Common's declarations bind in every pass, as in the app.)
			found = re.findall(r"uniform sampler2D (iChannel\d);\s*///buf:([A-D]);?([^\n]*)", (common or "") + "\n" + src)
			reads = [(ch, b) for ch, b, _ in found]
			prog = rdheadless.compile_program(check_shader.wrap(src, common))
			# Filtering and wrapping as the app sets them: linear unless
			# min:n, clamped unless s:r.
			for ch, b, params in found:
				self.modes[(prog, ch)] = ("min:n" in params, "s:r" in params)
			if re.search(r"\bvec2\s+mainSound\s*\(", src):
				self.sound = (prog, reads)
			elif name == "Image":
				self.image = (prog, reads)
			else:
				self.passes.append((name[-1], prog, reads))
		names = [b for b, _, _ in self.passes]
		self.tex = {b: [rdheadless._texture(w, h), rdheadless._texture(w, h)] for b in names}
		self.cur = {b: 0 for b in names}
		self.fbo = gl.glGenFramebuffers(1)
		for b in names:
			for t in self.tex[b]:
				self._target(t)
				gl.glClearColor(0, 0, 0, 1)
				gl.glClear(gl.GL_COLOR_BUFFER_BIT)
		self.out = rdheadless._texture(w, h)
		vbo = gl.glGenBuffers(1)
		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, vbo)
		quad = np.array([-1, -1, 1, -1, -1, 1, 1, 1], "f4")
		gl.glBufferData(gl.GL_ARRAY_BUFFER, quad.nbytes, quad, gl.GL_STATIC_DRAW)
		gl.glEnableVertexAttribArray(0)
		gl.glVertexAttribPointer(0, 2, gl.GL_FLOAT, False, 0, None)
		self.step = 0
		self.pointers = []
		self.made = 0

	def _target(self, t):
		gl.glBindFramebuffer(gl.GL_FRAMEBUFFER, self.fbo)
		gl.glFramebufferTexture2D(gl.GL_FRAMEBUFFER, gl.GL_COLOR_ATTACHMENT0, gl.GL_TEXTURE_2D, t, 0)

	def _bind(self, prog, reads, size):
		gl.glUseProgram(prog)
		for unit, (ch, src) in enumerate(reads):
			gl.glActiveTexture(gl.GL_TEXTURE0 + unit)
			gl.glBindTexture(gl.GL_TEXTURE_2D, self.tex[src][self.cur[src]])
			gl.glUniform1i(gl.glGetUniformLocation(prog, ch), unit)
			nearest, repeat = self.modes.get((prog, ch), (True, False))
			f = gl.GL_NEAREST if nearest else gl.GL_LINEAR
			w = gl.GL_REPEAT if repeat else gl.GL_CLAMP_TO_EDGE
			for p in (gl.GL_TEXTURE_MIN_FILTER, gl.GL_TEXTURE_MAG_FILTER):
				gl.glTexParameteri(gl.GL_TEXTURE_2D, p, f)
			for p in (gl.GL_TEXTURE_WRAP_S, gl.GL_TEXTURE_WRAP_T):
				gl.glTexParameteri(gl.GL_TEXTURE_2D, p, w)

		def put(name, setter, *val):
			loc = gl.glGetUniformLocation(prog, name)
			if loc >= 0:
				setter(loc, *val)

		put("iResolution", gl.glUniform3f, size[0], size[1], 1.0)
		put("iFrame", gl.glUniform1i, self.step)
		put("iTime", gl.glUniform1f, self.step / 60.0 / self.steps)
		put("pointerCount", gl.glUniform1i, len(self.pointers))
		if self.pointers:
			flat = np.zeros(30, "f4")
			for i, (x, y) in enumerate(self.pointers):
				flat[i * 3:i * 3 + 3] = (x, y, 40.0)
			put("pointers", gl.glUniform3fv, 10, flat)
		return put

	def frame(self):
		gl.glViewport(0, 0, self.w, self.h)
		for _ in range(self.steps):
			for b, prog, reads in self.passes:
				self._bind(prog, reads, (self.w, self.h))
				nxt = 1 - self.cur[b]
				self._target(self.tex[b][nxt])
				gl.glDrawArrays(gl.GL_TRIANGLE_STRIP, 0, 4)
				self.cur[b] = nxt
			self.step += 1
		# Wait for the GPU now and then: thousands of frames queued with no
		# read-back (the desktop's NVIDIA driver, 720x1544) and the buffers
		# came back as stale memory, the run silently lost.
		self.frames = getattr(self, "frames", 0) + 1
		if self.frames % 30 == 0:
			gl.glFinish()

	def picture(self):
		prog, reads = self.image
		self._bind(prog, reads, (self.w, self.h))
		self._target(self.out)
		gl.glViewport(0, 0, self.w, self.h)
		gl.glDrawArrays(gl.GL_TRIANGLE_STRIP, 0, 4)
		a = np.empty((self.h, self.w, 4), "f4")
		gl.glReadPixels(0, 0, self.w, self.h, gl.GL_RGBA, gl.GL_FLOAT, a)
		a = a[::-1, :, :3]
		return Image.fromarray((np.clip(a, 0, 1) * 255 + 0.5).astype("u1"))

	def read(self, b):
		self._target(self.tex[b][self.cur[b]])
		px = np.empty((self.h, self.w, 4), "f4")
		gl.glReadPixels(0, 0, self.w, self.h, gl.GL_RGBA, gl.GL_FLOAT, px)
		return px

	def listen(self, seconds):
		"""seconds more of the Sound pass, as the app makes it."""
		n = int(round(seconds * RATE))
		rows = (n + SOUND_W - 1) // SOUND_W
		prog, reads = self.sound
		# (Made first: making it binds it, over a channel.)
		t = rdheadless._texture(SOUND_W, rows)
		put = self._bind(prog, reads, (SOUND_W, rows))
		put("enkiSoundOffset", gl.glUniform1i, self.made)
		self._target(t)
		gl.glViewport(0, 0, SOUND_W, rows)
		gl.glDrawArrays(gl.GL_TRIANGLE_STRIP, 0, 4)
		px = np.empty((rows, SOUND_W, 4), "f4")
		gl.glReadPixels(0, 0, SOUND_W, rows, gl.GL_RGBA, gl.GL_FLOAT, px)
		gl.glDeleteTextures(1, [t])
		self.made += n
		return px.reshape(-1, 4)[:n, :2]


def main():
	ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
	ap.add_argument("shader")
	ap.add_argument("out")
	ap.add_argument("--size", default="360x720")
	ap.add_argument("--frames", type=int, default=240)
	ap.add_argument("--every", type=int, default=30)
	ap.add_argument("--touch", action="append", default=[])
	ap.add_argument("--sound", action="store_true")
	args = ap.parse_args()
	w, h = map(int, args.size.split("x"))
	os.makedirs(args.out, exist_ok=True)
	touches = []
	for t in args.touch:
		span, *points = t.split(":")
		a, b = map(int, span.split("-"))
		touches.append((a, b, [tuple(map(float, p.split(","))) for p in points]))
	run = Run(open(args.shader).read(), w, h)
	audio = []
	for f in range(args.frames):
		run.pointers = []
		for a, b, points in touches:
			if a <= f <= b:
				x, y = points[0]
				if len(points) > 1:
					k = (f - a) / max(b - a, 1)
					x, y = x + (points[1][0] - x) * k, y + (points[1][1] - y) * k
				run.pointers.append((x, y))
		run.frame()
		if args.sound and run.sound:
			audio.append(run.listen(1 / 60))
		if f % args.every == 0 or f == args.frames - 1:
			run.picture().save(os.path.join(args.out, "frame%04d.png" % f))
	if audio:
		pcm = (np.clip(np.concatenate(audio), -1, 1) * 32767).astype("<i2")
		with wave.open(os.path.join(args.out, "sound.wav"), "wb") as wav:
			wav.setnchannels(2)
			wav.setsampwidth(2)
			wav.setframerate(RATE)
			wav.writeframes(pcm.tobytes())
		print("sound: peak %.3f, rms %.4f" % (np.abs(pcm).max() / 32767, np.sqrt((pcm.astype("f8") ** 2).mean()) / 32767))
	return run


if __name__ == "__main__":
	main()
