package enk.danwills.android.enkishadereditor.widget;

import android.app.Activity;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.database.DataRecords;
import enk.danwills.android.enkishadereditor.io.ShaderPackInstaller;

// Update Version (long-press a pack in the sidebar): the versions of an
// installed pack found in Downloads, newest first and chosen, to swap the
// installed one for - see ShaderPackInstaller.replace(). The buttons sit
// above the list, so they're never scrolled away.
public final class UpdatePackDialog {
	public interface Host {
		void update(@NonNull ShaderPackInstaller.Found version);

		// Any other file, through the system picker.
		void chooseFile();
	}

	private UpdatePackDialog() {
	}

	public static void show(@NonNull Activity activity, @NonNull DataRecords.ShaderPack installed,
			@NonNull List<ShaderPackInstaller.Found> versions, @NonNull Host host) {
		float density = activity.getResources().getDisplayMetrics().density;
		int pad = (int) (20 * density);

		var layout = new LinearLayout(activity);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setPadding(pad, (int) (8 * density), pad, 0);

		var buttons = new LinearLayout(activity);
		buttons.setOrientation(LinearLayout.HORIZONTAL);
		buttons.setGravity(Gravity.END);
		Button choose = textButton(activity, R.string.update_pack_choose_file);
		Button cancel = textButton(activity, android.R.string.cancel);
		var update = new MaterialButton(activity);
		update.setText(R.string.update_pack_update);
		var spacer = new View(activity);
		buttons.addView(choose);
		buttons.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
		buttons.addView(cancel);
		buttons.addView(update);
		layout.addView(buttons);

		var list = new ListView(activity);
		if (versions.isEmpty()) {
			var none = new TextView(activity);
			none.setText(R.string.update_pack_none);
			none.setPadding(0, pad / 2, 0, pad);
			layout.addView(none);
			update.setEnabled(false);
		} else {
			var labels = new ArrayList<String>();
			for (var version : versions) {
				String when = DateFormat.getMediumDateFormat(activity)
						.format(new Date(version.modifiedMillis())) + " " +
						DateFormat.getTimeFormat(activity)
								.format(new Date(version.modifiedMillis()));
				labels.add(activity.getString(version.pack().version() == installed.version()
								? R.string.update_pack_item_installed
								: R.string.update_pack_item,
						version.pack().version(), when, version.fileName()));
			}
			list.setAdapter(new ArrayAdapter<>(activity,
					android.R.layout.simple_list_item_single_choice, labels));
			list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
			list.setItemChecked(0, true);
			layout.addView(list, new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT));
		}

		AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
				.setTitle(activity.getString(R.string.update_pack_title, installed.title(),
						installed.version()))
				.setView(layout)
				.show();
		cancel.setOnClickListener(v -> dialog.dismiss());
		choose.setOnClickListener(v -> {
			dialog.dismiss();
			host.chooseFile();
		});
		update.setOnClickListener(v -> {
			int chosen = list.getCheckedItemPosition();
			if (chosen < 0 || chosen >= versions.size()) {
				return;
			}
			dialog.dismiss();
			host.update(versions.get(chosen));
		});
	}

	@NonNull
	private static Button textButton(@NonNull Activity activity, int text) {
		var button = new MaterialButton(activity, null,
				androidx.appcompat.R.attr.borderlessButtonStyle);
		button.setText(text);
		return button;
	}
}
