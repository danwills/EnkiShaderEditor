"""Reads and writes the saved state in Ready's .vti files.

A .vti is VTK XML image data: a header (the <RD> element, with the rule,
initial pattern and render settings) and then one array per chemical,
compressed by vtkZLibDataCompressor. Arrays are stored inline ("binary":
the block header and the data base64-encoded separately) or appended
after the XML (raw or base64). Only 2D grids are handled.
"""
import base64
import re
import zlib

import numpy as np

BLOCK = 32768


def _blocks(raw, off):
	# A block header (UInt32: count, block size, last block's size, then each
	# block's compressed size) and the blocks, at raw[off:].
	n = int(np.frombuffer(raw, "<u4", 1, off)[0])
	hdr = np.frombuffer(raw, "<u4", 3 + n, off)
	p = off + 4 * (3 + n)
	out = []
	for c in hdr[3:]:
		out.append(zlib.decompress(raw[p:p + c]))
		p += int(c)
	return b"".join(out)


def _b64blocks(txt):
	# The same, with the header and the data base64-encoded separately.
	txt = txt.lstrip()
	n = int(np.frombuffer(base64.b64decode(txt[:8]), "<u4", 1)[0])
	hlen = ((4 * (3 + n) + 2) // 3) * 4
	hdr = np.frombuffer(base64.b64decode(txt[:hlen]), "<u4", 3 + n)
	clen = int(hdr[3:].sum())
	body = base64.b64decode(txt[hlen:hlen + ((clen + 2) // 3) * 4])
	out = []
	p = 0
	for c in hdr[3:]:
		out.append(zlib.decompress(body[p:p + c]))
		p += int(c)
	return b"".join(out)


def header(data):
	# Everything before the image data: the XML declaration, <VTKFile> and <RD>.
	return data[:data.index(b"<ImageData")]


def read(path):
	"""Returns (header bytes, width, height, {chemical: (h, w) float32 array}).
	A chemical whose array is missing or short maps to None."""
	data = open(path, "rb").read()
	ext = [int(v) for v in re.search(rb'WholeExtent="([^"]*)"', data).group(1).split()]
	w, h = ext[1] - ext[0] + 1, ext[3] - ext[2] + 1
	if ext[5] > ext[4]:
		raise ValueError("3D (%d deep)" % (ext[5] - ext[4] + 1))
	arrays = {}
	for m in re.finditer(rb"<DataArray ([^>]*)>", data):
		at = dict(re.findall(rb'(\w+)="([^"]*)"', m.group(1)))
		name = at[b"Name"].decode()
		if at.get(b"format") == b"binary":
			end = data.index(b"</DataArray>", m.end())
			out = _b64blocks(data[m.end():end].strip())
		else:
			a0 = data.index(b"<AppendedData")
			a1 = data.index(b"_", a0) + 1
			enc = re.search(rb'encoding="([^"]*)"', data[a0:a0 + 200]).group(1)
			off = a1 + int(at[b"offset"])
			out = _blocks(data, off) if enc == b"raw" else _b64blocks(data[off:])
		arrays[name] = (np.frombuffer(out, "<f4", w * h).reshape(h, w).copy()
				if len(out) >= 4 * w * h else None)
	return header(data), w, h, arrays


def _encode(a):
	raw = np.ascontiguousarray(a, "<f4").tobytes()
	chunks = [raw[i:i + BLOCK] for i in range(0, len(raw), BLOCK)]
	comp = [zlib.compress(c, 6) for c in chunks]
	last = len(chunks[-1]) if len(chunks[-1]) < BLOCK else 0
	hdr = np.array([len(chunks), BLOCK, last] + [len(c) for c in comp], "<u4").tobytes()
	return (base64.b64encode(hdr) + base64.b64encode(b"".join(comp))).decode()


def write(path, head, arrays, size=None):
	"""Writes a .vti: `head` (from header()) and the chemicals in `arrays`
	({name: (h, w) array}, in order), inline as Ready writes them. `size`
	(h, w) is needed only when there are none."""
	h, w = size or next(iter(arrays.values())).shape
	ext = "0 %d 0 %d 0 0" % (w - 1, h - 1)
	out = ["",
			'  <ImageData WholeExtent="%s" Origin="0 0 0" Spacing="1 1 1">' % ext,
			'  <Piece Extent="%s">' % ext,
			"    <PointData>"]
	for name, a in arrays.items():
		out.append('      <DataArray type="Float32" Name="%s" format="binary" RangeMin="%.11g" RangeMax="%.11g">'
				% (name, float(a.min()), float(a.max())))
		out.append("        " + _encode(a))
		out.append("      </DataArray>")
	out += ["    </PointData>", "    <CellData>", "    </CellData>", "  </Piece>", "  </ImageData>",
			"</VTKFile>", ""]
	with open(path, "wb") as f:
		f.write(head.rstrip() + "\n".join(out).encode())
