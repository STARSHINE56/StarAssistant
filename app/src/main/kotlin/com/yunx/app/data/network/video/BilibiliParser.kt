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

import com.yunx.app.data.download.DownloadPathPolicy
import com.yunx.app.util.CookieHeader
import com.yunx.app.util.DiagnosticLog
import org.json.JSONObject

/**
 * 哔哩哔哩视频解析：**单文件低清**（`fnval=1` 的 durl 直链）。
 *
 * 数据来源（都是公开的 web 接口，未登录也能用）：
 * 1. `x/web-interface/view` 取 bvid/aid/cid/标题/作者/封面/时长；
 * 2. `x/player/playurl?fnval=1&platform=html5&high_quality=1` 取**单文件** `durl[0]`。
 *
 * ★ 为什么刻意用 `fnval=1` 而不是默认的 DASH：DASH 是音视频**分离**的两路流，需要 ffmpeg 或
 *   自研 mux 才能合并成一个可播放文件，Android 侧没有这个能力（参考实现靠 ffmpeg）。
 *   `fnval=1` 换来的是「能直接下载播放」的单个 mp4/flv，代价是清晰度偏低（未登录尤其明显）。
 *   DASH 合并留作 TODO：等有了 Android 侧的 mux 方案再开高清。
 *
 * ★ 已知限制：
 * - 只解析**第一个分P**（多 P 视频后续可按 `pages[]` 展开）；
 * - 番剧/课程（`bangumi/play`、`cheese/play`）不在识别范围内，需要完全不同的接口与登录态；
 * - 若 B 站把 `playurl` 也改成必须 WBI 签名，此接口需要换成带签名版本（见 Agent.md 的 TODO）。
 */
object BilibiliParser : VideoParser {

    override val platform: VideoPlatform = VideoPlatform.BILIBILI

    private const val VIEW_URL = "https://api.bilibili.com/x/web-interface/view"
    private const val PLAY_URL = "https://api.bilibili.com/x/player/playurl"
    private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"
    /** 720P 的 qn 值：DASH 达到这一档才值得为它引入合轨链路 */
    private const val QN_720P = 64

    /** 带 WBI 签名的播放地址：接口收紧后只有它能稳定拿到 DASH 高清轨 */
    private const val WBI_PLAY_URL = "https://api.bilibili.com/x/player/wbi/playurl"

