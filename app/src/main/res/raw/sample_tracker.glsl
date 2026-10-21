// Tracker: a little demoscene-style sequencer. Eight tracks of 32 steps
// play top to bottom at BPM, looping, on the Soundboard's instruments.
// - Tap an empty cell to put that track's instrument there; tap a note
//   to select it.
// - Hold a cell to edit it: change the note, its octave, or the
//   instrument in the panel at the bottom.
// - Tap a note twice to arm it, then tap the red DEL to delete it.
// - The button top left plays and pauses.
// To keep a song, More Options > Save Buffer as Texture, Buffer A, named
// trackerSong: it's loaded every time this starts, instead of the demo.
//
// Buffer A holds the song, Buffer B what you're doing, Buffer C is the
// Sound pass; this pass draws.

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;
uniform sampler2D font;

#define BPM 120.0
const int TRACKS = 8;
const int STEPS = 32;

// --- Shared layout (the same in every pass) ---
vec4 gridRect(vec2 res) {
	return vec4(res.x * 0.10, res.y * 0.30, res.x * 0.99, res.y * 0.90);
}

vec4 cellRect(int track, int step, vec2 res) {
	vec4 g = gridRect(res);
	float w = (g.z - g.x) / float(TRACKS);
	float h = (g.w - g.y) / float(STEPS);
	float x = g.x + float(track) * w;
	float y = g.w - float(step + 1) * h;
	return vec4(x, y, x + w, y + h);
}

vec4 playRect(vec2 res) {
	float s = res.y * 0.06;
	return vec4(res.x * 0.04, res.y * 0.92, res.x * 0.04 + s, res.y * 0.92 + s);
}

// The edit panel: row 0 holds the note, - + OC- OC+ and OK; rows 1 and
// 2 the ten instruments.
vec4 panelButton(int row, int col, vec2 res) {
	int cols = row == 0 ? 6 : 5;
	float x0 = res.x * 0.03;
	float x1 = res.x * 0.97;
	float w = (x1 - x0) / float(cols);
	float top = res.y * 0.28 - float(row) * res.y * 0.09;
	return vec4(x0 + float(col) * w + w * 0.05, top - res.y * 0.08,
			x0 + float(col + 1) * w - w * 0.05, top);
}

bool inside(vec2 p, vec4 r) {
	return p.x >= r.x && p.x <= r.z && p.y >= r.y && p.y <= r.w;
}

int byte(float v) {
	return int(v * 255.0 + 0.5);
}

int unpack(vec4 b) {
	ivec4 i = ivec4(b * 255.0 + 0.5);
	return i.x | (i.y << 8) | (i.z << 16) | (i.w << 24);
}
// --- end of shared layout ---

const int LABELS[10] = int[10](363819, 591315, 643112, 525699, 636404,
		642082, 382352, 648363, 132268, 405666);
const vec3 COLORS[10] = vec3[10](
		vec3(0.95, 0.35, 0.3), vec3(0.95, 0.55, 0.3), vec3(0.95, 0.8, 0.35),
		vec3(0.95, 0.45, 0.6), vec3(0.85, 0.4, 0.3), vec3(0.95, 0.65, 0.2),
		vec3(0.4, 0.85, 0.35), vec3(0.3, 0.75, 0.95), vec3(0.65, 0.45, 0.95),
		vec3(0.95, 0.45, 0.8));

int labelChar(int packed, int k) {
	int v = (packed >> (5 * k)) & 31;
	if (v == 0) return 32;
	if (v <= 26) return 64 + v;
	if (v == 27) return 43;
	if (v == 28) return 45;
	if (v == 29) return 35;
	if (v == 30) return 46;
	return 58;
}

// One character `code` at p, in a cell `h` pixels tall from `origin`.
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

float label(vec2 p, vec2 origin, float h, int packed) {
	float v = 0.0;
	for (int k = 0; k < 4; ++k) {
		v = max(v, glyph(labelChar(packed, k), p, origin + vec2(float(k) * h * 0.5, 0.0), h));
	}
	return v;
}

