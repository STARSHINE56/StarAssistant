/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunx.app.data.network.video

import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 闸门的规则测试。
 *
 * 熔断是「有时间语义的状态机」，用假时钟才把冷却到期 / 半开 / 翻倍这几条分支跑全，
 * 否则一个用例就得真等 60 秒。
 */
class ParseGuardTest {

    /** 只测熔断时把节流关掉，避免用例被 delay 拖慢 */
    private fun breakerOnly() = ParseGuard.Config(
        failureThreshold = 3,
        cooldownMs = 60_000L,
        maxCooldownMs = 300_000L,
        defaultMinIntervalMs = 0L,
        minIntervalMs = emptyMap()
    )

    /** 断言 acquire 抛 COOLDOWN 的辅助（不用断言库的 assertThrows：它套不住 suspend 调用） */
    private suspend fun acquiringFailure(block: suspend () -> Unit): VideoParseException? = try {
        block()
        null
    } catch (e: VideoParseException) {
        e
    }

    private class FakeClock(var now: Long = 0L) {
        fun advance(ms: Long) { now += ms }
        fun asFn(): () -> Long = { now }
    }

    @Test
    fun `连续失败达到阈值后开闸并抛 COOLDOWN`() = runBlocking {
        val clock = FakeClock()
        val guard = ParseGuard(breakerOnly(), clock.asFn())

        repeat(3) {
            guard.acquire(VideoPlatform.WEIBO)
            guard.record(VideoPlatform.WEIBO, VideoParseErrorKind.BLOCKED)
        }

        val error = acquiringFailure { guard.acquire(VideoPlatform.WEIBO) }
        assertEquals(VideoParseErrorKind.COOLDOWN, error?.kind)
        assertEquals(ParseGuard.State.OPEN, guard.state(VideoPlatform.WEIBO))
    }

    @Test
    fun `内容不存在不计入熔断`() = runBlocking {
        val clock = FakeClock()
        val guard = ParseGuard(breakerOnly(), clock.asFn())

        // 连抛 10 次「作品已删除」，也不应该把这个平台冷静掉
        repeat(10) {
            guard.acquire(VideoPlatform.XHS)
            guard.record(VideoPlatform.XHS, VideoParseErrorKind.NOT_FOUND)
        }
        assertEquals(ParseGuard.State.CLOSED, guard.state(VideoPlatform.XHS))
        guard.acquire(VideoPlatform.XHS)
    }

    @Test
    fun `冷却是按平台独立的`() = runBlocking {
        val clock = FakeClock()
        val guard = ParseGuard(breakerOnly(), clock.asFn())

        repeat(3) {
            guard.acquire(VideoPlatform.DOUYIN)
            guard.record(VideoPlatform.DOUYIN, VideoParseErrorKind.NETWORK)
        }
        assertEquals(ParseGuard.State.OPEN, guard.state(VideoPlatform.DOUYIN))
        // 抖音被熔断，不妨碍快手照常解析
        guard.acquire(VideoPlatform.KUAISHOU)
    }

    @Test
    fun `冷却到期后半开只放一条探测`() = runBlocking {
        val clock = FakeClock()
        val guard = ParseGuard(breakerOnly(), clock.asFn())
        repeat(3) {
            guard.acquire(VideoPlatform.KUAISHOU)
            guard.record(VideoPlatform.KUAISHOU, VideoParseErrorKind.UNKNOWN)
        }

        clock.advance(60_001L)
        assertEquals(ParseGuard.State.HALF_OPEN, guard.state(VideoPlatform.KUAISHOU))
        // 第一条探测放行
        guard.acquire(VideoPlatform.KUAISHOU)
        // 探测还没出结果，第二条必须被挡（否则「半开」等于「全开」）
        val error = acquiringFailure { guard.acquire(VideoPlatform.KUAISHOU) }
        assertEquals(VideoParseErrorKind.COOLDOWN, error?.kind)
    }

    @Test
    fun `半开探测成功后完全恢复`() = runBlocking {
        val clock = FakeClock()
        val guard = ParseGuard(breakerOnly(), clock.asFn())
        repeat(3) {
            guard.acquire(VideoPlatform.ACFUN)
            guard.record(VideoPlatform.ACFUN, VideoParseErrorKind.BLOCKED)
        }
        clock.advance(60_001L)
        guard.acquire(VideoPlatform.ACFUN)
        guard.record(VideoPlatform.ACFUN, null)

        assertEquals(ParseGuard.State.CLOSED, guard.state(VideoPlatform.ACFUN))
        // 计数清零：再失败两次不该开闸
        repeat(2) {
            guard.acquire(VideoPlatform.ACFUN)
            guard.record(VideoPlatform.ACFUN, VideoParseErrorKind.BLOCKED)
        }
        assertEquals(ParseGuard.State.CLOSED, guard.state(VideoPlatform.ACFUN))
    }

    @Test
    fun `半开探测再失败时冷却翻倍`() = runBlocking {
        val clock = FakeClock()
        val guard = ParseGuard(breakerOnly(), clock.asFn())
        repeat(3) {
            guard.acquire(VideoPlatform.WEIBO)
            guard.record(VideoPlatform.WEIBO, VideoParseErrorKind.BLOCKED)
        }
        assertEquals(60_000L, guard.remainingCooldownMs(VideoPlatform.WEIBO))

        clock.advance(60_001L)
        guard.acquire(VideoPlatform.WEIBO)
        guard.record(VideoPlatform.WEIBO, VideoParseErrorKind.BLOCKED)
        // 第一次是 60s，这次应当翻成 120s
        assertEquals(120_000L, guard.remainingCooldownMs(VideoPlatform.WEIBO))
    }

    @Test
    fun `重置后立刻可用`() = runBlocking {
        val clock = FakeClock()
        val guard = ParseGuard(breakerOnly(), clock.asFn())
        repeat(3) {
            guard.acquire(VideoPlatform.XHS)
            guard.record(VideoPlatform.XHS, VideoParseErrorKind.BLOCKED)
        }
        guard.reset()
        assertEquals(ParseGuard.State.CLOSED, guard.state(VideoPlatform.XHS))
        guard.acquire(VideoPlatform.XHS)
    }

    @Test
    fun `节流会把同平台的两次请求拉开间隔`() = runBlocking {
        val guard = ParseGuard(
            ParseGuard.Config(
                defaultMinIntervalMs = 120L,
                minIntervalMs = mapOf(VideoPlatform.WEIBO to 120L)
            )
        )
        val elapsed = measureTimeMillis {
            guard.acquire(VideoPlatform.WEIBO)
            guard.acquire(VideoPlatform.WEIBO)
            guard.acquire(VideoPlatform.WEIBO)
        }
        // 三次请求两次间隔，至少 240ms；给系统调度留点余量
        assertTrue("实际等待 ${elapsed}ms，应 >= 220ms", elapsed >= 220L)
    }
}
