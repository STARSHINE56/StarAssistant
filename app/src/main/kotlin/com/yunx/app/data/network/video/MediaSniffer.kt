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

import android.webkit.CookieManager
import com.yunx.app.util.DiagnosticLog
import com.yunx.app.util.LogRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通用「页面嗅探」通道：**不管平台给了什么接口，直接把页面在 WebView 里跑一遍，
 * 拦下它自己发出的媒体请求。**
 *
 * ★ 为什么需要它：解析器的第一梯队（官方接口 / 分享页 SSR）拼的是「能不能拿到结构化数据」。
 *   一旦平台改版或收紧风控（HTTP 403、SSR 里不再塞数据、接口要求签名），这条路就整条断掉，
 *   但页面本身**一定还能播**——只要浏览器能播，媒体地址就一定在网络层出现过。这一层不看接口、
 *   不看结构，只认「谁在下载视频」，所以它是所有平台共用的最后一道防线。
 *
 * ★ 代价：
 *   - 只能拿到媒体地址，拿不到标题/作者/正文（页面 DOM 里有，但各平台结构完全不一样，
 *     为了通用性这里不解析 DOM，宁可少点元数据也要保证能拿到片子）；
 *   - 慢（要真跑一遍页面，秒级），所以只在 [VideoParserGateway] 的兜底位次调用。
 *
 * ★ 与抖音自己的 WebView 通道（`DouyinParser.fetchViaWebView`）的关系：那条路要过
 *   `byted_acrawler` 挑战、还要钩页面内部的详情 XHR，是**定制**的，效果比这里的通用嗅探好，
 *   所以抖音不在这里的 [SUPPORTED] 名单里 —— 由它自己兜底，避免同一平台挂两个 WebView。
 *
 * ★ Cookie：走系统的 [CookieManager]（与登录页同一份存储），所以用户登录过的平台，
 *   嗅探时天然带登录态；[cookie] 参数用于把账号页保存的那份 Cookie 再补一遍
 *   （账号页读的是数据库，不一定与 WebView 的 CookieStore 同步）。
 */
internal object MediaSniffer {

    /** 一次嗅探最多考虑多少条候选（拦住页面里无穷无尽的表情包/埋点图） */
    private const val MAX_CANDIDATES = 200

    /** 图片最多保留多少张（图集类作品的常见上限留点余量） */
    private const val MAX_IMAGES = 30

    /** 视频最多保留多少条（多清晰度/多码率时给用户挑） */
    private const val MAX_VIDEOS = 6

    /**
     * 支持通用嗅探的平台。
     *
     * ★ 抖音不在名单里：它有自己的定制 WebView 通道，通用嗅探反而更弱（见类注释）。
     *   哔哩哔哩/Pixiv 也不在：前者是 DASH 分离流（嗅探拿到的是音视频两段，拼不回一个文件），
     *   后者干脆没有「播放」这一步，图片地址全在 JSON 接口里。
     */
    private val SUPPORTED = setOf(
        VideoPlatform.KUAISHOU,
        VideoPlatform.XHS,
        VideoPlatform.WEIBO,
        VideoPlatform.ACFUN
    )

    fun supports(platform: VideoPlatform): Boolean = platform in SUPPORTED

