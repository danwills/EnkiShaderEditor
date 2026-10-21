package enk.danwills.android.enkishadereditor.widget;

import android.app.Activity;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.activity.ShowActivity;
import enk.danwills.android.enkishadereditor.database.DataRecords;
import enk.danwills.android.enkishadereditor.database.DataSource;
import enk.danwills.android.enkishadereditor.database.Database;
import enk.danwills.android.enkishadereditor.pack.ShaderPackCatalog;
import enk.danwills.android.enkishadereditor.pack.ShaderPackDelivery;
import enk.danwills.android.enkishadereditor.pack.ShaderPackStore;

// Get Shader Pack: the packs on offer (see ShaderPackCatalog) with their
// blurbs and thumbnails, what's installed already, buying one through a
// ShaderPackStore and having it delivered by a ShaderPackDelivery - plus
// installing a pack file from anywhere on the device.
public final class GetShaderPackDialog {
	private static final int THUMBNAILS_SHOWN = 8;
	private static final int BLURB_LINES = 3;

	private GetShaderPackDialog() {
	}

	// Installs a pack file a delivery fetched. (Not java.util.function's
	// Consumer, which needs API 24.)
	public interface PackFileInstaller {
		void install(@NonNull File packFile);
	}

	// installFromFile: lets the user pick a pack file to install.
	// installDelivered: installs a pack file a delivery fetched.
	public static void show(@NonNull Activity activity, @NonNull ShaderPackStore store,
			@NonNull ShaderPackDelivery delivery, @NonNull Runnable installFromFile,
			@NonNull PackFileInstaller installDelivered) {
		DataSource dataSource = Database.getInstance(activity).getDataSource();
		new Thread(() -> {
			List<ShaderPackCatalog.Entry> catalog = ShaderPackCatalog.load(activity);
			List<DataRecords.ShaderPack> installed = dataSource.pack.getPacks();
			var installedThumbs = new HashMap<String, List<byte[]>>();
			var installedCounts = new HashMap<String, Integer>();
			for (var pack : installed) {
				installedThumbs.put(pack.packId(),
						dataSource.pack.getThumbnails(pack.packId(), THUMBNAILS_SHOWN));
				installedCounts.put(pack.packId(), dataSource.pack.countShaders(pack.packId()));
			}
			activity.runOnUiThread(() -> store.queryStatus(catalog, statuses -> {
				if (!activity.isFinishing() && !activity.isDestroyed()) {
					showDialog(activity, store, delivery, installFromFile, installDelivered,
							catalog, statuses, installed, installedThumbs, installedCounts);
				}
			}));
		}).start();
	}

	private static void showDialog(@NonNull Activity activity, @NonNull ShaderPackStore store,
			@NonNull ShaderPackDelivery delivery, @NonNull Runnable installFromFile,
			@NonNull PackFileInstaller installDelivered,
			@NonNull List<ShaderPackCatalog.Entry> catalog,
			@NonNull Map<String, ShaderPackStore.Status> statuses,
			@NonNull List<DataRecords.ShaderPack> installed,
			@NonNull Map<String, List<byte[]>> installedThumbs,
			@NonNull Map<String, Integer> installedCounts) {
		var installedById = new HashMap<String, DataRecords.ShaderPack>();
		for (var pack : installed) {
			installedById.put(pack.packId(), pack);
		}

		var list = new LinearLayout(activity);
		list.setOrientation(LinearLayout.VERTICAL);
		int padding = dp(activity, 20);
		list.setPadding(padding, dp(activity, 8), padding, 0);
		var scroll = new ScrollView(activity);
		scroll.addView(list);

		AlertDialog[] dialog = new AlertDialog[1];
		for (var entry : catalog) {
			var pack = installedById.remove(entry.id());
			var status = statuses.get(entry.id());
			String statusText;
			if (pack != null) {
				statusText = activity.getString(R.string.pack_status_installed, pack.version());
			} else if (status != null && status.owned()) {
				statusText = activity.getString(R.string.pack_status_purchased);
			} else if (status != null && status.price() != null) {
				statusText = status.price();
			} else {
				statusText = activity.getString(R.string.pack_status_available);
			}
			Runnable get = pack != null ? null : () -> store.purchase(activity, entry,
					(success, message) -> {
						if (!success) {
							explain(activity, message);
							return;
						}
						delivery.fetch(entry, (file, deliveryMessage) -> {
							if (file == null) {
								explain(activity, deliveryMessage);
								return;
							}
							if (dialog[0] != null) {
								dialog[0].dismiss();
							}
							installDelivered.install(file);
						});
					});
			addPack(list, entry.title(), entry.author(), entry.shaderNames().size(),
					entry.description(), entry.thumbnails(), statusText, get,
					playShow(activity, pack, dialog));
		}

		// Packs installed from files (or no longer on offer).
		if (!installedById.isEmpty()) {
			addHeading(list, activity.getString(R.string.pack_installed_from_files));
			for (var pack : installedById.values()) {
				Integer count = installedCounts.get(pack.packId());
				List<byte[]> thumbs = installedThumbs.get(pack.packId());
				addPack(list, pack.title(), pack.author(), count != null ? count : 0,
						pack.description(), thumbs != null ? thumbs : List.of(),
						activity.getString(R.string.pack_status_installed, pack.version()),
						null, playShow(activity, pack, dialog));
			}
		}
		if (catalog.isEmpty() && installed.isEmpty()) {
			var empty = new TextView(activity);
			empty.setText(R.string.no_shader_packs);
			list.addView(empty);
		}

		var fromFile = new MaterialButton(activity, null,
				com.google.android.material.R.attr.materialButtonOutlinedStyle);
		fromFile.setText(R.string.install_pack_from_file);
		fromFile.setOnClickListener(v -> {
			if (dialog[0] != null) {
				dialog[0].dismiss();
			}
			installFromFile.run();
		});
		var fromFileLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		fromFileLp.topMargin = dp(activity, 8);
		list.addView(fromFile, fromFileLp);

		dialog[0] = new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.get_shader_pack)
				.setView(scroll)
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	private static void addHeading(@NonNull LinearLayout list, @NonNull String text) {
		var heading = new TextView(list.getContext());
		heading.setText(text);
		heading.setTypeface(Typeface.DEFAULT_BOLD);
		heading.setPadding(0, dp(list.getContext(), 16), 0, dp(list.getContext(), 4));
		list.addView(heading);
	}

