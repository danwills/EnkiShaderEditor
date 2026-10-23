#!/usr/bin/env python3
"""A converted Ready pattern (vti2enki's multipass output, with a Common
section) flown over as a heightfield: the simulation's buffers as they
are, a new Image pass ray marching their chemicals as terrain, and a
camera kept in a spare texel of the state row, flown with the fingers.

  make_terrain.py converted.glsl out.glsl

Fingers fly the camera:
  one finger     paint, on the ground where it touches (as the pattern's
                 own one finger paints)
  two fingers    drag to slide over the ground, pinch to climb or dive,
                 twist to turn
  triple tap     over to the pattern's own interface (its colour view and
                 tiles, painting, the buttons) - and a triple tap there
                 back to the terrain
(Restart puts the camera back where it started.)
The look is set by the #defines at the top of the Image pass (heights,
speed, lens curvature, fog) - Adjust Defines' live mode suits them.
"""
import re
import sys

CAMERA = r"""
// The terrain view's camera (tools/samples/terrain/make_terrain.py), in
// spare texels of the state row, worked out in each frame's first step:
const int CAM_POSE = 53;  // where it is over the grid (x, y, in cells), heading, height
const int CAM_LOOK = 54;  // pitch (down from level), when last moved (iTime), last
                          // frame's finger count, and 2 once started (new buffers clear
                          // to an alpha of 1)
const int CAM_TOUCH = 55; // last frame's first two fingers (x, y, x, y)
const int CAM_TAPS = 56;  // 1 in the pattern's own interface (0 the terrain), taps so far,
                          // when the last ended, when this touch began
const int CAM_DOWN = 57;  // where this touch began (x, y), 1 once it's moved too far for a tap
#define CAM_TAP_TIME 0.3   // seconds a tap may last
#define CAM_TAP_GAP 0.45   // and the most between a triple tap's taps
#define CAM_SPEED 5.0      // cells a second it drifts forward
#define CAM_HEIGHT 10.0    // cells above the ground to start
#define CAM_PITCH 0.26     // radians down from level to start
#define LENS 0.18           // barrel curvature at the corners
#define FOCAL 1.25          // the lens's focal length, of the screen's height

void camStart(ivec2 grid, out vec4 pose, out vec4 look) {
	pose = vec4(vec2(grid) * 0.5, 0.0, CAM_HEIGHT);
	look = vec4(CAM_PITCH, iTime, 0.0, 2.0);
}

// Whether the pattern's own interface is up, not the terrain.
bool camInterface(ivec2 grid) {
	return CAM_TAPS < grid.x && rdState(CAM_TAPS, grid).x > 0.5;
}

// The camera's texels next frame, from last frame's and the fingers.
void camNext(ivec2 grid, vec2 screen, out vec4 pose, out vec4 look, out vec4 touch,
		out vec4 taps, out vec4 down) {
	pose = rdState(CAM_POSE, grid);
	look = rdState(CAM_LOOK, grid);
	touch = rdState(CAM_TOUCH, grid);
	taps = rdState(CAM_TAPS, grid);
	down = rdState(CAM_DOWN, grid);
	if (look.w < 1.5) {
		camStart(grid, pose, look);
		touch = vec4(0.0);
		taps = vec4(0.0);
		down = vec4(0.0);
		return;
	}
	if (!rdNewFrame(grid)) {
		return;
	}
	float dt = clamp(iTime - look.y, 0.0, 0.1);
	look.y = iTime;
	int n = pointerCount;
	int was = int(look.z + 0.5);
	vec2 p0 = pointers[0].xy;
	vec2 p1 = pointers[1].xy;
	// Taps, counted for a triple tap: short, one finger, hardly moving.
	if (n > 0 && was == 0) {
		taps.w = iTime;
		down = vec4(p0, 0.0, 0.0);
	}
	if (n > 1 || (n == 1 && distance(p0, down.xy) > 0.03 * min(screen.x, screen.y))) {
		down.z = 1.0;
	}
	if (n == 0 && was == 1) {
		if (down.z < 0.5 && iTime - taps.w < CAM_TAP_TIME) {
			taps.y = iTime - taps.z < CAM_TAP_GAP ? taps.y + 1.0 : 1.0;
			taps.z = iTime;
			if (taps.y >= 3.0) {
				taps.x = 1.0 - taps.x;
				taps.y = 0.0;
			}
		} else {
			taps.y = 0.0;
		}
	}
	look.z = float(n);
	if (taps.x > 0.5) {
		// The interface has the fingers; the camera waits where it is.
		touch = vec4(p0, p1);
		return;
	}
	if (n >= 2 && was >= 2) {
		vec2 mid0 = (touch.xy + touch.zw) * 0.5;
		vec2 mid1 = (p0 + p1) * 0.5;
		float len0 = max(distance(touch.xy, touch.zw), 1.0);
		float len1 = max(distance(p0, p1), 1.0);
		// Slide what's under the fingers along with them, about as far
		// as the ground moves on screen at this height.
		vec2 d = (mid1 - mid0) / screen.y * pose.w * 1.6;
		vec2 f = vec2(cos(pose.z), sin(pose.z));
		vec2 r = vec2(f.y, -f.x);
		pose.xy -= r * d.x + f * d.y;
		pose.w = clamp(pose.w * len0 / len1, 2.0, 120.0);
		vec2 a = touch.zw - touch.xy;
		vec2 b = p1 - p0;
		pose.z += atan(a.x * b.y - a.y * b.x, dot(a, b));
	}
	pose.xy += vec2(cos(pose.z), sin(pose.z)) * CAM_SPEED * dt;
	// Kept to one copy of the grid (it wraps), so precision holds.
	pose.xy = mod(pose.xy, vec2(grid));
	touch = vec4(p0, p1);
}

// The view's ray through screen point s, as the terrain draws it.
vec3 camRay(vec2 s, vec2 screen, vec4 pose, vec4 look) {
	vec2 uv = (s - 0.5 * screen) / screen.y;
	uv *= 1.0 + LENS * dot(uv, uv);
	float pitch = look.x;
	vec3 f = vec3(cos(pose.z) * cos(pitch), sin(pose.z) * cos(pitch), -sin(pitch));
	vec3 r = normalize(cross(f, vec3(0.0, 0.0, 1.0)));
	vec3 u = cross(r, f);
	return normalize(f * FOCAL + uv.x * r + uv.y * u);
}

// Where on the ground (in cells) a finger at s touches, taking the ground
// as flat at the middle of the hills - near enough for a brush. The copy
// nearest `near`, as the grid wraps.
vec2 camGround(vec2 s, vec2 near, ivec2 grid, vec2 screen) {
	vec4 pose = rdState(CAM_POSE, grid);
	vec4 look = rdState(CAM_LOOK, grid);
	vec3 rd = camRay(s, screen, pose, look);
	vec2 at = pose.xy + rd.xy * (pose.w / max(-rd.z, 0.02));
	vec2 g = vec2(grid);
	return near + mod(at - near + 0.5 * g, g) - 0.5 * g;
}

vec4 camTexel(int texel, ivec2 grid, vec2 screen) {
	vec4 pose, look, touch, taps, down;
	camNext(grid, screen, pose, look, touch, taps, down);
	return texel == CAM_POSE ? pose : texel == CAM_LOOK ? look : texel == CAM_TOUCH ? touch
			: texel == CAM_TAPS ? taps : down;
}
"""

