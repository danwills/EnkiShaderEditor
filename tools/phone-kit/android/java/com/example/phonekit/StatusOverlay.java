package com.example.phonekit;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
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
// using the phone, and the run's last MAX_LINES lines stay, the newest at
// the bottom, so it's always clear what's going on (Dan's wish: never an
// empty panel while Claude's driving). hide() takes it out of sight for a
// screenshot, lines kept; the run's end (done) takes it away at once -
// the notification then has the log's tail. Touches go straight through
// it (and it's at the opacity Android lets them through another app's
// overlay at). Each line starts with its kind's icon, and
// is coloured: `code` in cyan, numbers in yellow, words that sound good
// in green and bad in red - and ends, dimmed, with how long its step's
// likely to take, if Claude said (`?` when it can't tell) - and, once
// the next line comes, how long it actually took (amendLast()).
// Beside the pill, in a small window of their own (the one part that
// takes touches): Pause/Continue and Stop, which Claude reads back
// between steps (files/claude-control: "paused" or "stopped"), and - only
// while Claude's asking whether something looks right (askRating()) -
// thumbs up and down (files/claude-rate-<id>: "up" or "down").
@RequiresApi(Build.VERSION_CODES.O)
final class StatusOverlay {
	private static final int MAX_LINES = 8;
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
	@Nullable
	private LinearLayout panel;
	// "Claude is using your phone", from a run's first line to its last.
	@Nullable
	private TextView badge;
	// The buttons' window - see the class comment.
	@Nullable
	private LinearLayout controls;
	@Nullable
	private ImageView pauseButton;
	@Nullable
	private LinearLayout thumbs;
	private boolean paused;
	private static final String CONTROL = "claude-control";
	// The pill's verb, one picked a run - Dan's idea, for a bit of fun.
	private static final String[] VERBS = {"Orbiting", "Exploring", "Tinkering with",
			"Prodding", "Steering", "Charting", "Conducting", "Wrangling", "Roaming",
			"Puzzling over", "Tuning", "Piloting"};

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

	// Debug-only tooling, in English like the rest of it.
	@SuppressLint("SetTextI18n")
	void add(@NonNull String text, @Nullable String kind, @Nullable String eta) {
		add(text, kind, eta, true);
	}

	// own: one of Claude's (status.sh's), which amendLast() times - not
	// one the buttons add themselves.
	@SuppressLint("SetTextI18n")
	private void add(@NonNull String text, @Nullable String kind, @Nullable String eta,
			boolean own) {
		LinearLayout lines = panel();
		lines.setVisibility(View.VISIBLE);
		if (badge == null) {
			badge = new TextView(context);
			badge.setText("🤖 Claude is " + VERBS[(int) (System.nanoTime() / 1000 % VERBS.length)] +
					" your phone");
			badge.setTextColor(Color.rgb(255, 200, 120));
			badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
			badge.setPadding(0, 0, 0, dp(2));
			// As tall as the buttons beside it, so they don't cover a line.
			badge.setMinHeight(dp(42));
			badge.setGravity(Gravity.CENTER_VERTICAL);
			lines.addView(badge, 0);
		}
		while (lines.getChildCount() - (badge != null ? 1 : 0) >= MAX_LINES) {
			lines.removeViewAt(badge != null ? 1 : 0);
		}
		controls();
		var line = new TextView(context);
		line.setTextColor(Color.rgb(235, 235, 240));
		line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
		line.setLineSpacing(0, 1.1f);
		line.setPadding(0, dp(3), 0, dp(3));
		line.setText(styled(text, kind, eta, null));
		if (own) {
			line.setTag(new String[]{text, kind, eta});
		}
		lines.addView(line);
	}

	// Claude's newest line, with how long its step actually took.
	void amendLast(@NonNull String took) {
		if (panel == null) {
			return;
		}
		for (int i = panel.getChildCount() - 1; i >= 0; --i) {
			if (panel.getChildAt(i) instanceof TextView line && line.getTag() instanceof String[] parts) {
				line.setText(styled(parts[0], parts[1], parts[2], took));
				return;
			}
		}
	}

