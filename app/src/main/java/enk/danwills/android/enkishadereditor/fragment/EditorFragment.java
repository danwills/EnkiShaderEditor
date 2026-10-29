package enk.danwills.android.enkishadereditor.fragment;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.app.ShaderEditorApp;
import enk.danwills.android.enkishadereditor.opengl.ChannelDeclarations;
import enk.danwills.android.enkishadereditor.opengl.ChannelParameters;
import enk.danwills.android.enkishadereditor.opengl.ShaderError;
import enk.danwills.android.enkishadereditor.preference.Preferences;
import enk.danwills.android.enkishadereditor.view.SoftKeyboard;
import enk.danwills.android.enkishadereditor.view.UndoRedo;
import enk.danwills.android.enkishadereditor.widget.ErrorListModal;
import enk.danwills.android.enkishadereditor.widget.ShaderEditor;

public class EditorFragment extends Fragment {
	public static final String TAG = "EditorFragment";

	// A multipass shader's passes - see setPassTabs(). The Image pass is
	// ShaderError.IMAGE_PASS, buffers 0 (A) .. MAX_BUFFERS - 1.
	public interface OnPassSelectedListener {
		void onPassSelected(int pass);
	}

	// Long-pressing a pass tab picks which pass the preview shows - see
	// getDisplayedPass().
	public interface OnPassDisplayedListener {
		void onPassDisplayed(int pass);
	}

	public interface OnChannelClickListener {
		void onChannelClick(int channel, @NonNull View anchor);
	}

	public static final int MAX_BUFFERS = 4;
	// A shader's texts (see ShaderTextDao) are passes too, for their tabs:
	// TEXT_PASS_BASE + their index, in the order setPassTabs() gives.
	public static final int TEXT_PASS_BASE = 100;

	public static boolean isTextPass(int pass) {
		return pass >= TEXT_PASS_BASE;
	}

	// Static, like ShaderEditorApp.editHistory, so they outlive this
	// fragment being recreated: which pass the editor shows, and each
	// other pass's own undo history and selection/scroll, kept while
	// another one is shown (see switchToPass()). Reset by setText(),
	// i.e. whenever a different shader is loaded.
	private static int activePass = ShaderError.IMAGE_PASS;
	// Which pass the preview shows: the Image pass, unless a buffer tab
	// was long-pressed to look at that buffer's pixels instead. Separate
	// from activePass, so editing a buffer doesn't change the preview.
	private static int displayedPass = ShaderError.IMAGE_PASS;
	private static final Map<Integer, UndoRedo.EditHistory> passHistories = new HashMap<>();
	private static final Map<Integer, int[]> passViewStates = new HashMap<>();

	private View editorContainer;
	private ScrollView editorScroll;
	private LinearLayout passTabs;
	private LinearLayout channelRow;
	private ShaderEditor shaderEditor;
	private UndoRedo undoRedo;
	private int pendingTopInsetPx = 0;
	@NonNull
	private boolean[] buffersPresent = new boolean[MAX_BUFFERS];
	@NonNull
	private boolean[] soundPasses = new boolean[0];
	@NonNull
	private List<String> textNames = List.of();
	// Whether the shader has a Common section (ShaderError.COMMON_PASS),
	// with its tab after the Image pass's.
	private boolean hasCommon;
	// Every pass's errors - the editor itself only marks the shown pass's.
	@NonNull
	private List<ShaderError> allErrors = Collections.emptyList();
	@Nullable
	private OnPassSelectedListener passSelectedListener;
	private OnPassDisplayedListener passDisplayedListener;
	@Nullable
	private OnChannelClickListener channelClickListener;

	@Nullable
	private ShaderEditor.OnEditPausedListener editPausedListener;
	@Nullable
	private ShaderEditor.OnTextModifiedListener textModifiedListener;
	@Nullable
	private ShaderEditor.CodeCompletionListener codeCompletionListener;
	@Nullable
	private Runnable onScrollChangedListener;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
		View view = inflater.inflate(R.layout.fragment_editor, container, false);

