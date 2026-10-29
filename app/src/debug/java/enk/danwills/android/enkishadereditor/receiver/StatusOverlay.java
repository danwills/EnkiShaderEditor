package enk.danwills.android.enkishadereditor.receiver;

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
import android.os.Handler;
import android.os.Looper;
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
// takes touches): 💬 to tell Claude something (SayActivity), and
// Pause/Continue and Stop, which every status.sh command reads back
// (files/claude-control: "paused" or "stopped"), ✕ to close the panel
// there and then (Dan got stuck with it up when the connection dropped;
// Claude's next line brings it back), and - only while Claude's
// asking whether something looks right (askRating()) - thumbs up and down
// (files/claude-rate-<id>: "up" or "down") and Clarify, to say more instead.
// While Dan's typing to Claude (SayActivity in front) the panel's out of
// the box's way (typing()), and if he leaves the box with words in it the
// 💬 button says so (✏️) - they're kept, for when he opens it again.
// When nothing new has come for IDLE_MS, a dim last line ticks, saying
// what's going on (Claude thinking, waiting, or waiting for Dan's
// answer) and for how long, so a quiet panel never looks stuck.
// If Claude goes quiet for QUIET_MS (the connection dropped, say) the
// panel takes itself away, so it's never left over everything: an
// overlay can stop taps getting through to some of Settings' screens.
@RequiresApi(Build.VERSION_CODES.O)
final class StatusOverlay {
	private static final int MAX_LINES = 8;
	// The most a non-touchable overlay can show and still let touches
	// through to another app (Android 12's touch occlusion rules).
	private static final float OPACITY = 0.8f;
	// How long the panel stays with no new line before it goes by itself
	// (not while paused, or while Dan's answering or typing): short, as
	// it's in the way (5 minutes was too long to be stuck with it); a
	// later line brings it back.
	private static final long QUIET_MS = 2 * 60 * 1000;
	// How long the panel's still before its last line ticks (see above).
	private static final long IDLE_MS = 6000;
	private static final String QUIET_CHANNEL = "claude_status";
	private static final int QUIET_NOTIFICATION = 7302;

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
	@Nullable
	private TextView sayButton;
	// The ticking last line (see the class comment), and since when.
	@Nullable
	private TextView idle;
	private long lastLine;
	@Nullable
	private String lastKind;
	private boolean paused;
	private boolean typing;
	private boolean hidden;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable tick = this::tick;
	private final Runnable quiet = () -> {
		if (paused || typing || thumbs != null) {
			waitQuietly();
		} else {
			clear();
			notifyQuiet();
		}
	};
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
		hidden = false;
		lines.setVisibility(typing ? View.GONE : View.VISIBLE);
		if (idle != null) {
			lines.removeView(idle);
			idle = null;
		}
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
		controls().setVisibility(typing ? View.GONE : View.VISIBLE);
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
		if (own) {
			lastLine = android.os.SystemClock.uptimeMillis();
			lastKind = kind;
		}
		handler.removeCallbacks(tick);
		handler.postDelayed(tick, IDLE_MS);
		waitQuietly();
	}

	// The ticking last line: what's going on, and for how long.
	// Debug-only tooling, in English like the rest of it.
	@SuppressLint("SetTextI18n")
	private void tick() {
		if (panel == null) {
			return;
		}
		long since = (android.os.SystemClock.uptimeMillis() - lastLine) / 1000;
		String what;
		if (paused) {
			what = "⏸ Paused - ▶ to carry on";
		} else if (thumbs != null) {
			what = "🤔 Waiting for your answer";
		} else if ("wait".equals(lastKind) || "⏳".equals(lastKind)) {
			what = "⏳ Waiting";
		} else {
			what = "💭 Claude's thinking - may take a moment";
		}
		if (idle == null) {
			idle = new TextView(context);
			idle.setTextColor(Color.rgb(150, 155, 170));
			idle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
			idle.setPadding(0, dp(3), 0, dp(1));
			panel.addView(idle);
		}
		idle.setText(what + "… " + (since < 60 ? since + " s" : since / 60 + " min " + since % 60 + " s"));
		handler.postDelayed(tick, 1000);
	}

	// Dan typing to Claude (SayActivity in front): the panel out of the
	// box's way till he's done. kept: he left with words in the box.
	// Debug-only tooling, in English like the rest of it.
	@SuppressLint("SetTextI18n")
	void typing(boolean typing, boolean kept) {
		this.typing = typing;
		if (sayButton != null) {
			sayButton.setText(kept ? "💬✏️" : "💬");
		}
		if (panel != null && !hidden) {
			panel.setVisibility(typing ? View.GONE : View.VISIBLE);
		}
		if (controls != null && !hidden) {
			controls.setVisibility(typing ? View.GONE : View.VISIBLE);
		}		if (kept && !typing && panel != null) {
			add("Your words are kept - tap 💬✏️ to finish them", "✏️", null, false);
		}
	}

	// The panel goes after QUIET_MS more with no new line.
	private void waitQuietly() {
		handler.removeCallbacks(quiet);
		handler.postDelayed(quiet, QUIET_MS);
		context.getSystemService(android.app.NotificationManager.class).cancel(QUIET_NOTIFICATION);
	}

	// Why the panel went by itself, so it doesn't just vanish.
	private void notifyQuiet() {
		var manager = context.getSystemService(android.app.NotificationManager.class);
		if (manager.getNotificationChannel(QUIET_CHANNEL) == null) {
			manager.createNotificationChannel(new android.app.NotificationChannel(QUIET_CHANNEL,
					"Claude's status panel", android.app.NotificationManager.IMPORTANCE_DEFAULT));
		}
		var notification = new androidx.core.app.NotificationCompat.Builder(context, QUIET_CHANNEL)
				.setSmallIcon(android.R.drawable.ic_dialog_info)
				.setContentTitle("🤖 Claude's gone quiet")
				.setContentText("Nothing new for " + QUIET_MS / 60000 + " minutes (the connection " +
						"may have dropped), so the panel's down.")
				.setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(
						"Nothing new for " + QUIET_MS / 60000 + " minutes (the connection may have " +
								"dropped), so the status panel's down. \"Appear on top\" may " +
								"still be allowed until Claude finishes - turn it off in Settings " +
								"if you like."))
				.setAutoCancel(true)
				.build();
		try {
			manager.notify(QUIET_NOTIFICATION, notification);
		} catch (SecurityException e) {
			android.util.Log.w("StatusOverlay", "Can't post the notification", e);
		}
	}

	// Claude's newest line, with how long its step actually took.
	void amendLast(@NonNull String took) {
		if (panel == null) {
			return;
		}
		for (int i = panel.getChildCount() - 1; i >= 0; --i) {
			View child = panel.getChildAt(i);
			if (child instanceof TextView && child.getTag() instanceof String[]) {
				String[] parts = (String[]) child.getTag();
				((TextView) child).setText(styled(parts[0], parts[1], parts[2], took));
				return;
			}
		}
	}

	// Out of sight for a moment (a clean screenshot): the next line
	// brings it back, with the lines it had.
	void hide() {
		hidden = true;
		if (panel != null) {
			panel.setVisibility(View.GONE);
		}
		if (controls != null) {
			controls.setVisibility(View.GONE);
		}
	}

	// Claude asking whether what it's looking at is right: thumbs up and
	// down until one's tapped (or clearRating()).
	void askRating(@NonNull String id, @Nullable String question) {
		LinearLayout bar = controls();
		if (thumbs != null) {
			bar.removeView(thumbs);
		}
		thumbs = new LinearLayout(context);
		thumbs.addView(button("👍", v -> rate(id, "up")));
		thumbs.addView(button("👎", v -> rate(id, "down")));
		thumbs.addView(button("Clarify", v -> {
			clearRating();
			say(id, question);
		}));
		// A gap before Claude's own buttons, so the thumbs don't read as one row with them.
		var gap = new View(context);
		gap.setLayoutParams(new LinearLayout.LayoutParams(dp(14), 1));
		thumbs.addView(gap);
		bar.addView(thumbs, 0);
	}

	// SayActivity: a message (rate null), or a clarification of question `rate`.
	private void say(@Nullable String rate, @Nullable String question) {
		var intent = new android.content.Intent(context, SayActivity.class)
				.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
		if (rate != null) {
			intent.putExtra("rate", rate).putExtra("question", question);
		}
		try {
			context.startActivity(intent);
		} catch (RuntimeException e) {
			android.util.Log.w("StatusOverlay", "Couldn't open SayActivity", e);
		}
	}

	// What Dan sent from SayActivity, on the panel so he sees it went.
	// Debug-only tooling, in English like the rest of it.
	@SuppressLint("SetTextI18n")
	void said(@NonNull String text, boolean clarifying) {
		add((clarifying ? "You clarified: " : "You said: ") + text, "💬", null, false);
		// Claude only reads it at its next status.sh command, which can be
		// a while if it's mid-build or thinking.
		add("Claude reads this at its next step, so a reply may take a little while", "⏳", null, false);
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
		add(paused ? "Pause asked - Claude will wait at its next step (▶ to carry on)"
				: "Carrying on", paused ? "⏸" : "▶", null, false);
	}

	private void stop() {
		paused = false;
		write(CONTROL, "stopped");
		add("Stop asked - Claude will stop at its next step and put things back", "⏹", null, false);
	}

	@Nullable
	private String read(@NonNull String name) {
		var file = new java.io.File(context.getFilesDir(), name);
		try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(
				new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
			String line = in.readLine();
			return line != null ? line.trim() : null;
		} catch (java.io.IOException e) {
			return null;
		}
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
		static final int CLOSE = 3;
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
				case PAUSE: {
					float bar = h * 0.34f;
					float gap = h * 0.3f;
					canvas.drawRoundRect(cx - gap / 2 - bar, cy - h / 2, cx - gap / 2, cy + h / 2,
							bar * 0.2f, bar * 0.2f, paint);
					canvas.drawRoundRect(cx + gap / 2, cy - h / 2, cx + gap / 2 + bar, cy + h / 2,
							bar * 0.2f, bar * 0.2f, paint);
					break;
				}
				case PLAY: {
					var path = new Path();
					path.moveTo(cx - h * 0.38f, cy - h / 2);
					path.lineTo(cx + h * 0.5f, cy);
					path.lineTo(cx - h * 0.38f, cy + h / 2);
					path.close();
					canvas.drawPath(path, paint);
					break;
				}
				case CLOSE: {
					float half = h * 0.4f;
					paint.setStyle(Paint.Style.STROKE);
					paint.setStrokeWidth(h * 0.16f);
					paint.setStrokeCap(Paint.Cap.ROUND);
					canvas.drawLine(cx - half, cy - half, cx + half, cy + half, paint);
					canvas.drawLine(cx - half, cy + half, cx + half, cy - half, paint);
					paint.setStyle(Paint.Style.FILL);
					break;
				}
				default: {
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
			if (!typing) {
				controls.setVisibility(View.VISIBLE);
			}
			return controls;
		}
		var bar = new LinearLayout(context);
		bar.setOrientation(LinearLayout.HORIZONTAL);
		// As files/claude-control has it: the app may have restarted (an
		// install) while paused, and Claude's still waiting for Continue.
		paused = "paused".equals(read(CONTROL));
		sayButton = button("💬", v -> say(null, null));
		bar.addView(sayButton);
		pauseButton = iconButton(new Glyph(paused ? Glyph.PLAY : Glyph.PAUSE), v -> togglePause());
		bar.addView(pauseButton);
		bar.addView(iconButton(new Glyph(Glyph.STOP), v -> stop()));
		bar.addView(iconButton(new Glyph(Glyph.CLOSE), v -> clear()));
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
		handler.removeCallbacks(quiet);
		handler.removeCallbacks(tick);
		idle = null;
		sayButton = null;
		hidden = false;
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
		switch (kind) {
			case "info": return "💬";
			case "step": return "🔧";
			case "look": return "👀";
			case "tap": return "👆";
			case "type": return "⌨️";
			case "wait": return "⏳";
			case "build": return "🛠️";
			case "install": return "📲";
			case "load": return "📥";
			case "think": return "🤔";
			case "good": return "✅";
			case "bad": return "❌";
			case "warn": return "⚠️";
			case "happy": return "😊";
			case "sad": return "😢";
			case "party": return "🎉";
			default: return kind;
		}
	}

	@Nullable
	private static Integer tint(@Nullable String kind) {
		if (kind == null) {
			return null;
		}
		switch (kind) {
			case "good": case "happy": case "party": return Color.rgb(120, 230, 140);
			case "bad": case "sad": return Color.rgb(255, 110, 110);
			case "warn": return Color.rgb(255, 190, 90);
			default: return null;
		}
	}

	private int dp(float value) {
		return Math.round(value * context.getResources().getDisplayMetrics().density);
	}
}
