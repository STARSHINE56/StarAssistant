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

package com.yunx.app.data.announcement

import android.util.Log
import com.yunx.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Official COS JSON and optional compatible API sources share the existing models and UI.
 * JSON is data only: actions are ignored and Markdown is rendered without WebView scripts.
 */
object AnnouncementApi {

    private var settings: com.yunx.app.data.prefs.SettingsRepository? = null
    private var cachePrefs: android.content.SharedPreferences? = null
    private val feedMutex = kotlinx.coroutines.sync.Mutex()
    val baseUrl: String get() = AnnouncementSource.effective(settings?.announcementBaseUrl.orEmpty())
    val isConfigured: Boolean get() = true
    fun install(context: android.content.Context) {
        settings = com.yunx.app.data.prefs.SettingsRepository(context.applicationContext)
        cachePrefs = context.applicationContext.getSharedPreferences("announcement_cache", android.content.Context.MODE_PRIVATE)
    }

    /**
     * 列表分页大小 = 接口上限 100。
     * 启动时一次取满，于是「未读角标」「启动弹窗候选」的口径就是**全部公告**，而不是前 20 条；
     * 只有公告总数超过 100 条时列表页才需要「加载更多」翻第二页（仍用同一个 pageSize，分页口径一致）。
     */
    const val PAGE_SIZE = 100

    /** 公告相关日志统一走这个标签，失败原因一律 E 级打印，方便直接看 logcat 定位 */
    private const val TAG = "YunX-Announce"

    /** 发布者（服务端 `publisher` 对象） */
    data class Publisher(val name: String, val avatarUrl: String?)

    /**
     * 公告对象（公开字段，见接口文档 §2.4）。
     * [content] 只有详情接口返回；[coverImage] / [images] / [publisher.avatarUrl] 都是**完整直链**，可直接加载。
     */
    data class Announcement(
        val id: String,
        val title: String,
        val summary: String,
        val content: String?,
        val coverImage: String?,
        val images: List<String>,
        val publishAt: String?,
        val author: String,
        val viewCount: Long,
        val publisher: Publisher,
        val isPinned: Boolean,
        val pinnedAt: String?,
        val pinExpireAt: String?,
        val sortOrder: Int,
        val createdAt: String,
        val updatedAt: String,
        val popup: Boolean = true,
        val expiresAt: String? = null,
    ) {
        /** 生效时间（毫秒）：`publishAt` 为空表示「立即发布」，退回 createdAt；两者都解析不出来才是 0 */
        val effectiveMillis: Long get() = parseIsoMillis(publishAt) ?: parseIsoMillis(createdAt) ?: 0L
    }

    /** 分页结果（接口文档 §1.3）：`hasMore` 等价于 `page * pageSize < total` */
    data class Page(
        val total: Int,
        val page: Int,
        val pageSize: Int,
        val totalPages: Int,
        val hasMore: Boolean,
        val list: List<Announcement>,
        val warning: String? = null,
    )

    /** 请求结果：失败带上可直接展示的原因（服务端 `message` / HTTP 码 / 异常信息） */
    sealed interface Result<out T> {
        data class Success<T>(val data: T) : Result<T>
        data class Failure(val message: String) : Result<Nothing>
    }

    /** 获取公告列表（不含正文）；[page] 从 1 开始 */
    suspend fun fetchPage(page: Int = 1, pageSize: Int = PAGE_SIZE, forceRefresh: Boolean = false): Result<Page> =
        if (AnnouncementSource.isStatic(baseUrl)) {
            when (val feed = fetchStaticFeed(forceRefresh)) {
                is Result.Failure -> feed
                is Result.Success -> Result.Success(Page(feed.data.first.size, 1, feed.data.first.size, 1, false, feed.data.first, feed.data.second))
            }
        } else
        request("/api/v1/announcements?page=$page&pageSize=$pageSize") { data ->
            Page(
                total = data.optInt("total"),
                page = data.optInt("page", page),
                pageSize = data.optInt("pageSize", pageSize),
                totalPages = data.optInt("totalPages"),
                hasMore = data.optBoolean("hasMore"),
                list = data.optJSONArray("list")?.let { parseAnnouncementArray(it) } ?: emptyList()
            )
        }

    /**
     * 获取公告详情（含正文）。
     * 公告不存在 / 草稿 / 已下架 / 定时未到统一是 `404` + `code 40401`，服务端 message 可直接展示。
     */
    suspend fun fetchDetail(id: String, forceRefresh: Boolean = false): Result<Announcement> =
        if (AnnouncementSource.isStatic(baseUrl)) {
            when (val feed = fetchStaticFeed(forceRefresh)) {
                is Result.Failure -> feed
                is Result.Success -> feed.data.first.firstOrNull { it.id == id }?.let { Result.Success(it) }
                    ?: Result.Failure("公告已过期或不存在")
            }
        } else request("/api/v1/announcements/${encodeId(id)}") { data -> parseAnnouncement(data) }

