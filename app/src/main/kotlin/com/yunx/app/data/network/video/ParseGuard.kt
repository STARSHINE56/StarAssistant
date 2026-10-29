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

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 解析器的「节流 + 熔断」闸门，**按平台**独立计数。
 *
 * 存在的意义：各平台的匿名解析本质上是在蹭人家公开接口的余量，短时间内连点几次就会吃到
 * 403/418（微博、小红书、快手都这样），而被风控之后接下来几次**必然**失败 —— 用户看到的是
 * 「刚才还好好的，突然就一直失败」。两道闸门分别治两件事：
 *
 * 1. **节流**（[Config.minIntervalMs]）：同一平台两次请求之间强制拉开间隔。治「连点解析按钮」，
 *    代价是把用户的手速限制在平台能接受的范围，比吃一记 403 便宜。
 * 2. **熔断**（[Config.failureThreshold] / [Config.cooldownMs]）：连续失败到阈值后直接开闸，
 *    一段时间内**不再发请求**，冷却结束后只放**一条**探测请求（半开）：
 *    - 探测成功 → 完全恢复，计数清零；
 *    - 探测失败 → 冷却时间翻倍重开（上限 [Config.maxCooldownMs]），避免对已被封的环境反复重试。
 *
 * ★ 哪些失败计入熔断：只有 [VideoParseErrorKind.BLOCKED] / [NETWORK] / [UNKNOWN] ——
 *   它们代表「平台侧/链路侧」的问题，重试有意义。`NOT_FOUND`（内容被删）与 `UNSUPPORTED`
 *   （链接形态不对、该登录没登录）是**用户侧**的事实，重试一万次也一样，计入熔断只会让
 *   正常用户被自己的错误链接误伤。
 *
 * ★ 时钟可注入（[clock]）：熔断是有时间语义的状态机，单测里用假时钟才能把「冷却到期」这类
 *   分支跑出来，不用真的 `delay(60s)`。
 *
 * ★ 线程：状态用 [Mutex] 保护，`acquire` / `record` 都是 `suspend` —— 允许并发调用，
 *   但同一平台的计数只有一份。多平台之间互不阻塞。
 */
