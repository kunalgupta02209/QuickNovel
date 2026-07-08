package com.lagradost.quicknovel

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaPlayer
import android.speech.tts.Voice
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.Log
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.core.text.getSpans
import androidx.core.text.toSpanned
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.lagradost.quicknovel.BaseApplication.Companion.context
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeyClass
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKeyClass
import com.lagradost.quicknovel.BookDownloader2Helper.getQuickChapter
import com.lagradost.quicknovel.CommonActivity.TAG
import com.lagradost.quicknovel.CommonActivity.activity
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.TTSHelper.parseTextToSpans
import com.lagradost.quicknovel.TTSHelper.preParseHtml
import com.lagradost.quicknovel.TTSHelper.ttsParseText
import android.os.Build
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.mvvm.letInner
import com.lagradost.quicknovel.tts.ModelDownloadManager
import com.lagradost.quicknovel.tts.OnDeviceTtsEngine
import com.lagradost.quicknovel.tts.TtsAudioCache
import com.lagradost.quicknovel.tts.TtsEngine
import com.lagradost.quicknovel.tts.TtsModels
import com.lagradost.quicknovel.ui.TtsEngineType
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.mvvm.map
import com.lagradost.quicknovel.mvvm.safe
import com.lagradost.quicknovel.mvvm.safeApiCall
import com.lagradost.quicknovel.mvvm.safeAsync
import com.lagradost.quicknovel.mvvm.throwableToResource
import com.lagradost.quicknovel.providers.RedditProvider
import com.lagradost.quicknovel.ui.OrientationType
import com.lagradost.quicknovel.ui.ReadingType
import com.lagradost.quicknovel.ui.ScrollIndex
import com.lagradost.quicknovel.ui.ScrollVisibilityIndex
import com.lagradost.quicknovel.ui.UiText
import com.lagradost.quicknovel.ui.toScroll
import com.lagradost.quicknovel.ui.toUiText
import com.lagradost.quicknovel.ui.txt
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.CoilImagesPlugin
import com.lagradost.quicknovel.util.CoilImagesPlugin.CoilStore
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.Coroutines.runOnMainThread
import com.lagradost.quicknovel.util.GoogleTranslateOnline
import com.lagradost.safefile.closeQuietly
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImageSizeResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.TOCReference
import me.ag2s.epublib.epub.EpubReader
import me.ag2s.epublib.util.zip.AndroidZipFile
import org.commonmark.node.Node
import org.jsoup.Jsoup
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

const val DEF_FONT_SIZE: Int = 14
const val DEF_HORIZONTAL_PAD: Int = 20
const val DEF_VERTICAL_PAD: Int = 0

class PreferenceDelegate<T : Any>(
    val key: String, val default: T, private val klass: KClass<T>
) {
    // simple cache to make it not get the key every time it is accessed, however this requires
    // that ONLY this changes the key
    private var cache: T? = null

    operator fun getValue(self: Any?, property: KProperty<*>) =
        cache ?: getKeyClass(key, klass.java).also { newCache -> cache = newCache } ?: default

    operator fun setValue(
        self: Any?,
        property: KProperty<*>,
        t: T?
    ) {
        cache = t
        if (t == null) {
            removeKey(key)
        } else {
            setKeyClass(key, t)
        }
    }
}

class PreferenceDelegateLiveView<T : Any>(
    val key: String, val default: T, klass: KClass<T>, private val _liveData: MutableLiveData<T>
) {
    // simple cache to make it not get the key every time it is accessed, however this requires
    // that ONLY this changes the key
    private var cache: T

    init {
        cache = getKeyClass(key, klass.java) ?: default
        _liveData.postValue(cache)
    }

    operator fun getValue(self: Any?, property: KProperty<*>) = cache

    operator fun setValue(
        self: Any?,
        property: KProperty<*>,
        t: T?
    ) {
        cache = t ?: default
        _liveData.postValue(cache)
        if (t == null) {
            removeKey(key)
        } else {
            setKeyClass(key, t)
        }
    }
}

abstract class AbstractBook {
    open fun resolveUrl(url: String): String {
        return url
    }

    abstract val canReload: Boolean

    abstract fun size(): Int
    abstract fun title(): String
    abstract fun getChapterTitle(index: Int): UiText
    abstract fun getLoadingStatus(index: Int): String?

    @Throws
    open fun loadImage(image: String): ByteArray? {
        return null
    }

    fun loadImageBitmap(image: String): Bitmap? {
        try {
            val data = this.loadImage(image) ?: return null
            return BitmapFactory.decodeByteArray(data, 0, data.size)
        } catch (t: Throwable) {
            logError(t)
            return null
        }
    }

    @WorkerThread
    @Throws
    abstract suspend fun getChapterData(index: Int, reload: Boolean): String

    abstract fun expand(last: String): Boolean

    @WorkerThread
    @Throws
    protected abstract suspend fun posterBytes(): ByteArray?

    private var poster: Bitmap? = null

    init {
        ioSafe {
            poster = posterBytes()?.let { byteArray ->
                BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
            }
        }
    }

    fun poster(): Bitmap? {
        return poster
    }

    abstract fun author(): String?
}

class QuickBook(val data: QuickStreamData) : AbstractBook() {
    override fun resolveUrl(url: String): String {
        return Apis.getApiFromNameNull(data.meta.apiName)?.fixUrl(url) ?: url
    }

    override fun author(): String? {
        return data.meta.author
    }

    override val canReload = true

    override fun size(): Int {
        return data.data.size
    }

    override fun title(): String {
        return data.meta.name
    }

    override fun getChapterTitle(index: Int): UiText {
        return data.data[index].name.toUiText()
    }

    override fun getLoadingStatus(index: Int): String {
        return data.data[index].url
    }

    override suspend fun getChapterData(index: Int, reload: Boolean): String {
        val ctx = context ?: throw ErrorLoadingException("Invalid context")
        return ctx.getQuickChapter(
            data.meta,
            data.data[index],
            index,
            reload
        )?.html ?: throw ErrorLoadingException("Error loading chapter")
    }

