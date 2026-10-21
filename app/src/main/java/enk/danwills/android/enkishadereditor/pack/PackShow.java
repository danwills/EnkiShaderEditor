package enk.danwills.android.enkishadereditor.pack;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// How a presentation pack plays (DatabaseContract.PackColumns.SHOW) - see
// ShowActivity. Kept as JSON so it can grow:
//
//   {
//     "seconds": 30,              each shader's turn, in a loop
//     "quality": 0.5,             render scale for every shader (as the
//                                 editor's Render Quality); 0 or none -
//                                 each shader's own
//     "module": "enki_demo",      a tracker module soundtrack, or
//     "soundShader": "Name",      the Sound pass of the pack's shader of
//                                 that name, played under every shader -
//                                 with neither, each plays its own sound
//     "scenes": [                 optional timeline, in place of seconds
//       {"shader": "Name", "seconds": 20,
//        "keys": [                its #defines over the scene (live, as
//          {"at": 0, "defines": {"ZOOM": 1.0, "TINT": [1, 0.5, 0]}},
//          {"at": 20, "defines": {"ZOOM": 4.0}}     Adjust Defines does)
//        ]}
//     ]
//   }
//
// Shaders are named as they were in the library the pack was built in;
// installed, they're "<Pack Title>: <name>" - see matchesName().
public record PackShow(int seconds, @Nullable String module, @Nullable String soundShader,
		@NonNull List<Scene> scenes, float quality) {
	public static final int DEFAULT_SECONDS = 30;
	public static final int MIN_SECONDS = 3;

	// One shader's turn in a timeline.
	public record Scene(@NonNull String shader, float seconds, @NonNull List<Key> keys) {
		// The defines' values `t` seconds into the scene: each one eased
		// between the keys either side of t that set it, held before the
		// first and after the last.
		@NonNull
		public Map<String, float[]> definesAt(float t) {
			var values = new LinkedHashMap<String, float[]>();
			for (Key key : keys) {
				for (String name : key.defines().keySet()) {
					if (!values.containsKey(name)) {
						values.put(name, valueAt(name, t));
					}
				}
			}
			return values;
		}

		@NonNull
		private float[] valueAt(@NonNull String name, float t) {
			Key before = null;
			Key after = null;
			for (Key key : keys) {
				if (!key.defines().containsKey(name)) {
					continue;
				}
				if (key.at() <= t && (before == null || key.at() >= before.at())) {
					before = key;
				}
				if (key.at() > t && (after == null || key.at() < after.at())) {
					after = key;
				}
			}
			if (before == null) {
				return after.defines().get(name).clone();
			}
			float[] a = before.defines().get(name);
			if (after == null) {
				return a.clone();
			}
			float[] b = after.defines().get(name);
			float f = (t - before.at()) / Math.max(1e-3f, after.at() - before.at());
			// Smoothstep: each key eases in and out.
			f = f * f * (3f - 2f * f);
			float[] v = new float[Math.max(a.length, b.length)];
			for (int i = 0; i < v.length; ++i) {
				float x = i < a.length ? a[i] : 0f;
				float y = i < b.length ? b[i] : x;
				v[i] = x + (y - x) * f;
			}
			return v;
		}
	}

	public record Key(float at, @NonNull Map<String, float[]> defines) {
	}

	@NonNull
	public static PackShow simple(int seconds, @Nullable String module,
			@Nullable String soundShader) {
		return new PackShow(Math.max(MIN_SECONDS, seconds), module, soundShader,
				Collections.emptyList(), 0);
	}

	// Null for no show (null or empty), or JSON this can't read.
	@Nullable
	public static PackShow parse(@Nullable String json) {
		if (json == null || json.trim().isEmpty()) {
			return null;
		}
		try {
			var o = new JSONObject(json);
			var scenes = new ArrayList<Scene>();
			JSONArray sceneArray = o.optJSONArray("scenes");
			if (sceneArray != null) {
				for (int i = 0; i < sceneArray.length(); ++i) {
					JSONObject s = sceneArray.getJSONObject(i);
					var keys = new ArrayList<Key>();
					JSONArray keyArray = s.optJSONArray("keys");
					if (keyArray != null) {
						for (int k = 0; k < keyArray.length(); ++k) {
							JSONObject key = keyArray.getJSONObject(k);
							keys.add(new Key((float) key.optDouble("at", 0),
									parseDefines(key.optJSONObject("defines"))));
						}
					}
					scenes.add(new Scene(s.getString("shader"),
							(float) Math.max(1, s.optDouble("seconds",
									o.optInt("seconds", DEFAULT_SECONDS))),
							keys));
				}
			}
			return new PackShow(
					Math.max(MIN_SECONDS, o.optInt("seconds", DEFAULT_SECONDS)),
					emptyToNull(o.optString("module", null)),
					emptyToNull(o.optString("soundShader", null)),
					scenes,
					(float) Math.max(0, o.optDouble("quality", 0)));
		} catch (JSONException e) {
			return null;
		}
	}

	@NonNull
	public String toJson() {
		try {
			var o = new JSONObject();
			o.put("seconds", seconds);
			if (quality > 0) {
				o.put("quality", quality);
			}
			if (module != null) {
				o.put("module", module);
			}
			if (soundShader != null) {
				o.put("soundShader", soundShader);
			}
			if (!scenes.isEmpty()) {
				var sceneArray = new JSONArray();
				for (Scene scene : scenes) {
					var s = new JSONObject();
					s.put("shader", scene.shader());
					s.put("seconds", scene.seconds());
					var keyArray = new JSONArray();
					for (Key key : scene.keys()) {
						var k = new JSONObject();
						k.put("at", key.at());
						var defines = new JSONObject();
						for (var entry : key.defines().entrySet()) {
							float[] v = entry.getValue();
							if (v.length == 1) {
								defines.put(entry.getKey(), v[0]);
							} else {
								var a = new JSONArray();
								for (float c : v) {
									a.put(c);
								}
								defines.put(entry.getKey(), a);
							}
						}
						k.put("defines", defines);
						keyArray.put(k);
					}
					s.put("keys", keyArray);
					sceneArray.put(s);
				}
				o.put("scenes", sceneArray);
			}
			return o.toString();
		} catch (JSONException e) {
			throw new IllegalStateException(e);
		}
	}

	// Whether an installed shader called `installedName` ("<Pack Title>:
	// <name>", or just the name) is the one the show calls `name`.
	public static boolean matchesName(@Nullable String installedName, @NonNull String name) {
		if (installedName == null) {
			return false;
		}
		if (installedName.equalsIgnoreCase(name)) {
			return true;
		}
		int separator = installedName.indexOf(": ");
		return separator >= 0 && installedName.substring(separator + 2).equalsIgnoreCase(name);
	}

	@NonNull
	private static Map<String, float[]> parseDefines(@Nullable JSONObject o)
			throws JSONException {
		var defines = new LinkedHashMap<String, float[]>();
		if (o == null) {
			return defines;
		}
		for (Iterator<String> it = o.keys(); it.hasNext(); ) {
			String name = it.next();
			JSONArray a = o.optJSONArray(name);
			if (a != null) {
				float[] v = new float[Math.min(4, a.length())];
				for (int i = 0; i < v.length; ++i) {
					v[i] = (float) a.getDouble(i);
				}
				defines.put(name, v);
			} else {
				defines.put(name, new float[]{(float) o.getDouble(name)});
			}
		}
		return defines;
	}

	@Nullable
	private static String emptyToNull(@Nullable String s) {
		return s == null || s.trim().isEmpty() ? null : s.trim();
	}
}