IMAGE = r"""
// The terrain: the simulation as a heightfield, flown over - see
// tools/samples/terrain/make_terrain.py.
#define SOLITON_HEIGHT 1.5  // cells high a soliton (chemical b) stands
#define HISTORY_HEIGHT 0.9  // and the trails it leaves (c)
#define WAVE_HEIGHT 0.5     // the waves' ripple (d), either way
#define FOG 0.006           // how quickly the distance fades into the sky
#define MAX_DISTANCE 260.0  // cells the view reaches
#define SUN vec3(0.55, -0.35, 0.62)

vec2 rangeB, rangeC, rangeD;

float level(float v, vec2 r) {
	return clamp((v - r.x) / (r.y - r.x), 0.0, 1.0);
}

// The ground's height at cell q (wrapped), in cells.
float cellHeight(ivec2 q, ivec2 grid) {
	q = ivec2(mod(vec2(q), vec2(grid)));
	vec4 t = texelFetch(iChannel0, q, 0);
	return SOLITON_HEIGHT * level(t.y, rangeB) + HISTORY_HEIGHT * level(t.z, rangeC) +
			WAVE_HEIGHT * (level(t.w, rangeD) - 0.5) * 2.0;
}

// Smoothly between the cells (bicubic-ish: smoothstep weights, so
// slopes don't kink at every cell edge).
float heightAt(vec2 p, ivec2 grid) {
	vec2 c = p - 0.5;
	vec2 i = floor(c);
	vec2 f = c - i;
	f = f * f * (3.0 - 2.0 * f);
	ivec2 q = ivec2(i);
	float h00 = cellHeight(q, grid);
	float h10 = cellHeight(q + ivec2(1, 0), grid);
	float h01 = cellHeight(q + ivec2(0, 1), grid);
	float h11 = cellHeight(q + ivec2(1, 1), grid);
	return mix(mix(h00, h10, f.x), mix(h01, h11, f.x), f.y);
}

vec3 normalAt(vec2 p, ivec2 grid) {
	float e = 0.5;
	float hx = heightAt(p + vec2(e, 0.0), grid) - heightAt(p - vec2(e, 0.0), grid);
	float hy = heightAt(p + vec2(0.0, e), grid) - heightAt(p - vec2(0.0, e), grid);
	return normalize(vec3(-hx, -hy, 2.0 * e));
}

vec3 sky(vec3 rd) {
	vec3 sun = normalize(SUN);
	float up = clamp(rd.z * 1.6 + 0.25, 0.0, 1.0);
	vec3 col = mix(vec3(0.62, 0.66, 0.74), vec3(0.16, 0.26, 0.48), up);
	col += vec3(1.0, 0.75, 0.45) * pow(max(dot(rd, sun), 0.0), 24.0) * 0.6;
	return col;
}

// The ground's colour where it was hit: the simulation's own colour map
// on the soliton chemical, warmed by the trails.
// The chemicals at p, smoothly between cells, as the height is.
vec4 cellsAt(vec2 p, ivec2 grid) {
	vec2 c = p - 0.5;
	vec2 i = floor(c);
	vec2 f = c - i;
	f = f * f * (3.0 - 2.0 * f);
	ivec2 q = ivec2(i);
	vec4 t00 = texelFetch(iChannel0, ivec2(mod(vec2(q), vec2(grid))), 0);
	vec4 t10 = texelFetch(iChannel0, ivec2(mod(vec2(q + ivec2(1, 0)), vec2(grid))), 0);
	vec4 t01 = texelFetch(iChannel0, ivec2(mod(vec2(q + ivec2(0, 1)), vec2(grid))), 0);
	vec4 t11 = texelFetch(iChannel0, ivec2(mod(vec2(q + ivec2(1, 1)), vec2(grid))), 0);
	return mix(mix(t00, t10, f.x), mix(t01, t11, f.x), f.y);
}

vec3 groundColour(vec2 p, ivec2 grid) {
	vec4 t = cellsAt(p, grid);
	float b = level(t.y, rangeB);
	float c = level(t.z, rangeC);
	vec3 col = colourMap(mix(LOW, HIGH, 0.12 + 0.88 * b));
	col = mix(col * 0.55, col, smoothstep(0.0, 0.6, b));
	return mix(col, col * vec3(1.15, 0.9, 0.75), c * 0.6);
}

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 size = textureSize(iChannel0, 0);
	vec2 screen = vec2(size);
	ivec2 grid = ivec2(size.x, size.y - 1);
	if (camInterface(grid)) {
		rdViews(fragColor, fragCoord);
		return;
	}
	rangeB = rdShowRange(1, grid);
	rangeC = rdShowRange(2, grid);
	rangeD = rdShowRange(3, grid);
	vec4 pose = rdState(CAM_POSE, grid);
	vec4 look = rdState(CAM_LOOK, grid);
	if (look.w < 1.5) {
		camStart(grid, pose, look);
	}
	// The lens: a little barrel curvature, more towards the corners (see
	// camRay(), which painting shares).
	vec2 uv = (fragCoord - 0.5 * screen) / screen.y;
	uv *= 1.0 + LENS * dot(uv, uv);
	vec3 rd = camRay(fragCoord, screen, pose, look);
	// A steady height over the ground's middle, not bobbing over each bump.
	vec3 ro = vec3(pose.xy, 0.5 * SOLITON_HEIGHT + pose.w);

	// March down to the ground: steps as long as the height above it
	// allows, then a few halvings to find the surface.
	float t = 0.0;
	float last = 0.0;
	bool hit = false;
	for (int i = 0; i < 140; ++i) {
		vec3 p = ro + rd * t;
		float above = p.z - heightAt(p.xy, grid);
		if (above < 0.0) {
			hit = true;
			break;
		}
		last = t;
		t += max(0.25, above * 0.55);
		if (t > MAX_DISTANCE || p.z > SOLITON_HEIGHT + HISTORY_HEIGHT + pose.w + 40.0) {
			break;
		}
	}
	vec3 col;
	if (hit) {
		for (int i = 0; i < 6; ++i) {
			float mid = (last + t) * 0.5;
			vec3 p = ro + rd * mid;
			if (p.z < heightAt(p.xy, grid)) {
				t = mid;
			} else {
				last = mid;
			}
		}
		vec3 p = ro + rd * t;
		vec3 n = normalAt(p.xy, grid);
		vec3 sun = normalize(SUN);
		float diffuse = max(dot(n, sun), 0.0);
		// Soft shadow-ish: how much the ground leans away from the sky.
		float ambient = 0.35 + 0.25 * n.z;
		vec3 h = normalize(sun - rd);
		float spec = pow(max(dot(n, h), 0.0), 48.0) * 0.35;
		vec3 base = groundColour(p.xy, grid);
		col = base * (ambient + diffuse * vec3(1.0, 0.92, 0.8)) + spec;
		col = mix(col, sky(rd), 1.0 - exp(-t * FOG));
	} else {
		col = sky(rd);
	}
	// A touch of vignette, as a lens would.
	col *= 1.0 - 0.25 * dot(uv, uv);
	fragColor = vec4(pow(clamp(col, 0.0, 1.0), vec3(0.9)), 1.0);
}
"""


