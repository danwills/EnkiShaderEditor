package enk.danwills.android.enkishadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Parcelable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineHeightSpan;
import android.text.style.ReplacementSpan;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.app.ShaderEditorApp;
import enk.danwills.android.enkishadereditor.highlighter.Highlight;
import enk.danwills.android.enkishadereditor.highlighter.Lexer;
import enk.danwills.android.enkishadereditor.highlighter.Token;
import enk.danwills.android.enkishadereditor.opengl.ShaderError;
import enk.danwills.android.enkishadereditor.preference.Preferences;

public class ShaderEditor extends LineNumberEditText {
	private static final Pattern PATTERN_TRAILING_WHITE_SPACE = Pattern.compile(
			"[\\t ]+$",
			Pattern.MULTILINE);
	private static final Pattern PATTERN_INSERT_UNIFORM = Pattern.compile(
			"^([ \t]*uniform.+)$",
			Pattern.MULTILINE);
	private static final Pattern PATTERN_ENDIF = Pattern.compile(
			"(#endif)\\b");
	private static final Pattern PATTERN_NO_BREAK_SPACE = Pattern.compile(
			"\\xA0");
	private static final ArrayList<String> DEFAULT_COMPLETIONS = new ArrayList<>();

	static {
		DEFAULT_COMPLETIONS.add("{");
		DEFAULT_COMPLETIONS.add("}");
		DEFAULT_COMPLETIONS.add("(");
		DEFAULT_COMPLETIONS.add(")");
		DEFAULT_COMPLETIONS.add("[");
		DEFAULT_COMPLETIONS.add("]");
	}

	private int revision = 0;
	private final int[] colors = new int[Highlight.values().length];
	// In HIGHLIGHTING_FAST (see highlight()), live typing never blocks
	// waiting for a fresh token list - it uses whatever's already
	// finished and otherwise leaves the editor showing its last
	// highlight. This same background-tokenization-completed callback
	// is what catches it up afterwards, once the newest revision's
	// result actually lands, without ever stalling the UI thread to
	// get there. tokenRevision == revision (guarding both branches
	// below) is what keeps a now-stale, already-superseded result from
	// a cancelled-but-still-finishing older computation from applying.
	private final TokenListUpdater tokenListUpdater = new TokenListUpdater(
			(tokenRevision, tokens, text) -> post(() -> {
				if (tokenRevision != revision) {
					return;
				}
				provideCompletions(tokens, text);
				if (ShaderEditorApp.preferences.getSyntaxHighlightingMode()
						== Preferences.HIGHLIGHTING_FAST) {
					Editable e = getText();
					if (e != null) {
						applySpans(e, tokens, false);
						this.tokens = tokens;
					}
				}
			}));
	private final Runnable updateRunnable = new Runnable() {
		@Override
		public void run() {
			Editable e = getText();

			if (e != null) {
				if (onEditPausedListener != null) {
					onEditPausedListener.onEditPaused(e.toString());
				}

				highlightWithoutChange(e);
			}

		}
	};

	@Nullable
	private OnTextModifiedListener onTextModifiedListener;
	@Nullable
	private OnEditPausedListener onEditPausedListener;
	@Nullable
	private CodeCompletionListener codeCompletionListener;
	private int updateDelay = 2000;
	@NonNull
	private List<ShaderError> shaderErrors = Collections.emptyList();
	private boolean isUserInteraction = true;
	private int colorError;
	private int colorSearchMatch;
	private int textColor;
	@Nullable
	private Object searchMatchSpan;
	private int tabWidthInCharacters = 0;
	private int tabWidth = 0;
	private List<Token> tokens = new ArrayList<>();
	private boolean isApplyingEdit = false;

	// Double-tap-and-drag selects by word, triple-tap-and-drag by line,
	// mirroring the word/line selection gestures of desktop editors.
	private static final int SELECTION_GESTURE_NONE = 0;
	private static final int SELECTION_GESTURE_WORD = 1;
	private static final int SELECTION_GESTURE_LINE = 2;
	private int pendingTapCount = 0;
	private long lastTapDownTime = 0;
	private float lastTapDownX = 0;
	private float lastTapDownY = 0;
	private int selectionGesture = SELECTION_GESTURE_NONE;
	private int selectionGestureAnchorStart = 0;
	private int selectionGestureAnchorEnd = 0;