    /** Fresh cache lasts 15 minutes; offline fallback is limited to 7 days and re-filters expiry. */
    internal fun cacheUsable(savedAt: Long, now: Long, offline: Boolean): Boolean =
        savedAt > 0L && now >= savedAt && now - savedAt <= if (offline) 7 * 86_400_000L else 15 * 60_000L

    internal fun parseStaticFeed(body: String, now: Long = System.currentTimeMillis()): List<Announcement> {
        val json = JSONObject(body)
        require(json.optInt("schemaVersion") == 1) { "不支持的公告格式" }
        if (json.has("enabled") && !json.optBoolean("enabled")) return emptyList()
        val arr = json.optJSONArray("announcements") ?: throw IllegalArgumentException("缺少公告列表")
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { !it.has("enabled") || it.optBoolean("enabled") }
            .map { item ->
                parseAnnouncement(item).copy(
                    publishAt = item.stringOrNull("publishedAt"),
                    createdAt = item.stringOrNull("publishedAt").orEmpty(),
                    isPinned = item.optBoolean("pinned"),
                    popup = item.optBoolean("popup", false),
                    expiresAt = item.stringOrNull("expiresAt"),
                    publisher = Publisher("星辰助手", null)
                )
            }.filter { item ->
                item.id.isNotBlank() && item.effectiveMillis <= now &&
                    (item.expiresAt == null || (parseIsoMillis(item.expiresAt)?.let { it > now } == true))
            }.distinctBy { it.id }
            .sortedWith(compareByDescending<Announcement> { it.isPinned }.thenByDescending { it.effectiveMillis })
    }

    internal fun pickPopupCandidate(items: List<Announcement>, shown: Set<String>): Announcement? =
        items.filter { it.popup && it.id.isNotBlank() && it.id !in shown }
            .sortedWith(compareByDescending<Announcement> { it.isPinned }.thenByDescending { it.effectiveMillis })
            .firstOrNull()

