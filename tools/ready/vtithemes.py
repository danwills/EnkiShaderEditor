#!/usr/bin/env python3
"""Drafts a curated packs.json for a vtipack collection: themed packs (by
rule and by the names Dan's lineages carry - fuseWorms, magicDance, nova,
boundaryPulses ...) of at most --size patterns each, picked to differ as
much as they can from one another (farthest first, by their parameters
and the words in their names). What a theme has more of, and anything no
theme takes, is left out of every pack - vticurate.py shows those under
Not in a pack, to drag in by hand.

  vtithemes.py OUT [--size 20] [--dry-run]

The packs.json it replaces is kept as packs.json.<time>.bak. Left-out
(excluded) patterns aren't picked; ones not built yet are, after the rest.
"""
import argparse
import json
import lzma
import math
import os
import re
import shutil
import time

# (id, title, blurb, test on the lower-cased path), first match wins.
THEMES = [
	("fuse-worms", "Fuse Worms",
		"Worms that meet, fuse and split again, riding a slow wave that remembers where they've been: "
		"Gray-Scott with a history chemical and a travelling wave feeding back into it.",
		lambda p: "fuseworms" in p),
	("magic-dance", "Magic Dance",
		"Solitons that circle, chase and swap partners on a rippling floor - Gray-Scott with history and wave coupling, "
		"tuned until the dots dance.",
		lambda p: "magicdance" in p or "dance2" in p),
	("chasms-and-swirls", "Chasms and Swirls",
		"Spots carve canyons into the wave history and swirl along their walls: deep trails, fractured floors "
		"and slow spirals.",
		lambda p: "chasms" in p or "swirls" in p),
	("puffy-gliders", "Puffy Gliders",
		"Soft gliders that puff, bud and leave a temporal web of their paths behind them.",
		lambda p: "puffygliders" in p or "temporalweb" in p or "microgrowth" in p),
	("active-mesh", "Active Mesh",
		"A living net: cells that hold their places in a mesh, pulse and rearrange when the wave passes through. "
		"Some get psychedelic.",
		lambda p: "activemesh" in p or "flowingmesh" in p),
	("flowy-dots", "Flowy Dots",
		"Sparse dots drifting through a cloud tank, each with a long, smooth wave wake trailing behind.",
		lambda p: "flowydots" in p or "nicewavewake" in p or "cloudtank" in p),
	("history-wave-dc", "Deep Currents",
		"Gray-Scott whose history runs on a DC wave: fibrous fills, powered-up a and b, and patterns that "
		"keep reorganising long after they've settled.",
		lambda p: "historywavedc" in p or "abpowers" in p),
	("wiggly", "Wigglers",
		"Restless worms that wiggle and never quite settle, on wave settings tuned for nervous energy.",
		lambda p: "wiggly" in p or "wavetune" in p or "wander" in p),
	("dot-fights", "Dot Fights",
		"Oscillating worms, single solitons and super-long trails competing for space.",
		lambda p: "oscworms" in p or "dotfights" in p or "supertraily" in p or "singles" in p),
	("evolving-mask", "Evolving Mask",
		"Gray-Scott with a slowly evolving mask chemical that redraws the map of where spots may live: "
		"separations, branching fronts and froth.",
		lambda p: "grayscott-withwave/" in p and "evolvingmask" in p),
	("wave-coupled-gray-scott", "Wave-Coupled Gray-Scott",
		"The first experiments coupling Gray-Scott to a wave and a history chemical: bouncy space-fillers, "
		"echoes and wakes.",
		lambda p: "grayscott-withwave/" in p and ("historyandwavecoupling" in p or "withwavecoupling" in p
				or "bouncyspacefiller" in p)),
	("history-wave", "History Wave",
		"Gray-Scott spots writing their history into a wave they then ride - lifey, unstable, "
		"sometimes dollar-shaped.",
		lambda p: "grayscott-withwave/" in p or "grayscott-historywave" in p),
	("sharpened-gray-scott", "Sharpened Gray-Scott",
		"Gray-Scott with a sharpening tweak: crisp cell trails, gelatinous blobs, ropes and Voronoi-ish cells.",
		lambda p: "grayscott-sharpentweak/" in p),
	("meta-gray-scott", "Meta Gray-Scott",
		"Gray-Scott running on top of another Gray-Scott - worms, solitons and fiestas from two coupled layers.",
		lambda p: "metagrayscott" in p),
	("u-skate-world", "U-Skate World",
		"Robert Munafo's U-Skate world of Gray-Scott: skaters, spin gliders, block worlds and travelling smiles.",
		lambda p: "uskate" in p or "u-skate" in p or "helix-gliders" in p),
	("gray-scott-demos", "Gray-Scott Demos",
		"Gray-Scott in its many moods: photo-solitons, worms, popping dots and organising mish-mashes.",
		lambda p: "grayscott-djw/" in p or "purwins-glider/" in p or "grayscott-minwave/" in p),
	("orbits-boundary-pulses", "Orbits: Boundary Pulses",
		"Dan's Orbit rule with pulses sent in from the edges: life waves and spaceships.",
		lambda p: "orbits" in p and ("boundarypulses" in p or "lifewaves" in p or "spaceships" in p)),
	("orbits-julia", "Orbits: Julia",
		"Orbit rule tests - Julia-set dynamics in a reaction-diffusion, phases, slow spins and odd squiggles.",
		lambda p: "orbittest" in p or "orbits_julia" in p),
	("orbits-nova", "Orbits: Nova",
		"The Nova variant of the Orbit rule: communities, beans, sausages, fractal tiles and chaos holes.",
		lambda p: "orbits" in p and "nova" in p),
	("orbits-chaos-spirals", "Orbits: Chaos Spirals",
		"Orbit patterns evolved by hand towards chaos spirals, travelling crystals and mungey waves.",
		lambda p: "orbits" in p and ("chaospirals" in p or "evolvesto" in p or "travellingcrystals" in p
				or "mungey" in p)),
	("orbits", "Orbits",
		"Dan's own Orbit rule (pow, atan2 and direct assignments): soft spots, epochs and explodey buddhas.",
		lambda p: "orbit" in p),
	("cell-spirals", "Cell Spirals",
		"Ginzburg-Landau ramped across the grid so spirals become cells: honeycombs, mazes, web fences, "
		"turing columns and continuous mitosis.",
		lambda p: "gl-ramps/" in p or "cellspiral" in p),
	("complex-ginzburg-landau", "Complex Ginzburg-Landau",
		"The complex Ginzburg-Landau equation: spirals and anti-spirals, chaos mush, soft soliton webs.",
		lambda p: "cglrd/" in p or p.startswith("ginzburg-landau/") or "yang2006/" in p),
	("ginzburg-landau-waves", "Ginzburg-Landau Waves",
		"Ginzburg-Landau magnitude coupled to a wave and a history: long wakes, embryos and negotiations.",
		lambda p: "wavecoupling" in p or "waveandhistorycoupling" in p),
	("ginzburg-landau-magnitude", "Ginzburg-Landau Magnitude",
		"Ginzburg-Landau with its magnitude remapped: slipping spirals, cell ordering, disco and smoke.",
		lambda p: "gl-magnitude" in p or "landau" in p or "cglrd" in p),
	("boiling-heat", "Boiling Heat",
		"A heat equation with history and a wave, boiling: ripple gliders, windscreen rain, ripple meshes "
		"and travellers.",
		lambda p: "heat" in p and "boil" in p),
	("waves-and-heat", "Waves and Heat",
		"The wave and heat equations, ripples and kernels: the plainest physics, as patterns of their own.",
		lambda p: "heat" in p or "wave_equation" in p or re.search(r"(^|/)ripple", p) is not None),
	("kuramoto-sivashinsky", "Kuramoto-Sivashinsky",
		"Flame fronts: the Kuramoto-Sivashinsky equation's flame puffs, hot clusters, pockets and hexagons.",
		lambda p: "kuramoto" in p or "kse" in p),
	("excitable-media", "Excitable Media",
		"FitzHugh-Nagumo: nerve-like pulses, splitting tips, spiral turbulence and pulsing mazes.",
		lambda p: "fitzhugh" in p),
	("ready-classics", "Ready Classics",
		"Patterns from Ready's own collection: Yang's jumping waves, McCabe, Meinhardt's stripes, "
		"Schrodinger packets, Purwins' gliders and more.",
		lambda p: re.match(r"(.*/)?(yang|kytta|mccabe|meinhardt|schrodinger|purwins|gray-scott/|turing|schlogl|"
				r"oregonator|brusselator|pearson|gladman|grayscott_|mutually|parameter_|phyllotaxis)", p) is not None),
	("life-and-friends", "Life and Friends",
		"Cellular automata and their smooth cousins from Ready's collection: Conway's Life, Larger than Life, "
		"SmoothLife gliders, salt, erosion, smoke and the community's own experiments.",
		lambda p: re.match(r"(.*/)?(cellularautomata|conway|larger|smoothlife|salt|erosion|bays|kernel_test|"
				r"fromcornus|smoke)", p) is not None),
]


