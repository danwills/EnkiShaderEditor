#!/bin/sh
# Makes Enki's icons from a crystal render made in Enki (Geode, captured
# oversampled), graded bluer and brighter, with a soft blue bloom on the
# highlights:
# - tools/enki/icon_source.png, a square: the adaptive icon's background
#   is all of it (launchers show its middle two thirds), and the legacy
#   rounded icons, Play's 512 icon and the docs site's icons are that
#   middle two thirds. Past the render's own edges it's mirrored, out of
#   sight.
# - tools/enki/feature_source.png, about 2:1: Play's 1024x500 feature
#   graphic.
#
# Usage, from the repo root: tools/enki/make_icon.sh
set -e
SRC=tools/enki/icon_source.png
FEATURE=tools/enki/feature_source.png
RES=app/src/main/res
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# The grade: contrast, brightness, a nudge from teal to blue, and bloom
# (its blur a fraction of the image's width).
grade() {
	blur=$(magick identify -format '%[fx:w*0.016]' "$1")
	magick "$1" -sigmoidal-contrast 5x38% -modulate 118,108,106 "$TMP/graded.png"
	magick "$TMP/graded.png" \( +clone -colorspace gray -level 55%,100% \
		-blur "0x$blur" +level 0%,45% \) -compose screen -composite "$2"
}

grade "$SRC" "$TMP/full.png"
# What a launcher's mask shows: the middle 72dp of the 108dp layer.
shown=$(magick identify -format '%[fx:round(w*2/3)]' "$SRC")
magick "$TMP/full.png" -gravity center -crop "${shown}x${shown}+0+0" +repage "$TMP/shown.png"

# size, corner radius as a fraction, output
rounded() {
	big=$(( $1 * 4 ))
	r=$(echo "$big * $2" | bc)
	magick "$TMP/shown.png" -resize "${big}x${big}" \
		\( -size "${big}x${big}" xc:black -fill white \
		-draw "roundrectangle 0,0 $(( big - 1 )),$(( big - 1 )) $r,$r" \) \
		-alpha off -compose copy_opacity -composite \
		-resize "$1x$1" -unsharp 0x0.8+0.5 "$3"
}

for d in ldpi:0.75 mdpi:1 hdpi:1.5 xhdpi:2 xxhdpi:3 xxxhdpi:4; do
	name=${d%%:*}
	k=${d##*:}
	bg=$(echo "108 * $k / 1" | bc)
	icon=$(echo "48 * $k / 1" | bc)
	magick "$TMP/full.png" -resize "${bg}x${bg}" -unsharp 0x0.8+0.5 \
		-alpha off -strip "$RES/drawable-$name/ic_launcher_background.png"
	rounded "$icon" 0.1 "$RES/mipmap-$name/ic_launcher.png"
done

magick "$TMP/shown.png" -resize 512x512 -unsharp 0x0.8+0.5 -alpha off -strip \
	fastlane/metadata/android/en-US/images/icon.png
rounded 256 0.22 docs-site/public/media/app-icon.png
magick "$TMP/shown.png" -resize 180x180 -unsharp 0x0.8+0.5 -alpha off -strip \
	docs-site/public/media/apple-touch-icon.png

grade "$FEATURE" "$TMP/feature.png"
magick "$TMP/feature.png" -resize 1024x500! -unsharp 0x0.8+0.5 -alpha off -strip \
	fastlane/metadata/android/en-US/images/featureGraphic.png
