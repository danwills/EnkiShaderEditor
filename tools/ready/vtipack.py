#!/usr/bin/env python3
"""Packs a collection of Ready patterns small: one copy of each rule, the
parameter sets that vary it, and for each pattern's saved state the
cheapest seed (see vtiseed.py) that still restarts it like the state does.

The source folder is only read. Everything goes in the output folder:
- index.json: the families (patterns sharing a rule: its type, chemicals
  and code), each with its files and their parameters, files with equal
  parameters grouped as variants; plus exact duplicates;
- headers.json.xz: each file's header (rule, initial pattern, render
  settings), by md5;
- states/<md5>.json and .seed: what each file's state became, and why.

  vtipack.py index SRC OUT     find families, duplicates and headers
  vtipack.py build SRC OUT     choose and store a seed for every state
                               (the GPU trial; resumable, and several
                               builds can run at once on one list)
  vtipack.py expand OUT PATH DEST.vti
                               a .vti like the file at PATH, its state
                               grown from the stored seed
  vtipack.py report OUT        sizes and seed kinds
  vtipack.py shader OUT PATH DIR
                               the Enki shader for the file at PATH, its
                               start textures (PNGs to import as Exact
                               Pixels) and thumbnail, into DIR
  vtipack.py plan OUT          group the files into packs of related
                               patterns: OUT/packs.json, to edit by hand
  vtipack.py packs OUT DIR     every pack in OUT/packs.json, into DIR
  vtipack.py pack OUT DEST.sqlite --id ID --title TITLE [PATH...]
                               a shader pack of the files at the PATHs (or
                               --match's), left-out ones skipped
                               (see vtishader.py)

A file whose initial pattern is applied when it loads needs no state;
nor does one whose state is all one value per chemical (stored exactly,
tiny). Otherwise build converts the rule with vti2enki, runs it on the
GPU from the saved state (the most typical 512^2 window of a bigger one)
and from each seed, cheapest first, and keeps the first that passes
rdheadless.Trial's test; failing every seed, the state is kept 8-bit,
then 16-bit, if that passes. Anything else is left out of the pack
(its reason noted): a state no seed or copy restarts, one over 1024^2
cells that no seed restarts, a rule whose chemicals run away even from
the saved state (so the test means little), and one vti2enki can't
convert or whose shader doesn't compile.
"""
import argparse
import hashlib
import json
import lzma
import os
import re
import sys
import time
import xml.etree.ElementTree as ET
import zlib

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vtiseed  # noqa: E402
import vtistate  # noqa: E402

CROP = 512
PACK_MOST = 48  # shaders in a pack, at most (plan)
PACK_LEAST = 6  # a smaller group joins its neighbour
LARGE = 1024 * 1024  # cells: a state this big is left out unless a seed restarts it


def scan(src):
	# Every .vti under src, in order, with its header and md5.
	for d, dirs, files in os.walk(src):
		dirs.sort()
		for f in sorted(files):
			if f.endswith(".vti"):
				path = os.path.join(d, f)
				data = open(path, "rb").read()
				i = data.find(b"<ImageData")
				yield os.path.relpath(path, src), data[:i] if i >= 0 else data, hashlib.md5(data).hexdigest(), len(data)


def parse_header(head):
	# Rule, code, parameters, chemicals, grid and initial-pattern setting.
	s, e = head.find(b"<RD"), head.find(b"</RD>")
	if s < 0 or e < 0:
		raise ValueError("no RD element")
	rd = ET.fromstring(head[s:e + 5])
	rule = rd.find("rule")
	body = rule.find("formula")
	if body is None:
		body = rule.find("kernel")
	code = (body.text or "") if body is not None else ""
	ipg = rd.find("initial_pattern_generator")
	return {"type": rule.get("type"), "name": rule.get("name"),
			"chemicals": int(body.get("number_of_chemicals", 0)) if body is not None else None,
			"code": code.strip(),
			"params": {p.get("name").strip(): (p.text or "").strip() for p in rule.findall("param")},
			"apply": ipg is not None and ipg.get("apply_when_loading", "true") == "true"}


