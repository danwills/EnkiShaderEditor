package enk.danwills.android.enkishadereditor.opengl;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import enk.danwills.android.enkishadereditor.app.ShaderEditorApp;
import enk.danwills.android.enkishadereditor.hardware.AudioSpectrumListener;

final class BuiltinAudioUniforms {
	private static final String TAG = "BuiltinAudioUniforms";

	// Set the first time we ask for consent while wanting system audio
	// and not having it yet, so repeated configure() passes (e.g. the
	// context rebuild that happens whenever the consent Activity itself
	// takes focus, or a full Activity recreation while backgrounded for
	// the picker) don't re-launch the request in a loop. Static and
	// process-wide (not tied to one ShaderRenderer/Activity instance) so
	// it survives Activity recreation; cleared when the preference is
	// off, so turning it back on asks fresh.
	private static volatile boolean consentAlreadyRequested;

	@NonNull
	private final Context context;

	@Nullable
	private AudioSpectrumListener audioSpectrumListener;
	@Nullable
	private ShaderTextureResources.SamplerTextureBinding spectrumTextureBinding;
	@Nullable
	private MediaProjection mediaProjection;
	// The in-app local-music-player's current MediaPlayer audio session,
	// or 0 when nothing's playing (a properly initialized MediaPlayer
	// never has session id 0 itself) - see setLocalPlaybackAudioSession().
	// Takes priority over system/mic audio in openListenerIfNeeded()
	// whenever it's set and useLocalPlaybackAudio() is on, since the
	// user specifically opened that player to drive the visuals from it.
	private int localPlaybackAudioSessionId = 0;
	private boolean needsAudioCaptureConsent;
	// The app's own sound (see setOwnSound()): while the shader has some
	// and use_own_sound_audio is on, audioSpectrum comes from it in place
	// of any capture - nothing recorded, so no permission or prompt.
	private boolean ownSoundExpected;
	@Nullable
	private AudioSpectrumListener ownListener;
	@Nullable
	private OwnSoundTap ownTap;
	private int ownEpoch;
	private long ownRead;
	@NonNull
	private short[] ownSamples = new short[0];
	// Set once releaseMediaProjection() has run (Activity being destroyed
	// for good) - see setMediaProjection() below for why this is needed
	// in addition to that method already being synchronized.
	private boolean destroyed;

	BuiltinAudioUniforms(@NonNull Context context) {
		this.context = context;
	}

	// Every method below that touches mediaProjection/audioSpectrumListener/
	// spectrumTextureBinding/needsAudioCaptureConsent is synchronized on
	// this instance. Those fields are normally only ever touched from the
	// GL thread (configure()/apply() run from ShaderRenderer's GL
	// callbacks; setMediaProjection() and friends are explicitly queued
	// there via GLSurfaceView.queueEvent() by ShaderViewManager) - except
	// releaseMediaProjection(), which ShaderViewManager.destroy() calls
	// directly from the main thread rather than queuing, specifically so
	// it still runs even if the GL thread is dead/stuck (e.g. crash
	// recovery). Without synchronization, that direct call could race a
	// concurrently-running configure()/setMediaProjection() on the GL
	// thread - e.g. destroy() reads/nulls a stale mediaProjection just
	// before the GL thread finishes registering a fresh one, which then
	// never gets released. This was the actual cause of an intermittent
	// MediaProjection/Activity leak (LeakCanary: "Global variable in
	// native code" retaining MediaProjection$MediaProjectionCallback ->
	// MediaProjection -> the destroyed Activity's Context) - not a
	// missing stop()/unregisterCallback() call, which already existed.
	synchronized void configure(
			@NonNull GlDevice device,
			@NonNull UniformPresence uniforms,
			@NonNull ShaderTextureResources textureResources) {
		spectrumTextureBinding = textureResources.getFirstBinding(
				ShaderRenderer.UNIFORM_AUDIO_SPECTRUM);
		if (spectrumTextureBinding == null) {
			unregisterListener();
			return;
		}
		// Leave an already-running capture alone rather than always
		// tearing it down and rebuilding it here - configure() runs on
		// every GL context recreation, including a plain app
		// background/foreground cycle, and re-creating an AudioRecord
		// against the same MediaProjection in quick succession can
		// silently deliver only silence instead of throwing. Only
		// reopen if there's genuinely no capture running right now.
		var listener = audioSpectrumListener;
		if (listener == null || !listener.isRunning()) {
			unregisterListener();
			openListenerIfNeeded();
		}
	}

	// Whether the shader makes its own sound (a Sound pass or a module).
	// Before configure() for a new shader, so it needn't open a capture
	// only to close it again; any time after, it reopens what's wanted.
	synchronized void setOwnSound(boolean expected) {
		if (expected == ownSoundExpected) {
			return;
		}
		ownSoundExpected = expected;
		if (spectrumTextureBinding != null) {
			unregisterListener();
			openListenerIfNeeded();
		}
	}

