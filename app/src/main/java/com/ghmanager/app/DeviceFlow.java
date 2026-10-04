package com.ghmanager.app;

import org.json.JSONObject;

/**
 * GitHub "device flow" sign-in. The user opens github.com/login/device in a browser and enters a short
 * code; GitHub's own page then offers every login method the account has (Google, e-mail and
 * password, passkey...). The app never sees the password: it only receives an access token.
 */
public final class DeviceFlow {
    private DeviceFlow() {
    }

    public static final String SCOPE = "repo workflow delete_repo read:org user admin:public_key";
    private static final String CODE_URL = "https://github.com/login/device/code";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";

    public static final class Code {
        public String deviceCode = "";
        public String userCode = "";
        public String uri = "https://github.com/login/device";
        public int expiresIn = 900;
        public int interval = 5;
    }

    public static final class Result {
        public String token;
        public boolean slowDown;
    }

    public static Code start(String clientId) throws Exception {
        String body = "client_id=" + GitHubApi.qe(clientId) + "&scope=" + GitHubApi.qe(SCOPE);
        JSONObject j = new JSONObject(GitHubApi.postForm(CODE_URL, body));
        if (j.has("error")) throw new Exception(j.optString("error_description", j.optString("error")));
        Code c = new Code();
        c.deviceCode = j.optString("device_code");
        c.userCode = j.optString("user_code");
        String uri = j.optString("verification_uri");
        if (!uri.isEmpty()) c.uri = uri;
        c.expiresIn = j.optInt("expires_in", 900);
        c.interval = Math.max(5, j.optInt("interval", 5));
        if (c.deviceCode.isEmpty() || c.userCode.isEmpty()) throw new Exception("GitHub");
        return c;
    }

    /** One poll. token == null means "not approved yet". Throws when the request failed for good. */
    public static Result poll(String clientId, String deviceCode) throws Exception {
        String body = "client_id=" + GitHubApi.qe(clientId) + "&device_code=" + GitHubApi.qe(deviceCode)
                + "&grant_type=" + GitHubApi.qe("urn:ietf:params:oauth:grant-type:device_code");
        JSONObject j = new JSONObject(GitHubApi.postForm(TOKEN_URL, body));
        Result r = new Result();
        String tok = j.optString("access_token");
        if (!tok.isEmpty()) {
            r.token = tok;
            return r;
        }
        String err = j.optString("error");
        if (err.isEmpty() || "authorization_pending".equals(err)) return r;
        if ("slow_down".equals(err)) {
            r.slowDown = true;
            return r;
        }
        throw new Exception(err);
    }
}