def family_key(h):
	code = re.sub(r"\s+", "", h["code"])
	return "%s/%s/%s" % (h["type"], h["chemicals"], hashlib.md5(code.encode()).hexdigest()[:12] if code else h["name"])


def index(src, out):
	os.makedirs(out, exist_ok=True)
	headers, seen, dups, fams, errors = {}, {}, {}, {}, {}
	for rel, head, md5, size in scan(src):
		if md5 in seen:
			dups[rel] = seen[md5]
			continue
		seen[md5] = rel
		headers[md5] = head.decode("utf-8", "replace")
		try:
			h = parse_header(head)
		except (ValueError, ET.ParseError) as e:
			errors[rel] = str(e)
			continue
		f = fams.setdefault(family_key(h), {"type": h["type"], "names": [], "chemicals": h["chemicals"],
				"code": h["code"], "files": []})
		if h["name"] not in f["names"]:
			f["names"].append(h["name"])
		f["files"].append({"path": rel, "md5": md5, "size": size, "params": h["params"], "apply": h["apply"]})
	families = []
	for f in sorted(fams.values(), key=lambda f: (-len(f["files"]), f["files"][0]["path"])):
		names = sorted({k for x in f["files"] for k in x["params"]})
		# Parameters every file sets alike are the family's; the rest vary.
		common = {k: v for k, v in f["files"][0]["params"].items()
				if all(x["params"].get(k) == v for x in f["files"])}
		variants = {}
		for x in f["files"]:
			x["params"] = {k: v for k, v in x["params"].items() if k not in common}
			variants.setdefault(json.dumps(x["params"], sort_keys=True), []).append(x["path"])
		families.append(dict(f, id="f%03d" % len(families), param_names=names, params=common,
				variants=list(variants.values())))
	idx = {"source": os.path.abspath(src), "families": families, "duplicates": dups, "unreadable": errors}
	json.dump(idx, open(os.path.join(out, "index.json"), "w"), indent=1)
	with lzma.open(os.path.join(out, "headers.json.xz"), "wt", preset=9) as fh:
		json.dump(headers, fh)
	print("%d files: %d unique in %d families (%d with one parameter set or more shared), "
			"%d duplicates, %d unreadable" % (len(seen) + len(dups), len(seen), len(families),
			sum(1 for f in families if len(f["variants"]) < len(f["files"])), len(dups), len(errors)))


def _files(idx):
	for f in idx["families"]:
		for x in f["files"]:
			yield f, x


def _exclude(out, md5, info, why):
	# Left out of the pack: nothing stored but the reason.
	info.update(kind="excluded", bytes=0, why=why)
	json.dump(info, open(os.path.join(out, "states", md5 + ".json"), "w"))
	return info


def _store(out, md5, kind, rec, info):
	data = vtiseed.encode(kind, rec)
	with open(os.path.join(out, "states", md5 + ".seed"), "wb") as fh:
		fh.write(data)
	info.update(kind=kind, bytes=len(data))
	json.dump(info, open(os.path.join(out, "states", md5 + ".json"), "w"))
	return info


