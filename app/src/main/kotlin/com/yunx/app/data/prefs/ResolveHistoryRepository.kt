/*
 * 星辰助手 - 基于 YunX 二次开发
 * 本文件随项目依据 GNU AGPL v3.0 或更高版本发布。
 */

package com.yunx.app.data.prefs

import android.content.Context
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.SharePlatform
import org.json.JSONArray
import org.json.JSONObject

data class ResolveHistoryItem(
    val link: String,
    val password: String,
    val platformName: String,
    val shareId: String,
    val timestamp: Long
)

class ResolveHistoryRepository(
    context: Context
) {
    private val prefs = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): List<ResolveHistoryItem> {
        val raw = prefs.getString(KEY_HISTORY, null)
            ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)

            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index)
                        ?: continue

                    val link = obj.optString("link")
                    val shareId = obj.optString("shareId")

                    if (link.isBlank() || shareId.isBlank()) {
                        continue
                    }

                    add(
                        ResolveHistoryItem(
                            link = link,
                            password = obj.optString("password"),
                            platformName = obj.optString(
                                "platformName",
                                "网盘"
                            ),
                            shareId = shareId,
                            timestamp = obj.optLong(
                                "timestamp",
                                0L
                            )
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(
        text: String,
        password: String?
    ): List<ResolveHistoryItem> {
        val parsed = ShareLinkParser.parse(text)
            ?: return load()

        val url = URL_REGEX
            .find(text.trim())
            ?.value
            ?.trimEnd(
                '。',
                '，',
                ',',
                '；',
                ';',
                ')',
                ']',
                '}',
                '"',
                '\''
            )
            ?: text.trim()

        val item = ResolveHistoryItem(
            link = url,
            password = password
                ?.trim()
                .orEmpty()
                .ifBlank {
                    parsed.pwd.orEmpty()
                },
            platformName = platformLabel(
                parsed.platform
            ),
            shareId = parsed.shareId,
            timestamp = System.currentTimeMillis()
        )

        val updated = buildList {
            add(item)

            addAll(
                load().filterNot {
                    it.platformName == item.platformName &&
                        it.shareId == item.shareId
                }
            )
        }.take(MAX_HISTORY)

        save(updated)

        return updated
    }

    fun remove(
        item: ResolveHistoryItem
    ): List<ResolveHistoryItem> {
        val updated = load().filterNot {
            it.platformName == item.platformName &&
                it.shareId == item.shareId
        }

        save(updated)

        return updated
    }



    fun clear() {

        prefs
            .edit()
            .remove(KEY_HISTORY)
            .apply()
    }

    private fun save(
        items: List<ResolveHistoryItem>
    ) {
        val array = JSONArray()

        items.forEach { item ->
            array.put(
                JSONObject()
                    .put(
                        "link",
                        item.link
                    )
                    .put(
                        "password",
                        item.password
                    )
                    .put(
                        "platformName",
                        item.platformName
                    )
                    .put(
                        "shareId",
                        item.shareId
                    )
                    .put(
                        "timestamp",
                        item.timestamp
                    )
            )
        }

        prefs
            .edit()
            .putString(
                KEY_HISTORY,
                array.toString()
            )
            .apply()
    }

    private fun platformLabel(
        platform: SharePlatform
    ): String = when (platform) {
        SharePlatform.QUARK -> "夸克网盘"
        SharePlatform.UC -> "UC 网盘"
        SharePlatform.XUNLEI -> "迅雷网盘"
        SharePlatform.BAIDU -> "百度网盘"
        SharePlatform.C139 -> "移动云盘"
        SharePlatform.PAN123 -> "123 云盘"
        SharePlatform.PAN115 -> "115 网盘"
        SharePlatform.GUANGYA -> "光鸭云盘"
        SharePlatform.ILANZOU -> "蓝奏云优享版"
        SharePlatform.LANZOU -> "蓝奏云"
        SharePlatform.GITHUB -> "GitHub"
    }

    private companion object {
        const val PREFS_NAME =
            "resolve_history"

        const val KEY_HISTORY =
            "history"

        const val MAX_HISTORY =
            30

        val URL_REGEX =
            Regex("""https?://[^\s]+""")
    }
}