	// One pack: title, "by author - N shaders", status, a blurb that
	// expands when tapped, a strip of thumbnails, and (if it can be got)
	// a Get button.
	private static void addPack(@NonNull LinearLayout list, @NonNull String title,
			@Nullable String author, int shaderCount, @Nullable String blurb,
			@NonNull List<byte[]> thumbnails, @NonNull String status, @Nullable Runnable get,
			@Nullable Runnable play) {
		var context = list.getContext();
		var card = new LinearLayout(context);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setPadding(0, dp(context, 12), 0, dp(context, 12));

		var titleView = new TextView(context);
		titleView.setText(title);
		titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
		titleView.setTypeface(Typeface.DEFAULT_BOLD);
		card.addView(titleView);

		var details = new StringBuilder();
		if (author != null && !author.isEmpty()) {
			details.append(context.getString(R.string.pack_by_author, author)).append(" · ");
		}
		details.append(context.getResources().getQuantityString(R.plurals.pack_shader_count,
				shaderCount, shaderCount));
		details.append(" · ").append(status);
		var detailsView = new TextView(context);
		detailsView.setText(details);
		detailsView.setAlpha(0.7f);
		card.addView(detailsView);

		if (blurb != null && !blurb.isEmpty()) {
			var blurbView = new TextView(context);
			blurbView.setText(blurb);
			blurbView.setMaxLines(BLURB_LINES);
			blurbView.setEllipsize(TextUtils.TruncateAt.END);
			blurbView.setPadding(0, dp(context, 6), 0, 0);
			blurbView.setOnClickListener(v -> blurbView.setMaxLines(
					blurbView.getMaxLines() == BLURB_LINES ? Integer.MAX_VALUE : BLURB_LINES));
			card.addView(blurbView);
		}

		if (!thumbnails.isEmpty()) {
			var strip = new LinearLayout(context);
			strip.setOrientation(LinearLayout.HORIZONTAL);
			int size = dp(context, 72);
			for (int i = 0; i < thumbnails.size() && i < THUMBNAILS_SHOWN; ++i) {
				byte[] png = thumbnails.get(i);
				var bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
				if (bitmap == null) {
					continue;
				}
				var image = new ImageView(context);
				image.setImageBitmap(bitmap);
				image.setScaleType(ImageView.ScaleType.CENTER_CROP);
				var lp = new LinearLayout.LayoutParams(size, size);
				lp.setMarginEnd(dp(context, 6));
				strip.addView(image, lp);
			}
			var stripScroll = new HorizontalScrollView(context);
			stripScroll.setHorizontalScrollBarEnabled(false);
			stripScroll.setPadding(0, dp(context, 8), 0, 0);
			stripScroll.addView(strip);
			card.addView(stripScroll);
		}

		if (get != null) {
			var button = new MaterialButton(context);
			button.setText(R.string.pack_get);
			button.setOnClickListener(v -> get.run());
			var lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			lp.topMargin = dp(context, 8);
			card.addView(button, lp);
		}
		if (play != null) {
			var button = new MaterialButton(context);
			button.setText(R.string.play_show);
			button.setIconResource(R.drawable.ic_play_arrow);
			button.setOnClickListener(v -> play.run());
			var lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			lp.topMargin = dp(context, 8);
			card.addView(button, lp);
		}
		list.addView(card);
	}

	// An installed presentation pack's Play Show - see ShowActivity.
	@Nullable
	private static Runnable playShow(@NonNull Activity activity,
			@Nullable DataRecords.ShaderPack pack, @NonNull AlertDialog[] dialog) {
		if (pack == null || !pack.isShow()) {
			return null;
		}
		return () -> {
			if (dialog[0] != null) {
				dialog[0].dismiss();
			}
			ShowActivity.start(activity, pack.packId());
		};
	}

	// A dialog rather than a toast - these explain why something didn't
	// happen, often in more than the two lines a toast may show.
	private static void explain(@NonNull Activity activity, @Nullable String message) {
		if (message != null) {
			new MaterialAlertDialogBuilder(activity)
					.setMessage(message)
					.setPositiveButton(android.R.string.ok, null)
					.show();
		}
	}

	private static int dp(@NonNull Context context, int dp) {
		return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
				context.getResources().getDisplayMetrics()));
	}
}
