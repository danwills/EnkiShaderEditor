package enk.danwills.android.enkishadereditor.opengl;

import android.opengl.GLES20;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

// Keys for shaders, fed from the UI thread (MainActivity.
// dispatchKeyEvent()) and applied once per frame on the GL thread:
// - `keyboard`: a 256x3 texture like ShaderToy's, indexed by JavaScript
//   key code (65 = A, 37-40 = arrows, 13 = Enter...): row 0 (v = 0) is
//   1 while the key is held, row 1 for the one frame it went down in
//   (and each time it auto-repeats while held), and row 2 flips each
//   time it's pressed. In all four channels.
// - `typedChar`: the character typed this frame, or 0 - one per frame
//   from a queue, so fast typing at a low frame rate never drops any.
//   Text as typed (Shift, layouts), plus 8 for Backspace, 9 for Tab,
//   10 for Enter and 127 for Delete.
// A press shorter than a frame still shows as pressed for one frame.
public final class KeyboardInput {
	public static final int KEYS = 256;
	private static final int ROWS = 3;
	private static final int HELD = 0;
	private static final int PRESSED = 1;
	private static final int TOGGLED = 2;
	private static final TextureParameters NEAREST = new TextureParameters(
			GLES20.GL_NEAREST, GLES20.GL_NEAREST,
			GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_CLAMP_TO_EDGE);

	// Positive key code: down; negative minus one: up; KEYS + key code:
	// a held key repeating. -KEYS - 1: all up.
	private final ConcurrentLinkedQueue<Integer> keyEvents = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Integer> typed = new ConcurrentLinkedQueue<>();
	// GL thread only from here.
	private final boolean[][] state = new boolean[ROWS][KEYS];
	// Key-ups held back a frame - see apply().
	private final List<Integer> deferredUps = new ArrayList<>();
	@Nullable
	private ByteBuffer pixels;
	@Nullable
	private GlTexture2D texture;
	private boolean dirty = true;
	private int typedChar;

	public void keyDown(int keyCode) {
		if (keyCode > 0 && keyCode < KEYS) {
			keyEvents.add(keyCode);
		}
	}

	// A held key's auto-repeat: pressed again for a frame, as if let go
	// and pressed - so holding an arrow keeps moving a cursor - but
	// without flipping its toggle.
	public void keyRepeat(int keyCode) {
		if (keyCode > 0 && keyCode < KEYS) {
			keyEvents.add(KEYS + keyCode);
		}
	}

	public void keyUp(int keyCode) {
		if (keyCode > 0 && keyCode < KEYS) {
			keyEvents.add(-keyCode - 1);
		}
	}

	// Down and up again: pressed for a frame, never held - for the
	// phone's keyboard, which sends text rather than key presses.
	public void tap(int keyCode) {
		keyDown(keyCode);
		keyUp(keyCode);
	}

	public void type(int character) {
		if (character > 0) {
			typed.add(character);
		}
	}

	// Lets go of every held key - when the keys stop coming here
	// (the editor opens, the app leaves the screen), so none stays held.
	public void releaseAll() {
		keyEvents.add(-KEYS - 1);
	}

	// Once per frame, GL thread only.
	void apply(@NonNull GlDevice device, @NonNull ProgramBindings bindings) {
		boolean[] pressed = state[PRESSED];
		for (int k = 0; k < KEYS; ++k) {
			if (pressed[k]) {
				pressed[k] = false;
				dirty = true;
			}
		}
		// A key let go in the frame it went down in is held for that
		// frame and let go in the next - so a quick tap, or a modifier
		// and a key pressed together, is seen held.
		var deferred = new ArrayList<Integer>();
		for (Integer up : deferredUps) {
			state[HELD][-up - 1] = false;
			dirty = true;
		}
		deferredUps.clear();
		boolean[] wentDown = new boolean[KEYS];
		for (Integer event; (event = keyEvents.poll()) != null; ) {
			if (event < 0 && event != -KEYS - 1 && wentDown[-event - 1]) {
				deferred.add(event);
				continue;
			}
			if (event >= 0 && event < KEYS) {
				wentDown[event] = true;
			}
			if (event == -KEYS - 1) {
				Arrays.fill(state[HELD], false);
			} else if (event >= KEYS) {
				pressed[event - KEYS] = true;
				state[HELD][event - KEYS] = true;
			} else if (event >= 0) {
				if (!state[HELD][event]) {
					pressed[event] = true;
					state[TOGGLED][event] = !state[TOGGLED][event];
				}
				state[HELD][event] = true;
			} else {
				state[HELD][-event - 1] = false;
			}
			dirty = true;
		}
		deferredUps.addAll(deferred);
		Integer next = typed.poll();
		typedChar = next != null ? next : 0;

		if (texture == null) {
			texture = device.createTexture2D();
			device.allocateTexture2D(texture, KEYS, ROWS);
			device.applyTextureParameters(texture, NEAREST);
			pixels = ByteBuffer.allocateDirect(KEYS * ROWS * 4).order(ByteOrder.nativeOrder());
			dirty = true;
		}
		if (dirty && pixels != null) {
			for (int row = 0; row < ROWS; ++row) {
				for (int k = 0; k < KEYS; ++k) {
					byte value = state[row][k] ? (byte) 255 : 0;
					int at = (row * KEYS + k) * 4;
					pixels.put(at, value);
					pixels.put(at + 1, value);
					pixels.put(at + 2, value);
					pixels.put(at + 3, value);
				}
			}
			device.updateTexture2D(texture, pixels);
			dirty = false;
		}
		bindings.setTexture(ShaderRenderer.UNIFORM_KEYBOARD, texture);
		bindings.setInt(ShaderRenderer.UNIFORM_TYPED_CHAR, typedChar);
	}

