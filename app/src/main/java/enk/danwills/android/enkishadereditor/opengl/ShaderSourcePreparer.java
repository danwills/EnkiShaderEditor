package enk.danwills.android.enkishadereditor.opengl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import enk.danwills.android.enkishadereditor.fragment.AbstractSamplerPropertiesFragment;

public final class ShaderSourcePreparer {
	private static final String SAMPLER_2D = "2D";
	private static final String SAMPLER_CUBE = "Cube";
	private static final String SAMPLER_EXTERNAL_OES = "ExternalOES";
	private static final Pattern PATTERN_SAMPLER = Pattern.compile(
			String.format(
					"uniform[ \t]+sampler(" +
							SAMPLER_2D + "|" +
							SAMPLER_CUBE + "|" +
							SAMPLER_EXTERNAL_OES +
							")+[ \t]+(%s);[ \t]*(.*)",
					AbstractSamplerPropertiesFragment.TEXTURE_NAME_PATTERN));
	private static final Pattern PATTERN_FTIME = Pattern.compile(
			"^#define[ \\t]+FTIME_PERIOD[ \\t]+([0-9.]+)[ \\t]*$",
			Pattern.MULTILINE);
	private static final Pattern PATTERN_PRESET = Pattern.compile(
			"[;/]p:(" + AbstractSamplerPropertiesFragment.TEXTURE_NAME_PATTERN + ")");
	private static final Pattern PATTERN_GLES3_VERSION = Pattern.compile(
			"^#version 3[0-9]{2} es$",
			Pattern.MULTILINE);
	private static final String OES_EXTERNAL =
			"#extension GL_OES_EGL_image_external : require\n";
	private static final String OES_EXTERNAL_ESS3 =
			"#extension GL_OES_EGL_image_external_essl3 : require\n";
	private static final String SHADER_EDITOR =
			"#define SHADER_EDITOR 1\n";
	// ShaderToy compatibility - see adaptShaderToy().
	private static final Pattern PATTERN_MAIN_IMAGE = Pattern.compile(
			"\\bvoid[ \t\n]+mainImage[ \t\n]*\\(");
	private static final Pattern PATTERN_MAIN = Pattern.compile(
			"\\bvoid[ \t\n]+main[ \t\n]*\\(");
	// A Sound pass - see SoundOutput: `vec2 mainSound(int samp, float
	// time)`, as ShaderToy has it, or its older `vec2 mainSound(float
	// time)`.
	private static final Pattern PATTERN_MAIN_SOUND = Pattern.compile(
			"\\bvec2[ \t\n]+mainSound[ \t\n]*\\(");
	private static final Pattern PATTERN_MAIN_SOUND_INT = Pattern.compile(
			"\\bvec2[ \t\n]+mainSound[ \t\n]*\\([ \t\n]*(in[ \t\n]+)?int\\b");
	private static final Pattern PATTERN_EXTENSION = Pattern.compile(
			"^[ \t]*#extension[^\n]*\n",
			Pattern.MULTILINE);
	private static final String SHADER_TOY_VERSION = """
			#version 300 es
			precision highp float;
			precision highp int;
			""";
	private static final String SHADER_TOY_PRECISION = """
			#ifdef GL_FRAGMENT_PRECISION_HIGH
			#define ENKI_HIGHP highp
			#else
			#define ENKI_HIGHP mediump
			#endif
			""";
	// Uniforms ShaderToy declares implicitly, in declaration order: the
	// name, and the declaration added when a source uses it undeclared.
	private static final String[][] SHADER_TOY_UNIFORMS = {
			{ShaderRenderer.UNIFORM_ST_RESOLUTION,
					"uniform ENKI_HIGHP vec3 " + ShaderRenderer.UNIFORM_ST_RESOLUTION + ";"},
			{ShaderRenderer.UNIFORM_ST_TIME,
					"uniform ENKI_HIGHP float " + ShaderRenderer.UNIFORM_ST_TIME + ";"},
			{ShaderRenderer.UNIFORM_ST_TIME_DELTA,
					"uniform ENKI_HIGHP float " + ShaderRenderer.UNIFORM_ST_TIME_DELTA + ";"},
			{ShaderRenderer.UNIFORM_ST_FRAME_RATE,
					"uniform ENKI_HIGHP float " + ShaderRenderer.UNIFORM_ST_FRAME_RATE + ";"},
			{ShaderRenderer.UNIFORM_ST_FRAME,
					"uniform int " + ShaderRenderer.UNIFORM_ST_FRAME + ";"},
			{ShaderRenderer.UNIFORM_ST_MOUSE,
					"uniform ENKI_HIGHP vec4 " + ShaderRenderer.UNIFORM_ST_MOUSE + ";"},
			{ShaderRenderer.UNIFORM_ST_DATE,
					"uniform ENKI_HIGHP vec4 " + ShaderRenderer.UNIFORM_ST_DATE + ";"},
			{ShaderRenderer.UNIFORM_ST_SAMPLE_RATE,
					"uniform ENKI_HIGHP float " + ShaderRenderer.UNIFORM_ST_SAMPLE_RATE + ";"},
			{ShaderRenderer.UNIFORM_ST_CHANNEL_RESOLUTION,
					"uniform ENKI_HIGHP vec3 " + ShaderRenderer.UNIFORM_ST_CHANNEL_RESOLUTION +
							"[" + ChannelParameters.MAX_CHANNELS + "];"},
	};

