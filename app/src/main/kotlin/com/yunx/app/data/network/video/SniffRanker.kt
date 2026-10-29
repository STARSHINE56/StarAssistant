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

/**
 * WebView 嗅探到的一条候选资源。
 *
 * [source] 只用于诊断（`fetch` / `xhr` / `dom`），不参与排序语义。
 * 宽高来自 `<video>` 元素或页面声明的尺寸，拿不到时是 0 —— 排序要对 0 宽容（见 [SniffRanker]）。
 */
internal data class SniffCandidate(
    val url: String,
    val source: String = "",
    val width: Int = 0,
    val height: Int = 0
)

/**
 * 嗅探候选的**排序器**。
 *
 * 为什么单独拆出来：WebView 里拦到的东西是一锅粥 —— 视频、m3u8、封面、作者头像、
 * 表情包、埋点 gif 全在里面。而这个「哪条才是用户想要的那条」的判断，恰恰是纯逻辑、
 * 不依赖 Android 的，放在这里就能用 JVM 单测把规则钉死（风控手感会变，排序规则也得跟着调，
 * 没有单测就只能靠真机试）。
 *
 * 排序口径（分高者胜，同分保持页面里出现的先后 —— 先出现的通常是主内容）：
 * - **形态**：mp4/flv/mov/webm 视频文件 > m3u8 流 > 无扩展名的「疑似流地址」> 图片；
 * - **路径语义**：`/play/`、`playwm`、`play_addr`、`video/tos`、`upic` 这类字眼加分；
 * - **尺寸**：面积越大越可能是主内容，但**只作为加分项**，因为大量候选拿不到宽高；
 * - **降权**：头像/图标/表情/占位图（`avatar`、`icon`、`logo`、`emoji`、`sprite`、`placeholder`）
 *   直接重罚，宽或高小于 [MIN_MAIN_SIZE] 的一并重罚 —— 这类东西数量多，不压下去会把真正的
 *   媒体挤出结果集。
 *
 * 去重按「host + path」：同一份媒体常带 n 个不同的查询串（鉴权参数/timestamp），
 * 不去重会在结果页里出现一堆看起来一模一样的条目。
 */
internal object SniffRanker {

    /** 小于这个宽/高的一律当作头像/图标处理 */
    private const val MIN_MAIN_SIZE = 200

    private val VIDEO_EXT = Regex("""\.(mp4|flv|mov|webm|m4v)(\?|$)""", RegexOption.IGNORE_CASE)
    private val HLS_EXT = Regex("""\.(m3u8)(\?|$)""", RegexOption.IGNORE_CASE)
    private val IMAGE_EXT = Regex("""\.(jpe?g|png|webp|heic|avif)(\?|$)""", RegexOption.IGNORE_CASE)

    private val PLAY_HINT = Regex(
        """(/play/|playwm|play_addr|playaddr|video_id=|/video/tos/|/upic/|/aweme/|/photo/|/stream/|getplayurl)""",
        RegexOption.IGNORE_CASE
    )

    private val NOISE_HINT = Regex(
        """(avatar|profile_image|head_?img|icon|logo|emoji|sprite|placeholder|loading|spinner|\.gif($|\?))""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 排序并去重。
     *
     * @return 分数从高到低、至多 [limit] 条；空输入返回空（调用方据此判定「没嗅到东西」）
     */
    fun rank(candidates: List<SniffCandidate>, limit: Int = 40): List<SniffCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val best = LinkedHashMap<String, Pair<SniffCandidate, Int>>()
        candidates.forEachIndexed { index, candidate ->
            if (candidate.url.isBlank()) return@forEachIndexed
            val key = dedupeKey(candidate.url)
            val score = score(candidate) - index / 100 // 同分时先出现的略优（index 差异不会跨过一个档位）
            val previous = best[key]
            if (previous == null || score > previous.second) {
                best[key] = candidate to score
            }
        }
        // ★ 先按「形态档位」再按分数：视频永远排在图片前面。
        //   只用一个总分会出现「1000x1000 的封面图压过 m3u8」这种翻车 —— 面积分一加就把档位差吃掉了。
        return best.values
            .sortedWith(
                compareByDescending<Pair<SniffCandidate, Int>> { tierOf(it.first) }
                    .thenByDescending { it.second }
            )
            .take(limit)
            .map { it.first }
    }

    /** 从 URL 反推媒体类型；认不出时给 [VideoMediaKind.VIDEO]（页面内嵌流多是这个） */
    fun kindOf(url: String): VideoMediaKind = when {
        HLS_EXT.containsMatchIn(url) -> VideoMediaKind.VIDEO
        VIDEO_EXT.containsMatchIn(url) -> VideoMediaKind.VIDEO
        IMAGE_EXT.containsMatchIn(url) -> VideoMediaKind.IMAGE
        else -> VideoMediaKind.VIDEO
    }

    fun isHls(url: String): Boolean = HLS_EXT.containsMatchIn(url)

    /** 给界面展示的清晰度标签：有宽高就报宽高，没有就给「嗅探」 */
    fun qualityLabel(candidate: SniffCandidate): String =
        if (candidate.width > 0 && candidate.height > 0) "${candidate.width}x${candidate.height}" else "嗅探"

    private fun score(candidate: SniffCandidate): Int {
        val url = candidate.url
        var score = 0
        score += when {
            HLS_EXT.containsMatchIn(url) -> 160
            VIDEO_EXT.containsMatchIn(url) -> 200
            IMAGE_EXT.containsMatchIn(url) -> 80
            PLAY_HINT.containsMatchIn(url) -> 120
            else -> 0
        }
        if (PLAY_HINT.containsMatchIn(url)) score += 40
        if (candidate.source == "dom") score += 60 // 页面上真的挂出来的 <video>，可信度最高
        if (candidate.width > 0 && candidate.height > 0) {
            val area = candidate.width.toLong() * candidate.height.toLong()
            score += (area / 10_000L).coerceAtMost(120L).toInt()
        }
        if (NOISE_HINT.containsMatchIn(url)) score -= 200
        val small = (candidate.width in 1 until MIN_MAIN_SIZE) || (candidate.height in 1 until MIN_MAIN_SIZE)
        if (small) score -= 250
        return score
    }

    /**
     * 形态档位：视频文件 / DOM 里真实挂出的媒体 = 3，HLS 流与认不出后缀的流地址 = 2，图片 = 1。
     *
     * ★ 为什么档位要高于分数：面积分、路径语义分都是「同档内」的比较维度。跨档比较时，
     *   一张大封面图的分数很容易超过一个小 m3u8，而用户要的永远是能播的那条。
     */
    private fun tierOf(candidate: SniffCandidate): Int = when {
        candidate.source == "dom" && !IMAGE_EXT.containsMatchIn(candidate.url) -> 3
        VIDEO_EXT.containsMatchIn(candidate.url) -> 3
        IMAGE_EXT.containsMatchIn(candidate.url) -> 1
        else -> 2
    }

    /** 去重键：host + path，丢掉查询串与片段 */
    private fun dedupeKey(url: String): String {
        val withoutFragment = url.substringBefore('#')
        val queryStart = withoutFragment.indexOf('?')
        val noQuery = if (queryStart >= 0) withoutFragment.substring(0, queryStart) else withoutFragment
        return noQuery.removePrefix("https://").removePrefix("http://")
    }
}