	// Out of sight for a moment (a clean screenshot): the next line
	// brings it back, with the lines it had.
	void hide() {
		if (panel != null) {
			panel.setVisibility(View.GONE);
		}
		if (controls != null) {
			controls.setVisibility(View.GONE);
		}
	}

	// Claude asking whether what it's looking at is right: thumbs up and
	// down until one's tapped (or clearRating()).
	void askRating(@NonNull String id) {
		LinearLayout bar = controls();
		if (thumbs != null) {
			bar.removeView(thumbs);
		}
		thumbs = new LinearLayout(context);
		thumbs.addView(button("👍", v -> rate(id, "up")));
		thumbs.addView(button("👎", v -> rate(id, "down")));
		bar.addView(thumbs, 0);
	}

	void clearRating() {
		if (controls != null && thumbs != null) {
			controls.removeView(thumbs);
		}
		thumbs = null;
	}

	// Debug-only tooling, in English like the rest of it.
	@SuppressLint("SetTextI18n")
	private void rate(@NonNull String id, @NonNull String answer) {
		write("claude-rate-" + id, answer);
		clearRating();
		add(answer.equals("up") ? "Thumbs up - thanks!" : "Thumbs down - Claude will look again",
				answer.equals("up") ? "👍" : "👎", null, false);
	}

	// Debug-only tooling, in English like the rest of it.
	@SuppressLint("SetTextI18n")
	private void togglePause() {
		paused = !paused;
		write(CONTROL, paused ? "paused" : "running");
		if (pauseButton != null) {
			pauseButton.setImageDrawable(new Glyph(paused ? Glyph.PLAY : Glyph.PAUSE));
		}
		add(paused ? "Pause asked - Claude will wait after this step (▶ to carry on)"
				: "Carrying on", paused ? "⏸" : "▶", null, false);
	}

	private void stop() {
		paused = false;
		write(CONTROL, "stopped");
		add("Stop asked - Claude will finish this step, put things back and stop", "⏹", null, false);
	}

	private void write(@NonNull String name, @NonNull String content) {
		try (var out = new java.io.FileOutputStream(new java.io.File(context.getFilesDir(), name))) {
			out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		} catch (java.io.IOException e) {
			android.util.Log.w("StatusOverlay", "Couldn't write " + name, e);
		}
	}

	// Pause, play and stop, drawn plain white - the emoji were hard to
	// make out at this size over a busy screen.
	private static final class Glyph extends Drawable {
		static final int PAUSE = 0;
		static final int PLAY = 1;
		static final int STOP = 2;
		private final int kind;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

		Glyph(int kind) {
			this.kind = kind;
			paint.setColor(Color.WHITE);
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			var b = getBounds();
			float size = Math.min(b.width(), b.height());
			float cx = b.exactCenterX();
			float cy = b.exactCenterY();
			float h = size * 0.5f;
			switch (kind) {
				case PAUSE -> {
					float bar = h * 0.34f;
					float gap = h * 0.3f;
					canvas.drawRoundRect(cx - gap / 2 - bar, cy - h / 2, cx - gap / 2, cy + h / 2,
							bar * 0.2f, bar * 0.2f, paint);
					canvas.drawRoundRect(cx + gap / 2, cy - h / 2, cx + gap / 2 + bar, cy + h / 2,
							bar * 0.2f, bar * 0.2f, paint);
				}
				case PLAY -> {
					var path = new Path();
					path.moveTo(cx - h * 0.38f, cy - h / 2);
					path.lineTo(cx + h * 0.5f, cy);
					path.lineTo(cx - h * 0.38f, cy + h / 2);
					path.close();
					canvas.drawPath(path, paint);
				}
				default -> {
					float half = h * 0.42f;
					canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half,
							half * 0.2f, half * 0.2f, paint);
				}
			}
		}

		@Override
		public void setAlpha(int alpha) {
			paint.setAlpha(alpha);
		}

