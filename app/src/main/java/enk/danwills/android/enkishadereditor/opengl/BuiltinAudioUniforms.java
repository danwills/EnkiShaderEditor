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
	private boolean needsAudioCaptureConsent;

	BuiltinAudioUniforms(@NonNull Context context) {
		this.context = context;
	}

	void configure(
			@NonNull GlDevice device,
			@NonNull GlProgram program,
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

	void apply(@NonNull GlDevice device) {
		var binding = spectrumTextureBinding;
		var listener = audioSpectrumListener;
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
	void setMediaProjection(@Nullable MediaProjection mediaProjection) {
		Log.i(TAG, "setMediaProjection: " + (mediaProjection != null ? "granted" : "null"));
		this.mediaProjection = mediaProjection;
		if (mediaProjection != null && ShaderEditorApp.preferences.useSystemAudio()) {
			unregisterListener();
			openListenerIfNeeded();
		}
	}

	boolean consumeNeedsAudioCaptureConsent() {
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
	void refreshCaptureSource() {
		unregisterListener();
		openListenerIfNeeded();
	}

	void release() {
		spectrumTextureBinding = null;
		unregisterListener();
	}

	private void openListenerIfNeeded() {
		if (spectrumTextureBinding == null || audioSpectrumListener != null) {
			return;
		}

		boolean wantsMicrophone = ShaderEditorApp.preferences.useMicrophoneAudio();
		boolean wantsSystemAudio = ShaderEditorApp.preferences.useSystemAudio() &&
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;

		if (!wantsMicrophone && !wantsSystemAudio) {
			// Both sources off - stay silent, no permission needed.
			consentAlreadyRequested = false;
			return;
		}

		if (ContextCompat.checkSelfPermission(
				context, android.Manifest.permission.RECORD_AUDIO) !=
				PackageManager.PERMISSION_GRANTED) {
			requestPermission(android.Manifest.permission.RECORD_AUDIO);
			return;
		}

		if (wantsSystemAudio) {
			if (mediaProjection != null) {
				var listener = new AudioSpectrumListener();
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
