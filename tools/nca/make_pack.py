#!/usr/bin/env python3
# Makes an Enki shader pack holding a shader for every Growing Neural
# Cellular Automata model in a directory (see fetch_models.py), each with
# its own weights texture (see gen.py). Every model is first run for a
# while on the CPU - the same update the shaders make - to check it grows
# and to draw its thumbnail. See README.md.
import argparse
import base64
import datetime
import io
import json
import os
import sqlite3
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))

EXPERIMENT_NAMES = {'ex1': 'Growing', 'ex2': 'Persistent', 'ex3': 'Regenerating'}
CREATURE_TITLES = {
	'lizard': 'Lizard', 'butterfly': 'Butterfly', 'ladybird': 'Ladybird', 'fish': 'Fish',
	'eye': 'Eye', 'pretzel': 'Pretzel', 'tree': 'Christmas Tree', 'web': 'Spider Web',
	'smiley': 'Smiley', 'explosion': 'Explosion', 'planarian': 'Planarian',
}
# Experiment 1's models were only trained to grow, and can fall apart
# soon after - so their thumbnails are taken earlier.
THUMBNAIL_STEPS = {'ex1': 100, 'ex2': 300, 'ex3': 300}
GRID = 72
THUMBNAIL_SIZE = 144

# The app's tables as of database version 13, as a pack file holds them.
SCHEMA = '''
CREATE TABLE shaders (_id INTEGER PRIMARY KEY AUTOINCREMENT,shader TEXT NOT NULL,thumb BLOB,
	name TEXT,created DATETIME,modified DATETIME,quality REAL, source_modified DATETIME,
	compiled_binary BLOB, compiled_binary_format INTEGER, compiled_binary_source_hash TEXT,
	compiled_binary_driver_sig TEXT, compile_time_ms INTEGER, compile_time_source_length INTEGER,
	compile_time_source_modified DATETIME, last_fps INTEGER, last_fps_quality REAL, pack_id TEXT);
CREATE TABLE shader_buffers (_id INTEGER PRIMARY KEY AUTOINCREMENT,shader_id INTEGER NOT NULL,
	buffer INTEGER NOT NULL,source TEXT NOT NULL,compiled_binary BLOB,compiled_binary_format INTEGER,
	compiled_binary_source_hash TEXT,compiled_binary_driver_sig TEXT,UNIQUE(shader_id,buffer));
CREATE TABLE textures (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,
	width INTEGER,height INTEGER,ratio REAL,thumb BLOB,matrix BLOB);
CREATE TABLE pack_info (pack_id TEXT NOT NULL,title TEXT NOT NULL,description TEXT,author TEXT,
	version INTEGER,created DATETIME,format INTEGER);
CREATE TABLE pack_media (_id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,
	shader_created TEXT,image BLOB,caption TEXT);
PRAGMA user_version = 13;
'''

DESCRIPTION = (
	'Creatures that grow from a single cell, from "Growing Neural Cellular Automata" by '
	'Mordvintsev, Randazzo, Niklasson and Levin (Distill, 2020). Each cell runs a tiny neural '
	'network over its neighbours, using the article\'s own trained weights. Growing models were '
	'trained only to grow, persistent ones to grow and keep their shape, regenerating ones to '
	'also regrow what you cut away. Tap to plant a seed, drag to cut, three fingers to adjust.')


def load_layers(path):
	def decode(layer):
		data = np.frombuffer(base64.b64decode(layer['data_b64']), np.uint8)
		data = data.reshape(layer['in_ch'] + 1, layer['out_ch']) / 255.0 - 0.5
		return data[:-1] * layer['weight_scale'], data[-1] * layer['bias_scale']
	layers = json.load(open(path))
	return decode(layers[0]) + decode(layers[1])


