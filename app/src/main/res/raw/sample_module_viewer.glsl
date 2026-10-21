// Module Viewer: plays a tracker module and shows it the way the old
// trackers did - each channel's waveform and spectrum up top, and the
// pattern scrolling up through the playing row below.
//
// The ///mod: line below picks the module: enki_demo ships with the app,
// or import your own (MOD, S3M or XM) with More Options > Import Tracker
// Module, and put its name here instead.
///mod:enki_demo

uniform sampler2D moduleInfo;
uniform sampler2D moduleScope;
uniform float moduleRow;
uniform sampler2D font;

#define SHOWN_CHANNELS 8 // at most this many columns and scopes
#define SCOPE_HEIGHT 0.42 // of the screen, for the scopes
#define ROWS_SHOWN 23.0 // pattern rows on screen

const vec3 COLORS[8] = vec3[8](
		vec3(0.35, 0.95, 0.55), vec3(0.95, 0.75, 0.3), vec3(0.4, 0.75, 1.0),
		vec3(1.0, 0.45, 0.6), vec3(0.75, 0.55, 1.0), vec3(0.3, 0.95, 0.95),
		vec3(1.0, 0.6, 0.35), vec3(0.85, 0.95, 0.4));

ivec4 info(int x, int y) {
	return ivec4(texelFetch(moduleInfo, ivec2(x, y), 0) * 255.0 + 0.5);
}

float scope(int x, int channel) {
	return texelFetch(moduleScope, ivec2(clamp(x, 0, 511), channel), 0).r;
}

// One character at p, in a cell `h` pixels tall from `origin`.
float glyph(int code, vec2 p, vec2 origin, float h) {
	vec2 q = (p - origin) / vec2(h * 0.5, h);
	if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0 || code == 32) {
		return 0.0;
	}
	vec2 cell = vec2(float(code % 16), float(15 - code / 16));
	vec2 g = clamp(vec2(0.5 + (q.x - 0.5) * 0.48, q.y), 0.03, 0.97);
	float d = textureLod(font, (cell + g) / 16.0, 0.0).r;
	float w = 3.5 / h;
	return smoothstep(0.5 - w, 0.5 + w, d);
}

int hexDigit(int v) {
	v &= 15;
	return v < 10 ? 48 + v : 55 + v;
}

