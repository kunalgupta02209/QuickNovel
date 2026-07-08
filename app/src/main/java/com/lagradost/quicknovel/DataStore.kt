package com.lagradost.quicknovel

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.quicknovel.mvvm.logError
import androidx.core.content.edit

const val PREFERENCES_NAME: String = "rebuild_preference"
const val DOWNLOAD_FOLDER: String = "downloads_data"
const val DOWNLOAD_SIZE: String = "downloads_size"
const val DOWNLOAD_TOTAL: String = "downloads_total"
const val DOWNLOAD_OFFSET: String = "downloads_offset"
const val DOWNLOAD_EPUB_SIZE: String = "downloads_epub_size"
const val DOWNLOAD_EPUB_LAST_ACCESS: String = "downloads_epub_last_access"
const val DOWNLOAD_SORTING_METHOD: String = "download_sorting"
const val DOWNLOAD_NORMAL_SORTING_METHOD: String = "download_normal_sorting"
const val DOWNLOAD_SETTINGS: String = "download_settings"
const val EPUB_LOCK_ROTATION: String = "reader_epub_rotation"
const val EPUB_TEXT_SIZE: String = "reader_epub_text_size"
const val EPUB_TEXT_BIONIC: String = "reader_epub_bionic_reading"
const val EPUB_TEXT_SELECTABLE: String = "reader_epub_text_selectable"
const val EPUB_SCROLL_VOL: String = "reader_epub_scroll_volume"
const val EPUB_AUTHOR_NOTES: String = "reader_epub_author_notes"
const val EPUB_TTS_LOCK: String = "reader_epub_scroll_lock"
const val EPUB_TTS_SET_SPEED: String = "reader_epub_tts_speed"
const val RESULT_CHAPTER_SORT: String = "result_chapter_sort"
const val RESULT_CHAPTER_FILTER_DOWNLOADED: String = "result_chapter_filter_download"
const val RESULT_CHAPTER_FILTER_BOOKMARKED: String = "result_chapter_filter_bookmarked"
const val RESULT_CHAPTER_FILTER_READ: String = "result_chapter_filter_read"
const val RESULT_CHAPTER_FILTER_UNREAD: String = "result_chapter_filter_unread"
const val EPUB_TTS_SET_PITCH: String = "reader_epub_tts_pitch"

// On-device neural TTS engine (sherpa-onnx). Kept separate from EPUB_VOICE/EPUB_LANG (different id scheme).
const val EPUB_TTS_ENGINE: String = "reader_epub_tts_engine"       // Int: 0=System, 1=OnDevice
const val EPUB_TTS_OD_MODEL: String = "reader_epub_tts_od_model"   // String: model id (see TtsModels)
const val EPUB_TTS_OD_VOICE: String = "reader_epub_tts_od_voice"   // String: "<modelId>:<sid>"
const val EPUB_TTS_OD_BUFFER: String = "reader_epub_tts_od_buffer" // Int: sentences to buffer ahead (1..6)
const val EPUB_TTS_OD_GAP: String = "reader_epub_tts_od_gap"       // Int: inter-sentence gap in ms (0..2000)
const val EPUB_TTS_OD_ENHANCE: String = "reader_epub_tts_od_enhance" // Boolean: clean up raspy on-device audio
const val EPUB_TTS_OD_DENOISE: String = "reader_epub_tts_od_denoise" // Boolean: GTCRN neural denoiser (heavier)
const val EPUB_TTS_OD_VOICE_STYLE: String = "reader_epub_tts_od_voice_style" // Int: 0=Natural,1=Warm,2=Sultry
const val EPUB_TTS_OD_AUTOGEN: String = "reader_epub_tts_od_autogen" // Boolean: auto-pregen all downloaded chapters on book open
const val EPUB_TTS_SERVER_AUTOGEN: String = "reader_epub_tts_server_autogen" // Boolean: offload autogen to the fix server's /tts
const val TTS_REMOTE_FOLDER: String = "tts_remote_data"             // sub-keyed "<bookId>|<modelId>|<sid>" -> server jobId
const val TELEMETRY_ENABLED: String = "telemetry_enabled"           // Boolean: report device status to the server dashboard
const val TELEMETRY_DEVICE_ID: String = "telemetry_device_id"       // String: fallback UUID when ANDROID_ID unavailable
const val EPUB_TTS_PREFETCH: String = "tts_prefetch_on_open"         // Boolean: cache current section + next chapter on chapter open
const val TTS_PREGEN_FOLDER: String = "tts_pregen_data"           // sub-keyed by "<bookId>|<modelId>|<sid>" -> TtsPregenRecord

