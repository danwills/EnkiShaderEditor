package enk.danwills.android.enkishadereditor.restarea;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.database.DataRecords;
import enk.danwills.android.enkishadereditor.database.DataSource;
import enk.danwills.android.enkishadereditor.database.Database;
import enk.danwills.android.enkishadereditor.pack.PackShow;
import enk.danwills.android.enkishadereditor.project.MultipassSource;
import enk.danwills.android.enkishadereditor.resource.Resources;

// The sets Ultra Rest Area ships with. Each is installed into the app's
// own database as a shader pack with a show (see PackShow), so
// ShowActivity plays it like any presentation pack - and later sets, sold
// or downloaded, are just more packs. For now they're made from Enki's
// samples; curated sets replace them as the library grows.
final class BundledSets {
	private static final String TAG = "BundledSets";
	// Bump to reinstall every set (their shaders are replaced).
	private static final int VERSION = 1;
	private static final String AUTHOR = "Ultra Rest Area";

	record Shader(@RawRes int source, @NonNull String name, float quality) {
	}

	record Set(@NonNull String packId, @NonNull String title, int seconds,
			@Nullable String module, @NonNull List<Shader> shaders) {
	}

	static final List<Set> SETS = List.of(
			new Set("ura-drift", "Drift", 20, null, List.of(
					new Shader(R.raw.sample_swirl, "Swirl", 0.5f),
					new Shader(R.raw.sample_circles, "Circles", 0.5f),
					new Shader(R.raw.sample_electric_fade, "Electric Fade", 0.125f))),
			new Set("ura-life", "Life", 25, null, List.of(
					new Shader(R.raw.sample_cloudy_conway, "Cloudy Conway", 0.125f),
					new Shader(R.raw.sample_game_of_life, "Game of Life", 0.125f))),
			new Set("ura-demoscene", "Demoscene", 30, "enki_demo", List.of(
					new Shader(R.raw.sample_demo_writer, "Demo Writer", 0.5f),
					new Shader(R.raw.sample_module_viewer, "Module Viewer", 0.5f))));

	private BundledSets() {
	}

	// Installs any set that isn't in the database at this VERSION. Off the
	// UI thread.
	static void install(@NonNull Context context) {
		DataSource data = Database.getInstance(context).getDataSource();
		for (Set set : SETS) {
			DataRecords.ShaderPack installed = data.pack.getPack(set.packId());
			if (installed != null && installed.version() >= VERSION) {
				continue;
			}
			try {
				install(context, data, set);
			} catch (IOException e) {
				Log.w(TAG, "Can't install " + set.title(), e);
			}
		}
	}

	private static void install(@NonNull Context context, @NonNull DataSource data,
			@NonNull Set set) throws IOException {
		for (long id : data.pack.getShaderIds(set.packId())) {
			data.shader.removeShader(id);
		}
		if (set.module() != null && data.module.getModuleData(set.module()) == null) {
			data.module.insertModule(set.module(), readBytes(context, R.raw.enki_demo_mod));
		}
		for (Shader shader : set.shaders()) {
			String text = Resources.loadRawResource(context, shader.source());
			// Samples are multipass text - see MultipassSource.
			var parsed = MultipassSource.parse(text);
			long id = data.shader.insertShader(parsed != null ? parsed.imageSource() : text,
					shader.name(), null, shader.quality());
			if (id < 1) {
				throw new IOException("Can't insert " + shader.name());
			}
			if (parsed != null) {
				data.buffer.setBufferSources(id, parsed.bufferSources());
				data.text.setTexts(id, parsed.texts());
			}
			data.shader.setPackId(id, set.packId());
		}
		data.pack.putPack(new DataRecords.ShaderPack(set.packId(), set.title(), null, AUTHOR,
				VERSION, null, null,
				PackShow.simple(set.seconds(), set.module(), null).toJson()), "bundled");
	}

	@NonNull
	private static byte[] readBytes(@NonNull Context context, @RawRes int id) throws IOException {
		try (var in = context.getResources().openRawResource(id)) {
			var out = new ByteArrayOutputStream();
			byte[] chunk = new byte[16384];
			for (int n; (n = in.read(chunk)) > 0; ) {
				out.write(chunk, 0, n);
			}
			return out.toByteArray();
		}
	}
}
