// Demo Writer: a demoscene twister. A square bar twists along the
// screen on layered sine waves, and each of its four faces scrolls a
// line of the `scroller` text past. That text isn't in this code - it's
// a shader text, in the "scroller" tab above (or Offscreen Buffers ->
// Texts...): edit it and the bar picks it up straight away. A pass
// reads a text as a texture, one texel per character and one row per
// line, like `sourceText`; `font` is the built-in glyph atlas.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution;
uniform float time;
uniform sampler2D font;
uniform sampler2D scroller;
uniform vec2 scrollerSize; // (columns, lines)

#define THICKNESS 0.46 // the bar's width, where the screen's short side is 2
#define TWIST 1.5 // how far the sine waves wind it
#define SPIN 0.6 // radians a second
#define WAVE 0.22 // how far it snakes about
#define CHARS 10.0 // characters along the screen's short side
#define SPEED 2.6 // characters a second
#define GAP 10.0 // blank characters before a line comes round again
#define GLYPH_WIDTH 0.42 // of an atlas cell - glyphs are about 0.37 wide

const float PI = 3.14159265;

// The glyph for character `code` at `p` (0-1 across its cell): 0.5 on
// its edge, higher inside.
float glyph(float code, vec2 p) {
	vec2 cell = vec2(mod(code, 16.0), 15.0 - floor(code / 16.0));
	return texture2D(font, (cell + p) / 16.0).r;
}

vec3 rainbow(float t) {
	return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)));
}

void main(void) {
	// Bar space: x along the screen's long side, y across it, the short
	// side running -1..1 - so on a phone held upright the bar runs down
	// the screen, and the text reads with the phone turned on its side.
	float shortSide = min(resolution.x, resolution.y);
	vec2 c = (gl_FragCoord.xy * 2.0 - resolution) / shortSide;
	vec2 p = resolution.x >= resolution.y ? c : vec2(-c.y, c.x);
	float pixel = 2.0 / shortSide;

	// Copper bars behind, rolling.
	float band = p.y * 2.5 + 0.35 * sin(p.x * 1.4 + time * 0.8) + time * 0.3;
	vec3 col = rainbow(floor(band) * 0.13 + time * 0.03) *
			pow(abs(sin(band * PI)), 5.0) * 0.4 + vec3(0.02, 0.0, 0.05);

	// How far the bar's turned here, and where its centre is.
	float a = time * SPIN + TWIST * (sin(p.x * 1.1 + time * 0.9) +
			0.5 * sin(p.x * 2.3 - time * 1.3));
	float centre = WAVE * sin(p.x * 0.8 + time * 0.6);
	float r = THICKNESS * 0.70710678; // centre to corner

	// Its four faces: face k runs from corner k to corner k + 1, and
	// faces us wherever it rises across the screen.
	for (int k = 0; k < 4; ++k) {
		float fk = float(k);
		float y0 = centre + r * sin(a + fk * PI * 0.5);
		float y1 = centre + r * sin(a + (fk + 1.0) * PI * 0.5);
		if (y1 <= y0 || p.y < y0 || p.y >= y1) {
			continue;
		}
		float height = y1 - y0;
		float v = (p.y - y0) / height;
		float facing = height / THICKNESS; // 1 head on, 0 edge on

		// This face's line of the text, scrolling along.
		float line = mod(fk, max(scrollerSize.y, 1.0));
		float x = p.x * CHARS * 0.5 + time * SPEED + fk * 9.0;
		float column = mod(floor(x), scrollerSize.x + GAP);
		vec4 t = texture2D(scroller, (vec2(column, line) + 0.5) / scrollerSize);
		float code = floor(t.r * 255.0 + 0.5);
		float index = floor(t.g * 255.0 + 0.5) * 256.0 + floor(t.b * 255.0 + 0.5);
		float present = t.a * step(column, scrollerSize.x - 1.0);

		float d = glyph(code, vec2(0.5 + (fract(x) - 0.5) * GLYPH_WIDTH, v));
		float w = clamp(2.5 * pixel / height, 0.02, 0.3);
		float ink = smoothstep(0.5 - w, 0.5 + w, d) * present;
		float glow = smoothstep(0.2, 0.5, d) * present;

		// The face: dark metal tinted by its own colour, lit as it turns
		// to face us, with a bright edge where it meets the next.
		vec3 tint = rainbow(fk * 0.25 + time * 0.07);
		vec3 face = mix(vec3(0.05, 0.06, 0.1), tint * 0.35, 0.6) * (0.3 + 0.7 * facing);
		float edge = 1.0 - smoothstep(0.0, 2.0 * pixel / height, min(v, 1.0 - v));
		face += vec3(0.6) * edge * facing;
		face += vec3(0.25) * pow(facing, 12.0);

		vec3 letter = rainbow(index * 0.035 - time * 0.4 + fk * 0.25) * (0.55 + 0.6 * facing);
		col = face + letter * (ink + glow * 0.35) + vec3(0.8) * ink * pow(facing, 6.0) * 0.4;
	}

	// A little vignette.
	vec2 uv = gl_FragCoord.xy / resolution;
	col *= 0.75 + 0.25 * pow(16.0 * uv.x * uv.y * (1.0 - uv.x) * (1.0 - uv.y), 0.3);
	gl_FragColor = vec4(col, 1.0);
}
//// Text scroller
*** ENKI SHADER EDITOR PRESENTS ... THE DEMO WRITER *** TWISTING ON SINE WAVES SINCE TODAY ***
GREETINGS TO EVERY PIXEL PUSHER, SINE WAVE SURFER AND COPPER BAR POLISHER OUT THERE!
THIS TEXT LIVES IN A SHADER TEXT, NOT IN THE CODE - OPEN THE "SCROLLER" TAB AND WRITE YOUR OWN
FOUR FACES, FOUR LINES ... KEEP ON TWISTING, AND DON'T FORGET TO SAY HI TO YOUR MUM