// A note as a tracker shows it: C-4, F#2.
float noteText(vec2 p, vec2 origin, float h, int note) {
	int k = note % 12;
	int letters[12] = int[12](67, 67, 68, 68, 69, 70, 70, 71, 71, 65, 65, 66);
	bool sharp = k == 1 || k == 3 || k == 6 || k == 8 || k == 10;
	float w = h * 0.5;
	return max(max(glyph(letters[k], p, origin, h),
			glyph(sharp ? 35 : 45, p, origin + vec2(w, 0.0), h)),
			glyph(48 + note / 12, p, origin + vec2(2.0 * w, 0.0), h));
}

float hexText(vec2 p, vec2 origin, float h, int v) {
	int hi = v / 16;
	int lo = v % 16;
	return max(glyph(hi < 10 ? 48 + hi : 55 + hi, p, origin, h),
			glyph(lo < 10 ? 48 + lo : 55 + lo, p, origin + vec2(h * 0.5, 0.0), h));
}

float button(vec2 p, vec4 r) {
	vec2 c = 0.5 * (r.xy + r.zw);
	vec2 d = abs(p - c) - 0.5 * (r.zw - r.xy) + 8.0;
	float sdf = length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - 8.0;
	return smoothstep(1.0, -1.0, sdf);
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	vec2 res = iResolution.xy;
	vec2 p = fragCoord;
	vec3 col = vec3(0.05, 0.05, 0.07);

	vec4 sel = texelFetch(iChannel1, ivec2(2, 0), 0);
	int selTrack = byte(sel.r);
	int selStep = byte(sel.g);
	int mode = byte(sel.b);
	bool playing = texelFetch(iChannel1, ivec2(3, 0), 0).r > 0.5;
	int playStep = byte(texelFetch(iChannel1, ivec2(3, 0), 0).g);

	// Header: play/pause, the title, the tempo.
	vec4 pr = playRect(res);
	float pb = button(p, pr);
	col = mix(col, vec3(0.16, 0.18, 0.22), pb);
	vec2 pc = 0.5 * (pr.xy + pr.zw);
	float ps = (pr.z - pr.x) * 0.28;
	vec2 q = (p - pc) / ps;
	float icon = playing
			? float(abs(q.y) < 1.0 && (abs(q.x + 0.5) < 0.25 || abs(q.x - 0.5) < 0.25))
			: float(q.x > -0.6 && q.x < 0.8 && abs(q.y) < (0.8 - q.x) * 0.72);
	col = mix(col, vec3(0.4, 0.95, 0.5), icon * pb);
	float th = res.y * 0.035;
	vec2 to = vec2(pr.z + res.x * 0.06, pr.y + (pr.w - pr.y - th) * 0.5);
	col += vec3(0.9) * label(p, to, th, 99924);
	col += vec3(0.9) * label(p, to + vec2(th * 2.0, 0.0), th, 18603);
	col += vec3(0.5) * label(p, vec2(res.x * 0.62, to.y), th * 0.7, 13826);
	col += vec3(0.5) * glyph(49, p, vec2(res.x * 0.62 + th * 1.6, to.y), th * 0.7);
	col += vec3(0.5) * glyph(50, p, vec2(res.x * 0.62 + th * 1.95, to.y), th * 0.7);
	col += vec3(0.5) * glyph(48, p, vec2(res.x * 0.62 + th * 2.3, to.y), th * 0.7);

	// The grid.
	vec4 g = gridRect(res);
	if (inside(p, vec4(0.0, g.y, g.z, g.w))) {
		float ch = (g.w - g.y) / float(STEPS);
		int step = clamp(int((g.w - p.y) / ch), 0, STEPS - 1);
		float rowShade = step % 4 == 0 ? 0.1 : 0.07;
		col = vec3(rowShade * 0.9, rowShade * 0.9, rowShade * 1.15);
		if (step == playStep && playing) {
			col = vec3(0.12, 0.2, 0.32);
		}
		float th2 = ch * 0.72;
		if (p.x < g.x) {
			col += vec3(0.45) * hexText(p, vec2(res.x * 0.015, g.w - float(step + 1) * ch +
					ch * 0.14), th2, step);
		} else {
			int track = clamp(int((p.x - g.x) / ((g.z - g.x) / float(TRACKS))), 0, TRACKS - 1);
			vec4 r = cellRect(track, step, res);
			vec4 c = texelFetch(iChannel0, ivec2(track, step), 0);
			int note = byte(c.r) - 1;
			int inst = byte(c.g);
			bool selected = track == selTrack && step == selStep && mode > 0;
			if (selected && mode == 3) {
				col = mix(col, vec3(0.85, 0.12, 0.12), button(p, r));
				col = mix(col, vec3(1.0), label(p, vec2(r.x + (r.z - r.x) * 0.5 - th2 * 0.75,
						r.y + ch * 0.14), th2, 12452));
			} else {
				if (selected) {
					col = mix(col, vec3(0.3, 0.3, 0.38), button(p, r));
				}
				vec2 o = vec2(r.x + (r.z - r.x) * 0.06, r.y + ch * 0.14);
				if (note >= 0) {
					vec3 ink = COLORS[clamp(inst, 0, 9)];
					col = mix(col, ink, noteText(p, o, th2, note));
					col = mix(col, ink * 0.6, glyph(48 + inst, p, o + vec2(th2 * 1.6, 0.0), th2));
				} else {
					col = mix(col, vec3(0.22), max(glyph(46, p, o, th2),
							max(glyph(46, p, o + vec2(th2 * 0.5, 0.0), th2),
							glyph(46, p, o + vec2(th2, 0.0), th2))));
				}
			}
			// Track separators.
			if (p.x - r.x < 1.0) {
				col += 0.05;
			}
		}
	}

	// The panel: editing, or how to use it.
	if (p.y < res.y * 0.29) {
		if (mode == 2) {
			vec4 c = texelFetch(iChannel0, ivec2(selTrack, selStep), 0);
			int note = byte(c.r) - 1;
			int inst = byte(c.g);
			for (int b = 0; b < 6; ++b) {
				vec4 r = panelButton(0, b, res);
				float f = button(p, r);
				if (f <= 0.0) {
					continue;
				}
				col = mix(col, b == 0 ? vec3(0.1) : vec3(0.18, 0.2, 0.25), f);
				float lh = (r.w - r.y) * 0.4;
				vec2 lo = vec2(0.5 * (r.x + r.z) - lh, 0.5 * (r.y + r.w) - lh * 0.5);
				if (b == 0) {
					col = mix(col, COLORS[clamp(inst, 0, 9)],
							noteText(p, lo + vec2(lh * 0.25, 0.0), lh, max(note, 0)));
				} else {
					int name = b == 1 ? 28 : b == 2 ? 27 : b == 3 ? 28783 : b == 4 ? 27759 : 367;
					float lx = b <= 2 ? lh * 0.75 : b == 5 ? lh * 0.5 : 0.0;
					col = mix(col, vec3(0.9), label(p, lo + vec2(lx, 0.0), lh, name));
				}
			}
			for (int b = 0; b < 10; ++b) {
				vec4 r = panelButton(1 + b / 5, b % 5, res);
				float f = button(p, r);
				if (f <= 0.0) {
					continue;
				}
				vec3 ic = COLORS[b];
				col = mix(col, b == inst ? ic * 0.75 : ic * 0.18, f);
				float lh = (r.w - r.y) * 0.32;
				col = mix(col, b == inst ? vec3(0.05) : ic,
						label(p, vec2(0.5 * (r.x + r.z) - lh, 0.5 * (r.y + r.w) - lh * 0.5), lh,
						LABELS[b]));
			}
		} else {
			float lh = res.y * 0.025;
			vec2 o = vec2(res.x * 0.08, res.y * 0.2);
			vec3 hint = vec3(0.45);
			col += hint * label(p, o, lh, 16436) + hint * label(p, o + vec2(lh * 2.0, 0.0), lh, 4225);
			o.y -= lh * 2.0;
			col += hint * label(p, o, lh, 143848) + hint * label(p, o + vec2(lh * 2.5, 0.0), lh, 664709);
			o.y -= lh * 2.0;
			col += hint * (label(p, o, lh, 12356) + label(p, o + vec2(lh * 2.0, 0.0), lh, 16436) +
					label(p, o + vec2(lh * 4.0, 0.0), lh, 12452));
		}
	}
	fragColor = vec4(col, 1.0);
}
//// Buffer A
// The song: texel (track, step) is a note - R the note + 1 (0 for none,
// C0 = 1), G the instrument (0-9, the Soundboard's) - as bytes, so it
// saves to an ordinary texture and back exactly. Texel (0, 40) marks a
// song ("ENK"), (1, 40) the last of Buffer B's edits applied.
// It starts from a saved trackerSong texture if there is one (see the
// Image pass), or a little demo.

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;
uniform sampler2D trackerSong;

