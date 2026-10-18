package enk.danwills.android.enkishadereditor.opengl;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

final class RendererProgramManager {
	record ReloadResult(
			@NonNull List<ShaderError> textureErrors,
			@NonNull List<ShaderError> programErrors,
			boolean succeeded,
			// Non-null only when the main program was freshly compiled
			// from source this time (as opposed to loaded from a cache
			// hit) - the caller can persist it so next time's load can
			// skip straight to createProgramFromBinary().
			@Nullable ProgramBinary freshBinary) {
		@NonNull
		static ReloadResult success(@NonNull List<ShaderError> textureErrors,
				@Nullable ProgramBinary freshBinary) {
			return new ReloadResult(List.copyOf(textureErrors), List.of(), true, freshBinary);
		}

		@NonNull
		static ReloadResult failure(
				@NonNull List<ShaderError> textureErrors,
				@NonNull List<ShaderError> programErrors) {
			return new ReloadResult(
					List.copyOf(textureErrors),
					List.copyOf(programErrors),
					false,
					null);
		}
	}

	// Package-private: also used by HeadlessShaderCompiler, which needs
	// the exact same vertex source to produce cache-compatible binaries.
	static final String FULL_SCREEN_VERTEX_SHADER = """
			attribute vec2 position;
			void main() {
				gl_Position = vec4(position, 0., 1.);
			}
			""";
	static final String FULL_SCREEN_VERTEX_SHADER_3 = """
			in vec2 position;
			void main() {
				gl_Position = vec4(position, 0., 1.);
			}
			""";
	private static final String SURFACE_FRAGMENT_SHADER = """
			#ifdef GL_FRAGMENT_PRECISION_HIGH
			precision highp float;
			#else
			precision mediump float;
			#endif

			uniform vec2 resolution;
			uniform vec2 frameSize;
			uniform float cropPixels;
			uniform sampler2D frame;
			void main(void) {
				vec2 uv = gl_FragCoord.xy / resolution.xy;
				vec2 inset = cropPixels / frameSize;
				uv = inset + uv * (1.0 - 2.0 * inset);
				gl_FragColor = texture2D(frame,uv).rgba;
			}
			""";

	private final int maxTextures;

	@Nullable
	private String sourceText;
	@NonNull
	private PreparedShaderSource preparedShaderSource = PreparedShaderSource.empty();
	// See prepareShaderSource()/releaseForReload().
	@NonNull
	private final List<ShaderTextureResources> staleTextureResources = new ArrayList<>();
	@NonNull
	private ShaderTextureResources textureResources =
			ShaderTextureResources.empty();
	private int version = 2;
	@Nullable
	private GlProgram surfaceProgram;
	@Nullable
	private GlProgram mainProgram;
	@Nullable
	private ProgramBindings surfaceBindings;

	RendererProgramManager(int maxTextures) {
		this.maxTextures = maxTextures;
	}

	void setVersion(int version) {
		this.version = version;
		prepareShaderSource(sourceText);
	}

	void setFragmentShader(@Nullable String source) {
		sourceText = source;
		prepareShaderSource(source);
	}

	boolean hasPreparedShader() {
		var fragmentShader = preparedShaderSource.getFragmentShader();
		return fragmentShader != null && !fragmentShader.getSource().isEmpty();
	}

