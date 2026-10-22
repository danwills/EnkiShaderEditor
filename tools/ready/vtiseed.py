"""Seeds: small records that grow into a start state statistically like a
saved one, so a pattern can restart without storing its whole state.

Each record holds the chemicals' names and exact means (every seed is
shifted to them: a reagent that only diffuses, like a wave's velocity,
conserves its mean, so an offset there never dies away) and one of:
- synthetic: each chemical's quantiles, radial spectrum and their
  correlations (a few hundred numbers), grown into matching noise;
- patch: the most typical window of the state, 8-bit, tiled at random
  rotations and flips;
- patches4: four smaller typical windows, tiled;
- patch_on_synth: the patch scattered over the synthetic seed;
- patch_on_rest: the busiest small window, scattered over each chemical's
  most common value as often as the means need (for sparse states);
- q8, q16: the whole state quantised to 8 or 16 bits per chemical;
- exact: the whole state as float32.
"""
import io
import json
import lzma
from statistics import NormalDist

import numpy as np

QUANTILES = 33
BANDS = 24
_nd = NormalDist()
# Normal CDF and inverse by table (NormalDist per value is slow).
_GX = np.linspace(-8, 8, 4097)
_CX = np.array([_nd.cdf(v) for v in _GX])


def gaussianise(a):
	# Ranks -> standard normal scores.
	flat = a.ravel()
	r = np.empty(flat.size)
	r[np.argsort(flat, kind="stable")] = (np.arange(flat.size) + 0.5) / flat.size
	grid = np.linspace(0.5 / flat.size, 1 - 0.5 / flat.size, 4097)
	inv = np.array([_nd.inv_cdf(v) for v in grid])
	return np.interp(r, grid, inv).reshape(a.shape)


def radial_bands(h, w, ref):
	# Log-spaced bands from a wave across `ref` cells (the size of the grid
	# the spectrum was measured on) up to Nyquist; longer waves join the first.
	fy = np.fft.fftfreq(h)[:, None]
	fx = np.fft.fftfreq(w)[None, :]
	f = np.sqrt(fx * fx + fy * fy)
	edges = np.geomspace(1.0 / ref, 0.71, BANDS + 1)
	return np.clip(np.searchsorted(edges, f) - 1, 0, BANDS - 1)


def describe(chems):
	n, h, w = chems.shape
	band = radial_bands(h, w, max(h, w))
	d = {"n": n, "size": [w, h], "quantiles": [], "spectrum": [], "constant": []}
	gs = []
	for a in chems:
		a = np.nan_to_num(a.astype("f8"))
		d["quantiles"].append(np.quantile(a, np.linspace(0, 1, QUANTILES)).tolist())
		d["constant"].append(bool(a.max() - a.min() < 1e-12))
		g = gaussianise(a) if not d["constant"][-1] else np.zeros_like(a)
		gs.append(g)
		p = np.abs(np.fft.fft2(g)) ** 2
		p[0, 0] = 0
		s = np.bincount(band.ravel(), p.ravel(), BANDS) / np.maximum(np.bincount(band.ravel(), None, BANDS), 1)
		d["spectrum"].append((s / max(s.sum(), 1e-30)).tolist())
	G = np.stack([g.ravel() for g in gs])
	with np.errstate(all="ignore"):
		c = np.nan_to_num(np.atleast_2d(np.corrcoef(G)))
	np.fill_diagonal(c, 1.0)
	d["corr"] = c.tolist()
	return d


def synthesise(d, w, h, rng):
	n = d["n"]
	band = radial_bands(h, w, max(d["size"]))
	fields = []
	for i in range(n):
		amp = np.sqrt(np.asarray(d["spectrum"][i])[band])
		amp[0, 0] = 0
		z = np.fft.ifft2(np.fft.fft2(rng.standard_normal((h, w))) * amp).real
		fields.append((z - z.mean()) / max(z.std(), 1e-30))
	# Mixed towards the chemicals' correlations (nearest positive definite).
	vals, vecs = np.linalg.eigh(np.asarray(d["corr"]))
	m = vecs @ np.diag(np.sqrt(np.clip(vals, 1e-6, None))) @ vecs.T
	F = m @ np.stack([x.ravel() for x in fields])
	out = np.empty((n, h, w), "f4")
	cdf = np.linspace(0, 1, QUANTILES)
	for i in range(n):
		q = np.asarray(d["quantiles"][i])
		if d["constant"][i]:
			out[i] = q[0]
			continue
		u = np.interp(F[i] / max(F[i].std(), 1e-30), _GX, _CX)
		out[i] = np.interp(u, cdf, q).reshape(h, w)
	return out