def words(path):
	return {t for t in re.split(r"[_\-. /]+", path.lower()[:-4]) if len(t) > 2}


def number(v):
	try:
		return float(v)
	except ValueError:
		return None


def pick(rows, size):
	"""Up to size rows, farthest first, starting from the plainest name.
	Only one of a file name (a pack's shaders are named after them): the
	first of its folders in sorted order, any "broken" one last."""
	seen = set()
	rows = [r for r in sorted(rows, key=lambda r: ("broken" in r["path"].lower(), r["path"]))
			if not (os.path.basename(r["path"]) in seen or seen.add(os.path.basename(r["path"])))]
	if len(rows) <= size:
		order = rows[:]
	else:
		# Each parameter scaled by its spread within the family.
		spread = {}
		for r in rows:
			for k, v in r["params"].items():
				spread.setdefault((r["fam"], k), []).append(v)
		scale = {k: (max(v) - min(v)) or 1.0 for k, v in spread.items()}

		def far(a, b):
			d = 0.0
			if a["fam"] != b["fam"]:
				d += 1.5
			else:
				keys = set(a["params"]) | set(b["params"])
				if keys:
					d += math.sqrt(sum(((a["params"].get(k, 0) - b["params"].get(k, 0))
							/ scale[(a["fam"], k)]) ** 2 for k in keys) / len(keys))
			wa, wb = a["words"], b["words"]
			d += 0.75 * (1 - len(wa & wb) / max(1, len(wa | wb)))
			return d

		pool = sorted(rows, key=lambda r: (r["kind"] == "pending", len(r["path"])))
		order = [pool.pop(0)]
		gap = [far(order[0], r) for r in pool]
		while pool and len(order) < size:
			# Built ones first: a pending one counts as half as far.
			i = max(range(len(pool)), key=lambda j: gap[j] * (0.5 if pool[j]["kind"] == "pending" else 1))
			order.append(pool.pop(i))
			gap.pop(i)
			gap = [min(g, far(order[-1], r)) for g, r in zip(gap, pool)]
	return [r["path"] for r in order]


