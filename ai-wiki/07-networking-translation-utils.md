# 07 - Networking, Translation & Utilities

This document covers the cross-cutting infrastructure of QuickNovel: the HTTP
client stack (NiceHttp + OkHttp), the Cloudflare bypass mechanism, the dual
on-device / online translation pipeline, the coroutine and parallel-map helpers,
Coil image loading, language/ISO code helpers, and the in-app updater.

All file paths below are absolute under
`C:\dev\QuickNovel\QuickNovel\app\src\main\java\com\lagradost\quicknovel\`.

---

## 1. HTTP Client Setup (NiceHttp `Requests`)

### 1.1 The two global clients

Defined in `MainActivity.kt` inside the `companion object` (lines ~111-152):

```kotlin
var app = Requests(
    OkHttpClient()
        .newBuilder()
        .ignoreAllSSLErrors()
        .readTimeout(30L, TimeUnit.SECONDS)
        .build(),
    responseParser = object : ResponseParser { ... }
).apply {
    defaultHeaders = mapOf("user-agent" to USER_AGENT)
}

val appWithInterceptor by lazy {
    Requests(
        baseClient = app.baseClient.newBuilder()
            .addInterceptor(CloudflareKiller())
            .build(),
        responseParser = app.responseParser
    ).apply {
        defaultHeaders = app.defaultHeaders
    }
}
```

- **`MainActivity.app`** — the default `com.lagradost.nicehttp.Requests` instance.
  Backed by a single `OkHttpClient` configured with:
  - `ignoreAllSSLErrors()` (NiceHttp extension) — disables certificate
    validation so self-signed / broken-cert novel hosts still load.
  - `readTimeout(30s)`.
  - `defaultHeaders = mapOf("user-agent" to USER_AGENT)` — every request carries
    a desktop Chrome UA by default.
- **`MainActivity.appWithInterceptor`** — lazily built. It clones
  `app.baseClient` (so it inherits the SSL/timeout/header config) and adds a
  `CloudflareKiller()` interceptor. It reuses the same `responseParser` and
  `defaultHeaders`. This is the client used when a provider needs Cloudflare
  bypass.

There is **no explicit OkHttp `Cache(...)`** wired into either client; HTTP-level
response caching is not configured for the NiceHttp clients. Caching that exists
in the app is at higher layers (translation file cache, Coil disk/memory cache —
see sections 4 and 5).

### 1.2 Response parsing

The `responseParser` passed to `app` is an anonymous `ResponseParser`
(`com.lagradost.nicehttp.ResponseParser`) backed by a Jackson
`jacksonObjectMapper()` with `FAIL_ON_UNKNOWN_PROPERTIES = false`. It implements
`parse`, `parseSafe` (try/catch → null), and `writeValueAsString`. This is what
powers `.parsed<T>()` / `.parsedSafe<T>()` on NiceHttp responses (e.g.
`GoogleTranslateOnline` calls `.parsed<GoogleTranslationResponse>()`).

### 1.3 Per-provider client selection (`MainAPI.app`)

`MainAPI.kt` (line ~24-25) exposes a per-provider `app` accessor that transparently
picks the right client based on a provider flag:

```kotlin
open val usesCloudFlareKiller = false
val app get() = if(!usesCloudFlareKiller) MainActivity.app else MainActivity.appWithInterceptor
```

So a provider that sets `usesCloudFlareKiller = true` automatically routes all of
its `this.app.get(...)` calls through the Cloudflare-killing client without any
other code changes. `MainAPI.fixPosterHeaders()` (line ~27) additionally appends
`DefaultImagesHeaders.useCloudflareKillerHeader` to poster image headers when the
provider uses Cloudflare, so images go through the same bypass path in Coil
(see section 5).

`MainAPI` also declares rate-limit primitives used by providers:
`open val rateLimitTime: Long`, `val hasRateLimit get() = rateLimitTime > 0L`,
and `val rateLimitMutex: Mutex` (lines 31-33).

### 1.4 `USER_AGENT`

Defined in `MainAPI.kt` (lines 15-16):

```kotlin
const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.93 Safari/537.36"
```

A fixed desktop Chrome UA. It is the default for `app`/`appWithInterceptor` and
the default `userAgent` parameter of `WebViewResolver`. Note that for Cloudflare
solving the app deliberately prefers the **real WebView UA** (see
`WebViewResolver.webViewUserAgent` / `getWebViewUserAgent()`), because a mismatched
UA breaks Cloudflare validation.

### 1.5 Download-link helpers

`MainAPI.kt` (lines 333-341) defines `@WorkerThread suspend fun DownloadExtractLink.get()`
and `DownloadLink.get()`, both delegating to `app.get(makeLinkSafe(url), headers,
referer, params, cookies)`. `makeLinkSafe` upgrades `http://` to `https://`.

