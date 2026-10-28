#!/usr/bin/env python3
"""Searches Slime Cartographer's settings for patterns like Softology's
Physarum pictures, headless on the desktop GPU. The pictures (img*.jpg,
the first skipped: it's a photo of the real mould) are in $SOFTOLOGY,
by default ~/dev/shaderEditor/physarum/softology/dl - downloaded from
softologyblog.wordpress.com/2019/04/11/physarum-simulations.

  search.py bench                      time one candidate
  search.py random N OUT.npz [SEED]    N random candidates: settings + features + trails
  search.py refine IN.npz OUT.npz      local search around each target's best
  search.py sheet IN.npz OUT.png       targets beside their best few matches

The shader's fresh-colony defines in Buffer A become uniforms, so it
compiles once and every candidate is just a restart with new values.
"""
import os
import re
import sys
import time

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
PICTURES = os.environ.get("SOFTOLOGY", os.path.expanduser("~/dev/shaderEditor/physarum/softology/dl"))
sys.path.insert(0, os.path.join(HERE, "..", "powder"))
import run_powder  # noqa: E402

gl = run_powder.gl
SHADER = os.path.join(HERE, "slime_cartographer.glsl")
W, H = 360, 772
STEPS = 1500

# name, low, high, log scale
SPACE = [
	("SENSOR_ANGLE", 0.05, 2.0, True),
	("SENSOR_DISTANCE", 0.002, 0.04, True),
	("TURN", 0.03, 1.5, True),
	("SPEED", 0.2, 1.0, True),
	("FADE", 0.002, 0.3, True),
	("DIFFUSE", 0.02, 1.0, True),
	("BLUR", 1.0, 5.0, False),
	("JITTER", 0.005, 0.5, True),
	("SPAWN", 0.0001, 0.01, True),
]
NAMES = [s[0] for s in SPACE]


def uniformed(text):
	for name in NAMES:
		text, n = re.subn(r"^#define %s .*$" % name, "uniform float %s;" % name, text, flags=re.M)
		assert n >= 1, name
	return text


class Sim(run_powder.Run):
	def __init__(self):
		text = uniformed(open(SHADER).read())
		super().__init__(text, W, H)
		# Each pass's channels with their filtering and wrapping.
		self.modes = {}
		passes = [p for p in run_powder.check_shader.passes(text) if p[0] != "Image"]
		for (name, src), (b, prog, reads) in zip(passes, self.passes):
			for ch, buf, params in re.findall(r"uniform sampler2D (iChannel\d);\s*///buf:([A-D]);?([^\n]*)", src):
				self.modes[(prog, ch)] = ("min:n" in params, "s:r" in params)
		self.values = {}

	def _bind(self, prog, reads, size):
		put = super()._bind(prog, reads, size)
		for unit, (ch, src) in enumerate(reads):
			nearest, repeat = self.modes.get((prog, ch), (False, False))
			gl.glActiveTexture(gl.GL_TEXTURE0 + unit)
			f = gl.GL_NEAREST if nearest else gl.GL_LINEAR
			w = gl.GL_REPEAT if repeat else gl.GL_CLAMP_TO_EDGE
			for p in (gl.GL_TEXTURE_MIN_FILTER, gl.GL_TEXTURE_MAG_FILTER):
				gl.glTexParameteri(gl.GL_TEXTURE_2D, p, f)
			for p in (gl.GL_TEXTURE_WRAP_S, gl.GL_TEXTURE_WRAP_T):
				gl.glTexParameteri(gl.GL_TEXTURE_2D, p, w)
		for name, v in self.values.items():
			put(name, gl.glUniform1f, float(v))
		return put

	def run(self, values, steps=STEPS):
		self.values = dict(values)
		for b in self.tex:
			for t in self.tex[b]:
				self._target(t)
				gl.glClearColor(0, 0, 0, 0)
				gl.glClear(gl.GL_COLOR_BUFFER_BIT)
		self.step = 0
		self.steps = 1
		gl.glViewport(0, 0, W, H)
		for _ in range(steps):
			self.frame()
		trail = self.read("B")[::-1, :, 0]
		return trail[:-2]  # without the settings row


# ---- Features -------------------------------------------------------------

def normalise(g):
	lo, hi = np.percentile(g, [1.0, 99.5])
	return np.clip((g - lo) / max(hi - lo, 1e-9), 0.0, 1.0)


def blur(g, sigma):
	if sigma <= 0.3:
		return g
	fy = np.fft.fftfreq(g.shape[0])[:, None]
	fx = np.fft.fftfreq(g.shape[1])[None, :]
	k = np.exp(-2 * (np.pi * sigma) ** 2 * (fx * fx + fy * fy))
	return np.real(np.fft.ifft2(np.fft.fft2(g) * k))


