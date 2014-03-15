package com.pixiv.muzei.pixivsource.settings;

import android.os.Bundle;
import android.preference.PreferenceFragment;

import com.pixiv.muzei.pixivsource.R;

public class SettingsFragment extends PreferenceFragment {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);
    }
}