    /**
     * 把 [link] 对应的页面跑一遍，收集媒体地址。
     *
     * @return 拼出来的解析结果；页面没跑起来、或一条媒体都没拦到时返回 null（让上层抛原始失败原因）
     */
    suspend fun sniff(link: DetectedVideoLink, cookie: String, extraHeaders: Map<String, String>): VideoParseResult? {
        val pageUrl = link.url.takeIf { it.startsWith("http") } ?: return null
        installCookie(pageUrl, cookie)
        val started = System.currentTimeMillis()
        val json = runCatching {
            HeadlessWebView.loadAndExtract(
                url = pageUrl,
                userAgent = userAgentOf(link.platform),
                readyCheckJs = READY_JS,
                extractJs = EXTRACT_JS,
                // ★ 15s 而不是 30s：嗅探是**兜底**，用户此时已经在等一次失败。
                //   兜底再花 30 秒，总时长会到 35s 上下，体感就是「点了解析没反应」。
                //   15s 足够多数分享页加载完首屏（真机上 3~8s 就有结果），
                //   等不到就老实报错，把原因交给上层提示。
                timeoutMs = 15_000L,
                extraDocumentStartJs = listOf(HOOK_JS)
            )
        }.getOrNull()
        if (json.isNullOrBlank()) {
            DiagnosticLog.warn(
                DiagnosticLog.NETWORK, "sniff_no_result",
                "platform=${link.platform.name} costMs=${System.currentTimeMillis() - started}"
            )
            return null
        }
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val title = obj.optString("title").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        val items = obj.optJSONArray("items") ?: JSONArray()
        val candidates = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url")
            if (url.isBlank()) return@mapNotNull null
            SniffCandidate(
                url = url,
                source = item.optString("src"),
                width = item.optInt("w"),
                height = item.optInt("h")
            )
        }
        val ranked = SniffRanker.rank(candidates, MAX_CANDIDATES)
        val videos = ranked.filter { SniffRanker.kindOf(it.url) == VideoMediaKind.VIDEO }.take(MAX_VIDEOS)
        val images = ranked.filter { SniffRanker.kindOf(it.url) == VideoMediaKind.IMAGE }.take(MAX_IMAGES)
        // 有视频就只给视频：视频作品的封面/帧图混进图集会让人以为能存一堆图
        val picked = if (videos.isNotEmpty()) videos else images
        if (picked.isEmpty()) {
            DiagnosticLog.warn(
                DiagnosticLog.NETWORK, "sniff_empty",
                "platform=${link.platform.name} candidates=${candidates.size}"
            )
            return null
        }
        val referer = originOf(pageUrl)
        val headers = buildMap {
            putAll(extraHeaders)
            put("User-Agent", userAgentOf(link.platform))
            put("Referer", referer)
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        val base = title.ifBlank { "${link.platform.name.lowercase()}_${System.currentTimeMillis()}" }
        val medias = picked.mapIndexed { index, candidate ->
            val kind = SniffRanker.kindOf(candidate.url)
            VideoMedia(
                url = candidate.url,
                kind = kind,
                fileName = "${sanitize(base)}_${index + 1}.${extensionOf(candidate.url, kind)}",
                isHls = SniffRanker.isHls(candidate.url),
                headers = headers,
                quality = SniffRanker.qualityLabel(candidate)
            )
        }
        DiagnosticLog.warn(
            DiagnosticLog.NETWORK, "sniff_hit",
            "platform=${link.platform.name} videos=${videos.size} images=${images.size} " +
                "costMs=${System.currentTimeMillis() - started} page=${LogRedactor.url(pageUrl)}"
        )
        return VideoParseResult(
            platform = link.platform,
            title = title,
            url = pageUrl,
            medias = medias
        )
    }

    /**
     * 把账号页保存的 Cookie 灌进 WebView 的 CookieStore。
     *
     * ★ 必须在 `loadAndExtract` **之前**（也就是主线程、页面开始加载之前）做完，
     *   否则第一次请求已经带着空 Cookie 出去了，登录态就白带了。
     */
    private suspend fun installCookie(pageUrl: String, cookie: String) = withContext(Dispatchers.Main) {
        if (cookie.isBlank()) return@withContext
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return@withContext
        runCatching { manager.setAcceptCookie(true) }
        cookie.split(';').map(String::trim).filter(String::isNotEmpty).forEach { pair ->
            runCatching { manager.setCookie(pageUrl, pair) }
        }
    }

    private fun userAgentOf(platform: VideoPlatform): String = when (platform) {
        // 快手/微博的分享页在移动端 UA 下才是「能播的那一版」；小红书要桌面 UA 才给完整数据
        VideoPlatform.KUAISHOU, VideoPlatform.WEIBO, VideoPlatform.ACFUN -> VideoHttp.UA_IPHONE
        else -> VideoHttp.UA_CHROME
    }