def shrink(g, factor):
	if abs(factor - 1.0) < 0.01:
		return g
	im = Image.fromarray(g.astype("f4"), mode="F")
	size = (max(8, int(g.shape[1] / factor)), max(8, int(g.shape[0] / factor)))
	return np.asarray(im.resize(size, Image.BOX if factor > 1.0 else Image.BICUBIC))


BINS = np.linspace(np.log(1 / 180.0), np.log(0.5), 40)


def spectrum(g):
	g = g - g.mean()
	wy = np.hanning(g.shape[0])[:, None]
	wx = np.hanning(g.shape[1])[None, :]
	p = np.abs(np.fft.fft2(g * wy * wx)) ** 2
	fy = np.fft.fftfreq(g.shape[0])[:, None]
	fx = np.fft.fftfreq(g.shape[1])[None, :]
	f = np.sqrt(fx * fx + fy * fy)
	lf = np.log(np.maximum(f, 1e-9))
	idx = np.digitize(lf.ravel(), BINS)
	s = np.bincount(idx, weights=p.ravel(), minlength=len(BINS) + 1)
	c = np.bincount(idx, minlength=len(BINS) + 1)
	s = np.log(s[1:len(BINS)] / np.maximum(c[1:len(BINS)], 1) + 1e-12)
	# Power per log-frequency bin (times f^2), so peaks show the scales
	# the pattern's made of.
	centres = 0.5 * (BINS[1:] + BINS[:-1])
	return s + 2 * centres


def structure(g):
	"""Line-ness (coherence), one dominant direction (anisotropy), and an
	intensity histogram."""
	gy, gx = np.gradient(blur(g, 0.7))
	jxx, jyy, jxy = blur(gx * gx, 2.0), blur(gy * gy, 2.0), blur(gx * gy, 2.0)
	tr = jxx + jyy
	coh = np.sqrt((jxx - jyy) ** 2 + 4 * jxy ** 2) / np.maximum(tr, 1e-12)
	coherence = float((coh * tr).sum() / max(tr.sum(), 1e-12))
	angle = 0.5 * np.arctan2(2 * jxy, jxx - jyy)
	h = np.bincount(((angle + np.pi / 2) / np.pi * 18).astype(int).clip(0, 17).ravel(),
			weights=(coh * tr).ravel(), minlength=18)
	aniso = float(h.max() / max(h.mean(), 1e-12))
	hist = np.histogram(g, bins=12, range=(0, 1))[0].astype(float)
	hist /= hist.sum()
	return coherence, aniso, hist


def features(trail):
	g = normalise(trail)
	return {"spec": spectrum(g), "g": g}


def target_features(path):
	im = np.asarray(Image.open(path).convert("L")).astype("f4") / 255.0
	g = normalise(im)
	coh, an, hist = structure(g)
	return {"spec": spectrum(g), "coh": coh, "aniso": an, "hist": hist, "g": g}


def compare(t, c):
	"""Distance and the best scale (how much smaller the target's pixels
	see the candidate)."""
	best = (1e9, 0)
	ts, cs = t["spec"], c["spec"]
	n = len(ts)
	step = BINS[1] - BINS[0]
	# The target's high end (JPEG, resampled) isn't trusted.
	tmax = n - 4
	for k in range(-8, 18):
		# Candidate shrunk by e^(k*step): its spectrum shifts up by k bins.
		lo = max(0, k)
		hi = min(tmax, n + k)
		if hi - lo < 12:
			continue
		a = ts[lo:hi]
		b = cs[lo - k:hi - k]
		d = (a - a.mean()) - (b - b.mean())
		e = float(np.sqrt((d * d).mean()))
		if e < best[0]:
			best = (e, k)
	e, k = best
	factor = float(np.exp(k * step))
	key = round(factor, 2)
	cache = c.setdefault("at", {})
	if key not in cache:
		cache[key] = structure(shrink(c["g"], factor))
	coh, an, hist = cache[key]
	return (e + 3.0 * abs(coh - t["coh"]) + 0.5 * abs(np.log(an / t["aniso"]))
			+ 2.0 * np.abs(hist - t["hist"]).sum()), factor


def targets():
	files = sorted(f for f in os.listdir(PICTURES) if f.startswith("img"))[1:]
	return [(f[:-4], target_features(os.path.join(PICTURES, f))) for f in files]


# ---- Search ---------------------------------------------------------------

def sample(rng):
	v = {}
	for name, lo, hi, lg in SPACE:
		u = rng.random()
		v[name] = float(np.exp(np.log(lo) + u * (np.log(hi) - np.log(lo)))) if lg else lo + u * (hi - lo)
	if rng.random() < 0.4:
		v["JITTER"] = 0.0
	return v