---

## 2. Cloudflare Bypass (`network/CloudflareKiller.kt` + `network/WebViewResolver.kt`)

### 2.1 `CloudflareKiller` — the OkHttp interceptor

`network/CloudflareKiller.kt` is an `@AnyThread class CloudflareKiller : Interceptor`.
State:
- `companion object` holds a single shared `Mutex` (so only one WebView solve runs
  at a time across the app) and `parseCookieMap(cookie)` which splits a raw
  `Cookie:` string into a `Map<String,String>`.
- `val savedCookies = ConcurrentHashMap<String, Map<String,String>>()` keyed by
  host — caches solved `cf_clearance` cookies per host.

**`intercept(chain)`** (runs inside `runBlocking`):
1. If `savedCookies[host]` exists, replay the request with those cookies via
   `proceed(...)`. If the response no longer looks like a challenge, return it;
   otherwise close it and clear the host's cookies.
2. Try the request normally (`chain.proceed(request)`). If it does **not** look
   like a Cloudflare challenge, return immediately — the bypass is only invoked
   when actually needed.
3. Under `mutex.withLock`: try `trySolveWithSavedCookies` (reads the
   `CookieManager` for a `cf_clearance` cookie). If that yields cookies, replay.
4. Otherwise call `bypassCloudflare(request)` which spins up a `WebViewResolver`
   to solve the challenge, then replays the original request with the harvested
   cookies.
5. On total failure, emits a `debugWarning` and falls back to a plain
   `chain.proceed(request)`.

**`looksLikeCloudflareChallenge(response)`** is the heuristic gate. It returns true
when:
- status is `403 / 429 / 503` **and** either there are Cloudflare headers
  (`cf-ray`, or `server` contains "cloudflare"), or the first 10 KB of the body
  (peeked, non-destructive) contains markers like `cf-browser-verification`,
  `checking your browser`, `just a moment`, or `/cdn-cgi/`; or
- a redirect `location` header points at `/cdn-cgi/`.

**`proceed(request, cookies)`** rebuilds the request headers, injecting the WebView
user agent (`WebViewResolver.webViewUserAgent ?: getWebViewUserAgent()`) and the
saved cookies merged with request cookies (`getHeaders(...)` from NiceHttp), then
executes via `app.baseClient.newCall(...).await()`.

**`bypassCloudflare(request)`** constructs a `WebViewResolver` configured for
Cloudflare specifically:
- `interceptUrl = Regex(".^")` (never matches — so it never exits on a URL),
- `userAgent = null` (Cloudflare needs the default WebView UA, not `USER_AGENT`),
- `useOkhttp = false` (OkHttp interception of cookies breaks the solve),
- `additionalUrls = listOf(Regex("."))` (match every request so the callback fires).
  The `requestCallBack` repeatedly calls `trySolveWithSavedCookies` until the
  `cf_clearance` cookie appears in the `CookieManager`, at which point cookies are
  saved and the original request is replayed.

### 2.2 `WebViewResolver` — headless WebView engine

`network/WebViewResolver.kt` is both an `Interceptor` and a standalone resolver.
Constructor params: `interceptUrl: Regex`, `additionalUrls: List<Regex>`,
`userAgent: String? = USER_AGENT`, `useOkhttp: Boolean = true`.

Key behavior:
- `companion object.webViewUserAgent` caches the device WebView UA;
  `getWebViewUserAgent()` lazily instantiates a `WebView` on the main thread
  (`mainWork { ... }`) to read `settings.userAgentString`.