	private ShaderSourcePreparer() {
	}

	@NonNull
	static PreparedShaderSource prepare(
			@Nullable String source,
			int version,
			int maxTextures) {
		float fTimeMax = parseFTime(source);
		if (source == null) {
			return new PreparedShaderSource(
					null,
					fTimeMax,
					null,
					new BackBufferParameters(),
					List.of(),
					new ChannelParameters[ChannelParameters.MAX_CHANNELS]);
		}

		PreparedShaderInput preparedInput = adaptShaderToy(new PreparedShaderInput(
				source,
				ShaderLineMapping.identity()));
		String gles3Version = getGLES3Version(preparedInput.getSource(), version);
		BackBufferParameters backBufferParameters = new BackBufferParameters();
		ArrayList<DiscoveredSampler> samplers = new ArrayList<>();
		ChannelParameters[] channels = new ChannelParameters[ChannelParameters.MAX_CHANNELS];

		for (Matcher matcher = PATTERN_SAMPLER.matcher(source);
				matcher.find() && samplers.size() < maxTextures; ) {
			String type = matcher.group(1);
			String name = matcher.group(2);
			String params = matcher.group(3);

			if (type == null || name == null) {
				continue;
			}

			if (ShaderRenderer.UNIFORM_BACKBUFFER.equals(name)) {
				backBufferParameters.parse(params);
				continue;
			}
			// Built in - bound by the renderer, see SourceText.
			if (ShaderRenderer.UNIFORM_SOURCE_TEXT.equals(name) ||
					ShaderRenderer.UNIFORM_SOURCE_TEXT_COLOR.equals(name) ||
					ShaderRenderer.UNIFORM_MODULE_INFO.equals(name) ||
					ShaderRenderer.UNIFORM_MODULE_SCOPE.equals(name)) {
				continue;
			}
			// Bound by the renderer to an offscreen buffer, not looked up
			// as a saved texture.
			int channel = ChannelParameters.getChannelIndex(name);
			if (channel >= 0) {
				if (SAMPLER_2D.equals(type)) {
					channels[channel] = new ChannelParameters(params);
				}
				continue;
			}

			int target = switch (type) {
				case SAMPLER_2D -> GLES20.GL_TEXTURE_2D;
				case SAMPLER_CUBE -> GLES20.GL_TEXTURE_CUBE_MAP;
				case SAMPLER_EXTERNAL_OES -> {
					String pattern = gles3Version != null
							? OES_EXTERNAL_ESS3
							: OES_EXTERNAL;
					if (!preparedInput.getSource().contains(pattern)) {
						preparedInput = addPreprocessorDirective(preparedInput, pattern);
					}
					yield GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
				}
				default -> -1;
			};
			if (target < 0) {
				continue;
			}

			samplers.add(new DiscoveredSampler(
					name,
					target,
					new TextureParameters(params)));
		}

		if (!preparedInput.getSource().contains(SHADER_EDITOR)) {
			preparedInput = addPreprocessorDirective(preparedInput, SHADER_EDITOR);
		}

		return new PreparedShaderSource(
				preparedInput,
				fTimeMax,
				gles3Version,
				backBufferParameters,
				samplers,
				channels);
	}

