package com.pixiv.muzei.pixivsource.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.preference.ListPreference;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListAdapter;

import com.pixiv.muzei.pixivsource.R;

import java.util.Arrays;
import java.util.List;

class SummariedPreferenceAdapter extends ArrayAdapter<CharSequence> {

    private final String key;

    SummariedPreferenceAdapter(Context context,
                               String key,
                               CharSequence[] entries) {
        super(context, android.R.layout.simple_list_item_single_choice, entries);
        this.key = key;
    }

    @Override
    public boolean isEnabled(int position) {
        if (key.equals("pref_updateMode")) {
            return isUpdateModeOptionsEnabled(position);
        }
        return true;
    }

    private boolean isUpdateModeOptionsEnabled(int position) {
        // forceful override item enabled options
        final Context context = this.getContext();
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(this.getContext());
        boolean useAuth = preferences.getBoolean("pref_useAuth", false);
        if (useAuth) {
            return true;
        }
        final Resources resources= context.getResources();
        List<String> items = Arrays.asList(
                resources.getStringArray(R.array.pref_updateMode_requireAuthEntries)
        );
        String value = resources.getStringArray(R.array.pref_updateMode_entryValues)[position];

        return !items.contains(value);
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = super.getView(position, convertView, parent);
        if (this.isEnabled(position)) {
            view.setEnabled(true);
        } else {
            view.setEnabled(false);
        }
        return view;
    }
}

public class SummariedListPreference extends ListPreference {
    public SummariedListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public CharSequence getSummary() {
        return getEntry();
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        ListAdapter adapter =
                new SummariedPreferenceAdapter(this.getContext(), this.getKey(), getEntries());
        builder.setAdapter(adapter, this);
        super.onPrepareDialogBuilder(builder);
    }
}