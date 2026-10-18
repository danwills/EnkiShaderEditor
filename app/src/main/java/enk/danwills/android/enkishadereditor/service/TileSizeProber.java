package enk.danwills.android.enkishadereditor.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import enk.danwills.android.enkishadereditor.opengl.TileBucketSize;
import enk.danwills.android.enkishadereditor.opengl.TileSafetyProbe;

// Finds the largest tile size a given shader can render at a given target
// resolution without tripping a GPU driver's hang-detection watchdog - by
// actually trying, in TileProbeService's isolated :probe process, where a
// crash costs only that disposable process (see that class's own doc for
// the full reasoning). Steps up through the TileBucketSize values in
// ascending pixel order (see buildRungs() for why that isn't simply
// values() order) one at a time and stops at the first failure - risk is
// assumed monotonic in pixel count, so there's nothing to gain from
// testing past a failure. If every bucket still leaves tiling *required*
// (the target resolution genuinely exceeds MAX's own 2048px), one further
// rung tests the whole target resolution in a single untiled pass - if
// that's safe too, tiling buys nothing and can be skipped entirely (see
// HighResScreenshotCapture.capture()'s own `singlePass` branch, which
// already takes over automatically whenever TileBucketSize.MAX is
// selected and the resolution fits one texture - reporting MAX as the
// result here is what actually triggers that, no separate plumbing
// needed). "Failure" covers a probe-process crash, a timeout, and a draw
// that survived but demonstrably didn't finish (see TileSafetyProbe's
// sentinel check and looksTruncated() below) - some drivers silently cut
// an over-long draw short at a fixed deadline instead of killing anything.
public final class TileSizeProber {
	// One rung of the ladder - pixels is the tile's actual content size
	// as tested (clamped to the frame - see buildRungs()); bucketSize is
	// the largest real TileBucketSize that maps to it, or null for the
	// single synthetic whole-frame rung above MAX.
	private record Rung(@NonNull String label, int pixels, @Nullable TileBucketSize bucketSize) {
	}

	public interface Listener {
		// Called (on the calling thread - see findMaxSafeTileSize()'s own
		// doc) right before each attempt starts, so a caller can show
		// progress.
		void onAttemptStarting(@NonNull String label, int attemptNumber, int totalAttempts);

		// Called right after that same attempt resolves - exactly one of
		// these per onAttemptStarting() call, always before the next
		// onAttemptStarting() (if any).
		void onAttemptFinished(@NonNull String label, boolean safe);
	}

	// The search's final verdict. safeBucketSize is the largest real
	// TileBucketSize confirmed safe, or null if even the smallest failed
	// (or the search was cancelled before it finished - callers
	// distinguish those via their own cancel flag). wholeFrameSafe is
	// only ever true when safeBucketSize is TileBucketSize.MAX *and* the
	// whole-frame rung above it was both offered (tiling was genuinely
	// required - see this class's own doc) and itself confirmed safe.
	public record SearchResult(@Nullable TileBucketSize safeBucketSize, boolean wholeFrameSafe) {
	}

	// Generous on purpose: a *safe* render at a large size and high
	// resolution can legitimately take a long time without ever being in
	// danger, and this is only ever meant to catch a probe that neither
	// completed nor got killed - not to pass judgment on performance.
	// Treated as a failure (same as a crash) purely to fail safe rather
	// than risk ever reporting an untested size as SAFE.
	private static final long TIMEOUT_MS = 60_000;
	// See looksTruncated() - below this, timing noise dominates, and no
	// driver deadline seen so far is anywhere near that short anyway.
	private static final long TRUNCATION_MIN_DRAW_MS = 500;
	// The longest a single probe draw may take and still count as safe -
	// a deliberate margin under the ~2s per-draw deadline the dev
	// device's driver enforces (draws hitting it come back "finished" in
	// almost exactly 2s, often with the framebuffer still looking fully
	// written, so neither glGetError() nor the sentinel readback can tell
	// them apart from a genuine completion). Being *under* the deadline
	// isn't enough: the real capture's own tiles vary in cost with
	// position (the probe only renders one), and a tile that does hit it
	// there resets the GPU and takes the main app down with it.
	private static final long MAX_SAFE_DRAW_MS = 1200;
	private static final String TAG = "TileSizeProber";

	private TileSizeProber() {
	}

