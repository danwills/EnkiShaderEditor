package enk.danwills.android.enkishadereditor.service;

import android.media.projection.MediaProjection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// A MediaProjection that Record Screen and Enki's audio input both use:
// Android allows one at a time, and starting a recording stops the one
// the audio input had, so ScreenRecordService hands its own to the audio
// input as well. Whoever lets go of it last stops it - so a rotation
// (which releases the audio input's) doesn't end a recording, and the
// end of a recording doesn't silence the shader.
public final class SharedProjection {
	private static MediaProjection projection;
	private static int users;

	private SharedProjection() {
	}

	// The recording's projection, its first user.
	static synchronized void share(@NonNull MediaProjection projection) {
		SharedProjection.projection = projection;
		users = 1;
	}

	// The recording's projection for one more user (the audio input), to
	// release() when done; null when there's none.
	@Nullable
	public static synchronized MediaProjection borrow() {
		if (projection == null) {
			return null;
		}
		++users;
		return projection;
	}

	// Stops projection - or, while it's shared, lets go of one user's
	// share, stopping it once the last has gone.
	public static synchronized void release(@NonNull MediaProjection projection) {
		if (projection == SharedProjection.projection) {
			if (--users > 0) {
				return;
			}
			SharedProjection.projection = null;
		}
		projection.stop();
	}
}
