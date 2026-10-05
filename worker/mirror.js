// GitHub mirror for Cloudflare Workers.
//
// Used by the GitHub Manager app (Settings > Mirror) and directly from a browser:
//   https://YOUR-WORKER/api.github.com/user                      GitHub API
//   https://YOUR-WORKER/github.com/OWNER/REPO/archive/main.zip   any github.com page / file
//   https://YOUR-WORKER/repo/OWNER/REPO[/REF]                    repository as a .zip (default branch if no REF)
//   https://YOUR-WORKER/release/OWNER/REPO/TAG/ASSET             release asset  (TAG may be "latest")
//
// Optional secret MIRROR_KEY: when set, every request must send the header  X-Mirror-Key: <key>
// (or the query parameter  ?k=<key>  for plain browser links).

const HOSTS = [
  /^github\.com$/,
  /^([a-z0-9-]+\.)+github\.com$/,
  /^([a-z0-9-]+\.)*githubusercontent\.com$/,
  /^([a-z0-9-]+\.)*githubstatus\.com$/,
  /^productionresultssa\d+\.blob\.core\.windows\.net$/,
];

// the token is only forwarded to GitHub itself, never to the storage hosts GitHub redirects to
const TOKEN_HOSTS = /^(api\.github\.com|uploads\.github\.com|github\.com)$/;

const DROP_REQUEST = /^(host|cf-|x-forwarded-|x-real-ip|x-mirror-key|connection|content-length$)/i;
const DROP_RESPONSE = /^(set-cookie|content-security-policy|x-frame-options|strict-transport-security)$/i;

const allowed = (host) => HOSTS.some((re) => re.test(host));

function reply(status, text) {
  return new Response(text, {
    status,
    headers: { "content-type": "text/plain; charset=utf-8", "access-control-allow-origin": "*" },
  });
}

// friendly shortcuts -> real GitHub URL (or null when the path is not a shortcut)
function shortcut(path) {
  const p = path.split("/").filter(Boolean);
  if (p[0] === "repo" && p.length >= 3) {
    const ref = p.length > 3 ? p.slice(3).join("/") : "HEAD";
    return `https://github.com/${p[1]}/${p[2]}/archive/${ref}.zip`;
  }
  if (p[0] === "release" && p.length >= 5) {
    const [, owner, repo, tag] = p;
    const asset = p.slice(4).join("/");
    return tag === "latest"
      ? `https://github.com/${owner}/${repo}/releases/latest/download/${asset}`
      : `https://github.com/${owner}/${repo}/releases/download/${tag}/${asset}`;
  }
  return null;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "access-control-allow-origin": "*",
          "access-control-allow-methods": "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS",
          "access-control-allow-headers": "*",
        },
      });
    }

    if (url.pathname === "/" || url.pathname === "/__health") {
      return reply(200, "GitHub mirror is running.");
    }

    if (env.MIRROR_KEY) {
      const sent = request.headers.get("x-mirror-key") || url.searchParams.get("k");
      if (sent !== env.MIRROR_KEY) return reply(403, "Forbidden");
    }
    url.searchParams.delete("k");

    let target = shortcut(url.pathname);
    let upstream;
    if (target) {
      upstream = new URL(target);
    } else {
      const m = url.pathname.match(/^\/([^/]+)(\/.*)?$/);
      if (!m) return reply(400, "Bad path");
      const host = m[1].toLowerCase();
      if (!allowed(host)) return reply(403, "Host not allowed: " + host);
      upstream = new URL("https://" + host + (m[2] || "/"));
      upstream.search = url.search;
    }

    const headers = new Headers();
    for (const [k, v] of request.headers) {
      if (!DROP_REQUEST.test(k)) headers.set(k, v);
    }
    if (!TOKEN_HOSTS.test(upstream.hostname)) headers.delete("authorization");

    const hasBody = !["GET", "HEAD"].includes(request.method);
    const res = await fetch(
      new Request(upstream.toString(), {
        method: request.method,
        headers,
        body: hasBody ? request.body : undefined,
        redirect: "manual",
      })
    );

    const out = new Headers();
    for (const [k, v] of res.headers) {
      if (!DROP_RESPONSE.test(k)) out.set(k, v);
    }
    out.set("access-control-allow-origin", "*");

    // keep every redirect (release assets, archives, logs, artifacts) inside the mirror
    const loc = res.headers.get("location");
    if (loc) {
      try {
        const next = new URL(loc, upstream);
        if (allowed(next.hostname)) {
          out.set("location", `${url.origin}/${next.hostname}${next.pathname}${next.search}`);
        }
      } catch (_) {
        /* leave the header as it is */
      }
    }

    return new Response(res.body, { status: res.status, statusText: res.statusText, headers: out });
  },
};
