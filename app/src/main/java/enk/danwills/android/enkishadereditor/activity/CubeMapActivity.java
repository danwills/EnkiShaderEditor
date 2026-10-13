package enk.danwills.android.enkishadereditor.activity;

import android.os.Bundle;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.fragment.CubeMapFragment;
import enk.danwills.android.enkishadereditor.view.SystemBarMetrics;
import enk.danwills.android.enkishadereditor.widget.CubeMapView;

public class CubeMapActivity
		extends AbstractSubsequentActivity
		implements CubeMapFragment.CubeMapViewProvider {
	private CubeMapView cubeMapImageView;

	@Override
	public CubeMapView getCubeMapView() {
		return cubeMapImageView;
	}

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_cube_map);

		cubeMapImageView = findViewById(R.id.cube_map_view);

		SystemBarMetrics.initMainLayout(this, cubeMapImageView.insets);
		AbstractSubsequentActivity.initToolbar(this);

		if (state == null) {
			setFragment(getSupportFragmentManager(), new CubeMapFragment());
		}
	}
}