const int TRACKS = 8;
const int STEPS = 32;
const ivec2 MAGIC = ivec2(0, 40);
const ivec2 APPLIED = ivec2(1, 40);

int byte(float v) {
	return int(v * 255.0 + 0.5);
}

vec4 cellValue(int note, int inst) {
	return note < 0 ? vec4(0.0) : vec4(float(note + 1), float(inst), 0.0, 255.0) / 255.0;
}

// The demo: drums on 0-3, a bass line, and a pentatonic tune.
vec4 demo(int track, int step) {
	int beat = step % 16;
	if (track == 0 && (beat == 0 || beat == 6 || beat == 10)) return cellValue(48, 0);
	if (track == 1 && (beat == 4 || beat == 12)) return cellValue(48, 1);
	if (track == 2 && step % 2 == 0) return cellValue(48, 2);
	if (track == 3 && beat == 12 && step >= 16) return cellValue(48, 3);
	if (track == 4 && step % 4 == 0) {
		int bass[8] = int[8](33, 33, 36, 36, 31, 31, 28, 31);
		return cellValue(bass[step / 4], 5);
	}
	if (track == 5 && step % 2 == 0) {
		int tune[16] = int[16](57, 60, 64, 62, 57, 64, 67, 64, 60, 62, 64, 69, 67, 64, 62, 60);
		return cellValue(tune[step / 2], 6);
	}
	if (track == 6 && step % 8 == 0) {
		int keys[4] = int[4](57, 60, 55, 52);
		return cellValue(keys[step / 8], 7);
	}
	return vec4(0.0);
}