    override fun expand(last: String): Boolean {
        try {
            val elements =
                Jsoup.parse(last).allElements.filterNotNull()

            for (element in elements) {
                val href = element.attr("href") ?: continue

                val text =
                    element.ownText().replace(Regex("[\\[\\]().,|{}<>]"), "").trim()
                if (text.equals("next", true) || text.equals(
                        "next chapter",
                        true
                    ) || text.equals("next part", true)
                ) {
                    val name = RedditProvider.getName(href) ?: "Next"
                    data.data.add(ChapterData(name, href, null, null))
                    return true
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
        return false
    }

    override suspend fun posterBytes(): ByteArray? {
        val poster = data.poster
        if (poster != null) {
            try {
                return MainActivity.app.get(poster).okhttpResponse.body.bytes()
            } catch (t: Throwable) {
                logError(t)
            }
        }
        return null
    }
}

class RegularBook(val data: EpubBook) : AbstractBook() {
    private val allTocReferences: List<TOCReference>
    init {
        val flatTOC = mutableListOf<TOCReference>()
        fun flatten(refs: List<TOCReference>) {
            refs.forEach { ref ->
                //this variable is to corrupted epubs
                val isValid = data.spine.getResourceIndex(ref.resource) != -1
                if(isValid){
                    flatTOC.add(ref)
                    if (ref.children != null && ref.children.isNotEmpty()) {
                        flatten(ref.children)
                    }
                }
            }
        }
        flatten(data.tableOfContents.tocReferences)

        //this is to corrupted epubs
        allTocReferences = if (flatTOC.size <= 1) {
            data.spine.spineReferences
                .filter { it.isLinear }
                .mapIndexed { index, spineRef ->
                    val res = spineRef.resource
                    TOCReference(res.title ?: "Chapter ${index + 1}", res)
                }
        } else {
            flatTOC
        }
    }

    override val canReload = false

    override fun author(): String? {
        val author = data.metadata.authors.firstOrNull() ?: return null
        return listOfNotNull(author.firstname, author.lastname).joinToString(" ").ifBlank { null }
    }

    override fun loadImage(image: String): ByteArray? {
        val decodedImage = try { URLDecoder.decode(image, "UTF-8") } catch (e: Exception) { image }

        data.resources.resourceMap[decodedImage]?.data?.let { return it }
        data.resources.resourceMap[image]?.data?.let { return it }

        val fileName = decodedImage.substringAfterLast("/")
        return data.resources.resourceMap.values.find {
            val entryName = it.href.substringAfterLast("/")
            entryName.equals(fileName, ignoreCase = true)
        }?.data
    }

    override fun size(): Int = allTocReferences.size

    override fun title(): String = data.title ?: "Unknown Book"

    override fun getChapterTitle(index: Int): UiText {
        return allTocReferences.getOrNull(index)?.title?.toUiText()
            ?: txt(R.string.chapter_format, (index + 1).toString())
    }

    override fun getLoadingStatus(index: Int): String? = null

    override suspend fun getChapterData(index: Int, reload: Boolean): String {
        val start = allTocReferences[index].resource
        val startIdx = data.spine.getResourceIndex(start)

        val end = allTocReferences.getOrNull(index + 1)?.resource
        var endIdx = data.spine.getResourceIndex(end)
        if (endIdx == -1) {
            endIdx = data.spine.spineReferences.size
        }
        val builder = StringBuilder()

        for (i in startIdx until endIdx) {
            try {
                val ref = data.spine.spineReferences[i]

                // I have no idea, but nonlinear = stop?
                if (!ref.isLinear && i != startIdx) continue

                val html = ref.resource.reader.readText()
                val doc = Jsoup.parse(html)
                val basePath = ref.resource.href.substringBeforeLast("/", "")

                doc.select("img, image").forEach { img ->
                    val attrName = if (img.tagName() == "image") "xlink:href" else "src"
                    var src = img.attr(attrName)
                    if (src.isNotEmpty() && !src.startsWith("http") && !src.startsWith("data:")) {
                        try {
                            val decodedSrc = URLDecoder.decode(src, "UTF-8")
                            src = resolveRelativePath(basePath, decodedSrc)
                        } catch (e: Throwable) {
                            logError(e)
                        }
                    }
                    if (img.tagName() == "image") {
                        val newImg = doc.createElement("img")
                        newImg.attr("src", src)
                        img.replaceWith(newImg)
                    } else {
                        img.attr("src", src)
                    }
                }

                builder.append(doc.body().html())
            } catch (t: Throwable) {
                logError(t)
            }
        }
        return builder.toString()
    }

    private fun resolveRelativePath(basePath: String, relativePath: String): String {
        val cleanRelative = relativePath.substringBefore("?").substringBefore("#")

        val fullPath = if (cleanRelative.startsWith("/") || basePath.isEmpty()) {
            cleanRelative
        } else {
            "$basePath/$cleanRelative"
        }

        val parts = fullPath.split("/")
        val resolvedParts = ArrayDeque<String>()

        for (part in parts) {
            when (part) {
                "", "." -> continue
                ".." -> if (resolvedParts.isNotEmpty()) resolvedParts.removeLast()
                else -> resolvedParts.addLast(part)
            }
        }
        return resolvedParts.joinToString("/")
    }

    override fun expand(last: String): Boolean = false

    override suspend fun posterBytes(): ByteArray? = data.coverImage?.data
}

class MLException(cause: Throwable) : Exception(cause)

data class LiveChapterData(
    val index: Int,
    /** Translated */
    val spans: List<TextSpan>,
    /** Translated */
    val rendered: Spanned,
    /** Non-Translated */
    val originalRendered: Spanned,
    /** Non-Translated */
    val originalSpans: ArrayList<TextSpan>,

    val title: UiText,
    val rawText: String,
    /** Plain-text chapter title, spoken by TTS before the chapter content. Blank/null = not spoken. */
    val ttsTitle: String? = null,
    //val ttsLines: List<TTSHelper.TTSLine>
) {
    // tts lines are lazy because not everyone uses tts
    val ttsLines by lazy {
        val lines = ttsParseText(rendered.substring(0, rendered.length), index)
        // Read the chapter title aloud before its content. Placed at char 0 so it is skipped
        // automatically when resuming mid-chapter (the seek matches on startChar), and only ever
        // spoken when a chapter is entered from the top.
        val spokenTitle = ttsTitle?.trim()
        if (!spokenTitle.isNullOrBlank()) {
            lines.add(0, TTSHelper.TTSLine(spokenTitle, startChar = 0, endChar = 0, index = index))
        }
        lines
    }
}

data class ChapterUpdate(
    val data: ArrayList<SpanDisplay>,
    val seekToDesired: Boolean
)

class ReadActivityViewModel : ViewModel() {
    lateinit var book: AbstractBook
    private lateinit var markwon: Markwon
    private var isInApp: Boolean = true
    private var leftAppAt: ScrollIndex? = null
    private var mlTranslator: Translator? = null

    fun leftApp() {
        lastChangeIndex?.let { setScrollKeys(it) }
        isInApp = false
        leftAppAt = desiredIndex
    }

    fun resumedApp() {
        val leftAt = leftAppAt
        val resumeAt = desiredIndex

        leftAppAt = null
        isInApp = false

        // if we resume the app and the desired index is different from what we are at currently
        // then we scroll to it
        if (leftAt != null && resumeAt != null && leftAt != resumeAt) {
            scrollToDesired(resumeAt)
        }
    }

    //private lateinit var reducer: MarkwonReducer

    fun canReload(): Boolean {
        return book.canReload
    }


    var mlSettings
        get() = getKey<MLSettings>(EPUB_CURRENT_ML, book.title()) ?: MLSettings("en", "en", false)
        set(value) = setKey(EPUB_CURRENT_ML, book.title(), value)

    private val _chapterData: MutableLiveData<ChapterUpdate> =
        MutableLiveData<ChapterUpdate>(null)
    val chapter: LiveData<ChapterUpdate> = _chapterData

    // we use bool as we cant construct Nothing, does not represent anything
    val _loadingStatus: MutableLiveData<Resource<Boolean>> =
        MutableLiveData<Resource<Boolean>>(null)
    val loadingStatus: LiveData<Resource<Boolean>> = _loadingStatus

    private val _chaptersTitles: MutableLiveData<List<UiText>> =
        MutableLiveData<List<UiText>>(null)
    val chaptersTitles: LiveData<List<UiText>> = _chaptersTitles

    private val _title: MutableLiveData<String> =
        MutableLiveData<String>(null)
    val title: LiveData<String> = _title

    private val _chapterTile: MutableLiveData<UiText> =
        MutableLiveData<UiText>(null)
    val chapterTile: LiveData<UiText> = _chapterTile

    private val _bottomVisibility: MutableLiveData<Boolean> =
        MutableLiveData<Boolean>(false)
    val bottomVisibility: LiveData<Boolean> = _bottomVisibility

    private val _ttsStatus: MutableLiveData<TTSHelper.TTSStatus> =
        MutableLiveData<TTSHelper.TTSStatus>(TTSHelper.TTSStatus.IsStopped)
    val ttsStatus: LiveData<TTSHelper.TTSStatus> = _ttsStatus

    private val _ttsLine: MutableLiveData<TTSHelper.TTSLine?> =
        MutableLiveData<TTSHelper.TTSLine?>(null)
    val ttsLine: LiveData<TTSHelper.TTSLine?> = _ttsLine

    // true = the highlighted line's audio is still generating (skip beyond the ready look-ahead) —
    // the reader flickers the highlight until playback starts.
    private val _ttsPending = MutableLiveData(false)
    val ttsPending: LiveData<Boolean> = _ttsPending

    // One-off: play was requested with on-device TTS selected but no voice downloaded (and thus no
    // way to synthesize or play cached audio) -> the reader opens the voice picker to prompt a choice.
    private val _promptModelDownload = MutableLiveData(false)
    val promptModelDownload: LiveData<Boolean> = _promptModelDownload
    fun consumeModelPrompt() { _promptModelDownload.value = false }

    private fun onDeviceModelReady(): Boolean = context?.let {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && modelDownloads(it).isReady(ttsOnDeviceModel)
    } ?: false

    // Sentences currently being synthesized in the background (drives the reader's "generating"
    // pulsating underline). The global tracker is filtered to the active book/voice scope.
    private val _ttsCacheScope = MutableLiveData<Triple<String, String, Int>?>(null) // (bookId, modelId, sid)
    private val _ttsGenerating = MediatorLiveData<List<TTSHelper.TTSLine>>(emptyList()).apply {
        addSource(com.lagradost.quicknovel.tts.TtsGenerationTracker.snapshot) { recomputeGenerating() }
        addSource(_ttsCacheScope) { recomputeGenerating() }
    }
    val ttsGenerating: LiveData<List<TTSHelper.TTSLine>> = _ttsGenerating

    private fun recomputeGenerating() {
        val scope = _ttsCacheScope.value
        val set = com.lagradost.quicknovel.tts.TtsGenerationTracker.snapshot.value ?: emptySet()
        _ttsGenerating.value = if (scope == null) emptyList()
        else set.asSequence()
            .filter { it.bookId == scope.first && it.modelId == scope.second && it.sid == scope.third }
            .filter { it.endChar > it.startChar } // skip the zero-width title line
            .map { TTSHelper.TTSLine("", startChar = it.startChar, endChar = it.endChar, index = it.index) }
            .toList()
    }

    private fun refreshTtsCacheScope() {
        if (ttsEngineType != TtsEngineType.ON_DEVICE || !::book.isInitialized) { _ttsCacheScope.postValue(null); return }
        val def = com.lagradost.quicknovel.tts.TtsModels.byId(ttsOnDeviceModel)
        val bookId = runCatching { com.lagradost.quicknovel.tts.TtsAudioCache.bookIdFor(book) }.getOrNull()
        if (bookId == null) { _ttsCacheScope.postValue(null); return }
        val sid = com.lagradost.quicknovel.tts.TtsModels.parseVoice(ttsOnDeviceVoice)?.second ?: 0
        _ttsCacheScope.postValue(Triple(bookId, def.id, sid))
    }


    /*  private val _orientation: MutableLiveData<OrientationType> =
          MutableLiveData<OrientationType>(null)
      val orientation: LiveData<OrientationType> = _orientation

      private val _backgroundColor: MutableLiveData<Int> =
          MutableLiveData<Int>(null)
      val backgroundColor: LiveData<Int> = _backgroundColor

      private val _textColor: MutableLiveData<Int> =
          MutableLiveData<Int>(null)
      val textColor: LiveData<Int> = _textColor

      private val _textSize: MutableLiveData<Int> =
          MutableLiveData<Int>(null)
      val textSize: LiveData<Int> = _textSize*/

    // private val _textFont: MutableLiveData<String> =
    //     MutableLiveData<String>(null)
    // val textFont: LiveData<String> = _textFont

    private var lastVisibilitySwitchMs = 0L

    fun switchVisibility() {
        // Debounce: one physical tap must flip the chrome exactly ONCE. A tap on the reader can reach
        // two handlers (the RecyclerView item-touch listener AND a parent container's click when the
        // list doesn't consume the touch), which double-toggled — bars slid out and straight back in
        // ("system bars go away and come back", app bars appear to do nothing).
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastVisibilitySwitchMs < 300) return
        lastVisibilitySwitchMs = now
        // Main-thread setValue (not postValue) so the tap toggles the chrome on this frame, no delay.
        _bottomVisibility.value = !(_bottomVisibility.value ?: false)
    }


    private var chaptersTitlesInternal: ArrayList<UiText> = arrayListOf()

    var desiredIndex: ScrollIndex? = null
    var desiredTTSIndex: ScrollIndex? = null

    // One-shot: on a live voice/model change, resume the TTS driver at THIS sentence (not the scroll
    // anchor) so the current line is re-spoken in the new voice. Consumed once in startTTSThread.
    @Volatile
    private var ttsResumeAt: ScrollIndex? = null

    private fun updateChapters() {
        for (idx in chaptersTitlesInternal.size until book.size()) {
            chaptersTitlesInternal.add(book.getChapterTitle(idx))
        }
        _chaptersTitles.postValue(chaptersTitlesInternal)
    }

    private val chapterMutex = Mutex()
    private val chapterExpandMutex = Mutex()

    private val requested: HashSet<Int> = hashSetOf()

    private val loading: HashSet<Int> = hashSetOf()
    private val chapterData: HashMap<Int, Resource<LiveChapterData>?> = hashMapOf()
    private val hasExpanded: HashSet<Int> = hashSetOf()

    var currentIndex = Int.MIN_VALUE
        private set




    /** lower padding for preloading current-chapterPaddingBottom*/
    private var initPaddingBottom = 1//these are to reduce loadings times
    private var chapterPaddingBottom: Int = 1

    /** upper padding, for preloading current+chapterPaddingTop */
    private var initPaddingTop = 1
    private var chapterPaddingTop: Int = 2

    fun reloadChapter(index: Int) = ioSafe {
        hasExpanded.clear() // will unfuck the rest
        val notify = chapterMutex.withLock {
            chapterData[index] is Resource.Failure
        }
        loadIndividualChapter(index, reload = true, notify = notify)
        updateReadArea(seekToDesired = false)
    }

    fun reTranslateChapter(index: Int) = ioSafe {
        hasExpanded.clear() // will unfuck the rest
        val notify = chapterMutex.withLock {
            chapterData[index] is Resource.Failure
        }
        loadIndividualChapter(index, reload = false, reTranslate = true, notify = notify)
        updateReadArea(seekToDesired = false)
    }

    fun reloadChapter() {
        reloadChapter(currentIndex)
    }

    fun refreshChapters() = ioSafe {
        hasExpanded.clear() // will unfuck the rest
        chapterMutex.withLock {
            chapterData.clear()
        }
        _loadingStatus.postValue(Resource.Loading())
        loadIndividualChapter(currentIndex, reload = false, notify = true, postLoading = true)
        updateReadArea(seekToDesired = true)
    }

    private suspend fun updateIndexAsync(
        index: Int,
        notify: Boolean = true,
        postLoading: Boolean = false,
    ) {
        val range =
            if(!notify) (index - initPaddingBottom..index + initPaddingTop)
            else (index - chapterPaddingBottom..index + chapterPaddingTop)

        for (idx in range) {
            requested += idx
            loadIndividualChapter(idx, notify = notify, postLoading = postLoading)
        }

    }

    private fun updateIndex(index: Int) {
        val range = (index - chapterPaddingBottom .. index + chapterPaddingTop)
        val needToLoad = range.any { !requested.contains(it) }
        if (needToLoad)
            ioSafe {
                updateIndexAsync(index)
            }
    }

    fun onScroll(visibility: ScrollVisibilityIndex?) {
        if (visibility == null) return
        // dynamically increase padding in case of very small chapters with a maximum of 10 chapters
        val first = visibility.firstInMemory.index
        val last = visibility.lastInMemory.index
        chapterPaddingTop = minOf(10, maxOf(chapterPaddingTop, (last - first) + 1))

        val current = currentIndex

        val save = visibility.firstFullyVisible ?: visibility.firstInMemory
        desiredTTSIndex = visibility.firstFullyVisibleUnderLine?.toScroll()
        changeIndex(save.toScroll())

        // update the read area if changed index
        if (current != save.index) {
            updateReadArea()
            maybePrefetchOnOpen(save.index) // F2: cache this + the next chapter when a new chapter opens
        }

        // load forwards and backwards
        updateIndex(visibility.firstInMemory.index)
        updateIndex(visibility.lastInMemory.index)
    }

    private fun chapterIdxToSpanDisplay(index: Int): List<SpanDisplay> {
        return when (val data = chapterData[index]) {
            null -> emptyList()
            is Resource.Loading -> {
                listOf<SpanDisplay>(LoadingSpanned(data.url, index))
            }

            is Resource.Success -> {
                data.value.spans
            }

            is Resource.Failure -> listOf<SpanDisplay>(
                FailedSpanned(
                    reason = data.errorString.toUiText(),
                    index = index,
                    cause = data.cause
                )
            )
        }
    }

    // ChapterLoadSpanned(fromIndex, 0, index, text)
    private fun chapterIdxToSpanDisplayNextButton(index: Int, fromIndex: Int): SpanDisplay? {
        return chapterIdxToSpanDisplayNext(
            index,
            fromIndex
        ) { cIndex, innerIndex, loadIndex, name ->
            ChapterLoadSpanned(cIndex, innerIndex, loadIndex, name)
        }
    }

    private fun chapterIdxToSpanDisplayOverscrollButton(index: Int, fromIndex: Int): SpanDisplay? {
        return chapterIdxToSpanDisplayNext(
            index,
            fromIndex
        ) { cIndex, innerIndex, loadIndex, name ->
            ChapterOverscrollSpanned(cIndex, innerIndex, loadIndex, name)
        }
    }

    private fun chapterIdxToSpanDisplayNext(
        index: Int,
        fromIndex: Int,
        constructor: (Int, Int, Int, UiText) -> SpanDisplay
    ): SpanDisplay? {
        return when (val data = chapterData[index]) {
            is Resource.Loading -> LoadingSpanned(data.url, index)
            is Resource.Failure ->
                FailedSpanned(
                    reason = data.errorString.toUiText(),
                    index = index,
                    cause = data.cause
                )

            else -> chaptersTitlesInternal.getOrNull(index)
                ?.let { text -> constructor(fromIndex, 0, index, text) }
        }
    }

    private fun updateReadArea(seekToDesired: Boolean = false) {
        val cIndex = currentIndex
        val chapters = ArrayList<SpanDisplay>()
        val canReload = this.book.canReload
        when (readerType) {
            ReadingType.DEFAULT, ReadingType.INF_SCROLL -> {
                for (idx in cIndex - chapterPaddingBottom..cIndex + chapterPaddingTop) {
                    if (idx < chaptersTitlesInternal.size && idx >= 0)
                        chapters.add(
                            ChapterStartSpanned(
                                idx,
                                0,
                                chaptersTitlesInternal[idx],
                                canReload
                            )
                        )
                    chapters.addAll(chapterIdxToSpanDisplay(idx))
                }
            }

            ReadingType.BTT_SCROLL -> {
                chapterIdxToSpanDisplayNextButton(cIndex - 1, cIndex)?.let {
                    chapters.add(it)
                }

                chaptersTitlesInternal.getOrNull(cIndex)?.let { text ->
                    chapters.add(ChapterStartSpanned(cIndex, 0, text, canReload))
                }

                chapters.addAll(chapterIdxToSpanDisplay(cIndex))

                chapterIdxToSpanDisplayNextButton(cIndex + 1, cIndex)?.let {
                    chapters.add(it)
                }
            }

            ReadingType.OVERSCROLL_SCROLL -> {
                chapterIdxToSpanDisplayOverscrollButton(cIndex - 1, cIndex)?.let {
                    chapters.add(it)
                }

                chaptersTitlesInternal.getOrNull(cIndex)?.let { text ->
                    chapters.add(ChapterStartSpanned(cIndex, 0, text, canReload))
                }

                chapters.addAll(chapterIdxToSpanDisplay(cIndex))

                chapterIdxToSpanDisplayOverscrollButton(cIndex + 1, cIndex)?.let {
                    chapters.add(it)
                }
            }
        }

        _chapterData.postValue(ChapterUpdate(data = chapters, seekToDesired = seekToDesired))
    }

    private fun notifyChapterUpdate(index: Int, seekToDesired: Boolean = false) {
        val cIndex = currentIndex
        if (cIndex - chapterPaddingBottom <= index && index <= cIndex + chapterPaddingTop) {
            updateReadArea(seekToDesired)
        }
    }

    private val markwonMutex = Mutex()

    @WorkerThread
    private suspend fun loadIndividualChapter(
        index: Int,
        reload: Boolean = false,
        notify: Boolean = true,
        reTranslate: Boolean = false,
        postLoading: Boolean = false,
    ) {
        if (index < 0) return

        // set loading and return early if already loading or return cache
        chapterMutex.withLock {
            if (loading.contains(index)) return
            if (!reload && !reTranslate && chapterData.contains(index)) {
                return
            }

            loading += index
            chapterData[index] = Resource.Loading(null)
            if (notify) notifyChapterUpdate(index)
        }

        // we check for out of bounds and if it is out of bounds then try to expand it (Reddit next)
        // we lock it here to prevent duplicate loading when init
        chapterExpandMutex.withLock {
            val preSize = book.size()
            while (index >= book.size()) {
                // will only expand once per session per chapter
                if (hasExpanded.contains(book.size())) break
                hasExpanded += book.size()

                try {
                    // we assume that the text is cached
                    book.expand(book.getChapterData(book.size() - 1, reload = false))
                } catch (t: Throwable) {
                    logError(t)
                }
            }
            if (preSize != book.size()) updateChapters()
        }

        // if we are still out of bounds then return no more chapters
        if (index >= book.size()) {
            chapterMutex.withLock {
                // only push one no more chapters
                if (index == book.size()) {
                    chapterData[index] =
                        Resource.Failure(
                            null,
                            context?.getString(R.string.no_more_chapters) ?: "ERROR"
                        )
                } else {
                    chapterData[index] = null
                }
                loading -= index
                if (notify) notifyChapterUpdate(index)
            }
            return
        }

        // we have verified we are within bounds, then set the loading to the index url
        chapterMutex.withLock {
            chapterData[index] = Resource.Loading(book.getLoadingStatus(index))
            if (notify) notifyChapterUpdate(index)
        }

        // load the data and precalculate everything needed
        try {
            val data = safeApiCall {
                book.getChapterData(index, reload)
            }.map { text ->
                // Substitute LLM-fixed prose for the raw body when "show fixed" is on and a fix is cached.
                val rawText = preParseHtml(maybeFixedText(context, index, text), authorNotes)
                // val renderedBuilder = SpannableStringBuilder()
                // val lengths : IntArray
                // val nodes : Array<Node>
                val parsed: Node
                var rendered: Spanned
                val originalRendered: Spanned
                val originalSpans: ArrayList<TextSpan>
                var spans: ArrayList<TextSpan>

                markwonMutex.withLock {
                    parsed = markwon.parse(rawText)
                    rendered = markwon.render(parsed)

                    spans = parseTextToSpans(rendered, index)
                    originalSpans = spans
                    originalRendered = rendered

                    val asyncDrawables = rendered.getSpans<AsyncDrawableSpan>()
                    for (async in asyncDrawables) {
                        async.drawable.result =
                            book.loadImageBitmap(async.drawable.destination)?.toDrawable(
                                Resources.getSystem()
                            )
                    }

                    // translation may strip stuff, idk how to solve that in a clean way atm
                    translate(
                        rendered,
                        spans
                    ) { (progressChapter, progressInnerIndex, progressInnerTotal) ->
                        val progressText =
                            "${context?.getString(R.string.translating)} ${
                                book.getChapterTitle(
                                    progressChapter
                                )
                            } ($progressInnerIndex/$progressInnerTotal)"
                        if (postLoading) {
                            _loadingStatus.postValue(Resource.Loading(progressText))
                        } else {
                            chapterMutex.withLock {
                                chapterData[index] =
                                    Resource.Loading(progressText)
                                if (notify) notifyChapterUpdate(index)
                            }
                        }
                    }.let { (mlRender, mlSpans) ->
                        rendered = mlRender
                        spans = mlSpans
                    }
                }

                LiveChapterData(
                    index = index,
                    rendered = rendered,
                    spans = spans,
                    originalRendered = originalRendered,
                    originalSpans = originalSpans,
                    rawText = rawText,
                    title = book.getChapterTitle(index),
                    ttsTitle = book.getChapterTitle(index).asStringNull(context),
                )
            }

            // set the data and return
            chapterMutex.withLock {
                chapterData[index] = data
            }
        } catch (t: Throwable) {
            // Tasks.await may throw
            chapterMutex.withLock {
                chapterData[index] = throwableToResource(t)
            }
        } finally {
            chapterMutex.withLock {
                loading -= index
                if (notify) notifyChapterUpdate(index)
            }
        }
    }

    private fun hashString(text: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(text)
        val sb = StringBuilder()
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun getFinalTranslatedText(spans: ArrayList<TextSpan>, translatedLines: List<String>):Pair<SpannableStringBuilder, ArrayList<TextSpan>>{
        val builder = SpannableStringBuilder()
        val out = ArrayList<TextSpan>()
        spans.forEachIndexed { i, originalSpan ->
            val hasImage = originalSpan.text.getSpans<AsyncDrawableSpan>().isNotEmpty()
            val finalText =
                if (hasImage)
                    originalSpan.text
                else
                    (translatedLines.getOrNull(i)?: return@forEachIndexed).toSpanned()
            val start = builder.length
            builder.append(finalText)
            val end = builder.length
            builder.append('\n')
            out.add(TextSpan(finalText, start, end, originalSpan.index, originalSpan.innerIndex))
        }
        return builder to out
    }


    @Throws(MLException::class)
    private suspend fun translate(
        text: Spanned,
        spans: ArrayList<TextSpan>,
        loading: suspend (Triple<Int, Int, Int>) -> Unit
    ): Pair<Spanned, ArrayList<TextSpan>> {
        try {
            val currentSettings = mlSettings
            if (spans.isEmpty() || currentSettings.isInvalid()) return text to spans
            val textHash = hashString(
                text.trim().toString().toByteArray()
            )

            //the file
            val filePrefix =
                "ml_${textHash}.${currentSettings.from}_to_${currentSettings.to}.${
                if (currentSettings.useOnlineTranslation) "online" 
                else "offline"
            }"

            // read from cache if it exists
            // we assume that parseTextToSpans is equivalent from restoring from the builder
            // aka out == parseTextToSpans(builder)
            val cachedData = safe {
                context?.cacheDir?.let { dir ->
                    val cache = File(dir, "$filePrefix.txt")
                    if (cache.exists()) {
                        Log.i(TAG, "Cache exists for $filePrefix")
                        val lines = cache.readLines()
                        val (builder, out) = getFinalTranslatedText(spans, lines)
                        return@safe builder to out
                    }
                }
                return@safe null
            }
            if(cachedData != null) return cachedData


            var translatedList: List<String>

            // --- Online mode ---
            if (currentSettings.useOnlineTranslation) {
                translatedList = GoogleTranslateOnline.onlineTranslate(
                        spans.map { it.text.toString() },
                        currentSettings.from,
                        currentSettings.to
                    ){ progress, total ->
                        loading.invoke(Triple(spans[0].index, progress, total))
                    }

            }

            // --- Offline mode ---
            else {
                val translator = mlTranslator ?: return text to spans
                translatedList = spans.mapIndexed { i,  span ->
                    loading.invoke(Triple(span.index, i, spans.size))
                    try {
                        Tasks.await(translator.translate(span.text.toString()))
                    } catch (t: ExecutionException) {
                        throw t.cause ?: t
                    }
                }
            }

            val (builder, out) = getFinalTranslatedText(spans, translatedList)

            // atomically write the file by rename
            safe {
                context?.cacheDir?.let {
                    val cache = File(it, "$filePrefix.tmp")
                    cache.writeText(builder.toString())
                    safe { File(it, "$filePrefix.txt").delete() } // just in case
                    cache.renameTo(File(it, "$filePrefix.txt"))
                }
            }

            return builder to out
        } catch (t: Throwable) {
            throw MLException(t)
        }
    }

    @Throws
    suspend fun requireMLDownload(): Boolean {
        val settings = MLSettings(from = mlFromLanguage, to = mlToLanguage, mlUseOnlineTransaltion)
        if (settings.isInvalid() || mlUseOnlineTransaltion) {
            return false
        }
        val modelManager = RemoteModelManager.getInstance()

        for (model in arrayOf(settings.from, settings.to)) {
            if (model == "en") continue

            if (!Tasks.await(
                    modelManager.isModelDownloaded(
                        TranslateRemoteModel.Builder(model).build()
                    )
                )
            ) {
                return true
            }
        }

        return false
    }

    fun applyMLSettings(allowDownload: Boolean) = ioSafe {
        val settings = MLSettings(from = mlFromLanguage, to = mlToLanguage, mlUseOnlineTransaltion)
        if (settings.isValid() && allowDownload && safeAsync { requireMLDownload() } == true) {
            _loadingStatus.postValue(Resource.Loading("Downloading language"))
        }
        initMLFromSettings(settings, allowDownload)
        reloadMLForAllChapters()
    }

    private suspend fun reloadMLForAllChapters() {
        _loadingStatus.postValue(Resource.Loading(context?.getString(R.string.translating)))
        chapterMutex.withLock {
            val cIndex = currentIndex
            val lower = cIndex - chapterPaddingBottom
            val upper = cIndex + chapterPaddingTop

            val keys =
                chapterData.keys.toTypedArray() // deep copy it to avoid ConcurrentModificationException

            // remove all irrelevant cache so we do not translate outdated shit
            for (key in keys) {
                if (key < lower || key > upper) {
                    chapterData.remove(key)
                }
            }

            // update the rem cache
            for (entry in chapterData.entries) {
                val value = entry.value
                if (value !is Resource.Success) continue
                val success = value.value

                try {
                    translate(
                        success.originalRendered,
                        success.originalSpans
                    ) { (progressChapter, progressInnerIndex, progressInnerTotal) ->
                        _loadingStatus.postValue(
                            Resource.Loading(
                                "${context?.getString(R.string.translating)} ${
                                    book.getChapterTitle(
                                        progressChapter
                                    )
                                } ($progressInnerIndex/$progressInnerTotal)"
                            )
                        )
                    }.let { (mlRender, mlSpans) ->
                        entry.setValue(
                            Resource.Success(
                                success.copy(
                                    rendered = mlRender,
                                    spans = mlSpans,
                                )
                            )
                        )
                    }
                } catch (t: Throwable) {
                    entry.setValue(
                        throwableToResource(t)
                    )
                }
            }
        }

        // update what we have read
        updateReadArea()
        //refreshChapters()
    }

