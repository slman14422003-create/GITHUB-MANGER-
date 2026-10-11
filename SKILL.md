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

# Next ideas
- Open the log directly on a step tapped from the live board in RunDetailActivity (extra "step" is already supported by LogActivity).
- Collapsible ##[group] sections inside a step.
- Build and test on a device: no Android SDK was available when these changes were written.
