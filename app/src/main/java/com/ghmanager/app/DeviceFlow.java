package com.ghmanager.app;

import android.content.Context;

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
        /** Wall-clock time (ms) at which GitHub stops accepting this code. */
        public long until = 0;
    }

    /** A definitive answer from GitHub (expired, denied...): polling must stop. Anything else is a hiccup to retry. */
    public static final class Fatal extends Exception {
        public Fatal(String code) {
            super(code);
        }
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
        c.until = System.currentTimeMillis() + c.expiresIn * 1000L;
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
        throw new Fatal(err);
    }

    private static final String KEY = "dev_flow";

    /** Keeps the pending code so a recreated screen can carry on with it. */
    public static void save(Context c, Code code) {
        try {
            JSONObject o = new JSONObject();
            o.put("dc", code.deviceCode);
            o.put("uc", code.userCode);
            o.put("uri", code.uri);
            o.put("until", code.until);
            o.put("iv", code.interval);
            Store.putPrefs(c, KEY, o.toString(), KEY + "_v", "1");
        } catch (Exception ignored) {
        }
    }

    public static void clear(Context c) {
        Store.putPrefs(c, KEY, "", KEY + "_v", "");
    }

    /** The saved code while it is still valid for at least 20 s, otherwise null. */
    public static Code load(Context c) {
        String raw = Store.rawPref(c, KEY);
        if (raw.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            Code code = new Code();
            code.until = o.optLong("until");
            code.deviceCode = o.optString("dc");
            code.userCode = o.optString("uc");
            code.uri = o.optString("uri", code.uri);
            code.interval = Math.max(5, o.optInt("iv", 5));
            if (code.deviceCode.isEmpty() || code.userCode.isEmpty()) return null;
            if (code.until - System.currentTimeMillis() < 20000L) {
                clear(c);
                return null;
            }
            return code;
        } catch (Exception e) {
            return null;
        }
    }
}
