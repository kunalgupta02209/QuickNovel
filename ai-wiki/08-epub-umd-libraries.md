# 08 - EPUB & UMD Format Libraries (`me.ag2s.*`)

## Overview

QuickNovel does not implement EPUB serialization itself. It vendors a third-party
EPUB reader/writer library (a fork of `epublib`) and a UMD e-book parser under the
**`me.ag2s`** namespace, separate from the app's own **`com.lagradost.quicknovel`**
namespace. Treat everything under `app/src/main/java/me/ag2s/` as an embedded library:
QuickNovel only touches a small public API surface (`EpubBook`, `EpubWriter`,
`EpubReader`, `Resource`, `Metadata`, `Author`, `MediaType(s)`, `Spine`,
`TableOfContents`, `TOCReference`, `AndroidZipFile`).

### Namespace / provenance note (important)

- **`com.lagradost.quicknovel.*`** = QuickNovel's own application code.
- **`me.ag2s.*`** = vendored library code. **Do not refactor it as if it were app code.**
- Provenance is documented in `app/src/main/java/me/ag2s/readme.txt`:
  the EPUB code was copied from
  [gedoor/legado](https://github.com/gedoor/legado)'s `me/ag2s` book module
  (itself a fork chain of `positiondev/epublib` → `psiegman/epublib`), because the
  upstream Maven artifacts had ZIP/EPUB3 bugs. License is **LGPL v3.0**
  (`app/src/main/java/me/ag2s/LICENSE`).
- The readme explicitly states: *"The only thing that may be modified is the
  `EPUB_GENERATOR_NAME`."* In other words, keep local changes to this code minimal —
  avoid gratuitous renames, reformatting, or "cleanup" refactors so the vendored code
  stays diff-able against upstream.

There is a name collision to be aware of: QuickNovel has its own
`com.lagradost.quicknovel.mvvm.Resource` (an MVVM result/loading wrapper) which is
**unrelated** to `me.ag2s.epublib.domain.Resource` (an EPUB file entry). Both appear in
`ReadActivityViewModel.kt` and `BookDownloader2.kt` via different imports.

---

## Part 1 — EPUB library (`me.ag2s.epublib`)

### Package layout

| Package | Role |
|---|---|
| `me.ag2s.epublib.domain` | The data model (`EpubBook`, `Resource`, `Resources`, `Metadata`, `Spine`, `TableOfContents`, `TOCReference`, `Author`, `MediaType(s)`, `Guide`, `Identifier`, lazy-resource providers). |
| `me.ag2s.epublib.epub` | Serialization: `EpubWriter`, `EpubReader`, package-document (OPF) and NCX (TOC) readers/writers. |
| `me.ag2s.epublib.util` | Helpers: IO, string, XML stream reading, and a custom `util.zip` ZIP layer (`AndroidZipFile`, `ZipFileWrapper`). |
| `me.ag2s.epublib.browsersupport` | Navigation helpers (`Navigator`, `NavigationHistory`). **Unused by QuickNovel.** |

### The data model (what an EPUB is modeled as)

`me.ag2s.epublib.domain.EpubBook` (`domain/EpubBook.java`) is the central aggregate.
Per the EPUB spec it holds three independent indexes over a shared resource pool:

- **`Resources` resources** — the flat pool of every file in the book (XHTML chapters,
  images, CSS, fonts, the OPF, the NCX). Backed by an href→`Resource` map; accessed in
  the reader via `book.getResources().getResourceMap()`.
- **`Spine` spine** — the linear reading order (`List<SpineReference>`, each wrapping a
  `Resource`). This is "what you see reading front to back."
- **`TableOfContents` tableOfContents** — a tree of `TOCReference` nodes (each has a
  title, a target `Resource`, an optional fragment id, and `children`). The TOC may
  differ in order/content from the spine.
- **`Guide` guide** — references to special pages (cover, glossary, …).
- Plus `Metadata metadata`, `Resource coverImage`, `opfResource`, `ncxResource`, and a
  `version` string (`"2.0"` default; `isEpub3()` switches NCX v2 vs v3 generation).

Key model classes:

- **`Resource`** (`domain/Resource.java`) — one file inside the EPUB. Carries `id`,
  `href`, `title`, `mediaType` (`MediaType`), `inputEncoding`, and `byte[] data`.
  Many constructors: `Resource(byte[] data, MediaType)`, `Resource(byte[] data, String href)`,
  `Resource(String id, byte[] data, String href, MediaType)`, etc. Media type is
  auto-inferred from the href extension when not supplied
  (`MediaTypes.determineMediaType`). Exposes `getInputStream()` and `getReader()` for
  reading content back out.
- **`Metadata`** (`domain/Metadata.java`) — Dublin-Core-style fields: lists of
  `Author` (authors / contributors), titles, descriptions, publishers, subjects,
  identifiers, dates, rights, types. Convenience: `addAuthor`, `addTitle`,
  `addDescription`, `getFirstTitle()`, `getAuthors()`.
- **`Author`** (`domain/Author.java`) — `firstname` / `lastname` pair. QuickNovel
  reconstructs a display name with `listOfNotNull(author.firstname, author.lastname).joinToString(" ")`.
- **`MediaType` / `MediaTypes`** (`domain/MediaType*.java`) — MIME + extension registry.
  Constants used by QuickNovel: `MediaTypes.XHTML`, `MediaTypes.EPUB`, `MediaTypes.NCX`.
- **`Spine`** (`domain/Spine.java`) — besides the reference list, exposes
  `getResourceIndex(Resource)` and `getSpineReferences()`, both used heavily by the reader.

### Writing an EPUB — `EpubWriter`

`me.ag2s.epublib.epub.EpubWriter` (`epub/EpubWriter.java`) is a **single-use,
non-thread-safe** serializer. Public surface QuickNovel uses is just the constructor and
`write(EpubBook book, OutputStream out)`. Internally `write(...)`:

1. `writeMimeType` — stores the `mimetype` entry uncompressed (STORED, with CRC) first,
   as the spec requires.
2. `writeContainer` — writes `META-INF/container.xml` pointing at `OEBPS/content.opf`.
3. `initTOCResource` — generates the NCX TOC (`NCXDocumentV2` for EPUB2 /
   `NCXDocumentV3` for EPUB3) from the book's `TableOfContents`.
4. `writeResources` — writes every `Resource` under `OEBPS/<href>`.
5. `writePackageDocument` — writes the OPF (`OEBPS/content.opf`) via
   `PackageDocumentWriter`.

There is also an optional progress `Callback` (`setCallback`) and a `BookProcessor`
pipeline hook, neither of which QuickNovel uses.

### Reading an EPUB — `EpubReader`

`me.ag2s.epublib.epub.EpubReader` (`epub/EpubReader.java`) parses an EPUB back into an
`EpubBook`. QuickNovel exclusively uses the **lazy** path:

```kotlin
val book = EpubReader().readEpubLazy(zipFile, "utf-8")
```

`readEpubLazy(...)` loads structure (OPF metadata, spine, TOC) but leaves resource bytes
as `LazyResource`s pulled from the ZIP on demand — important for large books on Android.
It is given a `me.ag2s.epublib.util.zip.AndroidZipFile` (wrapping a `ParcelFileDescriptor`
from Android's `ContentResolver`). The custom `util.zip` layer exists because Android's
stock `ZipFile` had the EPUB3 issues mentioned in the readme.

---

## Part 2 — How QuickNovel uses the EPUB library

### Writing during download — `BookDownloader2.kt`

`app/src/main/java/com/lagradost/quicknovel/BookDownloader2.kt` builds and serializes
EPUBs in several flows (imports at lines 87–94):

- **Standard novel download → EPUB** (`turnToEpub`-style flow, ~lines 732–799):
  1. `val book = EpubBook()`.
  2. Populate `book.metadata`: `metadata.addAuthor(Author(author))`,
     `metadata.addDescription(synopsis)`, `metadata.addTitle(name)`.
  3. Set cover if present: `book.coverImage = Resource(pFile.readBytes(), MediaType("cover", ".jpg"))`.
  4. For each downloaded chapter, build an XHTML resource:
     `Resource("id$threadIndex", chap.html.toByteArray(), "chapter$threadIndex.html", MediaTypes.XHTML)`
     and add it as a TOC section + spine entry via
     `book.addSection(chapter.title, resource)`.
  5. `EpubWriter().write(book, fileStream)`.
  Note `EpubBook.addSection(title, resource)` is the convenience method that adds the
  resource to `resources`, appends a `TOCReference`, and (if absent) a `SpineReference` —
  i.e. one call wires a chapter into all three indexes.

- **PDF import → EPUB** (~lines 1865–1965): creates an `EpubBook` with title/author,
  writes a placeholder EPUB immediately so the system registers the file, then as pages
  are processed adds image `Resource`s (`book.addResource(res)`, first image becomes
  `book.coverImage`) and chapter sections, and finally re-serializes with
  `EpubWriter().write(book, fos)`.

- **Partial-import preload** (`preloadPartialImportedPdf`, ~lines 2000–2027): same
  pattern — assemble `EpubBook` from temp files, `EpubWriter().write(...)`.

### Importing an existing EPUB — `BookDownloader2.kt`

`downloadWorkThread(data: Uri, …)` (~line 2038) ingests a user-supplied `.epub`:
opens a `ParcelFileDescriptor`, wraps it in `AndroidZipFile`, and calls
`EpubReader().readEpubLazy(zipFile, "utf-8")`. It then pulls `book.metadata.authors`
and `book.metadata.firstTitle` for display. (Heuristic: if there is no cover image, it
assumes converted-junk metadata and falls back to the file name.)

### Reading in the reader — `ReadActivityViewModel.kt`

`app/src/main/java/com/lagradost/quicknovel/ReadActivityViewModel.kt` (imports
`EpubBook`, `TOCReference`, `EpubReader`, `AndroidZipFile` at lines 92–95) wraps a parsed
book in `class RegularBook(val data: EpubBook) : AbstractBook()` (line 295):

- **Load**: `EpubReader().readEpubLazy(zipFile, "utf-8")` (~line 1201).
- **Chapter list**: flattens `data.tableOfContents.tocReferences` into a linear
  `List<TOCReference>`, filtering each by `data.spine.getResourceIndex(ref.resource) != -1`
  to drop entries pointing at resources missing from the spine (defends against corrupt
  EPUBs). If the flattened TOC is empty/degenerate (`size <= 1`), it falls back to
  synthesizing chapters from `data.spine.spineReferences` (linear ones only).
- **Chapter content** (`getChapterData`, ~line 357): finds the spine index of the
  current TOC resource and of the next one, then concatenates the HTML of every spine
  resource in `[startIdx, endIdx)`. Each resource's bytes are read via
  `ref.resource.reader.readText()`, parsed with Jsoup, and `<img>`/`<image>` `src`/
  `xlink:href` attributes are rewritten to resolve relative paths.
- **Images** (`loadImage`, ~line 333): looks the href up in
  `data.resources.resourceMap` (with URL-decoding and a filename-only fallback) and
  returns the resource's `data` bytes.
- **Metadata** (`author()`, `title()`): reads `data.metadata.authors` and `data.title`.

So the round trip is: **download → `EpubBook` + `EpubWriter` → `.epub` on disk →
`EpubReader.readEpubLazy` → `RegularBook` → spine/TOC traversal in the reader.**

---

## Part 3 — UMD library (`me.ag2s.umdlib`)

### What it is

UMD (优美的电子书 / a Chinese e-book container format) is parsed by
`me.ag2s.umdlib.umd.UmdReader` (`umd/UmdReader.java`). UMD is a binary, segment-based
format (magic header `0xde9a9b89`, `#`/`$`-prefixed sections). The reader decodes header
metadata (title, author, date, book type, publisher), chapter offsets/titles, body text
(zlib-compressed, via `UmdUtils.decompress`), and an optional JPEG cover.

Data model (`me.ag2s.umdlib.domain`):

- **`UmdBook`** (`domain/UmdBook.java`) — aggregate of `UmdHeader` (metadata),
  `UmdChapters` (titles + contents), `UmdCover`, `UmdEnd`. It can also **build/serialize**
  a UMD file via `buildUmd(OutputStream)`, so the library is read+write capable.
- `UmdHeader`, `UmdChapters`, `UmdCover`, `UmdEnd` — the four binary parts.
- `me.ag2s.umdlib.tool` — `StreamReader` (little-endian primitive reads), `UmdUtils`
  (decompress, unicode decode), `WrapOutputStream`.

Entry points: `new UmdReader().read(InputStream)` → `UmdBook`, and
`umdBook.buildUmd(OutputStream)` for writing.

### When QuickNovel uses it

**Currently: not at all.** A repository-wide search shows `Umd`/`umdlib` symbols appear
**only within `me.ag2s.umdlib` itself** — there are no references from any
`com.lagradost.quicknovel` source file. The UMD library is vendored (it ships in the
same `me/ag2s` book module copied from legado) but is **dead/unwired code** in the current
QuickNovel codebase. Keep it for parity with upstream / potential future UMD import
support, but be aware no app flow exercises it today.

---

## Maintenance guidance

- **Treat `me.ag2s` as a vendored dependency.** Per `me/ag2s/readme.txt`, restrict edits
  to what's strictly necessary (the readme calls out `EPUB_GENERATOR_NAME` as the only
  intended local change). Avoid wholesale reformatting/renaming so the code remains
  diffable against the upstream legado/epublib source and easy to re-sync.
- **Respect the LGPL-3.0 license** (`me/ag2s/LICENSE`).
- **Watch the `Resource` name clash**: in QuickNovel files, `me.ag2s.epublib.domain.Resource`
  (EPUB file entry) and `com.lagradost.quicknovel.mvvm.Resource` (MVVM result wrapper)
  coexist behind different imports — don't conflate them.
- The reader deliberately defends against corrupt EPUBs (spine-index validity checks,
  TOC→spine fallback in `RegularBook`); preserve those guards when touching reader logic.

## Key files

| File | Role |
|---|---|
| `app/src/main/java/me/ag2s/readme.txt` | Provenance & "treat as vendored" note |
| `app/src/main/java/me/ag2s/LICENSE` | LGPL-3.0 license |
| `app/src/main/java/me/ag2s/epublib/domain/EpubBook.java` | Central EPUB aggregate (resources/spine/TOC/metadata) |
| `app/src/main/java/me/ag2s/epublib/domain/Resource.java` | A single file entry in the EPUB |
| `app/src/main/java/me/ag2s/epublib/domain/Metadata.java` | Dublin-Core metadata model |
| `app/src/main/java/me/ag2s/epublib/domain/Spine.java` / `TableOfContents.java` / `TOCReference.java` | Reading order + TOC tree |
| `app/src/main/java/me/ag2s/epublib/epub/EpubWriter.java` | Serialize `EpubBook` → `.epub` |
| `app/src/main/java/me/ag2s/epublib/epub/EpubReader.java` | Parse `.epub` → `EpubBook` (`readEpubLazy`) |
| `app/src/main/java/me/ag2s/epublib/util/zip/AndroidZipFile.java` | Android ZIP wrapper used by the reader |
| `app/src/main/java/me/ag2s/umdlib/umd/UmdReader.java` | UMD binary parser (currently unused by app) |
| `app/src/main/java/me/ag2s/umdlib/domain/UmdBook.java` | UMD aggregate + `buildUmd` writer |
| `app/src/main/java/com/lagradost/quicknovel/BookDownloader2.kt` | Writes EPUBs on download/import; imports via `EpubReader` |
| `app/src/main/java/com/lagradost/quicknovel/ReadActivityViewModel.kt` | `RegularBook` wraps `EpubBook` for the reader |
