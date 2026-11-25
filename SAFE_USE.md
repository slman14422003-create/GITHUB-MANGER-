# Safe Use and Misuse Protection

Arabic version: [SAFE_USE.ar.md](SAFE_USE.ar.md)

This page explains what GitHub Manager is meant for, what is forbidden, how the project protects you, and what to do if something goes wrong.

## 1. Intended use

GitHub Manager is a personal tool to manage GitHub accounts, repositories and workflows that you own or are authorized to manage. It talks to GitHub only, through the official GitHub API.

## 2. Forbidden use

- Accessing, changing or deleting repositories or accounts you do not own and are not authorized to manage.
- Using someone else's token, or asking a person to give you their token.
- Bulk, repeated or automated actions meant to spam, scrape, harass, inflate statistics or avoid GitHub rate limits.
- Publishing malware, stolen data, secrets or any content that breaks GitHub's Terms of Service or the law.
- Offering the Cloudflare Worker to other people as a public proxy, VPN or relay, or using it to reach hosts other than GitHub.
- Removing or bypassing the app's protections (app lock, APK seal, signature check) or redistributing a modified build as if it were the original.

## 3. What the project already does to protect you

- The access token is stored with the Android Keystore and is removed when you sign out.
- Optional app lock with the phone fingerprint, pattern or PIN.
- Screen capture is blocked by default (you can allow it in Settings, Security), backups are disabled, and traffic is HTTPS only with system certificates only.
- The APK can be sealed at build time. The app refuses to run if code or resources were changed after the build.
- In-app updates are accepted only if the SHA-256 matches and the signing certificate is the same as the installed app. An APK that uses this app's package name with a different certificate is refused.
- Links are restricted to https and the sign-in page to github.com.
- The Markdown preview opens only https links; other link types are ignored. Archives are extracted only inside the chosen folder, with limits on entry count and size.
- Tokens are masked in logs.
- The Worker refuses all requests without the secret MIRROR_KEY, refuses browser requests, forwards only to GitHub hosts, sends the token only to GitHub itself, keeps redirects inside allowed hosts, disables caching of responses and does not keep request logs.
- The workflow that creates the signing key refuses to run on a public repository.

## 4. Your responsibilities

Accounts and tokens
- Prefer the device flow sign-in over typing a token.
- If you use a token, give it the fewest scopes and the shortest expiry you can. Note that the device flow requests broad scopes (repo, workflow, delete_repo, read:org, user, admin:public_key). Revoke the app at github.com/settings/applications when you stop using it.
- Turn on two-factor authentication on GitHub and Cloudflare.
- Turn on the app lock.

Publishing this repository safely
- Before making a repository public, check the whole history, not only the latest files. Secrets removed in a later commit are still readable in history.
- Never commit: keystore files (.jks, .keystore, .p12), keystore.properties, signing-info.txt, tokens, passwords, .env files, the Worker MIRROR_KEY, Play Console snippets.
- In the repository settings turn on Secret scanning and Push protection, and keep all signing material only in repository secrets.
- Keep the generated signing key offline. Run the key workflow only on a private repository and delete its artifact right after downloading.
- Do not publish your own Worker address or key. Use placeholders in documentation.
- Release only APKs built by the official workflow, and publish the signing certificate SHA-256 so users can verify it.

Worker
- Use a random key of at least 32 characters, never reuse a password.
- Turn off the workers.dev address and use your own domain, and add a rate limiting rule.
- Do not set ALLOW_BROWSER.
- Change the key immediately if you suspect exposure, and give Workers edit access to nobody.

## 5. If something goes wrong

Token or key leaked
1. Revoke the token at github.com/settings/tokens and the app authorization at github.com/settings/applications.
2. Replace MIRROR_KEY with: npx wrangler secret put MIRROR_KEY.
3. Check your repositories, Actions runs and security log for unknown activity, then change your GitHub password.

Signing key leaked
1. Stop publishing releases, create a new key and tell users to reinstall from the official Releases page.
2. Remember that a new key means the old app cannot be updated in place.

Secret committed by mistake
1. Revoke or rotate it first, then remove it from history. Removing it from the latest commit is not enough.

Found a vulnerability or abuse
- Do not open a public issue with details. Use the Security tab of the repository, Report a vulnerability, after the owner enables private vulnerability reporting in Settings, Code security.
- Report abuse of GitHub itself at github.com/contact/report-abuse.

## 6. Disclaimer

The app is provided as is. You are responsible for what you do with your accounts, tokens and Worker, and for following the terms of GitHub and Cloudflare. The maintainers may remove access to this project from anyone who misuses it.