def quantise(a, bits=8):
	# Each chemical over its own range: (codes, lo, hi).
	n = a.shape[0]
	lo = a.reshape(n, -1).min(1).astype("f8")
	hi = a.reshape(n, -1).max(1).astype("f8")
	top = (1 << bits) - 1
	span = np.maximum(hi - lo, 1e-30)
	codes = np.round((a - lo.reshape((n,) + (1,) * (a.ndim - 1))) / span.reshape((n,) + (1,) * (a.ndim - 1)) * top)
	return codes.astype("u1" if bits == 8 else "<u2"), lo, hi


def dequantise(codes, lo, hi, bits=8):
	n = codes.shape[0]
	shape = (n,) + (1,) * (codes.ndim - 1)
	return (lo.reshape(shape) + codes / ((1 << bits) - 1) * (hi - lo).reshape(shape)).astype("f4")


def _window_score(chems, size, qs=(5, 25, 50, 75, 95)):
	# Each size x size window (at quarter steps) by how unlike the whole
	# grid its chemicals' percentiles are.
	n, h, w = chems.shape
	whole = np.percentile(chems.reshape(n, -1), qs, axis=1).T
	spread = np.maximum(whole[:, -1] - whole[:, 0], 1e-12)[:, None]
	out = []
	step = max(size // 4, 1)
	for y in range(0, h - size + 1, step):
		for x in range(0, w - size + 1, step):
			win = chems[:, y:y + size, x:x + size].reshape(n, -1)
			out.append(((np.abs(np.percentile(win, qs, axis=1).T - whole) / spread).max(), y, x))
	return sorted(out)


def typical_windows(chems, size, count):
	# The `count` windows most like the whole grid, not overlapping much.
	size = min(size, chems.shape[1], chems.shape[2])
	chosen = []
	for v, y, x in _window_score(chems, size):
		if all(abs(y - cy) >= size // 2 or abs(x - cx) >= size // 2 for cy, cx in chosen):
			chosen.append((y, x))
		if len(chosen) == count:
			break
	return [chems[:, y:y + size, x:x + size] for y, x in chosen]


def busiest_window(chems, size):
	# The window with the most going on.
	n, h, w = chems.shape
	size = min(size, h, w)
	spread = np.maximum(chems.reshape(n, -1).std(1), 1e-12)
	best = None
	for y in range(0, h - size + 1, max(size // 4, 1)):
		for x in range(0, w - size + 1, max(size // 4, 1)):
			v = (chems[:, y:y + size, x:x + size].reshape(n, -1).std(1) / spread).sum()
			if best is None or v > best[0]:
				best = (v, y, x)
	_, y, x = best
	return chems[:, y:y + size, x:x + size]


def rest_values(chems):
	# Each chemical's most common value: the empty background.
	out = []
	for a in chems.reshape(len(chems), -1):
		try:
			hist, edges = np.histogram(a, 256)
		except ValueError:
			# A range too narrow for 256 bins at this magnitude: nearly constant.
			out.append(float(np.median(a)))
			continue
		i = hist.argmax()
		out.append(0.5 * (edges[i] + edges[i + 1]))
	return np.array(out, "f8")


def rest_coverage(chems, p, bg):
	# How much of the grid the patch should cover for the means to come out right.
	n = len(chems)
	m = chems.reshape(n, -1).mean(1)
	pm = p.reshape(n, -1).mean(1)
	wts = np.abs(pm - bg)
	return float(np.clip(np.sum(wts * (m - bg) / np.where(wts > 1e-12, pm - bg, 1)) / max(wts.sum(), 1e-12),
			0.03, 1.0))


def tile(patches, w, h, rng):
	# Copies over the grid, each a random patch at a random offset, rotation and flip.
	n, s, _ = patches[0].shape
	out = np.empty((n, h, w), "f4")
	for y in range(0, h, s):
		for x in range(0, w, s):
			t = np.rot90(patches[rng.integers(len(patches))], rng.integers(4), axes=(1, 2))
			if rng.integers(2):
				t = t[:, :, ::-1]
			t = np.roll(t, (rng.integers(s), rng.integers(s)), axis=(1, 2))
			hh, ww = min(s, h - y), min(s, w - x)
			out[:, y:y + hh, x:x + ww] = t[:, :hh, :ww]
	return out


def scatter(background, p, coverage, rng):
	# Copies of the patch dropped on a background, with soft edges, until
	# about `coverage` of the grid is patch.
	out = background.copy()
	n, h, w = out.shape
	s = p.shape[1]
	r = (np.arange(s) - (s - 1) / 2) / (s / 2)
	m = np.clip((1 - np.maximum(np.abs(r)[:, None], np.abs(r)[None, :])) * 4, 0, 1)
	for _ in range(max(1, int(round(coverage * h * w / (s * s))))):
		t = np.rot90(p, rng.integers(4), axes=(1, 2))
		y, x = rng.integers(h), rng.integers(w)
		ys = (np.arange(s) + y) % h
		xs = (np.arange(s) + x) % w
		cur = out[:, ys][:, :, xs]
		out[:, ys[:, None], xs[None, :]] = cur * (1 - m) + t * m
	return out


def _quantise_patches(patches):
	# k patches (n, s, s) as (n, k, s, s) codes, each chemical over its range in all of them.
	return quantise(np.stack(patches, 1))


def seeds(names, chems, means=None):
	"""The seed records for a state, cheapest first: [(kind, record)].
	`chems` is the (n, h, w) state they're made from (a crop of a big one
	is fine); `means`, the chemicals' means to grow them to (default
	chems')."""
	n, h, w = chems.shape
	base = {"names": list(names),
			"means": chems.reshape(n, -1).astype("f8").mean(1) if means is None else np.asarray(means, "f8")}
	ps = min(128, h // 2, w // 2)
	desc = describe(chems)
	patch = typical_windows(chems, ps, 1)
	busy = busiest_window(chems, ps // 2)
	bg = rest_values(chems)
	out = [("synthetic", dict(base, desc=desc)),
			("patch_on_rest", dict(base, patches=_quantise_patches([busy]), bg=bg,
					coverage=rest_coverage(chems, busy, bg))),
			("patch", dict(base, patches=_quantise_patches(patch))),
			("patches4", dict(base, patches=_quantise_patches(typical_windows(chems, ps // 2, 4)))),
			("patch_on_synth", dict(base, desc=desc, patches=_quantise_patches(patch)))]
	return sorted(out, key=lambda kr: len(encode(*kr)))


def copies(names, full):
	"""Records holding the whole state, smallest first: q8, q16, exact."""
	n = len(full)
	base = {"names": list(names), "means": full.reshape(n, -1).astype("f8").mean(1)}
	yield "q8", dict(base, state=quantise(full, 8))
	yield "q16", dict(base, state=quantise(full, 16))
	yield "exact", dict(base, exact=np.ascontiguousarray(full, "<f4"))


def _patches(rec):
	codes, lo, hi = rec["patches"]
	p = dequantise(codes, lo, hi)
	return [p[:, j] for j in range(p.shape[1])]


def grow(kind, rec, w, h, seed=1, means=None):
	"""The start state (n, h, w) a record grows into on a w x h grid, at
	the record's means (or `means`)."""
	rng = np.random.default_rng(seed)
	n = len(rec["names"])
	if kind == "exact":
		return rec["exact"].astype("f4")
	if kind in ("q8", "q16"):
		codes, lo, hi = rec["state"]
		s = dequantise(codes, lo, hi, 8 if kind == "q8" else 16)
	elif kind == "synthetic":
		s = synthesise(rec["desc"], w, h, rng)
	elif kind in ("patch", "patches4"):
		s = tile(_patches(rec), w, h, rng)
	elif kind == "patch_on_synth":
		s = scatter(synthesise(rec["desc"], w, h, rng), _patches(rec)[0], 0.3, rng)
	elif kind == "patch_on_rest":
		base = np.broadcast_to(np.asarray(rec["bg"], "f4")[:, None, None], (n, h, w)).copy()
		s = scatter(base, _patches(rec)[0], rec["coverage"], rng)
	else:
		raise ValueError("seed kind %r" % kind)
	means = np.asarray(rec["means"] if means is None else means)
	return (s + (means - s.reshape(n, -1).astype("f8").mean(1))[:, None, None]).astype("f4")


def encode(kind, rec):
	# One record as bytes: a .npz of its arrays (JSON for the rest), LZMA'd.
	arrays = {}
	meta = {"kind": kind}
	for k, v in rec.items():
		if k in ("patches", "state"):
			arrays[k], arrays[k + "_lo"], arrays[k + "_hi"] = v
		elif isinstance(v, np.ndarray):
			arrays[k] = v
		else:
			meta[k] = v
	buf = io.BytesIO()
	np.savez(buf, meta=np.frombuffer(json.dumps(meta).encode(), "u1"), **arrays)
	return lzma.compress(buf.getvalue(), preset=9)


def decode(data):
	z = np.load(io.BytesIO(lzma.decompress(data)))
	meta = json.loads(bytes(z["meta"]).decode())
	kind = meta.pop("kind")
	rec = dict(meta)
	for k in z.files:
		if k == "meta" or k.endswith(("_lo", "_hi")):
			continue
		rec[k] = (z[k], z[k + "_lo"], z[k + "_hi"]) if k in ("patches", "state") else z[k]
	return kind, rec
