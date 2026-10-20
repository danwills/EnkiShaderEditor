package enk.danwills.android.enkishadereditor.io;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// One frame-sequence video MainActivity's "Play Recent Recordings" (see
// VideoEncoder) found in MediaStore. label is a cleaned-up display name -
// the raw MediaStore DISPLAY_NAME with the ".mp4"/".webm" extension and the
// embedded prose-date substring (see PROSE_DATE_PATTERN) both stripped,
// so the list shows just the shader name without wrapping or visual
// clutter. dateAddedSeconds (MediaStore's own DATE_ADDED column, not a
// re-parse of the stripped-out text) is the reliable thing to actually
// sort/filter by later - dateLabel is kept only in case some future
// feature wants to show or filter by the *rendered* date specifically
// (which can differ from dateAddedSeconds, e.g. a video re-imported or
// re-saved later).
public record RecentRecording(
		@NonNull Uri uri, @NonNull String label, long dateAddedSeconds, @Nullable String dateLabel) {

	// Matches VideoEncoder's own filename date component - see
	// MainActivity.proseDateString(): hour12:minute(am|pm), then an
	// ordinal day (1st/2nd/3rd/4th...) directly against a full English
	// month name (Locale.US, so always English regardless of device
	// locale), then a 4-digit year, e.g. "5:30pm1stJanuary2025" - no
	// separators between those last three, which is why a dedicated
	// pattern (rather than any generic date parser) is needed to find
	// where it starts and ends inside a filename.
	private static final Pattern PROSE_DATE_PATTERN = Pattern.compile(
			"\\d{1,2}:\\d{2}(?:am|pm)\\d{1,2}(?:st|nd|rd|th)[A-Za-z]+\\d{4}");

	@NonNull
	public static RecentRecording fromDisplayName(
			@NonNull Uri uri, @NonNull String displayName, long dateAddedSeconds) {
		String name = displayName;
		int dot = name.lastIndexOf('.');
		String extension = dot > 0 ? name.substring(dot + 1) : "";
		if (extension.equalsIgnoreCase("mp4") || extension.equalsIgnoreCase("webm")) {
			name = name.substring(0, dot);
		}
		String dateLabel = null;
		Matcher matcher = PROSE_DATE_PATTERN.matcher(name);
		if (matcher.find()) {
			dateLabel = matcher.group();
			// Also eats the "." joining the shader name to the date
			// (see VideoEncoder's own "baseShaderName + '.' +
			// proseDateString(...)" construction) so the cleaned label
			// doesn't end up with a trailing dot.
			int start = matcher.start() > 0 && name.charAt(matcher.start() - 1) == '.'
					? matcher.start() - 1
					: matcher.start();
			name = name.substring(0, start) + name.substring(matcher.end());
		}
		return new RecentRecording(uri, name.isEmpty() ? displayName : name, dateAddedSeconds, dateLabel);
	}
}
