// Splats test: a turning spiral of coloured 2D Gaussians for Upscaling's
// 2D Gaussian Splats. Buffer A lists them two texels each: (x, y, ux, uy) -
// centre and major axis - then (r, g, b, minor). Only the first COUNT are
// drawn; the rest are left empty, which costs almost nothing.
///upscale:splats

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	vec2 uv = fragCoord / iResolution.xy;
	fragColor = vec4(0.02, 0.02, 0.05 + 0.05 * uv.y, 1.0);
}

//// Buffer A
///float32

#define COUNT 160
#define SIZE 0.03 // major axis, in screen heights
#define ASPECT 0.3 // minor / major

void mainImage(out vec4 fragColor, vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	int index = p.y * int(iResolution.x) + p.x;
	int splat = index / 2;
	if (splat >= COUNT) {
		fragColor = vec4(0.0);
		return;
	}
	float f = float(splat) / float(COUNT);
	float angle = f * 30.0 + iTime * 0.5;
	float radius = 0.05 + 0.4 * f;
	vec2 res = iResolution.xy;
	vec2 dir = vec2(cos(angle), sin(angle));
	vec2 centre = 0.5 + radius * dir * vec2(res.y / res.x, 1.0);
	vec2 major = vec2(-dir.y, dir.x) * SIZE * (0.5 + f);
	vec3 colour = 0.5 + 0.5 * cos(6.2831853 * (f + vec3(0.0, 0.33, 0.67)));
	fragColor = index % 2 == 0
			? vec4(centre, major)
			: vec4(colour * 0.9, length(major) * ASPECT);
}
