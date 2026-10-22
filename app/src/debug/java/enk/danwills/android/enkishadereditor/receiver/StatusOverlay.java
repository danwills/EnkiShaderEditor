package enk.danwills.android.enkishadereditor.receiver;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Debug builds only: TestStatusReceiver's panel of Claude's status lines,
// near the top of the screen (just under the status bar) over every app.
// From a run's first line to its last, a pill at its top says Claude is
// using the phone, so something always shows. Lines are added as they come
// rather than queued: the newest at the bottom, MAX_LINES at most, each
// fading out after a while, the panel going once they and the pill have. Touches go
// straight through it (and it's at the opacity Android lets them through
// another app's overlay at). Each line starts with its kind's icon, and
// is coloured: `code` in cyan, numbers in yellow, words that sound good
// in green and bad in red.
@RequiresApi(Build.VERSION_CODES.O)
final class StatusOverlay {
	private static final int MAX_LINES = 8;
	private static final long LINE_MILLIS = 20_000;
	private static final long DONE_MILLIS = 15_000;
	private static final long FADE_MILLIS = 600;
	// The most a non-touchable overlay can show and still let touches
	// through to another app (Android 12's touch occlusion rules).
	private static final float OPACITY = 0.8f;

	private static final Pattern CODE = Pattern.compile("`([^`]+)`");
	private static final Pattern NUMBER = Pattern.compile(
			"(?<![\\w.])[-+]?\\d+(?:\\.\\d+)?(?:%|x|fps|ms|s|px)?(?![\\w.])");
	private static final Pattern GOOD = Pattern.compile(
			"(?i)\\b(ok|works?|working|worked|passed|pass|fixed|done|success|good|nice|yes|" +
					"compiles?|compiled|clean|ready|found)\\b");
	private static final Pattern BAD = Pattern.compile(
			"(?i)\\b(fail(?:s|ed|ing)?|errors?|broken|crash(?:ed|es)?|wrong|nan|" +
					"can't|cannot|missing|stuck|refused|interrupted)\\b");

	// Holds only the application context.
	@SuppressLint("StaticFieldLeak")
	private static StatusOverlay instance;

	private final Context context;
	private final WindowManager windows;
	private final Handler handler = new Handler(Looper.getMainLooper());
	@Nullable
	private LinearLayout panel;
	// "Claude is using your phone", from a run's first line to its last.
	@Nullable
	private TextView badge;

	private StatusOverlay(@NonNull Context context) {
		this.context = context;
		windows = context.getSystemService(WindowManager.class);
	}

	@NonNull
	static StatusOverlay get(@NonNull Context app) {
		if (instance == null) {
			instance = new StatusOverlay(app);
		}
		return instance;
	}

	void add(@NonNull String text, @Nullable String kind, boolean done) {
		LinearLayout lines = panel();
		// While a run's going, a pill at the top says so, whatever's faded.
		if (!done && badge == null) {
			badge = new TextView(context);
			badge.setText("🤖 Claude is using your phone");
			badge.setTextColor(Color.rgb(255, 200, 120));
			badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
			badge.setPadding(0, 0, 0, dp(2));
			lines.addView(badge, 0);
		} else if (done && badge != null) {
			lines.removeView(badge);
			badge = null;
		}
		while (lines.getChildCount() - (badge != null ? 1 : 0) >= MAX_LINES) {
			lines.removeViewAt(badge != null ? 1 : 0);
		}
		var line = new TextView(context);
		line.setTextColor(Color.rgb(235, 235, 240));
		line.setTextSize(TypedValue.COMPLEX_UNIT_SP, done ? 18 : 13.5f);
		line.setLineSpacing(0, 1.1f);
		line.setPadding(0, dp(done ? 8 : 3), 0, dp(done ? 8 : 3));
		line.setText(styled(text, kind, done));
		lines.addView(line);
		handler.postDelayed(() -> fade(line), done ? DONE_MILLIS : LINE_MILLIS);
	}

	void clear() {
		handler.removeCallbacksAndMessages(null);
		if (panel != null) {
			windows.removeView(panel);
			panel = null;
		}
		// Back with the next line, if the run's still going.
		badge = null;
	}

	private void fade(@NonNull TextView line) {
		line.animate().alpha(0f).setDuration(FADE_MILLIS).withEndAction(() -> {
			if (panel == null || line.getParent() != panel) {
				return;
			}
			panel.removeView(line);
			if (badge == null && panel.getChildCount() == 0) {
				clear();
			}
		});
	}

