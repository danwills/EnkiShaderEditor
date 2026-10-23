"""Runs vti2enki's shaders headless on the desktop GPU (EGL + OpenGL ES 3
through PyOpenGL), to check that a seed restarts a pattern like its saved
state does.

Sim steps the shader's buffer passes unchanged, as the app would, in
RGBA32F textures. Trial runs a pattern from its saved state once, then
from each start it's given, and scores the start by time stretch: for
some warm-up of at most `floor` steps and stretch k from 1 to 2, the
start's run at warm-up + k*t must keep every chemical's mean and std
within TOL of the saved run's at t, over at least the first third of it.
TOL is a fraction of each chemical's spread (3.3 x its saved std, about
p5-p95) or a quarter of its level, whichever is bigger.

Needs PyOpenGL and a GPU with EGL (any desktop Linux driver).
"""
import ctypes
import os
import re
import time
import sys

os.environ.setdefault("PYOPENGL_PLATFORM", "egl")
import numpy as np
from OpenGL import EGL, GLES3 as gl

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "samples"))
import check_shader  # noqa: E402

TOL = 0.1
VERT = "#version 300 es\nin vec2 pos;\nvoid main() { gl_Position = vec4(pos, 0.0, 1.0); }\n"
_ctx = None


def context():
	global _ctx
	if _ctx:
		return
	dpy = EGL.eglGetDisplay(EGL.EGL_DEFAULT_DISPLAY)
	EGL.eglInitialize(dpy, None, None)
	EGL.eglBindAPI(EGL.EGL_OPENGL_ES_API)
	attrs = (EGL.EGLint * 5)(EGL.EGL_RENDERABLE_TYPE, 0x40, EGL.EGL_SURFACE_TYPE,
			EGL.EGL_PBUFFER_BIT, EGL.EGL_NONE)
	cfg = EGL.EGLConfig()
	n = EGL.EGLint()
	EGL.eglChooseConfig(dpy, attrs, ctypes.pointer(cfg), 1, ctypes.pointer(n))
	ctx = EGL.eglCreateContext(dpy, cfg, EGL.EGL_NO_CONTEXT,
			(EGL.EGLint * 3)(EGL.EGL_CONTEXT_CLIENT_VERSION, 3, EGL.EGL_NONE))
	surf = EGL.eglCreatePbufferSurface(dpy, cfg,
			(EGL.EGLint * 5)(EGL.EGL_WIDTH, 16, EGL.EGL_HEIGHT, 16, EGL.EGL_NONE))
	EGL.eglMakeCurrent(dpy, surf, surf, ctx)
	_ctx = (dpy, ctx, surf)


def compile_program(src):
	prog = gl.glCreateProgram()
	for kind, text in ((gl.GL_VERTEX_SHADER, VERT), (gl.GL_FRAGMENT_SHADER, src)):
		s = gl.glCreateShader(kind)
		gl.glShaderSource(s, text)
		gl.glCompileShader(s)
		if not gl.glGetShaderiv(s, gl.GL_COMPILE_STATUS):
			raise RuntimeError(gl.glGetShaderInfoLog(s).decode())
		gl.glAttachShader(prog, s)
	gl.glBindAttribLocation(prog, 0, "pos")
	gl.glLinkProgram(prog)
	if not gl.glGetProgramiv(prog, gl.GL_LINK_STATUS):
		raise RuntimeError(gl.glGetProgramInfoLog(prog).decode())
	return prog


def _texture(w, h):
	t = gl.glGenTextures(1)
	gl.glBindTexture(gl.GL_TEXTURE_2D, t)
	gl.glTexImage2D(gl.GL_TEXTURE_2D, 0, gl.GL_RGBA32F, w, h, 0, gl.GL_RGBA, gl.GL_FLOAT, None)
	for p in (gl.GL_TEXTURE_MIN_FILTER, gl.GL_TEXTURE_MAG_FILTER):
		gl.glTexParameteri(gl.GL_TEXTURE_2D, p, gl.GL_NEAREST)
	return t


