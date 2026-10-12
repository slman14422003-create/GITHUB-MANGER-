---
name: github-manager-progress
description: Progress log of the GITHUB-MANGER Android app (Java/Views, com.ghmanager.app). Read before continuing any task on this project.
---

# Project
- Android app, Java + Views, package com.ghmanager.app, minSdk 24, target 36.
- Screens live in app/src/main/java/com/ghmanager/app; strings in res/values (Arabic default) and res/values-en.

# Done
1. Sign-in (device flow)
   - DeviceFlow.Fatal separates final GitHub answers from network hiccups; hiccups no longer stop polling.
   - LoginActivity: one poll in flight at a time, expired code is renewed automatically (max 4), flow state is saved
     in preferences (key dev_flow) and restored after the screen is recreated, token check retries 3 times and the
     account is saved even if the screen was destroyed meanwhile. The app never signs the user out on its own.
2. Home screen (ReposActivity)
   - Quiet sync every 15 s while the screen is open (skips re-render when the list signature is unchanged).
   - Relative times ("x minutes ago") refreshed every 10 s.
3. Actions job viewer (LogActivity, StepLog, LogFmt.parse)
   - Website-like layout: step rows with status dot, name, live duration; tap to open the step output with line numbers.
   - Lines are filed under steps by timestamp plus the "##[group]Run" / "Post Run" markers (StepLog.build).
   - Running step opens by itself and its clock ticks; failed steps open by default; filter "errors only" keeps original line numbers.
   - Menu: expand all / collapse all; long press copies a step or a block.

# Design tokens (res/values/dimens.xml)
- radius_xs 6, radius_s 14 (tiles, step rows), radius_m 22 (notes, popups, inputs, options), radius_l 28 (cards, sheets, dialogs, nav), radius_pill 100.
- screen_margin 14dp. Text sizes use whole steps only: 11, 12, 13, 14, 15, 16, 20, 24, 32 sp.
- Java code reads radii with Ui.radius(context, R.dimen.x); never hard-code a corner radius.

# Progress and dialogs
- Progress bars are iOS-style: 4dp rounded track (stroke colour), flat accent fill, eased animation through Ui.progress(bar, target); Ui.bar draws the same look without a ProgressBar.
- Dlg: message-only dialogs are centred (icon, title, text) and narrower (max 380dp); forms keep start-aligned titles; option lists stay bottom sheets.

# App icon, splash and animation
- Mark: a cat head with two ears inside a ring, torso lines meeting the ring and a tail ending in a small commit-style node; white outline, round caps and joins, on black.
- The same six paths (ring, head, torso1, torso2, tail, node, 240 viewport) are used by ic_mark_splash (stroke 6), ic_launcher_foreground (stroke 9, scaled to the adaptive safe zone) and ic_mark (stroke 8, text_primary colour).
- anim_mark draws the paths one after another with trimPathEnd (ring, head, torso, tail, node) plus a slight scale-in; splash duration 1000 ms.

# Security
- Already in place before this pass: Keystore-backed token, HTTPS-only with system CAs, no backups, FLAG_SECURE, tapjacking filter, zip-slip checks, SHA-256 + signer check for in-app updates, sealed APK, minified release.
- Added: Perms.installApk refuses an APK that uses this app's package name but another signing certificate (Integrity.safeToInstall); SafeUrl restricts openUrl to https links with no user-info and the sign-in page to github.com; checkout in build.yml no longer keeps credentials.
- Gradle wrapper checksum and every GitHub Action pinned by commit SHA are enforced by the verifier (the updater writes them on its first run).

# Workflows
- toolchain-update.yml: cron 1st and 16th of each month (about every 15 days) plus manual run. tools/toolchain_update.py moves Gradle, AGP, androidx libs, Kotlin BOM patch, build-tools and Actions to the newest STABLE versions of the same major line, pins Actions by SHA, writes the wrapper checksum, pushes toolchain/auto-update-YYYYMMDD and opens a PR; result handed over as artifact toolchain-update-info.
- verify-release.yml: starts when the updater finishes (or by hand). tools/verify_toolchain.py checks stable-only versions, Gradle consistency, existence in the official repositories, wrapper checksum, SHA pinning and a 3-day minimum release age; the branch may only touch toolchain files; dependencies resolve and assembleDebug builds; then the PR is merged ([skip release]) and build.yml is dispatched for a release.
- Needs once in the repo settings: Actions > General > "Allow GitHub Actions to create and approve pull requests". compileSdk/targetSdk and major version jumps stay manual.

# Structure
- DeviceFlow owns the device-flow code and its persistence (save/load/clear); LoginActivity only drives the UI.
- LogFmt.parse -> StepLog.build -> LogActivity; StepHeader (step row view) and LineNumberSpan (line-number gutter) are separate classes.
- LiveBoard.Listener.onStep: tapping a step in the run screen opens the log with that step expanded (extra "step").

# Next ideas
- Collapsible ##[group] sections inside a step.
- Build and test on a device: no Android SDK was available when these changes were written.
