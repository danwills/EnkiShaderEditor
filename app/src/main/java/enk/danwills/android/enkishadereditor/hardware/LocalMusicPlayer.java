package enk.danwills.android.enkishadereditor.hardware;

import android.content.Context;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Plays through a directory of MP3 files one at a time (see
 * MainActivity's local-music-player dialog), looping back to the start
 * once the last track finishes. Holds one MediaPlayer for the whole
 * playlist's lifetime, pinned to a single, explicitly-generated audio
 * session id (see the constructor) - without that, a plain reset()
 * between tracks could hand the player a *new* session id each time,
 * which would silently break AudioSpectrumListener.registerVisualizer()
 * (attached once, by id, from BuiltinAudioUniforms) on every track
 * change. Intentionally owned by MainActivity, not a foreground
 * service - playback survives the control dialog being dismissed, but
 * not the app being backgrounded; see MainActivity's own comment on
 * showLocalMusicPlayerDialog() for why that's an acceptable first cut.
 */
public class LocalMusicPlayer {
	private static final String TAG = "LocalMusicPlayer";

	public interface Listener {
		void onTrackChanged(@NonNull String trackName);

		void onPlaybackStateChanged(boolean playing);
	}

	private record Track(@NonNull Uri uri, @NonNull String name) {
	}

	@NonNull
	private final Context context;
	@NonNull
	private final MediaPlayer mediaPlayer = new MediaPlayer();
	private final int audioSessionId;
	@NonNull
	private List<Track> tracks = Collections.emptyList();
	private int currentIndex = -1;
	@Nullable
	private Listener listener;

	public LocalMusicPlayer(@NonNull Context context) {
		this.context = context.getApplicationContext();
		AudioManager audioManager =
				(AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
		audioSessionId = audioManager != null
				? audioManager.generateAudioSessionId()
				: mediaPlayer.getAudioSessionId();
		try {
			mediaPlayer.setAudioSessionId(audioSessionId);
		} catch (IllegalArgumentException | IllegalStateException e) {
			Log.e(TAG, "setAudioSessionId failed", e);
		}
		mediaPlayer.setOnCompletionListener(mp -> next());
		mediaPlayer.setOnErrorListener((mp, what, extra) -> {
			Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra
					+ " - skipping to the next track");
			next();
			return true;
		});
	}

	public void setListener(@Nullable Listener listener) {
		this.listener = listener;
	}

	public int getAudioSessionId() {
		return audioSessionId;
	}

	// Lists every *.mp3 directly inside treeUri (not recursing into
	// subfolders) via plain DocumentsContract queries - avoids needing
	// the androidx.documentfile dependency just for this one lookup.
	// Returns false (and leaves any previous playlist untouched) if the
	// folder couldn't be read or has no MP3s in it.
	public boolean loadDirectory(@NonNull Uri treeUri) {
		List<Track> found = new ArrayList<>();
		String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
		Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId);
		try (Cursor cursor = context.getContentResolver().query(childrenUri, new String[]{
				DocumentsContract.Document.COLUMN_DOCUMENT_ID,
				DocumentsContract.Document.COLUMN_DISPLAY_NAME,
		}, null, null, null)) {
			if (cursor != null) {
				while (cursor.moveToNext()) {
					String docId = cursor.getString(0);
					String name = cursor.getString(1);
					if (docId != null && name != null
							&& name.toLowerCase(Locale.ROOT).endsWith(".mp3")) {
						found.add(new Track(
								DocumentsContract.buildDocumentUriUsingTree(treeUri, docId), name));
					}
				}
			}
		} catch (SecurityException | IllegalArgumentException e) {
			Log.e(TAG, "loadDirectory failed", e);
			return false;
		}
		if (found.isEmpty()) {
			return false;
		}
		Collections.sort(found, (a, b) -> a.name().compareToIgnoreCase(b.name()));
		tracks = found;
		currentIndex = -1;
		return true;
	}

	public int getTrackCount() {
		return tracks.size();
	}

	public void play() {
		if (tracks.isEmpty()) {
			return;
		}
		if (currentIndex < 0) {
			playIndex(0);
			return;
		}
		try {
			mediaPlayer.start();
			notifyPlaybackState(true);
		} catch (IllegalStateException e) {
			Log.e(TAG, "play failed", e);
		}
	}

	public void pause() {
		try {
			if (currentIndex >= 0 && mediaPlayer.isPlaying()) {
				mediaPlayer.pause();
				notifyPlaybackState(false);
			}
		} catch (IllegalStateException e) {
			Log.e(TAG, "pause failed", e);
		}
	}

	public void togglePlayPause() {
		if (isPlaying()) {
			pause();
		} else {
			play();
		}
	}

	public void next() {
		if (!tracks.isEmpty()) {
			playIndex(currentIndex + 1);
		}
	}

	public void previous() {
		if (!tracks.isEmpty()) {
			playIndex(currentIndex - 1);
		}
	}

	public void seekTo(int ms) {
		try {
			if (currentIndex >= 0) {
				mediaPlayer.seekTo(ms);
			}
		} catch (IllegalStateException e) {
			Log.e(TAG, "seekTo failed", e);
		}
	}

	public boolean isPlaying() {
		try {
			return currentIndex >= 0 && mediaPlayer.isPlaying();
		} catch (IllegalStateException e) {
			return false;
		}
	}

	public int getCurrentPosition() {
		try {
			return currentIndex >= 0 ? mediaPlayer.getCurrentPosition() : 0;
		} catch (IllegalStateException e) {
			return 0;
		}
	}

	public int getDuration() {
		try {
			return currentIndex >= 0 ? mediaPlayer.getDuration() : 0;
		} catch (IllegalStateException e) {
			return 0;
		}
	}

	@Nullable
	public String getCurrentTrackName() {
		return currentIndex >= 0 && currentIndex < tracks.size()
				? tracks.get(currentIndex).name()
				: null;
	}

	public void release() {
		mediaPlayer.setOnCompletionListener(null);
		mediaPlayer.setOnErrorListener(null);
		mediaPlayer.setOnPreparedListener(null);
		mediaPlayer.release();
	}

	// Wraps around in both directions (Math.floorMod), same as the
	// shader-navigation history's own wraparound - playing through a
	// whole folder on loop, start to end to start again, is the
	// obviously-expected behavior for this, not stopping dead at
	// either end.
	private void playIndex(int index) {
		currentIndex = Math.floorMod(index, tracks.size());
		Track track = tracks.get(currentIndex);
		try {
			mediaPlayer.reset();
			mediaPlayer.setAudioSessionId(audioSessionId);
			mediaPlayer.setDataSource(context, track.uri());
			mediaPlayer.setOnPreparedListener(mp -> {
				mp.start();
				notifyPlaybackState(true);
			});
			mediaPlayer.prepareAsync();
			notifyTrackChanged(track.name());
		} catch (IOException | IllegalStateException | IllegalArgumentException
				| SecurityException e) {
			Log.e(TAG, "playIndex failed for " + track.name(), e);
		}
	}

	private void notifyTrackChanged(@NonNull String name) {
		if (listener != null) {
			listener.onTrackChanged(name);
		}
	}

	private void notifyPlaybackState(boolean playing) {
		if (listener != null) {
			listener.onPlaybackStateChanged(playing);
		}
	}
}
