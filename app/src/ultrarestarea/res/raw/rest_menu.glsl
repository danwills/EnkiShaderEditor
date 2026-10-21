// Ultra Rest Area's menu: each set is a blob in one metaball field, and
// so is the finger - near a tile it reaches out and merges into it, the
// colours blending where they meet. A touched tile swells; a tapped one
// grows to fill the screen as its set starts.
//
// RestAreaActivity owns the layout and the hit-testing, and hands it in:
// tile0..tile3 (x, y, radius, hue - in units where the screen's shorter
// side is 2, centred), tileCount, focus (the touched tile, or -1), press
// (0 to 1 as a tapped tile grows), labelLengths (characters in each
// name), and the names as the shader text `names`, one per line.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution;
uniform float time;
uniform vec3 pointers[10];
uniform int pointerCount;
uniform sampler2D font;
uniform sampler2D names;
uniform vec2 namesSize;

uniform vec4 tile0;
uniform vec4 tile1;
uniform vec4 tile2;
uniform vec4 tile3;
uniform int tileCount;
uniform float focus;
uniform float press;
uniform vec4 labelLengths;

#define FINGER_RADIUS 0.16
#define WOBBLE 0.015
#define LABEL_HEIGHT 0.11

vec4 tileAt(int i) {
	if (i == 0) return tile0;
	if (i == 1) return tile1;
	if (i == 2) return tile2;
	return tile3;
}

float labelLength(int i) {
	if (i == 0) return labelLengths.x;
	if (i == 1) return labelLengths.y;
	if (i == 2) return labelLengths.z;
	return labelLengths.w;
}

vec3 hueColour(float h) {
	return 0.5 + 0.5 * cos(6.28318 * (h + vec3(0.0, 0.33, 0.67)));
}

// The screen point p in the tiles' units.
vec2 toUnits(vec2 px) {
	return (px * 2.0 - resolution) / min(resolution.x, resolution.y);
}

// A tile as drawn: wobbling a little, swollen when touched, and the tapped
// one growing past the screen's edges.
vec4 liveTile(int i) {
	vec4 t = tileAt(i);
	float fi = float(i);
	t.xy += WOBBLE * vec2(sin(time * 0.7 + fi * 2.1), cos(time * 0.6 + fi * 1.7));
	float touched = abs(focus - fi) < 0.5 ? 1.0 : 0.0;
	t.z *= 1.0 + 0.12 * touched + 0.015 * sin(time * 1.3 + fi);
	t.z *= 1.0 + touched * press * press * 6.0;
	return t;
}

float glyph(float code, vec2 g) {
	vec2 cell = vec2(mod(code, 16.0), 15.0 - floor(code / 16.0));
	return texture2D(font, (cell + g) / 16.0).r;
}

// Tile i's name, centred low in it: coverage 0-1.
float label(int i, vec4 t, vec2 p) {
	float len = labelLength(i);
	float h = LABEL_HEIGHT * t.z / 0.42;
	float w = h * 0.6;
	vec2 origin = t.xy + vec2(-0.5 * len * w, -0.35 * t.z - 0.5 * h);
	vec2 q = (p - origin) / vec2(w, h);
	if (q.x < 0.0 || q.y < 0.0 || q.x >= len || q.y >= 1.0) {
		return 0.0;
	}
	float column = floor(q.x);
	vec4 texel = texture2D(names, (vec2(column, float(i)) + 0.5) / namesSize);
	float code = floor(texel.r * 255.0 + 0.5);
	vec2 g = vec2(0.5 + (fract(q.x) - 0.5) * 0.62, q.y);
	float d = glyph(code, g);
	float aa = 1.5 / (h * min(resolution.x, resolution.y) * 0.5);
	return smoothstep(0.5 - aa * 8.0, 0.5 + aa * 8.0, d) * texel.a;
}

void main(void) {
	vec2 p = toUnits(gl_FragCoord.xy);
	float px = 2.0 / min(resolution.x, resolution.y);

	// Behind: a slow, dim aurora.
	float band = p.y * 1.3 + 0.4 * sin(p.x * 1.7 + time * 0.13) + time * 0.03;
	vec3 col = mix(vec3(0.02, 0.015, 0.05), vec3(0.04, 0.07, 0.11),
			0.5 + 0.5 * sin(band * 3.0));

	// The field: each tile, then each finger.
	float field = 0.0;
	vec2 grad = vec2(0.0);
	vec3 tint = vec3(0.0);
	float tintWeight = 0.0;
	for (int i = 0; i < 4; ++i) {
		if (i >= tileCount) {
			break;
		}
		vec4 t = liveTile(i);
		vec2 d = p - t.xy;
		float dd = dot(d, d) + 1e-5;
		float f = t.z * t.z / dd;
		field += f;
		grad += -2.0 * t.z * t.z * d / (dd * dd);
		tint += hueColour(t.w) * f;
		tintWeight += f;
	}
	for (int i = 0; i < 10; ++i) {
		if (i >= pointerCount) {
			break;
		}
		vec2 d = p - toUnits(pointers[i].xy);
		float dd = dot(d, d) + 1e-5;
		float f = FINGER_RADIUS * FINGER_RADIUS / dd;
		field += f;
		grad += -2.0 * FINGER_RADIUS * FINGER_RADIUS * d / (dd * dd);
		tint += vec3(0.95, 0.95, 1.0) * f;
		tintWeight += f;
	}
	tint /= max(tintWeight, 1e-5);

	// Inside where the field passes 1: shaded as a glossy dome, its edge
	// anti-aliased by the field's own slope. The dome's height is
	// sqrt(1 - 1/field) - for a lone blob, exactly a sphere - whose slope
	// stays gentle at the centres, where the field's own shoots up.
	float edgeWidth = length(grad) * px;
	float inside = smoothstep(1.0 - edgeWidth, 1.0 + edgeWidth, field);
	float inverse = 1.0 / max(field, 1e-4);
	float height = sqrt(max(1.0 - inverse, 1e-3));
	vec2 slope = grad * inverse * inverse / (2.0 * height);
	vec3 normal = normalize(vec3(-slope * 0.35, 1.0));
	vec3 light = normalize(vec3(-0.4, 0.6, 0.8));
	float diffuse = max(dot(normal, light), 0.0);
	float shine = pow(max(dot(reflect(-light, normal), vec3(0.0, 0.0, 1.0)), 0.0), 24.0);
	float rim = 1.0 - smoothstep(1.0, 1.6, field);
	vec3 blob = tint * (0.25 + 0.75 * diffuse) + vec3(0.9) * shine * 0.6 + tint * rim * 0.5;

	// A soft glow around the blobs.
	col += tint * smoothstep(0.35, 1.0, field) * 0.25 * (1.0 - inside);
	col = mix(col, blob, inside);

	// The names.
	for (int i = 0; i < 4; ++i) {
		if (i >= tileCount) {
			break;
		}
		float ink = label(i, liveTile(i), p);
		col = mix(col, vec3(1.0), ink * 0.9 * (1.0 - press));
	}

	gl_FragColor = vec4(col, 1.0);
}
