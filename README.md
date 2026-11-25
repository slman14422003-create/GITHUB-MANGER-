# GitHub Manager

Languages: [English](README.md) | [العربية](README.ar.md) | [Français](README.fr.md) | [Español](README.es.md) | [Türkçe](README.tr.md) | [Русский](README.ru.md)

Safe use and misuse protection: [SAFE_USE.md](SAFE_USE.md)

GitHub Manager is a native Android app (Java) for managing GitHub repositories, files, Actions workflows, issues and releases from a phone.

## Features

Repositories and files
- List, search and filter your repositories, and clean up old ones.
- Upload whole folders from the phone with the structure kept. The app skips files that are already identical, honors .gitignore, shows a preview (new, changed, identical) before uploading, and resumes after a failure.
- Download single files, folders or whole repositories with pause, resume and cancel. Transfers keep running in the background with a notification.
- Device file manager with grid and list views, multi-select by dragging, search, and an in-app trash that empties itself after 30 days.
- Code editor with syntax highlighting, line numbers, undo and redo, code search, Markdown rendering and line diffs.

Branches and commits
- Create, merge and delete branches, and set the default branch.
- Browse commit history and view the diff of each commit.

GitHub Actions
- List workflows and watch runs live, with every job and step and a running timer.
- Log viewer with step grouping, line numbers and an errors-only filter.
- Start a workflow manually with inputs, and clean up old runs.
- Download artifacts, including automatic APK extraction.
- Build and publish a release from one screen.
- SEMO AI: an offline pre-check that runs before a workflow starts. It finds unbalanced brackets and safe formatting mistakes and fixes only what is certain. It makes no network call and uses no model.

Issues, releases and collaboration
- Issues: list, filter, create, comment and edit.
- Releases: view, publish and delete releases and download their assets.
- Collaborators: invite people and change their permission level.
- Repository insights: languages, traffic and contributors.
- Repository security settings: branch protection, secret scanning, push protection, Dependabot security updates and default Actions permissions.
- Encrypted Actions secrets and signing key setup.

Security and privacy
- The access token is stored with the Android Keystore.
- Optional app lock using the phone fingerprint, pattern or PIN.
- Screen capture is blocked inside the app.
- HTTPS only, trusting system certificates only. Backups are disabled.
- The APK can be sealed at build time, and the app checks the seal at start.
- In-app updates are verified by SHA-256 and by the signing certificate.

Other
- Optional Cloudflare Worker mirror for networks where GitHub is slow or blocked.
- Arabic and English interface, light and dark themes, multiple accounts.

## Activation

1. Install the APK
   - Download the APK from the Releases page of this repository and install it. Allow "Install unknown apps" for the app you used to open the file.
   - Android 7.0 (API 24) or newer is required.

2. Sign in (choose one)
   - Device flow: tap the Google or email option, GitHub shows a code, enter it on the official GitHub page. Your password never goes through the app. This needs a GitHub OAuth App with Device Flow enabled; its Client ID is public by design. Put it in the repository variable GH_OAUTH_CLIENT_ID (Settings, Secrets and variables, Actions, Variables) before building, or enter it in the app with the Client ID option.
   - Personal access token: create a token on GitHub and paste it in the app. Give it only the scopes you need. The device flow requests: repo, workflow, delete_repo, read:org, user, admin:public_key.

3. Optional protection
   - Settings, App lock: turn it on and choose the delay. A screen lock must already be set on the phone.

## Build from source with GitHub Actions

1. Create a permanent signing key. Run the workflow "Generate signing key" in a PRIVATE copy of the repository only. It refuses to run on a public repository. Download the artifact, store the files safely offline, then delete the artifact. If you prefer, run tools/make-keystore.sh on your own computer.
2. Add these repository secrets: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
3. Run the workflow "Build APK". The release appears on the Releases page.
4. Never upload the keystore, passwords or tokens to the repository.

## Deploy the Cloudflare Worker (optional)

The Worker forwards requests to GitHub hosts only and refuses every request that does not carry your secret key.

1. Install Node.js and open a terminal in the worker folder.
2. Log in: npx wrangler login
3. Deploy: npx wrangler deploy
4. Create a long random key: openssl rand -hex 32
5. Store it as a secret: npx wrangler secret put MIRROR_KEY and paste the key. Without this secret the Worker answers 503 to everything.
6. In the app open Settings, Mirror. Enter the Worker address and the key, then press Test.
7. Recommended: in the Cloudflare dashboard turn off the workers.dev address and use your own domain, and add a rate limiting rule. Enable two-factor authentication on your Cloudflare account.
8. If the key is ever exposed, replace it at once with the same command.

## Staying within GitHub rules

Read SAFE_USE.md for the full protection and misuse policy.