def main():
	ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
	ap.add_argument("out")
	ap.add_argument("--size", type=int, default=20)
	ap.add_argument("--dry-run", action="store_true")
	args = ap.parse_args()
	idx = json.load(open(os.path.join(args.out, "index.json")))
	themes = {t[0]: [] for t in THEMES}
	left = []
	for fi, fam in enumerate(idx["families"]):
		for f in fam["files"]:
			p = f["path"]
			if p in idx["duplicates"]:
				continue
			s = os.path.join(args.out, "states", f["md5"] + ".json")
			kind = json.load(open(s))["kind"] if os.path.exists(s) else "pending"
			if kind == "excluded" or p.lower().startswith("cpu-only/"):
				continue
			params = {k: n for k, n in ((k, number(v)) for k, v in f["params"].items()) if n is not None}
			row = {"path": p, "fam": fi, "rule": fam["names"][0], "kind": kind, "params": params,
					"words": words(p)}
			for tid, _, _, test in THEMES:
				if test(p.lower()):
					themes[tid].append(row)
					break
			else:
				left.append(p)
	packs = []
	for tid, title, blurb, _ in THEMES:
		rows = themes[tid]
		if not rows:
			continue
		rules = sorted({r["rule"] for r in rows})
		packs.append({"id": tid, "title": title, "description": blurb,
				"rule": rules[0] if len(rules) == 1 else "assorted", "paths": pick(rows, args.size)})
		print("%3d of %3d  %s" % (len(packs[-1]["paths"]), len(rows), title))
	print("%d packs, %d patterns; %d themed but not picked; no theme: %s" % (
			len(packs), sum(len(p["paths"]) for p in packs),
			sum(len(v) for v in themes.values()) - sum(len(p["paths"]) for p in packs), left or "none"))
	if args.dry_run:
		return
	f = os.path.join(args.out, "packs.json")
	if os.path.exists(f):
		shutil.copyfile(f, "%s.%s.bak" % (f, time.strftime("%Y%m%d-%H%M%S")))
	with open(f + ".tmp", "w") as fh:
		json.dump(packs, fh, indent=1)
	os.replace(f + ".tmp", f)


if __name__ == "__main__":
	main()
