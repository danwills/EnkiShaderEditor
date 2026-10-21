// Soundboard: a grid of pads to play - drums along the top, then five
// tuned instruments, each across the notes of A minor pentatonic (A C D
// E G), all in equal temperament with A = 440 Hz, so anything played
// together is in tune. Hold a pad and drag up or down (past a little
// dead zone) to bend its pitch while it plays. Several fingers at once
// work too.
//
// Buffer A keeps track of the pads; Buffer B is the Sound pass (its
// mainSound() makes the sound); this pass draws.

uniform sampler2D iChannel0; ///buf:A;
uniform sampler2D font;

const int COLS = 5;
const int ROWS = 6;
const int PADS = COLS * ROWS;

// The same layout in every pass: pad i's rectangle, in pixels.
vec4 padRect(int i, vec2 res) {
	int c = i % COLS;
	int r = i / COLS;
	float top = res.y * 0.86;
	float bottom = res.y * 0.05;
	float left = res.x * 0.03;
	float right = res.x * 0.97;
	float w = (right - left) / float(COLS);
	float h = (top - bottom) / float(ROWS);
	float x0 = left + float(c) * w;
	float y1 = top - float(r) * h;
	return vec4(x0 + w * 0.05, y1 - h + h * 0.05, x0 + w * 0.95, y1 - h * 0.05);
}

// Labels: four characters packed into an int, five bits each - see
// tools/samples/pack_label.py.
const int KICK = 363819;
const int SNAR = 591315;
const int HATS = 643112;
const int CLAP = 525699;
const int TOMS = 636404;
const int BASS = 642082;
const int PLUK = 382352;
const int KEYS = 648363;
const int LEAD = 132268;
const int BELL = 405666;

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

// One character of the built-in font at f (0-1 across its cell), with
// an edge `w` wide - fixed rather than from fwidth(), which isn't
// defined in the branches this is called from.
float glyph(int code, vec2 f, float w) {
	vec2 cell = vec2(float(code % 16), float(15 - code / 16));
	// Kept off the cell's edges, where the neighbouring glyphs bleed in.
	vec2 g = clamp(vec2(0.5 + (f.x - 0.5) * 0.48, f.y), 0.03, 0.97);
	float d = textureLod(font, (cell + g) / 16.0, 0.0).r;
	return smoothstep(0.5 - w, 0.5 + w, d);
}

// Up to four characters, `packed` or the codes given, from `origin`.
float label(vec2 p, vec2 origin, float h, int packed) {
	vec2 q = (p - origin) / vec2(h * 0.5, h);
	int k = int(floor(q.x));
	if (q.y < 0.0 || q.y > 1.0 || k < 0 || k > 3) {
		return 0.0;
	}
	return glyph(labelChar(packed, k), vec2(fract(q.x), q.y), 3.5 / h);
}

float chars2(vec2 p, vec2 origin, float h, int a, int b) {
	vec2 q = (p - origin) / vec2(h * 0.5, h);
	int k = int(floor(q.x));
	if (q.y < 0.0 || q.y > 1.0 || k < 0 || k > 1) {
		return 0.0;
	}
	return glyph(k == 0 ? a : b, vec2(fract(q.x), q.y), 3.5 / h);
}

