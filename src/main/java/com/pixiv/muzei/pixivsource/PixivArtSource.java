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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PixivArtSource extends RemoteMuzeiArtSource {
    private static final String LOG_TAG = "muzei.PixivArtSource";
    private static final String SOURCE_NAME = "PixivArtSource";
    private static final int MINUTE = 60 * 1000;  // a minute in milliseconds

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
        final String s = preferences.getString(
                "pref_changeInterval", String.valueOf(R.string.pref_changeInterval_default)
        );
        Log.d(LOG_TAG, "pref_changeInterval = " + s);

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
        data.add("client_id", PixivArtSourceDefines.CLIENT_ID);
        data.add("client_secret", PixivArtSourceDefines.CLIENT_SECRET);
        // TODO: use refresh token for the security
        data.add("grant_type", "password");
        data.add("username", loginId);
        data.add("password", loginPassword);

        JsonObject ret;
        try {
            Response resp = sendPostRequest(
                    PixivArtSourceDefines.OAUTH_URL,
                    data,
                    "application/x-www-form-urlencoded"
            );
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
        // Log.d(LOG_TAG, "auth" + this.accessToken);
        // Log.e(LOG_TAG, "uid" + this.userId);
        return authorized;
    }

    private JsonObject getUpdateUriInfo() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        final String updateMode = preferences.getString(
                "pref_updateMode", String.valueOf(R.string.pref_updateMode_default)
        );

        JsonObject ret = new JsonObject();

        switch (updateMode) {
            case "follow":
                if (checkAuth()) {
                    ret.add("use_auth_api", true);
                    ret.add("url", PixivArtSourceDefines.FOLLOW_URL + "?restrict=public");
                } else {
                    ret.add("url", PixivArtSourceDefines.DAILY_RANKING_URL);
                }
                break;
            case "bookmark":
                if (checkAuth()) {
                    ret.add("use_auth_api", true);
                    ret.add("url", PixivArtSourceDefines.BOOKMARK_URL + "?user_id=" + this.userId + "&restrict=public");
                } else {
                    ret.add("url", PixivArtSourceDefines.DAILY_RANKING_URL);
                }
                break;
            case "weekly_rank":
                ret.add("url", PixivArtSourceDefines.WEEKLY_RANKING_URL);
                break;
            case "monthly_rank":
                ret.add("url", PixivArtSourceDefines.MONTHLY_RANKING_URL);
                break;
            case "daily_rank":
            default:
                ret.add("url", PixivArtSourceDefines.DAILY_RANKING_URL);
        }
        return ret;
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

        final JsonObject updateUriInfo = getUpdateUriInfo();

        try {
            Response resp = sendGetRequest(updateUriInfo);
            if (!resp.isSuccessful()) {
                throw new RetryException();
            }

            ranking = Json.parse(resp.body().string()).asObject();
            contents = getContents(ranking);
        } catch (IOException e) {
            Log.e(LOG_TAG, e.toString());
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

            final int illustId = getIllustId(content);
            final int restrict = getRestrictMode(content);

            if (illustId < 0 || restrict < 0) {
                continue;
            }
            final String token = illustId + "." + restrict;
            Log.d(LOG_TAG, token);
            if (prevToken != null && prevToken.equals(token)) {
                continue;
            }

            final String workUri = PixivArtSourceDefines.MEMBER_ILLUST_URL + illustId;

            final Uri fileUri = downloadOriginalImage(content, token, updateUriInfo.getString("url", ""));
            final String title = content.getString("title", "");

            final String username = getUserName(content);

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

    private String getUserName(JsonObject content) {
        String username = content.getString("user_name", null);
        if (username != null) {
            return username;
        }
        JsonValue user = content.get("user");
        if (user == null) {
            return "";
        }
        return user.asObject().getString("name", "");
    }

    private JsonArray getContents(JsonObject ranking) throws IOException {
        JsonValue contents = ranking.get("contents");
        if (contents != null) {
            return contents.asArray();
        }
        contents = ranking.get("illusts");
        if (contents != null) {
            return contents.asArray();
        }
        throw new IOException("Not found contents");
    }

    private int getIllustId(JsonObject content) {
        int illustId = content.getInt("id", -1);
        if (illustId >= 0) {
            return illustId;
        }
        return content.getInt("illust_id", -1);
    }

    private int getRestrictMode(JsonObject content) {
        int restrict = content.getInt("restrict", -1);
        if (restrict >= 0) {
            return restrict;
        }
        JsonValue contentType = content.get("illust_content_type");
        if (contentType == null) {
            return -1;
        }
        return contentType.asObject().getInt("sexual", -1);
    }

    // input: https://i.pximg.net/c/240x480/img-master/img/2017/10/29/00/00/01/65636164_p0_master1200.jpg
    // original: https://i.pximg.net/img-original/img/2017/10/29/00/00/01/65636164_p0.png
    private static final Pattern IMAGE_URI_PATTERN = Pattern.compile(
        "^(https?://.+?/)c/[0-9]+x[0-9]+/img-master(/.+)_master.+$"
    );

    private static final String[] IMAGE_SUFFIXS = {
        ".png",
        ".jpg",
        ".gif",
    };

    private Response findOriginalImageResponseFromOldType(JsonObject content) throws RetryException {
        String imageUri = content.getString("url", null);
        if (imageUri == null) {
            throw new RetryException();
        }
        final Matcher m = IMAGE_URI_PATTERN.matcher(imageUri);
        if (!m.matches()) {
            Log.e(LOG_TAG, "Match failed: " + imageUri);
            throw new RetryException();
        }
        final String base = m.group(1), path = m.group(2);
        for (String suffix : IMAGE_SUFFIXS) {
            String orig = base + "img-original" + path + suffix;
            try {
                Response res = sendGetRequest(orig, PixivArtSourceDefines.PIXIV_HOST);
                if (res.code() == 200) {
                    return res;
                }
            } catch (IOException e) {}
        }
        Log.e(LOG_TAG, "Not fount orig image: " + imageUri);
        throw new RetryException();
    }

    private Response getOriginalImageResponse(JsonObject content, String referer) throws RetryException {
        JsonValue single_page = content.get("meta_single_page");
        if (single_page == null) {
            return findOriginalImageResponseFromOldType(content);
        }
        String url;
        url = single_page.asObject().getString("original_image_url", null);
        if (url != null) {
            try {
                return sendGetRequest(url, referer);
            } catch (IOException e) {
                throw new RetryException();
            }
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
                try {
                    return sendGetRequest(url, referer);
                } catch (IOException e) {}
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

        final File originalFile = new File(app.getExternalCacheDir(), token);

        try {
            Response resp = getOriginalImageResponse(content, referer);

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
        JsonObject obj = new JsonObject();
        obj.add("url", url);
        obj.add("referer", referer);
        return sendGetRequest(obj);
    }

    private Response sendGetRequest(JsonObject urlInfo) throws IOException {
        String url = urlInfo.getString("url", "");

        Log.d(LOG_TAG, "Request: " + url);
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        Request.Builder builder = applyCommonHeaders(new Request.Builder(), urlInfo.getBoolean("use_auth_api", false))
                .url(url);
        String referer = urlInfo.getString("referer", null);
        if (referer != null) {
            builder.addHeader("Referer", referer);
        }
        if (this.authorized) {
            builder.addHeader("Authorization", "Bearer " + this.accessToken);
        }
        return httpClient.newCall(builder.build()).execute();
    }

    private Request.Builder applyCommonHeaders(Request.Builder builder, boolean useAuthAPI) {
        if (useAuthAPI) {
            return builder.addHeader("User-Agent", PixivArtSourceDefines.APP_USER_AGENT)
                    .addHeader("App-OS", PixivArtSourceDefines.APP_OS)
                    .addHeader("App-OS-Version", PixivArtSourceDefines.APP_OS_VERSION)
                    .addHeader("App-Version", PixivArtSourceDefines.APP_VERSION);
        }
        return builder.addHeader("User-Agent", PixivArtSourceDefines.BROWSER_USER_AGENT);
    }

    private Response sendPostRequest(String url, JsonObject bodyData,
                                     String contentType) throws IOException {
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

        Request.Builder builder = applyCommonHeaders(new Request.Builder(), true)
                .addHeader("Content-type", body.contentType().toString())
                .post(body)
                .url(url);
        if (this.authorized) {
            builder.addHeader("Authorization", "Bearer " + this.accessToken);
        }
        return httpClient.newCall(builder.build()).execute();
    }
}
