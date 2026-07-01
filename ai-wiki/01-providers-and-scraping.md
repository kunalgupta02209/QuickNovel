# Providers & Scraping

This module is the heart of QuickNovel: it defines how the app talks to dozens of
third-party novel websites, normalizes their wildly different HTML/JSON into a
single data model, and exposes a uniform `search → load → loadHtml` flow to the
rest of the app.

Key files:

- `app/src/main/java/com/lagradost/quicknovel/MainAPI.kt` — abstract base class + shared data model + builder helpers.
- `app/src/main/java/com/lagradost/quicknovel/APIRepository.kt` — thin wrapper around a `MainAPI` adding caching, ad-stripping, rate limiting, error wrapping.
- `app/src/main/java/com/lagradost/quicknovel/util/Apis.kt` — the static registry of all providers and lookup/settings helpers.
- `app/src/main/java/com/lagradost/quicknovel/providers/*.kt` — one concrete provider per site (~50 files), plus shared base classes (`WPReader`, `AllNovelProvider`, `MoreNovelProvider`, `LibReadProvider`, `WuxiaBoxProvider`, …).

---

## 1. What a provider is

A provider is a subclass of `abstract class MainAPI` (`MainAPI.kt:18`). The base
class is a bag of `open` members; a concrete provider overrides the ones it needs.
There is no registration interface — being a `MainAPI` and being listed in
`Apis.apis` is all it takes.

### Identity / configuration (overridable `val`s)

| Member | Default | Meaning |
|---|---|---|
| `name` | `"NONE"` | Display name **and** the persistent key used everywhere (settings, cache hash, downloads). Changing it orphans user data. |
| `mainUrl` | `"NONE"` | Base site URL; used by `fixUrl`/`fixUrlNull` to absolutize relative links. |
| `lang` | `"en"` | ISO-639-1 code; controls visibility via the language filter (`Apis.getApiProviderLangSettings`). |
| `usesCloudFlareKiller` | `false` | Routes all requests through the Cloudflare-bypass HTTP client (see §6). |
| `rateLimitTime` | `0L` | If `> 0`, `load` calls are serialized with a delay-equivalent mutex (see §5). |
| `hasMainPage` | `false` | Enables the browse/discover tab and `loadMainPage`. |
| `mainCategories` / `orderBys` / `tags` | empty | `List<Pair<label, queryValue>>` driving the main-page filter dropdowns. |
| `hasReviews` | `false` | Enables `loadReviews`. |
| `iconId` / `iconBackgroundId` | `null` / gray | Provider logo drawable + background color. |

### Behavior (overridable `suspend fun`s)

All default to `throw NotImplementedError()` (except where noted):

- `search(query): List<SearchResponse>?` — required for almost every provider.
- `load(url): LoadResponse?` — fetch a book's metadata + chapter list (or download links).
- `loadHtml(url): String?` — fetch a single chapter's body HTML.
- `loadMainPage(page, mainCategory, orderBy, tag): HeadMainPageResponse` — browse listing; only if `hasMainPage`.
- `loadReviews(url, page, showSpoilers): List<UserReview>` — only if `hasReviews`.

A minimal provider therefore overrides `name`, `mainUrl`, and implements
`search` + `load` + `loadHtml`.

---

## 2. The data model

### SearchResponse (`MainAPI.kt:190`)

The lightweight result of a search or main-page query. Fields: `name`, `url`,
`posterUrl?`, `rating?` (0–1000 scale), `latestChapter?`, `apiName`,
`posterHeaders?`. The `image` getter wraps poster + headers into a `UiImage`.

### LoadResponse (`MainAPI.kt:241`) — interface

The full detail of a book. Two concrete implementations:

- **`StreamResponse`** (`MainAPI.kt:272`) — the common case: a readable novel with
  a `data: List<ChapterData>` chapter list (plus optional `nextChapter`). This is
  what powers the in-app reader and on-device EPUB generation.
- **`EpubResponse`** (`MainAPI.kt:348`) — for sites that only offer downloadable
  files: carries `downloadLinks: List<DownloadLink>` and
  `downloadExtractLinks: List<DownloadExtractLink>` instead of chapters. Used by
  e.g. `AnnasArchive`.

Shared `LoadResponse` fields: `url`, `name`, `author?`, `posterUrl?`,
`rating?` (0–1000), `peopleVoted?`, `views?`, `synopsis?`, `tags?`,
`status: ReleaseStatus?`, `posterHeaders?`, `apiName`, `related?`.

### ChapterData (`MainAPI.kt:386`)

`name`, `url`, `dateOfRelease?`, `views?`. A `StreamResponse.data` list of these
is the chapter index; each `url` is later passed back to `loadHtml`.

