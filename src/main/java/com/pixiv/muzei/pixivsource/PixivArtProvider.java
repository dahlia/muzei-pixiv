package com.pixiv.muzei.pixivsource;

import com.google.android.apps.muzei.api.provider.MuzeiArtProvider;

public class PixivArtProvider extends MuzeiArtProvider {
    @Override
    protected void onLoadRequested(boolean initial) {
        PixivArtWorker.enqueLoad();
    }
}
