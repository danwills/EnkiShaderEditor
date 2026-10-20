# Neural CA shaders

Scripts that turn the trained models from "Growing Neural Cellular Automata" (Mordvintsev, Randazzo,
Niklasson and Levin, [Distill 2020](https://distill.pub/2020/growing-ca/)) into Enki multipass shaders,
and bundle them into a shader pack. The models and the article's WebGL demo are in
[distillpub/post--growing-ca](https://github.com/distillpub/post--growing-ca). The weights are
CC-BY 4.0 and the demo code is Apache 2.0, so every generated shader keeps a credit header.

Needs Python 3 with numpy and Pillow.

## Make the pack

```sh
python3 tools/nca/fetch_models.py /tmp/nca-models
python3 tools/nca/make_pack.py /tmp/nca-models neural-ca-v2.sqlite --preview neural-ca-preview.json
```

- `fetch_models.py` downloads the 31 models. There are three experiments (`ex1` growing, `ex2`
  persistent, `ex3` regenerating), each with ten creatures, plus a regenerating planarian.
- `make_pack.py` does three things for each model:
  - runs `gen.py` to make its shader and weights texture;
  - runs the model on the CPU (the same update the shader makes) to check that it grows, and to
    draw its thumbnail;
  - writes the shader, its buffers and its texture into a pack file in the app's own format.
- Install the pack file with Get Shader Pack -> Install from File, or open it with the app.

## One shader

```sh
python3 tools/nca/gen.py /tmp/nca-models/ex3_lizard.json lizard.glsl ncaLizardWeights.png \
	--texture ncaLizardWeights --title "GROWING LIZARD" --creature lizard --experiment ex3
```

Import the PNG with Add Uniform/Texture -> sampler2D -> + -> Exact Pixels, keeping the name given to
`--texture`, then open or paste the `.glsl`.

## How the shaders work

- **Buffer A** holds each cell's 16 channels as 2x2 texels. Past the grid it also holds the touch,
  the settings and the three-finger gesture.
- **Buffers B and C** hold the 128-value hidden layer, half each, at 16 texels a cell.
- **Buffer D** holds each step's update.
- **The Image pass** draws the colour channels over white, with cubic interpolation.

The weights live in a texture, not in `const` arrays, because the test phone's GPU corrupts large
constant arrays (see the FAQ). Each cell is `CELL_SIZE` render pixels, so the app's quality setting
sets how many cells there are, and so how big the creatures look. The grid fills the frame and wraps
around (`FULL_FRAME`).

`DILATION` (default 1) spaces the cells each cell sees k apart. That grows a creature k times larger
from k x k interleaved copies of the model. It adds no detail.
