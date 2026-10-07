package com.yunx.app.data.download

/** One atomic FIFO budget for built-in and Gopeed tasks, including engine switches. */
internal class SharedDownloadSlots {
    private val active = LinkedHashSet<Long>()
    private val waiting = LinkedHashSet<Long>()

    @Synchronized
    fun tryAcquire(id: Long, limit: Int): Boolean {
        if (id in active) return true
        waiting.add(id)
        if (active.size >= limit.coerceAtLeast(1) || waiting.firstOrNull() != id) return false
        waiting.remove(id)
        active.add(id)
        return true
    }

    @Synchronized
    fun release(id: Long) { active.remove(id); waiting.remove(id) }

    @Synchronized
    fun contains(id: Long): Boolean = id in active

    @Synchronized
    fun activeCount(): Int = active.size
}
