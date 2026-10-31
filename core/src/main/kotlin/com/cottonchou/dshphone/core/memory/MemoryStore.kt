package com.cottonchou.dshphone.core.memory

import java.util.concurrent.atomic.AtomicLong

/** One remembered preference or habit, explicitly told by the user. */
data class MemoryEntry(
    val id: String,
    val content: String,
    val tag: String,
    val createdAtMs: Long,
)

/**
 * Patterns for data that must never land in long-term memory: passwords,
 * one-time codes and card numbers. Kept deliberately narrow to stay useful.
 */
object SensitiveFilter {
    private val patterns = listOf(
        Regex("""(?i)(密码|口令|password|passwd)\s*[:：是]?\s*\S+"""),
        Regex("""(?i)(验证码|otp|one[- ]?time code)\s*[:：是]?\s*\S*""") ,
        Regex("""\b\d{13,19}\b"""),
        Regex("""(?i)(卡号|card number)\s*[:：是]?\s*\S+"""),
    )

    fun isSensitive(text: String): Boolean = patterns.any { it.containsMatchIn(text) }
}

/** Local, on-device memory of what the user explicitly said. */
class MemoryStore(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val entries = LinkedHashMap<String, MemoryEntry>()
    private val counter = AtomicLong(0)

    /** Returns false and stores nothing when the content trips the sensitive filter. */
    fun remember(content: String, tag: String): Boolean {
        if (content.isBlank()) return false
        if (SensitiveFilter.isSensitive(content)) return false
        val id = "mem-${counter.incrementAndGet()}"
        entries[id] = MemoryEntry(id, content.trim(), tag.trim(), clock())
        return true
    }

    fun recall(query: String, limit: Int = 10): List<MemoryEntry> {
        val q = query.trim().lowercase()
        val all = entries.values.sortedByDescending { it.createdAtMs }
        if (q.isEmpty()) return all.take(limit)
        return all.filter {
            it.content.lowercase().contains(q) || it.tag.lowercase().contains(q)
        }.take(limit)
    }

    fun update(id: String, content: String): Boolean {
        if (SensitiveFilter.isSensitive(content)) return false
        val old = entries[id] ?: return false
        entries[id] = old.copy(content = content.trim())
        return true
    }

    fun forget(id: String) = entries.remove(id)

    fun all(): List<MemoryEntry> = entries.values.toList()

    /** Prompt paragraph injected into the system prompt. */
    fun promptParagraph(limit: Int = 8): String {
        val top = entries.values.sortedByDescending { it.createdAtMs }.take(limit)
        if (top.isEmpty()) return ""
        return "## 用户记忆\n" + top.joinToString("\n") { "- ${it.content}" }
    }
}
