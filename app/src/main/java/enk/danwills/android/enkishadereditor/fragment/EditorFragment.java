package enk.danwills.android.enkishadereditor.fragment;

import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.Collections;
import java.util.List;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.app.ShaderEditorApp;
import enk.danwills.android.enkishadereditor.opengl.ShaderError;
import enk.danwills.android.enkishadereditor.preference.Preferences;
import enk.danwills.android.enkishadereditor.view.SoftKeyboard;
import enk.danwills.android.enkishadereditor.view.UndoRedo;
import enk.danwills.android.enkishadereditor.widget.ErrorListModal;
import enk.danwills.android.enkishadereditor.widget.ShaderEditor;

public class EditorFragment extends Fragment {
	public static final String TAG = "EditorFragment";

	private View editorContainer;
	private ShaderEditor shaderEditor;
	private UndoRedo undoRedo;
	private int pendingTopInsetPx = 0;

	@Nullable
	private ShaderEditor.OnEditPausedListener editPausedListener;
	@Nullable
	private ShaderEditor.OnTextModifiedListener textModifiedListener;
	@Nullable
	private ShaderEditor.CodeCompletionListener codeCompletionListener;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
		View view = inflater.inflate(R.layout.fragment_editor, container, false);

		editorContainer = view.findViewById(R.id.editor_container);
		shaderEditor = view.findViewById(R.id.editor);
		shaderEditor.setOnEditPausedListener((text) -> {
			if (editPausedListener != null) {
				editPausedListener.onEditPaused(text);
			}
		});
		shaderEditor.setOnTextModifiedListener(() -> {
			if (textModifiedListener != null) {
				textModifiedListener.onTextModified();
			}
		});
		shaderEditor.setOnCompletionsListener((completions, position) -> {
			if (codeCompletionListener != null) {
				codeCompletionListener.onCodeCompletions(completions, position);
			}
		});
		setShowLineNumbers(ShaderEditorApp.preferences.showLineNumbers());
		undoRedo = new UndoRedo(shaderEditor, ShaderEditorApp.editHistory);
		applyTopInset();