- `resolveUsingWebView(...)` creates a `WebView` (JS + DOM storage enabled), wires a
  `WebViewClient`, loads the URL, and awaits a `CompletableDeferred` with a **60 s
  timeout** (`withTimeoutOrNull(60000L)`), always destroying the WebView afterward.
- `shouldInterceptRequest` does request-level filtering:
  - Blocks known tracker hosts (`blockedTrackerHosts`: google-analytics,
    googletagmanager, doubleclick, etc.) by returning an empty response.
  - When the loaded URL matches `interceptUrl`, it captures the request as
    `fixedRequest`, fires `requestCallBack`, and completes the deferred.
  - URLs matching `additionalUrls` are collected into `extraRequestList`.
  - Blacklisted binary/asset extensions (`blacklistedExtensions`: jpg, png, mp4,
    woff, css, etc.), favicon, and `wss://` are short-circuited with a stub
    response to avoid wasting bandwidth.
  - reCAPTCHA / `/cdn-cgi/` URLs are passed to `super` (real network).
  - Otherwise, when `useOkhttp` is true, GET/POST are proxied through
    `app.get/post(...).okhttpResponse.toWebResourceResponse()`.
- `onReceivedSslError` calls `handler.proceed()` (ignores SSL issues).
- `onPageFinished` injects a JS snippet that detects a Cloudflare challenge form
  (`#challenge-form`, `cf-turnstile-response`) and auto-clicks the submit button,
  retrying up to 15 times at 1 s intervals.

Helper extensions at file end: `WebResourceRequest.toRequest()` (builds a NiceHttp
`requestCreator`) and `Response.toWebResourceResponse()` (maps an OkHttp response,
parsing `Content-Type`/charset via `CONTENT_TYPE_REGEX`).

---

## 3. Translation Pipeline

QuickNovel supports two translation backends, selectable per book:
**on-device Google ML Kit** (offline) and **Google Translate web endpoint**
(online). The orchestration lives in `ReadActivityViewModel.kt`; the online HTTP
call lives in `util/GoogleTranslateOnline.kt`.

### 3.1 Settings model — `MLSettings`

`ReadActivityViewModel.MLSettings` (data class, lines ~1935-2040) carries
`from`, `to`, and `useOnlineTranslation`. Persisted per book via
`PreferenceDelegateLiveView` keys: `mlFromLanguage` (`EPUB_ML_FROM_LANGUAGE`,
default `TranslateLanguage.ENGLISH`), `mlToLanguage` (`EPUB_ML_TO_LANGUAGE`), and
`mlUseOnlineTransaltion` (`EPUB_ML_USEONLINETRANSLATION`, default false). [Note the
misspelling "Transaltion" is the actual symbol name in code.]

- `companion object.map` is the canonical short-code → display-name table (~60
  languages: `"af" -> "Afrikaans"`, ..., `"zh" -> "Chinese"`). `mapOnline` prepends
  `AUTO_LANG = "auto"` → `"Auto"` for the online path. `fromShortToDisplay()` and
  the `fromDisplay`/`toDisplay` getters use these.
- `isValid()` / `isInvalid()` validate against `TranslateLanguage.getAllLanguages()`:
  blank codes are invalid; the target must be a real ML Kit language; the source
  must be a real ML Kit language **unless** online mode is used with `from == "auto"`
  (auto-detect is only supported online — ML Kit has no offline language ID here).

### 3.2 The `translate(...)` entry point

`ReadActivityViewModel.translate(text, spans, loading)` (lines ~977-1058,
`@Throws(MLException::class)`) is the unified translate routine for one chapter's
spans:
1. If `spans` is empty or `mlSettings.isInvalid()`, returns the input unchanged.
2. Computes a content hash (`hashString(text.trim()...)`) and builds a cache file
   prefix: `ml_<hash>.<from>_to_<to>.<online|offline>`.
3. **Disk cache read**: if `<prefix>.txt` exists in `context.cacheDir`, it reads the
   translated lines and reconstructs spans via `getFinalTranslatedText(...)` —
   skipping translation entirely.
