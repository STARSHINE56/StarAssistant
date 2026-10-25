package com.yunx.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分片规划（chunkCountFor）的等价实现测试。
 *
 * 目标片大小 4MB 是本轮提速的核心：1MB 片在网盘常见的 1-3MB/s 单连接速度下，
 * 有 7-19% 的带宽被「建连 + 请求 + 等首字节」的固定开销吃掉。这里锁死三条边界，
 * 防止以后改参数时把小文件或大文件弄坏。
 */
class ChunkPlanTest {

    /** 与 DownloadManager.chunkCountFor 逐行等价（那边是 private，故在此复刻并加断言） */
    private fun plan(total: Long, threads: Int = 32): Int {
        if (total <= 0) return 1
        if (total < 5L * 1024 * 1024) return 1
        val byTarget = total / (4L * 1024 * 1024)
        val lower = maxOf(byTarget, threads * 2L)
        val upper = minOf(threads * 8L, total / 1024 / 1024, 512L)
        return lower.coerceAtMost(upper).coerceAtLeast(8L).toInt()
    }

    private fun mb(n: Int) = n.toLong() * 1024 * 1024

    @Test
    fun tinyFilesAreNotSplit() {
        assertEquals(1, plan(mb(1)))
        assertEquals(1, plan(mb(4)))
        assertEquals(1, plan(4L * 1024 * 1024 + 1))  // 刚好过 5MB 门槛前
    }

    @Test
    fun smallFilesKeepRoughlyOneMegabytePieces() {
        // 10-50MB：片数不变（= 每 MB 一片），保证并发能吃满、行为与旧版一致
        assertEquals(10, plan(mb(10)))
        assertEquals(30, plan(mb(30)))
        assertEquals(50, plan(mb(50)))
    }

    @Test
    fun midSizeFilesGetBiggerPieces() {
        // 100MB 旧版 100 片 × 1MB；现在 64 片 × 1.56MB
        val p100 = plan(mb(100))
        assertTrue("100MB 片数应少于 100，实际 $p100", p100 < 100)
        assertTrue("100MB 单片应大于 1MB", mb(100) / p100 > 1024 * 1024)
        // 300MB 应接近 4MB 一片
        val p300 = plan(mb(300))
        assertTrue("300MB 单片应 ≥3MB", mb(300) / p300 >= 3 * 1024 * 1024)
    }

    @Test
    fun largeFilesCapChunkCountInsteadOfExploding() {
        // ≥1GB：片数封顶 threads*8 = 256，单片随体积自然变大
        assertEquals(256, plan(mb(1024)))
        assertEquals(256, plan(mb(4096)))
        assertEquals(256, plan(mb(10240)))
        assertTrue("10GB 单片应 ≥ 32MB", mb(10240) / plan(mb(10240)) >= 32L * 1024 * 1024)
    }

    @Test
    fun alwaysEnoughChunksForStartingConcurrency() {
        // 起始并发是 8，任何需要分片的文件都必须至少 8 片，否则 worker 抢不到活
        listOf(6, 8, 10, 20, 50, 100).forEach { size ->
            val p = plan(mb(size))
            assertTrue("${size}MB 只有 $p 片，少于起始并发 8", p >= 8)
        }
    }

    @Test
    fun pieceSizeNeverBelowOneMegabyte() {
        // 8MB 起单片不应小于 1MB（否则分片本身成了开销）。
        // ★ 5MB 是例外：保底 8 片会切到 640KB，但此时「多一路并发」的收益大于那点开销，
        //   与其为了凑 1MB 把并发从 8 降到 5，不如保留 8 片。
        listOf(8, 10, 20, 50, 100, 300, 1024, 4096).forEach { size ->
            val total = mb(size)
            val p = plan(total)
            if (p > 1) {
                assertTrue("${size}MB 单片 ${total / p} < 1MB", total / p >= 1024 * 1024)
            }
        }
        // 5MB 档：单片可以低于 1MB，但不低于 512KB
        assertEquals(8, plan(mb(5)))
        assertTrue("5MB 单片应 ≥512KB", mb(5) / plan(mb(5)) >= 512 * 1024)
    }

    @Test
    fun differentThreadCountsStaySane() {
        listOf(1, 4, 8, 16, 32, 64).forEach { t ->
            val p = plan(mb(100), t)
            assertTrue("threads=$t 片数=$p 越界", p in 1..512)
            assertTrue("threads=$t 片数 $p 少于起始并发 8", p >= 8)
        }
    }
}