const vec3 ROW_COLORS[6] = vec3[6](
		vec3(0.95, 0.35, 0.3), vec3(0.95, 0.65, 0.2), vec3(0.4, 0.85, 0.35),
		vec3(0.3, 0.75, 0.95), vec3(0.65, 0.45, 0.95), vec3(0.95, 0.45, 0.8));

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	vec2 res = iResolution.xy;
	vec3 col = mix(vec3(0.05, 0.05, 0.08), vec3(0.09, 0.08, 0.12), fragCoord.y / res.y);

	// The title, and how to bend.
	float th = res.y * 0.035;
	col += vec3(0.9) * label(fragCoord, vec2(res.x * 0.5 - th * 2.5, res.y * 0.92), th, 480755);
	col += vec3(0.9) * label(fragCoord, vec2(res.x * 0.5 - th * 0.5, res.y * 0.92), th, 48196);
	col += vec3(0.9) * label(fragCoord, vec2(res.x * 0.5 + th * 1.5, res.y * 0.92), th, 146);
	float sh = res.y * 0.018;
	col += vec3(0.5) * label(fragCoord, vec2(res.x * 0.5 - sh * 3.0, res.y * 0.885), sh, 143848);
	col += vec3(0.5) * label(fragCoord, vec2(res.x * 0.5 - sh * 0.75, res.y * 0.885), sh, 230980);
	col += vec3(0.5) * label(fragCoord, vec2(res.x * 0.5 + sh * 1.5, res.y * 0.885), sh, 145570);

	for (int i = 0; i < PADS; ++i) {
		vec4 r = padRect(i, res);
		if (fragCoord.x < r.x || fragCoord.x > r.z || fragCoord.y < r.y || fragCoord.y > r.w) {
			continue;
		}
		vec4 state = texelFetch(iChannel0, ivec2(i, 0), 0);
		bool held = state.r > 0.5;
		float bend = state.g;
		int row = i / COLS;
		int c = i % COLS;
		vec3 base = ROW_COLORS[row];
		vec2 center = 0.5 * (r.xy + r.zw);
		vec2 size = r.zw - r.xy;
		// A rounded pad, lit while held.
		vec2 d = abs(fragCoord - center) - size * 0.5 + 12.0;
		float sdf = length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - 12.0;
		float fill = smoothstep(1.0, -1.0, sdf);
		float edge = smoothstep(2.5, 0.0, abs(sdf + 2.0));
		vec3 pad = base * (held ? 0.85 : 0.22) + edge * base * 0.6;
		// A bend shows as a bar from the middle, up or down.
		if (held && abs(bend) > 0.01) {
			float barTop = center.y + bend / 12.0 * size.y * 0.45;
			if (abs(fragCoord.x - center.x) < size.x * 0.05 &&
					fragCoord.y > min(center.y, barTop) && fragCoord.y < max(center.y, barTop)) {
				pad = vec3(1.0);
			}
		}
		col = mix(col, pad, fill);

		// Its name, or instrument and note.
		float lh = size.y * 0.2;
		vec3 ink = held ? vec3(0.05) : base * 1.2;
		if (row == 0) {
			int name = c == 0 ? KICK : c == 1 ? SNAR : c == 2 ? HATS : c == 3 ? CLAP : TOMS;
			col = mix(col, ink, label(fragCoord, center - vec2(lh, lh * 0.5), lh, name));
		} else {
			int name = row == 1 ? BASS : row == 2 ? PLUK : row == 3 ? KEYS : row == 4 ? LEAD : BELL;
			float sl = lh * 0.85;
			col = mix(col, ink * 0.85, label(fragCoord,
					vec2(center.x - sl, r.y + size.y * 0.7), sl, name));
			// A C D E G, octave from the instrument's: bass A1, pluck and
			// keys A3, lead A4, bell A5 - the octave goes up at C.
			int letter = c == 0 ? 65 : c == 1 ? 67 : c == 2 ? 68 : c == 3 ? 69 : 71;
			int octave = (row == 1 ? 1 : row == 4 ? 4 : row == 5 ? 5 : 3) + (c >= 1 ? 1 : 0);
			col = mix(col, ink, chars2(fragCoord, center - vec2(lh * 0.55, lh * 0.9), lh * 1.1,
					letter, 48 + octave));
		}
	}
	fragColor = vec4(col, 1.0);
}
//// Buffer A
// The pads' state, for the Sound pass (Buffer B) and drawing. Pad i's
// texels, in column i:
//   row 0: held, bend (semitones, smoothed)
//   row 1: the sample it was pressed at
//   row 2: the sample it was let go at
//   row 3: how far its oscillators have got, in samples of unbent time
//          x 16 ("bent time": a bend speeds them up or slows them down)
//   row 4: the sample row 3 is as of
//   row 5: the pitch ratio row 3 runs at from then on, x 65536
// Samples come from `soundSample`, the next sample the Sound pass will
// make - so a note starts exactly when pressed. Big numbers are kept as
// four bytes (see pack()), exact in any buffer format.
// Rows 6 and 7, slot j: a touch - in use, its pad, where it went down
// (y) - and where it was last frame, to follow a finger even when
// another one lifts and the touches renumber.

uniform sampler2D iChannel0; ///buf:A;
uniform vec3 pointers[10];
uniform int pointerCount;
uniform int soundSample;