### ReleaseStatus (`MainAPI.kt:218`)

Enum `Ongoing | Completed | Paused | Dropped | Stubbed`, each mapped to a string
resource. The helper `LoadResponse.setStatus(status: String?)` (`MainAPI.kt:226`)
normalizes free-text site labels (`"on-going"`, `"complete"`, `"hiatus"`, …) into
the enum and returns whether it matched — providers typically call
`setStatus(doc.selectFirst(...)?.text())`.

### DownloadLink / DownloadExtractLink (`MainAPI.kt:309`, `:320`)

Both implement `DownloadLinkType` (`url`, `name`). `DownloadLink` adds
`referer`, `headers`, `params`, `cookies`, and `kbPerSec` (sort hint).
`DownloadExtractLink` is the same minus speed and signals "needs an extractor".
Extension `.get()` on each (`MainAPI.kt:334`/`:339`) fetches the bytes via the
provider's `app` client, forcing https through `makeLinkSafe`.

---

## 3. Builder helpers

Rather than constructing data classes directly, providers use `MainAPI` extension
builders that auto-fill `apiName`, absolutize the URL, and fix poster headers.
Each takes a trailing `initializer` lambda (receiver = the object) for optional
fields:

- `newSearchResponse(name, url, fix = true) { ... }` (`MainAPI.kt:202`) — sets `apiName = this.name`, runs `fixUrl(url)` unless `fix = false`, and post-processes `posterHeaders` via `fixPosterHeaders`.
- `newStreamResponse(name, url, data) { ... }` (`MainAPI.kt:290`) — same, for a chapter-list book.
- `newEpubResponse(name, url, links) { ... }` (`MainAPI.kt:366`) — partitions the supplied `List<DownloadLinkType>` into `downloadLinks` / `downloadExtractLinks` via `filterIsInstance`.
- `newChapterData(name, url, fix = true) { ... }` (`MainAPI.kt:396`).

The `fix` flag matters when a site returns URLs that should not be prefixed with
`mainUrl`. `fixUrl`/`fixUrlNull` (`MainAPI.kt:92`/`:99`) handle absolute,
protocol-relative (`//`), and root-relative (`/`) URLs.

The convention: prefer these builders over raw constructors so `apiName` and
poster headers stay correct. (Older providers like `MadaraReader` still use raw
`SearchResponse(...)`/`ChapterData(...)` constructors — both styles compile, but
the builders are the intended path.)

---

## 4. Registration (`util/Apis.kt`)

All active providers are listed by hand in `Apis.apis` (`Apis.kt:64`):

```kotlin
val apis: List<MainAPI> = arrayOf(
    AllNovelProvider(),
    AnnasArchive(),
    ChrysanthemumGardenProvider(),
    ...
    RoyalRoadProvider(),
    ...
).sortedBy { it.name }
```

Notes:

- The array is instantiated once and `sortedBy { it.name }`. **Adding a provider
  means adding one line here** (and the matching `import`). Dead/broken sites are
  left in as commented-out lines (e.g. `//LightNovelPubProvider(), // Got cloudflare`).
- Lookups go through this list: `getApiFromNameNull(name): MainAPI?`,
  `getApiFromNameOrNull(name): APIRepository?`, `getApiFromName(name)`.
- `RedditProvider` is special-cased — it is **not** in `apis` but is reachable by
  name via `getApiFromNameOrNull` (`Apis.kt:146`).
- `getApiSettings(context)` (`Apis.kt:220`) intersects the user's enabled-provider
  set with their enabled-language set to decide which providers are searched.
- `testProviders()` (`Apis.kt:171`) is a built-in smoke test: it runs
  `search → load → loadHtml` (and `loadMainPage` when `hasMainPage`) against every
  provider in parallel and asserts non-empty results — useful when a site changes
  its HTML.

---

## 5. What `APIRepository` adds

The rest of the app never calls a `MainAPI` directly; it wraps it in
`APIRepository(api)` (`APIRepository.kt:35`). The repository forwards config
getters (`name`, `mainUrl`, `hasReviews`, `iconId`, `mainCategories`, …) and adds
cross-cutting behavior:

1. **Error wrapping** — `search`, `load`, `loadReviews`, `loadMainPage` run inside
   `safeApiCall { ... }`, converting exceptions into a `Resource.Failure` instead
   of crashing. A `null` provider result is turned into `ErrorLoadingException("No data")`.