def build_one(src, out, x, steps):
	import rdheadless
	import vti2enki
	t = time.time()
	info = {"path": x["path"]}
	try:
		head, w, h, arrays = vtistate.read(os.path.join(src, x["path"]))
	except (ValueError, KeyError, AttributeError, zlib.error) as e:
		return _exclude(out, x["md5"], info, "its state can't be read (%s)" % e)
	# The chemicals (a file can hold other arrays too).
	names = sorted(k for k, a in arrays.items() if a is not None and re.fullmatch("[a-h]", k))
	names = names or sorted(k for k, a in arrays.items() if a is not None)
	info["grid"] = [w, h]
	if not names:
		info["why"] = "it has no saved state"
		return _store(out, x["md5"], "none", {"names": [], "means": np.zeros(0)}, info)
	full = np.nan_to_num(np.stack([arrays[k] for k in names]).astype("f4"))
	if x["apply"]:
		info["why"] = "its initial pattern is applied when it loads"
		return _store(out, x["md5"], "none", {"names": names, "means": np.zeros(len(names))}, info)
	if all(a.min() == a.max() for a in full):
		info["why"] = "every chemical is one value"
		return _store(out, x["md5"], "exact", next(c for k, c in vtiseed.copies(names, full) if k == "exact"), info)
	try:
		glsl, _ = vti2enki.convert(os.path.join(src, x["path"]))
	except (vti2enki.Unsupported, ET.ParseError, KeyError, ValueError, AttributeError) as e:
		return _exclude(out, x["md5"], info, "vti2enki can't convert it (%s)" % e)
	crop = rdheadless.best_window(full, CROP)
	try:
		trial = rdheadless.Trial(glsl, crop, steps)
	except RuntimeError as e:
		return _exclude(out, x["md5"], info, "its shader doesn't compile (%s)" % str(e).strip().splitlines()[0][:200])
	info["trial"] = {"grid": [crop.shape[2], crop.shape[1]], "steps": int(trial.fb[-1]), "tried": {}}
	tried = info["trial"]["tried"]
	if trial.unbounded():
		return _exclude(out, x["md5"], info, "a chemical runs away even from the saved state")
	full_means = full.reshape(len(names), -1).astype("f8").mean(1)
	ch, cw = crop.shape[1:]

	def attempt(kind, rec):
		s = trial.score(vtiseed.grow(kind, rec, cw, ch))
		tried[kind] = None if s is None else [round(s[0], 4), round(s[1], 2), s[2]]
		return s is not None and s[0] <= rdheadless.TOL

	for kind, rec in vtiseed.seeds(names, crop):
		if attempt(kind, rec):
			info["why"] = "the cheapest seed that passes"
			info["secs"] = round(time.time() - t, 1)
			return _store(out, x["md5"], kind, dict(rec, means=full_means), info)
	info["secs"] = round(time.time() - t, 1)
	if w * h > LARGE:
		return _exclude(out, x["md5"], info, "no seed passes, and the state is too big to keep (%dx%d)" % (w, h))
	crop_copies = dict(vtiseed.copies(names, crop))
	full_copies = dict(vtiseed.copies(names, full))
	for kind in ("q8", "q16"):
		if attempt(kind, crop_copies[kind]):
			info["why"] = "no seed passes; the %d-bit state does" % (8 if kind == "q8" else 16)
			info["secs"] = round(time.time() - t, 1)
			return _store(out, x["md5"], kind, full_copies[kind], info)
	info["secs"] = round(time.time() - t, 1)
	return _exclude(out, x["md5"], info, "neither a seed nor a quantised copy passes")


def _claim(path):
	# Takes a file for this process, so several builds can share one list;
	# a claim left by a process that's gone is taken over.
	try:
		fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
	except FileExistsError:
		try:
			pid = open(path).read()
			if pid:
				os.kill(int(pid), 0)
			return False  # held (or being written)
		except ProcessLookupError:
			os.remove(path)
			return _claim(path)
	os.write(fd, str(os.getpid()).encode())
	os.close(fd)
	return True


def build(src, out, part, steps, match=None):
	idx = json.load(open(os.path.join(out, "index.json")))
	os.makedirs(os.path.join(out, "states"), exist_ok=True)
	i, n = part
	todo = [x for j, (_, x) in enumerate(_files(idx)) if j % n == i
			and (not match or re.search(match, x["path"])) and not os.path.exists(os.path.join(out, "states", x["md5"] + ".json"))]
	for k, x in enumerate(todo):
		claim = os.path.join(out, "states", x["md5"] + ".claim")
		if os.path.exists(os.path.join(out, "states", x["md5"] + ".json")) or not _claim(claim):
			continue  # done, or another build is on it
		try:
			info = build_one(src, out, x, steps)
			print("%d/%d %s: %s (%d bytes) - %s" % (k + 1, len(todo), x["path"], info["kind"], info["bytes"],
					info["why"]), flush=True)
		except Exception as e:  # noqa: BLE001 - one bad file mustn't stop the rest
			# Maybe passing (the machine short of memory, say): left for the
			# next build, which tries it again.
			print("%d/%d %s: failed - %s: %s" % (k + 1, len(todo), x["path"], type(e).__name__, e), flush=True)
		finally:
			os.remove(claim)


