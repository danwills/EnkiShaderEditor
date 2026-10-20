# Editing the docs site

Most of the site's text lives in small JSON and Markdown files, kept apart from the page layout, so
everyday edits don't touch any Astro code. Every push to `master` that changes `docs-site/**` or
`FAQ.md` rebuilds and deploys the site (see the repo's **Actions** tab; it takes about 2 minutes).

## Where things are

| What | File(s) |
|---|---|
| FAQ | `FAQ.md` at the repo root. Each `## Heading` is one question; everything under it is the answer. |
| Feature sections | `src/content/features/NN-name.json` |
| Screenshot gallery | `src/content/gallery/{editing,library,output}/NN-name.json` |
| Example walkthroughs | `src/content/walkthroughs/{basics,input,camera,backbuffer,advanced}/NN-name.md` |
| Download page (APK version, hashes) | `src/pages/enki.astro` (the `apk` object at the top) and `src/components/Download.astro` |
| Images and videos | `public/media/` |

The `NN-` prefix in file names is just for tidiness; the order on the page comes from each entry's
`order` field.

## Media paths

Paths are relative to `public/`, without a leading slash: a file at
`docs-site/public/media/screen-foo.webp` is written as `"media/screen-foo.webp"`. The site adds its
own base path.

## Adding a screenshot to the gallery

1. Put the image in `public/media/`, e.g. `screen-batch-rename.webp`.
2. Add `src/content/gallery/output/04-batch-rename.json` (copy a neighbour and change it):

```json
{
  "group": "output",
  "order": 4,
  "title": "Batch Rename & Delete",
  "desc": "Tidy up unnamed shaders in one go.",
  "icon": "image",
  "accent": "lime",
  "src": "media/screen-batch-rename.webp",
  "type": "image"
}
```

For a video, set `"type": "video"`, point `src` at the `.webm` and add `"poster"` with a still
`.webp` shown before it plays.

## Feature sections

```json
{
  "order": 9,
  "icon": "layers",
  "title": "Buffer Passes",
  "desc": "One-line summary shown under the title.",
  "detail": "A longer paragraph.",
  "media": "media/feat-buffers.webm",
  "mediaType": "video",
  "poster": "media/feat-buffers.webp",
  "accent": "violet"
}
```

`mediaType` is `video` or `image`; `poster` is optional.

## Walkthroughs

Markdown with a front-matter header: `group`, `order`, `name`, `desc`, optional `video` and
`poster`, a list of `links` (`label` + `href`), and the shader as `code: |-` followed by indented
GLSL. The text below the header is the explanation. Copy an existing one as a template.

## Allowed values

The build checks every entry and fails with the offending file and field if a value isn't allowed
(the lists live in `src/content.config.ts`).

- **accent:** `cyan`, `magenta`, `violet`, `lime`, `orange`
- **icon:** `zap`, `palette`, `wallpaper`, `camera`, `layers`, `code`, `hand`, `volume-2`, `clock`,
  `cpu`, `settings`, `download`, `play`, `chevron-down`, `chevron-left`, `chevron-right`, `x`,
  `copy`, `external-link`, `book-open`, `file-code`, `terminal`, `keyboard`, `alert-triangle`,
  `library`, `image`, `sliders-horizontal`, `sparkles`
- **gallery group:** `editing`, `library`, `output`
- **walkthrough group:** `basics`, `input`, `camera`, `backbuffer`, `advanced`

## Editing on GitHub (no setup)

1. Upload media: browse to `docs-site/public/media/`, **Add file → Upload files**, commit.
2. Edit or add an entry: open the file and click the pencil, or press `.` anywhere in the repo for
   the web editor. Commit to `master`.
3. Watch the deploy in **Actions**. If it fails, the build log names the file and field.

## Previewing locally

```sh
cd docs-site
npm install     # first time only
npm run dev     # live-reloading preview; open the address it prints
```

`npm run build` does the same build as the deploy, if you want to check before pushing.

## Making screenshots and clips

- Phone screenshots or the app's own Save Current Frame both work. `adb exec-out screencap -p > shot.png`
  grabs one over USB/Wi-Fi.
- WebP is much smaller than PNG: `cwebp -q 85 shot.png -o shot.webp`. Scale big phone screenshots
  down first (around 720 px wide is plenty).
- Clips are WebM with a WebP poster, e.g. from an MP4:
  `ffmpeg -i clip.mp4 -vf scale=540:-2 -c:v libvpx-vp9 -b:v 0 -crf 36 -an clip.webm` and
  `ffmpeg -i clip.mp4 -frames:v 1 -vf scale=540:-2 clip.webp`.
- If you redeploy without changing `docs-site/**`, run the workflow by hand:
  `gh workflow run deploy-docs.yml -R danwills/EnkiShaderEditor`.
