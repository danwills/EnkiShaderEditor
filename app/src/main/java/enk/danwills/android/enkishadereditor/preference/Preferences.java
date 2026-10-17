package enk.danwills.android.enkishadereditor.preference;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.hardware.SensorManager;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.preference.PreferenceManager;

import java.util.HashMap;
import java.util.Map;

import enk.danwills.android.enkishadereditor.R;

public class Preferences {
	public static final String WALLPAPER_SHADER = "shader";
	public static final String SAVE_BATTERY = "save_battery";
	public static final String SORT_BY_LAST_MODIFICATION = "sort_by_last_modification";
	public static final String RUN_MODE = "run_mode";
	public static final String UPDATE_DELAY = "update_delay";
	public static final String SENSOR_DELAY = "sensor_delay";
	public static final String USE_MICROPHONE_AUDIO = "use_microphone_audio";
	public static final String USE_SYSTEM_AUDIO = "use_system_audio";
	public static final String AUDIO_SPECTRUM_SHADERTOY_COMPATIBLE =
			"audio_spectrum_shadertoy_compatible";
	public static final String TEXT_SIZE = "text_size";
	public static final String FONT = "font";
	public static final String USE_LIGATURES = "use_ligatures";
	public static final String TAB_WIDTH = "tab_width";
	public static final String SHOW_INSERT_TAB = "show_insert_tab";
	public static final String USE_TAB_FOR_INDENT = "use_tab_for_indent";
	public static final String EXPORT_TABS = "export_tabs";
	public static final String SAVE_ON_RUN = "save_on_run";
	public static final String DEFAULT_NEW_SHADER = "default_new_shader";
	public static final String DISABLE_HIGHLIGHTING = "disable_highlighting";
	public static final String AUTO_SAVE = "auto_save";
	public static final String IMPORT_FROM_DIRECTORY = "import_from_directory";
	public static final String EXPORT_TO_DIRECTORY = "export_to_directory";
	public static final String IMPORT_DATABASE = "import_database";
	public static final String EXPORT_DATABASE = "export_database";
	public static final String COMPILE_ALL_SHADERS = "compile_all_shaders";
	public static final String SHOW_LINE_NUMBERS = "show_line_numbers";
	public static final String SHOW_EXTRA_KEYS = "show_extra_keys";
	public static final String AUTO_HIDE_EXTRA_KEYS = "auto_hide_extra_keys";
	public static final String HIDE_NATIVE_SUGGESTIONS = "hide_native_suggestions";
	public static final String LAST_OPENED_SHADER = "last_opened_shader";
	public static final String PENDING_CRASH_SHADER = "pending_crash_shader";
	public static final String ICON_SIZE = "icon_size";
	public static final String WARN_SLOW_COMPILE = "warn_slow_compile";
	public static final String CAPTURE_THREAD_PRIORITY = "capture_thread_priority";

	private static final String WALLPAPER_RUNTIME = "wallpaper_runtime_";

	private static final int RUN_AUTO = 1;
	private static final int RUN_MANUALLY = 2;
	private static final int RUN_MANUALLY_EXTRA = 3;
	private static final int RUN_MANUALLY_EXTRA_NEW = 4;
	private static final int RUN_PAUSE_WHILE_EDITING = 5;

	// Button-size/icon-glyph-size dp pairs for each ICON_SIZE_* level -
	// the button is always exactly double the glyph, consistent with the
	// original 48dp button / 24dp icon ("Normal") proportions.
	private static final int ICON_SIZE_VERY_SMALL = 1;
	private static final int ICON_SIZE_SMALL = 2;
	private static final int ICON_SIZE_NORMAL = 3;
	private static final int ICON_SIZE_LARGE = 4;

	// Thread priority used by the frame-sequence capture thread (see
	// MainActivity.runFrameSequenceCapture()) - NORMAL leaves other apps/
	// the live renderer unaffected (the default for any new Thread),
	// HIGH trades that courtesy away so an offline/backgrounded sequence
	// render finishes faster. Public (unlike the other *_* level
	// constants above) because HighResCaptureService's notification
	// action buttons need to name these same two levels when changing
	// this preference live, from outside Preferences itself.
	public static final int CAPTURE_PRIORITY_NORMAL = 1;
	public static final int CAPTURE_PRIORITY_HIGH = 2;
	private static final Map<String, Integer> fontNameToResId = new HashMap<>();

