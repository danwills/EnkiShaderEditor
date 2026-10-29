package enk.danwills.android.enkishadereditor.activity.managers;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.activity.AddUniformActivity;
import enk.danwills.android.enkishadereditor.activity.LoadSampleActivity;
import enk.danwills.android.enkishadereditor.activity.PreviewActivity;
import enk.danwills.android.enkishadereditor.app.ShaderEditorApp;
import enk.danwills.android.enkishadereditor.database.DataSource;
import enk.danwills.android.enkishadereditor.fragment.EditorFragment;
import enk.danwills.android.enkishadereditor.opengl.ModuleDirective;
import enk.danwills.android.enkishadereditor.project.MultipassSource;
import enk.danwills.android.enkishadereditor.project.LegacyShaderProjectSource;
import enk.danwills.android.enkishadereditor.project.LooseShaderFileProjectSource;
import enk.danwills.android.enkishadereditor.project.ShaderProjectSession;

public class ShaderManager {
	public final ActivityResultLauncher<Intent> addUniformLauncher;
	public final ActivityResultLauncher<Intent> loadSampleLauncher;
	public final ActivityResultLauncher<Intent> previewShaderLauncher;

	private static final String SELECTED_SHADER_ID = "selected_shader_id";
	private static final String ACTIVE_FILE_PATH = "active_file_path";
	private static final long NO_SHADER = 0;

	private final AppCompatActivity activity;
	private final EditorFragment editorFragment;
	private final ShaderViewManager shaderViewManager;
	private final ShaderListManager shaderListManager;
	private final UIManager uiManager;
	private final DataSource dataSource;

	private long selectedShaderId = NO_SHADER;
	private float quality = 1f;
	private boolean isModified = false;
	@Nullable
	private ShaderProjectSession currentProjectSession;
	// Notified whenever applyProjectSession() below actually switches
	// what's loaded - e.g. so the caller can drop any editor-state
	// (like a Find-in-Shader ring) that's tied to the shader just left.
	@Nullable
	private Runnable onShaderSwitchedListener;

	public void setOnShaderSwitchedListener(@Nullable Runnable listener) {
		onShaderSwitchedListener = listener;
	}

