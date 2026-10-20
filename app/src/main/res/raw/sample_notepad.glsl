// Notepad: a shader that edits its own source. Buffer A holds a copy of
// this text that you can type into - More Options > Type into Shader,
// or a hardware keyboard with the editor hidden - and this pass draws
// it. The Run Typed Text button in the toolbar compiles what you typed
// and, if it works, makes it this pass's source: the screen you're
// typing on is the code you're typing.
//
// Arrows, Home, End, PageUp and PageDown move the cursor, and so does
// a tap; hold Shift to select. One finger drags the text about; two
// pinch, pan and turn it. Buffer A does all of that - this pass just
// draws.

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D font;

#define GLYPH_WIDTH 0.42 // of an atlas cell - glyphs are about 0.37 wide
#define CURVE 0.06 // how much the screen bulges
#define SCANLINES 260.0 // lines down the screen
#define SCANLINE_DEPTH 0.35
#define BACKGROUND vec3(0.035, 0.04, 0.045)
#define CURSOR_COLOR vec3(1.0, 0.76, 0.03)
#define SELECTION_COLOR vec3(0.13, 0.25, 0.45)

ivec2 res;

// Buffer A's own row of numbers - see there.
int byte(float v) {
	return int(v * 255.0 + 0.5);
}

int unpack(vec2 v) {
	return byte(v.x) + byte(v.y) * 256;
}

int unpack24(vec3 v) {
	return byte(v.x) + byte(v.y) * 256 + byte(v.z) * 65536;
}

vec4 meta(int i) {
	return texelFetch(iChannel0, ivec2(res.x - 1 - i, res.y - 1), 0);
}

vec4 cell(ivec2 p) {
	if (p.x < 0 || p.y < 0 || p.x >= res.x || p.y >= res.y - 1) {
		return vec4(0.0);
	}
	return texelFetch(iChannel0, p, 0);
}

bool before(ivec2 a, ivec2 b) {
	return a.y < b.y || (a.y == b.y && a.x < b.x);
}

mat2 rotation(float a) {
	return mat2(cos(a), sin(a), -sin(a), cos(a));
}

