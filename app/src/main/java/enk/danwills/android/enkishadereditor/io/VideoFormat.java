package enk.danwills.android.enkishadereditor.io;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

// What a frame sequence's video is made as - picked in Settings
// (Preferences.VIDEO_FORMAT) and made by VideoEncoder with MediaCodec +
// MediaMuxer. Which formats a phone can make depends on its encoders,
// so Settings only lists the ones it has (supported()).
public enum VideoFormat {
	H264("h264", MediaFormat.MIMETYPE_VIDEO_AVC,
			MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4", "video/mp4", 1.0),
	// HEVC and VP9 need roughly half H.264's bitrate for the same
	// quality, which is the point of picking them - so the quality
	// slider maps to a lower bitrate for these (see bitrateScale).
	HEVC("hevc", MediaFormat.MIMETYPE_VIDEO_HEVC,
			MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4", "video/mp4", 0.6),
	VP9("vp9", MediaFormat.MIMETYPE_VIDEO_VP9,
			MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, "webm", "video/webm", 0.6),
	VP8("vp8", MediaFormat.MIMETYPE_VIDEO_VP8,
			MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, "webm", "video/webm", 1.0);

	// The value stored in preferences.
	@NonNull
	public final String key;
	// The MediaCodec encoder type.
	@NonNull
	public final String codecMimeType;
	public final int muxerFormat;
	@NonNull
	public final String extension;
	// The file's own type, for MediaStore and opening it.
	@NonNull
	public final String mimeType;
	public final double bitrateScale;

	VideoFormat(@NonNull String key, @NonNull String codecMimeType, int muxerFormat,
			@NonNull String extension, @NonNull String mimeType, double bitrateScale) {
		this.key = key;
		this.codecMimeType = codecMimeType;
		this.muxerFormat = muxerFormat;
		this.extension = extension;
		this.mimeType = mimeType;
		this.bitrateScale = bitrateScale;
	}

	// The formats this phone has an encoder for at all - a given size
	// can still be too big for one (see VideoEncoder.encode()).
	@NonNull
	public static List<VideoFormat> supported() {
		MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
		List<VideoFormat> formats = new ArrayList<>();
		for (VideoFormat format : values()) {
			if (hasEncoder(codecs, format.codecMimeType)) {
				formats.add(format);
			}
		}
		return formats;
	}

	public boolean supportsSize(int width, int height) {
		return encoderFor(width, height) != null;
	}

	// The name of an encoder that can make a video this size, or null.
	// Software VP8/VP9 encoders top out at 2048x2048, well below the
	// hardware H.264 one. Only the width, height and alignment limits
	// count: encoders also advertise a block count (the software VP9
	// one about 1280x720's worth), but that's a real-time speed
	// estimate, not a limit - VP9 makes a 720x1544 video fine - and
	// these videos aren't made in real time.
	@Nullable
	public String encoderFor(int width, int height) {
		for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS)
				.getCodecInfos()) {
			if (!info.isEncoder() || !supportsType(info, codecMimeType)) {
				continue;
			}
			MediaCodecInfo.VideoCapabilities video;
			try {
				video = info.getCapabilitiesForType(codecMimeType).getVideoCapabilities();
			} catch (IllegalArgumentException e) {
				continue;
			}
			if (video != null &&
					video.getSupportedWidths().contains(width) &&
					video.getSupportedHeights().contains(height) &&
					width % video.getWidthAlignment() == 0 &&
					height % video.getHeightAlignment() == 0) {
				return info.getName();
			}
		}
		return null;
	}

	private static boolean hasEncoder(@NonNull MediaCodecInfo[] codecs, @NonNull String type) {
		for (MediaCodecInfo info : codecs) {
			if (info.isEncoder() && supportsType(info, type)) {
				return true;
			}
		}
		return false;
	}

	private static boolean supportsType(@NonNull MediaCodecInfo info, @NonNull String type) {
		for (String supportedType : info.getSupportedTypes()) {
			if (supportedType.equalsIgnoreCase(type)) {
				return true;
			}
		}
		return false;
	}

	@NonNull
	public static VideoFormat fromKey(@Nullable String key) {
		for (VideoFormat format : values()) {
			if (format.key.equals(key)) {
				return format;
			}
		}
		return H264;
	}
}
