package enk.danwills.android.enkishadereditor.activity.managers;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.app.ShaderEditorApp;

public class MainMenuManager {
	public interface EditorActions {
		void onUndo();

		void onRedo();

		boolean canUndo();

		boolean canRedo();

		void onFindInShader();

		void onHideToolbar();
	}

	public interface ShaderActions {
		void onAddShader();

		void onPasteAsNewShader();

		void onPasteOverShader();

		void onSaveShader();

		void onDuplicateShader();

		void onDeleteShader();

		void onShareShader();

		void onCopyShader();

		void onCopyDefines();

		void onUpdateWallpaper();

		void onToggleExtraKeys();

		long getSelectedShaderId();
	}

	public interface NavigationActions {
		void onAddUniform();

		void onLoadSample();

		void onShowSettings();

		void onShowFaq();

		void onShowShaderStats();

		void onPlayRecentRecordings();

		void onShowHotkeys();

		void onAdjustDefines();
	}

	@NonNull
	private final PopupWindow popupWindow;
	@NonNull
	private final PopupWindow moreOptionsPopupWindow;
	@NonNull
	private final EditorActions editorActions;
	@NonNull
	private final ShaderActions shaderActions;
	@NonNull
	private final Activity activity;
	@Nullable
	private View anchorView;

	public MainMenuManager(@NonNull Activity activity,
			@NonNull EditorActions editorActions,
			@NonNull ShaderActions shaderActions,
			@NonNull NavigationActions navigationActions) {
		this.activity = activity;
		this.editorActions = editorActions;
		this.shaderActions = shaderActions;
		ViewGroup root = activity.findViewById(android.R.id.content);
		View view = activity.getLayoutInflater().inflate(
				R.layout.main_menu, root, false);
		view.setClipToOutline(true);

		popupWindow = new PopupWindow(view,
				android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
				android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
				true);
		popupWindow.setOutsideTouchable(true);
		popupWindow.setFocusable(true);
		popupWindow.setElevation(16f);
		popupWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);

		// Wire up listeners
		setClickListener(R.id.undo, () -> {
			editorActions.onUndo();
			updateUndoRedoMenu(popupWindow.getContentView());
		}, false);
		setClickListener(R.id.redo, () -> {
			editorActions.onRedo();
			updateUndoRedoMenu(popupWindow.getContentView());
		}, false);
		setClickListener(R.id.add_shader, shaderActions::onAddShader);
		setClickListener(R.id.paste_as_new_shader, shaderActions::onPasteAsNewShader);
		setClickListener(R.id.paste_over_shader, shaderActions::onPasteOverShader);
		setClickListener(R.id.save_shader, shaderActions::onSaveShader);
		setClickListener(R.id.duplicate_shader, shaderActions::onDuplicateShader);
		setClickListener(R.id.delete_shader, shaderActions::onDeleteShader);
		setClickListener(R.id.share_shader, shaderActions::onShareShader);
		setClickListener(R.id.copy_shader, shaderActions::onCopyShader);
		setClickListener(R.id.copy_defines, shaderActions::onCopyDefines);
		setClickListener(R.id.settings, navigationActions::onShowSettings);
		setClickListener(R.id.find_in_shader, editorActions::onFindInShader);
		setClickListener(R.id.more_options, this::showMoreOptions, false);

		// The less frequently used items live in a secondary popup,
		// opened from "More options" above, to keep this one short.
		View moreOptionsView = activity.getLayoutInflater().inflate(
				R.layout.more_options_menu, root, false);
		moreOptionsView.setClipToOutline(true);

