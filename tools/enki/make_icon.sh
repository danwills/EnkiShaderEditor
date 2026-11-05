#!/bin/sh
# Makes Enki's icons from a square crop of a crystal render made in Enki
# (tools/enki/icon_source.png, 1440x1440): graded bluer and brighter, with
# a soft blue bloom on the highlights. Writes the adaptive icon's
# background (the whole square: launchers show its middle two thirds),
# the legacy rounded icons and Play's 512 icon (that middle two thirds),
# Play's 1024x500 feature graphic and the docs site's icons.
#
# Usage, from the repo root: tools/enki/make_icon.sh
set -e
SRC=tools/enki/icon_source.png
RES=app/src/main/res
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# The grade: contrast, brightness, a nudge from teal to blue, and bloom.
magick "$SRC" -sigmoidal-contrast 5x38% -modulate 118,108,106 "$TMP/graded.png"
magick "$TMP/graded.png" \( +clone -colorspace gray -level 55%,100% \
	-blur 0x23 +level 0%,45% \) -compose screen -composite "$TMP/full.png"
# What a launcher's mask shows: the middle 72dp of the 108dp layer.
magick "$TMP/full.png" -gravity center -crop 960x960+0+0 +repage "$TMP/shown.png"

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
# The feature graphic: a band across the crystal's middle.
magick "$TMP/full.png" -crop 1440x703+0+369 +repage -resize 1024x500! \
	-unsharp 0x0.8+0.5 -alpha off -strip \
	fastlane/metadata/android/en-US/images/featureGraphic.png
rounded 256 0.22 docs-site/public/media/app-icon.png
magick "$TMP/shown.png" -resize 180x180 -unsharp 0x0.8+0.5 -alpha off -strip \
	docs-site/public/media/apple-touch-icon.png