def perturb(v, rng, scale):
	out = dict(v)
	for name, lo, hi, lg in SPACE:
		if rng.random() < 0.5:
			continue
		if name == "JITTER" and v[name] == 0.0:
			if rng.random() < 0.3:
				out[name] = 0.01
			continue
		if lg:
			out[name] = float(np.clip(v[name] * np.exp(rng.normal() * scale), lo, hi))
		else:
			out[name] = float(np.clip(v[name] + rng.normal() * scale * (hi - lo) * 0.5, lo, hi))
	return out


def defaults():
	return {"SENSOR_ANGLE": 0.40, "SENSOR_DISTANCE": 0.007, "TURN": 0.8, "SPEED": 1.0, "FADE": 0.03,
			"DIFFUSE": 0.1, "BLUR": 1.0, "JITTER": 0.0, "SPAWN": 0.0004}


def save(path, cands, trails):
	np.savez_compressed(path, cands=np.array([[c[n] for n in NAMES] for c in cands], "f8"),
			trails=np.array(trails, "f2"))


def load(path):
	d = np.load(path)
	cands = [dict(zip(NAMES, map(float, row))) for row in d["cands"]]
	return cands, list(d["trails"].astype("f4"))


def small(trail):
	"""Kept at half size, to look at and to match on."""
	return shrink(trail, 2.0)


def score_all(cands, trails, tg):
	feats = [features(t) for t in trails]
	scores = np.zeros((len(tg), len(cands)))
	scales = np.zeros((len(tg), len(cands)))
	for j, f in enumerate(feats):
		for i, (_, t) in enumerate(tg):
			scores[i, j], scales[i, j] = compare(t, f)
	return scores, scales


def main():
	cmd = sys.argv[1]
	if cmd == "bench":
		sim = Sim()
		t = time.time()
		tr = sim.run(defaults())
		print("%.1f s for %d steps" % (time.time() - t, STEPS), tr.shape, tr.min(), tr.max())
		Image.fromarray((normalise(tr) * 255).astype("u1")).save(os.path.join(HERE, "bench.png"))
	elif cmd == "random":
		n, out = int(sys.argv[2]), sys.argv[3]
		rng = np.random.default_rng(int(sys.argv[4]) if len(sys.argv) > 4 else 1)
		sim = Sim()
		cands, trails = [defaults()], []
		cands += [sample(rng) for _ in range(n - 1)]
		t0 = time.time()
		for i, c in enumerate(cands):
			trails.append(small(sim.run(c)))
			if i % 20 == 0:
				print("%d/%d %.0f s" % (i, n, time.time() - t0), flush=True)
				save(out, cands[:len(trails)], trails)
		save(out, cands, trails)
	elif cmd == "refine":
		cands, trails = load(sys.argv[2])
		out = sys.argv[3]
		rounds = int(sys.argv[4]) if len(sys.argv) > 4 else 3
		per = int(sys.argv[5]) if len(sys.argv) > 5 else 6
		tg = targets()
		sim = Sim()
		rng = np.random.default_rng(7)
		t0 = time.time()
		for r in range(rounds):
			scores, _ = score_all(cands, trails, tg)
			new = []
			for i in range(len(tg)):
				for j in np.argsort(scores[i])[:2]:
					for _ in range(per // 2):
						new.append(perturb(cands[j], rng, 0.5 / (r + 1)))
			for c in new:
				cands.append(c)
				trails.append(small(sim.run(c)))
			save(out, cands, trails)
			print("round %d: %d candidates, %.0f s" % (r, len(cands), time.time() - t0), flush=True)
	elif cmd == "sheet":
		cands, trails = load(sys.argv[2])
		out = sys.argv[3]
		tg = targets()
		scores, scales = score_all(cands, trails, tg)
		tile = 200
		rows = []
		report = []
		for i, (name, t) in enumerate(tg):
			ims = [Image.fromarray((t["g"] * 255).astype("u1")).resize((tile * 500 // 281, tile))]
			for j in np.argsort(scores[i])[:4]:
				# The target's 500 x 281 seen at the found scale.
				f = scales[i, j]
				g = normalise(trails[j])
				crop = g[:max(16, int(281 * f)), :max(16, int(500 * f))]
				im = Image.fromarray((crop * 255).astype("u1")).resize((tile * 500 // 281, tile))
				ims.append(im)
				report.append("%s #%d %.3f x%.2f %s" % (name, j, scores[i, j], scales[i, j],
						" ".join("%s=%.4g" % (k, cands[j][k]) for k in NAMES)))
			row = Image.new("L", (sum(im.width + 4 for im in ims), tile), 255)
			x = 0
			for im in ims:
				row.paste(im, (x, 0))
				x += im.width + 4
			rows.append(row)
		sheet = Image.new("L", (rows[0].width, len(rows) * (tile + 4)), 255)
		for k, row in enumerate(rows):
			sheet.paste(row, (0, k * (tile + 4)))
		sheet.save(out)
		open(out[:-4] + ".txt", "w").write("\n".join(report) + "\n")


if __name__ == "__main__":
	main()