		@Override
		public void setColorFilter(@Nullable ColorFilter filter) {
			paint.setColorFilter(filter);
		}

		@Override
		@SuppressWarnings("deprecation")
		public int getOpacity() {
			return PixelFormat.TRANSLUCENT;
		}
	}

	@NonNull
	private ImageView iconButton(@NonNull Drawable icon, @NonNull View.OnClickListener onClick) {
		var button = new ImageView(context);
		button.setImageDrawable(icon);
		button.setMinimumWidth(dp(44));
		button.setMinimumHeight(dp(40));
		style(button, onClick);
		return button;
	}

	@NonNull
	private TextView button(@NonNull String label, @NonNull View.OnClickListener onClick) {
		var button = new TextView(context);
		button.setText(label);
		button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
		button.setGravity(Gravity.CENTER);
		button.setMinWidth(dp(44));
		button.setMinHeight(dp(40));
		style(button, onClick);
		return button;
	}

	private void style(@NonNull View button, @NonNull View.OnClickListener onClick) {
		button.setPadding(dp(6), dp(2), dp(6), dp(2));
		var background = new GradientDrawable();
		background.setColor(Color.argb(230, 40, 44, 60));
		background.setCornerRadius(dp(12));
		background.setStroke(dp(1), Color.rgb(217, 119, 87));
		button.setBackground(background);
		var params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		params.setMarginStart(dp(6));
		button.setLayoutParams(params);
		button.setOnClickListener(onClick);
	}

	// The buttons' window, at the panel's top right, made with a run's
	// first line.
	@NonNull
	private LinearLayout controls() {
		if (controls != null) {
			controls.setVisibility(View.VISIBLE);
			return controls;
		}
		var bar = new LinearLayout(context);
		bar.setOrientation(LinearLayout.HORIZONTAL);
		pauseButton = iconButton(new Glyph(paused ? Glyph.PLAY : Glyph.PAUSE), v -> togglePause());
		bar.addView(pauseButton);
		bar.addView(iconButton(new Glyph(Glyph.STOP), v -> stop()));
		var params = new WindowManager.LayoutParams(
				WindowManager.LayoutParams.WRAP_CONTENT,
				WindowManager.LayoutParams.WRAP_CONTENT,
				WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
				WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
						WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
				PixelFormat.TRANSLUCENT);
		params.gravity = Gravity.TOP | Gravity.END;
		params.x = dp(18);
		params.y = dp(36) + dp(4);
		params.setTitle("Claude controls");
		windows.addView(bar, params);
		controls = bar;
		return bar;
	}

	// The run's over: gone at once, lines and all.
	void clear() {
		if (panel != null) {
			windows.removeView(panel);
			panel = null;
		}
		if (controls != null) {
			windows.removeView(controls);
			controls = null;
		}
		badge = null;
		pauseButton = null;
		thumbs = null;
		paused = false;
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
	private static CharSequence styled(@NonNull String text, @Nullable String kind,
			@Nullable String eta, @Nullable String took) {
		var out = new SpannableStringBuilder();
		String icon = icon(kind);
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
		Integer tint = tint(kind);
		if (tint != null) {
			out.setSpan(new ForegroundColorSpan(tint), 0, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		boolean estimated = eta != null && !eta.isEmpty();
		if (estimated || took != null) {
			int from = out.length();
			out.append("  ⏱ ");
			if (estimated) {
				out.append(eta.equals("?") ? "no estimate" : eta);
			}
			out.setSpan(new ForegroundColorSpan(Color.rgb(150, 155, 170)), from, out.length(),
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			if (took != null) {
				int at = out.length();
				out.append(estimated ? " → took " : "took ").append(took);
				out.setSpan(new ForegroundColorSpan(Color.rgb(190, 175, 230)), at, out.length(),
						Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			}
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
	private static String icon(@Nullable String kind) {
		if (kind == null || kind.isEmpty()) {
			return "🔧";
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
	private static Integer tint(@Nullable String kind) {
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