const int COLS = 5;
const int ROWS = 6;
const int PADS = COLS * ROWS;
const int SLOTS = 10;
#define DEAD_ZONE 20.0 // pixels a finger moves before it bends
#define PIXELS_PER_SEMITONE 45.0
#define MAX_BEND 12.0
#define BEND_SMOOTHING 0.35 // of the way to the finger, each frame

vec4 padRect(int i, vec2 res) {
	int c = i % COLS;
	int r = i / COLS;
	float top = res.y * 0.86;
	float bottom = res.y * 0.05;
	float left = res.x * 0.03;
	float right = res.x * 0.97;
	float w = (right - left) / float(COLS);
	float h = (top - bottom) / float(ROWS);
	float x0 = left + float(c) * w;
	float y1 = top - float(r) * h;
	return vec4(x0 + w * 0.05, y1 - h + h * 0.05, x0 + w * 0.95, y1 - h * 0.05);
}

int padAt(vec2 p, vec2 res) {
	for (int i = 0; i < PADS; ++i) {
		vec4 r = padRect(i, res);
		if (p.x >= r.x && p.x <= r.z && p.y >= r.y && p.y <= r.w) {
			return i;
		}
	}
	return -1;
}

vec4 last(int x, int y) {
	return iFrame == 0 ? vec4(0.0) : texelFetch(iChannel0, ivec2(x, y), 0);
}

vec4 pack(int v) {
	return vec4(float(v & 255), float((v >> 8) & 255), float((v >> 16) & 255),
			float((v >> 24) & 255)) / 255.0;
}

int unpack(vec4 b) {
	ivec4 i = ivec4(b * 255.0 + 0.5);
	return i.x | (i.y << 8) | (i.z << 16) | (i.w << 24);
}

// Touch slot j this frame: (in use, pad, down y, bend).
vec4 slot(int j) {
	vec4 was = last(j, 6);
	vec2 wasAt = last(j, 7).xy;
	if (j >= pointerCount) {
		return vec4(0.0, -1.0, 0.0, 0.0);
	}
	vec2 at = pointers[j].xy;
	bool same = was.r > 0.5 && distance(at, wasAt) < 200.0;
	float pad = same ? was.g : float(padAt(at, iResolution.xy));
	float downY = same ? was.b : at.y;
	float dy = at.y - downY;
	float bend = clamp(sign(dy) * max(abs(dy) - DEAD_ZONE, 0.0) / PIXELS_PER_SEMITONE,
			-MAX_BEND, MAX_BEND);
	return vec4(1.0, pad, downY, bend);
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	if (p.y == 6 && p.x < SLOTS) {
		fragColor = vec4(slot(p.x).rgb, 0.0);
		return;
	}
	if (p.y == 7 && p.x < SLOTS) {
		fragColor = vec4(p.x < pointerCount ? pointers[p.x].xy : vec2(0.0), 0.0, 0.0);
		return;
	}
	if (p.y > 5 || p.x >= PADS) {
		fragColor = vec4(0.0);
		return;
	}
	int i = p.x;
	bool held = false;
	float target = 0.0;
	for (int j = 0; j < SLOTS; ++j) {
		vec4 s = slot(j);
		if (s.r > 0.5 && int(s.g) == i) {
			held = true;
			target = s.a;
		}
	}
	vec4 was = last(i, 0);
	bool wasHeld = was.r > 0.5;
	bool pressedNow = held && !wasHeld;
	// The bend eases towards the finger rather than jumping with it.
	float bend = pressedNow ? target : held ? mix(was.g, target, BEND_SMOOTHING) : was.g;
	float ratio = exp2(bend / 12.0);

	int pressed = pressedNow ? soundSample : unpack(last(i, 1));
	// Bent time carries on from where it was, at the ratio it was
	// running at - so the phase never jumps, however the pitch moves.
	int asOf = unpack(last(i, 4));
	float lastRatio = float(unpack(last(i, 5))) / 65536.0;
	int bent = pressedNow ? 0 :
			unpack(last(i, 3)) + int(float(soundSample - asOf) * lastRatio * 16.0 + 0.5);

	if (p.y == 0) {
		fragColor = vec4(held ? 1.0 : 0.0, bend, 0.0, 0.0);
	} else if (p.y == 1) {
		fragColor = pack(pressed);
	} else if (p.y == 2) {
		fragColor = !held && wasHeld ? pack(soundSample) : last(i, 2);
	} else if (p.y == 3) {
		fragColor = pack(bent);
	} else if (p.y == 4) {
		fragColor = pack(soundSample);
	} else {
		fragColor = pack(int(ratio * 65536.0 + 0.5));
	}
}
//// Buffer B
// The Sound pass: every pad that's held, or still ringing out, played
// from Buffer A's state. Tuned pads are A minor pentatonic in equal
// temperament from A = 440 Hz. Oscillators run on "bent time" (see
// Buffer A), so a bend glides without clicks; envelopes on real time.

