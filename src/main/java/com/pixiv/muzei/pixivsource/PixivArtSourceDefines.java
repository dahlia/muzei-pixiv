package com.pixiv.muzei.pixivsource;

public class PixivArtSourceDefines {
    // app strings
    public static final String APP_OS = "ios";
    public static final String APP_OS_VERSION = "10.3.1";
    public static final String APP_VERSION = "6.7.1";
    public static final String USER_AGENT =
            "PixivIOSApp/6.7.1 (iOS 10.3.1; iPhone8,1)";
    public static final String CLIENT_ID = "bYGKuGVw91e0NMfPGp44euvGt59s";
    public static final String CLIENT_SECRET = "HP3RmkgAmEGro0gn1x9ioawQE8WMfvLXDz3ZqxpK";

    // urls
    private static final String PIXIV_API_HOST = "https://app-api.pixiv.net";
    private static final String PIXIV_RANKING_URL = PIXIV_API_HOST + "/v1/illust/ranking";

    public static final String DAILY_RANKING_URL =
            PIXIV_RANKING_URL + "?mode=day";
    public static final String WEEKLY_RANKING_URL =
            PIXIV_RANKING_URL + "?mode=week";
    public static final String MONTHLY_RANKING_URL =
            PIXIV_RANKING_URL + "?mode=month";
    public static final String BOOKMARK_URL =
            PIXIV_API_HOST + "/v1/user/bookmarks/illust";
    public static final String FOLLOW_URL =
            PIXIV_API_HOST + "/v2/illust/follow";
    public static final String OAUTH_URL =
            "https://oauth.secure.pixiv.net/auth/token";
}
