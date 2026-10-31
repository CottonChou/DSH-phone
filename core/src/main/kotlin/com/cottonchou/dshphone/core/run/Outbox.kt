package com.cottonchou.dshphone.core.run

import kotlinx.serialization.Serializable

enum class DeliveryState { PENDING, ATTEMPTING, DELIVERED, FAILED, UNCERTAIN }

/** A message destined for the user through some channel. */
@Serializable
data class OutboundMessage(
    val id: String,
    val threadId: String,
    val kind: Kind,
    val text: String,
) {
    @Serializable
    enum class Kind { RESULT, QUESTION, CONFIRMATION, TAKEOVER, NOTICE }
}

/**
 * Delivery ledger. Record first, send second: after a process restart we can tell
 * the user what was already sent and what was lost. An uncertain delivery is never
 * resent automatically.
 */
interface Outbox {
    suspend fun record(message: OutboundMessage): DeliveryState

    suspend fun mark(id: String, state: DeliveryState)

    suspend fun history(threadId: String): List<Pair<OutboundMessage, DeliveryState>>
}

/** In-memory implementation; the Android layer wraps a persisted one. */
class InMemoryOutbox : Outbox {
    private val ledger = LinkedHashMap<String, Pair<OutboundMessage, DeliveryState>>()

    override suspend fun record(message: OutboundMessage): DeliveryState {
        ledger[message.id] = message to DeliveryState.PENDING
        return DeliveryState.PENDING
    }

    override suspend fun mark(id: String, state: DeliveryState) {
        ledger[id]?.let { (m, _) -> ledger[id] = m to state }
    }

    override suspend fun history(threadId: String) =
        ledger.values.filter { it.first.threadId == threadId }
}