	static {
		fontNameToResId.put("fira_code", R.font.fira_code);
		fontNameToResId.put("ibm_plex_mono", R.font.ibm_plex_mono);
		fontNameToResId.put("jetbrains_mono", R.font.jetbrains_mono);
		fontNameToResId.put("roboto_mono", R.font.roboto_mono);
		fontNameToResId.put("source_code_pro", R.font.source_code_pro);
	}

	private SharedPreferences preferences;
	private long wallpaperShaderId = 1;
	private boolean saveBattery = true;
	private boolean sortByLastModification = true;
	private int runMode = RUN_AUTO;
	private int updateDelay = 1000;
	private int sensorDelay = SensorManager.SENSOR_DELAY_NORMAL;
	private boolean useMicrophoneAudio = true;
	private boolean useSystemAudio = false;
	private boolean audioSpectrumShaderToyCompatible = false;
	private int textSize = 12;
	private @NonNull Typeface font = Typeface.MONOSPACE;
	private boolean useLigatures = false;
	private int tabWidth = 4;
	private boolean exportTabs = false;
	private boolean showInsertTab = true;
	private boolean useTabForIndent = true;
	private boolean saveOnRun = true;
	private boolean batteryLow = false;
	private boolean powerConnected = false;
	private int systemBarColor;
	private long defaultNewShaderId = 0;
	private long lastOpenedShaderId = 0;
	private boolean disableHighlighting = false;
	private boolean autoSave = true;
	private boolean warnSlowCompile = true;
	private boolean showLineNumbers = true;
	private boolean showExtraKeys = true;
	private boolean autoHideExtraKeys = true;
	private boolean hideNativeSuggestions = true;
	private String defaultFont;
	private long pendingCrashShaderId = 0;
	private int iconSize = ICON_SIZE_NORMAL;
	private int captureThreadPriority = CAPTURE_PRIORITY_NORMAL;

	public void init(Context context) {
		systemBarColor = ContextCompat.getColor(
				context,
				R.color.primary_translucent);

		PreferenceManager.setDefaultValues(
				context,
				R.xml.preferences,
				false);

		preferences = PreferenceManager.getDefaultSharedPreferences(
				context);

		defaultFont = context.getString(R.string.default_font_value);

		update(context);
	}

	public SharedPreferences getSharedPreferences() {
		return preferences;
	}

	public void update(Context context) {
		wallpaperShaderId = parseLong(
				preferences.getString(WALLPAPER_SHADER, null),
				wallpaperShaderId);
		saveBattery = preferences.getBoolean(
				SAVE_BATTERY,
				saveBattery);
		sortByLastModification = preferences.getBoolean(
				SORT_BY_LAST_MODIFICATION,
				sortByLastModification);
		runMode = parseInt(
				preferences.getString(RUN_MODE, null),
				runMode);
		updateDelay = parseInt(
				preferences.getString(UPDATE_DELAY, null),
				updateDelay);
		sensorDelay = parseSensorDelay(
				preferences.getString(SENSOR_DELAY, null),
				sensorDelay);
		useMicrophoneAudio = preferences.getBoolean(
				USE_MICROPHONE_AUDIO,
				useMicrophoneAudio);
		useSystemAudio = preferences.getBoolean(
				USE_SYSTEM_AUDIO,
				useSystemAudio);
		audioSpectrumShaderToyCompatible = preferences.getBoolean(
				AUDIO_SPECTRUM_SHADERTOY_COMPATIBLE,
				audioSpectrumShaderToyCompatible);
		textSize = parseInt(
				preferences.getString(TEXT_SIZE, null),
				textSize);
		font = loadFont(context, preferences.getString(FONT, defaultFont));
		useLigatures = preferences.getBoolean(
				USE_LIGATURES,
				useLigatures);
		tabWidth = parseInt(
				preferences.getString(TAB_WIDTH, null),
				tabWidth);
		exportTabs = preferences.getBoolean(
				EXPORT_TABS,
				exportTabs);
		showInsertTab = preferences.getBoolean(
				SHOW_INSERT_TAB,
				showInsertTab);
		useTabForIndent = preferences.getBoolean(
				USE_TAB_FOR_INDENT,
				useTabForIndent);
		saveOnRun = preferences.getBoolean(
				SAVE_ON_RUN,
				saveOnRun);
		disableHighlighting = preferences.getBoolean(
				DISABLE_HIGHLIGHTING,
				disableHighlighting);
		autoSave = preferences.getBoolean(
				AUTO_SAVE,
				autoSave);
		warnSlowCompile = preferences.getBoolean(
				WARN_SLOW_COMPILE,
				warnSlowCompile);
		defaultNewShaderId = parseLong(
				preferences.getString(DEFAULT_NEW_SHADER, null),
				defaultNewShaderId);
		showLineNumbers = preferences.getBoolean(
				SHOW_LINE_NUMBERS,
				showLineNumbers);
		showExtraKeys = preferences.getBoolean(
				SHOW_EXTRA_KEYS,
				showExtraKeys);
		autoHideExtraKeys = preferences.getBoolean(
				AUTO_HIDE_EXTRA_KEYS,
				autoHideExtraKeys);
		hideNativeSuggestions = preferences.getBoolean(
				HIDE_NATIVE_SUGGESTIONS,
				hideNativeSuggestions);
		lastOpenedShaderId = preferences.getLong(
				LAST_OPENED_SHADER,
				lastOpenedShaderId);
		pendingCrashShaderId = preferences.getLong(
				PENDING_CRASH_SHADER,
				pendingCrashShaderId);
		iconSize = parseInt(
				preferences.getString(ICON_SIZE, null),
				iconSize);
		captureThreadPriority = parseInt(
				preferences.getString(CAPTURE_THREAD_PRIORITY, null),
				captureThreadPriority);
	}

