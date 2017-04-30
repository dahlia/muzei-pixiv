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
import android.text.TextUtils;
import android.util.Log;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.RemoteMuzeiArtSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PixivArtSource extends RemoteMuzeiArtSource {
    private static final String LOG_TAG = "muzei.PixivArtSource";
    private static final String SOURCE_NAME = "PixivArtSource";
    private static final int MINUTE = 60 * 1000;  // a minute in milliseconds

    private static final String PIXIV_API_HOST = "https://app-api.pixiv.net";
    private static final String PIXIV_RANKING_URL = PIXIV_API_HOST + "/v1/illust/ranking";
    private static final String DAILY_RANKING_URL =
            PIXIV_RANKING_URL + "?mode=day";
    private static final String WEEKLY_RANKING_URL =
            PIXIV_RANKING_URL + "?mode=week";
    private static final String MONTHLY_RANKING_URL =
            PIXIV_RANKING_URL + "?mode=month";
    private static final String BOOKMARK_URL =
            PIXIV_API_HOST + "/v1/user/bookmarks/illust";
    private static final String FOLLOW_URL =
            PIXIV_API_HOST + "/v2/illust/follow";
    private static final String OAUTH_URL =
            "https://oauth.secure.pixiv.net/auth/token";

    private static final String APP_OS = "ios";
    private static final String APP_OS_VERSION = "10.3.1";
    private static final String APP_VERSION = "6.7.1";
    private static final String USER_AGENT =
        "PixivIOSApp/6.7.1 (iOS 10.3.1; iPhone8,1)";
    private static final String CLIENT_ID = "bYGKuGVw91e0NMfPGp44euvGt59s";
    private static final String CLIENT_SECRET = "HP3RmkgAmEGro0gn1x9ioawQE8WMfvLXDz3ZqxpK";

    private String accessToken = null;
    private String userId = null;
    private boolean authorized = false;

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

    private boolean checkAuth() {
        // cleanup authorization information
        this.authorized = false;

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (!preferences.getBoolean("pref_useAuth", false)) {
            return false;
        }
        final String loginId = preferences.getString("pref_loginId", "");
        final String loginPassword = preferences.getString("pref_loginPassword", "");
        if (loginId.equals("") || loginPassword.equals("")) {
            return false;
        }

        JsonObject data = new JsonObject();
        data.add("get_secure_url", 1);
        data.add("client_id", CLIENT_ID);
        data.add("client_secret", CLIENT_SECRET);
        // TODO: use refresh token for the security
        data.add("grant_type", "password");
        data.add("username", loginId);
        data.add("password", loginPassword);

        JsonObject ret;
        try {
            Response resp = sendPostRequest(OAUTH_URL, data, "application/x-www-form-urlencoded");

            //Log.d(LOG_TAG, resp.body().string());
            ret = Json.parse(resp.body().string()).asObject();
        } catch (IOException e) {
            return false;
        }
        if (ret.getBoolean("has_error", false)) {
            return false;
        }
        final JsonObject tokens = ret.get("response").asObject();
        this.accessToken = tokens.getString("access_token", null);
        this.userId = tokens.get("user").asObject().getString("id", null);
        this.authorized = this.accessToken != null && this.userId != null;
        return authorized;
    }

    private String getUpdateUri() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        final String updateMode =
                preferences.getString("pref_updateMode", String.valueOf(R.string.pref_updateMode_default));
        switch (updateMode) {
            case "follow":
                return checkAuth()
                    ? (FOLLOW_URL + "?restrict=public")
                    : DAILY_RANKING_URL;
            case "bookmark":
                return checkAuth()
                    ? (BOOKMARK_URL + "?user_id=" + this.userId + "&restrict=public")
                    : DAILY_RANKING_URL;
            case "weekly_rank":
                return WEEKLY_RANKING_URL;
            case "monthly_rank":
                return MONTHLY_RANKING_URL;
            case "daily_rank":
            default:
                return DAILY_RANKING_URL;
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

        final String updateUri = getUpdateUri();
        try {
            Response resp = sendGetRequest(updateUri);
            Log.d(LOG_TAG, "Response code: " + resp.code());
            if (!resp.isSuccessful()) {
                throw new RetryException();
            }

            ranking = Json.parse(resp.body().string()).asObject();
            contents = ranking.get("illusts").asArray();
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
            } catch (IndexOutOfBoundsException | NullPointerException e) {
                Log.e(LOG_TAG, e.toString(), e);
                throw new RetryException(e);
            }
            final int workId = content.getInt("id", -1);
            final int restrict = content.getInt("restrict", -1);
            if (workId < 0 || restrict < 0) {
                continue;
            }
            final String token = workId + "." + restrict;
            Log.d(LOG_TAG, token);
            if (prevToken != null && prevToken.equals(token)) {
                continue;
            }

            final String workUri = "http://www.pixiv.net/member_illust.php" +
                                   "?mode=medium&illust_id=" + workId;
            final Uri fileUri = downloadOriginalImage(content, token, updateUri);
            final String title = content.getString("title", "");
            final JsonObject user = content.get("user").asObject();
            final String username = user == null ? "" : user.getString("name", "");

            final Artwork artwork = new Artwork.Builder()
                .title(title)
                .byline(username)
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

    private String findOriginalImageUri(JsonObject content) throws RetryException {
        JsonObject single_page = content.get("meta_single_page").asObject();
        if (single_page == null) {
            throw new RetryException();
        }
        String url;
        url = single_page.getString("original_image_url", null);
        if (url != null) {
            return url;
        }
        JsonArray meta_pages = content.get("meta_pages").asArray();
        JsonObject group = null;
        if (!meta_pages.isEmpty()) {
            group = meta_pages.get(0).asObject();
        }
        if (group == null) {
            group = content;
        }

        JsonObject urls = group.get("image_urls").asObject();
        if (urls == null) {
            throw new RetryException();
        }
        for (String size : new String[]{"original", "large", "medium"}) {
            url = urls.getString(size, null);
            if (url != null) {
                return url;
            }
        }
        throw new RetryException();
    }

    private Uri downloadOriginalImage(final JsonObject content,
                                      final String token,
                                      final String referer) throws RetryException {
        final Application app = getApplication();
        if (app == null) {
            Log.e(LOG_TAG, "getApplication() returns null");
            throw new RetryException();
        }

        final String uri = findOriginalImageUri(content);
        final File originalFile = new File(app.getExternalCacheDir(), token);

        try {
            Response resp = sendGetRequest(uri, referer);

            final int status = resp.code();
            Log.d(LOG_TAG, "Response code: " + status);
            if (!resp.isSuccessful()) {
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

    private Response sendGetRequest(String url) throws IOException {
        return sendGetRequest(url, null);
    }

    private Response sendGetRequest(String url, String referer) throws IOException {
        Log.d(LOG_TAG, "Request: " + url);
        OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
        Request.Builder builder = new Request.Builder()
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("App-OS", APP_OS)
            .addHeader("App-OS-Version", APP_OS_VERSION)
            .addHeader("App-Version", APP_VERSION)
            .url(url);
        if (referer != null) {
            builder.addHeader("Referer", referer);
        }
        if (this.authorized) {
            builder.addHeader("Authorization", "Bearer " + this.accessToken);
        }
        return httpClient.newCall(builder.build()).execute();
    }

    private Response sendPostRequest(String url, JsonObject bodyData, String contentType) throws IOException {
        String bodyString;
        if (contentType.equals("application/json")) {
            bodyString = bodyData.toString();
        } else {
            ArrayList<String> buf = new ArrayList<String>();
            for (JsonObject.Member member : bodyData) {
                JsonValue v = member.getValue();
                if (v.isNumber()) {
                    buf.add(String.format("%s=%d", member.getName(), v.asInt()));
                } else {
                    buf.add(String.format("%s=%s", member.getName(), v.asString()));
                }
            }
            bodyString = TextUtils.join("&", buf);
        }
        RequestBody body = RequestBody.create(
                MediaType.parse(contentType),
                bodyString
        );
        // Log.d(LOG_TAG, "data: " + bodyString);
        return sendPostRequest(url, body);
    }

    private Response sendPostRequest(String url, RequestBody body) throws IOException {
        Log.d(LOG_TAG, "Request: " + url);
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        Request.Builder builder = new Request.Builder()
                .addHeader("Content-type", body.contentType().toString())
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("App-OS", APP_OS)
                .addHeader("App-OS-Version", APP_OS_VERSION)
                .addHeader("App-Version", APP_VERSION)
                .post(body)
                .url(url);
        if (this.authorized) {
            builder.addHeader("Authorization", "Bearer " + this.accessToken);
        }
        return httpClient.newCall(builder.build()).execute();
    }
}
