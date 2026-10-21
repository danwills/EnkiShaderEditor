#!/usr/bin/env python3
"""Writes the app's built-in demo tracker module: a little 4-channel
ProTracker MOD, composed and synthesised here (so it's the app's own, with
no licence of its own to carry) - bass, drums, hats and a square lead in A
minor pentatonic. About a minute, then it loops.

Usage: make_demo_mod.py app/src/main/res/raw/enki_demo_mod.mod
"""
import math
import random
import struct
import sys

# ProTracker periods, C-1 to B-3.
PERIODS = [
	856, 808, 762, 720, 678, 640, 604, 570, 538, 508, 480, 453,
	428, 404, 381, 360, 339, 320, 302, 285, 269, 254, 240, 226,
	214, 202, 190, 180, 170, 160, 151, 143, 135, 127, 120, 113,
]
NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]


def period(note):
	"""note like 'A-2' or 'C#3' -> period."""
	name, octave = note[:-1].rstrip("-"), int(note[-1])
	return PERIODS[(octave - 1) * 12 + NAMES.index(name)]


def signed8(values):
	return bytes((max(-128, min(127, int(round(v * 127)))) & 255) for v in values)


def samples():
	random.seed(7)
	rate = 8363.0  # C-2's playback rate
	out = []
	# 1: bass - a bright saw, one 64-sample cycle, looped.
	saw = [1.0 - 2.0 * (i / 64.0) for i in range(64)]
	saw = [0.7 * v + 0.3 * math.sin(2 * math.pi * i / 64.0) for i, v in enumerate(saw)]
	out.append(("bass", signed8(saw), 0, 64, 64))
	# 2: kick - a falling sine.
	kick = []
	phase = 0.0
	for i in range(2600):
		t = i / rate
		f = 45.0 + 140.0 * math.exp(-28.0 * t)
		phase += 2 * math.pi * f / rate
		kick.append(math.sin(phase) * math.exp(-7.0 * t))
	out.append(("kick", signed8(kick), 0, 0, 64))
	# 3: snare - noise over a dropping tone.
	snare = []
	phase = 0.0
	for i in range(2800):
		t = i / rate
		phase += 2 * math.pi * (190.0 + 60.0 * math.exp(-30 * t)) / rate
		n = random.uniform(-1, 1)
		snare.append((0.55 * n * math.exp(-12 * t) + 0.5 * math.sin(phase) * math.exp(-20 * t)))
	out.append(("snare", signed8(snare), 0, 0, 56))
	# 4: hat - a short burst of high-passed noise.
	hat = []
	last = 0.0
	for i in range(700):
		t = i / rate
		n = random.uniform(-1, 1)
		hat.append((n - last) * 0.6 * math.exp(-40 * t))
		last = n
	out.append(("hat", signed8(hat), 0, 0, 40))
	# 5: lead - a square with its corners rounded, one 32-sample cycle.
	square = [math.tanh(4 * math.sin(2 * math.pi * i / 32.0)) * 0.6 for i in range(32)]
	out.append(("lead", signed8(square), 0, 32, 48))
	return out


EMPTY = (0, 0, 0, 0)


def cell(sample, note=None, effect=0, param=0):
	return (sample, period(note) if note else 0, effect, param)


def pattern(lead_notes, bass_notes, fill=False):
	rows = [[EMPTY] * 4 for _ in range(64)]
	for r in range(64):
		beat = r % 16
		# Bass: a note every 8 rows, sliding quietly down in between.
		if r % 8 == 0:
			rows[r][0] = cell(1, bass_notes[(r // 8) % len(bass_notes)])
		else:
			rows[r][0] = (0, 0, 0xA, 0x01)
		# Drums.
		if beat in (0, 6, 10):
			rows[r][1] = cell(2, "C-2")
		elif beat in (4, 12):
			rows[r][1] = cell(3, "C-2")
		elif fill and r >= 56 and r % 2 == 0:
			rows[r][1] = cell(3, "C-2", 0xC, 0x20 + (r - 56) * 4)
		# Hats on the off-beats.
		if r % 2 == 1:
			rows[r][2] = cell(4, "C-3", 0xC, 0x28 if r % 4 == 3 else 0x18)
		# Lead: a note every 4 rows when there's one, dying away.
		note = lead_notes[(r // 4) % len(lead_notes)]
		if r % 4 == 0 and note:
			rows[r][3] = cell(5, note)
		else:
			rows[r][3] = (0, 0, 0xA, 0x02)
	return rows


def main(path):
	pats = [
		pattern(["A-2", None, "C-3", "D-3", "E-3", None, "D-3", "C-3",
				"A-2", None, "G-2", "A-2", "C-3", "D-3", "E-3", None],
				["A-1", "A-1", "C-2", "C-2", "G-1", "G-1", "E-1", "G-1"]),
		pattern(["E-3", "G-3", "A-3", None, "G-3", "E-3", "D-3", None,
				"C-3", "D-3", "E-3", "G-3", "E-3", None, "D-3", "C-3"],
				["A-1", "A-1", "C-2", "C-2", "D-2", "D-2", "E-2", "G-1"]),
		pattern([None] * 16, ["A-1", "A-1", "G-1", "G-1", "C-2", "C-2", "E-1", "E-1"],
				fill=True),
	]
	order = [2, 0, 1, 0, 1, 0, 1, 2]
	data = bytearray(b"Enki demo".ljust(20, b"\0"))
	smps = samples()
	for i in range(31):
		if i < len(smps):
			name, wave, loop_start, loop_len, volume = smps[i]
			length = len(wave) // 2
			data += name.encode().ljust(22, b"\0")
			data += struct.pack(">HBBHH", length, 0, volume,
					loop_start // 2, max(1, loop_len // 2))
		else:
			data += b"\0" * 22 + struct.pack(">HBBHH", 0, 0, 0, 0, 1)
	data += bytes([len(order), 127])
	data += bytes(order + [0] * (128 - len(order)))
	data += b"M.K."
	for p in pats:
		for row in p:
			for sample, per, effect, param in row:
				data += bytes([(sample & 0xF0) | (per >> 8), per & 0xFF,
						((sample & 0x0F) << 4) | effect, param])
	for _, wave, _, _, _ in smps:
		data += wave
	with open(path, "wb") as f:
		f.write(data)


if __name__ == "__main__":
	main(sys.argv[1])