    /** 页面/接口请求头：Referer 必须是 bilibili.com，否则会被风控挡 */
    private fun apiHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_CHROME)
        put("Referer", "https://www.bilibili.com/")
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /**
     * 下载直链的请求头。
     *
     * ★ 必须带 `Referer`：B 站 CDN（upos-*.bilivideo.com）会校验来源，缺了直接 403；
     *   带 Cookie 则是为了让登录用户拿到更高清晰度（未登录会退到最低档）。
     */
    private fun mediaHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_CHROME)
        put("Referer", "https://www.bilibili.com/")
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /** 登录态校验：`nav` 接口顺带返回用户名，可以直接作为昵称存下来 */
    override suspend fun validateCookie(cookie: String): String? {
        if (cookie.isBlank()) return null

        // ① 关键字段先过一遍：没有 SESSDATA 就一定是"网页里还没登录成功"，
        //    这时候去请求 nav 是白挨一次风控，也不该把原因藏在网络层
        val hasSession = CookieHeader.hasKey(cookie, "SESSDATA")
        val json = runCatching { VideoHttp.getJson(NAV_URL, apiHeaders(cookie), "B站登录态校验") }
            .onFailure { error ->
                // 以前这里用 runCatching{}.getOrNull() 静默吞掉，界面只能笼统报「未检测到登录态」
                DiagnosticLog.warn(
                    DiagnosticLog.NETWORK, "bili_nav_failed",
                    "hasSess=$hasSession err=${error.javaClass.simpleName}:${error.message}"
                )
            }
            .getOrNull()

        val data = json?.optJSONObject("data")
        if (data != null && data.optInt("isLogin") == 1) {
            return data.optString("uname")
        }

        val code = json?.optInt("code") ?: 0
        DiagnosticLog.warn(
            DiagnosticLog.NETWORK, "bili_nav_not_login",
            "code=$code hasSess=$hasSession"
        )

        // ② 三件套齐全就按「登录态」接受，**-101 也不例外**。
        //    ⚠️ 踩过的坑：以前把 -101 当成「nav 亲口说没登录」，直接 return null ——
        //    实测同一份三件套 Cookie 换个出口 IP 请求 nav / space/myinfo，两个接口都回 -101
        //    （不是 -352），说明 B 站会用「未登录」这个笼统理由拒绝**异地/指纹不符**的会话。
        //    把它当铁证，结果就是用户在网页里明明登录成功，却永远存不上（正是这次报的现象）。
        //    现在的取舍：先存下来让他能用；真失效时解析接口会立刻报错，那比卡死在登录页强。
        if (hasSession && CookieHeader.hasKey(cookie, "bili_jct") && CookieHeader.hasKey(cookie, "DedeUserID")) {
            DiagnosticLog.warn(
                DiagnosticLog.NETWORK, "bili_nav_fallback_accepted",
                "code=$code（校验被挡，按 Cookie 形态接受登录态）"
            )
            return ""
        }
        return null
    }

    override suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val viewUrl = if (link.id.startsWith("BV", ignoreCase = true)) {
            "$VIEW_URL?bvid=${link.id}"
        } else {
            "$VIEW_URL?aid=${link.id.removePrefix("av")}"
        }
        val view = VideoHttp.getJson(viewUrl, apiHeaders(cookie), "B站视频信息")
        if (view.optInt("code") != 0) {
            throw VideoParseException(
                VideoParseErrorKind.NOT_FOUND,
                "B站视频信息接口返回 code=${view.optInt("code")} msg=${view.optString("message")}"
            )
        }
        val data = view.optJSONObject("data")
            ?: throw VideoParseException(VideoParseErrorKind.UNKNOWN, "B站视频信息缺少 data 字段")

        val bvid = data.optString("bvid")
        val cid = data.optJSONArray("pages")?.optJSONObject(0)?.optLong("cid") ?: data.optLong("cid")
        if (bvid.isBlank() || cid <= 0L) {
            throw VideoParseException(VideoParseErrorKind.UNKNOWN, "B站视频信息缺少 bvid/cid")
        }

        val title = data.optString("title")
        val fileBase = fileNameBase(title, "bilibili_$bvid")

        // 策略：**优先给用户账号实际能拿到的最高画质**，而不是「有 DASH 就上 DASH」。
        //
        // ① 已登录才试 DASH：它是音视频分离的两路流，下载时由 mp4parser 合轨（见 DashMuxer），
        //    代价是链路更长、预览只有画面没声音。所以只有它确实更高时才值得走。
        //    ★ 未登录不试：匿名能拿到的 DASH 只有 480P，比单文件的 720P 还低。
        val dash = if (cookie.isNotBlank()) dashQuality(bvid, cid, cookie) else null
        DiagnosticLog.warn(
            DiagnosticLog.NETWORK, "bili_quality_probe",
            "bvid=$bvid dashQn=${dash?.quality ?: -1} loggedIn=${cookie.isNotBlank()}"
        )
        // DASH 已经到 720P 及以上：直接用它（这就是账号权限允许的高档了）
        if (dash != null && dash.quality >= QN_720P) {
            return buildDashResult(data, bvid, fileBase, cookie, dash)
        }

        // ② 单文件 durl（未登录 / DASH 拿不到 / DASH 档位反而更低时都要问一次）
        val play = VideoHttp.getJson(
            "$PLAY_URL?bvid=$bvid&cid=$cid&qn=80&fnval=1&fnver=0&fourk=1&platform=html5&high_quality=1",
            apiHeaders(cookie),
            "B站取直链"
        )
        val playData = play.optJSONObject("data")
        val durl = playData?.optJSONArray("durl")
        if (durl == null || durl.length() == 0) {
            // 单文件没有（番剧/付费内容常见），但分轨拿到了 → 用分轨，别让用户白等一场
            if (dash != null) return buildDashResult(data, bvid, fileBase, cookie, dash)
            throw VideoParseException(
                VideoParseErrorKind.UNSUPPORTED,
                "B站既未返回单文件直链、也未返回可合轨的分轨流" +
                    "（code=${play.optInt("code")} msg=${play.optString("message")}）：该视频可能是番剧/付费内容"
            )
        }
        val first = durl.optJSONObject(0)
            ?: throw VideoParseException(VideoParseErrorKind.UNKNOWN, "B站 durl 为空")
        val url = first.optString("url")
        if (url.isBlank()) {
            throw VideoParseException(VideoParseErrorKind.BLOCKED, "B站直链为空（可能需要登录或已被风控）")
        }

        // ③ 两条路都拿到了就比一比：版权受限的视频，DASH 只给 480P 而单文件能给 720P，
        //    这时选单文件才是「用户账号能达到的最高画质」（且省掉一次合轨）
        val singleQn = playData.optInt("quality")
        if (dash != null && dash.quality > singleQn) {
            return buildDashResult(data, bvid, fileBase, cookie, dash)
        }

        return buildResult(
            data = data,
            bvid = bvid,
            media = VideoMedia(
                url = httpsUrl(url),
                kind = VideoMediaKind.VIDEO,
                fileName = "$fileBase.mp4",
                size = first.optLong("size", -1L),
                headers = mediaHeaders(cookie),
                quality = qualityLabel(singleQn)
            )
        )
    }

    /** DASH 分轨结果的组装（视频轨 + 音轨 + 合轨提示），两条分支共用 */
    private fun buildDashResult(
        data: JSONObject,
        bvid: String,
        fileBase: String,
        cookie: String,
        dash: DashStream
    ): VideoParseResult = buildResult(
        data = data,
        bvid = bvid,
        media = VideoMedia(
            url = dash.videoUrl,
            kind = VideoMediaKind.VIDEO,
            fileName = "$fileBase.mp4",
            headers = mediaHeaders(cookie),
            quality = "${qualityLabel(dash.quality)}（分轨，下载后合轨）",
            audioUrl = dash.audioUrl,
            audioHeaders = mediaHeaders(cookie)
        )
    )

    /** 组装解析结果（DASH 与单文件两条路径共用，避免字段各写一遍） */
    private fun buildResult(data: JSONObject, bvid: String, media: VideoMedia): VideoParseResult =
        VideoParseResult(
            platform = platform,
            title = data.optString("title"),
            author = data.optJSONObject("owner")?.optString("name").orEmpty(),
            avatar = data.optJSONObject("owner")?.optString("face").orEmpty(),
            cover = httpsUrl(data.optString("pic")),
            text = data.optString("desc"),
            timestampSeconds = data.optLong("pubdate"),
            url = "https://www.bilibili.com/video/$bvid",
            medias = listOf(media)
        )

    /** DASH 分轨直链（视频轨 + 音轨 + 清晰度编号） */
    private data class DashStream(val videoUrl: String, val audioUrl: String, val quality: Int)

    /**
     * 取 DASH 分轨直链：视频轨取清晰度最高的一路、音轨取码率最高的一路。
     *
     * ★ `fnval=4048` 是「DASH + HDR + 4K + 杜比」的组合位；`qn=127` 表示「能给多高给多高」
     *   （B 站把 127 定义为 8K 档，超出权限时服务端会自动降到账号允许的最高档，不会报错）。
     *   **实际返回哪一档由账号权限决定**：未登录 480P、登录 1080P、大会员才有 4K/HDR/杜比。
     *   这不是客户端能绕的 —— 服务端不给流就是不给。
     *
     * ★ 走 WBI 签名的 `x/player/wbi/playurl`（见 [BiliWbi]）：不签名的老接口目前还能用，
     *   但 B 站一直在往「web 接口全签名」收，签名是为了不被静默降级。
     *
     * 任何一步不对（没有 dash 字段、两轨缺一）都返回 null，由调用方退回单文件路径 ——
     * 宁可低清可用，也不要在这里抛错。
     */
    private suspend fun dashQuality(bvid: String, cid: Long, cookie: String): DashStream? {
        val url = wbiPlayUrl(bvid, cid)
        val json = runCatching {
            VideoHttp.getJson(url, apiHeaders(cookie), "B站取 DASH 直链")
        }.getOrNull() ?: return null
        val dash = json.optJSONObject("data")?.optJSONObject("dash") ?: return null
        val videos = dash.optJSONArray("video") ?: return null
        val audios = dash.optJSONArray("audio") ?: return null

        // 数组通常已按清晰度降序，但显式比较更稳（不同端返回顺序不一致过）
        val bestVideo = (0 until videos.length())
            .mapNotNull { videos.optJSONObject(it) }
            .maxByOrNull { it.optInt("id") } ?: return null
        val bestAudio = (0 until audios.length())
            .mapNotNull { audios.optJSONObject(it) }
            .maxByOrNull { it.optInt("id") } ?: return null

        // 直链字段名有两种（baseUrl / base_url），且可能被下发成 JSON null（optString 会得到 "null"）
        val videoUrl = bestVideo.dashUrl() ?: return null
        val audioUrl = bestAudio.dashUrl() ?: return null
        return DashStream(videoUrl = videoUrl, audioUrl = audioUrl, quality = bestVideo.optInt("id"))
    }

    /**
     * 拼出带 WBI 签名的 DASH 播放地址；拿不到 `mixinKey` 时退回不签名的地址。
     *
     * ★ 取不到密钥不算失败：签名的意义是「防未来收紧」，当前不签名也能取到同一档画质，
     *   为此把整条解析路径搞挂是本末倒置。
     */
    private suspend fun wbiPlayUrl(bvid: String, cid: Long): String {
        val params = mapOf(
            "bvid" to bvid,
            "cid" to cid.toString(),
            "fnval" to "4048",
            "fnver" to "0",
            "fourk" to "1",
            "qn" to "127"
        )
        val mixin = wbiMixinKey()
        return if (mixin.isBlank()) {
            "$PLAY_URL?bvid=$bvid&cid=$cid&qn=127&fnval=4048&fnver=0&fourk=1"
        } else {
            BiliWbi.url(WBI_PLAY_URL, params, mixin)
        }
    }

    /** `wbi_img` 里的两个密钥（缓存一小时；B 站每天换，缓存太短会白打一堆 nav） */
    @Volatile
    private var wbiMixin: String = ""
    @Volatile
    private var wbiExpireAt: Long = 0L

    private suspend fun wbiMixinKey(): String {
        val cached = wbiMixin
        if (cached.isNotBlank() && System.currentTimeMillis() < wbiExpireAt) return cached
        val json = runCatching {
            VideoHttp.getJson(NAV_URL, apiHeaders(""), "B站 WBI 密钥")
        }.getOrNull() ?: return cached
        val wbiImg = json.optJSONObject("data")?.optJSONObject("wbi_img") ?: return cached
        val imgKey = wbiImg.optString("img_url").substringAfterLast('/').substringBefore('.')
        val subKey = wbiImg.optString("sub_url").substringAfterLast('/').substringBefore('.')
        val mixin = BiliWbi.mixinKey(imgKey, subKey)
        if (mixin.isBlank()) return cached
        wbiMixin = mixin
        wbiExpireAt = System.currentTimeMillis() + 3_600_000L
        return mixin
    }

    private fun JSONObject.dashUrl(): String? =
        (optString("baseUrl").takeIf { it.isNotBlank() } ?: optString("base_url"))
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { httpsUrl(it) }

    /** B站清晰度编号 → 展示文案（只列常见的几档，其余原样显示编号） */
    private fun qualityLabel(qn: Int): String = when (qn) {
        6, 7 -> "240P"
        16 -> "360P"
        32 -> "480P"
        64 -> "720P"
        74 -> "720P60"
        80 -> "1080P"
        112 -> "1080P+"
        116 -> "1080P60"
        120 -> "4K"
        125 -> "HDR"
        126 -> "杜比视界"
        127 -> "8K"
        else -> if (qn > 0) "画质 $qn" else ""
    }

    /** 接口偶尔给 `//` 开头的协议相对地址 */
    private fun httpsUrl(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        else -> url
    }

    /** 文件名：标题清洗后截断，标题为空时用兜底名 */
    private fun fileNameBase(title: String, fallback: String): String {
        val cleaned = DownloadPathPolicy.sanitizeName(title).take(80)
        return cleaned.ifBlank { fallback }
    }
}