	// tap: what the app's own sound is playing, or null for none.
	synchronized void apply(@NonNull GlDevice device, @Nullable OwnSoundTap tap) {
		var binding = spectrumTextureBinding;
		var listener = audioSpectrumListener;
		if (binding != null && tap != null && wantsOwnSound()) {
			listener = feedOwnSound(tap);
		}
		if (binding == null || listener == null) {
			return;
		}
		if (binding.texture() instanceof GlTexture2D texture) {
			device.updateTexture2D(texture, listener.getSpectrumPixels());
		}
	}

	// Called once a MediaProjection consent request (triggered via
	// consumeNeedsAudioCaptureConsent()) has been answered by the user.
	// A null projection means they declined, or the API level/activity
	// didn't support asking; either way we keep running on the mic.
	// mediaProjection can legitimately arrive here well after consent was
	// granted - MainActivity.onMediaProjectionResult() waits on an extra
	// async hop (the audio-capture foreground service actually starting)
	// before calling this, and the Activity can be destroyed during that
	// window (e.g. the user backs out, or a config change, while that
	// service is still starting up). Without this check, a MediaProjection
	// handed to an already-destroyed instance would get registered
	// (openListenerIfNeeded() below) with nothing left to ever release it -
	// releaseMediaProjection() already ran and won't run again - leaking
	// the MediaProjection (and the destroyed Activity's Context with it)
	// until the process dies. Stopping it immediately instead is the
	// correct response: there's no renderer left for it to usefully drive.
	synchronized void setMediaProjection(@Nullable MediaProjection mediaProjection) {
		Log.i(TAG, "setMediaProjection: " + (mediaProjection != null ? "granted" : "null"));
		if (destroyed) {
			if (mediaProjection != null) {
				Log.i(TAG, "setMediaProjection: already destroyed, stopping it immediately");
				mediaProjection.stop();
			}
			return;
		}
		this.mediaProjection = mediaProjection;
		if (mediaProjection != null && ShaderEditorApp.preferences.useSystemAudio()) {
			unregisterListener();
			openListenerIfNeeded();
		}
	}

	// sessionId 0 means local playback has stopped/isn't active (see the
	// field's own doc) - reopens against system/mic audio in that case if
	// either of those is still wanted, same fallback behavior
	// setMediaProjection() above already has for the system-audio path.
	synchronized void setLocalPlaybackAudioSession(int sessionId) {
		Log.i(TAG, "setLocalPlaybackAudioSession: " + sessionId);
		localPlaybackAudioSessionId = sessionId;
		unregisterListener();
		openListenerIfNeeded();
	}

	synchronized boolean consumeNeedsAudioCaptureConsent() {
		boolean needs = needsAudioCaptureConsent;
		needsAudioCaptureConsent = false;
		return needs;
	}

	// Clears the "already asked" guard, so a user action that re-enables
	// system audio (after a prior denial, or after toggling the
	// preference off and back on) gets asked again instead of silently
	// staying on the microphone fallback forever. The flag is volatile
	// and process-wide, so this is safe to call from any thread.
	static void resetConsentGuard() {
		consentAlreadyRequested = false;
	}

	// Re-evaluates which audio source to use against the current
	// preferences, e.g. after use_system_audio changes, without
	// requiring a shader recompile to trigger it. Must run on the GL
	// thread, like configure().
	synchronized void refreshCaptureSource() {
		unregisterListener();
		openListenerIfNeeded();
	}

	synchronized void release() {
		spectrumTextureBinding = null;
		unregisterListener();
		releaseOwnListener();
	}

	// Called when the Activity is being destroyed for good, to fully end
	// the MediaProjection session rather than just unregistering our
	// listener from it. Registering a MediaProjection.Callback makes the
	// system hold a Binder-side reference to an internal wrapper that
	// keeps this MediaProjection (and the Activity Context it was
	// created with) alive until either unregisterCallback() or stop()
	// runs; unregisterListener() alone (via unregisterCallback()) covers
	// the ordinary pause/resume case, but if the Activity is destroyed
	// without a clean pause first (e.g. recovering from a crash),
	// nothing else ever calls stop() and the whole chain leaks.
	synchronized void releaseMediaProjection() {
		destroyed = true;
		unregisterListener();
		if (mediaProjection != null) {
			mediaProjection.stop();
			mediaProjection = null;
		}
	}

	// See AudioSpectrumListener.isReceivingAudio() - lets a manual
	// "reacquire" action skip the teardown/re-prompt dance when capture
	// is demonstrably still working.
	synchronized boolean isReceivingAudio() {
		var listener = audioSpectrumListener;
		return listener != null && listener.isReceivingAudio();
	}

