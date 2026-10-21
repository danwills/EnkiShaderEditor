package enk.danwills.android.enkishadereditor.opengl;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import enk.danwills.android.enkishadereditor.database.Database;
import enk.danwills.android.enkishadereditor.hardware.AudioSpectrumListener;

final class ShaderTextureResources {
	static final class SamplerTextureBinding {
		@NonNull
		private final DiscoveredSampler sampler;
		@Nullable
		private GlTexture texture;

		SamplerTextureBinding(@NonNull DiscoveredSampler sampler) {
			this.sampler = sampler;
		}

		@NonNull
		DiscoveredSampler sampler() {
			return sampler;
		}

		@Nullable
		GlTexture texture() {
			return texture;
		}

		void setTexture(@Nullable GlTexture texture) {
			this.texture = texture;
		}
	}

	@NonNull
	private final List<SamplerTextureBinding> bindings;
	// The shader's texts (see ShaderTextDao) its samplers are bound to,
	// by sampler name: (columns, lines) for `<name>Size`, and character
	// counts for `<name>Length`. A text wins over a saved texture of the
	// same name, for this shader.
	private final Map<String, float[]> textSizes = new HashMap<>();
	private final Map<String, Float> textLengths = new HashMap<>();
	// Texts keep tabs as tabs, every 4 columns - prose rarely has any.
	private static final int TEXT_TAB_WIDTH = 4;

	private ShaderTextureResources(@NonNull List<SamplerTextureBinding> bindings) {
		this.bindings = bindings;
	}

	@NonNull
	static ShaderTextureResources create(@NonNull List<DiscoveredSampler> samplers) {
		var bindings = new ArrayList<SamplerTextureBinding>(samplers.size());
		for (var sampler : samplers) {
			bindings.add(new SamplerTextureBinding(sampler));
		}
		return new ShaderTextureResources(bindings);
	}

	@NonNull
	static ShaderTextureResources empty() {
		return new ShaderTextureResources(List.of());
	}

	void discard() {
		for (var binding : bindings) {
			binding.setTexture(null);
		}
	}

	void release(@NonNull GlDevice device) {
		for (var binding : bindings) {
			device.deleteTexture(binding.texture());
			binding.setTexture(null);
		}
		textSizes.clear();
		textLengths.clear();
	}

	// texts: the shader's, name -> text - see ShaderTextDao.
	@NonNull
	List<ShaderError> load(@NonNull Context context, @NonNull GlDevice device,
			@NonNull Map<String, String> texts) {
		release(device);

		var errors = new ArrayList<ShaderError>();
		var textureDao = Database.getInstance(context).getDataSource().texture;
		for (var binding : bindings) {
			var sampler = binding.sampler();
			if (loadText(context, device, binding, texts)) {
				continue;
			}
			if (sampler.target() == GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
				loadExternalTexture(device, binding);
				continue;
			}
			if (ShaderRenderer.UNIFORM_AUDIO_SPECTRUM.equals(sampler.name())) {
				loadAudioSpectrumTexture(device, binding);
				continue;
			}

			var bitmap = textureDao.getTextureBitmap(sampler.name());
			if (bitmap == null) {
				continue;
			}

			try {
				switch (sampler.target()) {
					case GLES20.GL_TEXTURE_2D -> loadTexture2D(
							device,
							binding,
							errors,
							bitmap);
					case GLES20.GL_TEXTURE_CUBE_MAP -> loadCubemap(
							device,
							binding,
							bitmap,
							errors);
					default -> {
					}
				}
			} finally {
				bitmap.recycle();
			}
		}

		return errors;
	}

	// A text's been edited: just the texts' textures made again, nothing
	// recompiled. A sampler that's no longer a text is left without one
	// until the shader next loads.
	void reloadTexts(@NonNull Context context, @NonNull GlDevice device,
			@NonNull Map<String, String> texts) {
		for (var binding : bindings) {
			String name = binding.sampler().name();
			if (textSizes.containsKey(name) || texts.containsKey(name)) {
				device.deleteTexture(binding.texture());
				binding.setTexture(null);
				textSizes.remove(name);
				textLengths.remove(name);
				loadText(context, device, binding, texts);
			}
		}
	}

	// Binds the text the sampler's named after, if there is one.
	private boolean loadText(@NonNull Context context, @NonNull GlDevice device,
			@NonNull SamplerTextureBinding binding, @NonNull Map<String, String> texts) {
		var sampler = binding.sampler();
		String text = texts.get(sampler.name());
		if (text == null || sampler.target() != GLES20.GL_TEXTURE_2D) {
			return false;
		}
		SourceText made = SourceText.create(context, device, text, TEXT_TAB_WIDTH, false);
		if (made == null) {
			return false;
		}
		binding.setTexture(made.texture);
		textSizes.put(sampler.name(), made.size);
		textLengths.put(sampler.name(), (float) made.length);
		return true;
	}

	void applyTo(@NonNull ProgramBindings bindings) {
		for (var binding : this.bindings) {
			if (binding.texture() != null) {
				bindings.setTexture(binding.sampler().name(), binding.texture());
			}
		}
		for (var entry : textSizes.entrySet()) {
			bindings.setFloat2(entry.getKey() + "Size", entry.getValue());
		}
		for (var entry : textLengths.entrySet()) {
			bindings.setFloat(entry.getKey() + "Length", entry.getValue());
		}
	}

	@Nullable
	SamplerTextureBinding getFirstBinding(@NonNull String... samplerNames) {
		for (var binding : bindings) {
			for (String samplerName : samplerNames) {
				if (binding.sampler().name().equals(samplerName)) {
					return binding;
				}
			}
		}
		return null;
	}

	private static void loadAudioSpectrumTexture(
			@NonNull GlDevice device,
			@NonNull SamplerTextureBinding binding) {
		var sampler = binding.sampler();
		var texture = device.createTexture2D();
		device.applyTextureParameters(texture, sampler.parameters());
		device.allocateTexture2D(
				texture,
				AudioSpectrumListener.TEXTURE_WIDTH,
				AudioSpectrumListener.TEXTURE_HEIGHT);
		binding.setTexture(texture);
	}

	private static void loadExternalTexture(
			@NonNull GlDevice device,
			@NonNull SamplerTextureBinding binding) {
		var sampler = binding.sampler();
		var texture = device.createExternalTexture();
		device.applyTextureParameters(texture, sampler.parameters());
		binding.setTexture(texture);
	}

	private static void loadTexture2D(
			@NonNull GlDevice device,
			@NonNull SamplerTextureBinding binding,
			@NonNull List<ShaderError> errors,
			@NonNull Bitmap bitmap) {
		var sampler = binding.sampler();
		var texture = device.createTexture2D();
		device.applyTextureParameters(texture, sampler.parameters());
		var message = device.uploadTexture2D(texture, bitmap, true);
		if (message != null) {
			errors.add(ShaderError.createGeneral(message));
			device.deleteTexture(texture);
			return;
		}
		device.generateMipmap(texture);
		binding.setTexture(texture);
	}

	private static void loadCubemap(
			@NonNull GlDevice device,
			@NonNull SamplerTextureBinding binding,
			@NonNull Bitmap bitmap,
			@NonNull List<ShaderError> errors) {
		var sampler = binding.sampler();
		var texture = device.createCubemap();
		device.applyTextureParameters(texture, sampler.parameters());
		var message = device.uploadCubemapFromAtlas(texture, bitmap);
		if (message != null) {
			errors.add(ShaderError.createGeneral(message));
			device.deleteTexture(texture);
			return;
		}
		device.generateMipmap(texture);
		binding.setTexture(texture);
	}
}
