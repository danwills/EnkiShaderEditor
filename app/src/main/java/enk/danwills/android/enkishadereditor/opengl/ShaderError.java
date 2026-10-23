package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShaderError {
	private static final Pattern ERROR_PATTERN = Pattern.compile(
			"^.*(\\d+):(\\d+):\\s+(.*)$");

	// Which pass of a multipass shader the error belongs to - the Image
	// pass (the shader's entry file), or an offscreen buffer (0 = A).
	public static final int IMAGE_PASS = -1;
	// The shader's Common section, which every pass starts with - an error
	// in it is the same in every pass, so it's Common's (see
	// ShaderLineMapping.toCommonLine()).
	public static final int COMMON_PASS = -2;

	private final int sourceStringNumber;
	private final int errorLine;
	@NonNull
	private final String message;
	private final int pass;

	private ShaderError(int sourceStringNumber, int errorLine, @NonNull String message) {
		this(sourceStringNumber, errorLine, message, IMAGE_PASS);
	}

	private ShaderError(int sourceStringNumber, int errorLine, @NonNull String message,
			int pass) {
		this.sourceStringNumber = sourceStringNumber;
		this.errorLine = errorLine;
		this.message = message;
		this.pass = pass;
	}

	// The error as `pass`'s - unless it's in Common, which stays Common's.
	@NonNull
	ShaderError withPass(int pass) {
		return this.pass == COMMON_PASS
				? this
				: new ShaderError(sourceStringNumber, errorLine, message, pass);
	}

	public int getPass() {
		return pass;
	}

	public static ShaderError createGeneral(@NonNull String message) {
		return new ShaderError(0, -1, message);
	}

	public boolean hasLine() {
		return errorLine > 0;
	}

	public int getLine() {
		return errorLine;
	}

	@NonNull
	public String getMessage() {
		return message;
	}

	@NonNull
	public static List<ShaderError> parseAll(@NonNull String infoLog) {
		return parseAll(infoLog, 0);
	}

	@NonNull
	public static List<ShaderError> parseAll(
			@NonNull String infoLog,
			int silentlyAddedExtraLines) {
		return parseAll(
				infoLog,
				ShaderLineMapping.withLeadingInsertedLines(silentlyAddedExtraLines));
	}

	@NonNull
	static List<ShaderError> parseAll(
			@NonNull String infoLog,
			@NonNull ShaderLineMapping lineMapping) {
		String[] messages = infoLog.split("\n");
		List<ShaderError> shaderErrors = new ArrayList<>(messages.length);
		for (String message : messages) {
			shaderErrors.add(parse(message, lineMapping));
		}
		return shaderErrors;
	}

	@NonNull
	public static ShaderError parse(@NonNull String message) {
		return parse(message, 0);
	}

	@NonNull
	public static ShaderError parse(
			@NonNull String message,
			int silentlyAddedExtraLines) {
		return parse(
				message,
				ShaderLineMapping.withLeadingInsertedLines(silentlyAddedExtraLines));
	}

	@NonNull
	static ShaderError parse(
			@NonNull String message,
			@NonNull ShaderLineMapping lineMapping) {
		Matcher matcher = ERROR_PATTERN.matcher(message);
		if (!matcher.matches()) {
			return ShaderError.createGeneral(message);
		}
		String sourceString = matcher.group(1);
		if (sourceString == null) {
			return ShaderError.createGeneral(message);
		}
		int sourceStringNumber = Integer.parseInt(sourceString);
		String errorLineString = matcher.group(2);
		if (errorLineString == null) {
			return ShaderError.createGeneral(message);
		}
		int preparedLine = Integer.parseInt(errorLineString);
		String infoLog = Objects.requireNonNull(matcher.group(3));
		int commonLine = lineMapping.toCommonLine(preparedLine);
		if (commonLine > 0) {
			return new ShaderError(sourceStringNumber, commonLine, infoLog, COMMON_PASS);
		}
		int errorLine = lineMapping.toSourceLine(preparedLine);
		return new ShaderError(sourceStringNumber, errorLine, infoLog);
	}

	@Override
	public final boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ShaderError that)) {
			return false;
		}

		return sourceStringNumber == that.sourceStringNumber &&
				errorLine == that.errorLine &&
				pass == that.pass &&
				message.equals(that.message);
	}

	@Override
	public int hashCode() {
		int result = sourceStringNumber;
		result = 31 * result + errorLine;
		result = 31 * result + message.hashCode();
		result = 31 * result + pass;
		return result;
	}

	@NonNull
	@Override
	public String toString() {
		String prefix = pass == IMAGE_PASS
				? ""
				: pass == COMMON_PASS ? "Common: " : "Buffer " + (char) ('A' + pass) + ": ";
		if (hasLine()) {
			return String.format(Locale.getDefault(),
					"%s%d: %s", prefix, errorLine, message);
		} else {
			return prefix + message;
		}
	}
}
