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

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.RemoteMuzeiArtSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import au.com.bytecode.opencsv.CSVReader;

public class PixivArtSource extends RemoteMuzeiArtSource {
    private static final String LOG_TAG = "com.pixiv.muzei.pixivsource.PixivArtSource";
    private static final String SOURCE_NAME = "PixivArtSource";
    private static final int MINUTE = 60 * 1000;  // a minute in milliseconds
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

    private int getChangeInterval() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        final String defaultValue = getString(R.string.pref_changeInterval_default),
                     s = preferences.getString("pref_changeInterval", defaultValue);
        Log.d(LOG_TAG, "pref_changeInterval = \"" + s + "\"");
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            Log.w(LOG_TAG, e.toString(), e);
            return 0;
        }
    }

    private void scheduleUpdate() {
        final int changeInterval = getChangeInterval();
        if (changeInterval > 0) {
            scheduleUpdate(System.currentTimeMillis() + changeInterval * MINUTE);
        }
    }

    @Override
    protected void onTryUpdate(final int reason) throws RetryException {
        final Artwork prevArtwork = getCurrentArtwork();
        final String prevToken = prevArtwork != null ? prevArtwork.getToken() : null;
        final URL rankingUrl;
        final HttpURLConnection conn;
        final InputStream inputStream;
        final CSVReader csvReader;
        final List<String[]> lines;

        try {
            rankingUrl = new URL(RANKING_URL);
        } catch (final MalformedURLException e) {
            Log.e(LOG_TAG, e.toString(), e);
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
                    Log.e(LOG_TAG, e.toString(), e);
                    throw new RetryException(e);
                }
            }
        } catch (final IOException e) {
            Log.e(LOG_TAG, e.toString(), e);
            throw new RetryException(e);
        }

        Log.d(LOG_TAG, "The number of CSV lines: " + lines.size());

        if (lines.isEmpty()) {
            Log.w(LOG_TAG, "No artworks returned from Pixiv");
            scheduleUpdate();
            return;
        }

        final Random random = new Random();
        while (true) {
            final int i = random.nextInt(lines.size());
            final String[] columns = lines.get(i);
            final String workId = columns[0], token = workId + "." + columns[2];
            if (prevToken != null && prevToken.equals(token)) {
                continue;
            }

            for (int c = 0; c < columns.length; ++c) {
                Log.d(LOG_TAG, "Column #" + c + ": " + columns[c]);
            }

            final String workUri = "http://www.pixiv.com/works/" + workId;
            final Uri fileUri = downloadOriginalImage(columns[9], token, workUri);

            final Artwork artwork = new Artwork.Builder()
                .title(columns[3])
                .byline(columns[5])
                .imageUri(fileUri)
                .token(token)
                .viewIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(workUri)))
                .build();
            publishArtwork(artwork);
            break;
        }

        final Application app = getApplication();
        if (app == null) {
            Log.e(LOG_TAG, "getApplication() returns null");
            throw new RetryException();
        }
        if (prevToken != null) {
            final File file = new File(app.getExternalCacheDir(), prevToken);
            file.delete();
        }

        scheduleUpdate();
    }

    private Uri downloadOriginalImage(final String imageUri,
                                      final String token,
                                      final String referer) throws RetryException {
        final Application app = getApplication();
        if (app == null) {
            Log.e(LOG_TAG, "getApplication() returns null");
            throw new RetryException();
        }

        final URL originalUri;
        try {
            originalUri = new URL(getOriginalImageUri(imageUri));
        } catch (final MalformedURLException e) {
            Log.e(LOG_TAG, e.toString(), e);
            throw new RetryException();
        }
        Log.d(LOG_TAG, "original image url: " + originalUri);

        final File originalFile = new File(app.getExternalCacheDir(), token);
        final HttpURLConnection conn;
        try {
            conn = (HttpURLConnection) originalUri.openConnection();
            conn.setReadTimeout(10000);
            conn.setConnectTimeout(15000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Referer", referer);
            conn.setDoInput(true);
            conn.connect();
            final int status = conn.getResponseCode();
            switch (status) {
                case 404:
                    // When the original image seems to not exist, use the thumbnail instead.
                    return Uri.parse(imageUri);

                case 200:
                    break;

                default:
                    Log.w(LOG_TAG, "Response code: " + status);
                    throw new RetryException();
            }
            Log.d(LOG_TAG, "Response code: " + status);
            final FileOutputStream fileStream = new FileOutputStream(originalFile);
            final InputStream inputStream = conn.getInputStream();
            try {
                final byte[] buffer = new byte[1024 * 50];
                int read;
                while ((read = inputStream.read(buffer)) > 0) {
                    fileStream.write(buffer, 0, read);
                }
            } finally {
                fileStream.close();
                try {
                    inputStream.close();
                } catch (final IOException e) {
                    Log.e(LOG_TAG, e.toString(), e);
                    throw new RetryException(e);
                }
            }
        } catch (final IOException e) {
            Log.e(LOG_TAG, e.toString(), e);
            throw new RetryException();
        }

        Log.d(LOG_TAG, "cache file path: " + originalFile.getAbsolutePath());
        return Uri.parse("file://" + originalFile.getAbsolutePath());
    }

    // input: http://i1.pixiv.net/img05/img/username/mobile/12345678_480mw.jpg
    // output: http://i1.pixiv.net/img05/img/username/12345678.jpg
    private static final Pattern IMAGE_URI_PATTERN = Pattern.compile(
        //111111111111111.......22222222......3333333333
        "^(https?://.+?/)mobile/([0-9]+)_[^.]+([.][^.]+)$"
    );

    private String getOriginalImageUri(final String imageUri) throws RetryException {
        final Matcher m = IMAGE_URI_PATTERN.matcher(imageUri);
        if (m.matches()) {
            final String base = m.group(1), token = m.group(2), suffix = m.group(3);
            return base + token + suffix;
        }
        Log.e(LOG_TAG, "Match failed: " + imageUri);
        throw new RetryException();
    }
}