bool isSong(vec4 m) {
	ivec4 b = ivec4(m * 255.0 + 0.5);
	return b.x == 69 && b.y == 78 && b.z == 75;
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	vec4 here = texelFetch(iChannel0, p, 0);
	bool fresh = !isSong(texelFetch(iChannel0, MAGIC, 0)) || iFrame == 0;
	if (fresh) {
		bool saved = isSong(texelFetch(trackerSong, MAGIC, 0));
		if (p == MAGIC) {
			fragColor = vec4(69.0, 78.0, 75.0, 255.0) / 255.0;
		} else if (p == APPLIED) {
			fragColor = texelFetch(iChannel1, ivec2(0, 0), 0);
		} else if (p.x < TRACKS && p.y < STEPS) {
			fragColor = saved ? texelFetch(trackerSong, p, 0) : demo(p.x, p.y);
		} else {
			fragColor = vec4(0.0);
		}
		return;
	}
	// Buffer B's latest edit, unless it's been applied already: texel
	// (0, 0) is its number, (1, 0) the cell and what to put there.
	vec4 command = texelFetch(iChannel1, ivec2(0, 0), 0);
	vec4 applied = texelFetch(iChannel0, APPLIED, 0);
	if (p == APPLIED) {
		fragColor = command;
		return;
	}
	if (byte(command.r) != byte(applied.r)) {
		vec4 d = texelFetch(iChannel1, ivec2(1, 0), 0);
		if (p == ivec2(byte(d.r), byte(d.g))) {
			int kind = byte(command.g);
			fragColor = kind == 2 ? vec4(0.0) : cellValue(byte(d.b) - 1, byte(d.a));
			return;
		}
	}
	fragColor = here;
}
//// Buffer B
// What you're doing, as bytes in row 0:
//   (0) the last edit's number and kind (1 set, 2 delete), for Buffer A
//   (1) its cell and what goes there: track, step, note + 1, instrument
//   (2) the selected cell, and the mode: 0 none, 1 selected, 2 editing,
//       3 armed to delete
//   (3) playing, and the step playing (for drawing)
//   (4) the sample playback started from, (5) where it was paused
//   (6) a touch: down, long press done; (7) where it went down and
//       (8) where it was last frame (x and y as two bytes each); (9) when
//       it went down; (10) the last tap's time, (11) its cell
// Times are `soundSample`.

