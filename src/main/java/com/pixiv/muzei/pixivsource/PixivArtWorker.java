package com.pixiv.muzei.pixivsource;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.google.android.apps.muzei.api.provider.Artwork;
import com.google.android.apps.muzei.api.provider.ProviderClient;
import com.google.android.apps.muzei.api.provider.ProviderContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PixivArtWorker extends Worker {
    private static final String LOG_TAG = "muzei.PixivArtWorker";

    private String accessToken = null;
    private String userId = null;
    private boolean authorized = false;

    public PixivArtWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    public static void enqueLoad() {
        WorkManager manager = WorkManager.getInstance();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        WorkRequest request = new OneTimeWorkRequest.Builder(PixivArtWorker.class)
                .setConstraints(constraints)
                .build();
        manager.enqueue(request);
    }

    @NonNull
    @Override
    public Result doWork() {
        final JsonObject updateUriInfo = getUpdateUriInfo();
        final JsonObject ranking;
        final JsonArray contents;

        try {
            Response resp = sendGetRequest(updateUriInfo);
            if (!resp.isSuccessful()) {
                return Result.retry();
            }

            ranking = Json.parse(resp.body().string()).asObject();
            contents = getContents(ranking);
        } catch (IOException e) {
            Log.e(LOG_TAG, e.toString());
            return Result.retry();
        }

        Log.d(LOG_TAG, "The number of Contents: " + contents.size());

        if (contents.isEmpty()) {
            Log.w(LOG_TAG, "No artworks returned from Pixiv");
            return Result.failure();
        }

        ProviderClient client =
                ProviderContract.getProviderClient(getApplicationContext(), PixivArtProvider.class);

        ArrayList artworks = new ArrayList<Artwork>();
        for (JsonValue item : contents) {
            final JsonObject content = item.asObject();
            final int illustId = getIllustId(content);
            final int restrict = getRestrictMode(content);

            if (illustId < 0 || restrict < 0) {
                continue;
            }

            final String token = illustId + "." + restrict;
            // Log.d(LOG_TAG, token);

            final String workUri = PixivArtSourceDefines.MEMBER_ILLUST_URL + illustId;
            final Uri webUri = Uri.parse(workUri);
            final Uri fileUri;
            try {
                fileUri = downloadOriginalImage(content, token, updateUriInfo.getString("url", ""));
            } catch (IOException e) {
                Log.d(LOG_TAG, e.toString());
                continue;
            }

            final String title = content.getString("title", "");
            final String username = getUserName(content);

            final Artwork artwork = new Artwork.Builder()
                    .title(title)
                    .byline(username)
                    .webUri(webUri)
                    .persistentUri(fileUri)
                    .token(token)
                    .build();
            artworks.add(artwork);
        }
        client.setArtwork(artworks);
        return Result.success();
    }

    private boolean checkAuth() {
        // cleanup authorization information
        this.authorized = false;

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
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
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

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

    private Response findOriginalImageResponseFromOldType(JsonObject content) throws IOException {
        String imageUri = content.getString("url", null);
        if (imageUri == null) {
            throw new IOException("Invalid URL");
        }
        final Matcher m = IMAGE_URI_PATTERN.matcher(imageUri);
        if (!m.matches()) {
            throw new IOException("Unmatched URL pattern: " + imageUri);
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
        throw new IOException("Couldn't find original image: " + imageUri);
    }

    private Response getOriginalImageResponse(JsonObject content, String referer) throws IOException {
        JsonValue single_page = content.get("meta_single_page");
        if (single_page == null) {
            return findOriginalImageResponseFromOldType(content);
        }
        String url;
        url = single_page.asObject().getString("original_image_url", null);
        if (url != null) {
            return sendGetRequest(url, referer);
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
            throw new IOException("Couldn't find image_urls");
        }
        for (String size : new String[]{"original", "large", "medium"}) {
            url = urls.getString(size, null);
            if (url != null) {
                try {
                    return sendGetRequest(url, referer);
                } catch (IOException e) {}
            }
        }
        throw new IOException("Couldn't find image groups");
    }

    private Uri downloadOriginalImage(final JsonObject content,
                                      final String token,
                                      final String referer) throws IOException {
        Context context = getApplicationContext();

        final File originalFile;
        try {
            originalFile = new File(context.getCacheDir(), token);
        } catch (NullPointerException e) {
            // TODO
            throw new IOException("Couldn't get cache directory");
        }

        Response resp = getOriginalImageResponse(content, referer);

        final int status = resp.code();
        if (!resp.isSuccessful()) {
            throw new IOException("Unsuccessful request: " + status);
        }

        final FileOutputStream fileStream = new FileOutputStream(originalFile);
        final InputStream inputStream = resp.body().byteStream();
        boolean failed = false;
        try {
            final byte[] buffer = new byte[1024 * 50];
            int read;
            while ((read = inputStream.read(buffer)) > 0) {
                fileStream.write(buffer, 0, read);
            }
        } catch (IOException e) {
            failed = true;
        } finally {
            fileStream.close();
        }
        inputStream.close();
        if (failed) {
            originalFile.delete();
            throw new IOException("download failed: " + originalFile.getAbsolutePath());
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
