package enk.danwills.android.enkishadereditor.project;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ShaderProject implements Serializable {
	@NonNull
	private final ShaderProjectOrigin origin;
	@NonNull
	private final String title;
	@NonNull
	private final String entryFilePath;
	@NonNull
	private final List<ShaderProjectFile> files;
	@NonNull
	private final Map<String, ShaderProjectFile> filesByPath;

	public ShaderProject(@NonNull ShaderProjectOrigin origin,
			@NonNull String title,
			@NonNull String entryFilePath,
			@NonNull List<ShaderProjectFile> files) {
		this.origin = Objects.requireNonNull(origin);
		this.title = requireText(title, "title");
		this.entryFilePath = ShaderProjectPaths.normalize(entryFilePath);

		ArrayList<ShaderProjectFile> orderedFiles = new ArrayList<>(
				Objects.requireNonNull(files).size());
		LinkedHashMap<String, ShaderProjectFile> indexedFiles = new LinkedHashMap<>();
		for (ShaderProjectFile file : files) {
			ShaderProjectFile projectFile = Objects.requireNonNull(file);
			if (indexedFiles.put(projectFile.path(), projectFile) != null) {
				throw new IllegalArgumentException(
						"Duplicate project file: " + projectFile.path());
			}
			orderedFiles.add(projectFile);
		}

		if (!indexedFiles.containsKey(this.entryFilePath)) {
			throw new IllegalArgumentException(
					"Missing entry file: " + this.entryFilePath);
		}

		this.files = Collections.unmodifiableList(orderedFiles);
		this.filesByPath = Collections.unmodifiableMap(indexedFiles);
	}

	@NonNull
	public ShaderProjectOrigin getOrigin() {
		return origin;
	}

	@NonNull
	public String getTitle() {
		return title;
	}

	@NonNull
	public String getEntryFilePath() {
		return entryFilePath;
	}

	@NonNull
	public List<ShaderProjectFile> getFiles() {
		return files;
	}

	@Nullable
	public ShaderProjectFile getFile(@NonNull String path) {
		return filesByPath.get(ShaderProjectPaths.normalize(path));
	}

	public boolean hasFile(@NonNull String path) {
		return getFile(path) != null;
	}

	@NonNull
	public ShaderProjectFile requireFile(@NonNull String path) {
		ShaderProjectFile file = getFile(path);
		if (file == null) {
			throw new IllegalArgumentException(
					"Unknown project file: " + ShaderProjectPaths.normalize(path));
		}
		return file;
	}

	@NonNull
	public ShaderProjectFile getEntryFile() {
		return requireFile(entryFilePath);
	}

	@NonNull
	public ShaderProject withFileSource(@NonNull String path, @NonNull String source) {
		String normalizedPath = ShaderProjectPaths.normalize(path);
		requireFile(normalizedPath);

		ArrayList<ShaderProjectFile> updatedFiles = new ArrayList<>(files.size());
		for (ShaderProjectFile file : files) {
			updatedFiles.add(file.path().equals(normalizedPath)
					? file.withSource(source)
					: file);
		}
		return new ShaderProject(origin, title, entryFilePath, updatedFiles);
	}

	// Indexed by buffer (0 = A), null where the project has no such
	// buffer - see ShaderProjectPaths.getBufferPath().
	@NonNull
	public String[] getBufferSources() {
		String[] sources = new String[ShaderProjectPaths.MAX_BUFFERS];
		for (int buffer = 0; buffer < sources.length; ++buffer) {
			ShaderProjectFile file = filesByPath.get(ShaderProjectPaths.getBufferPath(buffer));
			sources[buffer] = file != null ? file.source() : null;
		}
		return sources;
	}

	public boolean hasBuffers() {
		for (String source : getBufferSources()) {
			if (source != null) {
				return true;
			}
		}
		return false;
	}

	// Replaces every buffer file with `sources` (same layout as
	// getBufferSources()), keeping all other files as they are.
	@NonNull
	public ShaderProject withBufferSources(@NonNull String[] sources) {
		ArrayList<ShaderProjectFile> updatedFiles = new ArrayList<>(files.size());
		for (ShaderProjectFile file : files) {
			if (ShaderProjectPaths.getBufferIndex(file.path()) < 0) {
				updatedFiles.add(file);
			}
		}
		for (int buffer = 0; buffer < ShaderProjectPaths.MAX_BUFFERS; ++buffer) {
			if (buffer < sources.length && sources[buffer] != null) {
				updatedFiles.add(new ShaderProjectFile(
						ShaderProjectPaths.getBufferPath(buffer), sources[buffer]));
			}
		}
		return new ShaderProject(origin, title, entryFilePath, updatedFiles);
	}

	// The Common section (see ShaderProjectPaths.COMMON_PATH), or null if
	// the project has none.
	@Nullable
	public String getCommonSource() {
		ShaderProjectFile file = filesByPath.get(ShaderProjectPaths.COMMON_PATH);
		return file != null ? file.source() : null;
	}

	// The project with `source` as its Common section, or none for null,
	// keeping all other files as they are.
	@NonNull
	public ShaderProject withCommonSource(@Nullable String source) {
		ArrayList<ShaderProjectFile> updatedFiles = new ArrayList<>(files.size() + 1);
		for (ShaderProjectFile file : files) {
			if (!ShaderProjectPaths.COMMON_PATH.equals(file.path())) {
				updatedFiles.add(file);
			}
		}
		if (source != null) {
			updatedFiles.add(new ShaderProjectFile(ShaderProjectPaths.COMMON_PATH, source));
		}
		return new ShaderProject(origin, title, entryFilePath, updatedFiles);
	}

	// The project's texts (see ShaderProjectPaths.getTextPath()), name ->
	// text, in file order.
	@NonNull
	public Map<String, String> getTexts() {
		var texts = new LinkedHashMap<String, String>();
		for (ShaderProjectFile file : files) {
			String name = ShaderProjectPaths.getTextName(file.path());
			if (name != null) {
				texts.put(name, file.source());
			}
		}
		return texts;
	}

	// Replaces every text file with `texts`, keeping all other files.
	@NonNull
	public ShaderProject withTexts(@NonNull Map<String, String> texts) {
		ArrayList<ShaderProjectFile> updatedFiles = new ArrayList<>(files.size());
		for (ShaderProjectFile file : files) {
			if (ShaderProjectPaths.getTextName(file.path()) == null) {
				updatedFiles.add(file);
			}
		}
		for (var entry : texts.entrySet()) {
			updatedFiles.add(new ShaderProjectFile(
					ShaderProjectPaths.getTextPath(entry.getKey()), entry.getValue()));
		}
		return new ShaderProject(origin, title, entryFilePath, updatedFiles);
	}

	@NonNull
	private static String requireText(String value, String name) {
		String text = Objects.requireNonNull(value, name).trim();
		if (text.isEmpty()) {
			throw new IllegalArgumentException(name + " must not be empty");
		}
		return text;
	}
}