uniform sampler2D iChannel0; ///buf:B;
uniform sampler2D iChannel1; ///buf:A;
uniform vec3 pointers[10];
uniform int pointerCount;
uniform int soundSample;

#define BPM 120.0
#define SLOP 24.0 // pixels a touch moves and is still a tap
const int TRACKS = 8;
const int STEPS = 32;
const int LONG_PRESS = 20000; // samples: about 0.45 s
const int DOUBLE_TAP = 17000; // samples: about 0.4 s
// Each track's instrument and note for a newly placed note.
const int TRACK_INST[8] = int[8](0, 1, 2, 3, 5, 6, 7, 8);
const int TRACK_NOTE[8] = int[8](48, 48, 48, 48, 33, 57, 57, 69);

// --- Shared layout (the same in every pass) ---
vec4 gridRect(vec2 res) {
	return vec4(res.x * 0.10, res.y * 0.30, res.x * 0.99, res.y * 0.90);
}

vec4 cellRect(int track, int step, vec2 res) {
	vec4 g = gridRect(res);
	float w = (g.z - g.x) / float(TRACKS);
	float h = (g.w - g.y) / float(STEPS);
	float x = g.x + float(track) * w;
	float y = g.w - float(step + 1) * h;
	return vec4(x, y, x + w, y + h);
}

vec4 playRect(vec2 res) {
	float s = res.y * 0.06;
	return vec4(res.x * 0.04, res.y * 0.92, res.x * 0.04 + s, res.y * 0.92 + s);
}

vec4 panelButton(int row, int col, vec2 res) {
	int cols = row == 0 ? 6 : 5;
	float x0 = res.x * 0.03;
	float x1 = res.x * 0.97;
	float w = (x1 - x0) / float(cols);
	float top = res.y * 0.28 - float(row) * res.y * 0.09;
	return vec4(x0 + float(col) * w + w * 0.05, top - res.y * 0.08,
			x0 + float(col + 1) * w - w * 0.05, top);
}

bool inside(vec2 p, vec4 r) {
	return p.x >= r.x && p.x <= r.z && p.y >= r.y && p.y <= r.w;
}

int byte(float v) {
	return int(v * 255.0 + 0.5);
}

int unpack(vec4 b) {
	ivec4 i = ivec4(b * 255.0 + 0.5);
	return i.x | (i.y << 8) | (i.z << 16) | (i.w << 24);
}
// --- end of shared layout ---

vec4 pack(int v) {
	return vec4(float(v & 255), float((v >> 8) & 255), float((v >> 16) & 255),
			float((v >> 24) & 255)) / 255.0;
}

vec4 bytes(int a, int b, int c, int d) {
	return vec4(float(a), float(b), float(c), float(d)) / 255.0;
}

vec4 packPoint(vec2 v) {
	ivec2 i = ivec2(clamp(v, vec2(0.0), vec2(65535.0)));
	return bytes(i.x & 255, i.x >> 8, i.y & 255, i.y >> 8);
}

vec2 unpackPoint(vec4 b) {
	ivec4 i = ivec4(b * 255.0 + 0.5);
	return vec2(float(i.x | (i.y << 8)), float(i.z | (i.w << 8)));
}

vec4 last(int x) {
	return iFrame == 0 ? vec4(0.0) : texelFetch(iChannel0, ivec2(x, 0), 0);
}

