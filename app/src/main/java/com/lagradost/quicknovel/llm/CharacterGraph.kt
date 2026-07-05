package com.lagradost.quicknovel.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.CHAR_GRAPH_FOLDER
import com.lagradost.quicknovel.DataStore
import me.xdrop.fuzzywuzzy.FuzzySearch
import java.security.MessageDigest

data class CharRel(val target: String = "", val type: String = "")

data class CharacterNode(
    val id: String = "",
    val canonicalName: String = "",
    val aliases: List<String> = emptyList(),
    val gender: String? = null,
    val pronouns: String? = null,
    val traits: List<String> = emptyList(),
    val relationships: List<CharRel> = emptyList(),
    val chapterAppearances: List<Int> = emptyList(),
    val updatedAtChapter: Int = -1,
)

/**
 * Per-novel character memory graph. The LLM extracts characters + attributes from each fixed chapter;
 * we dedup/merge with fuzzy name matching (fuzzywuzzy, already a dependency) and persist one
 * [GraphData] per book in the KV DataStore under [CHAR_GRAPH_FOLDER]. A recency + role scored subset is
 * injected into the fix prompt so pronouns/gender/relationships stay consistent across chapters — the
 * whole point of this feature for machine-translated novels (Chinese 他/她 are both "ta").
 */
object CharacterGraph {
    data class GraphData(val nodes: List<CharacterNode> = emptyList())

    /** DTO for the model's JSON extraction output (tolerant of extra/missing keys). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ExtractedChar(
        @JsonProperty("name") val name: String = "",
        @JsonProperty("aliases") val aliases: List<String> = emptyList(),
        @JsonProperty("gender") val gender: String? = null,
        @JsonProperty("pronouns") val pronouns: String? = null,
        @JsonProperty("traits") val traits: List<String> = emptyList(),
        @JsonProperty("relationships") val relationships: List<CharRel> = emptyList(),
    )

    fun load(bookId: String): List<CharacterNode> =
        runCatching { getKey<GraphData>(CHAR_GRAPH_FOLDER, bookId) }.getOrNull()?.nodes ?: emptyList()

    private fun save(bookId: String, nodes: List<CharacterNode>) {
        runCatching { setKey(CHAR_GRAPH_FOLDER, bookId, GraphData(nodes)) }
    }

    fun clear(bookId: String) {
        runCatching { removeKey(CHAR_GRAPH_FOLDER, bookId) }
    }

    fun knownNames(bookId: String): List<String> = load(bookId).map { it.canonicalName }

    /** Build the Cytoscape.js elements JSON (nodes + resolved relationship edges) for the graph WebView. */
    fun toCytoscapeJson(bookId: String): String {
        val nodes = load(bookId)
        if (nodes.isEmpty()) return """{"elements":[]}"""
        val byName = HashMap<String, String>()
        for (n in nodes) {
            byName[n.canonicalName.trim().lowercase()] = n.id
            n.aliases.forEach { byName[it.trim().lowercase()] = n.id }
        }
        val edges = ArrayList<Map<String, Any?>>()
        val degree = HashMap<String, Int>()
        for (n in nodes) for (rel in n.relationships) {
            val targetId = byName[rel.target.trim().lowercase()] ?: continue
            if (targetId == n.id) continue
            edges.add(mapOf("data" to mapOf("source" to n.id, "target" to targetId, "label" to rel.type)))
            degree[n.id] = (degree[n.id] ?: 0) + 1
            degree[targetId] = (degree[targetId] ?: 0) + 1
        }
        val elements = ArrayList<Map<String, Any?>>()
        for (n in nodes) {
            val detail = buildString {
                if (n.traits.isNotEmpty()) append(n.traits.joinToString(", "))
                if (n.relationships.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(n.relationships.joinToString("; ") { "${it.type} of ${it.target}" })
                }
                append("\nAppears in ${n.chapterAppearances.size} chapter(s)")
            }
            elements.add(
                mapOf(
                    "data" to mapOf(
                        "id" to n.id, "label" to n.canonicalName, "pronouns" to (n.pronouns ?: ""),
                        "gender" to (n.gender ?: "unknown"), "aliases" to n.aliases.joinToString(", "),
                        "detail" to detail, "deg" to (degree[n.id] ?: 0),
                    )
                )
            )
        }
        elements.addAll(edges)
        return DataStore.mapper.writeValueAsString(mapOf("elements" to elements))
    }

