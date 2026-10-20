package enk.danwills.android.enkishadereditor.opengl;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// What a ShaderToy-style `iChannel0`..`iChannel3` slot reads, stored the
// app's usual way - as trailing parameters on the sampler's declaration:
//
//   uniform sampler2D iChannel0;///buf:A;min:l;mag:l;s:c;t:c;
//
// `buf` names the offscreen buffer (A-D) the slot samples, or `tex` one
// of the app's built-in textures (`tex:font;`, see FontTexture, or
// `tex:keyboard;`, see KeyboardInput); with
// neither, the slot is unassigned and reads black. Filtering/wrapping default to
// ShaderToy's (linear, clamp) rather than TextureParameters' own.
public class ChannelParameters extends TextureParameters {
	public static final int MAX_CHANNELS = 4;
	public static final int NO_BUFFER = -1;
	public static final String CHANNEL_PREFIX = "iChannel";
	private static final Pattern PATTERN_CHANNEL_NAME =
			Pattern.compile("^" + CHANNEL_PREFIX + "([0-" + (MAX_CHANNELS - 1) + "])$");
	private static final String BUFFER = "buf";
	private static final String TEXTURE = "tex";
	public static final String TEXTURE_FONT = "font";
	public static final String TEXTURE_KEYBOARD = "keyboard";

	private int buffer = NO_BUFFER;
	// A built-in texture's name (TEXTURE_FONT, TEXTURE_KEYBOARD), or null.
	@Nullable
	private String texture;

	public ChannelParameters() {
		super(GLES20.GL_LINEAR,
				GLES20.GL_LINEAR,
				GLES20.GL_CLAMP_TO_EDGE,
				GLES20.GL_CLAMP_TO_EDGE);
	}

	public ChannelParameters(String params) {
		this();
		parse(params);
	}

	// 0..MAX_CHANNELS - 1 if `name` is a channel uniform's name, else -1.
	public static int getChannelIndex(@NonNull String name) {
		Matcher matcher = PATTERN_CHANNEL_NAME.matcher(name);
		return matcher.matches() ? matcher.group(1).charAt(0) - '0' : -1;
	}

	@NonNull
	public static String getChannelName(int channel) {
		return CHANNEL_PREFIX + channel;
	}

	// 0 = Buffer A ... 3 = Buffer D, or NO_BUFFER.
	public int getBuffer() {
		return buffer;
	}

	public void setBuffer(int buffer) {
		this.buffer = buffer;
		texture = null;
	}

	@Nullable
	public String getTexture() {
		return texture;
	}

	public void setTexture(@Nullable String texture) {
		this.texture = texture;
		buffer = NO_BUFFER;
	}

	@NonNull
	@Override
	public String toString() {
		String params = super.toString();
		if (buffer != NO_BUFFER) {
			if (params.isEmpty()) {
				params = HEADER;
			}
			params += BUFFER + ASSIGN + (char) ('A' + buffer) + SEPARATOR;
		} else if (texture != null) {
			if (params.isEmpty()) {
				params = HEADER;
			}
			params += TEXTURE + ASSIGN + texture + SEPARATOR;
		}
		return params;
	}

	@Override
	protected void parseParameter(String name, String value) {
		if (BUFFER.equals(name)) {
			buffer = value.length() == 1 &&
					value.charAt(0) >= 'A' &&
					value.charAt(0) < 'A' + MAX_CHANNELS
					? value.charAt(0) - 'A'
					: NO_BUFFER;
		} else if (TEXTURE.equals(name)) {
			texture = TEXTURE_FONT.equals(value) || TEXTURE_KEYBOARD.equals(value) ? value : null;
		} else {
			super.parseParameter(name, value);
		}
	}
}