    internal suspend fun fetchStaticFeed(
        forceRefresh: Boolean,
        source: String = baseUrl,
        prefs: android.content.SharedPreferences? = cachePrefs,
        client: okhttp3.OkHttpClient = HttpClients.apiClient(),
        now: Long = System.currentTimeMillis()
    ): Result<Pair<List<Announcement>, String?>> =
        withContext(Dispatchers.IO) {
            feedMutex.lock()
            try {
                // Exact source URL is the key: switching sources never mixes data.
                val savedAt = prefs?.getLong(source + ":time", 0L) ?: 0L
                val cached = prefs?.getString(source, null)
                if (!forceRefresh && cached != null && cacheUsable(savedAt, now, false)) {
                    runCatching { parseStaticFeed(cached, now) }.getOrNull()?.let {
                        return@withContext Result.Success(it to null)
                    }
                }
                try {
                    val req = Request.Builder().url(source).header("Accept", "application/json")
                        .header("Cache-Control", "no-cache").get().build()
                    val body = client.newCall(req).execute().use { response ->
                        require(response.isSuccessful) { "HTTP ${response.code}" }
                        require((response.body?.contentLength() ?: -1L) <= 2 * 1024 * 1024) { "公告文件过大" }
                        val input = response.body?.byteStream() ?: throw IllegalArgumentException("公告响应为空")
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            require(output.size() + read <= 2 * 1024 * 1024) { "公告文件过大" }
                            output.write(buffer, 0, read)
                        }
                        output.toString("UTF-8")
                    }
                    require(body.toByteArray().size <= 2 * 1024 * 1024) { "公告文件过大" }
                    val items = parseStaticFeed(body, now)
                    prefs?.edit()?.putString(source, body)?.putLong(source + ":time", now)?.apply()
                    Result.Success(items to null)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (cached != null && cacheUsable(savedAt, now, true)) {
                        runCatching { parseStaticFeed(cached, now) }.getOrNull()?.let {
                            return@withContext Result.Success(it to "网络不可用，显示缓存公告")
                        }
                    }
                    Result.Failure("公告加载失败，请检查网络后重试")
                }
            } finally { feedMutex.unlock() }
        }

    /** 发 GET 请求并解析统一响应结构；所有失败路径都返回 [Result.Failure]（不抛异常） */
    private suspend fun <T> request(path: String, fromData: (JSONObject) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext Result.Failure("尚未配置公告来源")
            val url = baseUrl + path
            val body = try {
                val call = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "XingChenAssistant")
                    .get()
                    .build()
                HttpClients.apiClient().newCall(call).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext Result.Failure("HTTP ${resp.code}")
                    if (text.isBlank()) {
                        // 服务端异常时可能连 JSON 都没有：这里只留 HTTP 码，够定位
                        Log.e(TAG, "公告响应为空（HTTP ${resp.code}，$url）")
                        return@withContext Result.Failure("响应为空（HTTP ${resp.code}）")
                    }
                    // ★ 非 2xx 也可能带合法 JSON（业务失败），所以先取 body 再交给 parseEnvelope 判 success
                    text
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "公告请求异常（$url）：${e.javaClass.simpleName}: ${e.message}", e)
                return@withContext Result.Failure("${e.javaClass.simpleName}: ${e.message ?: "网络异常"}")
            }
            parseEnvelope(body, url, fromData)
        }

    /** 解析统一响应结构：`success` 为 false 时把服务端 message 原样带回（接口文档 §1.2） */
    private fun <T> parseEnvelope(body: String, url: String, fromData: (JSONObject) -> T): Result<T> {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.e(TAG, "公告响应不是合法 JSON（前 200 字=${body.take(200)}）", e)
            return Result.Failure("响应格式异常")
        }
        if (!json.optBoolean("success")) {
            val code = json.optInt("code")
            val message = json.stringOrNull("message") ?: "请求失败"
            Log.e(TAG, "公告接口业务失败：[$code] $message（$url）")
            return Result.Failure(message)
        }
        val data = json.optJSONObject("data") ?: return Result.Failure("响应数据为空")
        return try {
            Result.Success(fromData(data))
        } catch (e: Exception) {
            Log.e(TAG, "公告数据解析失败（$url）：${e.message}", e)
            Result.Failure("数据解析失败")
        }
    }

    private fun parseAnnouncementArray(arr: JSONArray): List<Announcement> {
        val out = ArrayList<Announcement>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val parsed = parseAnnouncement(item)
            // ★ id 是列表 key（LazyColumn 的 key 必须唯一）与已读记录的键：空 id 直接丢掉，
            //   否则一条脏数据就会让整个列表抛 "Key was already used"。
            if (parsed.id.isEmpty()) {
                Log.w(TAG, "公告列表第 $i 条缺少 id，已跳过（title=${parsed.title.take(30)}）")
                continue
            }
            out.add(parsed)
        }
        return out.distinctBy { it.id }
    }

    private fun parseStringArray(arr: JSONArray): List<String> {
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            if (arr.isNull(i)) continue
            val text = arr.optString(i).trim()
            if (text.isNotEmpty()) out.add(text)
        }
        return out
    }

    private fun parseAnnouncement(json: JSONObject): Announcement = Announcement(
        id = json.stringOrNull("id").orEmpty(),
        title = json.stringOrNull("title").orEmpty(),
        summary = json.stringOrNull("summary").orEmpty(),
        content = json.stringOrNull("content"),
        coverImage = json.stringOrNull("coverImage"),
        images = json.optJSONArray("images")?.let { parseStringArray(it) } ?: emptyList(),
        publishAt = json.stringOrNull("publishAt"),
        author = json.stringOrNull("author").orEmpty(),
        viewCount = json.optLong("viewCount"),
        publisher = json.optJSONObject("publisher")?.let { p ->
            Publisher(
                name = p.stringOrNull("name").orEmpty(),
                avatarUrl = p.stringOrNull("avatarUrl")
            )
        } ?: Publisher(name = "", avatarUrl = null),
        isPinned = json.optBoolean("isPinned"),
        pinnedAt = json.stringOrNull("pinnedAt"),
        pinExpireAt = json.stringOrNull("pinExpireAt"),
        sortOrder = json.optInt("sortOrder"),
        createdAt = json.stringOrNull("createdAt").orEmpty(),
        updatedAt = json.stringOrNull("updatedAt").orEmpty()
    )

    /** 公告 ID 长度 <= 128 且允许客户端自定义，进路径前统一编码（正常 ann_xxx 编码后原样） */
    private fun encodeId(id: String): String = URLEncoder.encode(id.trim(), "UTF-8")
}

/**
 * `optString` 遇到 JSON null 会返回字符串 `"null"`（不是空串），可空字段一律走这里。
 * 「字段缺失」与「字段为 null」在接口里是两种含义（如 publishAt = null 表示立即发布），
 * 所以这里只做「取不到 / 为 null ⇒ null」，空串也算没值（避免把 "" 当时间/图片地址用）。
 */
private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
