package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

final class ShaderLineMapping {
	private static final ShaderLineMapping IDENTITY =
			new ShaderLineMapping(List.of(), 0, 0);

	@NonNull
	private final List<InsertedLines> insertions;
	// Where the shader's Common section is in the prepared source (its
	// first line, 0 for none, and how many) - see ShaderSourcePreparer.
	private final int commonFirstLine;
	private final int commonLineCount;

	private ShaderLineMapping(@NonNull List<InsertedLines> insertions,
			int commonFirstLine, int commonLineCount) {
		this.insertions = List.copyOf(insertions);
		this.commonFirstLine = commonFirstLine;
		this.commonLineCount = commonLineCount;
	}

	@NonNull
	static ShaderLineMapping identity() {
		return IDENTITY;
	}

	@NonNull
	static ShaderLineMapping withLeadingInsertedLines(int lineCount) {
		return identity().addInsertedLinesAfterSourceLine(0, lineCount);
	}

	int toSourceLine(int preparedLine) {
		if (preparedLine < 1) {
			return -1;
		}

		int removedLines = 0;
		for (InsertedLines insertion : insertions) {
			int startLine = insertion.sourceLine + removedLines + 1;
			int endLine = startLine + insertion.lineCount - 1;
			if (preparedLine < startLine) {
				break;
			}
			if (preparedLine <= endLine) {
				return -1;
			}
			removedLines += insertion.lineCount;
		}

		return Math.max(preparedLine - removedLines, -1);
	}

	// A prepared line's line in the Common section, or -1 if it isn't one
	// of Common's.
	int toCommonLine(int preparedLine) {
		return commonFirstLine > 0 && preparedLine >= commonFirstLine &&
				preparedLine < commonFirstLine + commonLineCount
				? preparedLine - commonFirstLine + 1
				: -1;
	}

	@NonNull
	ShaderLineMapping withCommon(int firstLine, int lineCount) {
		return new ShaderLineMapping(insertions, firstLine, lineCount);
	}

	@NonNull
	ShaderLineMapping addInsertedLinesAfterSourceLine(int sourceLine, int lineCount) {
		if (lineCount <= 0) {
			return this;
		}

		ArrayList<InsertedLines> updatedInsertions = new ArrayList<>(insertions);
		int last = updatedInsertions.size() - 1;
		if (last >= 0) {
			InsertedLines previous = updatedInsertions.get(last);
			if (previous.sourceLine == sourceLine) {
				updatedInsertions.set(last, new InsertedLines(
						sourceLine,
						previous.lineCount + lineCount));
				return new ShaderLineMapping(updatedInsertions, commonFirstLine,
						commonLineCount);
			}
		}
		updatedInsertions.add(new InsertedLines(sourceLine, lineCount));
		return new ShaderLineMapping(updatedInsertions, commonFirstLine, commonLineCount);
	}

	private static final class InsertedLines {
		private final int sourceLine;
		private final int lineCount;

		private InsertedLines(int sourceLine, int lineCount) {
			this.sourceLine = sourceLine;
			this.lineCount = lineCount;
		}
	}
}