def expand(out, path, dest):
	idx = json.load(open(os.path.join(out, "index.json")))
	path = idx["duplicates"].get(path, path)
	x = next((x for _, x in _files(idx) if x["path"] == path), None)
	if x is None:
		sys.exit("%s isn't in the index" % path)
	info = json.load(open(os.path.join(out, "states", x["md5"] + ".json")))
	if info["kind"] == "excluded":
		sys.exit("%s was left out: %s" % (path, info["why"]))
	with lzma.open(os.path.join(out, "headers.json.xz"), "rt") as fh:
		head = json.load(fh)[x["md5"]].encode()
	kind, rec = vtiseed.decode(open(os.path.join(out, "states", x["md5"] + ".seed"), "rb").read())
	w, h = info["grid"]
	if kind == "none":
		state = np.zeros((len(rec["names"]), h, w), "f4")
	else:
		state = vtiseed.grow(kind, rec, w, h)
	vtistate.write(dest, head, dict(zip(rec["names"], state)), (h, w))
	print("%s -> %s (%s)" % (path, dest, kind))


def report(out):
	idx = json.load(open(os.path.join(out, "index.json")))
	kinds, old, new, done = {}, 0, 0, 0
	for _, x in _files(idx):
		p = os.path.join(out, "states", x["md5"] + ".json")
		if not os.path.exists(p):
			continue
		info = json.load(open(p))
		done += 1
		k = kinds.setdefault(info["kind"], [0, 0, 0])
		k[0] += 1
		k[1] += x["size"]
		k[2] += info["bytes"]
		old += x["size"]
		new += info["bytes"]
	total = sum(1 for _ in _files(idx))
	print("%d of %d unique files built" % (done, total))
	for kind, (n, o, b) in sorted(kinds.items(), key=lambda kv: -kv[1][0]):
		print("  %-15s %5d files  %8.1f MB -> %7.2f MB" % (kind, n, o / 1e6, b / 1e6))
	hdr = os.path.getsize(os.path.join(out, "headers.json.xz"))
	n, o, _ = kinds.get("excluded", [0, 0, 0])
	print("kept %d files: %.2f GB -> %.1f MB, plus %.1f MB of headers; left out %d (%.2f GB)" % (
			done - n, (old - o) / 1e9, new / 1e6, hdr / 1e6, n, o / 1e9))


def shader(out, path, dest):
	import vtishader
	c = vtishader.Collection(out)
	os.makedirs(dest, exist_ok=True)
	name = os.path.splitext(os.path.basename(path))[0]
	glsl, textures, thumb = c.shader(path)
	with open(os.path.join(dest, name + ".glsl"), "w") as fh:
		fh.write(glsl)
	for t, png in textures.items():
		with open(os.path.join(dest, t + ".png"), "wb") as fh:
			fh.write(png)
	if thumb:
		with open(os.path.join(dest, name + "_thumb.jpg"), "wb") as fh:
			fh.write(thumb)
	print("%s -> %s (%s)" % (path, os.path.join(dest, name + ".glsl"), ", ".join(textures) or "no textures"))


def _stem_tokens(path):
	# A file's name in words: split at _ (and the folder's own prefix kept whole).
	return os.path.splitext(os.path.basename(path))[0].split("_")


def _split(paths, most, depth=1):
	# Paths sharing their first `depth` name words, splitting further any
	# group still too big; [(words, paths)].
	groups = {}
	for p in paths:
		groups.setdefault(tuple(_stem_tokens(p)[:depth]), []).append(p)
	if len(groups) == 1 and all(len(_stem_tokens(p)) <= depth for p in paths):
		return [(next(iter(groups)), paths)]  # can't split further
	out = []
	for words, ps in sorted(groups.items()):
		if len(ps) > most:
			out += _split(ps, most, depth + 1)
		else:
			out.append((words, ps))
	return out