// ---- On-device LLM novel-fixer (Qwen2.5 GGUF via llama.cpp) ----
const val LLM_FIX_ENABLED: String = "llm_fix_enabled"             // Boolean: feature enabled at all
const val LLM_FIX_MODEL: String = "llm_fix_model"                 // String: model id (see LlmModels)
const val LLM_FIX_SYSTEM_PROMPT: String = "llm_fix_system_prompt" // String: editable system prompt ("" = built-in default)
const val LLM_FIX_PROMPT_VERSION: String = "llm_fix_prompt_version" // Int: bumped on prompt edit; part of the fixed-text cache key
const val LLM_FIX_PREV_CHAPTERS: String = "llm_fix_prev_chapters" // Int: previous chapters given as context (0..3)
const val LLM_FIX_SHOW_FIXED: String = "llm_fix_show_fixed"       // Boolean: reader shows fixed text vs original
const val LLM_FIX_FOLDER: String = "llm_fix_pregen"              // background-fix job records, sub-keyed like TTS_PREGEN_FOLDER
const val LLM_FIX_SERVER_URL: String = "llm_fix_server_url"     // String: GPU fix server base URL ("" = on-device)
const val LLM_FIX_SERVER_MODEL: String = "llm_fix_server_model" // String: server model id ("" = server default)
const val CHAR_GRAPH_FOLDER: String = "char_graph"              // character memory nodes, keyed "char_graph/<bookId>/<charId>"

const val EPUB_BG_COLOR: String = "reader_epub_bg_color"
const val EPUB_TEXT_COLOR: String = "reader_epub_text_color"
const val EPUB_TEXT_VERTICAL_PADDING: String = "reader_epub_vertical_padding"
const val EPUB_TEXT_PADDING: String = "reader_epub_text_padding"
const val EPUB_TEXT_PADDING_TOP: String = "reader_epub_text_padding_top"
const val EPUB_HAS_BATTERY: String = "reader_epub_has_battery"
const val EPUB_KEEP_SCREEN_ACTIVE: String = "reader_epub_keep_screen_active"
const val EPUB_SLEEP_TIMER: String = "reader_epub_tts_timer"
const val EPUB_ML_FROM_LANGUAGE: String = "reader_epub_ml_from"
const val EPUB_ML_TO_LANGUAGE: String = "reader_epub_ml_to"
const val EPUB_ML_USEONLINETRANSLATION: String = "reader_epub_ml_useOnlineTranslation"
const val EPUB_HAS_TIME: String = "reader_epub_has_time"
const val EPUB_TWELVE_HOUR_TIME: String = "reader_epub_twelve_hour_time"
const val EPUB_FONT: String = "reader_epub_font"
const val EPUB_LANG: String = "reader_epub_lang"
const val EPUB_VOICE: String = "reader_epub_voice"
const val EPUB_READER_TYPE: String = "reader_reader_type"
const val EPUB_CURRENT_POSITION: String = "reader_epub_position"
const val EPUB_CURRENT_POSITION_SCROLL: String = "reader_epub_position_scroll"
const val EPUB_CURRENT_POSITION_SCROLL_CHAR: String = "reader_epub_position_scroll_char"
const val EPUB_CURRENT_ML: String = "reader_epub_ml"
const val EPUB_CURRENT_POSITION_READ_AT: String = "reader_epub_position_read"
const val EPUB_CURRENT_POSITION_CHAPTER: String = "reader_epub_position_chapter"
const val RESULT_BOOKMARK: String = "result_bookmarked"
const val RESULT_BOOKMARK_STATE: String = "result_bookmarked_state"
const val HISTORY_FOLDER: String = "result_history"
const val CURRENT_TAB : String = "current_tab"
/** When inserting many keys use this function, this is because apply for every key is very expensive on memory */
data class Editor(
    val editor : SharedPreferences.Editor
) {
    /** Always remember to call apply after */
    fun<T> setKeyRaw(path: String, value: T) {
        @Suppress("UNCHECKED_CAST")
        if (isStringSet(value)) {
            editor.putStringSet(path, value as Set<String>)
        } else {
            when (value) {
                is Boolean -> editor.putBoolean(path, value)
                is Int -> editor.putInt(path, value)
                is String -> editor.putString(path, value)
                is Float -> editor.putFloat(path, value)
                is Long -> editor.putLong(path, value)
            }
        }
    }

    private fun isStringSet(value: Any?) : Boolean {
        if (value is Set<*>) {
            return value.filterIsInstance<String>().size == value.size
        }
        return false
    }

    fun apply() {
        editor.apply()
        System.gc()
    }
}

