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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.RemoteMuzeiArtSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class PixivArtSource extends RemoteMuzeiArtSource {
    private static final String LOG_TAG = "muzei.PixivArtSource";
    private static final String SOURCE_NAME = "PixivArtSource";
    private static final int MINUTE = 60 * 1000;  // a minute in milliseconds
    private static final String RANKING_URL =
        "https://www.pixiv.net/ranking.php?mode=daily&content=illust&p=1&format=json";
    private static final String USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/57.0.2987.133 Safari/537.36";

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

    private boolean isOnlyUpdateOnWifi() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        final boolean defaultValue = false,
                      v = preferences.getBoolean("pref_onlyWifi", defaultValue);
        Log.d(LOG_TAG, "pref_onlyWifi = " + v);
        return v;
    }

    private boolean isEnabledWifi() {
        ConnectivityManager connectivityManager =
            (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo wifi = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return wifi.isConnected();
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
        final JsonObject ranking;
        final JsonArray contents;

        if (isOnlyUpdateOnWifi() && !isEnabledWifi()) {
            Log.d(LOG_TAG, "no wifi");
            scheduleUpdate();
            return;
        }

        try {
            Response resp = sendRequest(RANKING_URL);
            Log.d(LOG_TAG, "Response code: " + resp.code());
            if (!resp.isSuccessful()) {
                throw new RetryException();
            }

            ranking = Json.parse(resp.body().string()).asObject();
            contents = ranking.get("contents").asArray();
        } catch (final IOException e) {
            Log.e(LOG_TAG, e.toString(), e);
            throw new RetryException(e);
        }

        Log.d(LOG_TAG, "The number of Contents: " + contents.size());

        if (contents.isEmpty()) {
            Log.w(LOG_TAG, "No artworks returned from Pixiv");
            scheduleUpdate();
            return;
        }

        final Random random = new Random();
        while (true) {
            final int i = random.nextInt(contents.size());
            final JsonObject content;
            try {
                content = contents.get(i).asObject();
            } catch (IndexOutOfBoundsException e) {
                Log.e(LOG_TAG, e.toString(), e);
                throw new RetryException(e);
            } catch (NullPointerException e) {
                Log.e(LOG_TAG, e.toString(), e);
                throw new RetryException(e);
            }
            final int workId = content.getInt("illust_id", -1);
            final String illustType = content.getString("illust_type", null);
            if (workId < 0 || illustType == null) {
                continue;
            }
            final String token = workId + "." + illustType;
            if (prevToken != null && prevToken.equals(token)) {
                continue;
            }

            final String workUri = "http://www.pixiv.net/member_illust.php" +
                                   "?mode=medium&illust_id=" + workId;
            final Uri fileUri = downloadOriginalImage(content, token, workUri);

            final Artwork artwork = new Artwork.Builder()
                .title(content.getString("title", ""))
                .byline(content.getString("user_name", ""))
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

    private Uri downloadOriginalImage(final JsonObject content,
                                      final String token,
                                      final String referer) throws RetryException {
        final Application app = getApplication();
        if (app == null) {
            Log.e(LOG_TAG, "getApplication() returns null");
            throw new RetryException();
        }

        final String smallUri = content.getString("url", null);
        if (smallUri == null) {
            throw new RetryException();
        }

        final String originalUri = getOriginalImageUri(smallUri);
        Log.d(LOG_TAG, "original image url: " + originalUri);

        final File originalFile = new File(app.getExternalCacheDir(), token);

        try {
            Response resp = sendRequest(originalUri, referer);

            final int status = resp.code();
            Log.d(LOG_TAG, "Response code: " + status);
            if (!resp.isSuccessful()) {
                if (status == 404) {
                    return Uri.parse(smallUri);
                }
                throw new RetryException();
            }

            final FileOutputStream fileStream = new FileOutputStream(originalFile);
            final InputStream inputStream = resp.body().byteStream();
            try {
                final byte[] buffer = new byte[1024 * 50];
                int read;
                while ((read = inputStream.read(buffer)) > 0) {
                    fileStream.write(buffer, 0, read);
                }
            } finally {
                fileStream.close();
            }
            inputStream.close();
        } catch (final IOException e) {
            Log.e(LOG_TAG, e.toString(), e);
            throw new RetryException();
        }

        Log.d(LOG_TAG, "cache file path: " + originalFile.getAbsolutePath());
        return Uri.parse("file://" + originalFile.getAbsolutePath());
    }

    // input: http://i1.pixiv.net/c/240x480/img-master/img/2015/01/23/01/23/45/12345678_p0_master1200.jpg
    // output: http://i1.pixiv.net/c/1200x1200/img-master/img/2015/01/23/01/23/45/12345678_p0_master1200.jpg
    private static final Pattern IMAGE_URI_PATTERN = Pattern.compile(
        //111111111111111.......22222222......3333333333
        "^(https?://.+?/c/)[0-9]+x[0-9]+(/img-master.+)$"
    );

    private String getOriginalImageUri(String imageUri) throws RetryException {
        final Matcher m = IMAGE_URI_PATTERN.matcher(imageUri);
        if (m.matches()) {
            final String base = m.group(1), path = m.group(2);
            return base + "1200x1200" + path;

        }
        Log.e(LOG_TAG, "Match failed: " + imageUri);
        throw new RetryException();
    }

    private Response sendRequest(String url) throws IOException {
        return sendRequest(url, null);
    }

    private Response sendRequest(String url, String referer) throws IOException {
        OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
        Request.Builder builder = new Request.Builder()
            .addHeader("User-Agent", USER_AGENT)
            .url(url);
        if (referer != null) {
            builder.addHeader("Referer", referer);
        }
        return httpClient.newCall(builder.build()).execute();
    }
}
