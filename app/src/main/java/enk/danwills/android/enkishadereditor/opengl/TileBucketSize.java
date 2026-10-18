package enk.danwills.android.enkishadereditor.opengl;

// User-selectable tile ("bucket") size for the tiled high-res capture -
// see HighResScreenshotCapture.MAX_TILE_CONTENT_SIZE's own doc for why
// 512 was originally chosen as a single one-size-fits-all default: a
// smaller bucket is safer against the GPU driver's hang-detection
// watchdog on an expensive shader, a bigger one means less per-tile
// overhead (one draw call + readback per bucket, however big).
//
// MAX is the most aggressive option: at 1x oversample, with a target
// resolution that fits within a single texture, the capture skips
// tiling - and the tile-program recompile it needs - entirely (see
// HighResScreenshotCapture.capture()'s `singlePass` branch). That
// deliberately opts back out of the watchdog-safety margin tiling
// exists for in the first place; a cheap/fast shader should render
// noticeably quicker this way, but a slow/expensive one at a large
// resolution risks the exact driver hang tiling was built to avoid -
// this is a knowing trade, not a free win.
public enum TileBucketSize {
	SMALL(256),
	NORMAL(512),
	LARGE(1024),
	MAX(2048),
	// Appended after MAX (not inserted before SMALL, where it'd read
	// more naturally size-wise) so existing stored
	// Preferences.PREF_CUSTOM_RES_BUCKET_SIZE ordinal values (an int,
	// positional against this enum/bucket_size_names.xml - see
	// MainActivity's use of values()[...]) keep meaning what they
	// already meant, rather than silently remapping to a different
	// size on upgrade. 16x fewer fragments per draw call than SMALL -
	// for a shader heavy enough that even SMALL still trips the GPU
	// watchdog (see this enum's own class doc above).
	TINY(64);

	public final int pixels;

	TileBucketSize(int pixels) {
		this.pixels = pixels;
	}
}