	@NonNull
	ReloadResult reload(@NonNull Context context, @NonNull GlDevice device,
			@Nullable ProgramBinary cachedMainBinary) {
		clearPrograms();

		var textureErrors = textureResources.load(context, device);
		var surfaceResult = device.createProgram(
				FULL_SCREEN_VERTEX_SHADER,
				SURFACE_FRAGMENT_SHADER,
				ShaderLineMapping.identity());
		if (!surfaceResult.succeeded() || surfaceResult.getProgram() == null) {
			return ReloadResult.failure(textureErrors, surfaceResult.getInfoLog());
		}

		var fragmentShader = preparedShaderSource.getFragmentShader();
		if (fragmentShader == null) {
			device.deleteProgram(surfaceResult.getProgram());
			return ReloadResult.failure(textureErrors, List.of());
		}

		// Only the main (user shader) program is worth caching - try a
		// cache hit first, skipping glCompileShader/glLinkProgram
		// entirely. A rejected/missing cache falls straight through to
		// the normal source compile below, exactly as if there were no
		// cache at all.
		GlProgramBuildResult mainResult = null;
		boolean fromCache = false;
		if (cachedMainBinary != null) {
			var binaryResult = device.createProgramFromBinary(
					cachedMainBinary.format(), cachedMainBinary.bytes());
			if (binaryResult.succeeded()) {
				mainResult = binaryResult;
				fromCache = true;
			}
		}
		if (mainResult == null) {
			mainResult = device.createProgram(
					preparedShaderSource.getVertexShader(
							FULL_SCREEN_VERTEX_SHADER,
							FULL_SCREEN_VERTEX_SHADER_3,
							version),
					fragmentShader.getSource(),
					fragmentShader.getLineMapping());
		}
		if (!mainResult.succeeded() || mainResult.getProgram() == null) {
			device.deleteProgram(surfaceResult.getProgram());
			return ReloadResult.failure(textureErrors, mainResult.getInfoLog());
		}

		surfaceProgram = surfaceResult.getProgram();
		mainProgram = mainResult.getProgram();
		surfaceBindings = new ProgramBindings(surfaceProgram);
		ProgramBinary freshBinary = fromCache ? null : device.getProgramBinary(mainProgram);
		return ReloadResult.success(textureErrors, freshBinary);
	}

	// The exact source text (already cleaned up, see ShaderView) that
	// prepareShaderSource() was last called with - used to compute the
	// program-binary cache key. null if none has been set yet.
	@Nullable
	String getSourceText() {
		return sourceText;
	}

	void discardContextResources() {
		clearPrograms();
		textureResources.discard();
		// The context these belonged to is already gone - nothing left
		// to delete, just references to drop.
		staleTextureResources.clear();
	}

	// Deletes (not just drops) the current programs and any texture sets
	// replaced by setFragmentShader() since the last reload, on a context
	// that's still alive - the preserved-context counterpart of
	// discardContextResources(), called right before reload() whenever
	// ShaderRenderer recompiles without onSurfaceCreated() (see its
	// pendingReload). Skipping this would leak one program pair plus every
	// texture the previous shader sampled, on every single shader switch.
	void releaseForReload(@NonNull GlDevice device) {
		device.deleteProgram(surfaceProgram);
		device.deleteProgram(mainProgram);
		clearPrograms();
		for (var stale : staleTextureResources) {
			stale.release(device);
		}
		staleTextureResources.clear();
	}

	@Nullable
	GlProgram getSurfaceProgram() {
		return surfaceProgram;
	}

	@Nullable
	GlProgram getMainProgram() {
		return mainProgram;
	}

	@Nullable
	ProgramBindings getSurfaceBindings() {
		return surfaceBindings;
	}

	@NonNull
	BackBufferParameters getBackBufferParameters() {
		return preparedShaderSource.getBackBufferParameters();
	}

	// For the high-res tile capture, which needs to compile its own
	// variant of the main program (same prepared source, with a tile-
	// offset macro prepended) alongside the normal one reload() above
	// builds - see HighResScreenshotCapture.
	@NonNull
	PreparedShaderSource getPreparedShaderSource() {
		return preparedShaderSource;
	}

	float getFTimeMax() {
		return preparedShaderSource.getFTimeMax();
	}

	@NonNull
	ShaderTextureResources getTextureResources() {
		return textureResources;
	}

	private void prepareShaderSource(@Nullable String source) {
		// Kept so releaseForReload() can delete its GL textures later, on
		// the GL thread - this runs on the UI thread, with the GL thread
		// paused, so it can't touch GL itself.
		staleTextureResources.add(textureResources);
		preparedShaderSource = ShaderSourcePreparer.prepare(
				source,
				version,
				maxTextures);
		textureResources = ShaderTextureResources.create(
				preparedShaderSource.getSamplers());
	}

	private void clearPrograms() {
		surfaceProgram = null;
		mainProgram = null;
		surfaceBindings = null;
	}
}