2. **Load caching** — `load(url, allowCache = true)` (`APIRepository.kt:69`) keeps a
   process-wide rolling cache of up to `cacheSize = 20` `SavedLoadResponse`s
   (`APIRepository.kt:48`), keyed by `(api.name, fixedUrl)` and valid for
   `cacheTimeSec = 600` (10 minutes). Hits skip the network entirely. The cache is
   a fixed-size ring (`cacheIndex` rolls over).

3. **Ad stripping** — `loadHtml(url)` (`APIRepository.kt:120`) calls the provider's
   `loadHtml`, then runs `String?.removeAds()` (`APIRepository.kt:15`), which Jsoup-parses
   the chapter and removes `small.ads-title`, `script`, `iframe`, and `.adsbygoogle`.
   So providers do **not** need to strip ad scripts themselves.

4. **Rate limiting** — if `api.hasRateLimit` (i.e. `rateLimitTime > 0`), `load`
   acquires `api.rateLimitMutex` for the duration of the call (`APIRepository.kt:72`/`:104`),
   serializing requests to that site. `rateLimitTime` is defined on `MainAPI`
   (`MainAPI.kt:31`); e.g. `RoyalRoadProvider` sets `rateLimitTime = 500L`.

`removeAds` and the cache live on `APIRepository`, not on the providers, which is
why provider `loadHtml` implementations can return relatively raw chapter HTML.

---

## 6. Cloudflare handling

`MainAPI` exposes a single networking entry point:

```kotlin
val app get() = if (!usesCloudFlareKiller) MainActivity.app else MainActivity.appWithInterceptor
```
(`MainAPI.kt:25`)

- `MainActivity.app` is the default NiceHttp `Requests` client (set with
  `USER_AGENT` default headers).
- `MainActivity.appWithInterceptor` (`MainActivity.kt:143`) is the same client
  rebuilt with a `CloudflareKiller()` OkHttp interceptor
  (`network/CloudflareKiller.kt`) that solves/clears Cloudflare challenges.

A provider opts in simply by setting `override val usesCloudFlareKiller = true`;
every `app.get(...)` inside it then transparently uses the bypass client. There is
no per-call wiring.

For **images**, `fixPosterHeaders(headers)` (`MainAPI.kt:27`) appends
`DefaultImagesHeaders.useCloudflareKillerHeader` (`"useCloudflareKiller" to "true"`)
to poster headers when `usesCloudFlareKiller` is on. The Coil image loader
(`util/ImageModuleCoil.kt`) detects that header and routes the image fetch through
its own `CloudflareKiller`. The `newSearchResponse`/`newStreamResponse`/`newEpubResponse`
builders call `fixPosterHeaders` automatically, so posters load behind Cloudflare
without extra provider code.

---

## 7. Shared base classes

Many providers don't extend `MainAPI` directly — they extend another provider or a
"reader" base that already implements the scraping for a common CMS/template. The
concrete subclass typically only overrides `name`, `mainUrl`, `lang`, icons, and
sometimes CSS-selector hooks.

- **`WPReader`** (`providers/WPReader.kt`) — abstract base for a WordPress
  light-novel theme. Implements `loadMainPage`, `search`, `load`, `loadHtml`,
  defaults `lang = "id"`, `usesCloudFlareKiller = true`, and a big `tags` list.
  Used by **`IndoWebNovelProvider`** and **`SakuraNovelProvider`** (each ~a dozen
  lines overriding `name`/`mainUrl`/icons).

- **`MadaraReader`** (`providers/MadaraReader.kt`) — base for the very common
  "Madara" WordPress manga/novel theme (selectors like `div.page-item-detail`,
  `wp-manga-chapter`, `ajax/chapters/`). **NOTE: the entire class is currently
  commented out** (`MadaraReader.kt:25–188`) and has no active subclasses, so it is
  documentation/dead code rather than a live base. It remains a useful reference
  for the Madara selector pattern.

- **`AllNovelProvider`** (`open`, `providers/AllNovelProvider.kt`) — base for the
  "novelfull"-style template; extended by **`NovelBinProvider`**,
  **`NovelFullProvider`**, **`NovelFullNETProvider`**.

- **`MoreNovelProvider`** (`open`) → **`MeioNovelProvider`** → **`NovLoveProvider`**
  — a chain of WordPress-variant sites.

- **`LibReadProvider`** (`open`) → **`FreewebnovelProvider`** (the active
  `FreeWebNovelProvider.kt` defines `class FreewebnovelProvider : LibReadProvider()`;
  the file also contains an older `: MainAPI()` version, kept commented/inactive).

- **`WuxiaBoxProvider`** (`open`) → **`FanMtlnProvider`**.

- **`ReadfromnetProvider`** (`open`) → **`GraycityProvider`** (in `EfremnetProvider.kt`).

