package enk.danwills.android.enkishadereditor.pack;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// The shader packs on offer: every preview file (as ShaderPackWriter saves
// next to a pack, <pack id>-preview.json) bundled in the app's
// assets/packs/ folder - so a pack can be shown, blurb and thumbnails and
// all, before it's bought and delivered. Publishing a pack adds its store
// ids to the preview: "productId" (what's bought) and "assetPack" (how
// it's delivered).
public final class ShaderPackCatalog {
	public record Entry(
			@NonNull String id,
			@NonNull String title,
			@Nullable String description,
			@Nullable String author,
			int version,
			@NonNull List<String> shaderNames,
			// PNG thumbnails, in shader order (where there is one).
			@NonNull List<byte[]> thumbnails,
			@Nullable String productId,
			@Nullable String assetPack) {
	}

	private static final String TAG = "ShaderPackCatalog";
	private static final String DIRECTORY = "packs";

	private ShaderPackCatalog() {
	}

	// Blocking (reads assets) - call off the UI thread.
	@NonNull
	public static List<Entry> load(@NonNull Context context) {
		var entries = new ArrayList<Entry>();
		String[] files;
		try {
			files = context.getAssets().list(DIRECTORY);
		} catch (IOException e) {
			return entries;
		}
		if (files == null) {
			return entries;
		}
		for (String file : files) {
			if (!file.endsWith(".json")) {
				continue;
			}
			try (InputStream in = context.getAssets().open(DIRECTORY + "/" + file)) {
				entries.add(parse(new JSONObject(readAll(in))));
			} catch (IOException | JSONException | IllegalArgumentException e) {
				Log.w(TAG, "Skipping unreadable pack preview " + file, e);
			}
		}
		Collections.sort(entries, (a, b) -> a.title().compareToIgnoreCase(b.title()));
		return entries;
	}

	@NonNull
	static Entry parse(@NonNull JSONObject json) throws JSONException {
		var names = new ArrayList<String>();
		var thumbnails = new ArrayList<byte[]>();
		var shaders = json.optJSONArray("shaders");
		if (shaders != null) {
			for (int i = 0; i < shaders.length(); ++i) {
				var shader = shaders.getJSONObject(i);
				names.add(shader.optString("name"));
				String thumb = shader.optString("thumb", "");
				if (!thumb.isEmpty()) {
					thumbnails.add(Base64.decode(thumb, Base64.DEFAULT));
				}
			}
		}
		return new Entry(
				json.getString("id"),
				json.getString("title"),
				optString(json, "description"),
				optString(json, "author"),
				json.optInt("version", 1),
				names,
				thumbnails,
				optString(json, "productId"),
				optString(json, "assetPack"));
	}

	@Nullable
	private static String optString(@NonNull JSONObject json, @NonNull String key) {
		return json.isNull(key) ? null : json.optString(key, null);
	}

	@NonNull
	private static String readAll(@NonNull InputStream in) throws IOException {
		var out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int len;
		while ((len = in.read(buffer)) != -1) {
			out.write(buffer, 0, len);
		}
		return out.toString(StandardCharsets.UTF_8.name());
	}
}