// Character k (0-9) of a pattern cell as trackers show it:
// note (C-4), instrument (two digits), effect and parameter (A02).
int cellChar(ivec4 n, ivec4 m, int k) {
	if (k <= 2) {
		if (n.x <= 0 || n.x >= 118) {
			return n.x == 97 ? 61 : 46; // key off "===", or "..."
		}
		int s = (n.x + 2) % 12;
		int letters[12] = int[12](65, 65, 66, 67, 67, 68, 68, 69, 70, 70, 71, 71);
		bool sharp = s == 1 || s == 4 || s == 6 || s == 9 || s == 11;
		return k == 0 ? letters[s] : k == 1 ? (sharp ? 35 : 45) : 48 + (n.x + 2) / 12;
	}
	if (k == 3) {
		return 32;
	}
	if (k <= 5) {
		return n.y > 0 && n.y < 255 ? hexDigit(k == 4 ? n.y >> 4 : n.y) : 46;
	}
	if (k == 6) {
		return 32;
	}
	bool fx = n.w > 0 || m.x > 0;
	if (!fx) {
		return 46;
	}
	if (k == 7) {
		return n.w < 10 ? 48 + n.w : n.w < 36 ? 55 + n.w : 46;
	}
	return hexDigit(k == 8 ? m.x >> 4 : m.x);
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	vec2 res = iResolution.xy;
	vec2 p = fragCoord;
	ivec4 g0 = info(0, 0);
	ivec4 g1 = info(1, 0);
	int channels = min(max(g1.z, 1), SHOWN_CHANNELS);
	vec3 col = vec3(0.03, 0.035, 0.05);

	float scopeTop = res.y;
	float scopeBottom = res.y * (1.0 - SCOPE_HEIGHT);
	if (p.y > scopeBottom) {
		// The scopes: a grid of two columns.
		int cols = channels > 4 ? 2 : 1;
		int rows = (channels + cols - 1) / cols;
		float w = res.x / float(cols);
		float h = (scopeTop - scopeBottom) / float(rows);
		int cx = int(p.x / w);
		int cy = int((scopeTop - p.y) / h);
		int c = cy * cols + cx;
		if (c < channels) {
			vec2 o = vec2(float(cx) * w, scopeTop - float(cy + 1) * h);
			vec2 q = (p - o) / vec2(w, h);
			vec3 cc = COLORS[c];
			// Its spectrum behind, as bars.
			float band = texelFetch(moduleScope, ivec2(int(q.x * 511.0), 32 + c), 0).r;
			col += cc * 0.18 * step(q.y, band * 0.95) * step(0.15, fract(q.x * 64.0));
			// Its waveform: filled between neighbouring samples, so steep
			// edges stay solid.
			int x = int(q.x * 511.0);
			float a = scope(x, c);
			float b = scope(x - 1, c);
			float lo = min(a, b) * 0.9 + 0.05;
			float hi = max(a, b) * 0.9 + 0.05;
			float px = 1.5 / h;
			float line = smoothstep(px, 0.0, max(lo - q.y, q.y - hi));
			col = mix(col, cc, line);
			// How loud, along the bottom.
			float vol = float(info(c, 1).z) / 255.0;
			col = mix(col, cc * 0.6, step(q.y, 0.03) * step(q.x, vol));
			// Its number, and frame.
			col = mix(col, cc, glyph(49 + c, p, o + vec2(8.0, h - 30.0), 22.0));
			float edge = min(min(q.x, 1.0 - q.x) * w, min(q.y, 1.0 - q.y) * h);
			col += vec3(0.06) * smoothstep(1.5, 0.0, edge);
		}
	} else {
		// The pattern: rows scroll up, the playing one in the middle.
		float rh = scopeBottom / ROWS_SHOWN;
		float centre = scopeBottom * 0.5;
		float f = fract(moduleRow);
		float d = (centre - p.y) / rh + f + 0.5;
		int row = int(floor(d));
		float rowFrac = fract(d);
		int window = 24 + row;
		if (row == 0) {
			col = vec3(0.1, 0.13, 0.2);
		} else if ((g0.z + row) % 4 == 0) {
			col = vec3(0.055, 0.06, 0.08);
		}
		if (window >= 0 && window < 64 && info(32, 8 + window).y > 0) {
			float ch = rh * 0.78;
			float y0 = centre - (float(row) - f + 0.5) * rh + rh * 0.12;
			// The row number, in hex.
			int rowNumber = (g0.z + row) % max(g0.w, 1);
			if (rowNumber < 0) {
				rowNumber += max(g0.w, 1);
			}
			vec3 dim = row == 0 ? vec3(0.9) : vec3(0.4);
			col = mix(col, dim, max(glyph(hexDigit(rowNumber >> 4), p, vec2(6.0, y0), ch),
					glyph(hexDigit(rowNumber), p, vec2(6.0 + ch * 0.5, y0), ch)));
			// Each channel's cell.
			float left = 6.0 + ch * 1.5;
			float cw = (res.x - left) / float(channels);
			int c = int((p.x - left) / cw);
			if (c >= 0 && c < channels) {
				float x0 = left + float(c) * cw;
				int k = int((p.x - x0) / (ch * 0.5));
				if (k >= 0 && k < 10) {
					ivec4 n = info(c, 8 + window);
					ivec4 m = info(32 + c, 8 + window);
					int code = cellChar(n, m, k);
					vec3 ink = k <= 2 ? COLORS[c] : k <= 5 ? vec3(0.75) : vec3(0.55, 0.6, 0.9);
					if (code == 46) {
						ink = vec3(0.22);
					}
					col = mix(col, ink * (row == 0 ? 1.15 : 0.8),
							glyph(code, p, vec2(x0 + float(k) * ch * 0.5, y0), ch));
				}
			}
		}
		// Fade away at the top and bottom.
		col *= smoothstep(0.0, 0.25, p.y / scopeBottom) *
				smoothstep(0.0, 0.15, 1.0 - p.y / scopeBottom);
	}
	fragColor = vec4(col, 1.0);
}