// The grid cell under p, or (-1, -1).
ivec2 cellAt(vec2 p, vec2 res) {
	vec4 g = gridRect(res);
	if (!inside(p, g)) {
		return ivec2(-1);
	}
	int track = int((p.x - g.x) / ((g.z - g.x) / float(TRACKS)));
	int step = int((g.w - p.y) / ((g.w - g.y) / float(STEPS)));
	return ivec2(clamp(track, 0, TRACKS - 1), clamp(step, 0, STEPS - 1));
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	if (p.y > 0 || p.x > 11) {
		fragColor = vec4(0.0);
		return;
	}
	vec2 res = iResolution.xy;
	int stepSamples = int(44100.0 * 15.0 / BPM);

	// Everything from last frame.
	vec4 command = last(0);
	int serial = byte(command.r);
	vec4 edit = last(1);
	vec4 s = last(2);
	ivec2 sel = ivec2(byte(s.r), byte(s.g));
	int mode = byte(s.b);
	bool playing = last(3).r > 0.5;
	int playStart = unpack(last(4));
	int pausedAt = unpack(last(5));
	bool wasDown = last(6).r > 0.5;
	bool longDone = last(6).g > 0.5;
	vec2 downAt = unpackPoint(last(7));
	vec2 lastAt = unpackPoint(last(8));
	int downTime = unpack(last(9));
	int tapTime = unpack(last(10));
	ivec2 tapCell = ivec2(byte(last(11).r), byte(last(11).g)) - 1;

	bool down = pointerCount > 0;
	vec2 at = down ? pointers[0].xy : lastAt;

	// What the touch did this frame: 1 tap, 2 long press, 3 double tap.
	int gesture = 0;
	vec2 where = downAt;
	if (down && !wasDown) {
		downAt = at;
		downTime = soundSample;
		longDone = false;
	} else if (down && !longDone && distance(at, downAt) < SLOP &&
			soundSample - downTime > LONG_PRESS) {
		gesture = 2;
		longDone = true;
	} else if (!down && wasDown && !longDone && distance(lastAt, downAt) < SLOP) {
		gesture = 1;
		ivec2 c = cellAt(downAt, res);
		if (c.x >= 0 && c == tapCell && soundSample - tapTime < DOUBLE_TAP) {
			gesture = 3;
		}
		tapTime = soundSample;
		tapCell = c;
	}

	// Acting on it.
	int kind = 0;
	ivec4 put = ivec4(byte(edit.r), byte(edit.g), byte(edit.b), byte(edit.a));
	if (gesture != 0) {
		ivec2 c = cellAt(where, res);
		vec4 cv = c.x >= 0 ? texelFetch(iChannel1, c, 0) : vec4(0.0);
		int note = byte(cv.r) - 1;
		bool empty = note < 0;
		if (gesture == 1 && inside(where, playRect(res))) {
			if (playing) {
				pausedAt = soundSample - playStart;
			} else {
				playStart = soundSample - pausedAt;
			}
			playing = !playing;
		} else if (mode == 2 && where.y < res.y * 0.29) {
			// The edit panel.
			vec4 sv = texelFetch(iChannel1, sel, 0);
			int n = max(byte(sv.r) - 1, 0);
			int inst = byte(sv.g);
			for (int b = 1; b < 6; ++b) {
				if (inside(where, panelButton(0, b, res))) {
					if (b == 5) {
						mode = 1;
					} else {
						n = clamp(n + (b == 1 ? -1 : b == 2 ? 1 : b == 3 ? -12 : 12), 0, 95);
						kind = 1;
					}
				}
			}
			for (int b = 0; b < 10; ++b) {
				if (inside(where, panelButton(1 + b / 5, b % 5, res))) {
					inst = b;
					kind = 1;
				}
			}
			if (kind == 1) {
				put = ivec4(sel, n + 1, inst);
			}
		} else if (c.x < 0) {
			mode = 0;
		} else if (mode == 3 && c == sel) {
			// The red DEL - checked first, as tapping it quickly would
			// otherwise count as another double tap.
			kind = 2;
			put = ivec4(c, 0, 0);
			mode = 0;
		} else if (gesture == 3 && !empty) {
			sel = c;
			mode = 3;
		} else {
			if (empty) {
				kind = 1;
				put = ivec4(c, TRACK_NOTE[c.x] + 1, TRACK_INST[c.x]);
			}
			sel = c;
			mode = gesture == 2 ? 2 : 1;
		}
	}
	if (kind != 0) {
		serial = serial % 255 + 1;
	}

	int position = playing ? soundSample - playStart : pausedAt;
	int step = (max(position, 0) / stepSamples) % STEPS;

	if (p.x == 0) {
		fragColor = kind != 0 ? bytes(serial, kind, 0, 0) : command;
	} else if (p.x == 1) {
		fragColor = bytes(put.x, put.y, put.z, put.w);
	} else if (p.x == 2) {
		fragColor = bytes(sel.x, sel.y, mode, 0);
	} else if (p.x == 3) {
		fragColor = bytes(playing ? 255 : 0, step, 0, 0);
	} else if (p.x == 4) {
		fragColor = pack(playStart);
	} else if (p.x == 5) {
		fragColor = pack(pausedAt);
	} else if (p.x == 6) {
		fragColor = bytes(down ? 255 : 0, longDone ? 255 : 0, 0, 0);
	} else if (p.x == 7) {
		fragColor = packPoint(downAt);
	} else if (p.x == 8) {
		fragColor = packPoint(at);
	} else if (p.x == 9) {
		fragColor = pack(downTime);
	} else if (p.x == 10) {
		fragColor = pack(tapTime);
	} else {
		fragColor = bytes(tapCell.x + 1, tapCell.y + 1, 0, 0);
	}
}
//// Buffer C
// The Sound pass: each track plays its latest note (up to 8 steps back)
// on the Soundboard's instruments, from where Buffer B says playback is.

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D iChannel1; ///buf:B;

