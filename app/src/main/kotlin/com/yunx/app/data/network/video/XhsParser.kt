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

import android.util.Log
import com.yunx.app.data.download.DownloadPathPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小红书笔记解析器（无水印版）。
 *
 * 不走私有 API，直接 GET 分享页，从 HTML 内嵌的 `window.__INITIAL_STATE__` 里取数据。
 *
 * ★★ 无水印的全部关键 = **只用 explore 页的 `urlDefault` / `masterUrl`，绝不用 discovery 页的
 *   `imageList[].url`**。实测（2026-11-10，10 篇笔记，无 Cookie 无 xsec_token 全命中）：
 *   - explore：`note.noteDetailMap[id].note.imageList[].urlDefault` → 原图，无水印；
 *   - explore：`video.media.stream.{h265,av1,h266,h264}[0].masterUrl` → 无水印
 *     （h264 的 streamType=258 是 web 播放流，下载抽帧验证过没有角标）；
 *   - discovery：`noteData.data.noteData.imageList[].url` **是带水印的分享版** ——
 *     干净变体在 `imageList[].urlSizeLarge`、或 `infoList[]` 里 `imageScene` 不含 `WM` 的条目
 *     （`WB_DFT` / `WB_PRV`），`normalNotePreloadData.imagesList[].urlSizeLarge` 只有一张。
 *
 * ★ CDN 地址**必须原样使用**：`...!nd_prv_wlteh_webp_3` 这个后缀是签名的一部分，
 *   删掉或者换成 `!nd_dft_...` 直接 403（实测）。
 *
 * ★ 需要登录：未登录时 explore 会 302 到 `/login`、discovery 到 `/website-login/captcha`，
 *   还有一条 `/404/sec_xxx` 的风控页；这三种都当「被墙」处理，给出可操作提示。
 *   分享链接里的 `xsec_token` / `xsec_source` 必须原样带上，缺了会落到 `/404/sec_xxx`。
 *
 * ★ 两种页面形态的内嵌 JSON 结构完全不同：
 *   - `/explore/<id>`：`note.noteDetailMap[<id>].note`（无 `time`，作者名 `nickname`）；
 *   - `/discovery/item/<id>`：`noteData.data.noteData`（有 `time`，作者名 `nickName`）。
 */
object XhsParser : VideoParser {

    override val platform: VideoPlatform = VideoPlatform.XHS

    private const val TAG = "YunX"

    /** 分享页请求头（桌面 UA 才会下发完整的 SSR 数据） */
    private const val ACCEPT =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8," +
            "application/signed-exchange;v=b3;q=0.7"

    private const val XHS_HOME = "https://www.xiaohongshu.com"

    /** 登录态探测地址：未登录访问首页会被 302 到 /login（与解析时同一道门，口径一致） */
    private const val EXPLORE_PROBE_URL = "$XHS_HOME/explore"

    private const val REFERER = "$XHS_HOME/"

    /**
     * 最终地址命中这些片段 = 被登录墙 / 风控拦下（不是作品被删）。
     *
     * ★ `/404/sec_` 是小红书的风控落地页（缺 xsec_token、IP 被限流时都会到这里），
     *   老版本没把它算进来，于是被报成「内容不存在」，把用户引到完全错误的方向。
     */
    private val WALL_MARKERS = listOf("/login", "website-login", "captcha", "/404/sec_")

    /** 视频流优先级：h265 / av1 / h266 是高码率源，h264 是 web 播放流（实测也无水印），放最后 */
    private val streamOrder = listOf(
        "h265" to "H.265",
        "av1" to "AV1",
        "h266" to "H.266",
        "h264" to "H.264"
    )

    /**
     * 登录态校验：**行为探测**，不按 Cookie 名字判断。
     *
     * ★ 踩过的坑：小红书网页端在 JS 启动后会自己种一个**游客态的 `web_session`**，
     *   `web_session` 只能说明「浏览器有过会话」，不能说明「用户已登录」。
     *   因此改成和解析同口径的探测：带 Cookie 请求 `/explore`，被 302 到登录页/风控页 = 未登录。
     */
    override suspend fun validateCookie(cookie: String): String? {
        if (cookie.isBlank()) return null
        val response = runCatching {
            VideoHttp.request(EXPLORE_PROBE_URL, headers = exploreHeaders(cookie))
        }.getOrElse { error ->
            Log.w(TAG, "小红书登录态探测失败（按未登录处理）：${error.message}")
            return null
        }
        if (WALL_MARKERS.any { response.finalUrl.contains(it) }) return null
        return ""
    }

