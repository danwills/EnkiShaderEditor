package enk.danwills.android.enkishadereditor.io;

import android.content.Context;
import android.database.SQLException;
import android.net.Uri;
import android.util.Base64;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.database.DataRecords;
import enk.danwills.android.enkishadereditor.database.DataSource;
import enk.danwills.android.enkishadereditor.database.Database;
import enk.danwills.android.enkishadereditor.database.DatabaseContract;
import enk.danwills.android.enkishadereditor.opengl.ModuleDirective;
import enk.danwills.android.enkishadereditor.opengl.TextureReferences;
import enk.danwills.android.enkishadereditor.pack.PackShow;

// Bundles chosen shaders into a shader pack - see PackDao.writePackFile()
// for what's in one - saved to Downloads as <pack id>-v<version>.sqlite,
// for ShaderPackInstaller to install anywhere. Also saves
// <pack id>-preview.json alongside: the pack's details plus its shaders'
// names and thumbnails, small enough to ship inside the app so a store
// can show a pack before it's bought (see ShaderPackCatalog). Blocking -
// call off the UI thread.
public final class ShaderPackWriter {
	// installed: Save & Install put this new version in the library.
	public record Result(boolean success, @NonNull String message, boolean installed) {
	}

	private static final String SQLITE_MIME_TYPE = "application/vnd.sqlite3";
	private static final String JSON_MIME_TYPE = "application/json";

	private ShaderPackWriter() {
	}

	// An installed pack shader's name without its "<Pack Title>: ", as
	// the pack file has it (see PackDao.writePackFile()).
	@NonNull
	private static String withoutPackTitle(@NonNull String name, @NonNull List<String> prefixes) {
		for (String prefix : prefixes) {
			if (name.startsWith(prefix)) {
				return name.substring(prefix.length());
			}
		}
		return name;
	}

	@NonNull
	public static Result write(@NonNull Context context, @NonNull List<Long> shaderIds,
			@NonNull DataRecords.ShaderPack info) {
		return write(context, shaderIds, info, false);
	}

	// install: Save & Install - once saved, the file just written (not
	// whatever Downloads holds) also goes into the library, in place of
	// any installed version of the pack (see ShaderPackInstaller.replace()).
	@NonNull
	public static Result write(@NonNull Context context, @NonNull List<Long> shaderIds,
			@NonNull DataRecords.ShaderPack info, boolean install) {
		DataSource dataSource = Database.getInstance(context).getDataSource();
		Set<String> textures = new TreeSet<>();
		Set<String> modules = new TreeSet<>();
		var preview = new JSONArray();
		var packTitles = new ArrayList<String>();
		for (var pack : dataSource.pack.getPacks()) {
			packTitles.add(pack.title() + DataRecords.ShaderInfo.PACK_NAME_SEPARATOR);
		}
		try {
			for (long id : shaderIds) {
				var shader = dataSource.shader.getShader(id);
				if (shader == null) {
					continue;
				}
				String[] buffers = dataSource.buffer.getBufferSources(id);
				textures.addAll(TextureReferences.find(shader.fragmentShader(), buffers));
				String[] sources = new String[buffers.length + 1];
				sources[0] = shader.fragmentShader();
				System.arraycopy(buffers, 0, sources, 1, buffers.length);
				String module = ModuleDirective.find(sources);
				if (module != null) {
					modules.add(module);
				}
				var entry = new JSONObject();
				entry.put("name", withoutPackTitle(shader.getTitle(), packTitles));
				byte[] thumb = dataSource.shader.getThumbnail(id);
				if (thumb != null && thumb.length > 0) {
					entry.put("thumb", Base64.encodeToString(thumb, Base64.NO_WRAP));
				}
				preview.put(entry);
			}

			// A show's module soundtrack comes along too.
			PackShow show = PackShow.parse(info.show());
			if (show != null && show.module() != null) {
				modules.add(show.module());
			}

			String baseName = info.packId() + "-v" + info.version();
			File file = new File(context.getCacheDir(), baseName + ".sqlite");
			dataSource.pack.writePackFile(file, shaderIds, textures, modules, info);
			String packFileName = baseName + ".sqlite";
			ShaderPackInstaller.Result installed = null;
			try {
				try (var in = new FileInputStream(file);
						OutputStream out = ExternalFile.openExternalOutputStream(context,
								packFileName, SQLITE_MIME_TYPE)) {
					byte[] buffer = new byte[8192];
					int len;
					while ((len = in.read(buffer)) != -1) {
						out.write(buffer, 0, len);
					}
				}
				if (install) {
					installed = ShaderPackInstaller.replace(context, info.packId(),
							Uri.fromFile(file));
				}
			} finally {
				//noinspection ResultOfMethodCallIgnored
				file.delete();
			}

			var json = new JSONObject();
			json.put("format", DatabaseContract.PackInfoColumns.CURRENT_FORMAT);
			json.put("id", info.packId());
			json.put("title", info.title());
			json.put("description", info.description());
			json.put("author", info.author());
			json.put("version", info.version());
			json.put("shaders", preview);
			try (OutputStream out = ExternalFile.openExternalOutputStream(context,
					info.packId() + "-preview.json", JSON_MIME_TYPE)) {
				out.write(json.toString(1).getBytes(StandardCharsets.UTF_8));
			}
			String saved = context.getString(R.string.shader_pack_saved, packFileName);
			if (installed == null) {
				return new Result(true, saved, false);
			}
			return new Result(installed.success(), saved + "\n" + installed.message(),
					installed.success());
		} catch (IOException | SQLException | JSONException e) {
			return new Result(false, context.getString(R.string.shader_pack_save_failed,
					String.valueOf(e.getMessage())), false);
		}
	}
}