internal class ParseGuard(
    private val config: Config = Config(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {

    /**
     * 闸门参数。默认值按 2026-10 的实测体感定：
     * 连续 3 次失败就该停手（第 4 次几乎必挂），冷却 60s 足够平台侧的风控窗口翻篇。
     */
    data class Config(
        /** 连续可计入的失败次数，达到即开闸 */
        val failureThreshold: Int = 3,
        /**
         * 首次熔断的冷却时长。
         *
         * ★ 30s 而不是 60s：错误卡片里没有「立即重试」的出口，用户只能干等。
         *   实测 B 站/微博的风控窗口多在 10~30 秒，30 秒足够跨过，又不至于让人以为软件卡死。
         *   （若将来给错误卡片加了重试按钮，这个值可以再调回 60s。）
         */
        val cooldownMs: Long = 30_000L,
        /** 冷却时长翻倍的上限 */
        val maxCooldownMs: Long = 300_000L,
        /** 未单独配置的平台使用的请求最小间隔 */
        val defaultMinIntervalMs: Long = 500L,
        /**
         * 各平台的最小请求间隔。
         *
         * 抖音/小红书风控最紧（小红书还有 `xsec_token` 校验，请求太密会直接落到登录页），
         * 微博相对宽松。宁可慢一点，也不要让用户在「成功 → 403 → 成功 → 403」之间来回跳。
         */
        val minIntervalMs: Map<VideoPlatform, Long> = mapOf(
            VideoPlatform.DOUYIN to 1_500L,
            VideoPlatform.XHS to 1_200L,
            VideoPlatform.KUAISHOU to 1_000L,
            VideoPlatform.WEIBO to 800L
        )
    )

    /** 熔断状态（仅用于诊断展示与单测断言，解析流程不依赖它做判断） */
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private class Entry {
        /** 连续失败次数；成功即清零 */
        var failures: Int = 0
        /**
         * 是否处于开闸状态。
         *
         * ★ 用独立布尔量而不是「openedAt != 0」当哨兵：时钟可以合法地走到 0
         *   （测试里的假时钟就从 0 起步），把「开闸时刻是 0」误判成「没开闸」会让熔断整个失效。
         */
        var opened: Boolean = false
        /** 开闸时刻（毫秒，来自 [clock]） */
        var openedAt: Long = 0L
        /** 本轮冷却时长 */
        var cooldown: Long = 0L
        /** 半开状态下是否已有探测请求在途（保证只放一条） */
        var probing: Boolean = false
        /** 上一次放行时刻，节流用 */
        var lastCallAt: Long? = null
    }

    private val lock = Mutex()
    private val entries = HashMap<VideoPlatform, Entry>()

    private fun entryOf(platform: VideoPlatform): Entry = entries.getOrPut(platform) { Entry() }

    /**
     * 放行前的闸门：先按平台节流（会**挂起**等待），再判断熔断。
     *
     * @throws VideoParseException `kind == COOLDOWN` 表示熔断中，调用方应当直接提示用户稍后再试，
     *   或者改走不需要 HTTP 的兜底通道（WebView 嗅探）。
     */
    suspend fun acquire(platform: VideoPlatform) {
        // 1) 节流：先把「本次放行时刻」占到表里再等待，避免并发调用同时读到同一个 lastCallAt 一起通过
        val waitMs = lock.withLock {
            val entry = entryOf(platform)
            val interval = config.minIntervalMs[platform] ?: config.defaultMinIntervalMs
            val now = clock()
            val last = entry.lastCallAt
            val wait = if (last == null) 0L else (last + interval - now).coerceAtLeast(0L)
            entry.lastCallAt = now + wait
            wait
        }
        if (waitMs > 0) delay(waitMs)

        // 2) 熔断
        lock.withLock {
            val entry = entryOf(platform)
            val now = clock()
            if (!entry.opened) return@withLock
            if (now < entry.openedAt + entry.cooldown) throw cooldownException(platform, entry, now)
            if (entry.probing) throw cooldownException(platform, entry, now)
            entry.probing = true
        }
    }

    /**
     * 记录一次放行的结果。
     *
     * @param failure 成功传 null；失败传 [VideoParseException.kind]
     */
    suspend fun record(platform: VideoPlatform, failure: VideoParseErrorKind?) {
        lock.withLock {
            val entry = entryOf(platform)
            entry.probing = false
            if (failure == null) {
                entry.failures = 0
                entry.opened = false
                entry.openedAt = 0L
                entry.cooldown = 0L
                return@withLock
            }
            // 用户侧事实（内容被删 / 链接形态不对）不计入熔断，但也算「这条放行已结束」
            if (!countsTowardBreaker(failure)) return@withLock

            if (!entry.opened) {
                entry.failures++
                if (entry.failures >= config.failureThreshold) {
                    entry.opened = true
                    entry.openedAt = clock()
                    entry.cooldown = config.cooldownMs
                }
            } else {
                // 半开探测又失败：冷却翻倍后重新开闸
                entry.openedAt = clock()
                entry.cooldown = (entry.cooldown * 2).coerceAtMost(config.maxCooldownMs)
            }
        }
    }

    /** 当前状态（诊断用） */
    suspend fun state(platform: VideoPlatform): State = lock.withLock {
        val entry = entries[platform] ?: return@withLock State.CLOSED
        when {
            !entry.opened -> State.CLOSED
            clock() < entry.openedAt + entry.cooldown -> State.OPEN
            else -> State.HALF_OPEN
        }
    }

    /** 剩余冷却毫秒数（0 表示未熔断）；提示文案里要告诉用户「还要等多久」 */
    suspend fun remainingCooldownMs(platform: VideoPlatform): Long = lock.withLock {
        val entry = entries[platform] ?: return@withLock 0L
        if (!entry.opened) return@withLock 0L
        (entry.openedAt + entry.cooldown - clock()).coerceAtLeast(0L)
    }

    /** 清空全部状态（切换账号、用户手动「重试」时调用，给一次干净的重来机会） */
    suspend fun reset() = lock.withLock { entries.clear() }

    private fun cooldownException(platform: VideoPlatform, entry: Entry, now: Long): VideoParseException {
        val remainSec = ((entry.openedAt + entry.cooldown - now) / 1000L).coerceAtLeast(1L)
        // ★ 文案口径：这是**我们**为了避免继续挨风控而主动踩的刹车，不是用户操作失败。
        //   写成「已暂停 58s」会让人以为软件坏了；写成「请求过于频繁」才是可理解的原因。
        return VideoParseException(
            VideoParseErrorKind.COOLDOWN,
            "${platform.displayName}请求过于频繁，请 ${remainSec} 秒后再试"
        )
    }

    private fun countsTowardBreaker(kind: VideoParseErrorKind): Boolean = when (kind) {
        VideoParseErrorKind.BLOCKED,
        VideoParseErrorKind.NETWORK,
        VideoParseErrorKind.UNKNOWN -> true

        VideoParseErrorKind.NOT_FOUND,
        VideoParseErrorKind.UNSUPPORTED,
        VideoParseErrorKind.COOLDOWN -> false
    }
}