	// Lets ShaderToy code run as written: a source that defines
	// mainImage() but no main() gets ShaderToy's GLSL ES 3.00 setup (if it
	// doesn't pick its own #version) and a main() that calls mainImage(),
	// and any of ShaderToy's implicit uniforms (iTime, iChannel0 ...) it
	// uses without declaring get declared. Lines are only ever inserted
	// before the user's code via the line-mapped helpers below, or
	// appended after it, so compile errors still point at the right line.
	@NonNull
	private static PreparedShaderInput adaptShaderToy(@NonNull PreparedShaderInput input) {
		String source = input.getSource();
		boolean wrap = PATTERN_MAIN_IMAGE.matcher(source).find() &&
				!PATTERN_MAIN.matcher(source).find();
		if ((wrap || isSoundSource(source)) && !source.trim().startsWith("#version")) {
			input = addPreprocessorDirective(input, SHADER_TOY_VERSION);
		}

		StringBuilder declarations = new StringBuilder();
		for (String[] uniform : SHADER_TOY_UNIFORMS) {
			if (usesUndeclared(source, uniform[0])) {
				declarations.append(uniform[1]).append('\n');
			}
		}
		for (int channel = 0; channel < ChannelParameters.MAX_CHANNELS; ++channel) {
			String name = ChannelParameters.getChannelName(channel);
			if (usesUndeclared(source, name)) {
				declarations.append("uniform sampler2D ").append(name).append(";\n");
			}
		}
		if (declarations.length() > 0) {
			input = insertAfterExtensions(input, SHADER_TOY_PRECISION + declarations);
		}

		boolean sound = isSoundSource(source);
		if (sound) {
			String adapted = input.getSource();
			String call = PATTERN_MAIN_SOUND_INT.matcher(source).find()
					? "mainSound(s, float(s) / " + SoundOutput.SAMPLE_RATE + ".0)"
					: "mainSound(float(s) / " + SoundOutput.SAMPLE_RATE + ".0)";
			// Each texel is one stereo sample, in reading order from
			// enkiSoundOffset; each channel's -1..1 is 16 bits, as two
			// bytes - see SoundOutput.
			String main = String.format(Locale.US, """
					uniform int enkiSoundOffset;
					out highp vec4 enkiFragColor;
					void main() {
						int s = enkiSoundOffset + int(gl_FragCoord.y) * %d + int(gl_FragCoord.x);
						vec2 v = clamp(%s, -1.0, 1.0);
						vec2 u = floor((v * 0.5 + 0.5) * 65535.0 + 0.5);
						vec2 hi = floor(u / 256.0);
						enkiFragColor = vec4(u.x - hi.x * 256.0, hi.x, u.y - hi.y * 256.0, hi.y) / 255.0;
					}
					""", SoundOutput.BLOCK_WIDTH, call);
			return new PreparedShaderInput(
					adapted + (adapted.endsWith("\n") ? "" : "\n") + main,
					input.getLineMapping());
		}
		if (wrap) {
			String adapted = input.getSource();
			String main = PATTERN_GLES3_VERSION.matcher(adapted).find()
					? """
					out highp vec4 enkiFragColor;
					void main() {
						mainImage(enkiFragColor, gl_FragCoord.xy);
					}
					"""
					: """
					void main() {
						mediump vec4 enkiFragColor;
						mainImage(enkiFragColor, gl_FragCoord.xy);
						gl_FragColor = enkiFragColor;
					}
					""";
			input = new PreparedShaderInput(
					adapted + (adapted.endsWith("\n") ? "" : "\n") + main,
					input.getLineMapping());
		}
		return input;
	}

	// Whether `source` is a Sound pass: it has a mainSound() and no
	// main() or mainImage() of its own.
	public static boolean isSoundSource(@Nullable String source) {
		return source != null && PATTERN_MAIN_SOUND.matcher(source).find() &&
				!PATTERN_MAIN.matcher(source).find() &&
				!PATTERN_MAIN_IMAGE.matcher(source).find();
	}

	private static boolean usesUndeclared(@NonNull String source, @NonNull String name) {
		String word = "\\b" + Pattern.quote(name) + "\\b";
		return Pattern.compile(word).matcher(source).find() &&
				!Pattern.compile("\\buniform\\b[^;]*" + word).matcher(source).find();
	}

