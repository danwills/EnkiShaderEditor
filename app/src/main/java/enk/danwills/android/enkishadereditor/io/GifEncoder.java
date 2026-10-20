package enk.danwills.android.enkishadereditor.io;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Makes a looping animated GIF from a sequence of already-saved frame
// images - VideoFormat.GIF's encoder, since Android has none built in.
// Same shape as VideoEncoder.encode(): frames are read back one at a
// time and written straight through to a MediaStore file, so neither
// the frames nor the GIF are ever held in memory whole.
//
// Each frame gets its own 256-colour palette (median cut over a 15-bit
// colour histogram), so a shader that changes colour over time doesn't
// have to share one palette across the whole sequence. A light 4x4
// ordered dither hides the banding 256 colours leave in smooth
// gradients - ordered rather than error diffusion because the pattern
// stays put from frame to frame instead of crawling, and it compresses
// better.
public final class GifEncoder {
	private static final String TAG = "GifEncoder";
	private static final String ALBUM = "Enki Shader Editor";
	// GIF's own limit on either dimension.
	public static final int MAX_SIZE = 65535;
	// Peak-to-peak dither, in 8-bit levels - about a palette step for
	// a typical shader frame.
	private static final int DITHER_AMPLITUDE = 12;
	private static final int[] BAYER_4X4 = {
			0, 8, 2, 10,
			12, 4, 14, 6,
			3, 11, 1, 9,
			15, 7, 13, 5,
	};

	private GifEncoder() {
	}

