#!/usr/bin/env python3
"""Compile-checks a sample shader's passes with glslangValidator, without
a phone: splits a multipass file at its `//// Buffer X` / `//// Sound`
markers (skipping `//// Text <name>` sections - shader texts, not GLSL)
and wraps each pass roughly the way ShaderSourcePreparer does -
GLSL ES 3.00, ShaderToy's implicit uniforms where used but undeclared,
and a main() calling mainImage() or mainSound(). It can't catch what
only a real GPU driver rejects, but it catches typos and type errors.

Usage: check_shader.py app/src/main/res/raw/sample_soundboard.glsl ...
"""
import re
import subprocess
import sys
import tempfile

HEADER = "#version 300 es\nprecision highp float;\nprecision highp int;\n"
IMPLICIT = [
	("iResolution", "uniform highp vec3 iResolution;"),
	("iTime", "uniform highp float iTime;"),
	("iTimeDelta", "uniform highp float iTimeDelta;"),
	("iFrameRate", "uniform highp float iFrameRate;"),
	("iFrame", "uniform int iFrame;"),
	("iMouse", "uniform highp vec4 iMouse;"),
	("iDate", "uniform highp vec4 iDate;"),
	("iSampleRate", "uniform highp float iSampleRate;"),
	("iChannelResolution", "uniform highp vec3 iChannelResolution[4];"),
]
MARKER = re.compile(r"^//// (Image|Sound|Buffer [A-D]|Text \w+)[ \t]*$", re.M)


def declared(src, name):
	return re.search(r"\buniform\b[^;]*\b" + re.escape(name) + r"\b", src) is not None


def used(src, name):
	return re.search(r"\b" + re.escape(name) + r"\b", src) is not None


def wrap(src):
	decls = ""
	for name, decl in IMPLICIT:
		if used(src, name) and not declared(src, name):
			decls += decl + "\n"
	for c in range(4):
		name = "iChannel%d" % c
		if used(src, name) and not declared(src, name):
			decls += "uniform sampler2D %s;\n" % name
	body = src
	if re.search(r"\bvec2\s+mainSound\s*\(", src):
		call = ("mainSound(s, float(s) / 44100.0)"
				if re.search(r"\bvec2\s+mainSound\s*\(\s*(in\s+)?int\b", src)
				else "mainSound(float(s) / 44100.0)")
		body += ("\nuniform int enkiSoundOffset;\nout highp vec4 enkiFragColor;\n"
				"void main() {\n\tint s = enkiSoundOffset + int(gl_FragCoord.y) * 512 + "
				"int(gl_FragCoord.x);\n\tvec2 v = clamp(%s, -1.0, 1.0);\n"
				"\tenkiFragColor = vec4(v, 0.0, 1.0);\n}\n" % call)
	elif re.search(r"\bvoid\s+mainImage\s*\(", src) and not re.search(r"\bvoid\s+main\s*\(", src):
		body += ("\nout highp vec4 enkiFragColor;\nvoid main() {\n"
				"\tmainImage(enkiFragColor, gl_FragCoord.xy);\n}\n")
	if src.lstrip().startswith("#version"):
		return decls + body
	if re.search(r"\bvoid\s+main\s*\(", src):
		# Its own main(): GLSL ES 1.00, as the app runs it.
		return "#version 100\n" + body
	return HEADER + decls + body


def passes(text):
	parts = []
	last = 0
	name = "Image"
	for m in MARKER.finditer(text):
		parts.append((name, text[last:m.start()]))
		name = m.group(1)
		last = m.end() + 1
	parts.append((name, text[last:]))
	# A `//// Text <name>` section is a shader text, not GLSL.
	return [(n, s) for n, s in parts if s.strip() and not n.startswith("Text ")]


def main():
	ok = True
	for path in sys.argv[1:]:
		for name, src in passes(open(path).read()):
			with tempfile.NamedTemporaryFile("w", suffix=".frag", delete=False) as f:
				f.write(wrap(src))
				tmp = f.name
			r = subprocess.run(["glslangValidator", tmp], capture_output=True, text=True)
			status = "ok" if r.returncode == 0 else "FAILED"
			print("%s [%s]: %s" % (path, name, status))
			if r.returncode != 0:
				ok = False
				print(r.stdout.strip())
	sys.exit(0 if ok else 1)


if __name__ == "__main__":
	main()