		return view;
	}

	// Top margin (in px) reserved above the editor so it starts below
	// the persistent shader-name label instead of underneath it -
	// driven by UIManager, which measures that label's actual (name
	// length/wrapping-dependent) height. Safe to call before the
	// fragment's view exists; applied once it does.
	public void setTopInset(int px) {
		pendingTopInsetPx = px;
		applyTopInset();
	}

	private void applyTopInset() {
		if (editorContainer == null) {
			return;
		}
		var lp = (ViewGroup.MarginLayoutParams) editorContainer.getLayoutParams();
		if (lp.topMargin != pendingTopInsetPx) {
			lp.topMargin = pendingTopInsetPx;
			editorContainer.setLayoutParams(lp);
		}
	}

	public void setOnTextModifiedListener(@Nullable ShaderEditor.OnTextModifiedListener listener) {
		textModifiedListener = listener;
	}

	public void setOnEditPausedListener(@Nullable ShaderEditor.OnEditPausedListener listener) {
		editPausedListener = listener;
	}

	public void setCodeCompletionListener(@Nullable ShaderEditor.CodeCompletionListener listener) {
		codeCompletionListener = listener;
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		undoRedo.detachListener();
	}

	@Override
	public void onResume() {
		super.onResume();
		updateToPreferences();
		// Only start listening after EditText restored its content
		// to make sure the initial change is not recorded.
		undoRedo.listenForChanges();
	}

	public void undo() {
		undoRedo.undo();
	}

	public boolean canUndo() {
		return undoRedo.canUndo();
	}

	public void redo() {
		undoRedo.redo();
	}

	public boolean canRedo() {
		return undoRedo.canRedo();
	}

	public boolean hasErrors() {
		return shaderEditor.hasErrors();
	}

	public void clearError() {
		shaderEditor.setErrors(Collections.emptyList());
	}

	public void updateHighlighting() {
		shaderEditor.updateHighlighting();
	}

	public void highlightErrors() {
		shaderEditor.updateErrorHighlighting();
	}

	public void setErrors(@NonNull List<ShaderError> errors) {
		shaderEditor.setErrors(errors);
		highlightErrors();
	}

	public void showErrors() {
		List<ShaderError> errors = shaderEditor.getErrors();
		new ErrorListModal(errors, this::navigateToLine).show(getParentFragmentManager(),
				ErrorListModal.TAG);
	}

	public void navigateToLine(int lineNumber) {
		shaderEditor.navigateToLine(lineNumber);
	}

	@NonNull
	public List<Integer> findAllMatches(@NonNull String query) {
		return shaderEditor.findAllMatches(query);
	}

	public void selectMatch(int start, int length) {
		shaderEditor.selectMatch(start, length);
	}

	public void highlightMatch(int start, int length) {
		shaderEditor.highlightMatch(start, length);
	}

	public void clearMatchHighlight() {
		shaderEditor.clearMatchHighlight();
	}

	@Nullable
	public Rect getMatchBoundsOnScreen(int start, int length) {
		return shaderEditor.getMatchBoundsOnScreen(start, length);
	}

	// Scrolls (via the ScrollView wrapping the editor) so the match at
	// [start, start + length) ends up with its top edge at
	// `targetTopOnScreen` (absolute screen Y) instead of wherever a
	// generic "bring into view" would leave it - e.g. just below a
	// dialog sitting over the top of the screen, rather than fully or
	// partially hidden underneath it.
	public void scrollMatchToScreenY(int start, int length, int targetTopOnScreen) {
		Rect bounds = getMatchBoundsOnScreen(start, length);
		if (bounds == null) {
			return;
		}
		int delta = bounds.top - targetTopOnScreen;
		editorContainer.scrollBy(0, delta);
	}


	public String getText() {
		return shaderEditor.getCleanText();
	}

	public void setText(String text) {
		clearError();
		undoRedo.clearHistory();
		undoRedo.stopListeningForChanges();
		shaderEditor.setTextHighlighted(text);
		undoRedo.listenForChanges();
	}

	public void insert(@NonNull CharSequence text) {
		shaderEditor.insert(text);
	}

	public void addUniform(String name) {
		shaderEditor.addUniform(name);
	}

	public boolean isCodeVisible() {
		return editorContainer.getVisibility() == View.VISIBLE;
	}

	public boolean toggleCode() {
		boolean visible = isCodeVisible();
		editorContainer.setVisibility(visible ? View.GONE : View.VISIBLE);
		if (visible) {
			SoftKeyboard.hide(getActivity(), shaderEditor);
		}
		return visible;
	}

	// Re-focuses the editor and reopens the keyboard, e.g. after
	// exiting fullscreen if it was showing beforehand.
	public void showKeyboard() {
		SoftKeyboard.show(getActivity(), shaderEditor);
	}

	public int getSelectionStart() {
		return shaderEditor.getSelectionStart();
	}

	public int getSelectionEnd() {
		return shaderEditor.getSelectionEnd();
	}

	// Clamped to the current text length since this may be called after
	// restoring selection captured against a since-recompiled/edited shader.
	public void setSelection(int start, int end) {
		int length = shaderEditor.length();
		start = Math.max(0, Math.min(start, length));
		end = Math.max(0, Math.min(end, length));
		shaderEditor.setSelection(start, end);
	}

	private void updateToPreferences() {
		Preferences preferences = ShaderEditorApp.preferences;
		shaderEditor.setUpdateDelay(preferences.getUpdateDelay());
		shaderEditor.setTextSize(TypedValue.COMPLEX_UNIT_SP, preferences.getTextSize());
		Typeface font = preferences.getFont();
		shaderEditor.setTypeface(font);
		String features = shaderEditor.getFontFeatureSettings();
		boolean isMono = font == Typeface.MONOSPACE;
		// Don't touch font features for the default MONOSPACE font as
		// this can impact performance.
		if (!isMono || features != null) {
			shaderEditor.setFontFeatureSettings(isMono ? null : preferences.useLigatures() ?
					"normal" : "calt off");
		}
	}

	public void setShowLineNumbers(boolean showLineNumbers) {
		shaderEditor.setShowLineNumbers(showLineNumbers);
	}
}