object DataStore {

    fun editor(context : Context, isEditingAppSettings: Boolean = false) : Editor {
        val editor: SharedPreferences.Editor =
            if (isEditingAppSettings) context.getDefaultSharedPrefs().edit() else context.getSharedPrefs().edit()
        return Editor(editor)
    }

    val mapper: JsonMapper = JsonMapper.builder().addModule(
        KotlinModule.Builder()
            .withReflectionCacheSize(512)
            .configure(KotlinFeature.NullToEmptyCollection, false)
            .configure(KotlinFeature.NullToEmptyMap, false)
            .configure(KotlinFeature.NullIsSameAsDefault, false)
            .configure(KotlinFeature.SingletonSupport, false)
            .configure(KotlinFeature.StrictNullChecks, false)
            .build()
    )
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    fun Context.getSharedPrefs(): SharedPreferences {
        return getPreferences(this)
    }

    fun getFolderName(folder: String, path: String): String {
        return "${folder}/${path}"
    }

    fun Context.getDefaultSharedPrefs(): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(this)
    }

    fun Context.getKeys(folder: String): List<String> {
        return this.getSharedPrefs().all.keys.filter { it.startsWith(folder) }
    }

    fun Context.removeKey(folder: String, path: String) {
        removeKey(getFolderName(folder, path))
    }

    fun Context.containsKey(folder: String, path: String): Boolean {
        return containsKey(getFolderName(folder, path))
    }

    fun Context.containsKey(path: String): Boolean {
        val prefs = getSharedPrefs()
        return prefs.contains(path)
    }

    fun Context.removeKey(path: String) {
        try {
            val prefs = getSharedPrefs()
            if (prefs.contains(path)) {
                prefs.edit {
                    remove(path)
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    fun Context.removeKeys(folder: String): Int {
        val keys = getKeys(folder)
        keys.forEach { value ->
            removeKey(value)
        }
        return keys.size
    }

    fun <T> Context.setKey(path: String, value: T) {
        try {
            getSharedPrefs().edit {
                putString(path, mapper.writeValueAsString(value))
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) {
        setKey(getFolderName(folder, path), value)
    }

    inline fun <reified T : Any> String.toKotlinObject(): T {
        return mapper.readValue(this, T::class.java)
    }

    fun <T> String.toKotlinObject(valueType: Class<T>): T {
        return mapper.readValue(this, valueType)
    }

    // GET KEY GIVEN PATH AND DEFAULT VALUE, NULL IF ERROR
    inline fun <reified T : Any> Context.getKey(path: String, defVal: T?): T? {
        try {
            val json: String = getSharedPrefs().getString(path, null) ?: return defVal
            return json.toKotlinObject()
        } catch (e: Exception) {
            return null
        }
    }

    fun <T> Context.getKey(path: String, valueType: Class<T>): T? {
        try {
            val json: String = getSharedPrefs().getString(path, null) ?: return null
            return json.toKotlinObject(valueType)
        } catch (e: Exception) {
            return null
        }
    }

    inline fun <reified T : Any> Context.getKey(path: String): T? {
        return getKey(path, null)
    }

    inline fun <reified T : Any> Context.getKey(folder: String, path: String): T? {
        return getKey(getFolderName(folder, path), null)
    }

    inline fun <reified T : Any> Context.getKey(folder: String, path: String, defVal: T?): T? {
        return getKey(getFolderName(folder, path), defVal) ?: defVal
    }
}