def _slug(text):
	return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def _title(words):
	# "grayscott-withWave", "Gray-Scott", "dotdotdot" -> "Grayscott With Wave Dotdotdot":
	# words split at case changes and punctuation, each kept once (one
	# already spelt out by those before it is dropped).
	out, seen = [], ""
	for w in re.split(r"[-_ ./]+", re.sub(r"([a-z])([A-Z])", r"\1 \2", " ".join(words))):
		key = re.sub(r"[^a-z0-9]", "", w.lower())
		if key and key not in seen:
			out.append(w[:1].upper() + w[1:])
			seen += key + " "
	return " ".join(out)


ROMAN = ["", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"]


def plan(out, most):
	"""Groups the collection into packs of related patterns, at most `most`
	each: by folder, then by rule (where a folder, or the top level, holds
	several), then by the words their names start with. Groups too small to
	stand alone are pooled into the folder's Miscellany. Written to
	OUT/packs.json as [{id, title, description, rule, paths}], for editing
	before packs runs."""
	idx = json.load(open(os.path.join(out, "index.json")))
	rule = {x["path"]: (f["names"] or ["?"])[0] for f, x in _files(idx)}
	folders = {}
	for p in rule:
		folders.setdefault(p.split("/")[0] if "/" in p else "", []).append(p)
	plans, titles = [], {}

	def add(title, rule_name, paths):
		for k in range(0, len(paths), most):
			n = titles.get(title, 0)
			titles[title] = n + 1
			t = title + (ROMAN[n] if n < len(ROMAN) else " %d" % (n + 1))
			plans.append({"id": _slug(t), "title": t, "description": "", "rule": rule_name,
					"paths": paths[k:k + most]})

	for folder, paths in sorted(folders.items(), key=lambda kv: (kv[0] == "", kv[0].lower())):
		by_rule = {}
		for p in sorted(paths):
			by_rule.setdefault(rule[p], []).append(p)
		if folder and len(paths) <= most:
			by_rule = {"": sorted(paths)}
		pooled = []
		for name, ps in sorted(by_rule.items()):
			if len(ps) < PACK_LEAST and len(by_rule) > 1:
				pooled += ps
				continue
			base = [w for w in (folder, name) if w]
			groups = _split(ps, most) if len(ps) > most else [((), ps)]
			merged = []
			for words, g in groups:
				# Small groups join their neighbour, so packs aren't scraps.
				if merged and (len(merged[-1][1]) < PACK_LEAST or len(g) < PACK_LEAST) and \
						len(merged[-1][1]) + len(g) <= most:
					merged[-1] = (merged[-1][0] + [words], merged[-1][1] + g)
				else:
					merged.append(([words], g))
			for wordss, g in merged:
				words = wordss[0] if len(wordss) == 1 else os.path.commonprefix([list(w) for w in wordss])
				add(_title(base + list(words)), name or rule[g[0]], g)
		if pooled:
			add(_title([folder or "Ready", "Miscellany"]), "assorted", pooled)
	json.dump(plans, open(os.path.join(out, "packs.json"), "w"), indent=1)
	for x in plans:
		print("%4d  %s" % (len(x["paths"]), x["title"]))
	print("%d packs of %d files -> %s" % (len(plans), sum(len(x["paths"]) for x in plans),
			os.path.join(out, "packs.json")))


def packs(out, dest, only, src):
	plans = json.load(open(os.path.join(out, "packs.json")))
	os.makedirs(dest, exist_ok=True)
	for x in plans:
		if only and not re.search(only, x["id"]):
			continue
		ns = argparse.Namespace(out=out, src=src, dest=os.path.join(dest, x["id"] + "-v%d.sqlite" % x.get("version", 1)),
				paths=x["paths"], match=None, id=x["id"], title=x["title"],
				description=x.get("description") or _description(x), author=x.get("author", "Dan Wills"),
				version=x.get("version", 1), quiet=True)
		pack(ns)


def _description(x):
	return ("%d %s patterns, from Dan's Ready (GollyGang/ready) collection. Each restarts the way its saved "
			"state did - from that state or a seed grown to match it. Paint with a finger, erase with two; "
			"three fingers show every chemical." % (len(x["paths"]), x["rule"]))


def pack(args):
	import vtishader
	c = vtishader.Collection(args.out, args.src)
	paths = list(args.paths)
	if args.match:
		paths += [p for p in sorted(c.files) if re.search(args.match, p) and p not in paths]
	quiet = getattr(args, "quiet", False)
	shaders, names = [], set()
	for path in paths:
		path = c.idx["duplicates"].get(path, path)
		try:
			glsl, textures, thumb = c.shader(path)
		except (KeyError, ValueError, vtishader.vti2enki.Unsupported) as e:
			if not quiet:
				print("%s: skipped - %s" % (path, e))
			continue
		name = os.path.splitext(os.path.basename(path))[0]
		while name in names:
			name += "_"
		names.add(name)
		shaders.append((name, glsl, textures, thumb))
		if not quiet:
			print("%s: %s" % (path, c.info(path)["kind"]))
	if not shaders:
		print("%s: nothing to pack (of %d)" % (args.dest, len(paths)))
		return
	vtishader.write_pack(args.dest, {"id": args.id, "title": args.title, "description": args.description,
			"author": args.author, "version": args.version}, shaders)
	print("wrote %s: %d of %d shaders, %.1f MB" % (args.dest, len(shaders), len(paths),
			os.path.getsize(args.dest) / 1e6))


def main():
	ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
	sub = ap.add_subparsers(dest="cmd", required=True)
	a = sub.add_parser("index")
	a.add_argument("src")
	a.add_argument("out")
	a = sub.add_parser("build")
	a.add_argument("src")
	a.add_argument("out")
	a.add_argument("--part", default="0/1", help="i/n: this process's share (default 0/1, all)")
	a.add_argument("--steps", type=int, default=20000, help="steps each trial runs (default 20000)")
	a.add_argument("--match", help="only files whose path matches this regular expression")
	a = sub.add_parser("expand")
	a.add_argument("out")
	a.add_argument("path", help="the file's path in the source folder")
	a.add_argument("dest")
	a = sub.add_parser("report")
	a.add_argument("out")
	a = sub.add_parser("plan")
	a.add_argument("out")
	a.add_argument("--most", type=int, default=PACK_MOST, help="shaders a pack holds at most (default %d)" % PACK_MOST)
	a = sub.add_parser("packs")
	a.add_argument("out")
	a.add_argument("dir")
	a.add_argument("--only", help="only packs whose id matches this regular expression")
	a.add_argument("--src", help="the source folder, for thumbnails (default: the index's)")
	a = sub.add_parser("shader")
	a.add_argument("out")
	a.add_argument("path", help="the file's path in the source folder")
	a.add_argument("dir")
	a = sub.add_parser("pack")
	a.add_argument("out")
	a.add_argument("dest")
	a.add_argument("paths", nargs="*", help="files' paths in the source folder")
	a.add_argument("--match", help="also every file whose path matches this regular expression")
	a.add_argument("--id", required=True, help="the pack's id, e.g. grayscott-history-wave")
	a.add_argument("--title", required=True)
	a.add_argument("--description", default="")
	a.add_argument("--author", default="Dan Wills")
	a.add_argument("--version", type=int, default=1)
	a.add_argument("--src", help="the source folder, for thumbnails (default: the index's)")
	args = ap.parse_args()
	if args.cmd == "index":
		index(args.src, args.out)
	elif args.cmd == "build":
		build(args.src, args.out, tuple(int(v) for v in args.part.split("/")), args.steps, args.match)
	elif args.cmd == "expand":
		expand(args.out, args.path, args.dest)
	elif args.cmd == "shader":
		shader(args.out, args.path, args.dir)
	elif args.cmd == "pack":
		pack(args)
	elif args.cmd == "plan":
		plan(args.out, args.most)
	elif args.cmd == "packs":
		packs(args.out, args.dir, args.only, args.src)
	else:
		report(args.out)


if __name__ == "__main__":
	main()