	public boolean autoHideExtraKeys() {
		return autoHideExtraKeys;
	}

	public boolean hideNativeSuggestions() {
		return hideNativeSuggestions;
	}

	public long getPendingCrashShaderId() {
		return pendingCrashShaderId;
	}

	public void setPendingCrashShaderId(long shaderId) {
		pendingCrashShaderId = shaderId;
		preferences.edit()
				.putLong(PENDING_CRASH_SHADER, shaderId)
				.apply();
	}

	public int getIconButtonSizeDp() {
		return getIconGlyphSizeDp() * 2;
	}

	public int getIconGlyphSizeDp() {
		return switch (iconSize) {
			case ICON_SIZE_VERY_SMALL -> 16;
			case ICON_SIZE_SMALL -> 20;
			case ICON_SIZE_LARGE -> 28;
			default -> 24;
		};
	}

	public int getCaptureThreadPriority() {
		return switch (captureThreadPriority) {
			case CAPTURE_PRIORITY_HIGH -> Process.THREAD_PRIORITY_MORE_FAVORABLE;
			default -> Process.THREAD_PRIORITY_DEFAULT;
		};
	}

	// The raw CAPTURE_PRIORITY_* level (as opposed to
	// getCaptureThreadPriority()'s translated Process.THREAD_PRIORITY_*
	// value) - for UI that needs to know/display which of the two levels
	// is currently selected, e.g. HighResCaptureService's notification.
	public int getCaptureThreadPriorityLevel() {
		return captureThreadPriority;
	}

	// Lets HighResCaptureService's notification action buttons change
	// this preference directly, from outside the normal Settings screen -
	// same persisted key as the ListPreference there, so either one
	// picking a level is immediately reflected in the other next time
	// it's read/shown. Ignored if level isn't one of the two known
	// constants.
	public void setCaptureThreadPriorityLevel(int level) {
		if (level != CAPTURE_PRIORITY_NORMAL && level != CAPTURE_PRIORITY_HIGH) {
			return;
		}
		captureThreadPriority = level;
		preferences.edit()
				.putString(CAPTURE_THREAD_PRIORITY, String.valueOf(level))
				.apply();
	}

	private @NonNull Typeface loadFont(
			@NonNull Context context,
			@Nullable String fontName) {
		Typeface typeface = tryLoadFont(context, fontName);
		if (typeface != null) {
			return typeface;
		}

		// Reset to default font if the stored value is no longer available.
		if (preferences != null && defaultFont != null &&
				fontName != null && !fontName.equals(defaultFont)) {
			preferences.edit().putString(FONT, defaultFont).apply();
			Typeface fallback = tryLoadFont(context, defaultFont);
			if (fallback != null) {
				return fallback;
			}
		}

		// Absolute last resort: return the platform monospace font.
		return Typeface.MONOSPACE;
	}

	@Nullable
	private Typeface tryLoadFont(@NonNull Context context, @Nullable String fontName) {
		if (fontName == null || fontName.isEmpty()) {
			return null;
		}
		if ("monospace".equals(fontName)) {
			return Typeface.MONOSPACE;
		}
		Integer resId = fontNameToResId.get(fontName);
		if (resId == null) {
			return null;
		}
		return ResourcesCompat.getFont(context, resId);
	}

	public boolean saveBattery() {
		return saveBattery;
	}

	public boolean sortByLastModification() {
		return sortByLastModification;
	}

	public boolean doesRunOnChange() {
		return runMode == RUN_AUTO;
	}