	@NonNull
	private LinearLayout panel() {
		if (panel != null) {
			return panel;
		}
		var lines = new LinearLayout(context);
		lines.setOrientation(LinearLayout.VERTICAL);
		lines.setPadding(dp(14), dp(10), dp(14), dp(10));
		var background = new GradientDrawable();
		background.setColor(Color.argb(240, 16, 18, 26));
		background.setCornerRadius(dp(16));
		background.setStroke(dp(2), Color.rgb(217, 119, 87));
		lines.setBackground(background);
		var params = new WindowManager.LayoutParams(
				WindowManager.LayoutParams.MATCH_PARENT,
				WindowManager.LayoutParams.WRAP_CONTENT,
				WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
				WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
						WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
						WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
				PixelFormat.TRANSLUCENT);
		params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
		params.horizontalMargin = 0.03f;
		params.y = dp(36);
		params.alpha = OPACITY;
		params.setTitle("Claude status");
		lines.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		windows.addView(lines, params);
		panel = lines;
		return lines;
	}

	// The line as shown: its kind's icon, then the text coloured.
	@NonNull
	private static CharSequence styled(@NonNull String text, @Nullable String kind, boolean done) {
		var out = new SpannableStringBuilder();
		String icon = icon(kind, done);
		out.append(icon).append("  ");
		int start = out.length();
		// `code`: the backticks dropped, the rest kept to colour.
		Matcher code = CODE.matcher(text);
		int last = 0;
		while (code.find()) {
			out.append(text, last, code.start());
			int from = out.length();
			out.append(code.group(1));
			out.setSpan(new ForegroundColorSpan(Color.rgb(110, 220, 255)), from, out.length(),
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			out.setSpan(new TypefaceSpan("monospace"), from, out.length(),
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			last = code.end();
		}
		out.append(text, last, text.length());
		String plain = out.toString();
		colour(out, plain, start, NUMBER, Color.rgb(255, 214, 102), false);
		colour(out, plain, start, GOOD, Color.rgb(120, 230, 140), true);
		colour(out, plain, start, BAD, Color.rgb(255, 110, 110), true);
		Integer tint = tint(kind, done);
		if (tint != null) {
			out.setSpan(new ForegroundColorSpan(tint), 0, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		if (done) {
			out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			out.setSpan(new RelativeSizeSpan(1.25f), 0, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		return out;
	}

	// Colours pattern's matches from `start` on - but not inside `code`,
	// already cyan.
	private static void colour(@NonNull SpannableStringBuilder out, @NonNull String plain,
			int start, @NonNull Pattern pattern, int colour, boolean bold) {
		Matcher m = pattern.matcher(plain);
		while (m.find()) {
			if (m.start() < start || out.getSpans(m.start(), m.end(), TypefaceSpan.class).length > 0) {
				continue;
			}
			out.setSpan(new ForegroundColorSpan(colour), m.start(), m.end(),
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			if (bold) {
				out.setSpan(new StyleSpan(Typeface.BOLD), m.start(), m.end(),
						Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			}
		}
	}

	// A kind's icon: a few named ones, or the kind itself (an emoji).
	@NonNull
	private static String icon(@Nullable String kind, boolean done) {
		if (kind == null || kind.isEmpty()) {
			return done ? "😊" : "🔧";
		}
		return switch (kind) {
			case "info" -> "💬";
			case "step" -> "🔧";
			case "look" -> "👀";
			case "tap" -> "👆";
			case "type" -> "⌨️";
			case "wait" -> "⏳";
			case "build" -> "🛠️";
			case "install" -> "📲";
			case "load" -> "📥";
			case "think" -> "🤔";
			case "good" -> "✅";
			case "bad" -> "❌";
			case "warn" -> "⚠️";
			case "happy" -> "😊";
			case "sad" -> "😢";
			case "party" -> "🎉";
			default -> kind;
		};
	}

	@Nullable
	private static Integer tint(@Nullable String kind, boolean done) {
		if (kind == null) {
			return null;
		}
		return switch (kind) {
			case "good", "happy", "party" -> Color.rgb(120, 230, 140);
			case "bad", "sad" -> Color.rgb(255, 110, 110);
			case "warn" -> Color.rgb(255, 190, 90);
			default -> null;
		};
	}

	private int dp(float value) {
		return Math.round(value * context.getResources().getDisplayMetrics().density);
	}
}
