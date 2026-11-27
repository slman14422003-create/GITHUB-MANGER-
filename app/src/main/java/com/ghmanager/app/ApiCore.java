package com.ghmanager.app;

import android.content.Context;

import com.ghmanager.app.GitHubApi.ApiException;
import com.ghmanager.app.GitHubApi.Progress;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class ApiCore {
    final ApiAuth auth;
    private final Context ctx;

    ApiCore(String token) {
        this.auth = new ApiAuth(token);
        this.ctx = null;
    }

    ApiCore(Context c) {
        this.auth = new ApiAuth(c);
        this.ctx = c.getApplicationContext();
    }

    static ApiException toException(int code, String resp) {
        return ApiParse.toException(code, resp);
    }

    static long rateWait(HttpURLConnection c) {
        return ApiParse.rateWaitMs(c.getHeaderField("Retry-After"), c.getHeaderField("X-RateLimit-Remaining"),
                c.getHeaderField("X-RateLimit-Reset"), System.currentTimeMillis());
    }

    JSONObject obj(String path) throws Exception {
        return new JSONObject(request("GET", path, null));
    }

    JSONArray arr(String path) throws Exception {
        return new JSONArray(request("GET", path, null));
    }

    static final String BASE = "https://api.github.com";

    static final String UPLOAD_BASE = "https://uploads.github.com";

    static final String JSON = "application/vnd.github+json";

    static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        final int cap = 32 * 1024 * 1024;   // a hostile mirror must not be able to exhaust memory
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
            if (bos.size() > cap) {
                is.close();
                throw new IOException("Response too large");
            }
        }
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Encodes each segment of a path, keeping the slashes. */
    public static String enc(String path) throws IOException {
        StringBuilder sb = new StringBuilder();
        String[] parts = path.split("/");
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(URLEncoder.encode(p, "UTF-8").replace("+", "%20"));
        }
        return sb.toString();
    }

    /** Encodes a query value. */
    public static String qe(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    public static String repo(String o, String r) {
        return "/repos/" + seg(o) + "/" + seg(r);
    }

    /** One path segment, encoded, so an owner or repo name can never add path parts or a query. */
    static String seg(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    private static final ThreadLocal<Boolean> DIRECT = new ThreadLocal<>();

    /** For the calling thread only: the next downloads skip the Mirror proxy and talk to GitHub directly. */
    public static void directDownload(boolean on) {
        if (on) DIRECT.set(Boolean.TRUE);
        else DIRECT.remove();
    }

    HttpURLConnection open(String method, String url, String accept, boolean api) throws IOException {
        // never talk plain HTTP (a redirect to http:// must not downgrade a download)
        if (!url.startsWith("https://")) throw new IOException("Blocked non-HTTPS URL");
        if (RepoAccess.writeBlocked(method, url)) throw new IOException(RepoAccess.message(ctx));
        if (api && !Mirror.isMirrored(url)) {
            // the token may only ever be sent to GitHub's own API hosts (a crafted path such as
            // "@evil.com" must never be able to redirect it somewhere else)
            String host = new URL(url).getHost().toLowerCase(java.util.Locale.ROOT);
            if (!host.equals("api.github.com") && !host.equals("uploads.github.com")) {
                throw new IOException("Blocked host");
            }
        }
        final String real = Boolean.TRUE.equals(DIRECT.get()) ? url : Mirror.map(url);
        HttpURLConnection c = (HttpURLConnection) new URL(real).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        if (Mirror.isMirrored(real) && !Mirror.key().isEmpty()) {
            c.setRequestProperty("X-Mirror-Key", Mirror.key());
        }
        if (api) {
            c.setRequestProperty("Authorization", "Bearer " + auth.bearer());
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        }
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("User-Agent", "GitHubManagerApp");
        return c;
    }

    void guardRepo(String o, String r) throws ApiException {
        if (RepoAccess.writeBlocked("POST", repo(o, r))) {
            throw new ApiException(403, RepoAccess.message(ctx));
        }
    }

    String request(String method, String path, JSONObject body) throws IOException, ApiException {
        if (RepoAccess.writeBlocked(method, path)) throw new ApiException(403, RepoAccess.message(ctx));
        final String cat = Verify.category(method, path, body);
        if (cat == null) return send(method, path, body);
        final Context lc = ctx != null ? ctx : App.context();
        try {
            Verify.require(cat);
        } catch (IOException e) {
            AuditLog.api(lc, auth.accId(), cat, method, path, "denied", 0);
            throw e;
        }
        try {
            String r = send(method, path, body);
            AuditLog.api(lc, auth.accId(), cat, method, path, "ok", 200);
            return r;
        } catch (ApiException e) {
            AuditLog.api(lc, auth.accId(), cat, method, path, "failed", e.code);
            throw e;
        } catch (IOException e) {
            AuditLog.api(lc, auth.accId(), cat, method, path, "failed", 0);
            throw e;
        }
    }

    private String send(String method, String path, JSONObject body) throws IOException, ApiException {
        try {
            return requestOnce(method, path, body);
        } catch (ApiException e) {
            if (e.code == 401 && auth.renew()) return requestOnce(method, path, body);
            throw e;
        }
    }

    private static void pause(long ms) throws IOException {
        try {
            Thread.sleep(ms + (long) (Math.random() * 250));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }

    /** Retries reads on network hiccups and 5xx with a growing pause, and waits out short rate limits. */
    private String requestOnce(String method, String path, JSONObject body) throws IOException, ApiException {
        boolean read = method.equals("GET");
        int tries = 0;
        boolean waited = false;
        for (; ; ) {
            try {
                return exchange(method, path, body);
            } catch (ApiException e) {
                boolean server = e.code == 500 || e.code == 502 || e.code == 503 || e.code == 504;
                if (read && server && tries < 2) {
                    pause(600L << tries++);
                    continue;
                }
                if (e.waitMs > 0) {
                    if (e.waitMs <= 20000L && !waited) {
                        waited = true;
                        pause(e.waitMs);
                        continue;
                    }
                    ApiException r = new ApiException(e.code, "Rate limit reached. Try again in "
                            + Math.max(1, e.waitMs / 1000L) + " s");
                    r.waitMs = e.waitMs;
                    throw r;
                }
                throw e;
            } catch (IOException e) {
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (m.startsWith("Blocked") || m.startsWith("Response too large") || m.equals("Interrupted")) throw e;
                if (read && tries < 2) {
                    pause(600L << tries++);
                    continue;
                }
                if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException) {
                    throw new IOException("No internet connection");
                }
                throw e;
            }
        }
    }

    private String exchange(String method, String path, JSONObject body) throws IOException, ApiException {
        boolean needsBody = method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
        if (body == null && needsBody) body = new JSONObject();
        byte[] data = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        String url = BASE + path;
        final boolean isGet = method.equals("GET");
        final String ckey = auth.accId() + "|" + path;
        final String[] hit = isGet ? ApiEtags.get(ckey) : null;
        // Redirects are followed by hand: every hop is checked against the allowed hosts again, so the
        // token can never be forwarded to another site by a redirect.
        for (int hop = 0; ; hop++) {
            HttpURLConnection c = open(method, url, JSON, true);
            c.setInstanceFollowRedirects(false);
            if (hit != null && hop == 0) c.setRequestProperty("If-None-Match", hit[0]);
            boolean reusable = false;
            try {
                if (data != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setFixedLengthStreamingMode(data.length);
                    OutputStream os = c.getOutputStream();
                    os.write(data);
                    os.close();
                }
                int code = c.getResponseCode();
                if (ctx != null) TokenExpiry.seen(ctx, auth.accId(), c.getHeaderField("github-authentication-token-expiration"));
                if (code >= 300 && code < 400 && code != 304 && hop < 3) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new ApiException(code, "Redirect without Location");
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                if (code == 304 && hit != null) {
                    // unchanged since the last answer: GitHub sends no body and does not count it as a request
                    try {
                        c.getInputStream().close();
                    } catch (Exception ignored) {
                    }
                    reusable = true;
                    return hit[1];
                }
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String resp = is == null ? "" : readAll(is);
                if (code >= 400) {
                    ApiException ex = toException(code, resp);
                    if (code == 403 || code == 429) ex.waitMs = rateWait(c);
                    throw ex;
                }
                if (isGet) {
                    String tag = c.getHeaderField("ETag");
                    if (tag != null && !tag.isEmpty() && resp.length() < 512 * 1024) {
                        ApiEtags.put(ckey, new String[]{tag, resp});
                    }
                }
                reusable = true;
                return resp;
            } finally {
                // A fully read answer leaves the socket in the keep-alive pool: the next request skips the
                // TCP/TLS handshake (a big saving through the proxy). Only a failed call is cut.
                if (!reusable) c.disconnect();
            }
        }
    }

    /**
     * Opens a download. The first hop carries the token; any redirect (logs, artifacts,
     * assets, zipballs) is followed WITHOUT the token because the target URL is pre-signed.
     */
    private static final ThreadLocal<Long> RANGE_FROM = new ThreadLocal<>();

    public static void rangeFrom(long from) {
        if (from > 0) RANGE_FROM.set(from);
        else RANGE_FROM.remove();
    }

    private static void applyRange(HttpURLConnection c) {
        Long f = RANGE_FROM.get();
        if (f != null && f > 0) {
            c.setRequestProperty("Range", "bytes=" + f + "-");
            c.setRequestProperty("Accept-Encoding", "identity");
        }
    }

    public HttpURLConnection openDownload(String path, String accept) throws Exception {
        HttpURLConnection c = open("GET", BASE + path, accept, true);
        c.setInstanceFollowRedirects(false);
        applyRange(c);
        int code = c.getResponseCode();
        int hops = 0;
        while (code >= 300 && code < 400 && hops < 5) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (loc == null) throw new ApiException(code, "Redirect without Location");
            c = open("GET", loc, "*/*", false);
            c.setInstanceFollowRedirects(false);
            applyRange(c);
            code = c.getResponseCode();
            hops++;
        }
        if (code >= 400) {
            InputStream es = c.getErrorStream();
            String resp = es == null ? "" : readAll(es);
            long wait = code == 403 || code == 429 ? rateWait(c) : 0;
            c.disconnect();
            ApiException ex = toException(code, resp);
            ex.waitMs = wait;
            throw ex;
        }
        return c;
    }

    public static long copy(InputStream in, OutputStream out, long total, Progress p) throws IOException {
        byte[] buf = new byte[131072];
        long done = 0;
        long last = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            done += n;
            if (p != null) {
                long now = System.currentTimeMillis();
                if (now - last > 150) {
                    p.on(done, total);
                    last = now;
                }
            }
        }
        out.flush();
        if (p != null) p.on(done, total);
        return done;
    }

    /** Unauthenticated form POST (GitHub device flow). Returns the body whatever the status is. */
    public static String postForm(String url, String form) throws Exception {
        if (!url.startsWith("https://")) throw new IOException("Blocked non-HTTPS URL");
        String real = Mirror.map(url);
        HttpURLConnection c = (HttpURLConnection) new URL(real).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", "GitHubManagerApp");
        if (Mirror.isMirrored(real) && !Mirror.key().isEmpty()) {
            c.setRequestProperty("X-Mirror-Key", Mirror.key());
        }
        byte[] data = form.getBytes(StandardCharsets.UTF_8);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(data.length);
        OutputStream os = c.getOutputStream();
        os.write(data);
        os.close();
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String resp = is == null ? "" : readAll(is);
        c.disconnect();
        return resp;
    }

    /** Small unauthenticated download (avatars). Returns null on any failure. */
    public static byte[] fetchBytes(String url, int maxBytes) {
        return fetchBytes(url, maxBytes, false, 15000);
    }

    /**
     * @param forceMirror use the saved mirror even when its switch is off
     * @param connectMs   how long to wait for the connection before giving up
     */
    public static byte[] fetchBytes(String url, int maxBytes, boolean forceMirror, int connectMs) {
        try {
            if (!url.startsWith("https://")) return null;
            String real = forceMirror ? Mirror.mapForced(url) : Mirror.map(url);
            HttpURLConnection c = (HttpURLConnection) new URL(real).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "GitHubManagerApp");
            if (Mirror.pointsAtMirror(real) && !Mirror.key().isEmpty()) {
                c.setRequestProperty("X-Mirror-Key", Mirror.key());
            }
            if (c.getResponseCode() != 200) {
                c.disconnect();
                return null;
            }
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > maxBytes) {
                    is.close();
                    c.disconnect();
                    return null;
                }
            }
            is.close();
            c.disconnect();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
