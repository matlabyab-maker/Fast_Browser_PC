# Fast_Browser_PC

Android PC-style compact browser project.

## Search engines
The address bar starts with Google as the default search engine. The engine button shows the active engine's short icon. Tap it to select another engine or add a custom search engine.

Built-in engines:
1. Google
2. Bing
3. DuckDuckGo
4. Brave Search
5. Startpage
6. Gerdoo (Iran)
7. Zarebin (Iran)
8. Parsijoo (Iran)
9. Yooz (Iran)
10. Jasjoo (Iran)
11. Baidu (China)
12. Sogou (China)
13. 360 Search / Haosou (China)
14. Shenma (China)
15. Naver (South Korea)
16. Daum (South Korea)
17. Yahoo! Japan (Japan)
18. Goo Japan (Japan)

Some Iranian entries are legacy/local services and may be unavailable depending on the service's current status. The browser does not depend on them; Google remains the normal default.

The Asian set includes major regional engines from China, South Korea, and Japan. The URLs use each service's web-search endpoint so the selected engine can receive the query directly.

## Tabs
A thin tab strip sits above the address bar. It supports:
- Add tab (+)
- Switch between tabs
- Close individual tabs
- Google is used for newly created tabs

## Address bar
- Search text uses the currently selected search engine.
- A complete URL is opened directly.
- The selected engine remains the active engine after app restart.

## Existing browser features
- Restores the last visited URL.
- Compact PC-like layout.
- Bottom controls: Setups, Tool, Links, Muse, Copy, PC_Phone, Text Size.
- Download support through Android DownloadManager.
- Resizable WebView foundation.

## GitHub / Mgit
This source package intentionally contains no GitHub Actions workflow (`main.yml`), so it can be imported into a repository without creating an unwanted workflow file.

## Video link detection / Links button

The browser now uses the supplied Video Downloader APK as a reference for the *architecture* of video discovery, without copying its proprietary code. The implementation in this project uses several complementary detection paths:

- WebView network-request interception (`shouldInterceptRequest`).
- WebView resource monitoring (`onLoadResource`).
- Service-worker request interception on Android 7+.
- Page-side inspection of `<video>`, `<source>` and `currentSrc`.
- Page-side Performance Resource Timing inspection to catch media/stream requests that do not appear as visible links.
- Direct video formats such as MP4/WebM/M4V/MOV/MKV/AVI/FLV/3GP/TS and streaming manifests such as M3U8/MPD.
- Duplicate URLs are collapsed into one candidate.
- Common advertising/tracking hosts and URL patterns are filtered before a candidate is added.

Every new detected candidate is placed in **Links** and the **Links** button flashes green. Opening **Links** shows the detected URL, type/format, and actions to copy, open, or send it to the downloader. The current page URL, User-Agent and cookies are passed to direct downloads when available.

For protected/encrypted DRM media, or sites that never expose a usable media request to the WebView, the browser does not attempt to bypass the site's protection.

## Long-press download queue
- Long-pressing a normal web link adds that target to the browser's own Downloads queue.
- Long-pressing an image adds the image URL to the queue.
- Long-pressing a video adds its exposed media URL when available; blob-based video falls back to the detected media links when available.
- Long-pressing an exposed downloadable file (video/audio/document/archive/image/etc.) can add it when its URL is identifiable.
- The Downloads window opens immediately and provides Download, Copy and Remove for each queued item, plus Download all.
- The queue is saved in browser preferences so queued items remain after restarting the browser.
- Actual transfers use Android DownloadManager with the page User-Agent, Referer and cookies where available.

## Copy button behavior
The bottom `Copy` button now copies the content associated with the last area touched inside the current webpage. It prefers selected text, then focused form text, then the nearest meaningful article/section/dialog/content element at the last touch point, with the whole page as a last fallback. Copying is text-only and does not trigger the site's buttons or links.

## Radio player added
- Tool > Radio opens a compact independent radio window.
- Playback is owned by `RadioPlaybackService` using Media3 ExoPlayer 1.11.0 and MediaSession.
- Supports direct HTTP/HTTPS audio streams and Media3-supported HLS/DASH/RTSP formats; an HTML page can be opened and the page's audio/source resources are inspected for a playable stream.
- Closing the radio window or the browser Activity does not intentionally stop playback. Stop is performed by the Radio Stop button/service action.
- The Fast Radio KBPS architecture is represented by the optional low-bandwidth relay field. The reference specification defines 32/48/64/96/128 kbps relay outputs through a server-side Transcoder (Liquidsoap) and Icecast; its sample YOUR-SERVER URLs are explicitly design examples and are not active servers. The browser therefore does not pretend those placeholder addresses are live.


