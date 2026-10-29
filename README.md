# Enki Shader Editor

Create and edit [GLSL](https://en.wikipedia.org/wiki/GLSL) shaders on
your Android phone or tablet and use them as live wallpaper.

If you find this app useful and wish to support its continued development,
you can [buy me a coffee](https://www.buymeacoffee.com/markusfisch) or
send some Bitcoin decimals to `bc1q2guk2rpll587aymrfadkdtpq32448x5khk5j8z`.

<a href="https://www.buymeacoffee.com/markusfisch" target="_blank"><img
	src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png"
	alt="Buy Me A Coffee"
	style="height: 60px !important;width: 217px !important;"/></a>&nbsp;<a
	href="https://liberapay.com/markusfisch/" target="_blank"><img
	src="https://liberapay.com/assets/widgets/donate.svg"
	alt="Liberapay"
	style="height: 60px !important"/></a>

## Screenshots

<img
	src="fastlane/metadata/android/en-US/images/phoneScreenshots/screencap-main.png"
	alt="Screenshot" width="160"/>
<img
	src="fastlane/metadata/android/en-US/images/phoneScreenshots/screencap-keyboard.png"
	alt="Screenshot with soft keyboard" width="160"/>

## Download

<a href="https://f-droid.org/en/packages/de.markusfisch.android.shadereditor/"><img alt="Get it on F-Droid" src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="80"/></a> <a href="https://play.google.com/store/apps/details?id=de.markusfisch.android.shadereditor"><img alt="Get it on Google Play" src="https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png" height="80"/></a>

## Features

* Live preview in the background or on an extra screen
* Syntax highlighting
* Error highlighting
* Use any shader as live wallpaper
* Exposure of sensors (camera, accelerometer, gyroscope, magnetic field,
	light, pressure, proximity)
* Exposure of battery level
* Supports wallpaper offset
* Supports multiple touches
* Supports multiple render resolutions
* Previously rendered frame in backbuffer texture
* Import and use arbitrary textures
* Create and use cube maps
* Disables rendering when the battery is low

See [FAQ.md](FAQ.md) for frequently asked questions.

## Third-party code and assets

* Tracker module playback (MOD, S3M, XM) uses [IBXM](https://github.com/martincameron/micromod)
	by Martin Cameron, under the [BSD-3-Clause licence](app/src/main/java/ibxm/LICENCE) - see
	`app/src/main/java/ibxm/README.md`.

* The built-in `font` texture is rendered from
	[IBM Plex Mono](https://github.com/IBM/plex), Copyright © 2017 IBM Corp., under the
	[SIL Open Font License 1.1](tools/font/OFL.txt) - see `tools/font/make_atlas.py`.

* The built-in `colormaps` texture holds the
	[CET Perceptually Uniform Colour Maps](https://colorcet.com) by Peter Kovesi, under
	[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) (the newest via the
	[colorcet](https://github.com/holoviz/colorcet) package), and Ready's colour maps: ColorBrewer's
	(Apache 2.0, from VTK) and matplotlib's inferno (CC0) - see
	`app/src/main/res/raw/colormaps_license.txt` and `tools/colormaps/make_atlas.py`.