    private fun originOf(url: String): String = runCatching {
        val uri = java.net.URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(url)

    private fun extensionOf(url: String, kind: VideoMediaKind): String {
        val clean = url.substringBefore('#').substringBefore('?')
        val ext = clean.substringAfterLast('.', "").lowercase()
        if (ext in setOf("mp4", "flv", "mov", "webm", "m4v", "m3u8", "jpg", "jpeg", "png", "webp")) return ext
        return if (kind == VideoMediaKind.IMAGE) "jpg" else "mp4"
    }

    /** 文件名里不能放路径分隔符（标题里常有 `/`），顺手截断避免超出文件系统上限 */
    private fun sanitize(name: String): String =
        name.replace(Regex("""[/\\:*?"<>|\s]+"""), "_").trim('_').take(60).ifBlank { "media" }

    // ---------------------------------------------------------------- 注入脚本

    /**
     * **文档开始前**注入的钩子：拦 `fetch` / `XHR` / `<video>`，把媒体地址堆进 `window.__yunxSniff`。
     *
     * ★ 必须 documentStart：页面自己的请求在 `onPageStarted` 之后立刻发出，晚一步挂钩子就漏了。
     * ★ 对响应体做正则扫描而不是解析 JSON：各平台的数据结构完全不同，但「响应里出现一个
     *   `.mp4`/`.m3u8` 地址」这件事是共通的，正则在通用性上远胜字段解析。
     * ★ 全程 try/catch：钩子挂在页面的执行环境里，任何异常都可能把页面自己的脚本搞挂，
     *   那比嗅不到更糟（页面会白屏）。
     */
    private const val HOOK_JS = """
(function () {
  if (window.__yunxSniff) return;
  var out = window.__yunxSniff = [];
  var seen = Object.create(null);
  var MEDIA = /https?:\/\/[^\s"'<>\\]+?\.(?:mp4|m3u8|flv|mov|webm)(?:\?[^\s"'<>\\]*)?/gi;
  var IMAGE = /https?:\/\/[^\s"'<>\\]+?\.(?:jpe?g|png|webp)(?:\?[^\s"'<>\\]{0,300})?/gi;

  function add(url, source, w, h) {
    try {
      if (!url || typeof url !== 'string') return;
      if (url.indexOf('data:') === 0 || url.indexOf('blob:') === 0) return;
      if (url.indexOf('http') !== 0) return;
      if (seen[url]) return;
      seen[url] = 1;
      out.push({ url: url, src: source || '', w: w || 0, h: h || 0 });
      if (out.length > 300) out.shift();
    } catch (e) {}
  }

  function scan(text, source, wantImages) {
    try {
      if (!text || typeof text !== 'string' || text.length > 4000000) return;
      var m = text.match(MEDIA);
      if (m) { for (var i = 0; i < m.length; i++) add(m[i], source, 0, 0); }
      if (wantImages) {
        var g = text.match(IMAGE);
        if (g) { for (var j = 0; j < g.length; j++) add(g[j], source, 0, 0); }
      }
    } catch (e) {}
  }

  try {
    var originalFetch = window.fetch;
    if (originalFetch) {
      window.fetch = function () {
        var promise = originalFetch.apply(this, arguments);
        try {
          promise.then(function (response) {
            try {
              response.clone().text().then(function (t) { scan(t, 'fetch', true); });
            } catch (e) {}
          });
        } catch (e) {}
        return promise;
      };
    }
  } catch (e) {}

  try {
    var originalOpen = XMLHttpRequest.prototype.open;
    var originalSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function () {
      try { this.__yunxUrl = arguments[1]; } catch (e) {}
      return originalOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
      var xhr = this;
      xhr.addEventListener('load', function () {
        try { scan(xhr.responseText, 'xhr', true); } catch (e) {}
      });
      return originalSend.apply(this, arguments);
    };
  } catch (e) {}

  function scanDom() {
    try {
      var nodes = document.querySelectorAll('video, video source, audio source');
      for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        add(node.currentSrc || node.src, 'dom', node.videoWidth || 0, node.videoHeight || 0);
      }
    } catch (e) {}
  }
  try {
    setInterval(scanDom, 800);
    document.addEventListener('DOMContentLoaded', scanDom, true);
    document.addEventListener('click', function () { setTimeout(scanDom, 600); }, true);
  } catch (e) {}
})();
"""

    /**
     * 就绪判据：只要已经拦到**一条视频地址**就算成。
     *
     * ★ 不能等「所有请求都结束」——页面是无限滚动的，永远不会有那一刻。
     * ★ 也不能一有图片就返回：很多页面先加载头像/封面，那时候视频请求还没发出去，
     *   过早返回会拿一堆图去交差（这正是「嗅探只能拿到图片」的典型翻车方式）。
     *   所以图片这条路要同时满足「≥2 张」和「页面已跑满 6 秒」才放行。
     */
    private const val READY_JS = """
(function () {
  try {
    var list = window.__yunxSniff || [];
    var images = 0;
    for (var i = 0; i < list.length; i++) {
      var url = list[i].url || '';
      if (/\.(mp4|m3u8|flv|mov|webm)/i.test(url)) return true;
      if (/\.(jpe?g|png|webp)/i.test(url)) images++;
    }
    // ★ 图集类作品（小红书图文、微博九宫格）**根本没有 <video>**：
    //   只等视频就必然等到超时，然后白等 15 秒返回 null。
    //   给「图片已经攒够 + 页面已稳定 6 秒」也开一个出口。
    if (images >= 2 && performance.now() > 6000) return true;
  } catch (e) {}
  return false;
})();
"""

    /** 取回：标题 + 候选列表（`evaluateJavascript` 会把对象序列化成 JSON 文本） */
    private const val EXTRACT_JS = """
(function () {
  try {
    return {
      title: (document.title || '').trim(),
      items: (window.__yunxSniff || []).slice(0, 300)
    };
  } catch (e) {
    return { title: '', items: [] };
  }
})();
"""
}
