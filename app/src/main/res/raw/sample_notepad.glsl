// Notepad: a shader that edits its own source. Buffer A holds a copy of
// this text that you can type into - More Options > Type into Shader,
// or a hardware keyboard with the editor hidden - and this pass draws
// it. Ctrl+Enter (or Run on the typing chip) compiles what you typed
// and, if it works, makes it this pass's source: the screen you're
// typing on is the code you're typing.

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D font;

#define COLUMNS 52.0 // characters across
#define GLYPH_WIDTH 0.42 // of an atlas cell - glyphs are about 0.37 wide
#define CURVE 0.06 // how much the screen bulges
#define SCANLINES 260.0 // lines down the screen
#define SCANLINE_DEPTH 0.35
#define BACKGROUND vec3(0.035, 0.04, 0.045)
#define CURSOR_COLOR vec3(1.0, 0.76, 0.03)

// Two bytes kept in two channels - see Buffer A.
float unpack(float lo, float hi) {
	return floor(lo * 255.0 + 0.5) + floor(hi * 255.0 + 0.5) * 256.0;
}

// The glyph for character `code` at `p` (0-1 across its cell):
// 0.5 on its edge, higher inside.
float glyph(float code, vec2 p) {
	vec2 cell = vec2(mod(code, 16.0), 15.0 - floor(code / 16.0));
	return texture(font, (cell + p) / 16.0).r;
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	vec2 uv = fragCoord / iResolution.xy;
	vec2 c = uv * 2.0 - 1.0;
	c *= 1.0 + CURVE * dot(c, c);
	float screen = smoothstep(1.0, 0.99, abs(c.x)) *
			smoothstep(1.0, 0.99, abs(c.y));

	// The cursor, from Buffer A's top right texel.
	ivec2 res = ivec2(iChannelResolution[0].xy);
	vec4 m = texelFetch(iChannel0, res - 1, 0);
	vec2 cursor = vec2(unpack(m.r, m.g), unpack(m.b, m.a));

	// Character cells from the top left, scrolled to keep the cursor
	// on screen.
	float cw = iResolution.x / COLUMNS;
	float ch = cw / GLYPH_WIDTH * 0.95;
	float rows = floor(iResolution.y / ch);
	vec2 scroll = vec2(max(0.0, cursor.x - COLUMNS + 4.0),
			max(0.0, cursor.y - floor(rows * 0.6)));
	vec2 px = vec2((c.x * 0.5 + 0.5) * iResolution.x,
			(0.5 - c.y * 0.5) * iResolution.y);
	vec2 cr = floor(px / vec2(cw, ch)) + scroll;
	vec2 f = fract(px / vec2(cw, ch));

	bool inside = cr.x >= 0.0 && cr.y >= 0.0 &&
			cr.x < float(res.x) && cr.y < float(res.y - 1);
	vec4 t = inside ? texelFetch(iChannel0, ivec2(cr), 0) : vec4(0.0);
	float code = floor(t.r * 255.0 + 0.5);
	float d = glyph(code, vec2(0.5 + (f.x - 0.5) * GLYPH_WIDTH, 1.0 - f.y));
	float w = 3.0 / ch;
	float ink = smoothstep(0.5 - w, 0.5 + w, d);
	float glow = smoothstep(0.2, 0.5, d) * 0.3;

	// A blinking bar at the cursor's left edge.
	float bar = step(f.x, 0.12) * step(0.08, f.y) * step(f.y, 0.92) *
			step(0.5, fract(iTime * 1.2));
	float atCursor = all(equal(cr, cursor)) ? 1.0 : 0.0;

	vec3 col = BACKGROUND + t.gba * (ink + glow) + CURSOR_COLOR * bar * atCursor;

	float scan = 0.5 + 0.5 * cos(c.y * SCANLINES * 3.14159);
	col *= 1.0 - SCANLINE_DEPTH * (1.0 - scan);
	col *= 1.0 - 0.3 * dot(c * 0.7, c * 0.7);
	fragColor = vec4(col * screen, 1.0);
}
//// Buffer A
// Notepad's text, laid out like sourceText: texel row n is line n, R is
// the character code / 255 (0 for none; a tab is 9 then 0s for its
// room) and GBA its colour. The ///text:Image line below says this is
// an edited copy of the Image pass - so `sourceText` here is the Image
// pass's source, and Run reads this buffer back into it. The top row is
// this pass's own: the cursor (top right) and the version of the
// source it copied (next to it).
///text:Image

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D sourceText;
uniform sampler2D sourceTextColor;
uniform vec2 sourceTextSize;
uniform float sourceTextVersion;
uniform sampler2D keyboard;
uniform int typedChar;

#define TAB_WIDTH 4
#define MAX_COLUMNS 512
#define TYPED_COLOR vec3(1.0)

ivec2 res;

vec4 cell(ivec2 p) {
	if (p.x < 0 || p.y < 0 || p.x >= res.x || p.y >= res.y - 1) {
		return vec4(0.0);
	}
	return texelFetch(iChannel0, p, 0);
}

int codeAt(ivec2 p) {
	return int(cell(p).r * 255.0 + 0.5);
}

int unpack(float lo, float hi) {
	return int(lo * 255.0 + 0.5) + int(hi * 255.0 + 0.5) * 256;
}

vec2 pack(int v) {
	return vec2(float(v % 256), float((v / 256) % 256)) / 255.0;
}

bool pressed(int key) {
	return texelFetch(keyboard, ivec2(key, 1), 0).r > 0.5;
}

// One past the last character on line y.
int lineLength(int y) {
	int length = 0;
	for (int x = 0; x < MAX_COLUMNS; ++x) {
		if (x >= res.x) {
			break;
		}
		if (codeAt(ivec2(x, y)) != 0) {
			length = x + 1;
		}
	}
	return length;
}

