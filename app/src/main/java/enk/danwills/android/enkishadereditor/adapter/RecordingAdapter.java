package enk.danwills.android.enkishadereditor.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.io.RecentRecording;

// Lists recent sequence-capture videos with a thumbnail, for
// MainActivity's "Play Recent Recordings" dialog. Thumbnails are decoded
// off the main thread (MediaMetadataRetriever.getFrameAtTime() is not
// cheap) and cached in memory for this adapter's lifetime, keyed by Uri -
// a ListView row reused for a different position mid-decode must not
// show a stale result, so each bind tags the ImageView with the Uri it's
// currently loading for and the decode callback checks that tag is
// still current before applying the bitmap.
public class RecordingAdapter extends BaseAdapter {
	private final List<RecentRecording> recordings;
	private final Map<Uri, Bitmap> thumbnailCache = new ConcurrentHashMap<>();
	private final ExecutorService executor = Executors.newFixedThreadPool(2);
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	public RecordingAdapter(@NonNull List<RecentRecording> recordings) {
		this.recordings = recordings;
	}

	@Override
	public int getCount() {
		return recordings.size();
	}

	@Override
	public RecentRecording getItem(int position) {
		return recordings.get(position);
	}

	@Override
	public long getItemId(int position) {
		return position;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		ViewHolder holder;
		if (convertView == null) {
			convertView = LayoutInflater.from(parent.getContext()).inflate(
					R.layout.row_recording, parent, false);
			holder = new ViewHolder(convertView);
			convertView.setTag(holder);
		} else {
			holder = (ViewHolder) convertView.getTag();
		}
		holder.bindTo(parent.getContext(), getItem(position));
		return convertView;
	}

	private class ViewHolder {
		private final ImageView thumbnail;
		private final TextView name;

		ViewHolder(@NonNull View itemView) {
			thumbnail = itemView.findViewById(R.id.recording_thumbnail);
			name = itemView.findViewById(R.id.recording_name);
		}

		void bindTo(@NonNull Context context, @NonNull RecentRecording recording) {
			name.setText(recording.label());
			Uri uri = recording.uri();
			thumbnail.setTag(uri);
			Bitmap cached = thumbnailCache.get(uri);
			if (cached != null) {
				thumbnail.setImageBitmap(cached);
				return;
			}
			thumbnail.setImageBitmap(null);
			executor.execute(() -> {
				Bitmap bitmap = decodeThumbnail(context, uri);
				if (bitmap == null) {
					return;
				}
				thumbnailCache.put(uri, bitmap);
				mainHandler.post(() -> {
					if (uri.equals(thumbnail.getTag())) {
						thumbnail.setImageBitmap(bitmap);
					}
				});
			});
		}
	}

	private static Bitmap decodeThumbnail(@NonNull Context context, @NonNull Uri uri) {
		MediaMetadataRetriever retriever = new MediaMetadataRetriever();
		try {
			retriever.setDataSource(context, uri);
			return retriever.getFrameAtTime(0);
		} catch (RuntimeException e) {
			return null;
		} finally {
			try {
				retriever.release();
			} catch (IOException e) {
				// Nothing more to do - the Bitmap (if any) was already
				// successfully extracted above.
			}
		}
	}
}
