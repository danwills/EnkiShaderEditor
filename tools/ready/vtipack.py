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
	full = np.nan_to_num(np.stack([arrays[k] for k in names]).astype("f4"))
	info["grid"] = [w, h]
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
	vtistate.write(dest, head, dict(zip(rec["names"], state)))
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
	args = ap.parse_args()
	if args.cmd == "index":
		index(args.src, args.out)
	elif args.cmd == "build":
		build(args.src, args.out, tuple(int(v) for v in args.part.split("/")), args.steps, args.match)
	elif args.cmd == "expand":
		expand(args.out, args.path, args.dest)
	else:
		report(args.out)


if __name__ == "__main__":
	main()
