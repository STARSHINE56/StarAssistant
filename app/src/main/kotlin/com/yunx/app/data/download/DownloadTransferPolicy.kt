package com.yunx.app.data.download

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay

/** Persistent geometry is independent of current connection count, including legacy plans. */
internal data class TransferPlan(val chunks: Int, val total: Long, val main: Int) {
    val chunkSize: Long get() = total / chunks + if (total % chunks == 0L) 0 else 1
    val elasticStart: Long get() = main * chunkSize
    fun encode() = "chunks=$chunks total=$total main=$main"
    companion object {
        fun parse(text: String, total: Long): TransferPlan? {
            val m = Regex("chunks=(\\d+) total=(\\d+) main=(\\d+)").matchEntire(text.trim()) ?: return null
            val n = m.groupValues[1].toIntOrNull() ?: return null
            val size = m.groupValues[2].toLongOrNull() ?: return null
            val main = m.groupValues[3].toIntOrNull() ?: return null
            if (size != total || size <= 0 || n !in 1..512 || n.toLong() > total || main !in 1..n) return null
            val plan = TransferPlan(n, size, main)
            return plan.takeIf { it.elasticStart <= size }
        }
    }
}

internal data class TransferPart(val start: Long, val end: Long, val file: File) {
    val size: Long get() = end - start + 1
    fun validate() {
        if (file.length() > size) throw IOException("下载分片长度异常，拒绝合并")
    }
}

/** Keep every valid partial segment, fill holes, and never allocate overlapping ranges. */
internal class ResumableTail(private val dir: File, private val start: Long, private val total: Long) {
    private val saved: List<TransferPart> = dir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
        .orEmpty().map { f ->
            val m = Regex("seg_(\\d+)_(\\d+)\\.part").matchEntire(f.name)
                ?: throw IOException("下载分片索引异常")
            val s = m.groupValues[1].toLongOrNull() ?: throw IOException("下载分片索引异常")
            val e = m.groupValues[2].toLongOrNull() ?: throw IOException("下载分片索引异常")
            if (s < start || e < s || e >= total) throw IOException("下载分片区间异常")
            TransferPart(s, e, f).also { it.validate() }
        }.sortedBy { it.start }
    private var cursor = start
    private var index = 0
    init {
        saved.zipWithNext().forEach { (a, b) ->
            if (a.end >= b.start) throw IOException("下载分片区间重叠，拒绝合并")
        }
    }
    fun existingBytes(): Long = saved.sumOf { it.file.length() }
    @Synchronized fun take(blockBytes: Long): TransferPart? {
        if (cursor >= total) return null
        val old = saved.getOrNull(index)
        if (old != null && old.start == cursor) {
            cursor = old.end + 1
            index++
            return old
        }
        val end = minOf(total - 1, cursor + blockBytes.coerceIn(256 * 1024L, 16 * 1024 * 1024L) - 1, (old?.start ?: total) - 1)
        val part = TransferPart(cursor, end, File(dir, "seg_${cursor}_${end}.part"))
        cursor = end + 1
        return part
    }
    @Synchronized fun hasPending() = cursor < total
}

/** Per-task adaptive admission. Waiting does not consume the global IO/network permit. */
internal class AdaptiveConnections(val maximum: Int, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    @Volatile var limit = minOf(8, maximum.coerceAtLeast(1)); private set
    private var active = 0
    private var until = 0L
    private var freezeUntil = 0L
    private var baseline = 0L
    private var previousLimit = limit
    private var probing = false
    suspend fun <T> withPermit(block: suspend () -> T): T {
        while (true) {
            val admitted = synchronized(this) {
                if (clock() >= until && active < limit) { active++; true } else false
            }
            if (admitted) break
            delay(25)
        }
        try { return block() } finally { synchronized(this) { active-- } }
    }
    @Synchronized fun congested(waitMillis: Long = 1000) {
        limit = maxOf(1, limit / 2)
        until = maxOf(until, clock() + waitMillis.coerceIn(250, 120_000))
        freezeUntil = until + 30_000
        baseline = 0
        probing = false
    }
    @Synchronized fun serialVerification() {
        limit = 1
        probing = false
        freezeUntil = Long.MAX_VALUE
    }
    @Synchronized fun sample(bytesPerSecond: Long, backlog: Boolean, memoryPressure: Boolean = false) {
        if (memoryPressure) { congested(); return }
        if (!backlog || bytesPerSecond <= 0 || clock() < freezeUntil) return
        if (probing) {
            if (bytesPerSecond.toDouble() < baseline.toDouble() * 1.10) {
                limit = previousLimit
                freezeUntil = clock() + 30_000
            }
            probing = false
            baseline = 0
        } else if (limit < maximum && baseline > 0) {
            previousLimit = limit
            baseline = bytesPerSecond
            limit = minOf(maximum, limit * 2)
            probing = true
        } else {
            baseline = bytesPerSecond
        }
    }
    fun blockBytes(bytesPerSecond: Long): Long =
        (bytesPerSecond / limit.coerceAtLeast(1) * 8).coerceIn(1024 * 1024L, 16 * 1024 * 1024L)
}

internal object TransferHttpPolicy {
    private fun date(value: String): Long? = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
            isLenient = false
        }.parse(value)?.time
    }.getOrNull()
    fun validator(etag: String, modified: String): String? =
        etag.trim().takeIf { it.startsWith('"') && it.endsWith('"') }
            ?: modified.trim().takeIf { it.isNotEmpty() && date(it) != null }
    fun retryAfter(value: String?, now: Long = System.currentTimeMillis()): Long {
        val raw = value?.trim().orEmpty()
        val seconds = raw.toLongOrNull()
        if (seconds != null) return seconds.coerceIn(0, 120) * 1000
        val date = date(raw)
        return if (date != null) (date - now).coerceIn(0, 120_000) else 1000
    }
}