	public ShaderManager(@NonNull AppCompatActivity activity,
			EditorFragment editorFragment,
			ShaderViewManager shaderViewManager,
			ShaderListManager shaderListManager,
			UIManager uiManager,
			DataSource dataSource,
			ShaderViewManager.Listener shaderViewManagerListener) {
		this.activity = activity;
		this.editorFragment = editorFragment;
		this.shaderViewManager = shaderViewManager;
		this.shaderListManager = shaderListManager;
		this.uiManager = uiManager;
		this.dataSource = dataSource;

		addUniformLauncher = activity.registerForActivityResult(
				new ActivityResultContracts.StartActivityForResult(), result -> {
					if (result.getResultCode() == Activity.RESULT_OK &&
							result.getData() != null) {
						editorFragment.addUniform(
								result.getData().getStringExtra(
										AddUniformActivity.STATEMENT));
					}
				});

		loadSampleLauncher = activity.registerForActivityResult(
				new ActivityResultContracts.StartActivityForResult(), result -> {
					if (result.getResultCode() == Activity.RESULT_OK &&
							result.getData() != null) {
						if (isModified()) {
							saveShader();
						}
						long newId = dataSource.shader.insertShaderFromResource(activity,
								Objects.requireNonNull(result.getData().getStringExtra(
										LoadSampleActivity.NAME)),
								result.getData().getIntExtra(LoadSampleActivity.RESOURCE_ID,
										R.raw.new_shader),
								result.getData().getIntExtra(LoadSampleActivity.THUMBNAIL_ID,
										R.drawable.thumbnail_new_shader),
								result.getData().getFloatExtra(LoadSampleActivity.QUALITY, 1f));
						splitSampleBuffers(newId);
						importBundledModules(newId);
						selectShader(newId);
					}
				});

		previewShaderLauncher =
				activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
					if (result.getResultCode() != Activity.RESULT_OK) {
						return;
					}
					PreviewActivity.RenderStatus status =
							PreviewActivity.renderStatus;
					if (status.getFps() > 0) {
						shaderViewManagerListener.onFramesPerSecond(
								status.getFps());
					}
					if (status.getInfoLog() != null) {
						shaderViewManagerListener.onInfoLog(status.getInfoLog());
					}
					if (getSelectedShaderId() > 0 &&
							status.getThumbnail() != null &&
							ShaderEditorApp.preferences.doesSaveOnRun()) {
						saveShader();
					}
				});
	}

	public long getSelectedShaderId() {
		return selectedShaderId;
	}

	public float getQuality() {
		return quality;
	}

	public void setQuality(float quality) {
		this.quality = quality;
		if (currentProjectSession != null) {
			currentProjectSession = currentProjectSession.withQuality(quality);
		}
		setModified(true);
	}

	public boolean isModified() {
		return isModified;
	}

	public void setModified(boolean modified) {
		this.isModified = modified;
		// The EditorFragment's modified state is managed internally by its
		// UndoRedo helper. It cannot and should not be set from the outside.
		// When a shader is loaded via `selectShader`,
		// `editorFragment.setText()` is called, which clears the history
		// and resets the modified state.
	}

	public void saveState(@NonNull Bundle outState) {
		outState.putLong(SELECTED_SHADER_ID, selectedShaderId);
		if (currentProjectSession != null) {
			outState.putString(ACTIVE_FILE_PATH, currentProjectSession.getActiveFilePath());
		}
	}

	// After the activity was recreated (e.g. the process was killed in
	// the background), only the editor's text comes back by itself - so
	// rebuild the shader's session (its buffer passes above all) from the
	// database, with the same file active as before. Without this, the
	// session fell back to a scratch one with no buffers, and the next
	// autosave deleted a multipass shader's buffers.
	public void restoreState(@NonNull Bundle savedInstanceState) {
		selectedShaderId = savedInstanceState.getLong(
				SELECTED_SHADER_ID, NO_SHADER);
		if (selectedShaderId <= 0) {
			return;
		}
		var shader = dataSource.shader.getShader(selectedShaderId);
		if (shader == null) {
			return;
		}
		ShaderProjectSession session = LegacyShaderProjectSource.load(dataSource, shader)
				.openSession();
		String activePath = savedInstanceState.getString(ACTIVE_FILE_PATH);
		if (activePath != null && session.getProject().hasFile(activePath)) {
			session = session.withActiveFile(activePath);
		}
		currentProjectSession = session;
		quality = session.getQuality();
		shaderListManager.setSelectedShaderId(selectedShaderId);
	}

	// Modules that ship with the app, for the samples that play them -
	// added to the library the first time a sample needs one.
	private static final Map<String, Integer> BUNDLED_MODULES =
			Map.of("enki_demo", R.raw.enki_demo_mod);

	private void importBundledModules(long id) {
		var shader = dataSource.shader.getShader(id);
		if (shader == null) {
			return;
		}
		String[] buffers = dataSource.buffer.getBufferSources(id);
		String[] sources = new String[buffers.length + 1];
		sources[0] = shader.fragmentShader();
		System.arraycopy(buffers, 0, sources, 1, buffers.length);
		String name = ModuleDirective.find(sources);
		Integer resource = name != null ? BUNDLED_MODULES.get(name) : null;
		if (resource == null || dataSource.module.getModuleData(name) != null) {
			return;
		}
		try (var in = activity.getResources().openRawResource(resource)) {
			var bytes = new ByteArrayOutputStream();
			byte[] chunk = new byte[16384];
			for (int n; (n = in.read(chunk)) > 0; ) {
				bytes.write(chunk, 0, n);
			}
			dataSource.module.insertModule(name, bytes.toByteArray());
		} catch (IOException e) {
			Log.w("ShaderManager", "Can't import " + name, e);
		}
	}

	// A sample with offscreen buffers is stored as one multipass text
	// (see MultipassSource) - split it into its passes once inserted.
	private void splitSampleBuffers(long id) {
		var shader = dataSource.shader.getShader(id);
		if (shader == null) {
			return;
		}
		var parsed = MultipassSource.parse(shader.fragmentShader());
		if (parsed == null) {
			return;
		}
		dataSource.shader.updateShader(id, parsed.imageSource(), null, shader.quality());
		dataSource.buffer.setBufferSources(id, parsed.bufferSources());
		dataSource.buffer.setCommonSource(id, parsed.commonSource());
		dataSource.text.setTexts(id, parsed.texts());
	}

	@NonNull
	public ShaderProjectSession getEditedProjectSession() {
		return getEditedProjectSession(editorFragment.getText());
	}

	// `editorText` is what the editor currently shows, which is the
	// *active* file's source - the entry (Image pass) file's only while
	// that's the one open, see setActiveFile().
	@NonNull
	public ShaderProjectSession getEditedProjectSession(
			@NonNull String editorText) {
		ShaderProjectSession session = getCurrentProjectSession();
		return session
				.withFileSource(session.getActiveFilePath(), editorText)
				.withQuality(quality);
	}

	// Replaces one file's source in the current session - for a file
	// that isn't the one the editor shows, whose text is the editor's.
	public void setFileSource(@NonNull String path, @NonNull String source) {
		currentProjectSession = getEditedProjectSession().withFileSource(path, source);
		setModified(true);
	}

	@NonNull
	public String getEntryFilePath() {
		return getCurrentProjectSession().getProject().getEntryFilePath();
	}

	@NonNull
	public String getActiveFilePath() {
		return getCurrentProjectSession().getActiveFilePath();
	}

	// Commits the editor's text into the active file, then makes `path`
	// the active one and returns its source for the editor to show.
	@NonNull
	public String setActiveFile(@NonNull String path) {
		currentProjectSession = getEditedProjectSession().withActiveFile(path);
		return currentProjectSession.getActiveFile().source();
	}

	// Adds/removes offscreen buffers, see ShaderProject.withBufferSources().
	// The editor's text is committed first, and if the active file was a
	// buffer that's now gone the entry file becomes active - the caller
	// should then show getActiveFile()'s source.
	@NonNull
	public ShaderProjectSession setBufferSources(@NonNull String[] sources) {
		currentProjectSession = getEditedProjectSession().withBufferSources(sources);
		setModified(true);
		return currentProjectSession;
	}

	// Adds (source) or removes (null) the Common section, as
	// setBufferSources() does buffers.
	@NonNull
	public ShaderProjectSession setCommonSource(@Nullable String source) {
		currentProjectSession = getEditedProjectSession().withCommonSource(source);
		setModified(true);
		return currentProjectSession;
	}

	// Adds/removes/renames texts, as setBufferSources() does buffers - the
	// caller first moves off a text tab that's about to go.
	@NonNull
	public ShaderProjectSession setTexts(@NonNull Map<String, String> texts) {
		currentProjectSession = getEditedProjectSession().withTexts(texts);
		setModified(true);
		return currentProjectSession;
	}

	public void selectShader(long id) {
		if (isModified()) {
			saveShader();
		}

		PreviewActivity.renderStatus.reset();
		var shader = dataSource.shader.getShader(id);
		if (shader == null) {
			applyProjectSession(
					NO_SHADER,
					createScratchProjectSession(
							activity.getString(R.string.new_shader_template),
							1f),
					activity.getString(R.string.add_shader));
			return;
		}

		ShaderProjectSession projectSession = LegacyShaderProjectSource.load(dataSource, shader)
				.openSession();
		ShaderEditorApp.preferences.setLastOpenedShader(id);
		applyProjectSession(id,
				projectSession,
				projectSession.getProject().getTitle());
	}

	public void saveShader() {
		saveShader(false);
	}

	public void saveShader(boolean force) {
		if (!force && !isModified()) {
			return;
		}

		ShaderProjectSession edited = getEditedProjectSession();
		String src = edited.getEntryPointSource();
		if (src.trim().isEmpty() && selectedShaderId <= 0) {
			return;
		}

		byte[] thumbnail = getThumbnail();
		// Only a session loaded from (or saved as) this very shader knows
		// its buffers - anything else (a scratch session standing in after
		// some state was lost) must leave them alone rather than save "no
		// buffers" over them.
		boolean ownsBuffers;
		if (selectedShaderId > 0) {
			ownsBuffers = edited.getShaderId() == selectedShaderId;
			dataSource.shader.updateShader(
					selectedShaderId, src, thumbnail, quality);
		} else {
			ownsBuffers = true;
			selectedShaderId = dataSource.shader.insertShader(
					src, null, thumbnail, quality);
			shaderListManager.setSelectedShaderId(selectedShaderId);
		}
		if (ownsBuffers) {
			dataSource.buffer.setBufferSources(selectedShaderId,
					edited.getProject().getBufferSources());
			dataSource.buffer.setCommonSource(selectedShaderId,
					edited.getProject().getCommonSource());
			dataSource.text.setTexts(selectedShaderId, edited.getProject().getTexts());
		}

		ShaderProjectSession saved = loadSavedProjectSession(selectedShaderId, src);
		currentProjectSession = saved.getProject().hasFile(edited.getActiveFilePath())
				? saved.withActiveFile(edited.getActiveFilePath())
				: saved;
		updatePendingCrashShaderId();
		setModified(false);
		shaderListManager.loadShadersAsync();
		Toast.makeText(activity, R.string.shader_saved,
				Toast.LENGTH_SHORT).show();
	}

	private byte[] getThumbnail() {
		return ShaderEditorApp.preferences.doesRunInBackground()
				? shaderViewManager.getThumbnail()
				: PreviewActivity.renderStatus.getThumbnail();
	}

	// Text that came in from outside - a .glsl/.frag/text file opened
	// with the app, or a file or plain text shared to it - plus a
	// suggested shader name (the file's name without its extension), if
	// there is one.
	public record SharedText(@NonNull String text, @Nullable String name) {
	}

	// The text an ACTION_VIEW/ACTION_SEND intent carries, or null if it
	// carries none (or isn't one). Consumes the intent's action, so the
	// same intent isn't handled twice (e.g. on recreation).
	@Nullable
	public SharedText readSharedText(@Nullable Intent intent) {
		if (intent == null || intent.getAction() == null) {
			return null;
		}
		String action = intent.getAction();
		if (!Intent.ACTION_SEND.equals(action) &&
				!Intent.ACTION_VIEW.equals(action)) {
			return null;
		}
		intent.setAction(null);

		Uri uri = Intent.ACTION_SEND.equals(action)
				? intent.getParcelableExtra(Intent.EXTRA_STREAM)
				: intent.getData();
		if (uri == null) {
			CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
			if (text == null || text.toString().isBlank()) {
				return null;
			}
			var named = MultipassSource.takeName(text.toString());
			return new SharedText(named.text(), named.name());
		}

		try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
			if (in == null) {
				return null;
			}
			byte[] buffer = new byte[4096];
			int len;
			var out = new ByteArrayOutputStream();
			while ((len = in.read(buffer)) != -1) {
				out.write(buffer, 0, len);
			}
			String text = out.toString(StandardCharsets.UTF_8.name());
			if (text.isBlank()) {
				return null;
			}
			// A name line (see MultipassSource.withName()) beats the file's.
			var named = MultipassSource.takeName(text);
			return new SharedText(named.text(),
					named.name() != null ? named.name() : getNameWithoutExtension(uri));
		} catch (IOException | SecurityException e) {
			Toast.makeText(activity, R.string.unsuitable_text,
					Toast.LENGTH_SHORT).show();
			return null;
		}
	}

	@Nullable
	private String getNameWithoutExtension(@NonNull Uri uri) {
		String name = getDisplayName(uri);
		if (name == null) {
			return null;
		}
		int dot = name.lastIndexOf('.');
		if (dot > 0) {
			name = name.substring(0, dot);
		}
		name = name.trim();
		return name.isEmpty() ? null : name;
	}

	// A shader pack file (see ShaderPackWriter) opened with or shared to
	// the app, or null if the intent isn't one - consuming the intent, like
	// readSharedText(), when it is.
	@Nullable
	public Uri takeShaderPackUri(@Nullable Intent intent) {
		if (intent == null || intent.getAction() == null) {
			return null;
		}
		String action = intent.getAction();
		Uri uri;
		if (Intent.ACTION_VIEW.equals(action)) {
			uri = intent.getData();
		} else if (Intent.ACTION_SEND.equals(action)) {
			uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
		} else {
			return null;
		}
		if (uri == null) {
			return null;
		}
		String type = intent.getType() != null
				? intent.getType()
				: activity.getContentResolver().getType(uri);
		String name = getDisplayName(uri);
		boolean pack = (type != null && type.contains("sqlite")) ||
				(name != null && (name.endsWith(".sqlite") || name.endsWith(".db")));
		if (!pack) {
			return null;
		}
		intent.setAction(null);
		return uri;
	}

	// The file name (no folders) of what `uri` points at, if it has one.
	@Nullable
	private String getDisplayName(@NonNull Uri uri) {
		String name = null;
		try (Cursor cursor = activity.getContentResolver().query(uri,
				new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
			if (cursor != null && cursor.moveToFirst()) {
				name = cursor.getString(0);
			}
		} catch (RuntimeException e) {
			// No display name on offer - fall back to the path below.
		}
		if (name == null) {
			name = uri.getLastPathSegment();
		}
		if (name == null) {
			return null;
		}
		int slash = name.lastIndexOf('/');
		if (slash >= 0) {
			name = name.substring(slash + 1);
		}
		return name;
	}

	public long duplicateShader(long shaderId) {
		var shader = dataSource.shader.getShader(shaderId);
		if (shader == null) {
			return NO_SHADER;
		}
		var thumbnail = dataSource.shader.getThumbnail(shaderId);
		long newId = dataSource.shader.insertShader(
				shader.fragmentShader(),
				null,
				thumbnail,
				shader.quality());
		if (newId > 0) {
			dataSource.buffer.copyBuffers(shaderId, newId);
			dataSource.text.copyTexts(shaderId, newId);
		}
		return newId;
	}

	// A new shader from `session` - every pass, Common and texts - with
	// the current thumbnail and quality, unnamed; the selected shader is
	// left as it is. Returns the new id, or NO_SHADER.
	public long insertShader(@NonNull ShaderProjectSession session) {
		long newId = dataSource.shader.insertShader(session.getEntryPointSource(), null,
				getThumbnail(), quality);
		if (newId <= 0) {
			return NO_SHADER;
		}
		dataSource.buffer.setBufferSources(newId, session.getProject().getBufferSources());
		dataSource.buffer.setCommonSource(newId, session.getProject().getCommonSource());
		dataSource.text.setTexts(newId, session.getProject().getTexts());
		return newId;
	}

	public void deleteShader(long shaderId) {
		dataSource.shader.removeShader(shaderId);
		if (shaderId == selectedShaderId) {
			selectedShaderId = NO_SHADER;
			currentProjectSession = null;
			updatePendingCrashShaderId();
		}
	}

	@NonNull
	private ShaderProjectSession getCurrentProjectSession() {
		return currentProjectSession != null
				? currentProjectSession
				: createScratchProjectSession(editorFragment.getText(), quality);
	}

	@NonNull
	private ShaderProjectSession createScratchProjectSession(
			@NonNull String source,
			float quality) {
		return new LooseShaderFileProjectSource(null, source, quality)
				.openSession();
	}

	@NonNull
	private ShaderProjectSession loadSavedProjectSession(long shaderId,
			@NonNull String fallbackSource) {
		var shader = dataSource.shader.getShader(shaderId);
		return shader != null
				? LegacyShaderProjectSource.load(dataSource, shader).openSession()
				: createScratchProjectSession(fallbackSource, quality);
	}

	private void applyProjectSession(long shaderId,
			@NonNull ShaderProjectSession projectSession,
			@NonNull String toolbarTitle) {
		selectedShaderId = shaderId;
		currentProjectSession = projectSession;
		quality = projectSession.getQuality();
		editorFragment.setText(projectSession.getActiveFile().source());
		uiManager.setToolbarTitle(toolbarTitle);
		shaderListManager.setSelectedShaderId(selectedShaderId);
		shaderViewManager.setProjectSession(projectSession);
		updatePendingCrashShaderId();
		setModified(false);
		if (onShaderSwitchedListener != null) {
			onShaderSwitchedListener.run();
		}
	}

	private void updatePendingCrashShaderId() {
		ShaderEditorApp.preferences.setPendingCrashShaderId(
				ShaderEditorApp.preferences.doesRunInBackground() &&
						selectedShaderId > 0
						? selectedShaderId
						: 0);
	}
}
