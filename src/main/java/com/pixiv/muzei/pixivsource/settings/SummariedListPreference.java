package com.pixiv.muzei.pixivsource.settings;

import android.content.Context;
import android.preference.ListPreference;
import android.util.AttributeSet;

public class SummariedListPreference extends ListPreference {
    public SummariedListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SummariedListPreference(Context context) {
        super(context);
    }

    @Override
    public CharSequence getSummary() {
        return getEntry();
    }
}
