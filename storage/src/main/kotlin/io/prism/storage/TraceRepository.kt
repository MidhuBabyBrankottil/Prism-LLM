package io.prism.storage

import io.prism.core.model.LlmCallTrace
import java.util.concurrent.ConcurrentLinkedDeque

interface TraceRepository {
    fun save(trace: LlmCallTrace)
    fun findAll(limit: Int = 100, offset: Int = 0): List<LlmCallTrace>
    fun findSince(timestamp: Long): List<LlmCallTrace>
    fun findByFeature(featureTag: String): List<LlmCallTrace>
    fun count(): Long
    fun clear()
}

/**
 * Fast, thread-safe in-memory ring buffer repository.
 * Keeps the most recent [maxCapacity] traces for sub-millisecond retrieval.
 */
class InMemoryTraceRepository(private val maxCapacity: Int = 5000) : TraceRepository {
    private val deque = ConcurrentLinkedDeque<LlmCallTrace>()

    override fun save(trace: LlmCallTrace) {
        deque.addFirst(trace)
        while (deque.size > maxCapacity) {
            deque.pollLast()
        }
    }

    override fun findAll(limit: Int, offset: Int): List<LlmCallTrace> {
        return deque.drop(offset).take(limit)
    }

    override fun findSince(timestamp: Long): List<LlmCallTrace> {
        return deque.filter { it.timestamp >= timestamp }
    }

    override fun findByFeature(featureTag: String): List<LlmCallTrace> {
        return deque.filter { it.featureTag.equals(featureTag, ignoreCase = true) }
    }

    override fun count(): Long = deque.size.toLong()

    override fun clear() {
        deque.clear()
    }
}
