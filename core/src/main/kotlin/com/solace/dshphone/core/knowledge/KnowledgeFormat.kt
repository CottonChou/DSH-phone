package com.solace.dshphone.core.knowledge

/**
 * A note about a place (app or site): how it works, what trips people up.
 * Injected into the prompt after the agent has seen that place, not before.
 */
data class KnowledgeNote(
    val id: String,
    val placeKey: String,
    val body: String,
)

/**
 * Knowledge format: a markdown body where every section starts with a place key
 * line, e.g. `## place:com.example.app`. Parsed into notes; lookup by place.
 */
object KnowledgeFormat {

    fun parse(markdown: String): List<KnowledgeNote> {
        val notes = ArrayList<KnowledgeNote>()
        var key: String? = null
        val body = StringBuilder()
        fun flush() {
            key?.let { k ->
                val text = body.toString().trim()
                if (text.isNotEmpty()) notes.add(KnowledgeNote("kb-${notes.size}", k, text))
            }
            body.clear()
        }
        markdown.lines().forEach { line ->
            val m = Regex("^##\\s+place:(.+)$").find(line.trim())
            if (m != null) {
                flush()
                key = m.groupValues[1].trim()
            } else if (key != null) {
                body.appendLine(line)
            }
        }
        flush()
        return notes
    }

    fun paragraphFor(notes: List<KnowledgeNote>): String =
        if (notes.isEmpty()) "" else "## 已知知识\n" + notes.joinToString("\n\n") { it.body }
}