# The shaders' update, on the CPU: Sobel perception, two dense layers,
# a random half of the cells firing, and the alive mask.
def simulate(path, steps, seed=0):
	w1, b1, w2, b2 = load_layers(path)
	state = np.zeros((GRID, GRID, 16))
	state[GRID // 2, GRID // 2, 3:] = 1.0
	rng = np.random.default_rng(seed)

	def shifted(a, dy, dx):
		return np.roll(np.roll(a, -dy, 0), -dx, 1)

	def max_pool(a):
		return np.max([shifted(a, y, x) for y in (-1, 0, 1) for x in (-1, 0, 1)], 0)

	for _ in range(steps):
		gx = np.zeros_like(state)
		gy = np.zeros_like(state)
		for y in (-1, 0, 1):
			for x in (-1, 0, 1):
				n = shifted(state, y, x)
				gx += x * (2 if y == 0 else 1) / 8 * n
				gy += y * (2 if x == 0 else 1) / 8 * n
		hidden = np.maximum(np.concatenate([state, gx, gy], -1) @ w1 + b1, 0)
		update = (hidden @ w2 + b2) * (rng.random((GRID, GRID, 1)) <= 0.5)
		alive = np.minimum(max_pool(state[..., 3]), max_pool(state[..., 3] + update[..., 3]))
		state = (state + update) * (alive >= 0.1)[..., None]
	return state


def thumbnail(state):
	rgba = np.clip(state[..., :4], 0, 1)
	rgb = np.clip(1 - rgba[..., 3:] + rgba[..., :3], 0, 1)
	image = Image.fromarray((rgb * 255).astype(np.uint8))
	image = image.resize((THUMBNAIL_SIZE, THUMBNAIL_SIZE), Image.BICUBIC)
	out = io.BytesIO()
	image.save(out, 'PNG')
	return out.getvalue()


def png_bytes(path):
	with open(path, 'rb') as f:
		return f.read()


def split_passes(text):
	# The Image pass, then "//// Buffer A" ... - as gen.py writes them.
	parts = text.split('//// Buffer ')
	image = parts[0]
	buffers = {}
	for part in parts[1:]:
		letter, source = part[0], part[2:]
		buffers[ord(letter) - ord('A')] = source
	return image, buffers


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('models', help='a directory of models, from fetch_models.py')
parser.add_argument('pack', help='where to write the pack file')
parser.add_argument('--pack-id', default='neural-ca')
parser.add_argument('--title', default='Neural CA')
parser.add_argument('--author', default='danwills')
parser.add_argument('--version', type=int, default=2)
parser.add_argument('--preview', help='also write the Get Shader Pack preview JSON here')
args = parser.parse_args()

models = []
for experiment in ['ex1', 'ex2', 'ex3']:
	for creature in CREATURE_TITLES:
		path = os.path.join(args.models, f'{experiment}_{creature}.json')
		if os.path.exists(path):
			models.append((experiment, creature, path))
if not models:
	sys.exit('no models in ' + args.models)

if os.path.exists(args.pack):
	os.remove(args.pack)
db = sqlite3.connect(args.pack)
db.executescript(SCHEMA)
now = datetime.datetime.now().replace(microsecond=0)
db.execute('INSERT INTO pack_info VALUES (?, ?, ?, ?, ?, ?, 1)',
		(args.pack_id, args.title, DESCRIPTION, args.author, args.version,
		now.strftime('%Y-%m-%d %H:%M:%S')))

names = []
with tempfile.TemporaryDirectory() as work:
	for index, (experiment, creature, path) in enumerate(models):
		title = f'{EXPERIMENT_NAMES[experiment]} {CREATURE_TITLES[creature]}'
		texture = f'nca_{experiment}_{creature}'
		shader_path = os.path.join(work, texture + '.glsl')
		weights_path = os.path.join(work, texture + '.png')
		subprocess.run([sys.executable, '-I', os.path.join(HERE, 'gen.py'), path, shader_path,
				weights_path, '--texture', texture, '--title',
				f'{title.upper()} - a neural cellular automaton',
				'--creature', CREATURE_TITLES[creature].lower(), '--experiment', experiment],
				check=True, stdout=subprocess.DEVNULL)

		state = simulate(path, THUMBNAIL_STEPS[experiment])
		alive = int((state[..., 3] > 0.1).sum())
		print(f'{title}: {alive} living cells after {THUMBNAIL_STEPS[experiment]} steps')
		if alive == 0:
			sys.exit(f'{title} died out - check its model')
		thumb = thumbnail(state)

		# Distinct dates: the installer tells a pack's shaders apart by them.
		created = (now - datetime.timedelta(seconds=len(models) - index)).strftime(
				'%Y-%m-%d %H:%M:%S')
		image, buffers = split_passes(open(shader_path).read())
		cursor = db.execute(
				'INSERT INTO shaders (shader, thumb, name, created, modified, quality, '
				'source_modified) VALUES (?, ?, ?, ?, ?, 0.25, ?)',
				(image, thumb, title, created, created, created))
		shader_id = cursor.lastrowid
		for buffer, source in sorted(buffers.items()):
			db.execute('INSERT INTO shader_buffers (shader_id, buffer, source) VALUES (?, ?, ?)',
					(shader_id, buffer, source))

		weights = png_bytes(weights_path)
		with Image.open(weights_path) as w:
			width, height = w.size
			preview = w.convert('RGB').resize((48, 48), Image.NEAREST)
		out = io.BytesIO()
		preview.save(out, 'PNG')
		db.execute('INSERT INTO textures (name, width, height, ratio, thumb, matrix) '
				'VALUES (?, ?, ?, ?, ?, ?)',
				(texture, width, height, round(height / width, 2), out.getvalue(), weights))
		db.execute('INSERT INTO pack_media (kind, shader_created, image, caption) '
				'VALUES (\'thumb\', ?, ?, ?)', (created, thumb, title))
		names.append({'name': title, 'thumb': base64.b64encode(thumb).decode('ascii')})

db.commit()
db.close()
print('wrote', args.pack, 'with', len(names), 'shaders')

if args.preview:
	with open(args.preview, 'w') as f:
		# As Build Shader Pack's own preview - see ShaderPackCatalog.
		json.dump({
			'format': 1, 'id': args.pack_id, 'title': args.title, 'description': DESCRIPTION,
			'author': args.author, 'version': args.version, 'shaders': names,
		}, f, indent=1)
	print('wrote', args.preview)
