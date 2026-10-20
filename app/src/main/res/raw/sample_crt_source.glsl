// CRT Self-Portrait: this shader types its own source onto an old
// green-phosphor screen. `sourceText` is the source (one texel per
// character, row n = line n) and `font` a built-in signed-distance-field
// glyph atlas - see the FAQ. Everything else is the CRT.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution;
uniform float time;
uniform sampler2D backbuffer;
uniform sampler2D font;
uniform sampler2D sourceText;
uniform vec2 sourceTextSize;
uniform float sourceTextLength;

#define COLUMNS 80.0 // characters across, as on a real terminal
#define CHARS_PER_SECOND 140.0
#define HOLD 6.0 // seconds to show the whole text before typing it again
#define CURVE 0.12 // how much the tube bulges
#define PERSISTENCE 0.55 // how much of the last frame glows on
#define PHOSPHOR vec3(0.3, 1.0, 0.45)
#define GLYPH_WIDTH 0.42 // of an atlas cell - glyphs are about 0.37 wide

// The glyph for character `code` at `p` (0-1 across its cell):
// 0.5 on its edge, higher inside.
float glyph(float code, vec2 p) {
	vec2 cell = vec2(mod(code, 16.0), 15.0 - floor(code / 16.0));
	return texture2D(font, (cell + p) / 16.0).r;
}

void main(void) {
	vec2 uv = gl_FragCoord.xy / resolution;
	vec2 c = uv * 2.0 - 1.0;
	c *= 1.0 + CURVE * dot(c, c);
	float tube = smoothstep(1.0, 0.985, abs(c.x)) *
			smoothstep(1.0, 0.985, abs(c.y));

	// Typing: how many characters are showing, round and round.
	float cycle = sourceTextLength / CHARS_PER_SECOND + HOLD;
	float shown = mod(time, cycle) * CHARS_PER_SECOND;

	// Character cells from the top left, scrolled to keep up with the
	// typing (roughly - by how far through the text it is).
	float cw = resolution.x / COLUMNS;
	float ch = cw / GLYPH_WIDTH * 0.95;
	float rows = resolution.y / ch;
	float done = min(shown / sourceTextLength, 1.0);
	float scroll = max(0.0, done * sourceTextSize.y - rows * 0.75);
	vec2 px = vec2((c.x * 0.5 + 0.5) * resolution.x,
			(0.5 - c.y * 0.5) * resolution.y + scroll * ch);
	vec2 cr = floor(px / vec2(cw, ch));
	vec2 f = fract(px / vec2(cw, ch));

	vec4 t = texture2D(sourceText, (cr + 0.5) / sourceTextSize);
	float code = floor(t.r * 255.0 + 0.5);
	float index = floor(t.g * 255.0 + 0.5) * 256.0 + floor(t.b * 255.0 + 0.5);
	float real = t.a * step(cr.x, sourceTextSize.x - 1.0) *
			step(0.0, cr.y) * step(cr.y, sourceTextSize.y - 1.0);

	float d = glyph(code, vec2(0.5 + (f.x - 0.5) * GLYPH_WIDTH, 1.0 - f.y));
	float visible = real * step(index, shown - 1.0);
	float w = 3.0 / ch;
	float ink = smoothstep(0.5 - w, 0.5 + w, d) * visible;
	float glow = smoothstep(0.2, 0.5, d) * 0.4 * visible;

	// The cursor: a block on the next character, blinking once typed.
	float typing = step(shown, sourceTextLength);
	float blink = mix(step(0.5, fract(time * 1.5)), 1.0, typing);
	float cursor = real * step(abs(index - floor(shown)), 0.5) * blink *
			step(0.1, f.x) * step(f.x, 0.9) * step(0.15, f.y) * step(f.y, 0.85);

	vec3 col = PHOSPHOR * (ink + glow + cursor * 0.9);

	// The tube: scanlines, an aperture grille, a rolling hum bar,
	// flicker, vignette, and a faint glow on the glass.
	col *= 0.75 + 0.25 * sin(gl_FragCoord.y * 3.14159);
	col *= 0.9 + 0.1 * vec3(
			sin(gl_FragCoord.x * 2.094),
			sin(gl_FragCoord.x * 2.094 + 2.094),
			sin(gl_FragCoord.x * 2.094 + 4.189));
	float hum = abs(fract(uv.y * 0.6 - time * 0.11) - 0.5);
	col *= 0.92 + 0.08 * smoothstep(0.0, 0.08, hum);
	col *= 0.97 + 0.03 * sin(time * 120.0);
	col *= 1.0 - 0.35 * dot(c * 0.7, c * 0.7);
	col += PHOSPHOR * 0.025 * (1.0 - dot(c, c) * 0.5);
	col *= tube;

	// Phosphor persistence: what was lit fades rather than vanishing.
	vec3 last = texture2D(backbuffer, uv).rgb;
	gl_FragColor = vec4(max(col, last * PERSISTENCE), 1.0);
}