// Where the character before the cursor starts - back over a tab's room.
int previousStart(ivec2 cur) {
	int x = cur.x - 1;
	for (int i = 0; i < 16; ++i) {
		if (x <= 0 || codeAt(ivec2(x, cur.y)) != 0) {
			break;
		}
		--x;
	}
	return max(x, 0);
}

vec4 typed(int code) {
	return vec4(float(code) / 255.0, TYPED_COLOR);
}

// This texel after this frame's typing.
vec4 edit(ivec2 p, ivec2 cur, int c) {
	vec4 here = cell(p);
	if (c >= 32 && c <= 126) {
		if (p.y != cur.y || p.x < cur.x) {
			return here;
		}
		return p.x == cur.x ? typed(c) : cell(p - ivec2(1, 0));
	}
	if (c == 9) {
		int n = TAB_WIDTH - cur.x % TAB_WIDTH;
		if (p.y != cur.y || p.x < cur.x) {
			return here;
		}
		if (p.x == cur.x) {
			return typed(9);
		}
		return p.x < cur.x + n ? vec4(0.0) : cell(p - ivec2(n, 0));
	}
	if (c == 8) {
		if (cur.x > 0) {
			int start = previousStart(cur);
			if (p.y != cur.y || p.x < start) {
				return here;
			}
			return cell(p + ivec2(cur.x - start, 0));
		}
		if (cur.y == 0 || p.y < cur.y - 1) {
			return here;
		}
		if (p.y == cur.y - 1) {
			int length = lineLength(cur.y - 1);
			return p.x < length ? here : cell(ivec2(p.x - length, cur.y));
		}
		return cell(p + ivec2(0, 1));
	}
	if (c == 127) {
		if (p.y != cur.y || p.x < cur.x) {
			return here;
		}
		return cell(p + ivec2(1, 0));
	}
	if (c == 10) {
		if (p.y < cur.y) {
			return here;
		}
		if (p.y == cur.y) {
			return p.x < cur.x ? here : vec4(0.0);
		}
		if (p.y == cur.y + 1) {
			return cell(ivec2(p.x + cur.x, cur.y));
		}
		return cell(p - ivec2(0, 1));
	}
	return here;
}

// Where the cursor goes this frame.
ivec2 move(ivec2 cur, int c) {
	if (c >= 32 && c <= 126) {
		return cur + ivec2(1, 0);
	}
	if (c == 9) {
		return cur + ivec2(TAB_WIDTH - cur.x % TAB_WIDTH, 0);
	}
	if (c == 10) {
		return ivec2(0, min(cur.y + 1, res.y - 2));
	}
	if (c == 8) {
		if (cur.x > 0) {
			return ivec2(previousStart(cur), cur.y);
		}
		return cur.y > 0 ? ivec2(lineLength(cur.y - 1), cur.y - 1) : cur;
	}
	if (pressed(37)) { // Left
		if (cur.x > 0) {
			return ivec2(previousStart(cur), cur.y);
		}
		return cur.y > 0 ? ivec2(lineLength(cur.y - 1), cur.y - 1) : cur;
	}
	if (pressed(39)) { // Right
		int length = lineLength(cur.y);
		if (cur.x >= length) {
			return cur.y < res.y - 2 ? ivec2(0, cur.y + 1) : cur;
		}
		int x = cur.x + 1;
		for (int i = 0; i < 16; ++i) {
			if (x >= length || codeAt(ivec2(x, cur.y)) != 0) {
				break;
			}
			++x;
		}
		return ivec2(x, cur.y);
	}
	if (pressed(38) && cur.y > 0) { // Up
		return ivec2(min(cur.x, lineLength(cur.y - 1)), cur.y - 1);
	}
	if (pressed(40) && cur.y < res.y - 2) { // Down
		return ivec2(min(cur.x, lineLength(cur.y + 1)), cur.y + 1);
	}
	if (pressed(36)) { // Home
		return ivec2(0, cur.y);
	}
	if (pressed(35)) { // End
		return ivec2(lineLength(cur.y), cur.y);
	}
	return cur;
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	res = ivec2(iResolution.xy);
	ivec2 p = ivec2(fragCoord);
	ivec2 cursorTexel = res - 1;
	ivec2 versionTexel = res - ivec2(2, 1);
	vec4 m = texelFetch(iChannel0, cursorTexel, 0);
	ivec2 cur = ivec2(unpack(m.r, m.g), unpack(m.b, m.a));
	vec4 v = texelFetch(iChannel0, versionTexel, 0);
	// A new source (the shader's first frame, a Run, an edit in the
	// editor): copy it afresh, colours and all - keeping the cursor,
	// unless this buffer is new (cleared to opaque black, so its "cursor"
	// is nonsense).
	int copiedVersion = unpack(v.r, v.g);
	bool copy = copiedVersion != int(sourceTextVersion);
	if (copiedVersion == 0) {
		cur = ivec2(0);
	}

	if (p.y == res.y - 1) {
		if (p == versionTexel) {
			fragColor = vec4(pack(int(sourceTextVersion)), 0.0, 0.0);
		} else if (p == cursorTexel) {
			ivec2 next = copy ? cur : move(cur, typedChar);
			fragColor = vec4(pack(next.x), pack(next.y));
		} else {
			fragColor = vec4(0.0);
		}
		return;
	}
	if (copy) {
		if (p.x < int(sourceTextSize.x) && p.y < int(sourceTextSize.y)) {
			fragColor = vec4(texelFetch(sourceText, p, 0).r,
					texelFetch(sourceTextColor, p, 0).rgb);
		} else {
			fragColor = vec4(0.0);
		}
		return;
	}
	fragColor = edit(p, cur, typedChar);
}