	@Nullable
	GlTexture2D getTexture() {
		return texture;
	}

	// The context is gone.
	void discard() {
		texture = null;
		pixels = null;
	}

	void release(@NonNull GlDevice device) {
		device.deleteTexture(texture);
		discard();
	}

	// Android key code to JavaScript's (what ShaderToy indexes its
	// keyboard texture by), or 0 for keys without one.
	public static int jsKeyCode(int keyCode) {
		if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
			return 65 + keyCode - KeyEvent.KEYCODE_A;
		}
		if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
			return 48 + keyCode - KeyEvent.KEYCODE_0;
		}
		if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
			return 96 + keyCode - KeyEvent.KEYCODE_NUMPAD_0;
		}
		if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
			return 112 + keyCode - KeyEvent.KEYCODE_F1;
		}
		return switch (keyCode) {
			case KeyEvent.KEYCODE_DEL -> 8;
			case KeyEvent.KEYCODE_TAB -> 9;
			case KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 13;
			case KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> 16;
			case KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> 17;
			case KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> 18;
			case KeyEvent.KEYCODE_ESCAPE -> 27;
			case KeyEvent.KEYCODE_SPACE -> 32;
			case KeyEvent.KEYCODE_PAGE_UP -> 33;
			case KeyEvent.KEYCODE_PAGE_DOWN -> 34;
			case KeyEvent.KEYCODE_MOVE_END -> 35;
			case KeyEvent.KEYCODE_MOVE_HOME -> 36;
			case KeyEvent.KEYCODE_DPAD_LEFT -> 37;
			case KeyEvent.KEYCODE_DPAD_UP -> 38;
			case KeyEvent.KEYCODE_DPAD_RIGHT -> 39;
			case KeyEvent.KEYCODE_DPAD_DOWN -> 40;
			case KeyEvent.KEYCODE_INSERT -> 45;
			case KeyEvent.KEYCODE_FORWARD_DEL -> 46;
			case KeyEvent.KEYCODE_SEMICOLON -> 186;
			case KeyEvent.KEYCODE_EQUALS -> 187;
			case KeyEvent.KEYCODE_COMMA -> 188;
			case KeyEvent.KEYCODE_MINUS -> 189;
			case KeyEvent.KEYCODE_PERIOD -> 190;
			case KeyEvent.KEYCODE_SLASH -> 191;
			case KeyEvent.KEYCODE_GRAVE -> 192;
			case KeyEvent.KEYCODE_LEFT_BRACKET -> 219;
			case KeyEvent.KEYCODE_BACKSLASH -> 220;
			case KeyEvent.KEYCODE_RIGHT_BRACKET -> 221;
			case KeyEvent.KEYCODE_APOSTROPHE -> 222;
			default -> 0;
		};
	}

	// The JavaScript key code of the key that types `c` on a US layout,
	// or 0 - for text from the phone's keyboard.
	public static int jsKeyCodeOf(char c) {
		if (c >= 'a' && c <= 'z') {
			return 65 + c - 'a';
		}
		if (c >= 'A' && c <= 'Z') {
			return 65 + c - 'A';
		}
		if (c >= '0' && c <= '9') {
			return 48 + c - '0';
		}
		return switch (c) {
			case ' ' -> 32;
			case '\n' -> 13;
			case '\t' -> 9;
			case ';' -> 186;
			case '=' -> 187;
			case ',' -> 188;
			case '-' -> 189;
			case '.' -> 190;
			case '/' -> 191;
			case '`' -> 192;
			case '[' -> 219;
			case '\\' -> 220;
			case ']' -> 221;
			case '\'' -> 222;
			default -> 0;
		};
	}

	// What typing `event` gives `typedChar`, or 0.
	public static int typedCharOf(@NonNull KeyEvent event) {
		return switch (event.getKeyCode()) {
			case KeyEvent.KEYCODE_DEL -> 8;
			case KeyEvent.KEYCODE_TAB -> 9;
			case KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 10;
			case KeyEvent.KEYCODE_FORWARD_DEL -> 127;
			default -> {
				int c = event.getUnicodeChar(event.getMetaState());
				yield c > 0 && (c & KeyCharacterMap.COMBINING_ACCENT) == 0 ? c : 0;
			}
		};
	}
}