#define BPM 120.0
#define VOLUME 0.3
const int TRACKS = 8;
const int STEPS = 32;
const int RING = 8; // steps a note can ring for
const float TAU = 6.2831853;
const float SR = 44100.0;

int byte(float v) {
	return int(v * 255.0 + 0.5);
}

int unpack(vec4 b) {
	ivec4 i = ivec4(b * 255.0 + 0.5);
	return i.x | (i.y << 8) | (i.z << 16) | (i.w << 24);
}

float hash(int n) {
	n = (n << 13) ^ n;
	n = n * (n * n * 15731 + 789221) + 1376312589;
	return float(n & 0x7fffffff) / 1073741823.5 - 1.0;
}

float smoothNoise(float t, float rate) {
	float x = t * rate;
	float i = floor(x);
	float f = fract(x);
	f = f * f * (3.0 - 2.0 * f);
	return mix(hash(int(i)), hash(int(i) + 1), f);
}

// The Soundboard's instruments; t is seconds since the note.
float kick(float t, float k) {
	float phase = 45.0 * k * t + (160.0 - 45.0) * k / 25.0 * (1.0 - exp(-25.0 * t));
	return sin(TAU * phase) * exp(-5.0 * t) * 1.4 + smoothNoise(t, 9000.0) * exp(-300.0 * t) * 0.4;
}

float snare(float t, int samp) {
	float body = sin(TAU * (180.0 * t + 2.0 * (1.0 - exp(-30.0 * t)))) * exp(-18.0 * t) * 0.7;
	float white = hash(samp) - 0.5 * hash(samp - 1);
	float band = smoothNoise(t, 7000.0) * sin(TAU * 2800.0 * t);
	return (body + (white * 0.55 + band * 0.8) * (exp(-11.0 * t) + 0.6 * exp(-60.0 * t))) * 1.1;
}

float metal(float t) {
	float f[6] = float[6](205.3, 304.4, 369.6, 522.7, 540.0, 800.0);
	float v = 0.0;
	for (int k = 0; k < 6; ++k) {
		v += sign(sin(TAU * f[k] * 2.1 * t));
	}
	return v / 6.0;
}

