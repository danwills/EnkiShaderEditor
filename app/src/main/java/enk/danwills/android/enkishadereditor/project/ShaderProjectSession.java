package enk.danwills.android.enkishadereditor.project;

import androidx.annotation.NonNull;

import java.io.Serializable;
import java.util.Objects;

public final class ShaderProjectSession implements Serializable {
	@NonNull
	private final ShaderProject project;
	@NonNull
	private final String activeFilePath;
	private final float quality;
	// The backing shaders._id row, or 0 for a session with none (a loose
	// imported file, a scratch/unsaved project) - used to key the
	// compiled-program cache, which such sessions simply don't
	// participate in. See ShaderRenderer/RendererProgramManager.
	private final long shaderId;

	public ShaderProjectSession(@NonNull ShaderProject project,
			@NonNull String activeFilePath,
			float quality) {
		this(project, activeFilePath, quality, 0);
	}

	public ShaderProjectSession(@NonNull ShaderProject project,
			@NonNull String activeFilePath,
			float quality,
			long shaderId) {
		this.project = Objects.requireNonNull(project);
		this.activeFilePath = ShaderProjectPaths.normalize(activeFilePath);
		this.project.requireFile(this.activeFilePath);
		this.quality = quality;
		this.shaderId = shaderId;
	}

	@NonNull
	public ShaderProject getProject() {
		return project;
	}

	@NonNull
	public String getActiveFilePath() {
		return activeFilePath;
	}

	@NonNull
	public ShaderProjectFile getActiveFile() {
		return project.requireFile(activeFilePath);
	}

	@NonNull
	public ShaderProjectFile getEntryFile() {
		return project.getEntryFile();
	}

	@NonNull
	public String getEntryPointSource() {
		return getEntryFile().source();
	}

	public float getQuality() {
		return quality;
	}

	public long getShaderId() {
		return shaderId;
	}

	@NonNull
	public ShaderProjectSession withActiveFile(@NonNull String path) {
		return new ShaderProjectSession(project, path, quality, shaderId);
	}

	@NonNull
	public ShaderProjectSession withQuality(float quality) {
		return new ShaderProjectSession(project, activeFilePath, quality, shaderId);
	}

	@NonNull
	public ShaderProjectSession withShaderId(long shaderId) {
		return new ShaderProjectSession(project, activeFilePath, quality, shaderId);
	}

	@NonNull
	public ShaderProjectSession withEntryPointSource(@NonNull String source) {
		return new ShaderProjectSession(
				project.withFileSource(project.getEntryFilePath(), source),
				activeFilePath,
				quality,
				shaderId);
	}

	// See ShaderProject.withBufferSources(). Falls back to the entry file
	// as the active one if the active buffer was just removed.
	@NonNull
	public ShaderProjectSession withBufferSources(@NonNull String[] sources) {
		ShaderProject updated = project.withBufferSources(sources);
		return new ShaderProjectSession(
				updated,
				updated.hasFile(activeFilePath)
						? activeFilePath
						: updated.getEntryFilePath(),
				quality,
				shaderId);
	}

	@NonNull
	public ShaderProjectSession withFileSource(@NonNull String path,
			@NonNull String source) {
		String normalizedPath = ShaderProjectPaths.normalize(path);
		return new ShaderProjectSession(
				project.withFileSource(normalizedPath, source),
				activeFilePath.equals(normalizedPath)
						? normalizedPath
						: activeFilePath,
				quality,
				shaderId);
	}
}