4. **Online mode** (`useOnlineTranslation == true`): delegates to
   `GoogleTranslateOnline.onlineTranslate(spans.map { it.text.toString() }, from, to)`
   with a progress callback.
5. **Offline mode**: requires a non-null `mlTranslator` (an ML Kit `Translator`),
   then `spans.mapIndexed { ... Tasks.await(translator.translate(span.text)) }`,
   unwrapping `ExecutionException.cause`.
6. Writes the result atomically: writes `<prefix>.tmp`, deletes any stale
   `<prefix>.txt`, then renames `.tmp → .txt`.
7. Wraps any throwable in `MLException` (class declared line ~434).

`getFinalTranslatedText(spans, translatedLines)` (lines ~957-974) rebuilds a
`SpannableStringBuilder` and an `ArrayList<TextSpan>`, preserving image spans
(`AsyncDrawableSpan`) untranslated and slotting translated text per original span.

### 3.3 On-device ML Kit lifecycle

ML Kit imports (`ReadActivityViewModel.kt` lines 31-36):
`com.google.mlkit.common.model.RemoteModelManager`,
`com.google.mlkit.nl.translate.{TranslateLanguage, TranslateRemoteModel,
Translation, Translator, TranslatorOptions}`.

- `mlTranslator: Translator?` (field, line ~467) is the active offline translator.
- `initMLFromSettings(settings, allowDownload)` (lines ~1153-1185): closes any
  existing translator (`closeQuietly()`), and for valid offline settings builds
  `TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to)` →
  `Translation.getClient(options)`. If `allowDownload`, it blocks on
  `Tasks.await(translator.downloadModelIfNeeded(), 120s)` (long timeout "for bad
  wifi"); on `TimeoutException` it toasts `R.string.unable_to_download_language`.
- `requireMLDownload()` (lines ~1060-1082): uses `RemoteModelManager.getInstance()`
  + `modelManager.isModelDownloaded(TranslateRemoteModel.Builder(model).build())`
  to detect whether the from/to models still need downloading (English `"en"` is
  skipped as it ships built-in).
- `applyMLSettings(allowDownload)` (lines ~1084-1091, `= ioSafe { ... }`): if a
  download is required, posts `Resource.Loading("Downloading language")`, then
  `initMLFromSettings` + `reloadMLForAllChapters()`.
- `reloadMLForAllChapters()` (lines ~1093-1151): under `chapterMutex`, evicts
  out-of-window cached chapters, then re-translates the in-window
  `Resource.Success` chapters, posting per-chapter `R.string.translating` progress.
- Teardown in `onCleared`-style paths closes and nulls `mlTranslator` (lines
  ~1774-1775).

### 3.4 Online translation — `util/GoogleTranslateOnline.kt`

`object GoogleTranslateOnline` calls the unofficial gtx endpoint
`https://translate.googleapis.com/translate_a/single?client=gtx&sl=<from>&tl=<to>&dt=t&q=<text>`
via `MainActivity.app.get(...).parsed<GoogleTranslationResponse>()`
(`callGoogleTranslateApi`). The response DTOs `GoogleTranslationResponse` /
`GoogleSentence` use Jackson `@JsonFormat(shape = ARRAY)` to map Google's
positional JSON arrays.

- `onlineTranslate(paragraphs, from, to, loading)` chunks input by
  `chunkByLimit()` (≤ `charsLimit = 2000` chars per request), joining paragraphs
  with a sentinel separator `"\nXQZX\n"` (`paragraphsSeparator`). After
  translation it splits results back on `paragraphsSeparatorRegex`
  (`Regex("\\n?XQZX\\n?")`) to recover paragraph boundaries.
- `translateChunk(...)` retries up to `maxRetry = 3` with exponential backoff
  (`delay(500L * 2^retry)`), rethrowing `UnknownHostException` immediately (no
  point retrying when offline). It also detects "not translated" sentences
  (`trans == orig` with real letters and ≥3 words) and recursively re-requests the
  original text once.

---

## 4. Coroutine & Parallel-Map Utilities

### 4.1 `util/Coroutines.kt`

`object Coroutines` provides ergonomic dispatcher wrappers (all generic over the
receiver `T`/`V`):
- `T.main(work)` → launches on `Dispatchers.Main` via `launchSafe` (returns `Job`).
- `T.ioSafe(work)` → launches on `Dispatchers.IO` via `launchSafe` (returns `Job`).
  This is the dominant "fire-and-forget background task" helper used across
  view models (e.g. `applyMLSettings = ioSafe { ... }`).
- `V.ioWorkSafe(work)` → `withContext(Dispatchers.IO)` wrapped in try/catch,
  returns `T?` (null on error, logged via `logError`).
- `V.ioWork(work)` → `withContext(Dispatchers.IO)`, returns `T` (throws).
- `V.mainWork(work)` → `withContext(Dispatchers.Main)`, returns `T`.
- `runOnMainThread(work)` → posts to a `Handler(Looper.getMainLooper())`.
- `threadSafeListOf(vararg items)` → `Collections.synchronizedList(...)`. Note the
  KDoc warning: safe to add/remove, but iteration must be wrapped in
  `synchronized(list) { ... }`. Used for thread-safe provider/registry lists.

`launchSafe` itself is defined in `mvvm/ArchComponentExt.kt` (line ~140) — a
`CoroutineScope.launch` variant whose block is wrapped in try/catch → `logError`,
so background coroutines never crash the app. The same file provides `safe { }`,
`safeAsync { }`, the `Resource<T>` sealed class, and `logError`.

### 4.2 `util/ParCollections.kt`

Three parallel-collection helpers:
- `Iterable<T>.pmap(numThreads, exec, transform)` — thread-pool based map. Default
  `numThreads = max(availableProcessors - 2, 1)` on a `newFixedThreadPool`. Submits
  each item, collects into a `Collections.synchronizedList`, then `shutdown()` +
  `awaitTermination(1, TimeUnit.DAYS)`. **Caveat**: ordering is not preserved (items
  are added as they finish).
- `List<A>.apmap(f)` — coroutine map using `runBlocking { map { async { f(it) } }
  .map { it.await() } }`. Order-preserving. Used by `ExtractorApi.extract(...)`.
- `List<A>.amap(f)` — suspend version using a `GlobalScope`-derived `CoroutineScope`
  (`@OptIn(DelicateCoroutinesApi)`), order-preserving.

---

## 5. Image Loading via Coil 3

Image loading is centralized in `util/ImageModuleCoil.kt` (package declared as
`com.lagradost.cloudstream3.utils` — "Taken from cs3"). It builds a single global
Coil 3 `ImageLoader`, registered by `BaseApplication`.

### 5.1 Global loader registration

`BaseApplication.kt` implements `coil3.SingletonImageLoader.Factory`; its
`newImageLoader(context)` returns `ImageLoader.buildImageLoader(applicationContext)`.
This makes the custom loader the app-wide singleton (`SingletonImageLoader.get(...)`).

### 5.2 `ImageLoader.buildImageLoader(...)`

Configures the Coil loader:
- `crossfade(200)`, `allowHardware(false)` (hardware bitmaps disabled so the
  Palette builder works on API ≥ 28).
- `diskCachePolicy(ENABLED)` + `networkCachePolicy(ENABLED)`.
- **Memory cache**: `MemoryCache` at `maxSizePercent(context, 0.1)` (10 % of app
  memory).
- **Disk cache**: `DiskCache` in `cacheDir/qn_image_cache`, capped at
  `512 MB` (`maxSizeBytes`) and `4 %` of device storage (`maxSizePercent(0.04)`).
- **Network fetcher**: `OkHttpNetworkFetcherFactory` with its own `OkHttpClient`
  (`ignoreAllSSLErrors()` + a `DynamicInterceptor`). The comment stresses passing
  interceptors with care so tokens aren't leaked to image hosts.
- Logging: `DebugLogger()` on debug builds; on release an `EventListener` that logs
  errors (`setupCoilLogger()`).

### 5.3 `DynamicInterceptor`

In the same file. Reads two opt-in request headers and strips them before sending:
- `DefaultImagesHeaders.useCloudflareKillerHeader` (`"useCloudflareKiller" -> "true"`)
  → routes the image request through a lazily-created `CloudflareKiller`.
- `DefaultImagesHeaders.useIgnore500Header` (`"useIgnore500" -> "true"`) → rewrites a
  `500` response into a `200` ("Forced OK from 500") so flaky hosts still render.

`DefaultImagesHeaders` is defined in `util/DefaultImagesHeaders.kt`. Providers add
the Cloudflare header to poster requests via `MainAPI.fixPosterHeaders(...)`.

### 5.4 `loadImage` extensions & type-safe loaders

`object ImageLoader` exposes `ImageView.loadImage(...)` overloads for `UiImage`,
`String`, `Uri`, `HttpUrl`, `File`, `@DrawableRes Int`, `Drawable`, `Bitmap`,
`ByteArray`, `ByteBuffer`. All funnel into the private `loadImageInternal(...)`,
which first `dispose()`s the view (prevents flicker on fast-scrolling recyclers),
short-circuits `Int` to `setImageResource` (so attr/resource drawables resolve
correctly), and otherwise calls Coil's `this.load(imageData, SingletonImageLoader.get(context))`
attaching any per-request headers via `NetworkHeaders`. A trailing `builder`
lambda allows callers to set placeholders/transformations.

### 5.5 Inline markdown images — `util/CoilPlugin.kt`

`CoilImagesPlugin : AbstractMarkwonPlugin` integrates Coil with the Markwon
markdown renderer used in the reader, so `![]()` images inside chapter HTML load
asynchronously. Its inner `CoilAsyncDrawableLoader` (extends Markwon's
`AsyncDrawableLoader`) builds a Coil `ImageRequest` per `AsyncDrawable`,
`enqueue`s it on the shared `ImageLoader`, tracks in-flight requests in a cache map
(with the `@since 4.5.1` race handling around `AtomicBoolean loaded`), and on
success/error/start maps the `coil3.Image` to a `Drawable` and assigns it to the
attached drawable. `BlurTransformation.kt` (a Coil `Transformation`) provides
blurred poster variants.

---

## 6. Language / Subtitle Code Helpers — `util/SubtitleHelper.kt`

`object SubtitleHelper` is an ISO-639 lookup table (`data class Language639` with
`languageName`, `nativeName`, and `ISO_639_1/2_T/2_B/3/6` fields; the full
`languages` list is generated data). Public conversion helpers:
- `fromLanguageToTwoLetters(input, looseCheck)` → ISO 639-1 code from a language or
  native name. With `looseCheck = true` it falls back to a `.contains` match in a
  second pass (exact matches prioritized).
- `fromTwoLettersToLanguage(input)` → language name from an ISO 639-1 code (handles
  region suffixes like `pt-BR` by taking the part before `-`; lazily builds
  `ISO_639_1Map`).
- `fromThreeLettersToLanguage(input)` → language name from ISO 639-2/B, 639-2/T, or
  639-3 (checked in that priority order).

`MainAPI.lang` (default `"en"`) documents that providers should use ISO 639-1 codes
"check SubtitleHelper". Note this is distinct from the ML Kit
`TranslateLanguage`/`MLSettings.map` table in section 3, which is a separate
translation-specific code set.

---

## 7. In-App Updater — `util/InAppUpdater.kt`

`class InAppUpdater` (logic in its `companion object`) self-updates the APK from
GitHub Releases:

- **DTOs**: `GithubAsset`, `GithubRelease`, and the computed `Update`
  (`shouldUpdate`, `updateURL`, `updateVersion`, `changelog`). A dedicated Jackson
  `JsonMapper` (KotlinModule, `FAIL_ON_UNKNOWN_PROPERTIES = false`) parses them.
- **`Activity.getAppUpdate()`**: GETs
  `https://api.github.com/repos/LagradOst/QuickNovel/releases/latest` (via
  `MainActivity.app`, with `Accept: application/vnd.github.v3+json`), takes
  `assets[0]`, extracts a version via
  `Regex("""(.*?((\d)\.(\d)\.(\d)).*\.apk)""")`, and compares it against the
  installed `versionName` to decide `shouldUpdate`. Failures return a no-op
  `Update(false, null, null, null)`.
- **`Activity.downloadUpdate(url)`**: guarded by a `@Volatile isDownloadingUpdate`
  flag. Downloads to `filesDir/Download/apk/update.apk` via a raw
  `URLConnection` (`Accept-Encoding: identity`, 10 s connect timeout). Streams in
  1 KB chunks, throttling progress notifications to ~1 s intervals through
  `NotificationHelper.createNotification(...)` with computed bps/ETA. Rejects
  payloads `< 5 MB` as invalid. On completion it launches an
  `ACTION_VIEW` install intent — using `FileProvider` (authority
  `BuildConfig.APPLICATION_ID + ".provider"`) on API ≥ N, or a raw `file://` Uri
  below that.
- **`Activity.runAutoUpdate(checkAutoUpdate)`**: respects the
  `R.string.auto_update_key` preference. When an update is found, shows an
  `AlertDialog` with the changelog and Update / Cancel / "Don't show again"
  buttons; the Update button runs `downloadUpdate` on a background `thread { }` and
  toasts on failure.

---

## 8. Extractors (skim) — `extractors/`

The `extractors` package resolves indirect download links (e.g. book-mirror pages
that themselves link to the real file).

- `ExtractorApi.kt`: `abstract class ExtractorApi` with `name`, `mainUrl`,
  `requiresReferer`, and a lazy `mainUrlNoHttp`. `getSafeUrl(link)` wraps
  `getUrl(link)` in try/catch → `logError` → empty list. The companion holds the
  registry `extractors = listOf(LibgenLi())` and a recursive
  `extract(links, depth = 5)` that walks `DownloadLinkType`s: passes through
  `DownloadLink`s, and for `DownloadExtractLink`s finds the matching extractor by
  host (`cmp.startsWith(it.mainUrlNoHttp)`) and recurses (depth-limited),
  parallelized with `apmap`. `fixUrl`/`fixUrlNull` extensions normalize relative
  URLs against `mainUrl` (handling `//`, leading `/`, and JSON-object URLs).
- `LibgenLi.kt`: concrete `ExtractorApi` for `https://libgen.li`. Its `getUrl`
  fetches the page via `link.get().document` (Jsoup), selects the first
  `tbody>tr>td>a` href, and returns a `DownloadLink` (`kbPerSec = 200`).

---

## Cross-Reference Summary

| Concern | Symbol(s) | File |
|---|---|---|
| Default HTTP client | `MainActivity.app` (`Requests`) | `MainActivity.kt` |
| Cloudflare HTTP client | `MainActivity.appWithInterceptor` | `MainActivity.kt` |
| Per-provider client switch | `MainAPI.app`, `usesCloudFlareKiller` | `MainAPI.kt` |
| User agent | `USER_AGENT` | `MainAPI.kt` |
| CF interceptor | `CloudflareKiller` | `network/CloudflareKiller.kt` |
| Headless WebView solve | `WebViewResolver` | `network/WebViewResolver.kt` |
| Online translate | `GoogleTranslateOnline` | `util/GoogleTranslateOnline.kt` |
| On-device translate | `mlTranslator`, `initMLFromSettings`, `MLSettings` | `ReadActivityViewModel.kt` |
| Coroutine helpers | `Coroutines` (`ioSafe`, `ioWorkSafe`, `threadSafeListOf`) | `util/Coroutines.kt` |
| Parallel maps | `pmap`, `apmap`, `amap` | `util/ParCollections.kt` |
| Coil loader | `ImageLoader.buildImageLoader`, `DynamicInterceptor` | `util/ImageModuleCoil.kt` |
| Markdown images | `CoilImagesPlugin` | `util/CoilPlugin.kt` |
| Image header flags | `DefaultImagesHeaders` | `util/DefaultImagesHeaders.kt` |
| ISO language codes | `SubtitleHelper` | `util/SubtitleHelper.kt` |
| In-app updater | `InAppUpdater` | `util/InAppUpdater.kt` |
| Link extractors | `ExtractorApi`, `LibgenLi` | `extractors/` |