	// Inserts `text` after the last #extension directive (they must come
	// before any declaration), or else after #version / at the top.
	@NonNull
	private static PreparedShaderInput insertAfterExtensions(
			@NonNull PreparedShaderInput input,
			@NonNull String text) {
		String source = input.getSource();
		int end = -1;
		for (Matcher matcher = PATTERN_EXTENSION.matcher(source); matcher.find(); ) {
			end = matcher.end();
		}
		if (end < 0) {
			return addPreprocessorDirective(input, text);
		}
		return new PreparedShaderInput(
				source.substring(0, end) + text + source.substring(end),
				input.getLineMapping().addInsertedLinesAfterSourceLine(
						countSourceLines(source, end),
						countLines(text)));
	}

	// Adds every saved-texture name `source` refers to: its sampler
	// declarations (other than the backbuffer and iChannel slots) and any
	// backbuffer preset (`///p:name;`) - see TextureReferences.
	static void collectTextureNames(@Nullable String source, @NonNull Set<String> names) {
		if (source == null) {
			return;
		}
		for (Matcher matcher = PATTERN_SAMPLER.matcher(source); matcher.find(); ) {
			String name = matcher.group(2);
			if (name == null || ChannelParameters.getChannelIndex(name) >= 0 ||
					ShaderRenderer.UNIFORM_SOURCE_TEXT.equals(name) ||
					ShaderRenderer.UNIFORM_SOURCE_TEXT_COLOR.equals(name) ||
					ShaderRenderer.UNIFORM_MODULE_INFO.equals(name) ||
					ShaderRenderer.UNIFORM_MODULE_SCOPE.equals(name)) {
				continue;
			}
			if (ShaderRenderer.UNIFORM_BACKBUFFER.equals(name)) {
				Matcher preset = PATTERN_PRESET.matcher(
						matcher.group(3) != null ? matcher.group(3) : "");
				if (preset.find()) {
					names.add(preset.group(1));
				}
				continue;
			}
			names.add(name);
		}
	}

	private static float parseFTime(@Nullable String source) {
		if (source != null) {
			Matcher matcher = PATTERN_FTIME.matcher(source);
			String period;
			if (matcher.find() &&
					matcher.groupCount() > 0 &&
					(period = matcher.group(1)) != null) {
				return Float.parseFloat(period);
			}
		}
		return 3f;
	}

	@Nullable
	private static String getGLES3Version(@NonNull String source, int version) {
		Matcher matcher = PATTERN_GLES3_VERSION.matcher(source);
		return version == 3 && matcher.find() ? matcher.group(0) : null;
	}

	// Package-private: also used by HighResScreenshotCapture, which needs
	// to inject its own directive (the per-tile gl_FragCoord offset)
	// into an already-prepared source the same #version-safe way - a
	// #version directive (GLES3 shaders) must stay the first line, so a
	// plain prepend would break any such shader.
	@NonNull
	static PreparedShaderInput addPreprocessorDirective(
			@NonNull PreparedShaderInput input,
			@NonNull String directive) {
		String source = input.getSource();
		ShaderLineMapping lineMapping = input.getLineMapping();
		int insertedLines = countLines(directive);
		if (source.trim().startsWith("#version")) {
			int lineFeed = source.indexOf("\n");
			if (lineFeed < 0) {
				return input;
			}
			int sourceLine = countSourceLines(source, lineFeed + 1);
			++lineFeed;
			return new PreparedShaderInput(
					source.substring(0, lineFeed) +
							directive +
							source.substring(lineFeed),
					lineMapping.addInsertedLinesAfterSourceLine(
							sourceLine,
							insertedLines));
		}
		return new PreparedShaderInput(
				directive + source,
				lineMapping.addInsertedLinesAfterSourceLine(0, insertedLines));
	}

	private static int countSourceLines(@NonNull String source, int endExclusive) {
		int lines = 0;
		for (int index = 0; index < endExclusive; ++index) {
			if (source.charAt(index) == '\n') {
				++lines;
			}
		}
		return lines;
	}

	private static int countLines(@NonNull String source) {
		int lines = 1;
		for (int index = 0; index < source.length(); ++index) {
			if (source.charAt(index) == '\n') {
				++lines;
			}
		}
		return source.endsWith("\n") ? lines - 1 : lines;
	}
}