## Resizable browser window
- MainActivity is explicitly marked resizeable for Android multi-window/freeform mode.
- The browser content follows the window dimensions; no fixed full-screen WebView size is used.
- Minimum window width is requested at about 15% where the Android window manager honors the theme value.
- On devices/launchers that support freeform windows, shrinking the browser exposes the apps/windows behind it.
- Android itself controls whether a normal app may become a freely floating window; the application cannot force freeform mode on devices that do not expose that capability.

## Complex website compatibility
The browser now configures Android WebView for modern, script-heavy websites: JavaScript, DOM/database storage, cookies including third-party cookies, normal HTTP caching, wide viewport support, media playback, multiple windows/tabs, JavaScript dialogs, HTML file chooser, geolocation permission flow, camera/microphone permission flow for WebRTC pages, fullscreen web content, safe browsing, mixed-content compatibility, and Android/WebView font-family fallbacks (sans-serif, serif, monospace, cursive, fantasy). The AndroidX WebKit compatibility library is included at stable version 1.17.1.

Web fonts served by a website are loaded by WebView normally; the project does not replace a site's own CSS fonts. Android's installed font fallback is used for characters the site does not supply. No DRM protection is bypassed.

## Initial page fit
Newly loaded websites start in overview mode and use the WebView wide viewport so the initial page is fitted to the available device/window screen. User zooming remains available afterward.


## High internet-usage warning
During the initial loading of a website, the browser monitors the Web Performance Resource Timing data available to WebView. If approximately 5 MB or more of resource data is observed, a relatively large red blinking dot appears in the upper corner of the browser content area. The warning remains visible for 3 seconds and then is removed automatically. Cached resources or resources for which WebView does not expose transfer-size information may not be counted, so this is an estimate rather than a device-level traffic meter.

## Long-press file actions
- Long-pressing an image, video, or detected downloadable file/link now opens a resource menu instead of immediately downloading it.
- The menu shows the resource name and detected type/extension.
- **Search by name and type** sends a query such as the filename plus its media/file type through the currently selected search engine.
- **Download** adds the resource to the browser's existing persistent download queue.
- Ordinary web-page links that are not downloadable files retain normal WebView long-press behavior.

## Function audit and activation pass
A full source-level control audit was performed. The previously present but non-functional controls were wired to real actions:
- Setups opens the browser setup panel.
- PC_Phone toggles Phone/PC User-Agent mode and persists the choice.
- Tool entries now have handlers for Bookmarks, History, Downloads, Incognito, Find in Page, Full Screen, Night Mode, No Images, Search Page, Snapshots (JPG), Add Bookmark, Share, Add to Desktop, Desktop Mode, Website Settings, Ad Block, Radio, Settings, and Quit.
- Bookmarks and up to 100 history entries are persisted locally.
- Ad Block now blocks a conservative list of common advertising/tracking URL patterns at WebView and Service Worker request interception points.
- Night Mode and No Images can be toggled back off.
- Custom search engines are persisted.
- The duplicate activity-result handler was consolidated so voice search, image selection, and HTML file chooser callbacks use one handler.
- The image/voice search buttons, tabs, navigation buttons, download queue, video Links actions, mouse controls, radio controls, and text-size control remain wired.

The Android project was source-audited and structurally checked in this environment. A local Gradle/Android SDK toolchain was not available here, so an APK compilation could not be truthfully claimed from this environment.

## Save Page
The Tool menu includes Save Page. The current page can be saved to Downloads as UTF-8 TXT, HTML, XML, or JSON. TXT contains visible page text; HTML contains the current DOM HTML; XML contains page metadata plus escaped HTML; JSON contains URL, title, visible text, and HTML.

## Tab page persistence
- Switching between tabs does not reload the page or call `loadUrl()`.
- Each tab keeps its own WebView instance alive, including page state, scroll position, form state, and back/forward history while the Activity remains alive.
- Android Activity recreation now saves/restores every open WebView with `WebView.saveState()` / `restoreState()`, so open tabs can return to their previous pages without an intentional refresh when the Activity is recreated.
- A background tab finishing a load updates its own tab record instead of incorrectly changing the currently selected tab's URL/title.

## Clean
Setups includes a Clean action with selectable cleanup targets: WebView cache, cookies/site data, browsing history, and the saved last-page state. Downloads and bookmarks are not removed by Clean.

## Widgets
The Tool menu now contains a Widgets manager. It stores several widget-provider resources by default (Common Ninja, Elfsight, POWR, and SociableKIT), allows adding custom HTTPS widget sources, opening a source, removing sources, and applying a selected source as a browser overlay without replacing the current tab. Widget providers may require their own account/embed configuration; the browser does not claim to create provider-specific widgets automatically.


### Temporary messages
All browser notification/warning/status messages shown as Toast messages are now automatically hidden after 4 seconds. Interactive dialogs that require a user action (such as OK/Cancel or text entry) remain available until the user closes or completes them.