		moreOptionsPopupWindow = new PopupWindow(moreOptionsView,
				android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
				android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
				true);
		moreOptionsPopupWindow.setOutsideTouchable(true);
		moreOptionsPopupWindow.setFocusable(true);
		moreOptionsPopupWindow.setElevation(16f);
		moreOptionsPopupWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);

		setMoreOptionsClickListener(R.id.update_wallpaper, shaderActions::onUpdateWallpaper);
		setMoreOptionsClickListener(R.id.add_uniform, navigationActions::onAddUniform);
		setMoreOptionsClickListener(R.id.load_sample, navigationActions::onLoadSample);
		setMoreOptionsClickListener(R.id.shader_stats, navigationActions::onShowShaderStats);
		setMoreOptionsClickListener(R.id.play_recent_recordings, navigationActions::onPlayRecentRecordings);
		setMoreOptionsClickListener(R.id.hide_toolbar, editorActions::onHideToolbar);
		setMoreOptionsClickListener(R.id.hotkeys, navigationActions::onShowHotkeys);
		setMoreOptionsClickListener(R.id.adjust_defines, navigationActions::onAdjustDefines);
		setMoreOptionsClickListener(R.id.faq, navigationActions::onShowFaq);

		CompoundButton extraKeysToggle = moreOptionsView.findViewById(
				R.id.show_extra_keys_box);
		extraKeysToggle.setOnClickListener(v -> {
			shaderActions.onToggleExtraKeys();
			updateExtraKeysToggle(extraKeysToggle,
					ShaderEditorApp.preferences.showExtraKeys());
		});
	}

	public void show(@NonNull View anchor) {
		anchorView = anchor;
		if (popupWindow.isShowing()) {
			popupWindow.dismiss();
			return;
		}
		prepareMenu();
		resizeAndShow(popupWindow, anchor);
	}

	private void showMoreOptions() {
		popupWindow.dismiss();
		if (moreOptionsPopupWindow.isShowing() || anchorView == null) {
			moreOptionsPopupWindow.dismiss();
			return;
		}
		prepareMoreOptionsMenu();
		resizeAndShow(moreOptionsPopupWindow, anchorView);
	}

	private void updateUndoRedoMenu(@NonNull View menuView) {
		View undo = menuView.findViewById(R.id.undo);
		undo.setEnabled(editorActions.canUndo());
		View redo = menuView.findViewById(R.id.redo);
		redo.setEnabled(editorActions.canRedo());
	}

	private void prepareMenu() {
		View menuView = popupWindow.getContentView();
		updateUndoRedoMenu(menuView);
	}

	private void prepareMoreOptionsMenu() {
		View menuView = moreOptionsPopupWindow.getContentView();
		long selectedShaderId = shaderActions.getSelectedShaderId();
		((Button) menuView.findViewById(R.id.update_wallpaper)).setText(
				ShaderEditorApp.preferences.getWallpaperShader() == selectedShaderId
						? R.string.update_wallpaper
						: R.string.set_as_wallpaper);

		updateExtraKeysToggle(menuView.findViewById(R.id.show_extra_keys_box),
				ShaderEditorApp.preferences.showExtraKeys());
	}

	public void updateExtraKeysToggle(@NonNull CompoundButton extraKeysToggle,
			boolean visible) {
		extraKeysToggle.setChecked(visible);
		Drawable drawable = ContextCompat.getDrawable(activity, visible
				? R.drawable.ic_bottom_panel_close
				: R.drawable.ic_bottom_panel_open);
		Drawable[] drawables = extraKeysToggle.getCompoundDrawablesRelative();
		extraKeysToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
				drawable, drawables[1], drawables[2], drawables[3]);
	}

	private void resizeAndShow(@NonNull PopupWindow popup, @NonNull View anchor) {
		Rect screenRect = new Rect();
		anchor.getWindowVisibleDisplayFrame(screenRect);
		int screenHeight = screenRect.height();

		DisplayMetrics displayMetrics =
				activity.getResources().getDisplayMetrics();
		int padding = (int) (100 * displayMetrics.density);
		int maxHeight = screenHeight - padding;

		View contentView = popup.getContentView();
		contentView.measure(View.MeasureSpec.UNSPECIFIED,
				View.MeasureSpec.UNSPECIFIED);
		popup.setHeight(Math.min(maxHeight,
				contentView.getMeasuredHeight()));
		popup.showAsDropDown(anchor,
				anchor.getWidth() - contentView.getMeasuredWidth(),
				-anchor.getHeight());
	}

	private void setClickListener(int id, @NonNull Runnable action,
			boolean dismiss) {
		popupWindow.getContentView().findViewById(id).setOnClickListener(v -> {
			// Dismissed before running the action (not after) - an action
			// that shows its own dialog while this popup's window is
			// still up can otherwise race the popup's dismissal and never
			// actually become visible.
			if (dismiss) {
				popupWindow.dismiss();
			}
			action.run();
		});
	}

	private void setClickListener(int id, @NonNull Runnable action) {
		setClickListener(id, action, true);
	}

	private void setMoreOptionsClickListener(int id, @NonNull Runnable action) {
		moreOptionsPopupWindow.getContentView().findViewById(id).setOnClickListener(v -> {
			// See setClickListener() above - dismiss first, then act, so
			// an action that shows its own dialog doesn't race this
			// popup's dismissal.
			moreOptionsPopupWindow.dismiss();
			action.run();
		});
	}
}
