package com.yunx.app.data.download

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class DownloadTransferPolicyTest {
    @Test fun legacyPlanSurvivesConnectionChanges() {
        val text = "chunks=256 total=104857600 main=179"
        val plan = TransferPlan.parse(text, 104857600)!!
        assertEquals(text, plan.encode())
        assertEquals(409600L, plan.chunkSize)
        assertNull(TransferPlan.parse(text, 104857601))
        assertNull(TransferPlan.parse("chunks=0 total=100 main=0", 100))
        assertNull(TransferPlan.parse("chunks=999 total=100 main=999", 100))
    }
    @Test fun tailPreservesPartialDataAndFillsHoles() {
        val dir = createTempDir()
        try {
            val partial = File(dir, "seg_100_199.part").apply { writeBytes(ByteArray(40)) }
            File(dir, "seg_300_399.part").writeBytes(ByteArray(100))
            val tail = ResumableTail(dir, 100, 500)
            assertEquals(140L, tail.existingBytes())
            val ranges = generateSequence { tail.take(1024 * 1024) }.toList()
            assertEquals(listOf(100L to 199L, 200L to 299L, 300L to 399L, 400L to 499L), ranges.map { it.start to it.end })
            assertEquals(40L, partial.length())
            assertFalse(tail.hasPending())
        } finally { dir.deleteRecursively() }
    }
    @Test fun randomResumeHolesProduceExactNonOverlappingCoverage() {
        val random = java.util.Random(73)
        repeat(50) {
            val dir = createTempDir()
            try {
                val start = 123L
                val total = 2 * 1024 * 1024L + 317
                var cursor = start
                while (cursor < total) {
                    val end = minOf(total - 1, cursor + 100000 + random.nextInt(100000))
                    if (random.nextBoolean()) {
                        val file = File(dir, "seg_${cursor}_${end}.part")
                        file.writeBytes(ByteArray(random.nextInt((end - cursor + 2).toInt())))
                    }
                    cursor = end + 1
                }
                val tail = ResumableTail(dir, start, total)
                val parts = generateSequence { tail.take(256 * 1024L + random.nextInt(512 * 1024)) }.toList()
                assertEquals(start, parts.first().start)
                assertEquals(total - 1, parts.last().end)
                assertEquals(total - start, parts.sumOf { it.size })
                parts.zipWithNext().forEach { (a, b) -> assertEquals(a.end + 1, b.start) }
            } finally { dir.deleteRecursively() }
        }
    }
    @Test fun tailRejectsOverlapAndOversizedParts() {
        val dir = createTempDir()
        try {
            File(dir, "seg_100_199.part").writeBytes(ByteArray(101))
            assertThrows(IOException::class.java) { ResumableTail(dir, 100, 500) }
            File(dir, "seg_100_199.part").writeBytes(ByteArray(40))
            File(dir, "seg_150_249.part").writeBytes(ByteArray(40))
            assertThrows(IOException::class.java) { ResumableTail(dir, 100, 500) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun adaptiveOnlyKeepsUsefulIncreases() {
        var now = 0L
        val policy = AdaptiveConnections(64) { now }
        // ★ 起始并发 16（原 8）：网盘 CDN 按连接限速，16 起跑避免前十几秒浪费在爬升上
        assertEquals(16, policy.limit)
        policy.sample(1000, true)      // 建立基线
        policy.sample(1000, true)      // 翻倍到 32 并进入探针
        assertEquals(32, policy.limit)
        policy.sample(1000, true)      // 没提速 → 回退到 16 并冻结 30s
        assertEquals(16, policy.limit)
        now = 31_000
        policy.sample(1000, true)
        policy.sample(1000, true)
        policy.sample(1800, true)      // 提速 80% → 保留 32
        assertEquals(32, policy.limit)
        policy.congested(2000)         // 拥塞 → 减半
        assertEquals(16, policy.limit)
        policy.sample(10000, true)     // 冻结期内不再变化
        assertEquals(16, policy.limit)
        policy.serialVerification()
        now = 100_000
        policy.sample(10000, true)
        assertEquals(1, policy.limit)
    }
    @Test fun memoryPressureReducesAndNoBacklogDoesNotGrow() {
        val policy = AdaptiveConnections(32)
        repeat(4) { policy.sample(100000, false) }
        assertEquals(16, policy.limit)          // 起始并发 16（原 8）
        policy.sample(100000, true, true)       // 内存压力 → 减半
        assertEquals(8, policy.limit)
        assertTrue(policy.blockBytes(Long.MAX_VALUE / 100) in 1024 * 1024L..16 * 1024 * 1024L)
    }
    @Test fun permitsBoundConcurrencyAndReleaseOnFailure() = runBlocking {
        val policy = AdaptiveConnections(2)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        coroutineScope {
            List(20) { launch {
                policy.withPermit {
                    val n = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, n) }
                    delay(2)
                    active.decrementAndGet()
                }
            } }.joinAll()
        }
        assertTrue(peak.get() <= 2)
        try { policy.withPermit<Unit> { throw IOException() } } catch (_: IOException) { }
        withTimeout(500) { policy.withPermit { assertEquals(0, active.get()) } }
    }
    @Test fun waitingPermitIsCancellable() = runBlocking {
        val policy = AdaptiveConnections(1)
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch { policy.withPermit { held.complete(Unit); release.await() } }
        held.await()
        val waiting = launch { policy.withPermit { fail("cancelled waiter was admitted") } }
        waiting.cancelAndJoin()
        release.complete(Unit)
        owner.join()
    }
    @Test fun retryAfterAndValidatorAreBounded() {
        assertEquals(2000L, TransferHttpPolicy.retryAfter("2"))
        assertEquals(120000L, TransferHttpPolicy.retryAfter("999999"))
        assertEquals(1000L, TransferHttpPolicy.retryAfter("broken"))
        assertEquals(10000L, TransferHttpPolicy.retryAfter("Wed, 14 Oct 2026 10:00:10 GMT", 1791972000000L))
        assertEquals("\"strong\"", TransferHttpPolicy.validator("\"strong\"", ""))
        assertNull(TransferHttpPolicy.validator("W/\"weak\"", "invalid"))
        assertEquals("Wed, 14 Oct 2026 10:00:10 GMT", TransferHttpPolicy.validator("W/\"weak\"", "Wed, 14 Oct 2026 10:00:10 GMT"))
    }
}
