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

import com.yunx.app.util.DiagnosticLog
import com.yunx.app.util.LogRedactor
import kotlinx.coroutines.CancellationException

/**
 * 解析总闸：**把「谁去解析」升级成「按什么顺序、失败后怎么办」**。
 *
 * 调用链只有三级，但顺序和条件是这个类的全部价值：
 *
 * ```
 * ① ParseGuard 闸门   节流（同平台拉开间隔）→ 熔断（连续失败后暂停，冷却后半开探测）
 * ② VideoParser      各平台自己的第一梯队：官方接口 / 分享页 SSR
 * ③ MediaSniffer     通用 WebView 嗅探（页面一定还能播，媒体地址就一定在网络层出现过）
 * ```
 *
 * ★ 为什么失败要分级而不是一律重试：
 *   - `BLOCKED`（403/418/验证码页）、`NETWORK`、`UNKNOWN`（改版/字段缺失）→ **值得换个通道再试一次**，
 *     所以落到 ③，同时计入熔断；
 *   - `NOT_FOUND`（作品被删）、`UNSUPPORTED`（链接形态不对、该登录没登录）→ **换通道也没用**，
 *     直接抛给用户，且不计入熔断（否则用户自己贴错几个链接，就能把整个平台冷静掉）。
 *
 * ★ 为什么 ③ 成功之后仍要记一次失败：嗅探是「设备把这页渲染了一遍」，它成功不代表
 *   HTTP 那条路恢复了。熔断针对的是接口/风控，不能被 WebView 的偶然成功骗过去。
 *
 * ★ 异常边界：解析器里抛出的**非** [VideoParseException] 一律收敛成 `UNKNOWN` 并保留 cause，
 *   保证上层（`ResolveViewModel`）只会看到一种异常类型；协程取消必须原样抛出，不能被吞成解析失败。
 */
internal class VideoParserGateway(
    private val guard: ParseGuard = ParseGuard(),
    private val sniffer: MediaSniffer = MediaSniffer
) {

    /**
     * 解析一条已识别的链接。
     *
     * @param parser 由调用方按平台取好的解析器（[VideoParsers.of]）
     * @throws VideoParseException 三级都失败时，抛的是**第一梯队**的失败原因 ——
     *   它对用户最有解释力（「被风控拦截」比「嗅探超时」有用得多）
     */
    suspend fun parse(
        link: DetectedVideoLink,
        cookie: String,
        parser: VideoParser
    ): VideoParseResult {
        val platform = link.platform
        var failure: VideoParseException? = null

        // ---------- ① 闸门 ----------
        val gated = try {
            guard.acquire(platform)
            false
        } catch (e: VideoParseException) {
            // 熔断中：不放弃，直接进 ③（嗅探走 WebView，不消耗 HTTP 风控额度）
            if (e.kind != VideoParseErrorKind.COOLDOWN) throw e
            DiagnosticLog.warn(DiagnosticLog.NETWORK, "parse_cooldown", "platform=${platform.name}")
            failure = e
            true
        }

        // ---------- ② 第一梯队 ----------
        if (!gated) {
            try {
                val result = parser.parse(link, cookie)
                guard.record(platform, null)
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: VideoParseException) {
                guard.record(platform, e.kind)
                failure = e
                DiagnosticLog.warn(
                    DiagnosticLog.NETWORK, "parse_failed",
                    "platform=${platform.name} kind=${e.kind} msg=${e.message}"
                )
            } catch (e: Exception) {
                guard.record(platform, VideoParseErrorKind.UNKNOWN)
                failure = VideoParseException(
                    VideoParseErrorKind.UNKNOWN,
                    "${platform.displayName}解析异常：${e.javaClass.simpleName}",
                    e
                )
            }
        }

        val primary = failure ?: VideoParseException(
            VideoParseErrorKind.UNKNOWN,
            "${platform.displayName}解析失败"
        )

        // ---------- ③ 通用嗅探兜底 ----------
        if (shouldFallback(primary.kind) && sniffer.supports(platform)) {
            val sniffed = runCatching {
                MediaSniffer.sniff(link, cookie, mediaHeaders(platform))
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                DiagnosticLog.warn(
                    DiagnosticLog.NETWORK, "sniff_failed",
                    "platform=${platform.name} err=${error.javaClass.simpleName}"
                )
                null
            }
            if (sniffed != null && sniffed.medias.isNotEmpty()) {
                DiagnosticLog.event(
                    DiagnosticLog.NETWORK, "parse_recovered_by_sniff",
                    "platform=${platform.name} medias=${sniffed.medias.size} " +
                        "primary=${primary.kind} url=${LogRedactor.url(link.url)}"
                )
                return sniffed
            }
        }

        throw primary
    }

    /** 用户手动重试时清空熔断（节流的最后一次放行时间保留，避免连点变连发） */
    suspend fun resetCooldown() = guard.reset()

    /** 兜底判据：这几类失败「换通道」还有救（见类注释） */    private fun shouldFallback(kind: VideoParseErrorKind): Boolean = when (kind) {
        VideoParseErrorKind.BLOCKED,
        VideoParseErrorKind.NETWORK,
        VideoParseErrorKind.UNKNOWN,
        VideoParseErrorKind.COOLDOWN -> true

        VideoParseErrorKind.NOT_FOUND,
        VideoParseErrorKind.UNSUPPORTED -> false
    }

    /** 嗅探产出的直链也要能下载，所以带上平台侧的 Referer（具体路径由各平台决定，这里给站点根） */
    private fun mediaHeaders(platform: VideoPlatform): Map<String, String> {
        val referer = when (platform) {
            VideoPlatform.KUAISHOU -> "https://v.kuaishou.com/"
            VideoPlatform.XHS -> "https://www.xiaohongshu.com/"
            VideoPlatform.WEIBO -> "https://m.weibo.cn/"
            VideoPlatform.ACFUN -> "https://www.acfun.cn/"
            else -> ""
        }
        return if (referer.isEmpty()) emptyMap() else mapOf("Referer" to referer)
    }
}
