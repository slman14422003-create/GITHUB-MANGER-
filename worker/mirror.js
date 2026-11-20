// GitHub mirror for Cloudflare Workers (PRIVATE by default).
//
// Used by the GitHub Manager app (Settings > Mirror). It forwards requests to GitHub hosts only.
//
//   https://YOUR-WORKER/api.github.com/user                      GitHub API
//   https://YOUR-WORKER/github.com/OWNER/REPO/archive/main.zip   any github.com page / file
//   https://YOUR-WORKER/repo/OWNER/REPO[/REF]                    repository as a .zip
//   https://YOUR-WORKER/release/OWNER/REPO/TAG/ASSET             release asset (TAG may be "latest")
//   https://YOUR-WORKER/status                                   GitHub status (githubstatus.com summary JSON)
//
// Privacy rules enforced here:
//   1. The secret MIRROR_KEY is REQUIRED. Without it the Worker refuses everything (it never runs open).
//      Every request must send the header  X-Mirror-Key: <key>.
//   2. Browsers are refused: no CORS, and any request that carries Origin / Sec-Fetch-* headers is
//      rejected, so a web page or the browser console cannot call the Worker (the app never sends them).
//      Set the variable ALLOW_BROWSER=1 only if you really want to open links in a browser (then the key
//      may also be passed as ?k=KEY).
//   3. Only GitHub hosts are reachable, the token goes only to GitHub itself, and redirects can never
//      leave the allowed hosts.

const HOSTS = [
  /^github\.com$/,
  /^([a-z0-9-]+\.)+github\.com$/,
  /^([a-z0-9-]+\.)*githubusercontent\.com$/,
  /^([a-z0-9-]+\.)*githubstatus\.com$/,
  /^productionresultssa\d+\.blob\.core\.windows\.net$/,
];

// the token is only forwarded to GitHub itself, never to the storage hosts GitHub redirects to
const TOKEN_HOSTS = /^(api\.github\.com|uploads\.github\.com|github\.com)$/;

const STATUS_URL = "https://www.githubstatus.com/api/v2/summary.json";

const METHODS = new Set(["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"]);
const DROP_REQUEST = /^(host|cf-|x-forwarded-|x-real-ip|x-mirror-key|connection|content-length$|origin$|referer$|cookie$|sec-)/i;
const DROP_RESPONSE = /^(set-cookie|strict-transport-security|alt-svc)$/i;

const allowed = (host) => HOSTS.some((re) => re.test(host));

const SAFE_HEADERS = {
  "content-type": "text/plain; charset=utf-8",
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
};

function reply(status, text) {
  return new Response(text, { status, headers: SAFE_HEADERS });
}

// compares two strings without leaking, through timing, how many leading characters matched
async function sameSecret(a, b) {
  const enc = new TextEncoder();
  const [x, y] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(a)),
    crypto.subtle.digest("SHA-256", enc.encode(b)),
  ]);
  const u = new Uint8Array(x);
  const v = new Uint8Array(y);
  let diff = 0;
  for (let i = 0; i < u.length; i++) diff |= u[i] ^ v[i];
  return diff === 0;
}