uniform sampler2D iChannel0; ///buf:A;

const int COLS = 5;
const int PADS = COLS * 6;
const float TAU = 6.2831853;
const float SR = 44100.0;
#define RELEASE 6.0 // how fast a let-go note fades
#define VOLUME 0.35

int unpack(vec4 b) {
	ivec4 i = ivec4(b * 255.0 + 0.5);
	return i.x | (i.y << 8) | (i.z << 16) | (i.w << 24);
}

float hash(int n) {
	n = (n << 13) ^ n;
	n = n * (n * n * 15731 + 789221) + 1376312589;
	return float(n & 0x7fffffff) / 1073741823.5 - 1.0;
}

// Smooth noise, about `rate` changes a second - low-passed white noise.
float smoothNoise(float t, float rate) {
	float x = t * rate;
	float i = floor(x);
	float f = fract(x);
	f = f * f * (3.0 - 2.0 * f);
	return mix(hash(int(i)), hash(int(i) + 1), f);
}

// Equal temperament: semitones from A4 to Hz.
float pitch(float semitones) {
	return 440.0 * exp2(semitones / 12.0);
}

// t: seconds since the hit, for envelopes; b: bent time, for pitch.
float kick(float t, float b) {
	float phase = 45.0 * b + (160.0 - 45.0) / 25.0 * (1.0 - exp(-25.0 * b));
	float click = smoothNoise(t, 9000.0) * exp(-300.0 * t) * 0.4;
	return sin(TAU * phase) * exp(-5.0 * t) * 1.4 + click;
}

// A tight body that drops in pitch, under a burst of bright noise -
// ring-modulated smooth noise is band-passed noise, centred on the
// carrier.
float snare(float t, float b, int samp) {
	float body = sin(TAU * (180.0 * b + 60.0 / 30.0 * (1.0 - exp(-30.0 * b)))) *
			exp(-18.0 * t) * 0.7;
	float white = hash(samp) - 0.5 * hash(samp - 1);
	float band = smoothNoise(t, 7000.0) * sin(TAU * 2800.0 * t);
	float noise = (white * 0.55 + band * 0.8) * (exp(-11.0 * t) + 0.6 * exp(-60.0 * t));
	return (body + noise) * 1.1;
}

// Six square waves at the 808's clashing frequencies, high-passed (by
// taking away the previous sample) - plus a little noise for air.
float metal(float b) {
	float f[6] = float[6](205.3, 304.4, 369.6, 522.7, 540.0, 800.0);
	float v = 0.0;
	for (int k = 0; k < 6; ++k) {
		v += sign(sin(TAU * f[k] * 2.1 * b));
	}
	return v / 6.0;
}

float hats(float t, float b, int samp) {
	float hp = metal(b) - metal(b - 1.0 / SR);
	float air = hash(samp) - hash(samp - 1);
	return (hp * 0.9 + air * 0.35) * exp(-28.0 * t) * 1.3;
}

// Hands: three quick bursts and a short tail, of noise band-passed
// around 1.5 kHz.
float clap(float t, int samp) {
	float band = smoothNoise(t, 3000.0) * sin(TAU * 1500.0 * t) +
			(hash(samp) - hash(samp - 1)) * 0.25;
	float env = 0.0;
	for (int k = 0; k < 3; ++k) {
		float s = t - float(k) * 0.011;
		env += s >= 0.0 ? exp(-180.0 * s) : 0.0;
	}
	float tail = t > 0.03 ? exp(-14.0 * (t - 0.03)) * 0.8 : 0.0;
	return band * (env + tail) * 1.2;
}

