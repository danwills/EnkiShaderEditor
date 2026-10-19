package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

final class PreparedShaderSource {
	@Nullable
	private final PreparedShaderInput fragmentShader;
	private final float fTimeMax;
	@Nullable
	private final String gles3VersionDirective;
	@NonNull
	private final BackBufferParameters backBufferParameters;
	@NonNull
	private final List<DiscoveredSampler> samplers;
	// Indexed by channel, null where the source declares no such
	// iChannel - see ChannelParameters.
	@NonNull
	private final ChannelParameters[] channels;

	PreparedShaderSource(
			@Nullable PreparedShaderInput fragmentShader,
			float fTimeMax,
			@Nullable String gles3VersionDirective,
			@NonNull BackBufferParameters backBufferParameters,
			@NonNull List<DiscoveredSampler> samplers,
			@NonNull ChannelParameters[] channels) {
		this.fragmentShader = fragmentShader;
		this.fTimeMax = fTimeMax;
		this.gles3VersionDirective = gles3VersionDirective;
		this.backBufferParameters = backBufferParameters;
		this.samplers = List.copyOf(samplers);
		this.channels = channels.clone();
	}

	@NonNull
	static PreparedShaderSource empty() {
		return new PreparedShaderSource(
				null,
				3f,
				null,
				new BackBufferParameters(),
				List.of(),
				new ChannelParameters[ChannelParameters.MAX_CHANNELS]);
	}

	@Nullable
	PreparedShaderInput getFragmentShader() {
		return fragmentShader;
	}

	float getFTimeMax() {
		return fTimeMax;
	}

	@NonNull
	BackBufferParameters getBackBufferParameters() {
		return backBufferParameters;
	}

	@NonNull
	List<DiscoveredSampler> getSamplers() {
		return samplers;
	}

	@Nullable
	ChannelParameters getChannel(int channel) {
		return channels[channel];
	}

	@NonNull
	String getVertexShader(
			@NonNull String vertexShader,
			@NonNull String vertexShader3,
			int version) {
		return version == 3 && gles3VersionDirective != null
				? gles3VersionDirective + "\n" + vertexShader3
				: vertexShader;
	}
}