// friendly shortcuts -> real GitHub URL (or null when the path is not a shortcut)
function shortcut(path) {
  const p = path.split("/").filter(Boolean).map(decodeURIComponent);
  const safe = (s) => encodeURIComponent(s);
  // githubstatus.com is blocked in some countries: the app asks the Worker for it instead
  if (p[0] === "status" && p.length === 1) return STATUS_URL;
  if (p[0] === "repo" && p.length >= 3) {
    const ref = p.length > 3 ? p.slice(3).map(safe).join("/") : "HEAD";
    return `https://github.com/${safe(p[1])}/${safe(p[2])}/archive/${ref}.zip`;
  }
  if (p[0] === "release" && p.length >= 5) {
    const [, owner, repo, tag] = p;
    const asset = p.slice(4).map(safe).join("/");
    return tag === "latest"
      ? `https://github.com/${safe(owner)}/${safe(repo)}/releases/latest/download/${asset}`
      : `https://github.com/${safe(owner)}/${safe(repo)}/releases/download/${safe(tag)}/${asset}`;
  }
  return null;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const browserMode = env.ALLOW_BROWSER === "1";

    // The key is mandatory: a Worker without MIRROR_KEY would be an open proxy for anyone on the internet.
    if (!env.MIRROR_KEY) {
      return reply(503, "Mirror is not configured: add the secret MIRROR_KEY.");
    }

    // No CORS at all. Pre-flight requests are refused, so no web page can use this Worker.
    if (request.method === "OPTIONS") return reply(403, "Forbidden");

    // Browser traffic (a page, the console, a link typed in the address bar) always carries these headers.
    if (!browserMode) {
      if (request.headers.has("origin") || request.headers.has("sec-fetch-site") ||
          request.headers.has("sec-fetch-mode") || request.headers.has("sec-fetch-dest")) {
        return reply(403, "Forbidden");
      }
    }

    if (!METHODS.has(request.method)) return reply(405, "Method not allowed");

    const sent = request.headers.get("x-mirror-key") || (browserMode ? url.searchParams.get("k") : "") || "";
    if (!(await sameSecret(sent, env.MIRROR_KEY))) return reply(403, "Forbidden");
    url.searchParams.delete("k");

    if (url.pathname === "/" || url.pathname === "/__health") {
      return reply(200, "GitHub mirror is running.");
    }

    const target = shortcut(url.pathname);
    let upstream;
    if (target) {
      upstream = new URL(target);
    } else {
      const m = url.pathname.match(/^\/([^/]+)(\/.*)?$/);
      if (!m) return reply(400, "Bad path");
      const host = m[1].toLowerCase();
      if (!allowed(host)) return reply(403, "Host not allowed");
      upstream = new URL("https://" + host + (m[2] || "/"));
      upstream.search = url.search;
    }

    const headers = new Headers();
    for (const [k, v] of request.headers) {
      if (!DROP_REQUEST.test(k)) headers.set(k, v);
    }
    if (!TOKEN_HOSTS.test(upstream.hostname)) headers.delete("authorization");

    if (!headers.has("user-agent")) headers.set("user-agent", "GitHubManagerApp");

    // the status page is public and changes slowly: a 30 second edge cache keeps it fast and light
    const isStatus = upstream.hostname === "www.githubstatus.com" || upstream.hostname === "githubstatus.com";
    const cache = isStatus && request.method === "GET" ? { cacheTtl: 30, cacheEverything: true } : undefined;

    const hasBody = request.method !== "GET" && request.method !== "HEAD";
    let res;
    try {
      res = await fetch(
        new Request(upstream.toString(), {
          method: request.method,
          headers,
          body: hasBody ? request.body : undefined,
          redirect: "manual",
        }),
        cache ? { cf: cache } : undefined
      );
    } catch (_) {
      return reply(502, "Upstream unreachable");
    }

    const out = new Headers();
    for (const [k, v] of res.headers) {
      if (!DROP_RESPONSE.test(k)) out.set(k, v);
    }
    out.set("cache-control", "no-store");
    out.set("x-content-type-options", "nosniff");
    out.set("referrer-policy", "no-referrer");
    // files from GitHub are never rendered as pages from this origin
    out.set("content-security-policy", "default-src 'none'; sandbox");

    // keep every redirect (release assets, archives, logs, artifacts) inside the mirror
    const loc = res.headers.get("location");
    if (loc) {
      try {
        const next = new URL(loc, upstream);
        if (!allowed(next.hostname)) return reply(502, "Redirect to a host that is not allowed");
        out.set("location", `${url.origin}/${next.hostname}${next.pathname}${next.search}`);
      } catch (_) {
        return reply(502, "Bad redirect");
      }
    }

    return new Response(res.body, { status: res.status, statusText: res.statusText, headers: out });
  },
};
