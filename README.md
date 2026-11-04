# HomeDesk (Android)

Small Android app that opens your web apps full screen, with no browser address bar or tabs.
Starts with an empty list: tap + to add your web apps, then edit, reorder or delete them in HomeDesk.
In HomeDesk every saved web page is called an **app**.

## Install on the phone
1. Download `HomeDesk-<version>.apk` from the [Releases](../../releases) page on the phone (or copy a local build
   of `HomeDesk.apk` to it).
2. Open it from the phone's file manager. Android asks to allow installs from that app. Allow it once.
3. Updates: build again and install the new APK over the old one. Your apps, usage history and logins stay.
   This only works while `keystore/` (the signing key) is kept. Without it Android refuses the update.

## Using it
- Opening HomeDesk from its icon shows the list, or goes straight to the **start app** if you picked one with the
  "Open automatically when HomeDesk starts" option in an app's Edit box. Back › All apps shows the list.
- Tap an app to open it. Full screen is off by default. The round button on the page toggles it: tap it to
  switch, drag it to move it out of the way (it stays where you leave it). Back › Full screen does the same.
- Each app gets its own entry in Android's Recents, so a remote desktop and a webmail can be open at the same time and switching between them doesn't reload either one.
- **Back** opens a menu (Go back a page / Full screen / Reload / Hard reload / All apps / Close) instead of leaving the page, so a
  stray back swipe can't drop an RDP session.
- **Hard reload (ignore cache)** is like Ctrl+Shift+R: it removes the page's service workers and Cache Storage, then reloads
  with the HTTP cache bypassed, so changed CSS/JS files are fetched fresh. Logins and cookies stay.
- "Go back a page" works like the browser back button. Cloudflare Access login pages are dropped from the history.
  Opening an app has no slide animation, and its renderer is marked important so Android keeps it alive in the background.
- Swipe from the top or bottom edge to show the status and navigation bars for a moment.
- The list shows each app's name and usage (time on screen today, last used), not its web address.
- App **⋮** menu (or long-press): Edit, Usage, Add to home screen (an icon that opens the app directly), Move up/down, Delete.
  Long-pressing the HomeDesk icon also lists the first 4 apps.