class Sim:
	"""A vti2enki shader's buffer passes on a w x h grid (plus the state row)."""

	def __init__(self, glsl_text, w, h, seed=0.37):
		context()
		self.n = int(re.search(r"(\d+) chemicals", glsl_text[:600]).group(1))
		self.groups = (self.n + 3) // 4
		self.w, self.h = w, h
		self.size = (w, h + 1)
		self.passes = []
		for name, src in check_shader.passes(glsl_text):
			if name.startswith("Buffer"):
				reads = re.findall(r"uniform sampler2D (iChannel\d); ///buf:([A-D]);", src)
				self.passes.append((name[-1], compile_program(check_shader.wrap(src)), reads))
		self.names = [b for b, _, _ in self.passes]
		self.stages = len(self.passes) // self.groups
		self.last = self.names[-self.groups:]
		self.tex = {b: [_texture(*self.size), _texture(*self.size)] for b in self.names}
		self.cur = {b: 0 for b in self.names}
		self.fbo = gl.glGenFramebuffers(1)
		# New buffers clear to alpha 1, as the app's do.
		for b in self.names:
			for t in self.tex[b]:
				self._target(t)
				gl.glClearColor(0, 0, 0, 1)
				gl.glClear(gl.GL_COLOR_BUFFER_BIT)
		self.vbo = gl.glGenBuffers(1)
		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, self.vbo)
		quad = np.array([-1, -1, 1, -1, -1, 1, 1, 1], "f4")
		gl.glBufferData(gl.GL_ARRAY_BUFFER, quad.nbytes, quad, gl.GL_STATIC_DRAW)
		gl.glEnableVertexAttribArray(0)
		gl.glVertexAttribPointer(0, 2, gl.GL_FLOAT, False, 0, None)
		gl.glViewport(0, 0, *self.size)
		self.frame = 0
		self.seed = seed

	def _target(self, t):
		gl.glBindFramebuffer(gl.GL_FRAMEBUFFER, self.fbo)
		gl.glFramebufferTexture2D(gl.GL_FRAMEBUFFER, gl.GL_COLOR_ATTACHMENT0, gl.GL_TEXTURE_2D, t, 0)

	def run(self):
		# One frame: `stages` of Ready's steps.
		for b, prog, reads in self.passes:
			gl.glUseProgram(prog)
			for unit, (ch, src) in enumerate(reads):
				gl.glActiveTexture(gl.GL_TEXTURE0 + unit)
				gl.glBindTexture(gl.GL_TEXTURE_2D, self.tex[src][self.cur[src]])
				gl.glUniform1i(gl.glGetUniformLocation(prog, ch), unit)
			for name, setter, val in (("iResolution", gl.glUniform3f, (self.size[0], self.size[1], 1)),
					("iFrame", gl.glUniform1i, (self.frame,)),
					("iTime", gl.glUniform1f, (self.frame / 240.0,)),
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

	def read(self):
		# The chemicals the last stage left, (n, h, w).
		out = []
		for b in self.last:
			self._target(self.tex[b][self.cur[b]])
			px = np.empty((self.h, self.w, 4), "f4")
			gl.glReadPixels(0, 0, self.w, self.h, gl.GL_RGBA, gl.GL_FLOAT, px)
			out.append(px)
		return np.concatenate(out, axis=2)[:, :, :self.n].transpose(2, 0, 1).copy()

	def write(self, chems):
		# Replaces the grid (not the state row) in the last stage's buffers.
		full = np.zeros((self.n + (-self.n) % 4, self.h, self.w), "f4")
		full[:self.n] = chems
		for g, b in enumerate(self.last):
			rgba = np.ascontiguousarray(full[g * 4:g * 4 + 4].transpose(1, 2, 0))
			gl.glBindTexture(gl.GL_TEXTURE_2D, self.tex[b][self.cur[b]])
			gl.glTexSubImage2D(gl.GL_TEXTURE_2D, 0, 0, 0, self.w, self.h, gl.GL_RGBA, gl.GL_FLOAT, rgba)

	def close(self):
		for ts in self.tex.values():
			gl.glDeleteTextures(len(ts), ts)
		for _, prog, _ in self.passes:
			gl.glDeleteProgram(prog)
		gl.glDeleteFramebuffers(1, [self.fbo])
		gl.glDeleteBuffers(1, [self.vbo])


def stats(chems):
	# Each chemical's mean and std (over every 7th cell).
	flat = chems.reshape(chems.shape[0], -1)[:, ::7].astype("f8")
	flat = np.where(np.isfinite(flat), flat, np.nan)
	return np.stack([np.nanmean(flat, 1), np.nanstd(flat, 1)], 1)


def best_window(chems, s):
	# The s x s window most like the whole grid (wrapping heals its seams).
	n, h, w = chems.shape
	if h <= s and w <= s:
		return chems
	s = min(s, h, w)
	qs = (5, 50, 95)
	whole = np.percentile(chems[:, ::4, ::4].reshape(n, -1), qs, axis=1).T
	spread = np.maximum(whole[:, -1] - whole[:, 0], 1e-12)[:, None]
	best = None
	for y in range(0, h - s + 1, s // 2):
		for x in range(0, w - s + 1, s // 2):
			win = chems[:, y:y + s:4, x:x + s:4].reshape(n, -1)
			v = (np.abs(np.percentile(win, qs, axis=1).T - whole) / spread).max()
			if best is None or v < best[0]:
				best = (v, y, x)
	_, y, x = best
	return chems[:, y:y + s, x:x + s].copy()


# The share of the time a trial keeps the GPU busy: below 1, it rests
# between samples for as long as each took, scaled to leave the rest idle
# (vtipack build --duty), so a long build can run gently beside other work.
DUTY = 1.0


def _rest(since):
	if DUTY < 1.0:
		gl.glFinish()
		time.sleep((time.time() - since) * (1.0 / max(DUTY, 0.05) - 1.0))


class Trial:
	"""A pattern's shader run from its saved state, for comparing starts with."""

	def __init__(self, glsl_text, saved, steps=20000, samples=150):
		self.glsl = glsl_text
		self.n, self.h, self.w = saved.shape
		self.steps = steps
		self.samples = samples
		self.saved = saved
		self.fb, self.sb, self.end = self.trajectory(saved)
		s0 = self.sb[0]
		self.scale = np.maximum(3.3 * s0[:, 1], 0.25 * np.abs(s0[:, 0])) + 1e-12

	def trajectory(self, start):
		# Steps and (samples, n, mean/std) along a run from `start`, and its end.
		sim = Sim(self.glsl, self.w, self.h)
		try:
			sim.run()  # starts it: the state row and the initial pattern
			sim.write(start)
			runs = max(self.steps // sim.stages, 1)
			every = max(runs // self.samples, 1)
			frames, series = [], []
			since = time.time()
			for i in range(runs + 1):
				if i % every == 0:
					series.append(stats(sim.read()))
					frames.append(sim.frame * sim.stages)
					_rest(since)
					since = time.time()
				if i < runs:
					sim.run()
			return np.array(frames), np.array(series), sim.read()
		finally:
			sim.close()

	def unbounded(self):
		# Some chemical's mean ends far beyond where the saved state had it.
		s0, s1 = self.sb[0], self.sb[-1]
		return bool(np.any(np.abs(s1[:, 0]) > 20 * (np.abs(s0[:, 0]) + 3.3 * s0[:, 1] + 1e-3)))

	def score(self, start, floor=2000):
		"""(worst error, stretch, warm-up) for a start, or None if it blew up."""
		fs, ss, _ = self.trajectory(start)
		return score(self.fb, self.sb, fs, ss, self.scale, floor)


def score(fb, sb, fs, ss, scale, floor=2000):
	if np.isnan(ss).all():
		return None
	best = None
	for off in np.arange(0, floor + 1, 250):
		for k in np.arange(1.0, 2.001, 0.05):
			at = off + k * (fb - fb[0])
			use = at <= fs[-1]
			if not use.any() or (fb[use][-1] - fb[0]) < (fb[-1] - fb[0]) / 3:
				continue
			idx = np.searchsorted(fs, at[use]).clip(0, len(fs) - 1)
			d = np.abs(ss[idx] - sb[use]) / scale[None, :, None]
			e = float(np.nan_to_num(d, nan=1e9, posinf=1e9).max())
			if best is None or e < best[0]:
				best = (e, float(k), int(off))
	return best