The remaining ~30 providers (e.g. `RoyalRoadProvider`, `AnnasArchive`,
`ScribblehubProvider`, `LnMTLProvider`, `WattpadProvider`, `WtrLabProvider`,
`MtlNovelProvider`) extend `MainAPI` directly because their sites are bespoke.

---

## 8. The typical search → load → loadHtml flow

Using `RoyalRoadProvider` (`providers/RoyalRoadProvider.kt`) as the canonical
direct-`MainAPI` example:

1. **search** (`:303`) — `app.get("$mainUrl/fictions/search?title=$query")`, Jsoup
   `.select("div.fiction-list-item")`, and for each match emit
   `newSearchResponse(url, name) { posterUrl = ...; rating = ... }`. Returns
   `List<SearchResponse>`. The UI shows these as cards.

2. **load** (`:323`) — user taps a result; `app.get(url)`, parse the book page:
   title, chapter table rows → `newChapterData(name, url) { dateOfRelease = ... }`,
   then `newStreamResponse(url, name, data) { ... }` filling `synopsis`, `author`,
   `tags`, `rating`, `views`, `status` (via `setStatus`), `related` (via a separate
   `loadRelated` JSON call), `posterUrl`. Returns a `StreamResponse`. This goes
   through `APIRepository.load`, so it is cached for 10 min and rate-limited
   (RoyalRoad sets `rateLimitTime = 500L`).

3. **loadHtml** (`:430`) — user opens a chapter; `app.get(chapterUrl)`, select
   `div.chapter-content`, run site-specific cleanup (RoyalRoad injects author-notes
   and strips CSS-hidden anti-scrape classes), and return the inner HTML string.
   `APIRepository.loadHtml` then strips ads and hands the HTML to the reader /
   EPUB builder.

For an `EpubResponse` provider (`AnnasArchive`), step 2 returns download links
instead of chapters and there is no `loadHtml`; the app downloads the file via
`DownloadLink.get()`.

---

## 9. How to add a new provider (step by step)

1. **Create the class** in `app/src/main/java/com/lagradost/quicknovel/providers/`,
   e.g. `MyNovelProvider.kt`:
   ```kotlin
   class MyNovelProvider : MainAPI() {
       override val name = "My Novel Site"      // permanent key — choose carefully
       override val mainUrl = "https://mynovelsite.com"
       override val lang = "en"
       override val hasMainPage = true          // optional
       override val iconId = R.drawable.ic_mysite // add a drawable
   }
   ```
   If the target site runs a known CMS already covered by a base class
   (WordPress/WPReader, novelfull/`AllNovelProvider`, LibRead/`LibReadProvider`,
   etc.), extend that base instead of `MainAPI` and override only what differs.

2. **Implement `search`** — fetch with `app.get(...)`, parse with Jsoup
   (`.document` / `.text`), and return `newSearchResponse(name, url) { posterUrl = ...; rating = ... }`
   for each hit. Use `fixUrlNull` for relative poster/links.

3. **Implement `load`** — parse the book page; build the chapter list with
   `newChapterData`, then return `newStreamResponse(name, url, data) { ... }`
   (or `newEpubResponse` for download-only sites). Use `setStatus(...)` for status
   and remember ratings are on a 0–1000 scale.

4. **Implement `loadHtml`** — return the chapter body HTML string. You do **not**
   need to strip `script`/`iframe`/`.adsbygoogle` — `APIRepository.removeAds` does
   that. Return `null` (or blank) on failure.

5. **(Optional)** implement `loadMainPage` (+ `mainCategories`/`orderBys`/`tags`)
   if `hasMainPage = true`, and `loadReviews` if `hasReviews = true`.

6. **Cloudflare / rate limits** — set `override val usesCloudFlareKiller = true`
   if the site is behind Cloudflare; set `override val rateLimitTime = <ms>` if it
   bans rapid `load`s.

7. **Register it** in `util/Apis.kt`: add `import com.lagradost.quicknovel.providers.MyNovelProvider`
   and add `MyNovelProvider(),` to the `apis = arrayOf(...)` list (`Apis.kt:64`).
   The list is auto-sorted by name; nothing else is required.

8. **Add the icon drawable** referenced by `iconId`, and (optionally) verify with
   `Apis.testProviders()` which exercises the full `search → load → loadHtml` path.

That's the whole contract: subclass `MainAPI`, implement the three scraping
methods using the builder helpers, and add one line to `Apis.apis`. Caching,
ad-stripping, rate limiting, Cloudflare bypass, error handling, language filtering,
and UI wiring are all provided by the surrounding `APIRepository` / `MainAPI` /
`Apis` infrastructure.