    private suspend fun initMLFromSettings(settings: MLSettings, allowDownload: Boolean) {
        try {
            mlTranslator?.closeQuietly()
            mlTranslator = null

            if (settings.isInvalid() || settings.useOnlineTranslation) {
                mlSettings = settings
                return
            }

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(settings.from)
                .setTargetLanguage(settings.to)
                .build()

            val translator = Translation.getClient(options)
            mlTranslator = translator

            if (allowDownload) {
                Tasks.await(
                    translator.downloadModelIfNeeded(), 120L, TimeUnit.SECONDS
                )//for bad wifi, like my 2mb/s one TT
            }

            mlSettings = settings
        } catch (_: TimeoutException) {
            showToast(R.string.unable_to_download_language)
            mlTranslator?.closeQuietly()
            mlTranslator = null
        } catch (t: Throwable) {
            logError(t)
        }
    }

    fun init(intent: Intent?, context: ReadActivity2) = ioSafe {
        _loadingStatus.postValue(Resource.Loading())
        initTTSSession(context)

        val loadedBook = safeApiCall {
            if (intent == null) throw ErrorLoadingException("No intent")

            val data = intent.data ?: throw ErrorLoadingException("Empty intent")
            val isFromEpub = intent.type != "quickstream"

            val epub = if (isFromEpub) {
                val fd = context.contentResolver.openFileDescriptor(data, "r")
                    ?: throw ErrorLoadingException("Unable to open file descriptor")
                val zipFile = AndroidZipFile(fd, "")
                val book = EpubReader().readEpubLazy(zipFile, "utf-8")
                RegularBook(book)
            } else {
                val input = context.contentResolver.openInputStream(data)
                    ?: throw ErrorLoadingException("Empty data")
                QuickBook(DataStore.mapper.readValue(input.reader().readText()))
            }

            if (epub.size() <= 0) {
                throw ErrorLoadingException("Empty book, failed to parse ${intent.type}")
            }
            epub
        }

        when (loadedBook) {
            is Resource.Success -> {
                init(loadedBook.value, context)

                initMLFromSettings(mlSettings, false)

                // cant assume we know a chapter max as it can expand

                val desiredChapterName = getKey<String>(EPUB_CURRENT_POSITION_CHAPTER, book.title())
                val desiredChapterIndex =
                    (0 until book.size()).firstOrNull {
                        loadedBook.value.getChapterTitle(it)
                            .asStringNull(context) == desiredChapterName
                    } ?: getKey<Int>(EPUB_CURRENT_POSITION, book.title()) ?: 0
                val loadedChapterIndex =
                    maxOf(desiredChapterIndex, 0)

                // we the current loaded thing here, but because loadedChapter can be >= book.size (expand) we have to check
                if (loadedChapterIndex < book.size()) {
                    _loadingStatus.postValue(
                        Resource.Loading(
                            book.getLoadingStatus(
                                loadedChapterIndex
                            )
                        )
                    )
                }

                currentIndex = loadedChapterIndex
                updateIndexAsync(loadedChapterIndex, notify = false, postLoading = true)

                if (book.size() <= 0) {
                    _loadingStatus.postValue(
                        Resource.Failure(
                            null,
                            "Invalid chapter data when trying to load chapter $loadedChapterIndex when the book only has ${book.size()} chapters"
                        )
                    )
                    return@ioSafe
                }

                // if we are reading a book that sub/resize for some reason, this will clamp it into the correct range
                if (loadedChapterIndex >= book.size()) {
                    currentIndex = book.size() - 1
                    updateIndexAsync(currentIndex, notify = false)
                    showToast("Resize $loadedChapterIndex -> $currentIndex", Toast.LENGTH_LONG)
                }

                val char = getKey(
                    EPUB_CURRENT_POSITION_SCROLL_CHAR, book.title()
                ) ?: 0

                val innerIndex = innerCharToIndex(currentIndex, char) ?: 0

                // don't update as you want to seek on update
                changeIndex(ScrollIndex(currentIndex, innerIndex, char))

                // notify once because initial load is 3 chapters I don't care about 10 notifications when the user cant see it
                updateReadArea(seekToDesired = true)

                /*_loadingStatus.postValue(
                    Resource.Success(true)
                )*/
            }

            is Resource.Failure -> {
                _loadingStatus.postValue(
                    Resource.Failure(
                        loadedBook.cause,
                        loadedBook.errorString
                    )
                )
            }

            else -> throw NotImplementedError()
        }
    }

