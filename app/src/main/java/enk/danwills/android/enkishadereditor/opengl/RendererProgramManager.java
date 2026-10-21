package enk.danwills.android.enkishadereditor.opengl;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RendererProgramManager {
	// A buffer pass's program freshly compiled from source this reload,
	// for the caller to persist - see ReloadResult.freshBinary.
	record FreshBufferBinary(int buffer, @NonNull String sourceText,
			@NonNull ProgramBinary binary) {
	}

	// Looks up a buffer pass's cached program binary, or null.
	interface BufferBinaryLookup {
		@Nullable
		ProgramBinary get(int buffer, @NonNull String sourceText);
	}

	record ReloadResult(
			@NonNull List<ShaderError> textureErrors,
			@NonNull List<ShaderError> programErrors,
			boolean succeeded,
			// Non-null only when the main program was freshly compiled
			// from source this time (as opposed to loaded from a cache
			// hit) - the caller can persist it so next time's load can
			// skip straight to createProgramFromBinary().
			@Nullable ProgramBinary freshBinary,
			@NonNull List<FreshBufferBinary> freshBufferBinaries) {
		@NonNull
		static ReloadResult success(@NonNull List<ShaderError> textureErrors,
				@Nullable ProgramBinary freshBinary,
				@NonNull List<FreshBufferBinary> freshBufferBinaries) {
			return new ReloadResult(List.copyOf(textureErrors), List.of(), true, freshBinary,
					List.copyOf(freshBufferBinaries));
		}

		@NonNull
		static ReloadResult failure(
				@NonNull List<ShaderError> textureErrors,
				@NonNull List<ShaderError> programErrors) {
			return new ReloadResult(
					List.copyOf(textureErrors),
					List.copyOf(programErrors),
					false,
					null,
					List.of());
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
	// Indexed by buffer (0 = A), null where there's no such buffer pass.
	@Nullable
	private String[] bufferSourceTexts;
	@NonNull
	private volatile Map<String, String> texts = Map.of();
	@NonNull
	private PreparedShaderSource preparedShaderSource = PreparedShaderSource.empty();
	// In buffer order, only the buffers the shader actually has.
	@NonNull
	private List<BufferPass> bufferPasses = List.of();
	// Replaced by prepareShaderSource() and not yet released on the GL
	// thread - same idea as staleTextureResources.
	@NonNull
	private final List<BufferPass> staleBufferPasses = new ArrayList<>();
	// See getSoundPass().
	@Nullable
	private BufferPass soundPass;
	// See getModuleName().
	@Nullable
	private String moduleName;
	// See getSteps().
	private static final Pattern STEPS = Pattern.compile(
			"^[ \\t]*///steps:([0-9]{1,3})[ \\t]*$", Pattern.MULTILINE);
	private static final int MAX_STEPS = 64;
	private int steps = 1;
	// The backbuffer parameters before the last prepareShaderSource(),
	// as text - see backBufferParametersUnchanged().
	@Nullable
	private String previousBackBufferParameters;
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
	// The Image pass's `sourceText`, if it uses it - see SourceText.
	@Nullable
	private SourceText mainSourceText;

	RendererProgramManager(int maxTextures) {
		this.maxTextures = maxTextures;
	}

	void setVersion(int version) {
		this.version = version;
		prepareShaderSource();
	}

	// The shader's texts (see ShaderTextDao) - for the next reload, or
	// reloadTexts(). Any thread.
	void setTexts(@NonNull Map<String, String> texts) {
		this.texts = Map.copyOf(texts);
	}

	// GL thread: the texts' textures made again from setTexts()'s.
	void reloadTexts(@NonNull Context context, @NonNull GlDevice device) {
		textureResources.reloadTexts(context, device, texts);
	}

	void setFragmentShader(@Nullable String source, @Nullable String[] bufferSources) {
		sourceText = source;
		bufferSourceTexts = bufferSources != null ? bufferSources.clone() : null;
		prepareShaderSource();
	}

	boolean hasPreparedShader() {
		var fragmentShader = preparedShaderSource.getFragmentShader();
		return fragmentShader != null && !fragmentShader.getSource().isEmpty();
	}

	@NonNull
	ReloadResult reload(@NonNull Context context, @NonNull GlDevice device,
			@Nullable ProgramBinary cachedMainBinary,
			@NonNull BufferBinaryLookup bufferBinaries) {
		clearPrograms();

		var textureErrors = textureResources.load(context, device, texts);
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

		var freshBufferBinaries = new ArrayList<FreshBufferBinary>();
		var bufferErrors = new ArrayList<ShaderError>();
		for (var pass : allBufferPasses()) {
			GlProgram program = buildBufferProgram(device, pass, bufferBinaries,
					freshBufferBinaries, bufferErrors);
			pass.setProgram(program);
		}
		if (!bufferErrors.isEmpty()) {
			// A buffer that doesn't compile fails the whole shader, like
			// any other compile error - there'd be no sensible picture.
			device.deleteProgram(surfaceResult.getProgram());
			device.deleteProgram(mainResult.getProgram());
			for (var pass : allBufferPasses()) {
				device.deleteProgram(pass.getProgram());
				pass.setProgram(null);
			}
			return ReloadResult.failure(textureErrors, bufferErrors);
		}

		surfaceProgram = surfaceResult.getProgram();
		mainProgram = mainResult.getProgram();
		surfaceBindings = new ProgramBindings(surfaceProgram);
		ProgramBinary freshBinary = fromCache ? null : device.getProgramBinary(mainProgram);
		return ReloadResult.success(textureErrors, freshBinary, freshBufferBinaries);
	}

	@Nullable
	private GlProgram buildBufferProgram(@NonNull GlDevice device, @NonNull BufferPass pass,
			@NonNull BufferBinaryLookup bufferBinaries,
			@NonNull List<FreshBufferBinary> freshBufferBinaries,
			@NonNull List<ShaderError> errors) {
		var fragmentShader = pass.prepared.getFragmentShader();
		if (fragmentShader == null) {
			return null;
		}
		ProgramBinary cached = bufferBinaries.get(pass.buffer, pass.sourceText);
		if (cached != null) {
			var binaryResult = device.createProgramFromBinary(cached.format(), cached.bytes());
			if (binaryResult.succeeded() && binaryResult.getProgram() != null) {
				return binaryResult.getProgram();
			}
		}
		var result = device.createProgram(
				pass.prepared.getVertexShader(
						FULL_SCREEN_VERTEX_SHADER,
						FULL_SCREEN_VERTEX_SHADER_3,
						version),
				fragmentShader.getSource(),
				fragmentShader.getLineMapping());
		if (!result.succeeded() || result.getProgram() == null) {
			for (var error : result.getInfoLog()) {
				errors.add(error.withPass(pass.buffer));
			}
			if (result.getInfoLog().isEmpty()) {
				errors.add(ShaderError.createGeneral("Link failed").withPass(pass.buffer));
			}
			return null;
		}
		ProgramBinary fresh = device.getProgramBinary(result.getProgram());
		if (fresh != null) {
			freshBufferBinaries.add(new FreshBufferBinary(pass.buffer, pass.sourceText, fresh));
		}
		return result.getProgram();
	}

	@NonNull
	List<BufferPass> getBufferPasses() {
		return bufferPasses;
	}

	// Every compiled program the current shader draws with - for
	// UniformPresence.
	@NonNull
	List<GlProgram> getPassPrograms() {
		var programs = new ArrayList<GlProgram>(bufferPasses.size() + 1);
		if (mainProgram != null) {
			programs.add(mainProgram);
		}
		for (var pass : allBufferPasses()) {
			if (pass.getProgram() != null) {
				programs.add(pass.getProgram());
			}
		}
		return programs;
	}

	// The Sound pass, if the shader has one - a buffer that makes sound
	// rather than a picture (see SoundOutput). Not in getBufferPasses().
	@Nullable
	BufferPass getSoundPass() {
		return soundPass;
	}

	// The tracker module the shader plays (a `///mod:` line), or null.
	@Nullable
	String getModuleName() {
		return moduleName;
	}

	// How many times the buffer passes run for each frame drawn - a
	// `///steps:N` line in any pass (1-64), else 1. A simulation in its
	// buffers then moves N steps a frame, for one Image pass.
	int getSteps() {
		return steps;
	}

	private static int findSteps(@NonNull String[] sources) {
		for (String source : sources) {
			if (source == null) {
				continue;
			}
			Matcher matcher = STEPS.matcher(source);
			if (matcher.find()) {
				return Math.max(1, Math.min(MAX_STEPS, Integer.parseInt(matcher.group(1))));
			}
		}
		return 1;
	}

	// The drawn buffer passes and the Sound pass - everything with a
	// program to build and release.
	@NonNull
	private List<BufferPass> allBufferPasses() {
		if (soundPass == null) {
			return bufferPasses;
		}
		var all = new ArrayList<>(bufferPasses);
		all.add(soundPass);
		return all;
	}

	// Clean source text of a buffer pass, for the binary cache key.
	@Nullable
	String getBufferSourceText(int buffer) {
		return bufferSourceTexts != null && buffer < bufferSourceTexts.length
				? bufferSourceTexts[buffer]
				: null;
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
		for (var pass : allBufferPasses()) {
			pass.discard();
		}
		// The context these belonged to is already gone - nothing left
		// to delete, just references to drop.
		staleTextureResources.clear();
		staleBufferPasses.clear();
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
		if (mainSourceText != null) {
			mainSourceText.release(device);
		}
		clearPrograms();
		for (var stale : staleTextureResources) {
			stale.release(device);
		}
		staleTextureResources.clear();
		// Only programs: these passes were made since the last reload,
		// so any render targets they have are ones they've just adopted
		// to keep (see adoptBufferTargets()).
		for (var pass : allBufferPasses()) {
			pass.releaseProgram(device);
		}
		for (var stale : staleBufferPasses) {
			stale.release(device);
		}
		staleBufferPasses.clear();
	}

	// After a reload - GL thread only.
	void createMainSourceText(@NonNull Context context, @NonNull GlDevice device, int tabWidth) {
		if (mainSourceText != null) {
			mainSourceText.release(device);
			mainSourceText = null;
		}
		if (mainProgram != null) {
			mainSourceText = SourceText.createFor(context, device, mainProgram, sourceText,
					tabWidth);
		}
	}

	@Nullable
	SourceText getMainSourceText() {
		return mainSourceText;
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

	// Compiles `source` and `bufferSources` (as setFragmentShader()
	// takes them) and deletes the programs straight away, changing
	// nothing - so Adjust Defines' live mode can find out whether its
	// converted source compiles before handing it over for real. GL
	// thread only. Empty if every pass compiled.
	@NonNull
	List<ShaderError> testCompile(@NonNull GlDevice device, @NonNull String source,
			@Nullable String[] bufferSources) {
		var errors = new ArrayList<ShaderError>();
		testCompilePass(device, ShaderSourcePreparer.prepare(source, version, maxTextures),
				ShaderError.IMAGE_PASS, errors);
		if (bufferSources != null) {
			for (int buffer = 0; buffer < bufferSources.length; ++buffer) {
				if (bufferSources[buffer] != null) {
					testCompilePass(device, ShaderSourcePreparer.prepare(
							bufferSources[buffer], version, maxTextures), buffer, errors);
				}
			}
		}
		return errors;
	}

	private void testCompilePass(@NonNull GlDevice device, @NonNull PreparedShaderSource prepared,
			int buffer, @NonNull List<ShaderError> errors) {
		var fragmentShader = prepared.getFragmentShader();
		if (fragmentShader == null) {
			return;
		}
		var result = device.createProgram(
				prepared.getVertexShader(
						FULL_SCREEN_VERTEX_SHADER,
						FULL_SCREEN_VERTEX_SHADER_3,
						version),
				fragmentShader.getSource(),
				fragmentShader.getLineMapping());
		if (!result.succeeded() || result.getProgram() == null) {
			for (var error : result.getInfoLog()) {
				errors.add(buffer == ShaderError.IMAGE_PASS ? error : error.withPass(buffer));
			}
			if (result.getInfoLog().isEmpty()) {
				var linkFailed = ShaderError.createGeneral("Link failed");
				errors.add(buffer == ShaderError.IMAGE_PASS ? linkFailed : linkFailed.withPass(buffer));
			}
		}
		device.deleteProgram(result.getProgram());
	}

	// For a reload that keeps the shader's state (see ShaderRenderer.
	// setFragmentShader()'s keepState): hands each new buffer pass the
	// render targets - and so the contents - of the replaced pass for
	// the same buffer, before releaseForReload() would delete them. GL
	// thread only.
	void adoptBufferTargets() {
		for (var pass : bufferPasses) {
			for (var stale : staleBufferPasses) {
				if (stale.buffer == pass.buffer && stale.hasTargets()) {
					pass.adoptTargets(stale);
					break;
				}
			}
		}
	}

	// Whether the last setFragmentShader() left the backbuffer's
	// parameters (size, filtering, preset) as they were, so its targets
	// can be kept - see adoptBufferTargets().
	boolean backBufferParametersUnchanged() {
		return previousBackBufferParameters != null && previousBackBufferParameters.equals(
				preparedShaderSource.getBackBufferParameters().toString());
	}

	private void prepareShaderSource() {
		// Kept so releaseForReload() can delete its GL textures later, on
		// the GL thread - this runs on the UI thread, with the GL thread
		// paused, so it can't touch GL itself.
		staleTextureResources.add(textureResources);
		staleBufferPasses.addAll(allBufferPasses());
		previousBackBufferParameters = preparedShaderSource.getFragmentShader() != null
				? preparedShaderSource.getBackBufferParameters().toString()
				: null;
		preparedShaderSource = ShaderSourcePreparer.prepare(
				sourceText,
				version,
				maxTextures);
		// One set of textures (saved images, camera, audio) for the whole
		// shader, shared by name between its passes.
		var samplers = new LinkedHashMap<String, DiscoveredSampler>();
		for (var sampler : preparedShaderSource.getSamplers()) {
			samplers.putIfAbsent(sampler.name(), sampler);
		}
		var passes = new ArrayList<BufferPass>();
		BufferPass sound = null;
		if (bufferSourceTexts != null) {
			for (int buffer = 0; buffer < bufferSourceTexts.length; ++buffer) {
				String bufferSource = bufferSourceTexts[buffer];
				if (bufferSource == null) {
					continue;
				}
				var prepared = ShaderSourcePreparer.prepare(bufferSource, version, maxTextures);
				var pass = new BufferPass(buffer, bufferSource, prepared);
				if (ShaderSourcePreparer.isSoundSource(bufferSource) && sound == null) {
					sound = pass;
				} else {
					passes.add(pass);
				}
				for (var sampler : prepared.getSamplers()) {
					samplers.putIfAbsent(sampler.name(), sampler);
				}
			}
		}
		for (var pass : passes) {
			int target = TextDirective.findTarget(pass.sourceText);
			if (target == ShaderError.IMAGE_PASS) {
				pass.setTextSource(sourceText);
			} else if (target != TextDirective.NONE && bufferSourceTexts != null &&
					target < bufferSourceTexts.length) {
				pass.setTextSource(bufferSourceTexts[target]);
			}
		}
		bufferPasses = List.copyOf(passes);
		soundPass = sound;
		String[] all = new String[1 + (bufferSourceTexts != null ? bufferSourceTexts.length : 0)];
		all[0] = sourceText;
		if (bufferSourceTexts != null) {
			System.arraycopy(bufferSourceTexts, 0, all, 1, bufferSourceTexts.length);
		}
		moduleName = ModuleDirective.find(all);
		steps = findSteps(all);
		textureResources = ShaderTextureResources.create(new ArrayList<>(samplers.values()));
	}

	private void clearPrograms() {
		surfaceProgram = null;
		mainProgram = null;
		surfaceBindings = null;
		mainSourceText = null;
	}
}
