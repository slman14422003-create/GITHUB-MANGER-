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

# Structure
- DeviceFlow owns the device-flow code and its persistence (save/load/clear); LoginActivity only drives the UI.
- LogFmt.parse -> StepLog.build -> LogActivity; StepHeader (step row view) and LineNumberSpan (line-number gutter) are separate classes.
- LiveBoard.Listener.onStep: tapping a step in the run screen opens the log with that step expanded (extra "step").

# Next ideas
- Collapsible ##[group] sections inside a step.
- Build and test on a device: no Android SDK was available when these changes were written.
