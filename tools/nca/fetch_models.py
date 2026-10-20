#!/usr/bin/env python3
# Downloads the trained Growing Neural Cellular Automata models from the
# Distill article's repository (github.com/distillpub/post--growing-ca,
# public/webgl_models8/, CC-BY 4.0) into a directory, renamed from their
# emoji names to plain ones (ex3_🦎.json -> ex3_lizard.json). See README.md.
import argparse
import os
import urllib.parse
import urllib.request

BASE = 'https://raw.githubusercontent.com/distillpub/post--growing-ca/master/public/webgl_models8/'

# Each model file's emoji, and the creature's name.
CREATURES = {
	'🦎': 'lizard',
	'🦋': 'butterfly',
	'🐞': 'ladybird',
	'🐠': 'fish',
	'👁': 'eye',
	'🥨': 'pretzel',
	'🎄': 'tree',
	'🕸': 'web',
	'😀': 'smiley',
	'💥': 'explosion',
	'planarian': 'planarian',
}
EXPERIMENTS = ['ex1', 'ex2', 'ex3']
# Only the regenerating experiment has a planarian.
ONLY = {'planarian': ['ex3']}

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('directory', help='where to put the model files')
args = parser.parse_args()
os.makedirs(args.directory, exist_ok=True)

for key, name in CREATURES.items():
	for experiment in ONLY.get(key, EXPERIMENTS):
		url = BASE + urllib.parse.quote(f'{experiment}_{key}.json')
		path = os.path.join(args.directory, f'{experiment}_{name}.json')
		if os.path.exists(path):
			continue
		with urllib.request.urlopen(url) as response, open(path, 'wb') as out:
			out.write(response.read())
		print('fetched', path)