float hats(float t, int samp) {
	float hp = metal(t) - metal(t - 1.0 / SR);
	return (hp * 0.9 + (hash(samp) - hash(samp - 1)) * 0.35) * exp(-28.0 * t) * 1.3;
}

float clap(float t, int samp) {
	float band = smoothNoise(t, 3000.0) * sin(TAU * 1500.0 * t) + (hash(samp) - hash(samp - 1)) * 0.25;
	float env = 0.0;
	for (int k = 0; k < 3; ++k) {
		float s = t - float(k) * 0.011;
		env += s >= 0.0 ? exp(-180.0 * s) : 0.0;
	}
	return band * (env + (t > 0.03 ? exp(-14.0 * (t - 0.03)) * 0.8 : 0.0)) * 1.2;
}

float tom(float t, float k) {
	float phase = 110.0 * k * t + 110.0 * k / 12.0 * (1.0 - exp(-12.0 * t));
	return sin(TAU * phase) * exp(-6.0 * t) * 0.9;
}

float voice(int inst, int note, float t, int samp) {
	// Drums tune from C4; the rest are equal temperament, A4 = 440 Hz.
	float k = exp2(float(note - 48) / 12.0);
	float f = 440.0 * exp2(float(note - 57) / 12.0);
	float v = 0.0;
	if (inst == 0) return kick(t, k);
	if (inst == 1) return snare(t, samp);
	if (inst == 2) return hats(t, samp);
	if (inst == 3) return clap(t, samp);
	if (inst == 4) return tom(t, k);
	if (inst == 5) {
		for (int h = 1; h <= 6; ++h) {
			v += sin(TAU * f * float(h) * t) / float(h) * exp(-float(h) * 0.6 * t);
		}
		return v * (0.55 + 0.45 * exp(-5.0 * t));
	}
	if (inst == 6) {
		for (int h = 1; h <= 8; ++h) {
			v += sin(TAU * f * float(h) * t) / float(h) * exp(-t * (2.0 + float(h) * 1.6));
		}
		return v * 0.9;
	}
	if (inst == 7) {
		return sin(TAU * f * t + 1.4 * exp(-4.0 * t) * sin(TAU * f * t)) * exp(-1.4 * t) * 0.6;
	}
	if (inst == 8) {
		for (int h = 1; h <= 7; h += 2) {
			v += sin(TAU * f * float(h) * t) / float(h);
		}
		return v * 0.32 * exp(-1.5 * t);
	}
	return sin(TAU * f * t + 2.2 * exp(-2.0 * t) * sin(TAU * f * 3.5 * t)) * exp(-2.2 * t) * 0.45;
}

vec2 mainSound(int samp, float time) {
	vec4 b3 = texelFetch(iChannel1, ivec2(3, 0), 0);
	if (b3.r < 0.5) {
		return vec2(0.0);
	}
	int position = samp - unpack(texelFetch(iChannel1, ivec2(4, 0), 0));
	if (position < 0) {
		return vec2(0.0);
	}
	int stepSamples = int(SR * 15.0 / BPM);
	int absStep = position / stepSamples;
	vec2 sum = vec2(0.0);
	for (int track = 0; track < TRACKS; ++track) {
		for (int back = 0; back < RING; ++back) {
			int s = absStep - back;
			if (s < 0) {
				break;
			}
			vec4 c = texelFetch(iChannel0, ivec2(track, s % STEPS), 0);
			int note = byte(c.r) - 1;
			if (note < 0) {
				continue;
			}
			float t = float(position - s * stepSamples) / SR;
			// A few milliseconds' fade in, so notes cutting each other
			// off don't click - and out over the last step it can ring.
			float age = float(position - s * stepSamples) / float(stepSamples);
			float v = voice(byte(c.g), note, t, samp) * smoothstep(0.0, 0.003, t) *
					(1.0 - smoothstep(float(RING - 1), float(RING), age));
			float pan = (float(track) - 3.5) * 0.08;
			sum += v * vec2(1.0 - pan, 1.0 + pan);
			break;
		}
	}
	return tanh(sum * VOLUME);
}