def main():
	src, out = sys.argv[1], sys.argv[2]
	text = open(src).read()
	parts = re.split(r"^(//// .*)\n", text, flags=re.M)
	image, rest = parts[0], parts[1:]
	sections = dict(zip(rest[0::2], rest[1::2]))
	order = rest[0::2]
	# The Image pass: the pattern's own (its main renamed rdViews()), then
	# the terrain, which hands over to it in the interface.
	views = image.replace("void mainImage(out vec4 fragColor, vec2 fragCoord) {",
			"// The pattern's own views, for when a triple tap's put its interface up.\n"
			"void rdViews(out vec4 fragColor, vec2 fragCoord) {", 1)
	assert views != image
	head = views.split("\n")
	title = head[0].replace("converted from Ready", "flown over as terrain - from Ready")
	keep = "\n".join([title, "// Fingers fly the camera: one steers and tilts; two slide, pinch to climb",
			"// or dive, twist to turn (Restart for the start view). A triple tap puts the",
			"// pattern's own interface up (painting, its tiles and buttons), and another",
			"// takes it away again. The look is set by the terrain's #defines (Adjust",
			"// Defines' live mode suits them)."] + head[1:])
	image = keep + IMAGE
	common = sections["//// Common"]
	common += CAMERA
	for name in order:
		if not name.startswith("//// Buffer"):
			continue
		body = sections[name]
		# Fingers fly the camera rather than paint.
		body, n = re.subn(r"\tbool touching = ([^;]*);",
				lambda m: "\t// Over the terrain one finger paints (two fly the camera).\n"
				"\tbool touching = (camInterface(grid) || uiGesture.x < 1.5) && " + m.group(1) + ";", body)
		assert n == 1, name
		# Over the terrain, the finger's point on the ground, as the colour
		# view's would be.
		body, n = re.subn(r"(\tint view = int\(uiGesture\.z \+ 0\.5\);)",
				"\\1\n\tif (!camInterface(grid)) {\n\t\tview = 0;\n\t}", body)
		assert n == 1, name
		body, n = re.subn(r"(\t\tvec2 at = pointers\[i\]\.xy;)",
				"\\1\n\t\tif (!camInterface(grid)) {\n"
				"\t\t\tat = camGround(at, fragCoord, grid, screen);\n\t\t}", body)
		assert n == 1, name
		if "texel == S_CLOCK" in body:
			body = body.replace("\t\telse if (texel == S_CLOCK)",
					"\t\telse if (texel >= CAM_POSE && texel <= CAM_DOWN) "
					"fragColor = camTexel(texel, grid, screen);\n\t\telse if (texel == S_CLOCK)", 1)
		sections[name] = body
	sections["//// Common"] = common
	with open(out, "w") as fh:
		fh.write(image)
		for name in order:
			fh.write(name + "\n" + sections[name])


if __name__ == "__main__":
	main()
