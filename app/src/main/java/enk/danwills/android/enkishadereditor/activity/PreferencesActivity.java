package enk.danwills.android.enkishadereditor.activity;

import androidx.fragment.app.Fragment;

import enk.danwills.android.enkishadereditor.fragment.PreferencesFragment;

public class PreferencesActivity extends AbstractContentActivity {
	@Override
	protected Fragment defaultFragment() {
		return new PreferencesFragment();
	}
}
