package enk.danwills.android.enkishadereditor.widget;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import enk.danwills.android.enkishadereditor.opengl.KeyboardInput;

// Type into Shader (see MainActivity.startTypingIntoShader()): an
// invisible view the phone's keyboard types into, which turns what's
// typed into the shader's `typedChar`, and taps on its `keyboard`
// texture. Keys an input method sends as key events (some send Enter,
// Backspace or the arrows that way) go through MainActivity's
// dispatchKeyEvent() like a hardware keyboard's.
public class ShaderKeyInputView extends View {
	@Nullable
	private KeyboardInput keyboard;

	public ShaderKeyInputView(@NonNull Context context) {
		this(context, null);
	}

	public ShaderKeyInputView(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		setTyping(false);
	}

	// Focusable only while typing: otherwise it'd pick up focus whenever
	// the editor hides, and keyboards (Samsung's, with a hardware
	// keyboard) would show their toolbar for it as if it were a field.
	public void setTyping(boolean typing) {
		setFocusable(typing);
		setFocusableInTouchMode(typing);
	}

	public void setKeyboard(@Nullable KeyboardInput keyboard) {
		this.keyboard = keyboard;
	}

	@Override
	public boolean onCheckIsTextEditor() {
		return true;
	}

	@Override
	public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
		// A "visible password" field, so keyboards type plain
		// characters straight away instead of composing words and
		// suggesting corrections - which a shader couldn't take back.
		outAttrs.inputType = InputType.TYPE_CLASS_TEXT |
				InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD |
				InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
		outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI |
				EditorInfo.IME_FLAG_NO_FULLSCREEN |
				EditorInfo.IME_FLAG_NO_ENTER_ACTION |
				EditorInfo.IME_ACTION_NONE;
		return new BaseInputConnection(this, false) {
			// Shouldn't happen for this kind of field; if a keyboard
			// composes anyway, each word is typed once it's finished.
			@Nullable
			private CharSequence composing;

			@Override
			public boolean commitText(CharSequence text, int newCursorPosition) {
				composing = null;
				typeText(text);
				return true;
			}

			@Override
			public boolean setComposingText(CharSequence text, int newCursorPosition) {
				composing = text;
				return true;
			}

			@Override
			public boolean finishComposingText() {
				typeText(composing);
				composing = null;
				return true;
			}

			@Override
			public boolean deleteSurroundingText(int beforeLength, int afterLength) {
				if (keyboard != null) {
					for (int i = 0; i < beforeLength; ++i) {
						keyboard.type(8);
						keyboard.tap(8);
					}
					for (int i = 0; i < afterLength; ++i) {
						keyboard.type(127);
						keyboard.tap(46);
					}
				}
				return true;
			}

			@Override
			public boolean performEditorAction(int actionCode) {
				typeText("\n");
				return true;
			}
		};
	}

	private void typeText(@Nullable CharSequence text) {
		if (keyboard == null || text == null) {
			return;
		}
		for (int i = 0; i < text.length(); ++i) {
			char c = text.charAt(i);
			keyboard.type(c == '\n' ? 10 : c);
			keyboard.tap(KeyboardInput.jsKeyCodeOf(c));
		}
	}
}