- **Usage** (top bar of the list): on-screen time for Today / 7 days / 30 days, a daily chart stacked by app colour,
  and apps ranked by time with how often each was opened. Tap an app there (or ⋮ › Usage) for that app alone.
  Only time an app is on screen counts (a card waiting in Recents doesn't). The data stays on the phone for 90 days;
  ⋮ › Clear usage history on the Usage screen deletes it, and deleting an app deletes its history.
- Per-app options: full screen, keep screen on, open at start, and **keyboard covers the page**. Turn that last one on
  for remote desktop pages such as Guacamole, whose mobile layouts expect the keyboard to overlay the page (like Chrome's
  `interactive-widget=overlays-content`), and the app blurs the text field when the keyboard closes.
  Leave it off for normal web pages: the page then shrinks to the area above the keyboard.
- **Hold on a link** for a menu: Open in new tab / Open in default browser / Copy link address.
  A new tab is its own window (own card in Recent apps) using the same app's settings. While a tab shows a page
  outside the app's own address (not starting with it), it gets a browser bar: back, forward, an editable address
  and reload; inside the app's address the bar hides. Tabs also have "Open in default browser" in the Back menu.
- **Clipboard:** Android's WebView never shows Chrome's "Allow clipboard?" prompt and blocks pages from reading the
  clipboard. HomeDesk adds a bridge (`ClipboardBridge`, only on each app's own origin) for navigator.clipboard
  readText/writeText/read/write and the clipboard permission query. Reading asks once per app (Allow / Block /
  Not now); the choice is the app's **Clipboard** setting in Edit (Ask / Allow / Block). Copying from the page to the
  phone needs no prompt unless the app is blocked. Android shows its own "pasted from clipboard" notice on reads.
- **Notifications:** WebView has no web Notification API (pages just see "denied"). `NotificationBridge` replaces
  window.Notification / registration.showNotification / the "notifications" permission query on each app's origin
  and posts real Android notifications. When a page asks: HomeDesk asks Allow / Block / Not now (the app's
  **Notifications** setting in Edit), then Android asks its own permission once (if refused for good, HomeDesk offers
  Android's settings). Same tag replaces the earlier notification. Tapping one brings back that window and fires the
  page's onclick. Works while the window is open (also in the background); no push after it is closed.
- **Microphone:** WebView refuses every getUserMedia request unless the app grants it, so voice recording and
  Guacamole audio input never worked. `MediaPermissions` now answers it like Chrome: when a page asks, HomeDesk asks
  Allow / Block / Not now (the app's **Microphone** setting in Edit: Ask / Allow / Block), then Android asks its own
  microphone permission once (if refused for good, HomeDesk offers Android's settings). Pages outside the app's own
  address (tabs) are asked every time and never remembered. Camera requests are refused.
- **Sound:** pages can play sound without a tap first (autoplay is on), also while the window is in the background.
- **Read aloud:** WebView has no Web Speech synthesis, so a page's "listen" / read-aloud button stayed silent.
  `SpeechBridge` replaces `speechSynthesis` and `SpeechSynthesisUtterance` on every page with Android's text-to-speech
  (the phone's TTS engine and its voices; language, voice, rate, pitch and volume are honoured; plays at media volume).
  No permission needed. Speech stops when the page changes. Pause/resume are not supported (Android TTS has none).
- HTTP basic auth gets a sign-in dialog with "Remember on this phone". The login is stored in
  the app's private storage (not encrypted). If a saved login is rejected, the dialog shows again.
- Pages keep running in the background (not paused), so remote sessions stay connected while you use other apps,
  until Android itself stops the app or the phone sleeps.
- Downloads go to the phone's Download folder. File uploads use the normal Android file picker.

## Install over Wi-Fi from a PC
Pair the phone once under Developer options › Wireless debugging. With Wireless debugging on, the port is shown on
that screen and changes each time:
```
adb connect <phone-ip>:<port>
adb install -r HomeDesk.apk
```
Installing restarts the app, so open pages reload and remote sessions reconnect.

## Build (Windows, needs only Docker Desktop)
```
powershell -File build.ps1
```
Builds in a `gradle:8.11.1-jdk17` container. The Android SDK and Gradle caches live in the Docker volumes
`homedesk-android-sdk` and `homedesk-gradle`. The first build downloads them, later builds are quick.
`docker/check.sh` verifies the APK signature and runs lint.
Bump `versionCode` in `app/build.gradle` for each release you install over an older one.

## Releases (GitHub Actions)
`.github/workflows/build.yml` builds the APK on every push and pull request (download it from the run's
artifacts). Pushing a tag `v*` also publishes a GitHub release with `HomeDesk-<version>.apk`:
```
git tag v1.9
git push origin v1.9
```
Releases are signed with your own key from these repository secrets (Settings › Secrets and variables › Actions),
so they install over earlier builds:
- `KEYSTORE_BASE64`: `keystore/release.jks` as base64 (PowerShell:
  `[Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore\release.jks"))`)
- `KEYSTORE_PASSWORD`: the `password` value in `keystore/keystore.properties`
- `KEY_ALIAS`: the `keyAlias` value (`homedesk`)

Without the secrets, branch builds are unsigned and tag builds fail. `keystore/` is never committed.

## Code
Plain Java; one library, androidx.webkit (origin-restricted JS bridge for the clipboard). `app/src/main/java/com/papyper/homedesk/`:
- `MainActivity` is the app list and add/edit dialog.
- `WebActivity` is the full-screen WebView: insets/keyboard, back menu, basic auth, downloads, file picker,
  restarting after a renderer crash.
- `SiteStore` holds the apps as JSON in SharedPreferences (defaults on first start). In code an app is a `Site`.
- `UsageStore` records on-screen time per app and day (WebActivity counts resumed time, saved every minute).
- `UsageActivity` + `UsageChart` are the Usage screen.
- `Icons` makes the letter icons, Recents label, and home-screen/app-icon shortcuts.