    private fun charId(name: String): String =
        MessageDigest.getInstance("SHA-1").digest(name.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)

    /** Defensively pull the JSON array out of a small model's output and parse it (no GBNF on this binding). */
    fun parseExtraction(raw: String): List<ExtractedChar> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return runCatching { DataStore.mapper.readValue<List<ExtractedChar>>(raw.substring(start, end + 1)) }
            .getOrElse { emptyList() }
            .filter { it.name.isNotBlank() }
    }

    /** Merge one chapter's extracted characters into the book graph, deduping by fuzzy name/alias match. */
    fun merge(bookId: String, extracted: List<ExtractedChar>, chapterIndex: Int) {
        if (extracted.isEmpty()) return
        val nodes = load(bookId).toMutableList()
        for (ex in extracted) {
            val name = ex.name.trim()
            if (name.isBlank()) continue
            val idx = nodes.indexOfFirst { node ->
                FuzzySearch.ratio(node.canonicalName, name) >= 85 ||
                    node.aliases.any { FuzzySearch.ratio(it, name) >= 90 } ||
                    ex.aliases.any { al -> FuzzySearch.ratio(node.canonicalName, al) >= 90 }
            }
            if (idx >= 0) {
                val n = nodes[idx]
                nodes[idx] = n.copy(
                    aliases = (n.aliases + ex.aliases + if (name != n.canonicalName) listOf(name) else emptyList()).distinct(),
                    gender = n.gender ?: ex.gender,
                    pronouns = n.pronouns ?: ex.pronouns,
                    traits = (n.traits + ex.traits).distinct().take(6),
                    relationships = (n.relationships + ex.relationships).distinctBy { it.target + "|" + it.type }.take(8),
                    chapterAppearances = (n.chapterAppearances + chapterIndex).distinct(),
                    updatedAtChapter = maxOf(n.updatedAtChapter, chapterIndex),
                )
            } else {
                nodes.add(
                    CharacterNode(
                        id = charId(name), canonicalName = name, aliases = ex.aliases.distinct(),
                        gender = ex.gender, pronouns = ex.pronouns, traits = ex.traits.take(4),
                        relationships = ex.relationships.take(8),
                        chapterAppearances = listOf(chapterIndex), updatedAtChapter = chapterIndex,
                    )
                )
            }
        }
        save(bookId, nodes)
    }

    /** Recency + role scored memory block for the {character_memory} slot (kept to a small char budget). */
    fun memoryBlock(bookId: String, currentChapter: Int, maxChars: Int = 1100): String {
        val nodes = load(bookId)
        if (nodes.isEmpty()) return ""
        val ranked = nodes.sortedWith(
            compareByDescending<CharacterNode> { it.chapterAppearances.contains(currentChapter) }
                .thenBy { currentChapter - it.updatedAtChapter }
                .thenByDescending { it.chapterAppearances.size }
        )
        val sb = StringBuilder()
        for (n in ranked) {
            val line = buildString {
                append(n.canonicalName)
                n.pronouns?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
                if (n.traits.isNotEmpty()) append(": ").append(n.traits.take(3).joinToString(", "))
                if (n.relationships.isNotEmpty())
                    append(". ").append(n.relationships.take(2).joinToString("; ") { "${it.type} of ${it.target}" })
                append(".")
            }
            if (sb.length + line.length > maxChars) break
            sb.append(line).append("\n")
        }
        return sb.toString().trim()
    }
}