	// Blocks for the whole search - must not be called from the main
	// thread. singlePassPossible should be the same `oversample == 1`
	// check the real capture itself gates singlePass eligibility on
	// (see HighResScreenshotCapture.isSinglePassEligible()) - the
	// whole-frame rung is pointless to even test when it's false, since
	// no resolved bucket size could make the real capture take that path
	// anyway.
	@NonNull
	public static SearchResult findMaxSafeTileSize(
			@NonNull Context context,
			@NonNull String fragmentSource,
			int targetWidth,
			int targetHeight,
			boolean singlePassPossible,
			@Nullable Listener listener,
			@Nullable BooleanSupplier cancelled) {
		List<Rung> rungs = buildRungs(targetWidth, targetHeight, singlePassPossible);
		int totalAttempts = rungs.size();
		Log.d(TAG, "findMaxSafeTileSize: starting, rungs=" + rungs + " targetWidth=" + targetWidth +
				" targetHeight=" + targetHeight + " singlePassPossible=" + singlePassPossible +
				" sourceLength=" + fragmentSource.length());
		TileBucketSize safeBucketSize = null;
		boolean wholeFrameSafe = false;
		long prevDrawMs = -1;
		long prevArea = 0;
		for (int i = 0; i < rungs.size(); ++i) {
			if (cancelled != null && cancelled.getAsBoolean()) {
				Log.d(TAG, "findMaxSafeTileSize: cancelled after " + i + " of " + totalAttempts + " rungs");
				break;
			}
			Rung rung = rungs.get(i);
			int attempt = i + 1;
			Log.d(TAG, "findMaxSafeTileSize: attempt " + attempt + "/" + totalAttempts + " trying " + rung);
			if (listener != null) {
				listener.onAttemptStarting(rung.label(), attempt, totalAttempts);
			}
			long drawMs = probeOne(context, fragmentSource, targetWidth, targetHeight, rung.pixels());
			long area = (long) rung.pixels() * rung.pixels();
			boolean withinBudget = drawMs <= MAX_SAFE_DRAW_MS;
			if (drawMs >= 0 && !withinBudget) {
				Log.w(TAG, "findMaxSafeTileSize: drawMs=" + drawMs + " exceeds the " + MAX_SAFE_DRAW_MS +
						"ms per-draw budget");
			}
			boolean safe = drawMs >= 0 && withinBudget &&
					!looksTruncated(prevDrawMs, prevArea, drawMs, area);
			Log.d(TAG, "findMaxSafeTileSize: " + rung.label() + " drawMs=" + drawMs + " -> " +
					(safe ? "SAFE" : "UNSAFE"));
			if (listener != null) {
				listener.onAttemptFinished(rung.label(), safe);
			}
			if (!safe) {
				break;
			}
			prevDrawMs = drawMs;
			prevArea = area;
			if (rung.bucketSize() != null) {
				safeBucketSize = rung.bucketSize();
			} else {
				wholeFrameSafe = true;
			}
		}
		SearchResult result = new SearchResult(safeBucketSize, wholeFrameSafe);
		Log.d(TAG, "findMaxSafeTileSize: done, result=" + result);
		return result;
	}

	// Backs up TileSafetyProbe's own sentinel readback for drivers that
	// cut a draw short at some fixed deadline but leave the framebuffer
	// looking written (or reset it) - seen on the dev device as 256px
	// through 2048px tiles all "finishing" in almost exactly 2s, despite
	// each being 4x the last one's pixels. GPU time for one shader scales
	// roughly with pixel count, so a draw that took well under a quarter
	// of what the previous rung's per-pixel rate predicts - while being
	// long enough to plausibly be hitting a deadline at all - most likely
	// didn't actually finish.
	private static boolean looksTruncated(long prevDrawMs, long prevArea, long drawMs, long area) {
		if (prevDrawMs <= 0 || prevArea <= 0 || drawMs < TRUNCATION_MIN_DRAW_MS) {
			return false;
		}
		double expectedMs = prevDrawMs * ((double) area / prevArea);
		boolean truncated = drawMs < expectedMs * 0.25;
		if (truncated) {
			Log.w(TAG, "looksTruncated: drawMs=" + drawMs + " but expected ~" + (long) expectedMs +
					" from the previous rung (" + prevDrawMs + "ms at " + prevArea + "px^2)");
		}
		return truncated;
	}

