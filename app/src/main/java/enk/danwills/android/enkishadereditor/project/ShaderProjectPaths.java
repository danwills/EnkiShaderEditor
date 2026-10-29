package enk.danwills.android.enkishadereditor.project;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;

public final class ShaderProjectPaths {
	public static final String DEFAULT_ENTRY_FILE = "main.glsl";
	// Offscreen buffer passes (Buffer A-D) live at these reserved paths
	// beside the entry file, which is always the Image pass - see
	// ShaderProject.getBufferSources().
	public static final int MAX_BUFFERS = 4;
	private static final String BUFFER_DIRECTORY = "buffers/";
	private static final String BUFFER_EXTENSION = ".glsl";
	// The Common section - code every pass starts with, as ShaderToy's
	// Common tab (see ShaderSourcePreparer) - lives here, beside the entry
	// file.
	public static final String COMMON_PATH = "common.glsl";
	// A shader's named texts (see ShaderTextDao) live at text/<name>.txt.
	private static final String TEXT_DIRECTORY = "text/";
	private static final String TEXT_EXTENSION = ".txt";

	private ShaderProjectPaths() {
	}

	@NonNull
	public static String normalize(@NonNull String path) {
		String normalized = Objects.requireNonNull(path)
				.replace('\\', '/')
				.trim();

		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("Path must not be empty");
		}

		String[] parts = normalized.split("/+");
		ArrayDeque<String> segments = new ArrayDeque<>(parts.length);
		for (String part : parts) {
			if (part == null || part.isEmpty() || ".".equals(part)) {
				continue;
			}
			if ("..".equals(part)) {
				if (segments.isEmpty()) {
					throw new IllegalArgumentException(
							"Path escapes project root: " + path);
				}
				segments.removeLast();
				continue;
			}
			segments.addLast(part);
		}

		if (segments.isEmpty()) {
			throw new IllegalArgumentException("Path must not be empty: " + path);
		}

		StringBuilder builder = new StringBuilder();
		Iterator<String> iterator = segments.iterator();
		while (iterator.hasNext()) {
			builder.append(iterator.next());
			if (iterator.hasNext()) {
				builder.append('/');
			}
		}
		return builder.toString();
	}

	@NonNull
	public static String getBufferPath(int buffer) {
		return BUFFER_DIRECTORY + getBufferLetter(buffer) + BUFFER_EXTENSION;
	}

	// 0..MAX_BUFFERS - 1 for a buffer pass's path, -1 for anything else.
	public static int getBufferIndex(@NonNull String path) {
		String normalized = normalize(path);
		for (int buffer = 0; buffer < MAX_BUFFERS; ++buffer) {
			if (getBufferPath(buffer).equals(normalized)) {
				return buffer;
			}
		}
		return -1;
	}

	@NonNull
	public static String getTextPath(@NonNull String name) {
		return TEXT_DIRECTORY + name + TEXT_EXTENSION;
	}

	// The text a path is for, or null if it isn't a text's.
	@Nullable
	public static String getTextName(@NonNull String path) {
		String normalized = normalize(path);
		if (!normalized.startsWith(TEXT_DIRECTORY) || !normalized.endsWith(TEXT_EXTENSION)) {
			return null;
		}
		String name = normalized.substring(TEXT_DIRECTORY.length(),
				normalized.length() - TEXT_EXTENSION.length());
		return name.isEmpty() || name.contains("/") ? null : name;
	}

	public static char getBufferLetter(int buffer) {
		return (char) ('A' + buffer);
	}

	@NonNull
	public static String getFileName(@NonNull String path) {
		String normalized = normalize(path);
		int slash = normalized.lastIndexOf('/');
		return slash >= 0 ? normalized.substring(slash + 1) : normalized;
	}
}
