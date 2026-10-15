package enk.danwills.android.enkishadereditor.opengl;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

// Computes the two pieces that key a cached compiled-program binary:
// - a hash of the raw GLSL source (+ GL version), standing in for the
//   exact text that would be fed to glCompileShader - ShaderSourcePreparer
//   is a pure function of (source, version, maxTextures), and maxTextures
//   is a fixed constant, so this is equivalent to hashing its output
//   without needing a GL context to compute it;
// - a driver signature (vendor/renderer/version strings), since a
//   compiled binary is only ever valid for the exact driver that
//   produced it - this one DOES need a current GL context.
final class ProgramBinaryKey {
	private ProgramBinaryKey() {
	}

	@NonNull
	static String sourceHash(@NonNull String source, int glVersion) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(source.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) glVersion);
			return toHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is guaranteed present on every Android version this
			// app targets - this can't actually happen.
			throw new AssertionError(e);
		}
	}

	// Must be called with a current GL context on the calling thread.
	@NonNull
	static String driverSignature() {
		return nullToEmpty(GLES20.glGetString(GLES20.GL_VENDOR)) + "|" +
				nullToEmpty(GLES20.glGetString(GLES20.GL_RENDERER)) + "|" +
				nullToEmpty(GLES20.glGetString(GLES20.GL_VERSION));
	}

	@NonNull
	private static String nullToEmpty(@Nullable String s) {
		return s == null ? "" : s;
	}

	@NonNull
	private static String toHex(@NonNull byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}
}
