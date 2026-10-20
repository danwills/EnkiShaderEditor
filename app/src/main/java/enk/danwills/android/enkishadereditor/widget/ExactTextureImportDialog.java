package enk.danwills.android.enkishadereditor.widget;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.database.Database;
import enk.danwills.android.enkishadereditor.fragment.AbstractSamplerPropertiesFragment;
import enk.danwills.android.enkishadereditor.opengl.ShaderRenderer;
import enk.danwills.android.enkishadereditor.opengl.TextureParameters;

// Add Texture -> Exact Pixels: imports a PNG as a 2D texture exactly as
// it is - no crop, no resize, its own bytes stored untouched - so a
// texture holding data rather than a picture (lookup tables, a neural
// network's weights) reads back bit for bit with texelFetch(). Like any
// saved texture, it goes into shader packs with the shaders that use it.
public final class ExactTextureImportDialog {
	// Exact textures are meant for data, which is small; this keeps an
	// accidental photo from taking a huge slice of memory and the
	// database. Every GLES 3 device can sample at least 2048.
	private static final int MAX_SIZE = 4096;
	private static final int MAX_BYTES = 32 << 20;
	private static final byte[] PNG_SIGNATURE = {
			(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
	private static final Pattern NAME_PATTERN = Pattern.compile(
			"^" + AbstractSamplerPropertiesFragment.TEXTURE_NAME_PATTERN + "$");

	private ExactTextureImportDialog() {
	}

	public interface OnImported {
		// statement: the uniform declaration to add to the shader.
		void onImported(@NonNull String statement);
	}

	public static void show(@NonNull Activity activity, @NonNull Uri uri,
			@NonNull OnImported onImported) {
		Context appContext = activity.getApplicationContext();
		new Thread(() -> {
			Loaded loaded = load(appContext, uri);
			activity.runOnUiThread(() -> {
				if (activity.isFinishing() || activity.isDestroyed()) {
					return;
				}
				if (loaded.error != null) {
					explain(activity, loaded.error);
					return;
				}
				showNameDialog(activity, loaded, onImported);
			});
		}).start();
	}

	private record Loaded(@Nullable byte[] png, @Nullable Bitmap bitmap,
			@Nullable String suggestedName, @Nullable String error) {
		static Loaded failed(@NonNull String error) {
			return new Loaded(null, null, null, error);
		}
	}

	@NonNull
	private static Loaded load(@NonNull Context context, @NonNull Uri uri) {
		byte[] png;
		try (InputStream in = context.getContentResolver().openInputStream(uri)) {
			if (in == null) {
				return Loaded.failed(context.getString(R.string.exact_texture_unreadable));
			}
			png = readAtMost(in, MAX_BYTES);
		} catch (IOException | SecurityException e) {
			return Loaded.failed(context.getString(R.string.exact_texture_unreadable));
		}
		if (png == null) {
			return Loaded.failed(context.getString(R.string.exact_texture_too_big_file,
					MAX_BYTES >> 20));
		}
		if (!isPng(png)) {
			return Loaded.failed(context.getString(R.string.exact_texture_not_png));
		}
		var bounds = new BitmapFactory.Options();
		bounds.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
			return Loaded.failed(context.getString(R.string.exact_texture_unreadable));
		}
		if (bounds.outWidth > MAX_SIZE || bounds.outHeight > MAX_SIZE) {
			return Loaded.failed(context.getString(R.string.exact_texture_too_big,
					bounds.outWidth, bounds.outHeight, MAX_SIZE));
		}
		// Unpremultiplied, as TextureDao reads textures back - so the
		// thumbnail is made from the same pixels the shader will see.
		var options = new BitmapFactory.Options();
		options.inPremultiplied = false;
		options.inScaled = false;
		Bitmap bitmap;
		try {
			bitmap = BitmapFactory.decodeByteArray(png, 0, png.length, options);
		} catch (OutOfMemoryError e) {
			bitmap = null;
		}
		if (bitmap == null) {
			return Loaded.failed(context.getString(R.string.exact_texture_unreadable));
		}
		return new Loaded(png, bitmap, suggestName(context, uri), null);
	}

	// null if there's more than maxBytes.
	@Nullable
	private static byte[] readAtMost(@NonNull InputStream in, int maxBytes) throws IOException {
		var out = new ByteArrayOutputStream();
		byte[] buffer = new byte[64 * 1024];
		int n;
		while ((n = in.read(buffer)) > 0) {
			if (out.size() + n > maxBytes) {
				return null;
			}
			out.write(buffer, 0, n);
		}
		return out.toByteArray();
	}

	private static boolean isPng(@NonNull byte[] data) {
		if (data.length < PNG_SIGNATURE.length) {
			return false;
		}
		for (int i = 0; i < PNG_SIGNATURE.length; ++i) {
			if (data[i] != PNG_SIGNATURE[i]) {
				return false;
			}
		}
		return true;
	}

	// The file's name, made into a GLSL identifier: "lizard-weights.png"
	// -> "lizard_weights".
	@NonNull
	private static String suggestName(@NonNull Context context, @NonNull Uri uri) {
		String name = null;
		try (Cursor cursor = context.getContentResolver().query(uri,
				new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
			if (cursor != null && cursor.moveToFirst()) {
				name = cursor.getString(0);
			}
		} catch (RuntimeException e) {
			// No display name - fall through to the default below.
		}
		if (name == null) {
			name = uri.getLastPathSegment();
		}
		if (name == null) {
			return "";
		}
		int dot = name.lastIndexOf('.');
		if (dot > 0) {
			name = name.substring(0, dot);
		}
		name = name.replaceAll("[^a-zA-Z0-9_]+", "_").replaceAll("^_+|_+$", "");
		if (!name.isEmpty() && Character.isDigit(name.charAt(0))) {
			name = "t" + name;
		}
		return name;
	}

	private static void showNameDialog(@NonNull Activity activity, @NonNull Loaded loaded,
			@NonNull OnImported onImported) {
		var layout = new LinearLayout(activity);
		layout.setOrientation(LinearLayout.VERTICAL);
		int padding = dp(activity, 20);
		layout.setPadding(padding, dp(activity, 8), padding, 0);

		var size = new TextView(activity);
		size.setText(activity.getString(R.string.exact_texture_size,
				loaded.bitmap.getWidth(), loaded.bitmap.getHeight()));
		layout.addView(size);

		var nameView = new EditText(activity);
		nameView.setHint(R.string.texture_name);
		nameView.setSingleLine(true);
		nameView.setText(loaded.suggestedName);
		nameView.setFilters(new InputFilter[]{
				(source, start, end, dest, dstart, dend) ->
						source.toString().matches("[a-zA-Z0-9_]*") ? null : ""});
		layout.addView(nameView);

		AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.add_texture_exact)
				.setView(layout)
				.setPositiveButton(R.string.save_texture, null)
				.setNegativeButton(android.R.string.cancel, null)
				.setOnDismissListener(d -> {
					if (!loaded.bitmap.isRecycled()) {
						loaded.bitmap.recycle();
					}
				})
				.show();
		// Checked here so a bad or taken name keeps the dialog open.
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			String name = nameView.getText().toString().trim();
			if (name.isEmpty()) {
				nameView.setError(activity.getString(R.string.missing_name));
				return;
			}
			if (!NAME_PATTERN.matcher(name).matches() ||
					Character.isDigit(name.charAt(0)) ||
					name.equals(ShaderRenderer.UNIFORM_BACKBUFFER)) {
				nameView.setError(activity.getString(R.string.invalid_texture_name));
				return;
			}
			Context appContext = activity.getApplicationContext();
			new Thread(() -> {
				long id = Database.getInstance(appContext).getDataSource().texture
						.insertExactTexture(name, loaded.png, loaded.bitmap);
				activity.runOnUiThread(() -> {
					if (id < 1) {
						nameView.setError(activity.getString(R.string.name_already_taken));
						return;
					}
					dialog.dismiss();
					// Data wants nearest texels and no wrapping, whatever
					// the usual texture defaults are.
					var parameters = new TextureParameters();
					parameters.set("n", "n", "c", "c");
					onImported.onImported("uniform " +
							AbstractSamplerPropertiesFragment.SAMPLER_2D + " " + name + ";" +
							parameters);
				});
			}).start();
		});
	}

	private static void explain(@NonNull Activity activity, @NonNull String message) {
		new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.add_texture_exact)
				.setMessage(message)
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	private static int dp(@NonNull Context context, int dp) {
		return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
				context.getResources().getDisplayMetrics()));
	}
}
