package com.solace.dshphone.core.observe

/** Where an observation came from. */
data class Place(
    val kind: Kind,
    /** App package, site host, or file path. */
    val id: String,
    val title: String = "",
) {
    enum class Kind { APP, SITE, PAGE, COMMAND, FILE }

    fun describe(): String = when (kind) {
        Kind.APP -> "应用 $id"
        Kind.SITE -> "网站 $id"
        Kind.PAGE -> "页面 $id"
        Kind.COMMAND -> "命令输出"
        Kind.FILE -> "文件 $id"
    }
}

enum class ObsKind { READ, OUTPUT, REPLY, USER_SAID, FACT }

/**
 * One thing the agent saw. The evidence ledger is built from these; answer
 * citations and outcome judging read the same records.
 */
data class Observation(
    val id: String,
    val kind: ObsKind,
    val sourceCapability: String,
    val place: Place,
    val title: String,
    val text: String,
    val step: Int,
    val timestampMs: Long,
) {
    companion object {
        /** Content from untrusted sources is fenced once, at construction time. */
        fun fence(raw: String): String = "<untrusted>$raw</untrusted>"
    }
}

/** Append-only record of what the run actually observed. */
class EvidenceLedger {
    private val items = ArrayList<Observation>()
    private var counter = 0

    @Synchronized
    fun add(obs: Observation): Observation {
        counter++
        val numbered = obs.copy(id = "obs-${obs.step}-$counter")
        items.add(numbered)
        return numbered
    }

    @Synchronized
    fun all(): List<Observation> = items.toList()

    @Synchronized
    fun recentReads(limit: Int = 6): List<Observation> =
        items.filter { it.kind == ObsKind.READ }.takeLast(limit)
}