	// Rungs in ascending pixel order - deliberately not
	// TileBucketSize.values()'s own declaration order, where TINY comes
	// *last* purely for stored-preference compatibility (an earlier
	// version of this search walked values() directly, tested TINY last,
	// and so reported it as the "largest" safe size). A tile can never be
	// bigger than the frame (the real capture clamps tileContentSize the
	// same way), so every bucket at or past the frame size collapses into
	// one rung tested at the frame size itself, reported as the largest
	// bucket mapping to it - MAX whenever MAX is among them, which is
	// what lets a whole-frame result go straight to the untiled
	// singlePass path when oversample allows. A separate whole-frame rung
	// is only added when the frame genuinely exceeds MAX (and
	// singlePassPossible).
	@NonNull
	private static List<Rung> buildRungs(int targetWidth, int targetHeight, boolean singlePassPossible) {
		int framePixels = Math.max(targetWidth, targetHeight);
		List<TileBucketSize> sorted = new ArrayList<>(List.of(TileBucketSize.values()));
		Collections.sort(sorted, (a, b) -> Integer.compare(a.pixels, b.pixels));
		List<Rung> rungs = new ArrayList<>();
		for (TileBucketSize size : sorted) {
			int effective = Math.min(size.pixels, framePixels);
			int lastIndex = rungs.size() - 1;
			if (lastIndex >= 0 && rungs.get(lastIndex).pixels() == effective) {
				rungs.set(lastIndex, new Rung(size.name(), effective, size));
			} else {
				rungs.add(new Rung(size.name(), effective, size));
			}
		}
		if (singlePassPossible && framePixels > TileBucketSize.MAX.pixels) {
			rungs.add(new Rung("WHOLE_FRAME", framePixels, null));
		}
		return rungs;
	}

	// One full bind -> send -> await -> unbind round-trip against a
	// fresh TileProbeService process, returning the probe's own measured
	// draw time in ms when it came back SAFE, or -1 for every other
	// outcome (crash, timeout, incomplete render, compile failure).
	// Binder callbacks (onServiceConnected/onServiceDisconnected) always
	// land on the main thread regardless of which thread called
	// bindService() - this blocks the *calling* thread on a latch those
	// main-thread callbacks count down, so findMaxSafeTileSize()'s loop
	// above can stay a plain sequential search.
	private static long probeOne(@NonNull Context context, @NonNull String fragmentSource,
			int targetWidth, int targetHeight, int tilePixels) {
		CountDownLatch latch = new CountDownLatch(1);
		long[] drawMs = {-1};

		Messenger replyMessenger = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
			if (msg.what == TileProbeService.MSG_RESULT) {
				if (msg.arg1 == TileSafetyProbe.Result.SAFE.ordinal()) {
					drawMs[0] = msg.arg2;
				} else {
					Log.w(TAG, "probeOne: probe reported " + TileSafetyProbe.Result.values()[msg.arg1] +
							" drawMs=" + msg.arg2);
				}
				latch.countDown();
			}
			return true;
		}));

		ServiceConnection connection = new ServiceConnection() {
			@Override
			public void onServiceConnected(ComponentName name, IBinder service) {
				Message msg = Message.obtain(null, TileProbeService.MSG_PROBE);
				Bundle data = new Bundle();
				data.putString(TileProbeService.KEY_FRAGMENT_SOURCE, fragmentSource);
				data.putInt(TileProbeService.KEY_TARGET_WIDTH, targetWidth);
				data.putInt(TileProbeService.KEY_TARGET_HEIGHT, targetHeight);
				data.putInt(TileProbeService.KEY_TILE_BUCKET_PIXELS, tilePixels);
				msg.setData(data);
				msg.replyTo = replyMessenger;
				try {
					new Messenger(service).send(msg);
				} catch (RemoteException e) {
					Log.e(TAG, "probeOne: send() failed", e);
					latch.countDown();
				}
			}

			@Override
			public void onServiceDisconnected(ComponentName name) {
				// The probe process died mid-render, with no reply ever
				// sent - exactly the watchdog-kill signal this whole
				// mechanism exists to detect. drawMs[0] stays -1 (its
				// default) - that *is* the verdict.
				Log.w(TAG, "probeOne: onServiceDisconnected - probe process died (tilePixels=" +
						tilePixels + ")");
				latch.countDown();
			}
		};

		Intent intent = new Intent(context, TileProbeService.class);
		if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
			Log.e(TAG, "probeOne: bindService failed");
			return -1;
		}
		try {
			if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				Log.w(TAG, "probeOne: timed out after " + TIMEOUT_MS + "ms, treating as unsafe");
				drawMs[0] = -1;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			drawMs[0] = -1;
		} finally {
			context.unbindService(connection);
		}
		return drawMs[0];
	}
}