	// Same arguments and result as VideoEncoder.encode(), less the
	// quality: a GIF's quality is its palette, which is always the best
	// 256 colours for each frame. filename includes ".gif".
	@Nullable
	@RequiresApi(Build.VERSION_CODES.Q)
	public static Uri encode(@NonNull Context context, @NonNull List<Uri> frameUris,
			int width, int height, int scale, float fps, @NonNull String filename,
			@Nullable VideoEncoder.ProgressListener progressListener) {
		int gifWidth = width * scale;
		int gifHeight = height * scale;
		if (frameUris.isEmpty() || gifWidth > MAX_SIZE || gifHeight > MAX_SIZE) {
			return null;
		}
		ContentResolver resolver = context.getContentResolver();
		Uri outputUri = insertPendingImage(resolver, filename);
		if (outputUri == null) {
			return null;
		}
		boolean succeeded = false;
		try (OutputStream raw = resolver.openOutputStream(outputUri)) {
			if (raw == null) {
				return null;
			}
			OutputStream out = new BufferedOutputStream(raw, 1 << 16);
			writeHeader(out, gifWidth, gifHeight);
			int[] pixels = new int[width * height];
			byte[] indices = new byte[width * height];
			for (int i = 0; i < frameUris.size(); ++i) {
				Bitmap bitmap = decodeUri(resolver, frameUris.get(i));
				if (bitmap == null) {
					return null;
				}
				if (bitmap.getWidth() != width || bitmap.getHeight() != height) {
					Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, false);
					bitmap.recycle();
					bitmap = scaled;
				}
				bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
				bitmap.recycle();
				int[] palette = buildPalette(pixels);
				mapToPalette(pixels, width, height, palette, indices);
				writeFrame(out, indices, width, height, scale, palette,
						frameDelayCentis(i, fps));
				if (progressListener != null &&
						!progressListener.onFrameEncoded(i + 1, frameUris.size())) {
					return null;
				}
			}
			out.write(0x3b);
			out.flush();
			succeeded = true;
			return outputUri;
		} catch (IOException | RuntimeException | OutOfMemoryError e) {
			Log.e(TAG, "encode: failed", e);
			return null;
		} finally {
			if (succeeded) {
				ContentValues values = new ContentValues();
				values.put(MediaStore.Images.Media.IS_PENDING, 0);
				resolver.update(outputUri, values, null, null);
			} else {
				resolver.delete(outputUri, null, null);
			}
		}
	}

	// GIF delays are whole centiseconds, so each one is rounded from the
	// frame's real start time rather than on its own, keeping the total
	// in step with fps. Browsers slow anything under 2cs right down to
	// 10cs, so 2 is the floor - a sequence above 50fps plays slower.
	private static int frameDelayCentis(int frameIndex, float fps) {
		long start = Math.round(frameIndex * 100.0 / fps);
		long end = Math.round((frameIndex + 1) * 100.0 / fps);
		return (int) Math.max(2, Math.min(65535, end - start));
	}

	private static void writeHeader(@NonNull OutputStream out, int width, int height)
			throws IOException {
		out.write("GIF89a".getBytes(StandardCharsets.US_ASCII));
		writeShort(out, width);
		writeShort(out, height);
		// No global colour table - every frame has its own.
		out.write(0);
		out.write(0);
		out.write(0);
		// NETSCAPE2.0 extension: loop forever.
		out.write(new byte[]{0x21, (byte) 0xff, 0x0b});
		out.write("NETSCAPE2.0".getBytes(StandardCharsets.US_ASCII));
		out.write(new byte[]{0x03, 0x01, 0x00, 0x00, 0x00});
	}

	private static void writeFrame(@NonNull OutputStream out, @NonNull byte[] indices,
			int width, int height, int scale, @NonNull int[] palette, int delayCentis)
			throws IOException {
		// Graphic control extension: leave the frame in place (each one
		// covers the whole image anyway), no transparency.
		out.write(new byte[]{0x21, (byte) 0xf9, 0x04, 0x04});
		writeShort(out, delayCentis);
		out.write(0);
		out.write(0);
		// Image descriptor with a 256-entry local colour table.
		out.write(0x2c);
		writeShort(out, 0);
		writeShort(out, 0);
		writeShort(out, width * scale);
		writeShort(out, height * scale);
		out.write(0x87);
		for (int i = 0; i < 256; ++i) {
			int rgb = i < palette.length ? palette[i] : 0;
			out.write((rgb >> 16) & 0xff);
			out.write((rgb >> 8) & 0xff);
			out.write(rgb & 0xff);
		}
		// Scaled up on the way out (nearest, so pixels stay crisp, like
		// VideoEncoder's Video Scale), never in memory.
		LzwWriter lzw = new LzwWriter(out);
		for (int y = 0; y < height; ++y) {
			int row = y * width;
			for (int ry = 0; ry < scale; ++ry) {
				for (int x = 0; x < width; ++x) {
					int index = indices[row + x] & 0xff;
					for (int rx = 0; rx < scale; ++rx) {
						lzw.write(index);
					}
				}
			}
		}
		lzw.finish();
	}

	// Median cut: starts with one box holding every colour present
	// (at 5 bits per channel), and keeps splitting the box with the most
	// pixels-times-extent across its longest side, at its pixel median,
	// until there are 256 boxes or nothing left to split. Each box's
	// colour is the mean of its actual pixels, not of its bins, so pure
	// white (say) stays 255 rather than its bin's middle.
	@NonNull
	static int[] buildPalette(@NonNull int[] pixels) {
		int[] histogram = new int[32768];
		long[] sumR = new long[32768];
		long[] sumG = new long[32768];
		long[] sumB = new long[32768];
		for (int argb : pixels) {
			int bin = bin15(argb);
			++histogram[bin];
			sumR[bin] += (argb >> 16) & 0xff;
			sumG[bin] += (argb >> 8) & 0xff;
			sumB[bin] += argb & 0xff;
		}
		int used = 0;
		for (int count : histogram) {
			if (count > 0) {
				++used;
			}
		}
		int[] colours = new int[used];
		for (int bin = 0, n = 0; bin < histogram.length; ++bin) {
			if (histogram[bin] > 0) {
				colours[n++] = bin;
			}
		}
		List<Box> boxes = new ArrayList<>();
		boxes.add(new Box(colours, histogram, 0, used));
		while (boxes.size() < 256) {
			Box best = null;
			for (Box box : boxes) {
				if (box.score > 0 && (best == null || box.score > best.score)) {
					best = box;
				}
			}
			if (best == null) {
				break;
			}
			sortByChannel(colours, best.start, best.end, best.channel);
			long half = 0;
			int split = best.start + 1;
			for (int i = best.start; i < best.end - 1; ++i) {
				half += histogram[colours[i]];
				split = i + 1;
				if (half * 2 >= best.count) {
					break;
				}
			}
			boxes.remove(best);
			boxes.add(new Box(colours, histogram, best.start, split));
			boxes.add(new Box(colours, histogram, split, best.end));
		}
		int[] palette = new int[boxes.size()];
		for (int b = 0; b < boxes.size(); ++b) {
			Box box = boxes.get(b);
			long r = 0;
			long g = 0;
			long bl = 0;
			for (int i = box.start; i < box.end; ++i) {
				int bin = colours[i];
				r += sumR[bin];
				g += sumG[bin];
				bl += sumB[bin];
			}
			long half = box.count / 2;
			palette[b] = (int) ((r + half) / box.count << 16 |
					(g + half) / box.count << 8 |
					(bl + half) / box.count);
		}
		return palette;
	}

	// colours[start, end) of buildPalette(), with what choosing and
	// splitting it needs worked out once.
	private static final class Box {
		final int start;
		final int end;
		final long count;
		// 0, 1 or 2 for red, green or blue: the longest side.
		final int channel;
		// Pixels times the longest side; 0 for a box of one colour.
		final long score;

		Box(@NonNull int[] colours, @NonNull int[] histogram, int start, int end) {
			this.start = start;
			this.end = end;
			int minR = 31, minG = 31, minB = 31;
			int maxR = 0, maxG = 0, maxB = 0;
			long total = 0;
			for (int i = start; i < end; ++i) {
				int bin = colours[i];
				int r = bin >> 10;
				int g = (bin >> 5) & 31;
				int b = bin & 31;
				minR = Math.min(minR, r);
				maxR = Math.max(maxR, r);
				minG = Math.min(minG, g);
				maxG = Math.max(maxG, g);
				minB = Math.min(minB, b);
				maxB = Math.max(maxB, b);
				total += histogram[bin];
			}
			count = total;
			int extentR = maxR - minR;
			int extentG = maxG - minG;
			int extentB = maxB - minB;
			channel = extentR >= extentG
					? (extentR >= extentB ? 0 : 2)
					: (extentG >= extentB ? 1 : 2);
			int extent = channel == 0 ? extentR : channel == 1 ? extentG : extentB;
			score = end - start < 2 ? 0 : count * extent;
		}
	}

	// Dithers each pixel, then looks its 15-bit colour up in a table of
	// nearest palette entries, filled in as colours turn up.
	static void mapToPalette(@NonNull int[] pixels, int width, int height,
			@NonNull int[] palette, @NonNull byte[] indices) {
		short[] nearest = new short[32768];
		Arrays.fill(nearest, (short) -1);
		for (int y = 0; y < height; ++y) {
			for (int x = 0; x < width; ++x) {
				int i = y * width + x;
				int argb = pixels[i];
				int offset = (BAYER_4X4[(y & 3) * 4 + (x & 3)] * 2 - 15) *
						DITHER_AMPLITUDE / 32;
				int r = clamp(((argb >> 16) & 0xff) + offset);
				int g = clamp(((argb >> 8) & 0xff) + offset);
				int b = clamp((argb & 0xff) + offset);
				int bin = (r >> 3) << 10 | (g >> 3) << 5 | (b >> 3);
				int index = nearest[bin];
				if (index < 0) {
					index = nearestEntry(palette, ((r >> 3) << 3) | 4, ((g >> 3) << 3) | 4,
							((b >> 3) << 3) | 4);
					nearest[bin] = (short) index;
				}
				indices[i] = (byte) index;
			}
		}
	}

	private static int nearestEntry(@NonNull int[] palette, int r, int g, int b) {
		int best = 0;
		int bestDistance = Integer.MAX_VALUE;
		for (int i = 0; i < palette.length; ++i) {
			int dr = ((palette[i] >> 16) & 0xff) - r;
			int dg = ((palette[i] >> 8) & 0xff) - g;
			int db = (palette[i] & 0xff) - b;
			// Weighted roughly by how much each channel shows.
			int distance = 2 * dr * dr + 4 * dg * dg + db * db;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = i;
			}
		}
		return best;
	}

	private static int bin15(int argb) {
		return ((argb >> 19) & 31) << 10 | ((argb >> 11) & 31) << 5 | ((argb >> 3) & 31);
	}

	private static int clamp(int value) {
		return Math.max(0, Math.min(255, value));
	}

	// Sorts colours[start, end) by one 5-bit channel - a counting sort,
	// since there are only 32 values.
	private static void sortByChannel(@NonNull int[] colours, int start, int end, int channel) {
		int shift = channel == 0 ? 10 : channel == 1 ? 5 : 0;
		int[] counts = new int[33];
		for (int i = start; i < end; ++i) {
			++counts[((colours[i] >> shift) & 31) + 1];
		}
		for (int k = 1; k < 33; ++k) {
			counts[k] += counts[k - 1];
		}
		int[] sorted = new int[end - start];
		for (int i = start; i < end; ++i) {
			sorted[counts[(colours[i] >> shift) & 31]++] = colours[i];
		}
		System.arraycopy(sorted, 0, colours, start, sorted.length);
	}

	private static void writeShort(@NonNull OutputStream out, int value) throws IOException {
		out.write(value & 0xff);
		out.write((value >> 8) & 0xff);
	}

	@Nullable
	private static Bitmap decodeUri(@NonNull ContentResolver resolver, @NonNull Uri uri) {
		try (InputStream in = resolver.openInputStream(uri)) {
			return in == null ? null : BitmapFactory.decodeStream(in);
		} catch (IOException e) {
			return null;
		}
	}

	@Nullable
	@RequiresApi(Build.VERSION_CODES.Q)
	private static Uri insertPendingImage(@NonNull ContentResolver resolver,
			@NonNull String filename) {
		ContentValues values = new ContentValues();
		values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
		values.put(MediaStore.Images.Media.MIME_TYPE, "image/gif");
		values.put(MediaStore.Images.Media.RELATIVE_PATH,
				Environment.DIRECTORY_PICTURES + "/" + ALBUM);
		values.put(MediaStore.Images.Media.IS_PENDING, 1);
		return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
	}

	// GIF's variable-width LZW over 8-bit palette indices, fed one index
	// at a time and written out in the format's 255-byte sub-blocks.
	// Starts again (with a clear code) once the 4096-entry table fills.
	// The table is an open-addressed hash of (prefix code, next index)
	// -> code, the usual way GIF encoders keep it small.
	private static final class LzwWriter {
		private static final int MIN_CODE_SIZE = 8;
		private static final int CLEAR = 1 << MIN_CODE_SIZE;
		private static final int END = CLEAR + 1;
		private static final int MAX_CODES = 4096;
		private static final int HASH_SIZE = 5003;

		private final OutputStream out;
		private final int[] hashKeys = new int[HASH_SIZE];
		private final int[] hashCodes = new int[HASH_SIZE];
		private final byte[] block = new byte[255];
		private int blockLength;
		private int bitBuffer;
		private int bitCount;
		private int codeSize;
		private int nextCode;
		private int prefix = -1;

		LzwWriter(@NonNull OutputStream out) throws IOException {
			this.out = out;
			out.write(MIN_CODE_SIZE);
			resetTable();
			codeSize = MIN_CODE_SIZE + 1;
			emit(CLEAR);
		}

		void write(int index) throws IOException {
			if (prefix < 0) {
				prefix = index;
				return;
			}
			int key = (prefix << 8) | index;
			int slot = find(key);
			if (hashKeys[slot] == key) {
				prefix = hashCodes[slot];
				return;
			}
			emit(prefix);
			if (nextCode < MAX_CODES) {
				hashKeys[slot] = key;
				hashCodes[slot] = nextCode++;
			} else {
				emit(CLEAR);
				resetTable();
				codeSize = MIN_CODE_SIZE + 1;
			}
			prefix = index;
		}

		void finish() throws IOException {
			if (prefix >= 0) {
				emit(prefix);
			}
			emit(END);
			if (bitCount > 0) {
				writeByte(bitBuffer & 0xff);
			}
			flushBlock();
			out.write(0);
		}

		private void resetTable() {
			Arrays.fill(hashKeys, -1);
			nextCode = END + 1;
		}

		private int find(int key) {
			int slot = (int) ((key * 2654435761L) >>> 32) % HASH_SIZE;
			if (slot < 0) {
				slot += HASH_SIZE;
			}
			while (hashKeys[slot] != -1 && hashKeys[slot] != key) {
				if (++slot == HASH_SIZE) {
					slot = 0;
				}
			}
			return slot;
		}

		// Writes a code at the current width, then widens once the next
		// code to be assigned no longer fits - the point where a decoder,
		// one entry behind, widens too.
		private void emit(int code) throws IOException {
			bitBuffer |= code << bitCount;
			bitCount += codeSize;
			while (bitCount >= 8) {
				writeByte(bitBuffer & 0xff);
				bitBuffer >>>= 8;
				bitCount -= 8;
			}
			if (code != CLEAR && nextCode >= (1 << codeSize) && codeSize < 12) {
				++codeSize;
			}
		}

		private void writeByte(int value) throws IOException {
			block[blockLength++] = (byte) value;
			if (blockLength == block.length) {
				flushBlock();
			}
		}

		private void flushBlock() throws IOException {
			if (blockLength > 0) {
				out.write(blockLength);
				out.write(block, 0, blockLength);
				blockLength = 0;
			}
		}
	}
}