    fun init(book: AbstractBook, context: Context) {
        this.book = book
        _title.postValue(book.title())

        maybeStartAutoPregen(context)
        refreshTtsCacheScope() // scope the "generating" underline to this book/voice
        updateChapters()
        val imageLoader: ImageLoader = SingletonImageLoader.get(context)

        val coilStore = object : CoilStore {
            override fun load(drawable: AsyncDrawable): ImageRequest {
                val newUrl = drawable.destination.substringAfter("&url=")
                val url =
                    book.resolveUrl(
                        if (newUrl.length > 8) { // we assume that it is not a stub url by length > 8
                            URLDecoder.decode(newUrl)
                        } else {
                            drawable.destination
                        }
                    )

                return ImageRequest.Builder(context)
                    .data(url)
                    .build()
            }

            override fun cancel(disposable: Disposable) {
                disposable.dispose()
            }
        }

        markwon = Markwon.builder(context)
            .usePlugin(HtmlPlugin.create { plugin -> plugin.excludeDefaults(false) })
            .usePlugin(CoilImagesPlugin.create(context, coilStore, imageLoader))
            .usePlugin(object :
                AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.imageSizeResolver(object : ImageSizeResolver() {
                        override fun resolveImageSize(drawable: AsyncDrawable): Rect {
                            return drawable.result.bounds
                        }
                    })
                }
            })
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .build()
        //reducer = MarkwonReducer.directChildren()
    }

    // ========================================  TTS STUFF ========================================

    var ttsSession: TtsEngine? = null

    /** English-only on-device models mispronounce other languages; check the rendered-text language.
     *  Returns true when the model has no language restriction, the content language is unknown, or
     *  it matches the model. */
    private fun onDeviceLanguageOk(): Boolean {
        val modelLang = TtsModels.byId(ttsOnDeviceModel).lang
        if (modelLang.isBlank()) return true
        val contentLang = runCatching { mlSettings.to }.getOrNull()?.take(2) ?: return true
        return contentLang.equals(modelLang.take(2), ignoreCase = true)
    }

    private fun initTTSSession(context: Context) {
        runOnMainThread {
            val modelReady = ttsEngineType == TtsEngineType.ON_DEVICE &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    modelDownloads(context).isReady(ttsOnDeviceModel)
            val langOk = onDeviceLanguageOk()
            ttsSession = if (modelReady && langOk) {
                OnDeviceTtsEngine(context, ttsOnDeviceModel, ttsOnDeviceVoice, ttsLookahead, ttsGapMs, ::parseAction).also { engine ->
                    engine.cacheBookId = runCatching { TtsAudioCache.bookIdFor(book) }.getOrNull()
                    engine.updateEnhance(ttsEnhance)
                    engine.updateDenoise(ttsDenoise)
                    engine.updateVoiceStyle(com.lagradost.quicknovel.tts.AudioPostProcessor.VoiceStyle.fromPref(ttsVoiceStyle))
                    engine.onAudibleLine = { current, next ->
                        _ttsLine.postValue(current)
                        _ttsPending.postValue(false) // audio started -> stop the flicker
                        TTSNotifications.updateNowPlaying(current.speakOutMsg, next?.speakOutMsg, currentTTSStatus, context)
                    }
                    engine.onLineTarget = { line, pending ->
                        _ttsLine.postValue(line)      // jump the highlight to the skip target now
                        _ttsPending.postValue(pending) // flicker if its audio isn't ready yet
                    }
                }
            } else {
                if (ttsEngineType == TtsEngineType.ON_DEVICE) {
                    if (modelReady && !langOk) showToast(R.string.tts_language_mismatch)
                    else showToast(R.string.tts_model_not_downloaded)
                }
                TTSSession(context, ::parseAction)
            }
        }
    }

    // @Volatile: incremented on the main thread (skip taps), read/reset on the TTS coroutine —
    // without it rapid taps could be missed (only 1 of N skips registering).
    @Volatile
    private var pendingTTSSkip: Int = 0
    private var _currentTTSStatus: TTSHelper.TTSStatus = TTSHelper.TTSStatus.IsStopped
    var currentTTSStatus: TTSHelper.TTSStatus
        get() = _currentTTSStatus
        set(value) = synchronized(this@ReadActivityViewModel) {
            playDummySound()
            if (_currentTTSStatus == TTSHelper.TTSStatus.IsStopped && value == TTSHelper.TTSStatus.IsRunning) {
                // On-device selected but no voice downloaded: server audio (if any) can't play without
                // an engine either -> prompt the user to pick/download a voice instead of silently
                // falling back to system TTS.
                if (ttsEngineType == TtsEngineType.ON_DEVICE && !onDeviceModelReady()) {
                    _promptModelDownload.postValue(true)
                }
                startTTSWorker()
            }

            _ttsStatus.postValue(value)
            _currentTTSStatus = value
            // F1: local pause gate so a skip while paused updates the highlight but doesn't auto-play.
            (ttsSession as? OnDeviceTtsEngine)?.setPaused(value == TTSHelper.TTSStatus.IsPaused)
            // F3: background pre-gen yields the CPU/model to the live engine while actually playing.
            com.lagradost.quicknovel.tts.TtsPlaybackGate.setListening(
                value == TTSHelper.TTSStatus.IsRunning && ttsSession is OnDeviceTtsEngine
            )
        }

    fun stopTTS() {
        currentTTSStatus = TTSHelper.TTSStatus.IsStopped
    }

    fun setTTSLanguage(locale: Locale?) {
        // System-only concept; no-op on non-system engines.
        (ttsSession as? TTSSession)?.setLanguage(locale)
    }

    fun setTTSVoice(voice: Voice?) {
        (ttsSession as? TTSSession)?.setVoice(voice)
    }

    fun pauseTTS() {
        val ttsSession = ttsSession ?: return
        if (!ttsSession.ttsInitialized()) return
        if (currentTTSStatus == TTSHelper.TTSStatus.IsRunning) {
            currentTTSStatus = TTSHelper.TTSStatus.IsPaused
        }
    }

    fun startTTS() {
        currentTTSStatus = TTSHelper.TTSStatus.IsRunning
    }

    fun forwardsTTS() {
        val ttsSession = ttsSession ?: return
        if (!ttsSession.ttsInitialized()) return
        pendingTTSSkip += 1
    }

    fun backwardsTTS() {
        val ttsSession = ttsSession ?: return
        if (!ttsSession.ttsInitialized()) return
        pendingTTSSkip -= 1
    }

    fun playTTS() {
        currentTTSStatus = TTSHelper.TTSStatus.IsRunning
    }

    fun pausePlayTTS() {
        if (currentTTSStatus == TTSHelper.TTSStatus.IsRunning) {
            currentTTSStatus = TTSHelper.TTSStatus.IsPaused
        } else if (currentTTSStatus == TTSHelper.TTSStatus.IsPaused) {
            currentTTSStatus = TTSHelper.TTSStatus.IsRunning
        }
    }

    fun isTTSRunning(): Boolean {
        return currentTTSStatus == TTSHelper.TTSStatus.IsRunning
    }


    private val ttsThreadMutex = Mutex()

    fun startTTSWorker() = ioSafe {
        TTSNotificationService.start(this@ReadActivityViewModel, context ?: return@ioSafe)
    }

    suspend fun startTTSThread() = coroutineScope {
        val ttsSession = ttsSession ?: return@coroutineScope
        try {
            val ttsStartTime = System.currentTimeMillis()
            var ttsEndTime = ttsStartTime + ttsTimer
            val ttsHasTimer = ttsEndTime > ttsStartTime

            // A pending live voice-change resume wins over the scroll anchor (consumed once).
            val dIndex = ttsResumeAt?.also { ttsResumeAt = null } ?: desiredTTSIndex ?: desiredIndex ?: return@coroutineScope

            if (ttsThreadMutex.isLocked) return@coroutineScope
            ttsThreadMutex.withLock {
                ttsSession.register()
                ttsSession.setSpeed(ttsSpeed)
                ttsSession.setPitch(ttsPitch)
                // Bind the audio cache to this book now that it's guaranteed loaded (the engine may
                // have been built during reader init before `book` was set, leaving cacheBookId null).
                (ttsSession as? OnDeviceTtsEngine)?.cacheBookId =
                    runCatching { TtsAudioCache.bookIdFor(book) }.getOrNull()

                var ttsInnerIndex = 0 // this inner index is different from what is set
                var index = dIndex.index

                let {
                    val startChar = dIndex.char

                    val lines = chapterMutex.withLock {
                        chapterData[index].letInner {
                            it.ttsLines
                        }
                    } ?: run {
                        // in case of error just go to the next chapter
                        index++
                        return@let
                    }

                    val idx = lines.indexOfFirst { it.startChar >= startChar }
                    if (idx != -1) {
                        ttsInnerIndex = idx
                    } else {
                        // In case we are at the very last thing, then goto the next chapter
                        index++
                    }
                }

                loadIndividualChapter(index)
                while (isActive && currentTTSStatus != TTSHelper.TTSStatus.IsStopped) {
                    val lines =
                        when (val currentData = chapterMutex.withLock { chapterData[index] }) {
                            null -> {
                                showToast(R.string.got_null_data)
                                break
                            }

                            is Resource.Failure -> {
                                showToast(currentData.errorString)
                                break
                            }

                            is Resource.Loading -> {
                                if (currentTTSStatus == TTSHelper.TTSStatus.IsStopped) break
                                delay(100)
                                continue
                            }

                            is Resource.Success -> {
                                currentData.value.ttsLines
                            }
                        }

                    fun notify() {
                        TTSNotifications.notify(currentTTSStatus, context)
                    }
                    notify()

                    // this is because if you go back one line you will be on the previous chapter with
                    // a negative innerIndex, this makes the wrapping good
                    if (ttsInnerIndex < 0) {
                        ttsInnerIndex += lines.size
                    }

                    updateIndex(index)

                    //preload next chapter
                    viewModelScope.launch(Dispatchers.IO) {
                        val exists =
                            chapterMutex.withLock { chapterData[index + 1] is Resource.Success }
                        if (!exists)
                            loadIndividualChapter(index + 1)
                    }
                    // speak all lines
                    while (ttsInnerIndex < lines.size && ttsInnerIndex >= 0) {
                        ensureActive()

                        // auto stop
                        val currentTimeRemaining = ttsEndTime - System.currentTimeMillis()
                        if (ttsHasTimer) {
                            if (currentTimeRemaining < 0) {
                                currentTTSStatus = TTSHelper.TTSStatus.IsStopped
                            } else {
                                ttsTimeRemaining.postValue(currentTimeRemaining)
                            }
                        }

                        if (currentTTSStatus == TTSHelper.TTSStatus.IsStopped) break

                        val line = lines[ttsInnerIndex]
                        val nextLine = lines.getOrNull(ttsInnerIndex + 1)

                        // set keys
                        /*setKey(
                            EPUB_CURRENT_POSITION_SCROLL_CHAR,
                            book.title(),
                            line.startChar
                        )
                        setKey(EPUB_CURRENT_POSITION, book.title(), line.index)*/

                        // if we are outside the app, then we post new desired location
                        // as otherwise the scroll overrides it
                        // this is done to scroll to latest when we go back to the app
                        if (!isInApp) {
                            innerCharToIndex(index, line.startChar)?.let {
                                changeIndex(
                                    ScrollIndex(
                                        index,
                                        it,
                                        line.startChar
                                    ), alsoTitle = false
                                )
                            }
                        }


                        // On-device engines post the audible line themselves (highlight + notification,
                        // audio-synced). The system engine posts it here at enqueue time.
                        if (ttsSession.drivesOwnHighlight != true) {
                            _ttsLine.postValue(line)
                            TTSNotifications.updateNowPlaying(
                                line.speakOutMsg,
                                nextLine?.speakOutMsg,
                                currentTTSStatus,
                                context
                            )
                        }

                        // wait for next line
                        // Feed a look-ahead window so an on-device engine can pre-render ahead
                        // (Strategy B). The system engine only uses the first entry, unchanged.
                        val upcoming = run {
                            val end = minOf(lines.size, ttsInnerIndex + 1 + ttsLookahead)
                            if (ttsInnerIndex + 1 < end) lines.subList(ttsInnerIndex + 1, end).toList()
                            else emptyList()
                        }
                        val waitFor = ttsSession.speak(
                            line,
                            upcoming
                        ) {
                            currentTTSStatus != TTSHelper.TTSStatus.IsRunning || pendingTTSSkip != 0
                        }

                        if (!ttsSession.isValidTTS()) {
                            currentTTSStatus = TTSHelper.TTSStatus.IsStopped
                        }

                        ttsSession.waitForOr(waitFor, {
                            currentTTSStatus != TTSHelper.TTSStatus.IsRunning || pendingTTSSkip != 0
                        }) {
                            notify()
                        }

                        // wait for pause
                        var isPauseDuration = 0L
                        while (currentTTSStatus == TTSHelper.TTSStatus.IsPaused) {
                            isPauseDuration++
                            delay(100)
                        }

                        // do not count in tts sleep
                        ttsEndTime += 100L * isPauseDuration

                        // if we pause then we resume on the same line
                        if (isPauseDuration > 0) {
                            notify()
                            pendingTTSSkip = 0
                            continue
                        }

                        if (pendingTTSSkip != 0) {
                            ttsInnerIndex += pendingTTSSkip
                            pendingTTSSkip = 0
                        } else {
                            ttsInnerIndex += 1
                        }
                    }
                    if (currentTTSStatus == TTSHelper.TTSStatus.IsStopped) break

                    // this may case a bug where you cant seek back if the entire chapter is none
                    // but this is better than restarting the chapter
                    if (ttsInnerIndex > 0 || lines.isEmpty()) {
                        // goto next chapter and set inner to 0
                        index++
                        ttsInnerIndex = 0
                    } else if (index > 0) {
                        index--
                    } else {
                        ttsInnerIndex = 0
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {

        } catch (t: Throwable) {
            logError(t)
        } finally {
            currentTTSStatus = TTSHelper.TTSStatus.IsStopped
            TTSNotifications.notify(TTSHelper.TTSStatus.IsStopped, context)
            ttsSession.interruptTTS()
            ttsSession.unregister()
            _ttsLine.postValue(null)
            _ttsPending.postValue(false)
            ttsTimeRemaining.postValue(null)
        }
    }

    fun parseAction(input: TTSHelper.TTSActionType): Boolean {
        val ttsSession = ttsSession ?: return false

        // validate that the action makes sense
        if (
            (currentTTSStatus == TTSHelper.TTSStatus.IsPaused && input == TTSHelper.TTSActionType.Pause) ||
            (currentTTSStatus != TTSHelper.TTSStatus.IsPaused && input == TTSHelper.TTSActionType.Resume) ||
            (currentTTSStatus == TTSHelper.TTSStatus.IsStopped && input == TTSHelper.TTSActionType.Stop) ||
            (currentTTSStatus != TTSHelper.TTSStatus.IsRunning && input == TTSHelper.TTSActionType.Next)
        ) {
            return false
        }

        if (!ttsSession.ttsInitialized()) return false

        when (input) {
            TTSHelper.TTSActionType.Pause -> pauseTTS()
            TTSHelper.TTSActionType.Resume -> startTTS()
            TTSHelper.TTSActionType.Stop -> stopTTS()
            TTSHelper.TTSActionType.Next -> forwardsTTS()
        }

        return true
    }

    fun innerCharToIndex(index: Int, char: Int): Int? {
        // the lock is so short it does not matter I *hope*
        return runBlocking {
            chapterMutex.withLock { chapterData[index] }?.letInner { live ->
                // todo binary search, but strip all but TextSpan first
                live.spans.firstOrNull { it.start >= char }?.innerIndex
            }
        }
    }

    /** sets the metadata and global vars used as well as keys */
    private var lastChangeIndex: ScrollIndex? = null
    private var lastScrollMs: Long = 0
    private fun changeIndex(scrollIndex: ScrollIndex, alsoTitle: Boolean = true) {
        if (alsoTitle) {
            _chapterTile.postValue(chaptersTitlesInternal[scrollIndex.index])
        }

        desiredIndex = scrollIndex
        currentIndex = scrollIndex.index

        // the majority of the time is spent on setKey, and because this is called from onscroll
        // this fixes lag
        lastChangeIndex = scrollIndex
        if (System.currentTimeMillis() > lastScrollMs + 200L) {
            lastScrollMs = System.currentTimeMillis()
            setScrollKeys(scrollIndex)
        }
    }

    private fun setScrollKeys(scrollIndex: ScrollIndex) {
        setKey(
            EPUB_CURRENT_POSITION_READ_AT,
            "${book.title()}/${scrollIndex.index}",
            System.currentTimeMillis()
        )

        setKey(
            EPUB_CURRENT_POSITION_SCROLL_CHAR,
            book.title(),
            scrollIndex.char
        )
        setKey(EPUB_CURRENT_POSITION, book.title(), scrollIndex.index)
        context?.let {
            setKey(
                EPUB_CURRENT_POSITION_CHAPTER,
                book.title(),
                book.getChapterTitle(scrollIndex.index).asString(it)
            )
        }
    }

    fun scrollToDesired(scrollIndex: ScrollIndex) {
        changeIndex(scrollIndex)
        updateReadArea(seekToDesired = true)
    }

    fun seekToChapter(index: Int) = ioSafe {
        // sanity check
        if (index < 0 || index >= book.size()) return@ioSafe

        // we wont allow chapter switching and tts at the same time, stop it
        if (currentTTSStatus != TTSHelper.TTSStatus.IsStopped) {
            currentTTSStatus = TTSHelper.TTSStatus.IsStopped
        }

        // set loading
        _loadingStatus.postValue(Resource.Loading())

        // load the chapters
        updateIndexAsync(index, notify = false, postLoading = true)
        // set the keys
        setKey(EPUB_CURRENT_POSITION, book.title(), index)
        setKey(EPUB_CURRENT_POSITION_SCROLL_CHAR, book.title(), 0)

        // set the state
        desiredIndex = ScrollIndex(index, 0, 0)
        currentIndex = index
        desiredTTSIndex = ScrollIndex(index, 0, 0)

        // push the update
        updateReadArea(seekToDesired = true)
        // update the view
        _chapterTile.postValue(chaptersTitlesInternal[index])
        //_loadingStatus.postValue(Resource.Success(true))
    }

    /*private fun changeIndex(index: Int, updateArea: Boolean = true) {
        val realNewIndex = minOf(index, book.size() - 1)
        if (currentIndex == realNewIndex) return
        setKey(EPUB_CURRENT_POSITION, book.title(), realNewIndex)
        currentIndex = realNewIndex
        if (updateArea) updateReadArea()
        _chapterTile.postValue(chaptersTitlesInternal[realNewIndex])
    }*/



    // FUCK ANDROID WITH ALL MY HEART
    // SEE https://stackoverflow.com/questions/45960265/android-o-oreo-8-and-higher-media-buttons-issue WHY
    private fun playDummySound() {
        val act = activity ?: return
        val mMediaPlayer: MediaPlayer = MediaPlayer.create(act, R.raw.dummy_sound_500ms)
        mMediaPlayer.setOnCompletionListener { mMediaPlayer.release() }
        mMediaPlayer.start()
    }

    override fun onCleared() {
        println("onCleared===${System.currentTimeMillis()}")
        lastChangeIndex?.let { setScrollKeys(it) }
        com.lagradost.quicknovel.tts.TtsPrefetchManager.cancelAll()
        com.lagradost.quicknovel.tts.TtsPlaybackGate.setListening(false)
        _ttsCacheScope.postValue(null) // blank the "generating" underlines on close
        ttsSession?.release()
        ttsSession = null
        mlTranslator?.close()
        mlTranslator = null
        super.onCleared()
    }


    private var readerTypeInternal by PreferenceDelegate(
        EPUB_READER_TYPE,
        ReadingType.DEFAULT.prefValue,
        Int::class
    )

    var readerType
        get() = ReadingType.fromSpinner(readerTypeInternal)
        set(value) {
            readerTypeInternal = value.prefValue
            updateReadArea(seekToDesired = true)
        }


    var scrollWithVolume by PreferenceDelegate(EPUB_SCROLL_VOL, true, Boolean::class)
    var authorNotes by PreferenceDelegate(EPUB_AUTHOR_NOTES, true, Boolean::class)
    var ttsLock by PreferenceDelegate(EPUB_TTS_LOCK, true, Boolean::class)
    //var ttsOSSpeed by PreferenceDelegate(EPUB_TTS_OS_SPEED, true, Boolean::class)

    private var ttsSpeedKey by PreferenceDelegate(EPUB_TTS_SET_SPEED, 1.0f, Float::class)
    private var ttsPitchKey by PreferenceDelegate(EPUB_TTS_SET_PITCH, 1.0f, Float::class)

    var ttsSpeed: Float
        get() = ttsSpeedKey
        set(value) {
            ttsSession?.setSpeed(value)
            ttsSpeedKey = value
        }

    var ttsPitch: Float
        get() = ttsPitchKey
        set(value) {
            ttsSession?.setPitch(value)
            ttsPitchKey = value
        }

    // ---- On-device neural TTS (engine/model/voice/buffer) ----
    private var ttsEngineInternal by PreferenceDelegate(EPUB_TTS_ENGINE, TtsEngineType.SYSTEM.prefValue, Int::class)
    var ttsEngineType: TtsEngineType
        get() = TtsEngineType.fromSpinner(ttsEngineInternal)
        set(value) {
            ttsEngineInternal = value.prefValue
            recreateTtsEngine()
        }
    // Changing model/voice must rebuild the engine (it captures the model + speaker at construction).
    private var ttsOnDeviceModelKey by PreferenceDelegate(EPUB_TTS_OD_MODEL, "kitten", String::class)
    var ttsOnDeviceModel: String
        get() = ttsOnDeviceModelKey
        set(value) {
            if (value == ttsOnDeviceModelKey) return
            ttsOnDeviceModelKey = value
            if (ttsEngineType == TtsEngineType.ON_DEVICE) recreateTtsEngine()
        }
    private var ttsOnDeviceVoiceKey by PreferenceDelegate(EPUB_TTS_OD_VOICE, "", String::class)
    var ttsOnDeviceVoice: String
        get() = ttsOnDeviceVoiceKey
        set(value) {
            if (value == ttsOnDeviceVoiceKey) return
            ttsOnDeviceVoiceKey = value
            if (ttsEngineType == TtsEngineType.ON_DEVICE) recreateTtsEngine()
        }
    // Buffer depth applies live to the running engine (no rebuild).
    private var ttsLookaheadKey by PreferenceDelegate(EPUB_TTS_OD_BUFFER, 3, Int::class)
    var ttsLookahead: Int
        get() = ttsLookaheadKey
        set(value) {
            ttsLookaheadKey = value.coerceIn(1, 6)
            (ttsSession as? OnDeviceTtsEngine)?.updateLookahead(ttsLookaheadKey)
        }
    // Inter-sentence gap (ms) for the on-device engine, applied live.
    private var ttsGapKey by PreferenceDelegate(EPUB_TTS_OD_GAP, 150, Int::class)
    var ttsGapMs: Int
        get() = ttsGapKey
        set(value) {
            ttsGapKey = value.coerceIn(0, 2000)
            (ttsSession as? OnDeviceTtsEngine)?.updateGapMs(ttsGapKey)
        }
    // Audio clean-up (de-clip / de-ess / normalize) for the on-device engine, applied live.
    private var ttsEnhanceKey by PreferenceDelegate(EPUB_TTS_OD_ENHANCE, true, Boolean::class)
    var ttsEnhance: Boolean
        get() = ttsEnhanceKey
        set(value) {
            ttsEnhanceKey = value
            (ttsSession as? OnDeviceTtsEngine)?.updateEnhance(value)
        }
    // GTCRN neural denoiser (heavier, opt-in). Enabling downloads the ~7 MB model on first use.
    private var ttsDenoiseKey by PreferenceDelegate(EPUB_TTS_OD_DENOISE, false, Boolean::class)
    var ttsDenoise: Boolean
        get() = ttsDenoiseKey
        set(value) {
            ttsDenoiseKey = value
            (ttsSession as? OnDeviceTtsEngine)?.updateDenoise(value)
            if (value) ensureDenoiserDownloaded()
        }

    // Voice character: 0=Natural, 1=Warm, 2=Sultry (warmth EQ + pitch/tempo, layered live).
    private var ttsVoiceStyleKey by PreferenceDelegate(EPUB_TTS_OD_VOICE_STYLE, 0, Int::class)
    var ttsVoiceStyle: Int
        get() = ttsVoiceStyleKey
        set(value) {
            ttsVoiceStyleKey = value
            (ttsSession as? OnDeviceTtsEngine)?.updateVoiceStyle(
                com.lagradost.quicknovel.tts.AudioPostProcessor.VoiceStyle.fromPref(value)
            )
        }

    // Feature 3: auto-generate all downloaded chapters when the book opens. ttsAutogen = on-device;
    // ttsServerAutogen = offload that generation to the fix server's /tts (falls back to on-device).
    var ttsAutogen by PreferenceDelegate(EPUB_TTS_OD_AUTOGEN, false, Boolean::class)
    var ttsServerAutogen by PreferenceDelegate(EPUB_TTS_SERVER_AUTOGEN, false, Boolean::class)

    private fun maybeStartAutoPregen(context: Context) {
        // G2 diagnostics: every silent early-return logs its reason (the queue-verification test
        // reads these to explain "nothing happened").
        val tag = "RemoteTts"
        if (!::book.isInitialized) { android.util.Log.i(tag, "autopregen skipped: no book yet"); return }
        if (!ttsAutogen && !ttsServerAutogen) { android.util.Log.i(tag, "autopregen skipped: both autogen prefs off"); return }
        if (ttsEngineType != TtsEngineType.ON_DEVICE) { android.util.Log.i(tag, "autopregen skipped: engine != ON_DEVICE"); return }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
        val ctx = context.applicationContext
        ioSafe {
            // The on-device model must be downloaded to PLAY the cached WAVs (even if the server made them).
            val def = com.lagradost.quicknovel.tts.TtsModels.byId(ttsOnDeviceModel)
            if (!com.lagradost.quicknovel.tts.TtsModels.isReady(ctx, def)) {
                android.util.Log.i(tag, "autopregen skipped: model ${def.id} not downloaded"); return@ioSafe
            }
            if (!onDeviceLanguageOk()) { android.util.Log.i(tag, "autopregen skipped: language mismatch"); return@ioSafe }
            // EPUB imports have no downloaded per-chapter files -> nothing to generate.
            val meta = (book as? QuickBook)?.data?.meta ?: run {
                android.util.Log.i(tag, "autopregen skipped: not a QuickBook (EPUB import)"); return@ioSafe
            }
            val author = meta.author ?: ""
            val total = BookDownloader2Helper.downloadInfo(ctx, author, meta.name, meta.apiName)?.total?.toInt()
                ?: run {
                    android.util.Log.i(
                        tag,
                        "autopregen skipped: no downloadInfo (no chapters downloaded) " +
                                "api='${meta.apiName}' author='$author' name='${meta.name}'"
                    ); return@ioSafe
                }
            if (total <= 0) { android.util.Log.i(tag, "autopregen skipped: 0 downloaded chapters"); return@ioSafe }
            val sid = com.lagradost.quicknovel.tts.TtsModels.parseVoice(ttsOnDeviceVoice)?.second ?: 0
            // onBookReady offloads to the server when a URL is set + reachable, else on-device.
            com.lagradost.quicknovel.tts.RemoteTtsManager.onBookReady(
                ctx,
                com.lagradost.quicknovel.tts.RemoteTtsManager.RemoteTtsRequest(
                    bookId = BookDownloader2Helper.generateId(meta.apiName, author, meta.name),
                    apiName = meta.apiName, author = author, name = meta.name,
                    posterUrl = (book as? QuickBook)?.data?.poster,
                    modelId = def.id, sid = sid, sampleRate = def.sampleRate,
                    rangeStart = 0, rangeEnd = total - 1,
                    serverUrl = if (ttsServerAutogen) llmServerUrl else "",
                ),
            )
        }
    }

    /** Per-chapter TTS cache state for the current book+voice: "✓ done · ◐ partial · · none" rows
     *  (same order as the chapter list, so a tap can jump). Runs off-main; result posted to main. */
    fun cachedChapterOverview(onResult: (List<String>) -> Unit) {
        val ctx = context ?: return
        ioSafe {
            if (!::book.isInitialized) return@ioSafe
            val def = com.lagradost.quicknovel.tts.TtsModels.byId(ttsOnDeviceModel)
            val sid = com.lagradost.quicknovel.tts.TtsModels.parseVoice(ttsOnDeviceVoice)?.second ?: 0
            val bookId = runCatching { TtsAudioCache.bookIdFor(book) }.getOrNull() ?: return@ioSafe
            val items = (0 until book.size()).map { i ->
                val done = TtsAudioCache.isChapterDone(ctx, bookId, def.id, sid, i)
                val partial = !done && TtsAudioCache.chapterDir(ctx, bookId, def.id, sid, i)
                    .listFiles()?.any { f -> f.name.endsWith(".wav") } == true
                val mark = if (done) "✓" else if (partial) "◐" else "·"
                "$mark  ${book.getChapterTitle(i).asString(ctx)}"
            }
            runOnMainThread { onResult(items) }
        }
    }

    // Feature 2: prefetch-on-open (app setting "tts_prefetch_on_open").
    private val prefetchOnOpen: Boolean
        get() = context?.let {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(it)
                .getBoolean(EPUB_TTS_PREFETCH, false)
        } ?: false

    private fun ttsLinesFor(index: Int): List<TTSHelper.TTSLine>? =
        (chapterData[index] as? Resource.Success)?.value?.ttsLines

    private fun maybePrefetchOnOpen(index: Int) {
        if (!prefetchOnOpen || ttsEngineType != TtsEngineType.ON_DEVICE || !::book.isInitialized) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
        val ctx = context ?: return
        val def = com.lagradost.quicknovel.tts.TtsModels.byId(ttsOnDeviceModel)
        if (!com.lagradost.quicknovel.tts.TtsModels.isReady(ctx, def) || !onDeviceLanguageOk()) return
        val bookId = runCatching { com.lagradost.quicknovel.tts.TtsAudioCache.bookIdFor(book) }.getOrNull() ?: return
        val sid = com.lagradost.quicknovel.tts.TtsModels.parseVoice(ttsOnDeviceVoice)?.second ?: 0
        ioSafe {
            loadIndividualChapter(index)
            loadIndividualChapter(index + 1)
            val batches = chapterMutex.withLock {
                buildList {
                    // Skip the current chapter while actively listening — the live look-ahead covers it.
                    if (currentTTSStatus != TTSHelper.TTSStatus.IsRunning)
                        ttsLinesFor(index)?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    ttsLinesFor(index + 1)?.takeIf { it.isNotEmpty() }?.let { add(it) } // next chapter
                }
            }
            if (batches.isNotEmpty())
                com.lagradost.quicknovel.tts.TtsPrefetchManager.prefetch(ctx, bookId, def, sid, batches)
        }
    }

    private val _denoiserDownloading = MutableLiveData(false)
    val denoiserDownloading: LiveData<Boolean> = _denoiserDownloading
    private fun ensureDenoiserDownloaded() {
        val ctx = context ?: return
        if (com.lagradost.quicknovel.tts.TtsDenoiser.isReady(ctx)) return
        ioSafe {
            _denoiserDownloading.postValue(true)
            runCatching { com.lagradost.quicknovel.tts.TtsDenoiser.downloadModel(ctx.applicationContext) }
            _denoiserDownloading.postValue(false)
        }
    }

    // ---- On-device LLM prose fixer ----
    private var llmModelKey by PreferenceDelegate(LLM_FIX_MODEL, "qwen2.5-1.5b", String::class)
    var llmModel: String
        get() = llmModelKey
        set(value) { llmModelKey = value }
    var llmSystemPrompt by PreferenceDelegate(LLM_FIX_SYSTEM_PROMPT, "", String::class)
    var llmPromptVersion by PreferenceDelegate(LLM_FIX_PROMPT_VERSION, 1, Int::class)
    // Default 0: previous-chapter context bloats the prompt and slows generation; the server also
    // gates it off by default. Raise the slider to re-enable for stronger pronoun consistency.
    var llmPrevChapters by PreferenceDelegate(LLM_FIX_PREV_CHAPTERS, 0, Int::class)
    // Optional GPU fix server: when the URL is set, rewriting offloads to it (seconds vs minutes).
    var llmServerUrl by PreferenceDelegate(LLM_FIX_SERVER_URL, "", String::class)
    var llmServerModel by PreferenceDelegate(LLM_FIX_SERVER_MODEL, "", String::class)

    private var llmShowFixedKey by PreferenceDelegate(LLM_FIX_SHOW_FIXED, false, Boolean::class)
    private var llmScriptModeKey by PreferenceDelegate(LLM_FIX_SCRIPT_MODE, -1, Int::class)

    /** Which script the GENERATE buttons produce (session-scoped; display mode is llmScriptMode). */
    var llmGenerateScript: com.lagradost.quicknovel.llm.ScriptType =
        com.lagradost.quicknovel.llm.ScriptType.GRAMMAR

    /** Reader script mode: 0=original, 1=grammar-fixed, 2=performance script. Seeded once from the
     *  legacy show-fixed boolean. Switching reloads the chapters (and what TTS speaks). */
    var llmScriptMode: Int
        get() = llmScriptModeKey.takeIf { it >= 0 } ?: (if (llmShowFixedKey) 1 else 0)
        set(value) {
            if (value == llmScriptMode) return
            llmScriptModeKey = value.coerceIn(0, 2)
            refreshChapters()
        }

    /** LEGACY compat for existing UI: "show fixed" == any generated script selected. */
    var llmShowFixed: Boolean
        get() = llmScriptMode != 0
        set(value) {
            llmScriptMode = if (value) 1 else 0
        }

    /** Kick off a BACKGROUND (WorkManager) model download so it survives the reader being closed. */
    fun downloadLlmModel(context: Context, id: String) {
        com.lagradost.quicknovel.llm.LlmModelDownloadManager.startBackgroundDownload(context, id)
    }

    private fun llmBookId(): String? = runCatching { TtsAudioCache.bookIdFor(book) }.getOrNull()

    /** Whether a fixed version of [index] exists for the current model+prompt. */
    fun hasFixedChapter(context: Context, index: Int): Boolean {
        val id = llmBookId() ?: return false
        return com.lagradost.quicknovel.llm.FixedTextCache.isFixed(context, id, llmModel, llmPromptVersion, index)
    }

    /** Substitute the selected generated script (grammar or performance) for the raw chapter body. */
    private fun maybeFixedText(context: Context?, index: Int, raw: String): String {
        val script = com.lagradost.quicknovel.llm.ScriptType.fromReaderMode(llmScriptMode) ?: return raw
        if (context == null) return raw
        val id = llmBookId() ?: return raw
        val fixed = com.lagradost.quicknovel.llm.FixedTextCache
            .load(context, id, llmModel, llmPromptVersion, index, script) ?: return raw
        // The fixer emits plain text with blank-line paragraphs; wrap them as <p> so the reader's HTML
        // pipeline (preParseHtml -> markwon) keeps paragraph breaks like the original chapter.
        return fixed.split(Regex("\n{2,}")).filter { it.isNotBlank() }
            .joinToString("\n") { "<p>" + it.trim().replace("\n", " ") + "</p>" }
    }

    /** The chapter as displayed plain text (paragraphs joined) — the base for selection splices. */
    private fun chapterPlainText(index: Int): String? =
        chapterIdxToSpanDisplay(index).filterIsInstance<TextSpan>()
            .joinToString("\n\n") { it.text.toString() }.takeIf { it.isNotBlank() }

    /** Expand a selection to sentence bounds; a selection covering most of the paragraph = whole paragraph. */
    private fun snapToSentence(p: String, selStart: Int, selEnd: Int): String {
        val s = selStart.coerceIn(0, p.length)
        val e = selEnd.coerceIn(s, p.length)
        if (e - s >= p.length * 3 / 4) return p
        val start = p.lastIndexOfAny(charArrayOf('.', '!', '?'), (s - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        val end = p.indexOfAny(charArrayOf('.', '!', '?'), e)
            .let { if (it < 0) p.length else it + 1 }
        return p.substring(start, end).trim().ifBlank { p }
    }

    /** One-shot fix of a SELECTION (sentence or paragraph) via the server snippet API; the result is
     *  spliced into the chapter's fixed text in place, and the reader switches to show it. */
    fun fixSelection(chapterIndex: Int, paragraph: String, selStart: Int, selEnd: Int,
                     script: com.lagradost.quicknovel.llm.ScriptType) {
        val ctx = context ?: return
        if (llmServerUrl.isBlank()) {
            showToast(R.string.llm_server_enter_url); return
        }
        ioSafe {
            val target = snapToSentence(paragraph, selStart, selEnd)
            showToast(R.string.sent_fix_to_server)
            val fixed = com.lagradost.quicknovel.llm.RemoteFixClient.fixSnippet(
                llmServerUrl, target, llmServerModel, "", "", script.apiValue,
            )?.trim()?.takeIf { it.isNotBlank() } ?: run {
                showToast(R.string.llm_test_no_response); return@ioSafe
            }
            val base = chapterPlainText(chapterIndex) ?: return@ioSafe
            if (!base.contains(target)) {
                showToast(R.string.llm_test_no_response); return@ioSafe
            }
            val id = llmBookId() ?: return@ioSafe
            // Selection fixes accumulate in the GRAMMAR display slot regardless of script — the
            // performance ScriptDoc (spans) only comes from whole-chapter jobs.
            com.lagradost.quicknovel.llm.FixedTextCache.save(
                ctx, id, llmModel, llmPromptVersion, chapterIndex,
                base.replaceFirst(target, fixed), com.lagradost.quicknovel.llm.ScriptType.GRAMMAR,
            )
            runOnMainThread { llmScriptMode = 1 } // show the spliced fix (setter reloads)
            if (llmScriptMode == 1) refreshChapters() // already in grammar mode -> force reload
        }
    }

    /** One hit of the reader's character/world lookup: a card + tappable occurrences. */
    data class CharSearchHit(
        val title: String,
        val card: String,
        val rows: List<Pair<Int, String>>, // chapterIndex -> display line (tap = jump)
    )

    /** Reader character/world search against the server map, spoiler-gated to the current chapter. */
    fun searchCharacterMap(query: String, onResult: (List<CharSearchHit>) -> Unit) {
        val url = llmServerUrl
        if (url.isBlank()) { showToast(R.string.llm_server_enter_url); return }
        val bookId = llmBookId() ?: return
        val gate = currentIndex.takeIf { it != Int.MIN_VALUE }
        ioSafe {
            val r = com.lagradost.quicknovel.llm.CharMapClient.search(url, bookId, query, gate)
            if (r == null) {
                showToast(R.string.character_map_missing)
                runOnMainThread { onResult(emptyList()) }
                return@ioSafe
            }
            val hits = ArrayList<CharSearchHit>()
            r.get("characters")?.forEach { c ->
                val name = c.get("name")?.asText() ?: return@forEach
                val traits = (c.get("personality")?.map { it.asText() } ?: emptyList()).take(4)
                val visual = c.get("visual")?.fields()?.asSequence()
                    ?.mapNotNull { (k, v) -> v.asText().takeIf { it.isNotBlank() }?.let { "$k: $it" } }
                    ?.toList() ?: emptyList()
                val card = buildString {
                    append(c.get("gender")?.asText() ?: "?").append(" · ")
                    append(c.get("role")?.asText() ?: "?")
                    c.get("aliases")?.takeIf { it.size() > 0 }
                        ?.let { al -> append("\naka: ").append(al.joinToString(", ") { a -> a.asText() }) }
                    if (traits.isNotEmpty()) append("\n").append(traits.joinToString(", "))
                    if (visual.isNotEmpty()) append("\n").append(visual.joinToString("; "))
                    (c.get("casting")?.get("voice_name")?.asText())
                        ?.let { v -> append("\nvoice: ").append(v) }
                }
                val rows = ArrayList<Pair<Int, String>>()
                c.get("interactions")?.forEach { inter ->
                    val ch = inter.get("chapter")?.asInt() ?: return@forEach
                    rows.add(
                        ch to "Ch ${ch + 1}:  ${inter.get("a")?.asText()} ↔ ${inter.get("b")?.asText()} — ${inter.get("summary")?.asText()}"
                    )
                }
                val interactionChapters = rows.map { it.first }.toSet()
                c.get("occurrences")?.forEach { o ->
                    val ch = o.asInt()
                    if (ch !in interactionChapters) rows.add(ch to "Ch ${ch + 1}:  appears")
                }
                rows.sortBy { it.first }
                hits.add(CharSearchHit(name, card, rows))
            }
            r.get("locations")?.forEach { lo ->
                val name = lo.get("name")?.asText() ?: return@forEach
                val first = lo.get("first_chapter")?.asInt() ?: 0
                hits.add(
                    CharSearchHit(
                        "📍 $name", lo.get("description")?.asText() ?: "",
                        listOf(first to "Ch ${first + 1}:  first mentioned"),
                    )
                )
            }
            runOnMainThread { onResult(hits) }
        }
    }

    private fun llmSupertonic(): Boolean =
        ttsEngineType == TtsEngineType.ON_DEVICE && ttsOnDeviceModel == "supertonic"

    private suspend fun buildPreviousContext(index: Int): String {
        val n = llmPrevChapters.coerceIn(0, 3)
        if (n <= 0) return ""
        val sb = StringBuilder()
        for (i in maxOf(0, index - n) until index) {
            val r = safeApiCall { book.getChapterData(i, false) }
            val t = (r as? Resource.Success)?.value?.let { preParseHtml(it, authorNotes) } ?: continue
            sb.append(t.takeLast(1500)).append("\n\n")
        }
        return sb.toString().takeLast(3000)
    }

    /**
     * Fix the currently-shown chapter on the spot: read it, gather previous-chapter context, run the
     * LLM, cache the result, then flip to "show fixed" and reload. [onState] reports coarse progress;
     * [onDone] fires with success. Heavy (minutes on mid-range) — runs entirely off the main thread.
     */
    fun fixCurrentChapter(
        context: Context,
        onState: (String) -> Unit,
        onStream: (info: String, text: String) -> Unit,
        onDone: (Boolean) -> Unit,
    ) = ioSafe {
        val tag = "LlmFixFlow"
        val index = currentIndex
        android.util.Log.i(tag, "fix start: index=$index model=$llmModel")
        if (index == Int.MIN_VALUE) { android.util.Log.e(tag, "currentIndex not set"); return@ioSafe onDone(false) }
        val bookId = llmBookId()
        if (bookId == null) { android.util.Log.e(tag, "no bookId"); return@ioSafe onDone(false) }
        if (llmServerUrl.isBlank() &&
            !com.lagradost.quicknovel.llm.LlmModels.isReady(context, com.lagradost.quicknovel.llm.LlmModels.byId(llmModel))
        ) {
            android.util.Log.e(tag, "model not ready: $llmModel"); onState("Model not downloaded"); return@ioSafe onDone(false)
        }
        onState(context.getString(R.string.llm_fixing_loading))
        val raw = safeApiCall { book.getChapterData(index, false) }
        val rawText = (raw as? Resource.Success)?.value?.let { preParseHtml(it, authorNotes) }
        if (rawText.isNullOrBlank()) {
            android.util.Log.e(tag, "rawText blank (raw=${raw.javaClass.simpleName}) for index=$index")
            return@ioSafe onDone(false)
        }
        android.util.Log.i(tag, "rawText len=${rawText.length}; loading engine + generating…")
        val prev = buildPreviousContext(index)
        val cfg0 = com.lagradost.quicknovel.llm.ChapterFixer.FixConfig(
            llmModel, llmPromptVersion, llmSystemPrompt, llmSupertonic(), llmServerUrl, llmServerModel,
        )
        // Decide server vs on-device UP FRONT and show it, so a fallback is never silent.
        val cfg = if (cfg0.serverUrl.isNotBlank()) {
            if (com.lagradost.quicknovel.llm.RemoteFixClient.reachable(cfg0.serverUrl)) {
                android.util.Log.i(tag, "server ${cfg0.serverUrl} reachable -> rewriting on server")
                onState(context.getString(R.string.llm_fixing_server)); cfg0
            } else {
                android.util.Log.w(tag, "server ${cfg0.serverUrl} UNREACHABLE -> on-device fallback")
                onState(context.getString(R.string.llm_fixing_server_unreachable)); cfg0.copy(serverUrl = "")
            }
        } else {
            onState(context.getString(R.string.llm_fixing_running, com.lagradost.quicknovel.llm.LlmModels.byId(llmModel).displayName))
            cfg0
        }
        // The on-the-spot button always regenerates: drop any stale cached fix so improvements apply.
        com.lagradost.quicknovel.llm.FixedTextCache.deleteChapter(context, bookId, llmModel, llmPromptVersion, index)
        val streamed = StringBuilder()
        val fixed = com.lagradost.quicknovel.llm.ChapterFixer.fixChapter(
            context, bookId, index, rawText, prev, cfg,
            onProgress = { chunkIdx, chunkCount, token ->
                streamed.append(token)
                onStream("Rewriting  ·  chunk ${chunkIdx + 1} / $chunkCount", streamed.toString())
            },
            onStatus = { s -> onState(s) },
        )
        android.util.Log.i(tag, "fix result: ${if (fixed != null) "OK len=${fixed.length}" else "NULL (engine load or blank generation)"}")
        if (fixed != null) {
            llmShowFixedKey = true
            refreshChapters()
            onDone(true)
        } else onDone(false)
    }

    private var _modelDownloads: ModelDownloadManager? = null
    fun modelDownloads(context: Context): ModelDownloadManager =
        _modelDownloads ?: ModelDownloadManager(context).also { _modelDownloads = it }

    /** Start (or no-op if already downloaded) an on-device model download; observe [ModelDownloadManager.states]. */
    fun downloadModel(context: Context, id: String) {
        val mgr = modelDownloads(context)
        viewModelScope.launch { mgr.download(id) }
    }

    /** Rebuild the active engine after an engine/model/voice change. In P1 this always yields TTSSession
     * (on-device playback is wired in P2); the pref is stored so the selection persists. */
    private fun recreateTtsEngine() {
        val ctx = context ?: return
        // Voice/model changed: abandon any prefetch keyed to the old voice + release the gate.
        com.lagradost.quicknovel.tts.TtsPrefetchManager.cancelAll()
        com.lagradost.quicknovel.tts.TtsPlaybackGate.setListening(false)
        refreshTtsCacheScope() // re-scope the "generating" underline to the new voice/model (or null)
        // Switching model/voice re-triggers autogen for the NEW voice (its own cache key -> a new
        // server/local job). The previous voice's job and cached audio are left untouched — switching
        // back is instant, and any in-flight old-voice job can be stopped from the dashboard.
        maybeStartAutoPregen(ctx)
        val wasRunning = isTTSRunning()
        // Capture the currently-spoken line BEFORE stopTTS (whose finally posts _ttsLine=null) so the
        // driver resumes at the SAME sentence in the new voice — auditioning voices on the fly.
        val resumeLine = if (wasRunning) _ttsLine.value else null
        stopTTS()
        ttsSession?.release()
        initTTSSession(ctx)
        if (resumeLine != null) {
            ttsResumeAt = ScrollIndex(
                resumeLine.index,
                innerCharToIndex(resumeLine.index, resumeLine.startChar) ?: 0,
                resumeLine.startChar,
            )
            _ttsLine.postValue(resumeLine) // hold the highlight through the rebuild
            _ttsPending.postValue(true)    // brief flicker while the new voice re-synthesizes
        }
        if (wasRunning) startTTS()
    }


    val textFontLive: MutableLiveData<String> = MutableLiveData(null)
    var textFont by PreferenceDelegateLiveView(EPUB_FONT, "", String::class, textFontLive)
    val textSizeLive: MutableLiveData<Int> = MutableLiveData(null)
    var textSize by PreferenceDelegateLiveView(
        EPUB_TEXT_SIZE,
        DEF_FONT_SIZE,
        Int::class,
        textSizeLive
    )

    val bionicReadingLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var bionicReading by PreferenceDelegateLiveView(
        EPUB_TEXT_BIONIC,
        false,
        Boolean::class,
        bionicReadingLive
    )

    val isTextSelectableLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var isTextSelectable by PreferenceDelegateLiveView(
        EPUB_TEXT_SELECTABLE,
        false,
        Boolean::class,
        isTextSelectableLive
    )

    val orientationLive: MutableLiveData<Int> = MutableLiveData(null)
    var orientation by PreferenceDelegateLiveView(
        EPUB_LOCK_ROTATION,
        OrientationType.DEFAULT.prefValue,
        Int::class, orientationLive
    )

    val textColorLive: MutableLiveData<Int> = MutableLiveData(null)
    var textColor by PreferenceDelegateLiveView(
        EPUB_TEXT_COLOR, "#cccccc".toColorInt(), Int::class, textColorLive
    )

    val textVerticalPaddingLive: MutableLiveData<Float> = MutableLiveData(null)
    var textVerticalPadding by PreferenceDelegateLiveView(
        EPUB_TEXT_VERTICAL_PADDING, 7.5f, Float::class, textVerticalPaddingLive
    )

    val backgroundColorLive: MutableLiveData<Int> = MutableLiveData(null)
    var backgroundColor by PreferenceDelegateLiveView(
        EPUB_BG_COLOR, "#292832".toColorInt(), Int::class, backgroundColorLive
    )

    val showBatteryLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var showBattery by PreferenceDelegateLiveView(
        EPUB_HAS_BATTERY, true, Boolean::class, showBatteryLive
    )

    val showTimeLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var showTime by PreferenceDelegateLiveView(
        EPUB_HAS_TIME, true, Boolean::class, showTimeLive
    )

    val paddingHorizontalLive: MutableLiveData<Int> = MutableLiveData(null)
    var paddingHorizontal by PreferenceDelegateLiveView(
        EPUB_TEXT_PADDING, DEF_HORIZONTAL_PAD, Int::class, paddingHorizontalLive
    )

    val paddingVerticalLive: MutableLiveData<Int> = MutableLiveData(null)
    var paddingVertical by PreferenceDelegateLiveView(
        EPUB_TEXT_PADDING_TOP, DEF_VERTICAL_PAD, Int::class, paddingVerticalLive
    )

    //val time12HLive: MutableLiveData<Boolean> = MutableLiveData(null)
    //var time12H by PreferenceDelegateLiveView(
    //    EPUB_TWELVE_HOUR_TIME, false, Boolean::class, time12HLive
    //)

    val screenAwakeLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var screenAwake by PreferenceDelegateLiveView(
        EPUB_KEEP_SCREEN_ACTIVE, true, Boolean::class, screenAwakeLive
    )

    // in milliseconds
    val ttsTimerLive: MutableLiveData<Long> = MutableLiveData(null)
    var ttsTimer by PreferenceDelegateLiveView(
        EPUB_SLEEP_TIMER, 0, Long::class, ttsTimerLive
    )

    val ttsTimeRemaining: MutableLiveData<Long?> = MutableLiveData(null)

    val mlFromLanguageLive: MutableLiveData<String> = MutableLiveData(null)
    var mlFromLanguage by PreferenceDelegateLiveView(
        EPUB_ML_FROM_LANGUAGE,
        TranslateLanguage.ENGLISH,
        String::class,
        mlFromLanguageLive
    )

    val mlToLanguageLive: MutableLiveData<String> = MutableLiveData(null)
    var mlToLanguage by PreferenceDelegateLiveView(
        EPUB_ML_TO_LANGUAGE,
        TranslateLanguage.ENGLISH,
        String::class,
        mlToLanguageLive
    )

    val mlUseOnlineTransaltionLive: MutableLiveData<Boolean> = MutableLiveData(false)
    var mlUseOnlineTransaltion by PreferenceDelegateLiveView(
        EPUB_ML_USEONLINETRANSLATION,
        false,
        Boolean::class,
        mlUseOnlineTransaltionLive
    )

    /*
   // Moved up to ensure correct initialization order. Having it lower caused a race condition  // where the default 'false' value was loaded before the actual saved preference.
    var mlSettings
        get() = getKey<MLSettings>(EPUB_CURRENT_ML, book.title()) ?: MLSettings("en", "en", false)
        set(value) = setKey(EPUB_CURRENT_ML, book.title(), value)

        */

    data class MLSettings(
        @JsonProperty("from")
        val from: String,
        @JsonProperty("to")
        val to: String,
        @JsonProperty("useOnlineTranslation")
        val useOnlineTranslation: Boolean = false
    ) {
        companion object {
            const val AUTO_LANG = "auto"
            val map = mapOf(
                "af" to "Afrikaans",
                "ar" to "Arabic",
                "be" to "Belarusian",
                "bg" to "Bulgarian",
                "bn" to "Bengali",
                "ca" to "Catalan",
                "cs" to "Czech",
                "cy" to "Welsh",
                "da" to "Danish",
                "de" to "German",
                "el" to "Greek",
                "en" to "English",
                "eo" to "Esperanto",
                "es" to "Spanish",
                "et" to "Estonian",
                "fa" to "Persian",
                "fi" to "Finnish",
                "fr" to "French",
                "ga" to "Irish",
                "gl" to "Galician",
                "gu" to "Gujarati",
                "he" to "Hebrew",
                "hi" to "Hindi",
                "hr" to "Croatian",
                "ht" to "Haitian",
                "hu" to "Hungarian",
                "id" to "Indonesian",
                "is" to "Icelandic",
                "it" to "Italian",
                "ja" to "Japanese",
                "ka" to "Georgian",
                "kn" to "Kannada",
                "ko" to "Korean",
                "lt" to "Lithuanian",
                "lv" to "Latvian",
                "mk" to "Macedonian",
                "mr" to "Marathi",
                "ms" to "Malay",
                "mt" to "Maltese",
                "nl" to "Dutch",
                "no" to "Norwegian",
                "pl" to "Polish",
                "pt" to "Portuguese",
                "ro" to "Romanian",
                "ru" to "Russian",
                "sk" to "Slovak",
                "sl" to "Slovenian",
                "sq" to "Albanian",
                "sv" to "Swedish",
                "sw" to "Swahili",
                "ta" to "Tamil",
                "te" to "Telugu",
                "th" to "Thai",
                "tl" to "Tagalog",
                "tr" to "Turkish",
                "uk" to "Ukrainian",
                "ur" to "Urdu",
                "vi" to "Vietnamese",
                "zh" to "Chinese",
            )
            val mapOnline = mapOf(AUTO_LANG to "Auto") + map
            val mapList = map.toList()
            val mapOnlineList = mapOnline.toList()
            fun fromShortToDisplay(from: String): String {
                return mapOnline[from] ?: "Unknown"
            }
        }

        val fromDisplay get() = fromShortToDisplay(from)
        val toDisplay get() = fromShortToDisplay(to)

        fun isInvalid(): Boolean = !isValid()

        fun isValid(): Boolean {
            if (from.isBlank() || to.isBlank()) {
                // nonsense
                return false
            }

            val all = TranslateLanguage.getAllLanguages()

            //If the user wants to translate to a language that doesn't exist,
            //or wants to auto-detect their own language, do not allow it.
            if (!all.contains(to)) {
                // no translation
                return false
            }

            // no support for auto yet (for offlineTranslations), see https://developers.google.com/ml-kit/language/identification/android
            //If the source language does not exist
            //and the user did not select auto-detect language, do not allow it.
            if (!all.contains(from) && !(useOnlineTranslation && from == AUTO_LANG)) {
                return false
            }

            if (from == to) {
                // identity function
                return false
            }

            return true
        }
    }
}
