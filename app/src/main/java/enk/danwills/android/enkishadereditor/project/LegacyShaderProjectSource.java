package enk.danwills.android.enkishadereditor.project;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import enk.danwills.android.enkishadereditor.database.DataRecords;

public final class LegacyShaderProjectSource implements ShaderProjectSource {
	@NonNull
	private final DataRecords.Shader shader;
	@NonNull
	private final ShaderProjectOrigin origin;
	@NonNull
	private final String title;

	// Indexed by buffer, see ShaderProject.getBufferSources().
	@NonNull
	private final String[] bufferSources;
	// Name -> text, see ShaderProject.getTexts().
	@NonNull
	private final Map<String, String> texts;

	public LegacyShaderProjectSource(@NonNull DataRecords.Shader shader) {
		this(shader, new String[ShaderProjectPaths.MAX_BUFFERS]);
	}

	public LegacyShaderProjectSource(@NonNull DataRecords.Shader shader,
			@NonNull String[] bufferSources) {
		this(shader, bufferSources, Map.of());
	}

	public LegacyShaderProjectSource(@NonNull DataRecords.Shader shader,
			@NonNull String[] bufferSources, @NonNull Map<String, String> texts) {
		this.shader = Objects.requireNonNull(shader);
		this.bufferSources = Objects.requireNonNull(bufferSources);
		this.texts = Objects.requireNonNull(texts);
		title = getTitle(shader);
		origin = new ShaderProjectOrigin(
				ShaderProjectOrigin.Type.LEGACY_DB,
				"shader:" + shader.id(),
				title);
	}

	@NonNull
	@Override
	public ShaderProjectOrigin getOrigin() {
		return origin;
	}

	@NonNull
	@Override
	public ShaderProjectSession openSession() {
		ShaderProject project = new ShaderProject(
				origin,
				title,
				ShaderProjectPaths.DEFAULT_ENTRY_FILE,
				List.of(new ShaderProjectFile(
						ShaderProjectPaths.DEFAULT_ENTRY_FILE,
						shader.fragmentShader())))
				.withBufferSources(bufferSources)
				.withTexts(texts);
		return new ShaderProjectSession(
				project,
				project.getEntryFilePath(),
				shader.quality(),
				shader.id());
	}

	@NonNull
	private static String getTitle(@NonNull DataRecords.Shader shader) {
		String title = shader.getTitle();
		if (title == null || title.trim().isEmpty()) {
			return "Shader " + shader.id();
		}
		return title;
	}
}
