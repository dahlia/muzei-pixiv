package com.pixiv.muzei.pixivsource.settings;

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.util.Log;

import com.google.android.apps.muzei.api.provider.Artwork;
import com.google.android.apps.muzei.api.provider.ProviderClient;
import com.google.android.apps.muzei.api.provider.ProviderContract;
import com.pixiv.muzei.pixivsource.PixivArtProvider;
import com.pixiv.muzei.pixivsource.PixivArtWorker;
import com.pixiv.muzei.pixivsource.R;

import java.util.ArrayList;

public class SettingsFragment extends PreferenceFragment {
    private static final String LOG_TAG = "muzei.PixivArt.Settings";

    private OnSharedPreferenceChangeListener preferenceChangeListener;
    private String currentUpdateMode;
    private String newUpdateMode;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        final SharedPreferences preferences = getPreferenceManager().getSharedPreferences();
        newUpdateMode = currentUpdateMode =
                preferences.getString(
                        "pref_updateMode",
                        String.valueOf(R.string.pref_updateMode_default)
                );
        preferenceChangeListener = new OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                if (!key.equals("pref_updateMode")) {
                    return;
                }
                newUpdateMode = preferences.getString(
                        "pref_updateMode",
                        String.valueOf(R.string.pref_updateMode_default)
                );
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        SharedPreferences preferences = getPreferenceManager().getSharedPreferences();
        preferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener);
    }

    @Override
    public void onPause() {
        super.onPause();
        SharedPreferences preferences = getPreferenceManager().getSharedPreferences();
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (currentUpdateMode.equals(newUpdateMode)) {
            return;
        }
        Log.d(LOG_TAG, "new update mode: " + newUpdateMode);
        ProviderClient client = ProviderContract.getProviderClient(getContext(), PixivArtProvider.class);
        // all clear cache
        client.setArtwork(new ArrayList<Artwork>());
        // request reload
        PixivArtWorker.enqueLoad();
    }
}