	private boolean wantsOwnSound() {
		return ownSoundExpected && ShaderEditorApp.preferences.useOwnSoundAudio();
	}

	// What's been heard since last time, into ownListener.
	@NonNull
	private AudioSpectrumListener feedOwnSound(@NonNull OwnSoundTap tap) {
		var listener = ownListener;
		if (listener == null) {
			listener = new AudioSpectrumListener();
			listener.setFullIntervalMode(
					!ShaderEditorApp.preferences.audioSpectrumShaderToyCompatible());
			listener.registerPushed();
			ownListener = listener;
		}
		long heard = tap.getHeard();
		if (tap != ownTap || tap.getEpoch() != ownEpoch || heard < ownRead) {
			ownTap = tap;
			ownEpoch = tap.getEpoch();
			ownRead = Math.max(0, heard - AudioSpectrumListener.TEXTURE_WIDTH * 2);
		}
		int count = (int) Math.min(heard - ownRead, 16384);
		if (count > 0) {
			if (ownSamples.length < count) {
				ownSamples = new short[Math.max(count, 4096)];
			}
			tap.read(heard, count, ownSamples);
			listener.push(ownSamples, count);
		}
		ownRead = heard;
		return listener;
	}

	private void releaseOwnListener() {
		if (ownListener != null) {
			ownListener.unregister();
			ownListener = null;
		}
		ownTap = null;
	}

	private void openListenerIfNeeded() {
		if (spectrumTextureBinding == null || audioSpectrumListener != null) {
			return;
		}
		if (wantsOwnSound()) {
			// The shader's own sound drives it - see apply().
			consentAlreadyRequested = false;
			return;
		}
		releaseOwnListener();

		boolean wantsLocalPlayback = ShaderEditorApp.preferences.useLocalPlaybackAudio()
				&& localPlaybackAudioSessionId != 0;
		boolean wantsMicrophone = ShaderEditorApp.preferences.useMicrophoneAudio();
		boolean wantsSystemAudio = ShaderEditorApp.preferences.useSystemAudio() &&
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;

		if (!wantsLocalPlayback && !wantsMicrophone && !wantsSystemAudio) {
			// All sources off - stay silent, no permission needed.
			consentAlreadyRequested = false;
			return;
		}

		if (ContextCompat.checkSelfPermission(
				context, android.Manifest.permission.RECORD_AUDIO) !=
				PackageManager.PERMISSION_GRANTED) {
			requestPermission(android.Manifest.permission.RECORD_AUDIO);
			return;
		}

		boolean fullInterval = !ShaderEditorApp.preferences.audioSpectrumShaderToyCompatible();

		// Tried first and, if it works, exclusively - the user opened the
		// local-music player specifically to drive the visuals from it,
		// so it should win over system/mic audio whenever it's actually
		// playing something, not just be one more fallback in the chain.
		if (wantsLocalPlayback) {
			var listener = new AudioSpectrumListener();
			listener.setFullIntervalMode(fullInterval);
			if (listener.registerVisualizer(localPlaybackAudioSessionId)) {
				audioSpectrumListener = listener;
				return;
			}
			// Attaching to that session failed (e.g. it's gone stale) -
			// fall through to system/mic, if either is wanted too.
		}

		if (wantsSystemAudio) {
			if (mediaProjection != null) {
				var listener = new AudioSpectrumListener();
				listener.setFullIntervalMode(fullInterval);
				if (listener.registerPlayback(mediaProjection)) {
					audioSpectrumListener = listener;
					return;
				}
				// Capture config was rejected (e.g. the projection was
				// revoked); fall through and use the microphone instead,
				// if that's wanted too.
			} else if (!consentAlreadyRequested) {
				Log.i(TAG, "openListenerIfNeeded: requesting MediaProjection consent");
				consentAlreadyRequested = true;
				needsAudioCaptureConsent = true;
			} else {
				Log.i(TAG, "openListenerIfNeeded: already asked for consent, not asking again"
						+ " (no projection yet) - falling back to mic if enabled");
			}
		} else {
			// Not wanted (or off this preference run) - let a future
			// opt-in ask fresh instead of staying silenced forever.
			consentAlreadyRequested = false;
		}

		if (wantsMicrophone) {
			var listener = new AudioSpectrumListener();
			listener.setFullIntervalMode(fullInterval);
			if (listener.registerMicrophone(context)) {
				audioSpectrumListener = listener;
			}
		}
	}

	private void unregisterListener() {
		if (audioSpectrumListener != null) {
			audioSpectrumListener.unregister();
			audioSpectrumListener = null;
		}
	}

	private void requestPermission(@NonNull String permission) {
		if (!(context instanceof Activity activity)) {
			return;
		}
		ActivityCompat.requestPermissions(
				activity,
				new String[]{permission},
				1);
	}
}