float tom(float t, float b) {
	float phase = 110.0 * b + (220.0 - 110.0) / 12.0 * (1.0 - exp(-12.0 * b));
	return sin(TAU * phase) * exp(-6.0 * t) * 0.9;
}

float bass(float f, float t, float b) {
	float v = 0.0;
	for (int k = 1; k <= 6; ++k) {
		v += sin(TAU * f * float(k) * b) / float(k) * exp(-float(k) * 0.6 * t);
	}
	return v * (0.55 + 0.45 * exp(-5.0 * t));
}

float pluck(float f, float t, float b) {
	float v = 0.0;
	for (int k = 1; k <= 8; ++k) {
		v += sin(TAU * f * float(k) * b) / float(k) * exp(-t * (2.0 + float(k) * 1.6));
	}
	return v * 0.9;
}

float keys(float f, float t, float b) {
	float m = 1.4 * exp(-4.0 * t) * sin(TAU * f * b);
	return sin(TAU * f * b + m) * exp(-1.4 * t) * 0.6;
}

float lead(float f, float t, float b) {
	// Vibrato as a phase wobble, so it's smooth too.
	float wobble = 0.006 * smoothstep(0.2, 0.6, t) * sin(TAU * 5.5 * t) / (TAU * 5.5);
	float v = 0.0;
	for (int k = 1; k <= 7; k += 2) {
		v += sin(TAU * f * float(k) * (b + wobble)) / float(k);
	}
	return v * 0.32 * smoothstep(0.0, 0.01, t);
}

float bell(float f, float t, float b) {
	float m = 2.2 * exp(-2.0 * t) * sin(TAU * f * 3.5 * b);
	return sin(TAU * f * b + m) * exp(-2.2 * t) * 0.45;
}

float voice(int pad, float t, float b, int samp) {
	int row = pad / COLS;
	int c = pad % COLS;
	if (row == 0) {
		return c == 0 ? kick(t, b) : c == 1 ? snare(t, b, samp) : c == 2 ? hats(t, b, samp) :
				c == 3 ? clap(t, samp) : tom(t, b);
	}
	// A minor pentatonic: A C D E G.
	float semis = c == 0 ? 0.0 : c == 1 ? 3.0 : c == 2 ? 5.0 : c == 3 ? 7.0 : 10.0;
	// Octaves from A4: bass A1, pluck and keys A3, lead A4, bell A5.
	float octave = row == 1 ? -3.0 : row == 4 ? 0.0 : row == 5 ? 1.0 : -1.0;
	float f = pitch(semis + octave * 12.0);
	return row == 1 ? bass(f, t, b) : row == 2 ? pluck(f, t, b) : row == 3 ? keys(f, t, b) :
			row == 4 ? lead(f, t, b) : bell(f, t, b);
}

vec2 mainSound(int samp, float time) {
	vec2 sum = vec2(0.0);
	for (int i = 0; i < PADS; ++i) {
		vec4 a = texelFetch(iChannel0, ivec2(i, 0), 0);
		bool held = a.r > 0.5;
		int pressed = unpack(texelFetch(iChannel0, ivec2(i, 1), 0));
		if (pressed <= 0 || samp < pressed) {
			continue;
		}
		float gate = 1.0;
		if (!held) {
			int released = unpack(texelFetch(iChannel0, ivec2(i, 2), 0));
			float since = float(samp - released) / SR;
			if (released < pressed || since > 1.5) {
				continue;
			}
			gate = exp(-RELEASE * max(since, 0.0));
		}
		float t = float(samp - pressed) / SR;
		// Bent time: where the oscillators had got to by row 4's sample,
		// carried on at row 5's ratio.
		int asOf = unpack(texelFetch(iChannel0, ivec2(i, 4), 0));
		float ratio = float(unpack(texelFetch(iChannel0, ivec2(i, 5), 0))) / 65536.0;
		float bent = float(unpack(texelFetch(iChannel0, ivec2(i, 3), 0))) / 16.0;
		float b = (bent + float(samp - asOf) * ratio) / SR;
		float v = voice(i, t, b, samp) * gate;
		// A little stereo spread, by column.
		float pan = (float(i % COLS) - 2.0) * 0.15;
		sum += v * vec2(1.0 - pan, 1.0 + pan);
	}
	return tanh(sum * VOLUME);
}
