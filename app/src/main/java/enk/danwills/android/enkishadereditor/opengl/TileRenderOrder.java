package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

// Which order HighResScreenshotCapture.renderTiles() visits a capture's
// tiles in - purely cosmetic (every tile's own pixels come out identical
// regardless of order; only the order the *preview* on the progress
// dialog visibly fills in changes) but each option makes for quite a
// different watching experience, which is the entire point.
public enum TileRenderOrder {
	// Row by row (GL's own bottom-up row convention), each row left to
	// right - what every capture did before this enum existed.
	SCANLINE,
	// Every tile, shuffled - no spatial pattern at all.
	RANDOM,
	// Nearest the middle of the image first, outward from there.
	FROM_CENTER,
	// A Hilbert space-filling curve - tiles always jump to a spatially
	// adjacent one next, but in a much more visually interesting pattern
	// than a plain scanline. The default - see Preferences.
	HILBERT;

	// One {tileRow, tileCol} pair per tile, in this order's visiting
	// sequence - tileRow/tileCol match renderTiles()'s own loop
	// variables (tileRow 0 = GL's bottom row), so callers can look up
	// each pair's actual pixel bounds exactly as renderTiles() already
	// does for the plain nested-loop case.
	@NonNull
	List<int[]> order(int tilesX, int tilesY) {
		List<int[]> tiles = new ArrayList<>(tilesX * tilesY);
		for (int row = 0; row < tilesY; ++row) {
			for (int col = 0; col < tilesX; ++col) {
				tiles.add(new int[]{row, col});
			}
		}
		switch (this) {
			case RANDOM:
				Collections.shuffle(tiles, new Random());
				break;
			case FROM_CENTER:
				double centerRow = (tilesY - 1) / 2.0;
				double centerCol = (tilesX - 1) / 2.0;
				// Collections.sort() with an explicit Comparator, not
				// List.sort()/Comparator.comparingDouble() - those are
				// API 24+, and this project's minSdk is 23.
				Collections.sort(tiles, (a, b) -> {
					double dRowA = a[0] - centerRow;
					double dColA = a[1] - centerCol;
					double dRowB = b[0] - centerRow;
					double dColB = b[1] - centerCol;
					return Double.compare(
							dRowA * dRowA + dColA * dColA, dRowB * dRowB + dColB * dColB);
				});
				break;
			case HILBERT:
				// The curve is only defined over a square grid whose side
				// is a power of two - pick the smallest one that covers
				// the real (generally non-square, non-power-of-two) tile
				// grid, compute every real tile's distance along that
				// bigger curve, then sort by it. Cells of the bigger
				// square that fall outside the real grid are simply
				// never generated in the first place (the tiles list
				// above only ever contains real tiles), so they don't
				// need filtering out afterward.
				int side = 1;
				while (side < Math.max(tilesX, tilesY)) {
					side *= 2;
				}
				final int hilbertSide = side;
				Collections.sort(tiles, (a, b) -> Long.compare(
						hilbertDistance(hilbertSide, a[1], a[0]),
						hilbertDistance(hilbertSide, b[1], b[0])));
				break;
			case SCANLINE:
			default:
				break;
		}
		return tiles;
	}

	// Classic iterative xy2d conversion (see e.g. Wikipedia's "Hilbert
	// curve" article) - n must be a power of two, 0 <= x,y < n.
	private static long hilbertDistance(int n, int x, int y) {
		long d = 0;
		for (int s = n / 2; s > 0; s /= 2) {
			int rx = (x & s) > 0 ? 1 : 0;
			int ry = (y & s) > 0 ? 1 : 0;
			d += (long) s * s * ((3 * rx) ^ ry);
			// Rotate the remaining (x, y) into the next quadrant's frame.
			if (ry == 0) {
				if (rx == 1) {
					x = s - 1 - x;
					y = s - 1 - y;
				}
				int t = x;
				x = y;
				y = t;
			}
		}
		return d;
	}
}