    /**
     * 解析入口：先 explore（桌面 UA），失败再 discovery（iOS UA + XHR 头）。
     *
     * ★ 两条路径的差别不只是请求头，**取字段的规则也不一样**（见类文档）。
     *   explore 拿到的天然无水印，discovery 必须做字段挑选，所以顺序不能反。
     */
    override suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val query = queryOf(link)

        val explore = runCatching { parseExplorePage(link, query, cookie) }
        explore.getOrNull()?.let { return it }
        val exploreError = explore.exceptionOrNull()
        Log.w(TAG, "小红书 explore 失败，转 discovery：${exploreError?.message}")

        val discovery = runCatching { parseDiscoveryPage(link, query, cookie) }
        discovery.getOrNull()?.let { return it }
        val discoveryError = discovery.exceptionOrNull()
        Log.w(TAG, "小红书 discovery 也失败：${discoveryError?.message}")

        // 两条都被墙 → 报需要登录；两条都 404 → 报名不存在；其余按 unknown 抛
        val wallError = listOfNotNull(exploreError, discoveryError)
            .filterIsInstance<VideoParseException>()
            .firstOrNull { it.kind == VideoParseErrorKind.UNSUPPORTED }
        if (wallError != null) throw wallError

        throw discoveryError as? VideoParseException
            ?: exploreError as? VideoParseException
            ?: VideoParseException(VideoParseErrorKind.UNKNOWN, "小红书解析失败", discoveryError ?: exploreError)
    }

    /** explore 形态：`/explore/<id>?<原查询串>`（桌面 UA） */
    private suspend fun parseExplorePage(link: DetectedVideoLink, query: String, cookie: String): VideoParseResult {
        val html = fetchPage("$XHS_HOME/explore/${link.id}$query", exploreHeaders(cookie))
        val state = extractInitialState(html)
        val note = state.optJSONObject("note")
            ?.optJSONObject("noteDetailMap")
            ?.optJSONObject(link.id)
            ?.optJSONObject("note")
            ?: throw VideoParseException(VideoParseErrorKind.NOT_FOUND, "小红书页面里没有该笔记数据")
        return buildFromExplore(note, link, cookie)
    }

    /** discovery 形态：`/discovery/item/<id>?<原查询串>`（iOS UA + XHR 头） */
    private suspend fun parseDiscoveryPage(link: DetectedVideoLink, query: String, cookie: String): VideoParseResult {
        val html = fetchPage("$XHS_HOME/discovery/item/${link.id}$query", discoveryHeaders(cookie))
        val state = extractInitialState(html)
        val root = state.optJSONObject("noteData")
            ?: throw VideoParseException(VideoParseErrorKind.NOT_FOUND, "小红书分享链接失效或内容已删除")
        val note = root.optJSONObject("data")?.optJSONObject("noteData")
            ?: throw VideoParseException(VideoParseErrorKind.NOT_FOUND, "小红书页面里没有笔记数据（内容可能已删除）")
        // preload 里的图确定无水印，但只有一张：用来兜底第一张
        val preloadCover = firstCleanPreload(
            root.optJSONObject("normalNotePreloadData")?.optJSONArray("imagesList")
        )
        return buildFromDiscovery(note, link, cookie, preloadCover)
    }

    /**
     * 取分享页，并**先把「被墙」识别出来**，不要让它掉进「内容不存在」。
     */
    private suspend fun fetchPage(url: String, headers: Map<String, String>): String {
        val response = VideoHttp.request(url, headers = headers)
        if (WALL_MARKERS.any { response.finalUrl.contains(it) }) {
            throw VideoParseException(
                VideoParseErrorKind.UNSUPPORTED,
                if (response.finalUrl.contains("sec_"))
                    "小红书风控拦截了本次请求（缺 xsec_token 或请求过频），请稍后重试或用带 xsec_token 的分享链接"
                else
                    "小红书要求登录后才能查看笔记内容，请先到「账号」页登录小红书"
            )
        }
        VideoHttp.requireSuccess(response, "小红书笔记")
        return response.body
    }

    /**
     * 抠出 `window.__INITIAL_STATE__` 并解析成 JSON。
     *
     * ★ 这段 JSON 里混有裸的 `undefined`（JS 字面量，不是合法 JSON）。
     *   **不能**整体 `replace("undefined", "null")` —— 笔记正文里一旦出现 "undefined"
     *   这个单词就会被改坏，整包 JSON 解析失败（表现为「页面数据解析失败」）。
     *   这里只把「冒号后面的 undefined」当 null，其余原样保留。
     */
    private fun extractInitialState(html: String): JSONObject {
        val raw = VideoHttp.extractInlineJson(html, "window.__INITIAL_STATE__")
            ?: throw VideoParseException(
                VideoParseErrorKind.UNKNOWN,
                "小红书页面里没有笔记数据（可能被风控拦截或页面改版）"
            )
        val patched = Regex(""":\s*undefined\b""").replace(raw, ":null")
        return runCatching { JSONObject(patched) }.getOrElse {
            throw VideoParseException(VideoParseErrorKind.UNKNOWN, "小红书页面数据解析失败", it)
        }
    }

    /** 原分享链接的查询串（含 `xsec_token`）：小红书**必须**带上它，否则笔记页会被风控页拦下 */
    private fun queryOf(link: DetectedVideoLink): String =
        link.url.substringAfter('?', "")
            .split('&')
            .filter { it.isNotBlank() }
            .filter { it.substringBefore('=') in setOf("xsec_token", "xsec_source") }
            .joinToString("&")
            .let { if (it.isBlank()) "" else "?$it" }

    private fun exploreHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_CHROME)
        put("accept", ACCEPT)
        put("Referer", REFERER)
        put("Accept-Language", "zh-CN,zh;q=0.9")
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    private fun discoveryHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_IPHONE)
        put("accept", ACCEPT)
        put("Referer", REFERER)
        put("origin", XHS_HOME)
        put("x-requested-with", "XMLHttpRequest")
        put("sec-fetch-site", "same-origin")
        put("sec-fetch-mode", "cors")
        put("sec-fetch-dest", "empty")
        put("Accept-Language", "zh-CN,zh;q=0.9")
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    // ------------------------------------------------------------------ 组装结果

    private fun buildFromExplore(note: JSONObject, link: DetectedVideoLink, cookie: String): VideoParseResult {
        val mediaHeaders = mediaHeaders(cookie)
        val title = note.str("title")
        val base = fileBase(title, link)
        // explore 的图片字段是 urlDefault —— 原图，无水印
        val images = pickImages(note.optJSONArray("imageList"))
        return assemble(note, link, mediaHeaders, base, images, coverHint = null)
    }

    private fun buildFromDiscovery(
        note: JSONObject,
        link: DetectedVideoLink,
        cookie: String,
        coverHint: String?
    ): VideoParseResult {
        val mediaHeaders = mediaHeaders(cookie)
        val title = note.str("title")
        val base = fileBase(title, link)
        // ★ discovery 的 imageList[].url 带水印，必须逐张挑干净变体
        val images = pickImages(note.optJSONArray("imageList"))
        return assemble(note, link, mediaHeaders, base, images, coverHint)
    }

    /** 图片产物：url + 是否无水印 */
    private data class PickedImage(val url: String, val clean: Boolean)

    /**
     * 逐张挑无水印变体。优先级：
     *   1. `urlSizeLarge` / `urlSizeMiddle` / `urlSizeSmall` —— discovery 下的无压缩变体；
     *   2. `infoList[]` 里 `imageScene` **不含 `WM`** 的条目（`WB_DFT` / `WB_PRV`）；
     *   3. `urlDefault` / `urlPre` —— explore 正常就是这一档（无水印）；
     *   4. `url` —— 最后的兜底，标记 clean=false，界面上可以提示。
     */
    private fun pickImages(array: JSONArray?): List<PickedImage> {
        if (array == null) return emptyList()
        val out = mutableListOf<PickedImage>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val picked = pickImage(item)
            if (picked.url.isNotBlank()) out += picked
        }
        return out
    }

    private fun pickImage(item: JSONObject): PickedImage {
        for (k in listOf("urlSizeLarge", "urlSizeMiddle", "urlSizeSmall")) {
            val u = item.str(k)
            if (u.isNotBlank()) return PickedImage(u, true)
        }
        val info = item.optJSONArray("infoList")
        if (info != null) {
            for (j in 0 until info.length()) {
                val e = info.optJSONObject(j) ?: continue
                val scene = e.str("imageScene").uppercase()
                val u = e.str("url")
                if (u.isNotBlank() && !scene.contains("WM")) return PickedImage(u, true)
            }
        }
        for (k in listOf("urlDefault", "urlPre", "url")) {
            val u = item.str(k)
            if (u.isNotBlank()) return PickedImage(u, false)
        }
        return PickedImage("", false)
    }

    private fun firstCleanPreload(array: JSONArray?): String? {
        if (array == null) return null
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val u = o.str("urlSizeLarge").ifBlank { o.str("url") }
            if (u.isNotBlank()) return u
        }
        return null
    }

    /**
     * 把 note 组装成 [VideoParseResult]（explore / discovery 共用）。
     *
     * ★ 判断「是不是视频笔记」用**有没有 `video` 对象**，不用 `type == "video"`：
     *   `type` 在不同路径/版本下取值不一致，判错会静默退化成只解析封面图。
     */
    private fun assemble(
        note: JSONObject,
        link: DetectedVideoLink,
        mediaHeaders: Map<String, String>,
        base: String,
        images: List<PickedImage>,
        coverHint: String?
    ): VideoParseResult {
        // preload 只有一张，但确定无水印：兜底第一张
        val fixed = images.toMutableList()
        if (coverHint != null) {
            if (fixed.isEmpty()) fixed += PickedImage(coverHint, true)
            else if (!fixed[0].clean) fixed[0] = PickedImage(coverHint, true)
        }

        val video = pickVideo(note)
        val user = note.optJSONObject("user")
        val title = note.str("title")
        val medias = mutableListOf<VideoMedia>()
        var cover = ""

        if (video != null) {
            cover = fixed.firstOrNull { it.clean }?.url ?: fixed.firstOrNull()?.url.orEmpty()
            medias += VideoMedia(
                url = video.first,
                kind = VideoMediaKind.VIDEO,
                fileName = "$base.mp4",
                headers = mediaHeaders,
                quality = video.second
            )
        } else if (note.optJSONObject("video") != null) {
            // 是视频笔记却没取到直链：明确报错，不要退回成图片（比报错更误导用户）
            throw VideoParseException(
                VideoParseErrorKind.UNKNOWN,
                "小红书视频笔记没有取到视频直链（有 video 字段，但 media.stream 里没有可用地址）"
            )
        } else {
            fixed.forEachIndexed { index, img ->
                medias += VideoMedia(
                    url = img.url,
                    kind = VideoMediaKind.IMAGE,
                    fileName = "${base}_${index + 1}.jpg",
                    headers = mediaHeaders,
                    quality = if (img.clean) "原图" else "原图（水印路径）"
                )
            }
            cover = fixed.firstOrNull()?.url.orEmpty()
        }

        return VideoParseResult(
            platform = platform,
            title = title,
            // discovery 形态的昵称字段是大写 N 的 nickName
            author = user?.str("nickname").orEmpty().ifBlank { user?.str("nickName").orEmpty() },
            avatar = user?.str("avatar").orEmpty().ifBlank { user?.str("image").orEmpty() },
            cover = cover,
            text = note.str("desc"),
            // note.time 是毫秒；discovery 才有
            timestampSeconds = note.optLong("time", 0L).let { if (it > 1_000_000_000_000L) it / 1000 else it },
            url = link.url,
            medias = medias
        )
    }

    /**
     * 取视频直链：`<note>.video.media.stream` 按 h265 → av1 → h266 → h264 取第一条 `masterUrl`。
     *
     * ★ 兜底一路 `originVideoKey`：`video.consumer.originVideoKey` 是上传原片的 key，
     *   拼 `https://sns-video-bd.xhscdn.com/<key>` 可拿到最高画质原片（不一定有，有就优先）。
     */
    private fun pickVideo(note: JSONObject): Pair<String, String>? {
        val video = note.optJSONObject("video") ?: return null
        val stream = video.optJSONObject("media")?.optJSONObject("stream")

        if (stream != null) {
            for ((key, label) in streamOrder) {
                val arr = stream.optJSONArray(key) ?: continue
                if (arr.length() == 0) continue
                val first = arr.optJSONObject(0) ?: continue
                // 字段名历史上出现过两种写法，都认
                val url = first.str("masterUrl").ifBlank { first.str("master_url") }
                if (url.isNotBlank()) return url to label
            }
        }

        val originKey = video.optJSONObject("consumer")?.str("originVideoKey").orEmpty()
        if (originKey.isNotBlank()) return "https://sns-video-bd.xhscdn.com/$originKey" to "原片"

        return null
    }

    private fun mediaHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_CHROME)
        put("Referer", REFERER)
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /** 文件名主干：清洗非法字符并截断；标题为空时兜底为「平台_id」 */
    private fun fileBase(title: String, link: DetectedVideoLink): String {
        val cleaned = DownloadPathPolicy.sanitizeName(title).take(80)
        return cleaned.ifBlank { "${platform.displayName}_${link.id}" }
    }
}

/**
 * `JSONObject.optString` 遇到 JSON 里的显式 `null` 会返回字符串 "null"
 * （Android org.json 的实现是 `String.valueOf(JSONObject.NULL)`），直接当字段值用会把 "null"
 * 当成有效内容，这里统一过滤；同时把空白也当缺失。
 */
private fun JSONObject.str(key: String): String {
    if (!has(key) || isNull(key)) return ""
    return optString(key, "").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
}