	public boolean doesRunInBackground() {
		return runMode != RUN_MANUALLY_EXTRA &&
				runMode != RUN_MANUALLY_EXTRA_NEW;
	}

	public boolean doesRunInNewTask() {
		return runMode == RUN_MANUALLY_EXTRA_NEW;
	}

	public boolean doesPauseWhileEditing() {
		return runMode == RUN_PAUSE_WHILE_EDITING;
	}

	public int getUpdateDelay() {
		return updateDelay;
	}

	public int getSensorDelay() {
		return sensorDelay;
	}

	public boolean useMicrophoneAudio() {
		return useMicrophoneAudio;
	}

	public boolean useSystemAudio() {
		return useSystemAudio;
	}

	public boolean audioSpectrumShaderToyCompatible() {
		return audioSpectrumShaderToyCompatible;
	}

	public int getTextSize() {
		return textSize;
	}

	@NonNull
	public Typeface getFont() {
		return font;
	}

	public boolean useLigatures() {
		return useLigatures;
	}

	public int getTabWidth() {
		return tabWidth;
	}

	public boolean exportTabs() {
		return exportTabs;
	}

	public boolean doesShowInsertTab() {
		return showInsertTab;
	}

	public boolean useTabForIndent() {
		return useTabForIndent;
	}

	public boolean doesSaveOnRun() {
		return saveOnRun;
	}

	public long getWallpaperRuntimeNanos(long shaderId) {
		return preferences.getLong(WALLPAPER_RUNTIME + shaderId, 0);
	}

	public void setWallpaperRuntimeNanos(long shaderId, long nanos) {
		preferences.edit()
				.putLong(WALLPAPER_RUNTIME + shaderId, nanos)
				.apply();
	}

	public long getWallpaperShader() {
		return wallpaperShaderId;
	}

	public void setWallpaperShader(long id) {
		wallpaperShaderId = id;
		putString(WALLPAPER_SHADER, String.valueOf(wallpaperShaderId));
	}

	public long getDefaultNewShader() {
		return defaultNewShaderId;
	}

	public void setDefaultNewShader(long id) {
		defaultNewShaderId = id;
		putString(DEFAULT_NEW_SHADER, String.valueOf(defaultNewShaderId));
	}

	public long getLastOpenedShader() {
		return lastOpenedShaderId;
	}

	public void setLastOpenedShader(long id) {
		lastOpenedShaderId = id;
		preferences.edit().putLong(LAST_OPENED_SHADER, id).apply();
	}


	public boolean isBatteryLow() {
		return batteryLow;
	}

	public void setBatteryLow(boolean isLow) {
		batteryLow = isLow;
	}

	public boolean isPowerConnected() {
		return powerConnected;
	}

	public void setPowerConnected(boolean isConnected) {
		powerConnected = isConnected;
	}

	public boolean disableHighlighting() {
		return disableHighlighting;
	}

	public boolean autoSave() {
		return autoSave;
	}

	public boolean warnSlowCompile() {
		return warnSlowCompile;
	}

	public int getSystemBarColor() {
		return systemBarColor;
	}

	public boolean showLineNumbers() {
		return showLineNumbers;
	}

	public boolean showExtraKeys() {
		return showExtraKeys;
	}

	public boolean toggleShowExtraKeys() {
		showExtraKeys = !showExtraKeys;
		preferences.edit().putBoolean(SHOW_EXTRA_KEYS, this.showExtraKeys).apply();
		return showExtraKeys;
	}

	private void putString(String key, String value) {
		SharedPreferences.Editor editor = preferences.edit();
		editor.putString(key, value);
		editor.apply();
	}

	private static int parseInt(String s, int preset) {
		try {
			if (s != null && !s.isEmpty()) {
				return Integer.parseInt(s);
			}
		} catch (NumberFormatException e) {
			// Use preset.
		}

		return preset;
	}

	private static long parseLong(String s, long preset) {
		try {
			if (s != null && !s.isEmpty()) {
				return Long.parseLong(s);
			}
		} catch (NumberFormatException e) {
			// Use preset.
		}

		return preset;
	}

	private static int parseSensorDelay(String s, int preset) {
		if (s == null) {
			return preset;
		}

		return switch (s) {
			case "Fastest" -> SensorManager.SENSOR_DELAY_FASTEST;
			case "Game" -> SensorManager.SENSOR_DELAY_GAME;
			case "Normal" -> SensorManager.SENSOR_DELAY_NORMAL;
			case "UI" -> SensorManager.SENSOR_DELAY_UI;
			default -> preset;
		};

	}
}
