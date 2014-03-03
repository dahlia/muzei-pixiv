/*
 * Copyright 2014 Hong Minhee
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.pixiv.muzei.pixivsource;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.RemoteMuzeiArtSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Random;

import au.com.bytecode.opencsv.CSVReader;
import retrofit.ErrorHandler;
import retrofit.RequestInterceptor;
import retrofit.RestAdapter;
import retrofit.RetrofitError;

public class PixivArtSource extends RemoteMuzeiArtSource {
    private static final String LOG_TAG = "com.pixiv.muzei.pixivsource.PixivArtSource";
    private static final String SOURCE_NAME = "PixivArtSource";
    private static final int ROTATE_TIME_MILLIS = 60 * 60 * 1000;  // rotate every hour
    private static final String RANKING_URL =
        "http://spapi.pixiv.net/iphone/ranking.php?mode=day&content=illust&p=1";

    public PixivArtSource() {
        super(SOURCE_NAME);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        setUserCommands(BUILTIN_COMMAND_ID_NEXT_ARTWORK);
    }

    @Override
    protected void onTryUpdate(int reason) throws RetryException {
        final Artwork currentArtwork = getCurrentArtwork();
        final String currentToken = currentArtwork != null ? currentArtwork.getToken() : null;
        final URL rankingUrl;
        final HttpURLConnection conn;
        final InputStream inputStream;
        final CSVReader csvReader;
        final List<String[]> lines;

        try {
            rankingUrl = new URL(RANKING_URL);
        } catch (final MalformedURLException e) {
            Log.e(LOG_TAG, e == null ? "" : e.toString(), e);
            throw new RetryException();
        }

        try {
            conn = (HttpURLConnection) rankingUrl.openConnection();
            conn.setReadTimeout(10000);
            conn.setConnectTimeout(15000);
            conn.setRequestMethod("GET");
            conn.setDoInput(true);
            conn.connect();
            final int status = conn.getResponseCode();
            if (status != 200) {
                Log.w(LOG_TAG, "Response code: " + status);
                throw new RetryException();
            }
            Log.d(LOG_TAG, "Response code: " + status);
            inputStream = conn.getInputStream();
            try {
                csvReader = new CSVReader(new InputStreamReader(inputStream));
                lines = csvReader.readAll();
            } finally {
                try {
                    inputStream.close();
                } catch (final IOException e) {
                    Log.e(LOG_TAG, e == null ? "" : e.toString(), e);
                    throw new RetryException(e);
                }
            }
        } catch (final IOException e) {
            Log.e(LOG_TAG, e == null ? "" : e.toString(), e);
            throw new RetryException(e);
        }

        Log.d(LOG_TAG, "The number of CSV lines: " + lines.size());

        if (lines.isEmpty()) {
            Log.w(LOG_TAG, "No artworks returned from Pixiv");
            scheduleUpdate(System.currentTimeMillis() + ROTATE_TIME_MILLIS);
            return;
        }

        final Random random = new Random();
        while (true) {
            final int i = random.nextInt(lines.size());
            final String[] columns = lines.get(i);
            final String token = columns[0];
            if (currentToken != null && currentToken.equals(token)) {
                continue;
            }

            for (int c = 0; c < columns.length; ++c) {
                Log.d(LOG_TAG, "Column #" + c + ": " + columns[c]);
            }

            final Artwork artwork = new Artwork.Builder()
                .title(columns[3])
                .byline(columns[5])
                .imageUri(Uri.parse(columns[9]))
                .token(token)
                .viewIntent(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("http://www.pixiv.com/works/" + token)))
                .build();
            publishArtwork(artwork);
            break;
        }

        scheduleUpdate(System.currentTimeMillis() + ROTATE_TIME_MILLIS);
    }
}

