"""Small grids of settings around one kind of pattern (hatch, curl, ring),
run headless with search.py's Sim: probe.py NAME writes probe-NAME.png
(numbered tiles), probe-NAME.npz and the settings to stdout."""
import sys, itertools
import numpy as np
from PIL import Image, ImageDraw
import search
base = search.defaults()
grids = {
	"hatch": dict(SENSOR_ANGLE=[0.03, 0.08], SENSOR_DISTANCE=[0.01, 0.03], TURN=[0.02, 0.08], DIFFUSE=[0.02, 0.1], FADE=[0.005], BLUR=[1.0], SPEED=[1.0]),
	"curl": dict(SENSOR_ANGLE=[0.8, 1.5], SENSOR_DISTANCE=[0.002, 0.004], TURN=[0.6, 1.2], FADE=[0.01, 0.05], DIFFUSE=[0.03], BLUR=[1.0], SPEED=[0.5, 1.0]),
	"ring": dict(SENSOR_ANGLE=[1.0, 2.0], SENSOR_DISTANCE=[0.01, 0.025], TURN=[0.3, 0.8], FADE=[0.01], DIFFUSE=[0.3, 1.0], BLUR=[3.0, 5.0], SPEED=[1.0]),
}
name = sys.argv[1]
g = grids[name]
keys = list(g)
cands = [dict(base, **dict(zip(keys, vals))) for vals in itertools.product(*[g[k] for k in keys])]
sim = search.Sim()
T = 180
cols = 8
m = Image.new("L", (cols * (T + 4), ((len(cands) + cols - 1) // cols) * (T + 16)), 0)
d = ImageDraw.Draw(m)
trails = []
for k, c in enumerate(cands):
	tr = search.small(sim.run(c))
	trails.append(tr)
	gg = search.normalise(tr[100:100 + T, :T])
	x, y = (k % cols) * (T + 4), (k // cols) * (T + 16)
	m.paste(Image.fromarray((gg * 255).astype("u1")), (x, y + 12))
	d.text((x + 2, y), str(k), fill=255)
m.save("probe-%s.png" % name)
search.save("probe-%s.npz" % name, cands, trails)
for k, c in enumerate(cands):
	print(k, " ".join("%s=%.3g" % (n, c[n]) for n in keys))
