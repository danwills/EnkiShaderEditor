package enk.danwills.android.enkishadereditor.service;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import enk.danwills.android.enkishadereditor.opengl.TileSafetyProbe;

// Runs in its own OS process (android:process=":probe" in the manifest),
// deliberately isolated from the main app process - see TileSizeProber's
// own doc for the full picture, but in short: the whole point of this
// service is to safely attempt renders that might trip a GPU driver's
// hang-detection watchdog, which kills the *entire owning process*
// outright with no catchable exception anywhere in Java/native code (see
// HighResScreenshotCapture's own doc on exactly this problem - tiled
// capture already works around it by keeping draw calls conservatively
// small; this service exists to find how small "small enough" actually
// needs to be for one specific shader, rather than always assuming the
// worst case). Running that risk in a disposable, unexported, UI-less
// process means a crash here costs nothing more than this one process -
// TileSizeProber (in the main process) detects it via
// ServiceConnection.onServiceDisconnected() and just tries a smaller tile
// size next, while the user's actual app keeps running completely
// unaffected throughout. No new permission is needed for any of this:
// a declared android:process is purely an internal app-architecture
// choice, not something the system asks the user to grant.
//
// A plain Messenger-based protocol, not AIDL - exactly one request type
// (MSG_PROBE) and one reply type (MSG_RESULT), nothing complex enough to
// need more.
public final class TileProbeService extends Service {
	public static final int MSG_PROBE = 1;
	public static final int MSG_RESULT = 2;

	public static final String KEY_FRAGMENT_SOURCE = "fragment_source";
	public static final String KEY_TARGET_WIDTH = "target_width";
	public static final String KEY_TARGET_HEIGHT = "target_height";
	public static final String KEY_TILE_BUCKET_PIXELS = "tile_bucket_pixels";
	public static final String KEY_SAMPLE_OFFSETS = "sample_offsets";

	private static final String TAG = "TileProbeService";

	// The actual probe render happens here, never on this service's own
	// main thread - a SAFE-but-slow render could otherwise trigger an
	// ANR for this (invisible, background) process, which would be a
	// confusing thing for the user to stumble across for no good reason.
	// An *unsafe* render still takes this whole process down regardless
	// of which thread triggered it - that one failure mode can never be
	// protected against by choice of thread, by design (see this class's
	// own doc above).
	private HandlerThread workerThread;
	private Handler workerHandler;

	@Override
	public void onCreate() {
		super.onCreate();
		workerThread = new HandlerThread("TileProbeWorker");
		workerThread.start();
		workerHandler = new Handler(workerThread.getLooper());
	}

	@Override
	public void onDestroy() {
		workerThread.quitSafely();
		super.onDestroy();
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return new Messenger(new Handler(Looper.getMainLooper(), this::handleMessage)).getBinder();
	}

	private boolean handleMessage(@NonNull Message msg) {
		Log.d(TAG, "handleMessage: received what=" + msg.what);
		if (msg.what != MSG_PROBE) {
			return false;
		}
		Messenger replyTo = msg.replyTo;
		Bundle data = msg.getData();
		String fragmentSource = data != null ? data.getString(KEY_FRAGMENT_SOURCE) : null;
		int targetWidth = data != null ? data.getInt(KEY_TARGET_WIDTH) : 0;
		int targetHeight = data != null ? data.getInt(KEY_TARGET_HEIGHT) : 0;
		int tileBucketPixels = data != null ? data.getInt(KEY_TILE_BUCKET_PIXELS) : 0;
		int[] sampleOffsets = data != null ? data.getIntArray(KEY_SAMPLE_OFFSETS) : null;
		if (sampleOffsets == null) {
			sampleOffsets = new int[]{0, 0};
		}
		int[] offsets = sampleOffsets;
		Log.d(TAG, "handleMessage: MSG_PROBE replyTo=" + replyTo +
				" fragmentSource=" + (fragmentSource == null ? "null" : fragmentSource.length() + " chars") +
				" targetWidth=" + targetWidth + " targetHeight=" + targetHeight +
				" tileBucketPixels=" + tileBucketPixels);
		if (fragmentSource == null || replyTo == null) {
			Log.e(TAG, "handleMessage: dropping MSG_PROBE - missing fragmentSource or replyTo");
			return true;
		}
		workerHandler.post(() -> {
			TileSafetyProbe.Outcome outcome = TileSafetyProbe.probe(
					fragmentSource, targetWidth, targetHeight, tileBucketPixels, offsets);
			Log.d(TAG, "handleMessage: replying with " + outcome);
			Message reply = Message.obtain(null, MSG_RESULT);
			reply.arg1 = outcome.result().ordinal();
			reply.arg2 = (int) outcome.drawMs();
			try {
				replyTo.send(reply);
			} catch (RemoteException e) {
				Log.w(TAG, "handleMessage: reply failed, caller likely already gone", e);
			}
		});
		return true;
	}
}