		editorContainer = view.findViewById(R.id.editor_container);
		editorScroll = view.findViewById(R.id.editor_scroll);
		passTabs = view.findViewById(R.id.pass_tabs);
		channelRow = view.findViewById(R.id.channel_row);
		shaderEditor = view.findViewById(R.id.editor);
		shaderEditor.setOnEditPausedListener((text) -> {
			refreshChannelRow();
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
		undoRedo = new UndoRedo(shaderEditor, activePass == ShaderError.IMAGE_PASS
				? ShaderEditorApp.editHistory
				: historyFor(activePass));
		applyTopInset();
		buildPassTabs();
		// Fires for both the vertical (editorContainer) and horizontal
		// (wrapping it) scrolling - lets a caller keep something like the
		// find-match ring overlay tracking the actual text position
		// instead of drifting off it as the user scrolls.
		editorContainer.getViewTreeObserver().addOnScrollChangedListener(() -> {
			if (onScrollChangedListener != null) {
				onScrollChangedListener.run();
			}
		});

		return view;
	}

	public void setOnScrollChangedListener(@Nullable Runnable listener) {
		onScrollChangedListener = listener;
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

	// Used by MainActivity's global hotkey dispatch to tell "the cursor
	// is in the shader source area" (several shortcuts - Up/Down,
	// Left/Right, Tab - need to behave normally for cursor movement and
	// text entry there instead of being hijacked as a shortcut) from
	// everywhere else in the UI.
	public boolean isSourceEditorFocused() {
		return shaderEditor.hasFocus();
	}

	public boolean hasErrors() {
		return !allErrors.isEmpty() || shaderEditor.hasErrors();
	}

	public void clearError() {
		allErrors = Collections.emptyList();
		shaderEditor.setErrors(Collections.emptyList());
		buildPassTabs();
	}

	public void updateHighlighting() {
		shaderEditor.updateHighlighting();
	}

	public void highlightErrors() {
		shaderEditor.updateErrorHighlighting();
	}

	public void setErrors(@NonNull List<ShaderError> errors) {
		allErrors = List.copyOf(errors);
		shaderEditor.setErrors(errorsFor(activePass));
		highlightErrors();
		buildPassTabs();
	}

	public void showErrors() {
		new ErrorListModal(allErrors, error -> {
			if (error.getPass() != activePass && passSelectedListener != null) {
				passSelectedListener.onPassSelected(error.getPass());
			}
			if (error.hasLine()) {
				navigateToLine(error.getLine());
			}
		}).show(getParentFragmentManager(), ErrorListModal.TAG);
	}

	public void setOnPassSelectedListener(@Nullable OnPassSelectedListener listener) {
		passSelectedListener = listener;
	}

	public void setOnPassDisplayedListener(@Nullable OnPassDisplayedListener listener) {
		passDisplayedListener = listener;
	}

	// The Image pass, or a buffer that's still there - so loading another
	// shader, or removing the shown buffer, falls back to the Image pass.
	public int getDisplayedPass() {
		if (displayedPass != ShaderError.IMAGE_PASS &&
				(displayedPass >= buffersPresent.length || !buffersPresent[displayedPass])) {
			displayedPass = ShaderError.IMAGE_PASS;
		}
		return displayedPass;
	}

	public void setOnChannelClickListener(@Nullable OnChannelClickListener listener) {
		channelClickListener = listener;
	}

	public int getActivePass() {
		return activePass;
	}

	// After the activity was recreated: the editor already shows `pass`'s
	// text again (restored by the EditText itself), so just make the tabs,
	// undo history and error marks agree - see ShaderManager.restoreState().
	public void restoreActivePass(int pass) {
		if (pass == activePass) {
			buildPassTabs();
			return;
		}
		activePass = pass;
		if (undoRedo != null) {
			undoRedo.setEditHistory(historyFor(pass));
		}
		buildPassTabs();
	}

	// Which buffers the shader has (indexed like ShaderError's passes);
	// with none, the tabs and channel row are hidden entirely.
	// soundPasses: which of them are Sound passes, labelled as such.
	// textNames: the shader's texts, each with its own tab after the
	// buffers'. hasCommon: whether it has a Common section.
	public void setPassTabs(@NonNull boolean[] buffersPresent, @NonNull boolean[] soundPasses,
			@NonNull List<String> textNames, boolean hasCommon) {
		this.buffersPresent = buffersPresent.clone();
		this.soundPasses = soundPasses.clone();
		this.textNames = List.copyOf(textNames);
		this.hasCommon = hasCommon;
		buildPassTabs();
	}

	public boolean hasBufferPasses() {
		for (boolean present : buffersPresent) {
			if (present) {
				return true;
			}
		}
		return false;
	}

	// Shows another pass's source, keeping the one shown so far's undo
	// history, selection and scroll position to restore if it's switched
	// back to - unlike setText(), which is for loading a different shader.
	public void switchToPass(int pass, @NonNull String text) {
		if (pass == activePass) {
			return;
		}
		passHistories.put(activePass, undoRedo.getEditHistory());
		View horizontalScroll = (View) shaderEditor.getParent();
		passViewStates.put(activePass, new int[]{
				shaderEditor.getSelectionStart(),
				shaderEditor.getSelectionEnd(),
				horizontalScroll.getScrollX(),
				editorScroll.getScrollY()});
		activePass = pass;
		undoRedo.stopListeningForChanges();
		undoRedo.setEditHistory(historyFor(pass));
		shaderEditor.setErrors(errorsFor(pass));
		shaderEditor.setTextHighlighted(text);
		undoRedo.listenForChanges();
		int[] state = passViewStates.get(pass);
		int scrollX = state != null ? state[2] : 0;
		int scrollY = state != null ? state[3] : 0;
		setSelection(state != null ? state[0] : 0, state != null ? state[1] : 0);
		editorScroll.post(() -> {
			horizontalScroll.scrollTo(scrollX, 0);
			editorScroll.scrollTo(0, scrollY);
		});
		highlightErrors();
		buildPassTabs();
	}

	@NonNull
	private static UndoRedo.EditHistory historyFor(int pass) {
		UndoRedo.EditHistory history = passHistories.get(pass);
		if (history == null) {
			history = pass == ShaderError.IMAGE_PASS
					? ShaderEditorApp.editHistory
					: new UndoRedo.EditHistory();
			passHistories.put(pass, history);
		}
		return history;
	}

	@NonNull
	private List<ShaderError> errorsFor(int pass) {
		var errors = new ArrayList<ShaderError>();
		for (var error : allErrors) {
			if (error.getPass() == pass) {
				errors.add(error);
			}
		}
		return errors;
	}

	private boolean passHasErrors(int pass) {
		for (var error : allErrors) {
			if (error.getPass() == pass) {
				return true;
			}
		}
		return false;
	}

	private void buildPassTabs() {
		if (passTabs == null) {
			return;
		}
		passTabs.removeAllViews();
		boolean multipass = hasBufferPasses();
		boolean tabs = multipass || hasCommon || !textNames.isEmpty();
		passTabs.setVisibility(tabs ? View.VISIBLE : View.GONE);
		// The iChannel row is a pass's - none for a text, or Common.
		channelRow.setVisibility(multipass && !isTextPass(activePass) &&
				activePass != ShaderError.COMMON_PASS
				? View.VISIBLE
				: View.GONE);
		if (!tabs) {
			return;
		}
		addPassTab(ShaderError.IMAGE_PASS, getString(R.string.pass_tab_image));
		if (hasCommon) {
			addPassTab(ShaderError.COMMON_PASS, getString(R.string.pass_tab_common));
		}
		for (int buffer = 0; buffer < buffersPresent.length; ++buffer) {
			if (buffersPresent[buffer]) {
				addPassTab(buffer, buffer < soundPasses.length && soundPasses[buffer]
						? getString(R.string.pass_tab_sound)
						: String.valueOf((char) ('A' + buffer)));
			}
		}
		for (int i = 0; i < textNames.size(); ++i) {
			String name = textNames.get(i);
			addPassTab(TEXT_PASS_BASE + i,
					name.length() > 8 ? name.substring(0, 7) + "\u2026" : name);
		}
		refreshChannelRow();
	}

	private void addPassTab(int pass, @NonNull String label) {
		var tab = new TextView(requireContext());
		var text = new SpannableStringBuilder(label);
		if (pass != ShaderError.IMAGE_PASS && pass == getDisplayedPass()) {
			// This buffer is what the preview shows - see displayedPass.
			int start = text.length();
			text.append(" \u25C9");
			text.setSpan(new ForegroundColorSpan(
							ContextCompat.getColor(requireContext(), R.color.toggle_active)),
					start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		if (passHasErrors(pass)) {
			int start = text.length();
			text.append(" \u25CF");
			text.setSpan(new ForegroundColorSpan(Color.RED), start, text.length(),
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		tab.setText(text);
		tab.setGravity(Gravity.CENTER);
		tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		boolean active = pass == activePass;
		tab.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
		tab.setTextColor(active
				? ContextCompat.getColor(requireContext(), R.color.toggle_active)
				: ContextCompat.getColor(requireContext(), R.color.editor_text));
		int size = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48,
				getResources().getDisplayMetrics()));
		tab.setMinWidth(size);
		tab.setPadding(size / 6, 0, size / 6, 0);
		tab.setContentDescription(pass == ShaderError.IMAGE_PASS
				? getString(R.string.pass_tab_image)
				: pass == ShaderError.COMMON_PASS
				? getString(R.string.pass_tab_common)
				: isTextPass(pass)
						? getString(R.string.pass_tab_text, label)
						: getString(R.string.pass_tab_buffer, label));
		tab.setOnClickListener(v -> {
			if (pass != activePass && passSelectedListener != null) {
				passSelectedListener.onPassSelected(pass);
			}
		});
		// Toggles the preview between this buffer and the Image pass - a
		// text, or Common, has no picture to show.
		tab.setOnLongClickListener(isTextPass(pass) || pass == ShaderError.COMMON_PASS
				? null
				: v -> {
			displayedPass = pass == getDisplayedPass() ? ShaderError.IMAGE_PASS : pass;
			buildPassTabs();
			if (passDisplayedListener != null) {
				passDisplayedListener.onPassDisplayed(displayedPass);
			}
			return true;
		});
		passTabs.addView(tab, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, size));
	}

	// One slot per iChannel, showing which buffer (if any) the shown
	// pass's source assigns it - see ChannelDeclarations.
	private void refreshChannelRow() {
		if (channelRow == null || channelRow.getVisibility() != View.VISIBLE) {
			return;
		}
		channelRow.removeAllViews();
		String source = shaderEditor.getCleanText();
		for (int channel = 0; channel < ChannelParameters.MAX_CHANNELS; ++channel) {
			ChannelParameters parameters = ChannelDeclarations.find(source, channel);
			String assigned = parameters == null
					? "\u2014"
					: ChannelParameters.TEXTURE_FONT.equals(parameters.getTexture())
							? getString(R.string.channel_font_short)
							: ChannelParameters.TEXTURE_KEYBOARD.equals(parameters.getTexture())
							? getString(R.string.channel_keyboard_short)
							: parameters.getBuffer() == ChannelParameters.NO_BUFFER
									? "\u2014"
									: String.valueOf((char) ('A' + parameters.getBuffer()));
			var slot = new TextView(requireContext());
			slot.setText(getString(R.string.channel_slot, channel, assigned));
			slot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
			slot.setTextColor(ContextCompat.getColor(requireContext(), R.color.editor_text));
			slot.setTypeface(Typeface.MONOSPACE);
			slot.setGravity(Gravity.CENTER);
			int padding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10,
					getResources().getDisplayMetrics()));
			slot.setPadding(padding / 2, padding, padding / 2, padding);
			final int index = channel;
			slot.setOnClickListener(v -> {
				if (channelClickListener != null) {
					channelClickListener.onChannelClick(index, v);
				}
			});
			channelRow.addView(slot, new LinearLayout.LayoutParams(
					0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
		}
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
		editorScroll.scrollBy(0, delta);
	}


	public String getText() {
		return shaderEditor.getCleanText();
	}

	// Loads a (different) shader: back to its Image pass, with every
	// pass's undo history and remembered position forgotten.
	public void setText(String text) {
		clearError();
		activePass = ShaderError.IMAGE_PASS;
		displayedPass = ShaderError.IMAGE_PASS;
		passHistories.clear();
		passViewStates.clear();
		undoRedo.setEditHistory(ShaderEditorApp.editHistory);
		undoRedo.clearHistory();
		undoRedo.stopListeningForChanges();
		shaderEditor.setTextHighlighted(text);
		undoRedo.listenForChanges();
		buildPassTabs();
	}

	public void insert(@NonNull CharSequence text) {
		shaderEditor.insert(text);
	}

	// Replaces the whole document in one go, same as insert() above but
	// spanning all of it - unlike setText(), this goes through the
	// editor's normal Editable.replace() path instead of resetting it,
	// so it's a single, perfectly normal undoable edit (Ctrl+Z undoes
	// it in one step) rather than wiping undo history the way loading a
	// different shader's source into this same editor should. See
	// MainActivity's "Adjust Defines" tool, the first caller that needs
	// a whole-document programmatic edit to still be undoable.
	public void replaceAll(@NonNull String newText) {
		shaderEditor.replaceAll(newText);
		refreshChannelRow();
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