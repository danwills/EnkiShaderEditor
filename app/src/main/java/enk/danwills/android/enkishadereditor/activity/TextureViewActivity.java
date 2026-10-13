package enk.danwills.android.enkishadereditor.activity;

import android.os.Bundle;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.fragment.TextureViewFragment;
import enk.danwills.android.enkishadereditor.view.SystemBarMetrics;
import enk.danwills.android.enkishadereditor.widget.ScalingImageView;

public class TextureViewActivity
		extends AbstractSubsequentActivity
		implements TextureViewFragment.ScalingImageViewProvider {
	private ScalingImageView scalingImageView;

	@Override
	public ScalingImageView getScalingImageView() {
		return scalingImageView;
	}

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_view_texture);

		scalingImageView = findViewById(R.id.scaling_image_view);

		SystemBarMetrics.initMainLayout(this, null);
		AbstractSubsequentActivity.initToolbar(this);

		if (state == null) {
			setFragmentForIntent(new TextureViewFragment(), getIntent());
		}
	}
}