	// EditText freezes (saves) its full text by default on a config
	// change, which for a large shader source can push the saved-state
	// Bundle past Android's ~1MB Binder transaction limit and crash the
	// app with TransactionTooLargeException on rotation. Caching it here
	// instead sidesteps that entirely: a plain static field survives the
	// Activity recreate for free (the process itself isn't killed), with
	// no Binder size limit to worry about.
	@Nullable
	private static String pendingRestoreText;
	private static int pendingRestoreSelStart = -1;
	private static int pendingRestoreSelEnd = -1;

	public ShaderEditor(Context context) {
		super(context);
		init(context);
	}

	public ShaderEditor(
			@NonNull Context context,
			@Nullable AttributeSet attrs) {
		super(context, attrs);
		init(context);
	}

	// The app's theme hides the cursor's insertion handle (see AppTheme),
	// but in code it's how to move the cursor precisely - so the editor
	// gets the platform's back. Only settable from Android 10.
	private void restoreInsertionHandle(@NonNull Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			return;
		}
		var platform = new ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault);
		TypedArray attrs = platform.obtainStyledAttributes(
				new int[]{android.R.attr.textSelectHandle});
		try {
			Drawable handle = attrs.getDrawable(0);
			if (handle != null) {
				setTextSelectHandle(handle);
			}
		} finally {
			attrs.recycle();
		}
	}

	@Nullable
	@Override
	public Parcelable onSaveInstanceState() {
		pendingRestoreText = getText() != null ? getText().toString() : null;
		pendingRestoreSelStart = getSelectionStart();
		pendingRestoreSelEnd = getSelectionEnd();
		// Keeps the Parcelable itself tiny (selection only, no text) by
		// turning off EditText's own text-freezing just for this call -
		// the text above takes its place for restoration instead.
		boolean wasFreezingText = getFreezesText();
		setFreezesText(false);
		Parcelable state = super.onSaveInstanceState();
		setFreezesText(wasFreezingText);
		return state;
	}

	@Override
	public void onRestoreInstanceState(Parcelable state) {
		super.onRestoreInstanceState(state);
		if (pendingRestoreText != null) {
			// setTextHighlighted() resets the selection, so it has to be
			// re-applied afterward rather than relying on the Parcelable's
			// own (now text-less) selection restoration above.
			setTextHighlighted(pendingRestoreText);
			int length = pendingRestoreText.length();
			setSelection(
					Math.max(0, Math.min(pendingRestoreSelStart, length)),
					Math.max(0, Math.min(pendingRestoreSelEnd, length)));
			pendingRestoreText = null;
		}
	}

	private static <T> void clearSpans(Spannable e,
			int start, int end, Class<T> clazz) {
		// Remove foreground color spans.
		T[] spans = e.getSpans(
				start,
				end,
				clazz);
		for (T span : spans) {
			e.removeSpan(span);
		}
	}

	public void setOnEditPausedListener(
			@Nullable OnEditPausedListener listener) {
		onEditPausedListener = listener;
	}

	public void setOnTextModifiedListener(
			@Nullable OnTextModifiedListener listener) {
		onTextModifiedListener = listener;
	}

	public void setOnCompletionsListener(
			@Nullable CodeCompletionListener listener) {
		codeCompletionListener = listener;
	}

	public void setUpdateDelay(int ms) {
		updateDelay = ms;
	}

	public void setTabWidth(int characters) {
		if (tabWidthInCharacters == characters) {
			return;
		}

		tabWidthInCharacters = characters;
		tabWidth = Math.round(getPaint().measureText("m") * characters);
	}

	public boolean hasErrors() {
		return !shaderErrors.isEmpty();
	}

	public List<ShaderError> getErrors() {
		return shaderErrors;
	}

	public void setErrors(@NonNull List<ShaderError> errorLines) {
		this.shaderErrors = errorLines;
	}

	public void updateHighlighting() {
		Editable text = getText();
		if (text != null) {
			highlightWithoutChange(text);
		}
	}

	// Its own BackgroundColorSpan subclass, distinct from the plain
	// BackgroundColorSpan highlightMatch() below uses, so clearError()'s
	// sweep (run on every syntax-highlight pass - far more often than an
	// actual error state change) only ever touches error highlighting,
	// never a search match that's supposed to stick around until the
	// next edit.
	private static final class ErrorBackgroundSpan extends BackgroundColorSpan {
		ErrorBackgroundSpan(int color) {
			super(color);
		}
	}

	private void clearError(@Nullable Spannable e) {
		if (e == null) {
			return;
		}
		clearSpans(e, 0, e.length(), ErrorBackgroundSpan.class);
	}

	private void highlightErrors(List<ShaderError> shaderErrors) {
		Spannable e = getText();
		clearError(e);
		if (e == null || e.length() == 0) {
			return;
		}
		for (ShaderError shaderError : shaderErrors) {
			int line = shaderError.getLine() - 1;
			Layout layout = getLayout();
			if (line < 0 || line >= layout.getLineCount()) {
				continue;
			}
			e.setSpan(
					new ErrorBackgroundSpan(colorError),
					layout.getLineStart(line),
					layout.getLineEnd(line),
					Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
	}

	public void updateErrorHighlighting() {
		Spannable e = getText();
		clearError(e);
		if (!shaderErrors.isEmpty()) {
			highlightErrors(shaderErrors);
		}
	}

	public void setTextHighlighted(CharSequence text) {
		if (text == null) {
			text = "";
		}

		cancelUpdate();

		shaderErrors = Collections.emptyList();

		isUserInteraction = false;

		// `setText` can't be overridden
		tokenListUpdater.update(text, ++revision);
		setText(highlight(new SpannableStringBuilder(text), true));
		isUserInteraction = true;

		Editable e = getText();
		if (e != null) {
			var str = e.toString();
			if (onTextModifiedListener != null) {
				onTextModifiedListener.onTextModified();
			}
			if (onEditPausedListener != null) {
				onEditPausedListener.onEditPaused(str);
			}
		}
	}

	public String getCleanText() {
		Editable text = getText();
		if (text == null) {
			return "";
		}
		return PATTERN_TRAILING_WHITE_SPACE
				.matcher(text)
				.replaceAll("");
	}

	public void insert(@NonNull CharSequence text) {
		int start = getSelectionStart();
		int end = getSelectionEnd();
		Editable e = getText();
		if (e == null) {
			return;
		}
		e.replace(Math.min(start, end),
				Math.max(start, end),
				text,
				0,
				text.length());
	}

	// Same shape as insert() above, but replacing the entire document
	// rather than the current selection - see EditorFragment.replaceAll().
	public void replaceAll(@NonNull String text) {
		Editable e = getText();
		if (e == null) {
			return;
		}
		e.replace(0, e.length(), text, 0, text.length());
		// Replacing everything drops every colour span, and the usual
		// highlight only redoes the tokens that changed - so redo it all.
		isUserInteraction = false;
		highlight(e, true);
		isUserInteraction = true;
	}

	public void navigateToLine(int lineNumber) {
		// Navigate to start of line if not already there.
		if (lineNumber < 1) {
			return;
		}
		Layout layout = getLayout();
		int lineStart = layout.getLineStart(lineNumber - 1);
		int lineEnd = layout.getLineEnd(lineNumber - 1);
		if (getSelectionStart() >= lineStart &&
				getSelectionEnd() <= lineEnd) {
			return;
		}
		setSelection(lineStart, lineStart);
	}

	// Every case-insensitive occurrence of `query`, as start offsets -
	// lets a caller build its own previous/random/next navigation (and
	// an "N of M" position indicator) over a stable list, the same
	// pattern the shader-library find dialog already uses, rather than
	// just stepping forward one match at a time.
	@NonNull
	public List<Integer> findAllMatches(@NonNull String query) {
		List<Integer> matches = new ArrayList<>();
		Editable text = getText();
		if (text == null || query.isEmpty()) {
			return matches;
		}
		String haystack = text.toString().toLowerCase(Locale.ROOT);
		String needle = query.toLowerCase(Locale.ROOT);
		int from = 0;
		int index;
		while ((index = haystack.indexOf(needle, from)) >= 0) {
			matches.add(index);
			from = index + needle.length();
		}
		return matches;
	}

	// Selects [start, start + length) without scrolling anything - the
	// other half of findAllMatches() above. Deliberately doesn't call
	// bringPointIntoView(): a caller that wants the match scrolled into
	// view should do that itself, precisely, via scrollMatchToScreenY()
	// against getMatchBoundsOnScreen() below - letting this also do a
	// coarse default scroll risked fighting that second, more precise
	// one for the final scroll position.
	public void selectMatch(int start, int length) {
		requestFocus();
		setSelection(start, start + length);
	}

	// Marks [start, start + length) with a highlight color distinct
	// from the normal text-selection color, so a found match stays
	// visually obvious even after the selection handles/cursor toolbar
	// stop drawing attention to it. Replaces whatever was highlighted
	// before; stays until the next call or clearMatchHighlight().
	public void highlightMatch(int start, int length) {
		Spannable text = getText();
		if (text == null) {
			return;
		}
		clearMatchHighlight();
		Object span = new BackgroundColorSpan(colorSearchMatch);
		text.setSpan(span, start, start + length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		searchMatchSpan = span;
	}

	public void clearMatchHighlight() {
		if (searchMatchSpan == null) {
			return;
		}
		Spannable text = getText();
		if (text != null) {
			text.removeSpan(searchMatchSpan);
		}
		searchMatchSpan = null;
	}

	// [start, start + length)'s bounding box in absolute screen
	// coordinates - lets a caller (which doesn't otherwise know
	// anything about this view's internal Layout) draw an indicator
	// over the match, or compute how to scroll some ancestor so the
	// match ends up at a particular screen position (e.g. below a
	// dialog covering the top of the screen). Null if there's no laid-
	// out text yet, or the range falls outside it.
	@Nullable
	public Rect getMatchBoundsOnScreen(int start, int length) {
		Layout layout = getLayout();
		Editable text = getText();
		if (layout == null || text == null ||
				start < 0 || start + length > text.length()) {
			return null;
		}
		int line = layout.getLineForOffset(start);
		float left = layout.getPrimaryHorizontal(start);
		float right = layout.getPrimaryHorizontal(start + length);
		int top = layout.getLineTop(line);
		int bottom = layout.getLineBottom(line);

		int[] location = new int[2];
		getLocationOnScreen(location);
		// This view's own getScrollX/Y are normally 0 here - the
		// HorizontalScrollView/ScrollView wrapping it scroll by moving
		// the whole view instead, which getLocationOnScreen() already
		// reflects - but included for correctness in case that ever
		// changes.
		int originX = location[0] + getTotalPaddingLeft() - getScrollX();
		int originY = location[1] + getTotalPaddingTop() - getScrollY();

		return new Rect(
				originX + Math.round(Math.min(left, right)),
				originY + top,
				originX + Math.round(Math.max(left, right)),
				originY + bottom);
	}

	public void addUniform(String statement) {
		if (statement == null) {
			return;
		}

		Editable e = getText();
		if (e == null) {
			return;
		}
		removeUniform(e, statement);

		Matcher m = PATTERN_INSERT_UNIFORM.matcher(e);
		int start = -1;

		while (m.find()) {
			start = m.end();
		}

		if (start > -1) {
			// Add line break before statement because it's
			// inserted before the last line-break.
			statement = "\n" + statement;
		} else {
			// Add a line break after statement if there's no
			// uniform already.
			statement += "\n";

			// Add an empty line between the last #endif
			// and the now following uniform.
			if ((start = endIndexOfLastEndIf(e)) > -1) {
				statement = "\n" + statement;
			}

			// Move index past line break or to the start
			// of the text when no #endif was found.
			++start;
		}

		e.insert(start, statement);
	}

	/**
	 * Define functions required for AutoFill API
	 */
	@RequiresApi(api = Build.VERSION_CODES.O)
	@Override
	public int getAutofillType() {
		return AUTOFILL_TYPE_NONE;
	}

	@Nullable
	@Override
	public InputConnection onCreateInputConnection(
			@NonNull EditorInfo outAttrs) {
		InputConnection connection = super.onCreateInputConnection(outAttrs);
		if (ShaderEditorApp.preferences.hideNativeSuggestions()) {
			outAttrs.inputType = InputType.TYPE_NULL;
		}
		return connection;
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN: {
				long now = event.getEventTime();
				float x = event.getX();
				float y = event.getY();
				long timeout = ViewConfiguration.getDoubleTapTimeout();
				int slop = ViewConfiguration.get(getContext()).getScaledDoubleTapSlop();
				boolean continuesTapSequence = now - lastTapDownTime <= timeout &&
						Math.hypot(x - lastTapDownX, y - lastTapDownY) <= slop;
				pendingTapCount = continuesTapSequence ? pendingTapCount + 1 : 1;
				lastTapDownTime = now;
				lastTapDownX = x;
				lastTapDownY = y;

				if (pendingTapCount == 2) {
					beginSelectionGesture(SELECTION_GESTURE_WORD, getOffsetForPosition(x, y));
					return true;
				} else if (pendingTapCount >= 3) {
					beginSelectionGesture(SELECTION_GESTURE_LINE, getOffsetForPosition(x, y));
					return true;
				}
				break;
			}
			case MotionEvent.ACTION_MOVE: {
				if (selectionGesture != SELECTION_GESTURE_NONE) {
					extendSelectionGesture(getOffsetForPosition(event.getX(), event.getY()));
					return true;
				}
				break;
			}
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL: {
				if (selectionGesture != SELECTION_GESTURE_NONE) {
					selectionGesture = SELECTION_GESTURE_NONE;
					return true;
				}
				break;
			}
			default:
				break;
		}
		return super.onTouchEvent(event);
	}

	private void beginSelectionGesture(int gesture, int offset) {
		int[] bounds = gesture == SELECTION_GESTURE_WORD ? wordBounds(offset) : lineBounds(offset);
		selectionGesture = gesture;
		selectionGestureAnchorStart = bounds[0];
		selectionGestureAnchorEnd = bounds[1];
		requestFocus();
		setSelection(selectionGestureAnchorStart, selectionGestureAnchorEnd);
		bringPointIntoView(selectionGestureAnchorStart);
	}

	private void extendSelectionGesture(int offset) {
		int[] bounds = selectionGesture == SELECTION_GESTURE_WORD
				? wordBounds(offset)
				: lineBounds(offset);
		int start, end;
		if (bounds[0] <= selectionGestureAnchorStart) {
			start = bounds[0];
			end = selectionGestureAnchorEnd;
		} else {
			start = selectionGestureAnchorStart;
			end = bounds[1];
		}
		if (start != getSelectionStart() || end != getSelectionEnd()) {
			setSelection(start, end);
		}
		bringPointIntoView(bounds[0] <= selectionGestureAnchorStart ? bounds[0] : bounds[1]);
	}

	// Mirrors the token lookup in provideCompletions() so word-selection
	// matches whatever the syntax highlighter considers one token.
	private int[] wordBounds(int offset) {
		Editable text = getText();
		if (text == null || text.length() == 0) {
			return new int[]{0, 0};
		}
		Token token = Lexer.findToken(tokens, offset);
		if (token == null && offset > 0) {
			token = Lexer.findToken(tokens, offset - 1);
		}
		if (token != null) {
			return new int[]{token.startOffset(), token.endOffset()};
		}
		int pos = Math.max(0, Math.min(offset, text.length() - 1));
		return new int[]{pos, pos + 1};
	}

	private int[] lineBounds(int offset) {
		Layout layout = getLayout();
		if (layout == null) {
			return new int[]{offset, offset};
		}
		int line = layout.getLineForOffset(offset);
		return new int[]{layout.getLineStart(line), layout.getLineEnd(line)};
	}

	@Override
	protected void onSelectionChanged(int selStart, int selEnd) {
		super.onSelectionChanged(selStart, selEnd);
		Editable text = getText();
		List<Token> completedTokens;
		if (text != null &&
				codeCompletionListener != null &&
				getSelectionStart() == getSelectionEnd() &&
				!isApplyingEdit &&
				(completedTokens = tokenListUpdater.getCompletedTokens(revision)) != null) {
			provideCompletions(completedTokens, text);
		}
	}

	private void removeUniform(Editable e, String statement) {
		if (statement == null) {
			return;
		}

		String regex = "^(" + statement.replace(" ", "[ \\t]+");
		int p = regex.indexOf(";");
		if (p > -1) {
			regex = regex.substring(0, p);
		}
		regex += ".*\\n)$";

		Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(e);
		if (m.find()) {
			e.delete(m.start(), m.end());
		}
	}

	private int endIndexOfLastEndIf(Editable e) {
		Matcher m = PATTERN_ENDIF.matcher(e);
		int idx = -1;

		while (m.find()) {
			idx = m.end();
		}

		return idx;
	}

	private void init(@NonNull Context context) {
		// Setting this through XML does not work
		setHorizontallyScrolling(true);
		restoreInsertionHandle(context);

		setFilters(new InputFilter[]{(source,
				start, end,
				dest, dstart, dend) -> {
			if (isUserInteraction &&
					end - start == 1 &&
					start < source.length() &&
					dstart < dest.length()) {
				char c = source.charAt(start);

				if (c == '\n') {
					return autoIndent(source, dest, dstart, dend);
				}
			}

			return source;
		}});

		addTextChangedListener(new TextWatcher() {
			private int start = 0;
			private int count = 0;

			@Override
			public void onTextChanged(
					CharSequence s,
					int start,
					int before,
					int count) {
				this.start = start;
				this.count = count;
			}

			@Override
			public void beforeTextChanged(
					CharSequence s,
					int start,
					int count,
					int after) {
				isApplyingEdit = true;
			}

			@Override
			public void afterTextChanged(Editable e) {
				cancelUpdate();
				convertTabs(e, start, count);

				if (!isUserInteraction) {
					isApplyingEdit = false;
					return;
				}

				if (onTextModifiedListener != null) {
					onTextModifiedListener.onTextModified();
				}

				isApplyingEdit = false;
				tokenListUpdater.update(e, ++revision);
				postDelayed(updateRunnable, updateDelay);
			}
		});

		setSyntaxColors(context);
		setUpdateDelay(ShaderEditorApp.preferences.getUpdateDelay());
		setTabWidth(ShaderEditorApp.preferences.getTabWidth());

		setOnKeyListener((v, keyCode, event) -> {
			if (ShaderEditorApp.preferences.useTabForIndent() &&
					event.getAction() == KeyEvent.ACTION_DOWN &&
					keyCode == KeyEvent.KEYCODE_TAB) {
				// Insert a tab character instead of doing focus
				// navigation.
				insert("\t");
				return true;
			}
			return false;
		});
	}

	private void setSyntaxColors(Context context) {
		for (Highlight highlight : Highlight.values()) {
			colors[highlight.ordinal()] =
					ContextCompat.getColor(context, highlight.id());
		}
		textColor = ContextCompat.getColor(context, R.color.editor_text);
		colorError = ContextCompat.getColor(
				context,
				R.color.syntax_error);
		colorSearchMatch = ContextCompat.getColor(
				context,
				R.color.search_match);
	}

	private void cancelUpdate() {
		removeCallbacks(updateRunnable);
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		cancelUpdate();
		tokenListUpdater.shutdown();
	}

	private void highlightWithoutChange(@NonNull Editable e) {
		isUserInteraction = false;
		highlight(e, false);
		isUserInteraction = true;
	}

	private Editable highlight(@NonNull Editable e, boolean complete) {
		int length = e.length();

		clearError(e);

		if (length == 0) {
			tokens = new ArrayList<>();
			return e;
		}

		// When pasting text from other apps, e.g. Github Mobile code
		// viewer, the text can be tainted with No-Break Space (U+00A0)
		// characters.
		for (Matcher m = PATTERN_NO_BREAK_SPACE.matcher(e); m.find(); ) {
			e.replace(m.start(), m.end(), " ");
		}

		int mode = ShaderEditorApp.preferences.getSyntaxHighlightingMode();
		if (mode == Preferences.HIGHLIGHTING_OFF) {
			clearSpans(e, 0, length, ForegroundColorSpan.class);
			tokens = new ArrayList<>();
			return e;
		}

		// FULL (and any one-time complete highlight, e.g. a freshly
		// loaded shader - see setTextHighlighted()) waits for a fresh,
		// fully up-to-date token list before touching a single span,
		// same as this app has always done; that's exactly the UI-
		// thread stall FAST exists to avoid for live typing, so it
		// instead uses whatever's already finished and lets the
		// background computation's own completion callback (see the
		// tokenListUpdater field above) catch the spans up once ready,
		// without ever blocking here.
		if (complete || mode == Preferences.HIGHLIGHTING_FULL) {
			List<Token> newTokens = tokenListUpdater.ensureUpdated(e, revision);
			applySpans(e, newTokens, complete);
			tokens = newTokens;
			return e;
		}
		List<Token> ready = tokenListUpdater.getCompletedTokens(revision);
		if (ready != null) {
			applySpans(e, ready, false);
			tokens = ready;
		}

		return e;
	}

	// Applies newTokens' coloring to e - complete clears and respans
	// the whole document (used for a one-time full highlight, e.g. a
	// freshly loaded shader); otherwise just the range Lexer.diff()
	// says actually changed since `tokens` (the previous, already-
	// applied list), which is cheap enough to redo on every live
	// keystroke/async catch-up. Shared by highlight() (FULL, and any
	// complete pass) and the tokenListUpdater completion callback above
	// (FAST's async catch-up).
	private void applySpans(@NonNull Editable e, @NonNull List<Token> newTokens, boolean complete) {
		int length = e.length();
		if (complete) {
			clearSpans(e, 0, length, ForegroundColorSpan.class);
			for (Token token : newTokens) {
				@ColorInt int color = colors[Highlight.from(token.type()).ordinal()];
				if (color != textColor) {
					e.setSpan(
							new ForegroundColorSpan(color),
							token.startOffset(), token.endOffset(),
							Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
				}
			}
			return;
		}
		Lexer.Diff diff = Lexer.diff(tokens, newTokens);
		if (diff.start <= diff.deleteEnd) {
			int startOffset = newTokens.get(diff.start).startOffset();
			int endOffset = newTokens.get(diff.insertEnd).endOffset();
			clearSpans(e, startOffset, endOffset, ForegroundColorSpan.class);
		}
		for (int i = diff.start; i <= diff.insertEnd; ++i) {
			Token token = newTokens.get(i);
			e.setSpan(
					new ForegroundColorSpan(
							colors[Highlight.from(token.type()).ordinal()]),
					token.startOffset(),
					token.endOffset(),
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
	}

	private void provideCompletions(
			@NonNull List<Token> tokens,
			@NonNull CharSequence text) {
		CodeCompletionListener listener = codeCompletionListener;
		if (listener == null) {
			return;
		}
		int start = getSelectionStart();
		Token tok = Lexer.findToken(tokens, start);
		if (tok == null && start > 0) {
			tok = Lexer.findToken(tokens, start - 1);
		}
		if (tok == null) {
			listener.onCodeCompletions(DEFAULT_COMPLETIONS, 0);
			return;
		}
		int positionInToken = start - tok.startOffset();
		List<String> completions = Lexer.completeKeyword(
				Lexer.tokenSource(tok, text).subSequence(0, positionInToken).toString(),
				tok.category());

		if (completions.isEmpty()) {
			completions = DEFAULT_COMPLETIONS;
			positionInToken = 0;
		}
		if (completions.size() == 1 && completions.get(0).contentEquals(
				text.subSequence(tok.startOffset(), start))) {
			completions = DEFAULT_COMPLETIONS;
			positionInToken = 0;
		}
		listener.onCodeCompletions(
				completions,
				positionInToken);
	}

	private CharSequence autoIndent(
			CharSequence source,
			Spanned dest,
			int dstart,
			int dend) {
		String indent = "";
		int istart = dstart - 1;

		// Find start of this line.
		boolean dataBefore = false;
		int pt = 0;

		for (; istart > -1; --istart) {
			char c = dest.charAt(istart);

			if (c == '\n') {
				break;
			}

			if (c != ' ' && c != '\t') {
				if (!dataBefore) {
					// Indent always after those characters.
					if (c == '{' ||
							c == '+' ||
							c == '-' ||
							c == '*' ||
							c == '/' ||
							c == '%' ||
							c == '^' ||
							c == '=') {
						--pt;
					}

					dataBefore = true;
				}

				// Parenthesis counter.
				if (c == '(') {
					--pt;
				} else if (c == ')') {
					++pt;
				}
			}
		}

		// Copy indent of this line into the next.
		if (istart > -1) {
			char charAtCursor = dest.charAt(dstart);
			int iend;

			for (iend = ++istart; iend < dend; ++iend) {
				char c = dest.charAt(iend);

				// Auto expand comments.
				if (charAtCursor != '\n' &&
						c == '/' &&
						iend + 1 < dend &&
						dest.charAt(iend) == c) {
					iend += 2;
					break;
				}

				if (c != ' ' && c != '\t') {
					break;
				}
			}

			indent += dest.subSequence(istart, iend);
		}

		// Add new indent.
		if (pt < 0) {
			indent += "\t";
		}

		// Append white space of previous line and new indent.
		return source + indent;
	}

	private void convertTabs(Editable e, int start, int count) {
		// From start to start + count (not to count, which left a change's
		// old spans behind to pile up).
		clearSpans(e, start, start + count, TabWidthSpan.class);
		if (tabWidth < 1) {
			return;
		}

		String s = e.toString();

		for (int stop = start + count;
				(start = s.indexOf("\t", start)) > -1 && start < stop;
				++start) {
			e.setSpan(
					new TabWidthSpan(tabWidth),
					start,
					start + 1,
					Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
	}

	@FunctionalInterface
	public interface OnEditPausedListener {
		void onEditPaused(@NonNull String text);
	}

	@FunctionalInterface
	public interface OnTextModifiedListener {
		void onTextModified();
	}

	@FunctionalInterface
	public interface CodeCompletionListener {
		void onCodeCompletions(@NonNull List<String> completions, int position);
	}

	@FunctionalInterface
	interface OnTokenized {
		void onTokens(int revision, @NonNull List<Token> tokens, @NonNull CharSequence text);
	}

	private static class TabWidthSpan
			extends ReplacementSpan
			implements LineHeightSpan.WithDensity {
		private final int width;

		private TabWidthSpan(int width) {
			this.width = width;
		}

		@Override
		public int getSize(
				@NonNull Paint paint,
				CharSequence text,
				int start,
				int end,
				Paint.FontMetricsInt fm) {
			return width;
		}

		@Override
		public void draw(
				@NonNull Canvas canvas,
				CharSequence text,
				int start,
				int end,
				float x,
				int top,
				int y,
				int bottom,
				@NonNull Paint paint) {
		}

		@Override
		public void chooseHeight(CharSequence text, int start, int end,
				int spanstartv, int lineHeight,
				Paint.FontMetricsInt fm, TextPaint paint) {
			paint.getFontMetricsInt(fm);
		}

		@Override
		public void chooseHeight(CharSequence text, int start, int end,
				int spanstartv, int lineHeight, Paint.FontMetricsInt fm) {
		}
	}

	static class TokenizeCalculation implements Callable<List<Token>> {
		private final String text;
		private final int revision;
		@NonNull
		private final OnTokenized onTokenized;

		public TokenizeCalculation(
				int revision,
				@NonNull String text,
				@NonNull OnTokenized onTokenized) {
			this.revision = revision;
			this.text = text;
			this.onTokenized = onTokenized;
		}

		@Override
		public List<Token> call() {
			Lexer lexer = new Lexer(text);
			List<Token> tokens = new ArrayList<>();
			for (Token token : lexer) {
				tokens.add(token);
				if (Thread.currentThread().isInterrupted()) {
					break;
				}
			}
			onTokenized.onTokens(revision, tokens, text);
			return tokens;
		}
	}

	static class TokenListUpdater {
		@NonNull
		private final OnTokenized onTokenized;
		@NonNull
		private final ExecutorService executor = Executors.newSingleThreadExecutor();
		@Nullable
		private FutureTask<List<Token>> task;
		@NonNull
		private List<Token> completedTokens = Collections.emptyList();
		private int revision = -1;
		private int completedRevision = -1;

		public TokenListUpdater(@NonNull OnTokenized onTokenized) {
			this.onTokenized = onTokenized;
		}

		@Nullable
		public synchronized List<Token> getCompletedTokens(int revision) {
			return revision == completedRevision ? completedTokens : null;
		}

		private synchronized void setCompleted(int revision, @NonNull List<Token> tokens) {
			if (revision != this.revision) {
				return;
			}
			completedRevision = revision;
			completedTokens = tokens;
		}

		@NonNull
		public List<Token> ensureUpdated(@NonNull CharSequence text, int revision) {
			FutureTask<List<Token>> futureTask;
			synchronized (this) {
				if (task == null || revision != this.revision) {
					update(text, revision);
				}
				futureTask = task;
			}
			if (futureTask == null) {
				return Collections.emptyList();
			}
			try {
				List<Token> tokens = futureTask.get();
				setCompleted(revision, tokens);
				return tokens;
			} catch (ExecutionException | InterruptedException e) {
				throw new RuntimeException(e);
			}
		}

		public void update(@NonNull CharSequence text, int revision) {
			FutureTask<List<Token>> currentTask;
			synchronized (this) {
				if (revision == this.revision && task != null) {
					return;
				}
				currentTask = task;
				this.revision = revision;
				task = new FutureTask<>(new TokenizeCalculation(
						revision,
						text.toString(),
						(tokenRevision, tokens, tokenText) -> {
							setCompleted(tokenRevision, tokens);
							onTokenized.onTokens(tokenRevision, tokens, tokenText);
						}));
			}
			if (currentTask != null) {
				currentTask.cancel(false);
			}
			executor.submit(task);
		}

		public void shutdown() {
			if (task != null) {
				task.cancel(true);
				task = null;
			}
			executor.shutdownNow();
		}
	}
}