// The glyph for character `code` at `p` (0-1 across its cell):
// 0.5 on its edge, higher inside.
float glyph(float code, vec2 p) {
	vec2 cell = vec2(mod(code, 16.0), 15.0 - floor(code / 16.0));
	return texture(font, (cell + p) / 16.0).r;
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	res = ivec2(iChannelResolution[0].xy);
	vec2 uv = fragCoord / iResolution.xy;
	vec2 c = uv * 2.0 - 1.0;
	c *= 1.0 + CURVE * dot(c, c);
	float screen = smoothstep(1.0, 0.99, abs(c.x)) *
			smoothstep(1.0, 0.99, abs(c.y));

	// Buffer A's view of the text.
	vec4 m = meta(0);
	ivec2 cursor = ivec2(unpack(m.rg), unpack(m.ba));
	m = meta(2);
	ivec2 anchor = ivec2(unpack(m.rg), unpack(m.ba));
	vec2 pan = vec2(float(unpack24(meta(3).rgb) - 8388608),
			float(unpack24(meta(9).rgb) - 8388608)) / 16.0;
	m = meta(4);
	float zoom = exp2(float(unpack(m.rg) - 32768) / 4096.0);
	float angle = float(unpack(m.ba)) / 65536.0 * 6.2831853;
	m = meta(8);
	vec2 cellSize = max(vec2(unpack(m.rg), unpack(m.ba)) / 16.0, vec2(1.0));

	// Where this pixel is on the page - y down, as text goes.
	vec2 center = 0.5 * iResolution.xy;
	vec2 s = vec2((c.x * 0.5 + 0.5) * iResolution.x,
			(0.5 - c.y * 0.5) * iResolution.y);
	vec2 page = rotation(-angle) * ((s - center) / zoom) + center + pan;
	vec2 cr = floor(page / cellSize);
	vec2 f = fract(page / cellSize);
	ivec2 r = ivec2(cr);

	vec4 t = cr.x >= 0.0 && cr.y >= 0.0 ? cell(r) : vec4(0.0);
	float code = floor(t.r * 255.0 + 0.5);
	float d = glyph(code, vec2(0.5 + (f.x - 0.5) * GLYPH_WIDTH, 1.0 - f.y));
	float w = 3.0 / (cellSize.y * zoom);
	float ink = smoothstep(0.5 - w, 0.5 + w, d);
	float glow = smoothstep(0.2, 0.5, d) * 0.3;

	// The selection: characters (and a tab's room) from anchor to cursor.
	ivec2 lo = before(anchor, cursor) ? anchor : cursor;
	ivec2 hi = before(anchor, cursor) ? cursor : anchor;
	bool inText = code != 0.0 || cell(r + ivec2(1, 0)).r > 0.0 ||
			cell(r + ivec2(2, 0)).r > 0.0 || cell(r + ivec2(3, 0)).r > 0.0;
	float selected = !before(r, lo) && before(r, hi) && inText ? 1.0 : 0.0;

	// A blinking bar at the cursor's left edge.
	float bar = step(f.x, 0.12) * step(0.08, f.y) * step(f.y, 0.92) *
			step(0.5, fract(iTime * 1.2)) * (r == cursor ? 1.0 : 0.0);

	vec3 col = mix(BACKGROUND, SELECTION_COLOR, selected) +
			t.gba * (ink + glow) + CURSOR_COLOR * bar;

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
// pass's source, and Run Typed Text reads this buffer back into it.
//
// The top row is this pass's own numbers, from the right (two bytes
// each, in pairs of channels): 0 the cursor, 1 the source version it
// copied, 2 the selection's other end, 3 and 9 the view's pan (three
// bytes each), 4 its zoom and turn, 5-7 last frame's touches (and how
// far the touch has moved, to tell a tap from a drag), 8 the
// size of a character cell - which the Image pass reads to draw.
///text:Image

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D sourceText;
uniform sampler2D sourceTextColor;
uniform vec2 sourceTextSize;
uniform float sourceTextVersion;
uniform sampler2D keyboard;
uniform int typedChar;
uniform vec3 pointers[10];
uniform int pointerCount;

#define COLUMNS 52.0 // characters across, unzoomed
#define CELL_ASPECT 2.26 // a character cell's height / width
#define TYPING_AREA 0.45 // the cursor is kept in this top part of the screen
#define TAB_WIDTH 4
#define MAX_COLUMNS 512
#define TAP_SLOP 24.0 // pixels a touch can move and still be a tap
#define MIN_ZOOM 0.125
#define MAX_ZOOM 8.0
#define TYPED_COLOR vec3(1.0)

ivec2 res;
vec2 cellSize;

int byte(float v) {
	return int(v * 255.0 + 0.5);
}

int unpack(vec2 v) {
	return byte(v.x) + byte(v.y) * 256;
}

vec2 pack(int v) {
	v = clamp(v, 0, 65535);
	return vec2(float(v % 256), float(v / 256)) / 255.0;
}

int unpack24(vec3 v) {
	return byte(v.x) + byte(v.y) * 256 + byte(v.z) * 65536;
}

vec3 pack24(int v) {
	v = clamp(v, 0, 16777215);
	return vec3(float(v % 256), float((v / 256) % 256), float(v / 65536)) / 255.0;
}

vec4 meta(int i) {
	return texelFetch(iChannel0, ivec2(res.x - 1 - i, res.y - 1), 0);
}

vec4 cell(ivec2 p) {
	if (p.x < 0 || p.y < 0 || p.x >= res.x || p.y >= res.y - 1) {
		return vec4(0.0);
	}
	return texelFetch(iChannel0, p, 0);
}

int codeAt(ivec2 p) {
	return byte(cell(p).r);
}

bool held(int key) {
	return texelFetch(keyboard, ivec2(key, 0), 0).r > 0.5;
}

bool pressed(int key) {
	return texelFetch(keyboard, ivec2(key, 1), 0).r > 0.5;
}

bool before(ivec2 a, ivec2 b) {
	return a.y < b.y || (a.y == b.y && a.x < b.x);
}

mat2 rotation(float a) {
	return mat2(cos(a), sin(a), -sin(a), cos(a));
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

// -1 for x means "the end of that line": a scan along it, so only
// worked out by the texels that need it.
int resolveX(ivec2 p) {
	return p.x < 0 ? lineLength(p.y) : p.x;
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

// Where the character after the cursor starts - past a tab's room, or
// the end of the line if that's all there is after it.
int nextStart(ivec2 cur) {
	for (int x = cur.x + 1; x <= cur.x + 16; ++x) {
		if (codeAt(ivec2(x, cur.y)) != 0) {
			return x;
		}
	}
	return cur.x + 1;
}

// This frame's typing, as one edit: delete [s, e) - nothing if s == e
// - then insert `ins` at s (0 nothing, 10 a line break, 9 a tab, else
// that character). A selection is what's deleted, so typing over it
// replaces it.
void planEdit(ivec2 cur, ivec2 anchor, int c, out ivec2 s, out ivec2 e, out int ins) {
	s = cur;
	e = cur;
	ins = 0;
	bool selection = anchor != cur;
	ivec2 lo = before(anchor, cur) ? anchor : cur;
	ivec2 hi = before(anchor, cur) ? cur : anchor;
	if ((c >= 32 && c <= 126) || c == 9 || c == 10) {
		if (selection) {
			s = lo;
			e = hi;
		}
		ins = c;
	} else if (c == 8) {
		if (selection) {
			s = lo;
			e = hi;
		} else if (cur.x > 0) {
			s = ivec2(previousStart(cur), cur.y);
		} else if (cur.y > 0) {
			s = ivec2(-1, cur.y - 1);
		}
	} else if (c == 127) {
		if (selection) {
			s = lo;
			e = hi;
		} else if (codeAt(cur) == 0) {
			// At the end of the line (the cursor never sits in a tab's
			// room): join the next line on.
			e = ivec2(0, cur.y + 1);
		} else {
			e = ivec2(nextStart(cur), cur.y);
		}
	}
}

vec4 deleted(ivec2 p, ivec2 s, ivec2 e) {
	if (s == e || p.y < s.y) {
		return cell(p);
	}
	if (p.y == s.y) {
		int sx = resolveX(s);
		return p.x < sx ? cell(p) : cell(ivec2(p.x - sx + e.x, e.y));
	}
	return cell(ivec2(p.x, p.y + e.y - s.y));
}

vec4 typed(int code) {
	return vec4(float(code) / 255.0, TYPED_COLOR);
}

vec4 edited(ivec2 p, ivec2 s, ivec2 e, int ins) {
	if (ins == 0 || p.y < s.y) {
		return deleted(p, s, e);
	}
	if (ins == 10) {
		if (p.y == s.y) {
			return p.x < s.x ? deleted(p, s, e) : vec4(0.0);
		}
		if (p.y == s.y + 1) {
			return deleted(ivec2(p.x + s.x, s.y), s, e);
		}
		return deleted(p - ivec2(0, 1), s, e);
	}
	if (p.y != s.y || p.x < s.x) {
		return deleted(p, s, e);
	}
	int n = ins == 9 ? TAB_WIDTH - s.x % TAB_WIDTH : 1;
	if (p.x == s.x) {
		return typed(ins);
	}
	return p.x < s.x + n ? vec4(0.0) : deleted(p - ivec2(n, 0), s, e);
}

ivec2 afterEdit(ivec2 s, int ins) {
	ivec2 p = ivec2(resolveX(s), s.y);
	if (ins == 10) {
		return ivec2(0, p.y + 1);
	}
	if (ins == 9) {
		return p + ivec2(TAB_WIDTH - p.x % TAB_WIDTH, 0);
	}
	return ins != 0 ? p + ivec2(1, 0) : p;
}

// Where the arrows, Home, End, PageUp and PageDown move the cursor.
ivec2 navigate(ivec2 cur, int page) {
	int last = res.y - 2;
	if (pressed(37)) { // Left
		if (cur.x > 0) {
			return ivec2(previousStart(cur), cur.y);
		}
		return cur.y > 0 ? ivec2(lineLength(cur.y - 1), cur.y - 1) : cur;
	}
	if (pressed(39)) { // Right
		if (codeAt(cur) == 0) {
			return cur.y < last ? ivec2(0, cur.y + 1) : cur;
		}
		return ivec2(nextStart(cur), cur.y);
	}
	int y;
	if (pressed(38)) { // Up
		y = cur.y - 1;
	} else if (pressed(40)) { // Down
		y = cur.y + 1;
	} else if (pressed(33)) { // PageUp
		y = cur.y - page;
	} else if (pressed(34)) { // PageDown
		y = cur.y + page;
	} else if (pressed(36)) { // Home
		return ivec2(0, cur.y);
	} else if (pressed(35)) { // End
		return ivec2(lineLength(cur.y), cur.y);
	} else {
		return cur;
	}
	y = clamp(y, 0, last);
	return ivec2(min(cur.x, lineLength(y)), y);
}

struct View {
	vec2 pan;
	float zoom;
	float angle;
};

vec2 toPage(vec2 s, View v) {
	vec2 center = 0.5 * vec2(res);
	return rotation(-v.angle) * ((s - center) / v.zoom) + center + v.pan;
}

vec2 toScreen(vec2 p, View v) {
	vec2 center = 0.5 * vec2(res);
	return rotation(v.angle) * (p - center - v.pan) * v.zoom + center;
}

// A touch, y down like the text.
vec2 touch(int i) {
	return vec2(pointers[i].x, float(res.y) - pointers[i].y);
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	res = ivec2(iResolution.xy);
	cellSize = vec2(1.0, CELL_ASPECT) * iResolution.x / COLUMNS;
	ivec2 p = ivec2(fragCoord);

	// A new source (the shader's first frame, a Run, an edit in the
	// editor) is copied afresh, colours and all. A brand new buffer is
	// cleared to opaque black, so its numbers start from scratch.
	int copiedVersion = unpack(meta(1).rg);
	bool fresh = copiedVersion == 0;
	bool copy = copiedVersion != int(sourceTextVersion);
	vec4 m = meta(0);
	ivec2 cur = fresh ? ivec2(0) : ivec2(unpack(m.rg), unpack(m.ba));
	m = meta(2);
	ivec2 anchor = fresh ? cur : ivec2(unpack(m.rg), unpack(m.ba));
	View view = View(vec2(0.0), 1.0, 0.0);
	if (!fresh) {
		view.pan = vec2(float(unpack24(meta(3).rgb) - 8388608),
				float(unpack24(meta(9).rgb) - 8388608)) / 16.0;
		m = meta(4);
		view.zoom = exp2(float(unpack(m.rg) - 32768) / 4096.0);
		view.angle = float(unpack(m.ba)) / 65536.0 * 6.2831853;
	}
	int c = copy ? 0 : typedChar;

	if (p.y < res.y - 1) {
		if (copy) {
			bool inSource = p.x < int(sourceTextSize.x) && p.y < int(sourceTextSize.y);
			fragColor = inSource
					? vec4(texelFetch(sourceText, p, 0).r, texelFetch(sourceTextColor, p, 0).rgb)
					: vec4(0.0);
			return;
		}
		ivec2 s, e;
		int ins;
		planEdit(cur, anchor, c, s, e, ins);
		fragColor = edited(p, s, e, ins);
		return;
	}

	// The top row: only the first few texels hold anything.
	int index = res.x - 1 - p.x;
	if (index > 9) {
		fragColor = vec4(0.0);
		return;
	}

	// The cursor and selection after this frame's keys.
	int page = max(1, int(float(res.y) * TYPING_AREA / (cellSize.y * view.zoom)));
	ivec2 s, e;
	int ins;
	planEdit(cur, anchor, c, s, e, ins);
	ivec2 next = cur;
	ivec2 nextAnchor = anchor;
	bool moved = s != e || ins != 0;
	if (moved) {
		next = afterEdit(s, ins);
		nextAnchor = next;
	} else if (!copy) {
		next = navigate(cur, page);
		moved = next != cur;
		nextAnchor = held(16) ? anchor : next;
	}

	// The view: one finger drags it, two pinch, pan and turn it.
	int count = min(pointerCount, 2);
	m = meta(5);
	int lastCount = fresh ? 0 : byte(m.r);
	float travel = fresh ? 0.0 : float(unpack(m.gb));
	m = meta(6);
	vec2 last0 = vec2(unpack(m.rg), unpack(m.ba));
	m = meta(7);
	vec2 last1 = vec2(unpack(m.rg), unpack(m.ba));
	vec2 now0 = touch(0);
	vec2 now1 = touch(1);
	if (count > 0 && count == lastCount) {
		if (count == 1) {
			view.pan -= rotation(-view.angle) * (now0 - last0) / view.zoom;
			travel += length(now0 - last0);
		} else {
			vec2 d0 = last1 - last0;
			vec2 d1 = now1 - now0;
			if (length(d0) > 1.0 && length(d1) > 1.0) {
				vec2 grip = toPage(0.5 * (last0 + last1), view);
				view.zoom = clamp(view.zoom * length(d1) / length(d0), MIN_ZOOM, MAX_ZOOM);
				view.angle = mod(view.angle + atan(d1.y, d1.x) - atan(d0.y, d0.x),
						6.2831853);
				vec2 center = 0.5 * vec2(res);
				view.pan = grip - center -
						rotation(-view.angle) * (0.5 * (now0 + now1) - center) / view.zoom;
			}
		}
	}
	if (count != 1 || lastCount > 1) {
		// Two fingers, or none: never a tap.
		travel = count == 0 && lastCount == 1 ? travel : TAP_SLOP + 1.0;
	}
	if (count == 1 && lastCount == 0) {
		travel = 0.0;
	}
	// A tap - one finger, let go having hardly moved - puts the cursor
	// where it was, on a character's start, or extends the selection
	// there with Shift held.
	if (!copy && count == 0 && lastCount == 1 && travel <= TAP_SLOP && !moved) {
		vec2 at = floor(toPage(last0, view) / cellSize);
		int y = clamp(int(at.y), 0, res.y - 2);
		int x = clamp(int(at.x), 0, lineLength(y));
		for (int i = 0; i < 16; ++i) {
			if (x <= 0 || codeAt(ivec2(x, y)) != 0 || x >= lineLength(y)) {
				break;
			}
			--x;
		}
		next = ivec2(x, y);
		nextAnchor = held(16) ? anchor : next;
	}
	// PageUp/PageDown move the view a page too; then typing or moving
	// the cursor brings it back into view.
	if (!copy && c == 0 && (pressed(33) || pressed(34))) {
		view.pan.y += float(pressed(34) ? page : -page) * cellSize.y;
	}
	if (moved) {
		vec2 at = toScreen((vec2(next) + 0.5) * cellSize, view);
		// A few characters' room each side - but never more than the
		// cursor's own column, so the start of a line isn't pushed right.
		vec2 margin = vec2(min(3.0, float(next.x)) * cellSize.x, 1.5 * cellSize.y) *
				view.zoom;
		vec2 hi = max(vec2(float(res.x), float(res.y) * TYPING_AREA) - margin, margin);
		view.pan -= rotation(-view.angle) * (clamp(at, margin, hi) - at) / view.zoom;
	}
	// No further than three screens past the text.
	vec2 room = 3.0 * vec2(res);
	view.pan = clamp(view.pan, -room,
			room + vec2(float(MAX_COLUMNS), max(sourceTextSize.y, 1.0)) * cellSize);

	if (index == 0) {
		fragColor = vec4(pack(next.x), pack(next.y));
	} else if (index == 1) {
		fragColor = vec4(pack(int(sourceTextVersion)), 0.0, 0.0);
	} else if (index == 2) {
		fragColor = vec4(pack(nextAnchor.x), pack(nextAnchor.y));
	} else if (index == 3) {
		fragColor = vec4(pack24(int(floor(view.pan.x * 16.0 + 0.5)) + 8388608), 0.0);
	} else if (index == 9) {
		fragColor = vec4(pack24(int(floor(view.pan.y * 16.0 + 0.5)) + 8388608), 0.0);
	} else if (index == 4) {
		fragColor = vec4(pack(int(floor(log2(view.zoom) * 4096.0 + 0.5)) + 32768),
				pack(int(floor(view.angle / 6.2831853 * 65536.0)) % 65536));
	} else if (index == 5) {
		fragColor = vec4(float(count) / 255.0, pack(int(min(travel, 60000.0))), 0.0);
	} else if (index == 6) {
		fragColor = vec4(pack(int(max(now0.x, 0.0))), pack(int(max(now0.y, 0.0))));
	} else if (index == 7) {
		fragColor = vec4(pack(int(max(now1.x, 0.0))), pack(int(max(now1.y, 0.0))));
	} else {
		fragColor = vec4(pack(int(cellSize.x * 16.0)), pack(int(cellSize.y * 16.0)));
	}
}
