# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

QuickNovel is a FOSS Android app (Kotlin) for searching, reading, and downloading web novels from ~50 scraper "providers", plus a built-in EPUB reader with TTS. Package `com.lagradost.quicknovel`. It is architecturally a sibling of CloudStream (same author/patterns): each site is a provider class extending `MainAPI` that scrapes HTML with Jsoup.

## Build & test

The root uses the Gradle Kotlin DSL but `app/build.gradle` is **Groovy** (not `.kts`). Uses the Gradle wrapper (Gradle 9.2.1, JDK 21). `local.properties` must point at an Android SDK.

```bash
./gradlew assembleDebug          # build debug APK (app/build/outputs/apk/debug/)
./gradlew installDebug           # build + install on connected device/emulator
./gradlew lint                   # Android lint
./gradlew test                   # local unit tests (JUnit) — currently minimal
./gradlew connectedAndroidTest   # instrumented tests (needs device/emulator)
```

Debug builds get an `applicationIdSuffix ".debug"` and `BuildConfig.BETA = true`, so debug and release can be installed side by side. `minSdk 21`, `target/compileSdk 35`. There is effectively no test suite — verify changes by building and running.

## Core architecture

The data flow is: **Provider (`MainAPI`) → `APIRepository` (caching wrapper) → ViewModel → Fragment/Activity**.

- **`MainAPI`** (`MainAPI.kt`) — abstract base every provider extends. Key overridable members: `name`, `mainUrl`, `lang`, `hasMainPage`, `rateLimitTime`, and suspend functions `search()`, `load(url)` (returns a `LoadResponse`), `loadHtml()` (chapter text), `loadMainPage()`, `loadReviews()`. This same file also defines the shared data model: `SearchResponse`, `LoadResponse` (interface) and its impls `StreamResponse`/`EpubResponse`, `ChapterData`, `DownloadLink`, plus the `newSearchResponse {}` / `newStreamResponse {}` / `newChapterData {}` builder helpers and HTML-cleaning utilities (`stripHtml`, `textClean`).
- **Provider registration** — `util/Apis.kt` holds `Apis.apis`, the hand-maintained `List<MainAPI>` of every active provider (sorted by name). **Adding a provider = create the class in `providers/` AND add it to this list** (commented-out entries document dead/blocked sites — keep that convention). `getApiFromName()` resolves a provider by name here.
- **`APIRepository`** (`APIRepository.kt`) — wraps a `MainAPI`, adds a short-lived in-memory `LoadResponse` cache, ad-stripping (`removeAds`), and rate-limit handling. UI code talks to repositories, not raw providers.
- **Networking** — uses NiceHttp (`Requests`). The global client is `MainActivity.app` (alias also exposed as `MainAPI.app`). Providers that set `usesCloudFlareKiller = true` transparently get `MainActivity.appWithInterceptor` (Cloudflare-bypass interceptor) instead via the `app` getter on `MainAPI`. JSON parsing is Jackson (`jackson-module-kotlin`, pinned to 2.13.1 — "DO NOT CHANGE" per build file).
- **Shared provider base classes** — many providers don't extend `MainAPI` directly but a common scraper base in `providers/`: e.g. `WPReader`, `MadaraReader`, `WPReaderBlogger`. When changing scraping behavior for a family of sites, check whether a base class is involved.

## Reader, downloads & TTS

- **`BookDownloader2.kt`** + `DownloadFileWorkManager.kt` / `DownloadNotificationService.kt` — downloads a novel chapter-by-chapter and packages it as EPUB using the vendored epublib. Runs under WorkManager with a foreground notification.
- **EPUB/UMD libraries** are vendored under `me/ag2s/epublib` and `me/ag2s/umdlib` (third-party `me.ag2s` namespace, not `com.lagradost`) — used for both export and the internal reader. Treat as a library; avoid gratuitous refactors.
- **`ReadActivity2.kt` + `ReadActivityViewModel.kt`** — the in-app reader (renders chapters from a downloaded EPUB or live stream).
- **TTS** — `TTSHelper.kt`, `TTSNotificationService.kt`, `TTSNotifications.kt` drive text-to-speech playback with a media-session notification.
- **Translation** — ML Kit on-device translate plus `util/GoogleTranslateOnline.kt`.

## UI

Single-Activity-ish structure with Fragments under `ui/` (`home`, `search`, `mainpage`, `result`, `download`, `history`, `settings`), navigated via the AndroidX Navigation component (`res/navigation/`). Uses **View Binding** (`viewBinding.enabled = true`) and XML layouts in `res/layout/` — not Compose. The MVVM helpers live in `mvvm/`: `Resource<T>` (Loading/Success/Error), `safeApiCall {}`, and `logError()` — wrap provider/network calls in these rather than try/catch by hand.

## Persistence

`DataStore.kt` is a thin Jackson-over-SharedPreferences key/value store used app-wide for settings, reading progress, history, and bookmarks (no Room/SQLite). `util/BackupUtils.kt` serializes this for backup/restore.

## Conventions

- Provider scraping code is heavily defensive; use the `safe { }` / `safeApiCall { }` wrappers and the `newXxxResponse {}` builders rather than constructing response objects directly (the builders normalize URLs via `fixUrl` and patch poster headers for Cloudflare sites).
- `versionCode`/`versionName` in `app/build.gradle` are bumped per release (current 56 / 3.6.1).
