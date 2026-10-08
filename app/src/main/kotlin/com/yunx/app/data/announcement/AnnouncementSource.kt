package com.yunx.app.data.announcement

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object AnnouncementSource {
    const val OFFICIAL_URL = "https://starassistant-1308286552.cos.ap-beijing.myqcloud.com/announcements.json"
    fun effective(value: String): String = value.ifBlank { OFFICIAL_URL }
    fun isStatic(value: String): Boolean = value.toHttpUrlOrNull()?.encodedPath?.endsWith(".json", true) == true

    fun normalize(value: String): String {
        val clean = value.trim().trimEnd('/')
        if (clean.isEmpty()) return ""
        val url = clean.toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "公告来源必须是不含账号、查询参数和片段的 HTTPS 地址"
        }
        return url.toString().trimEnd('/')
    }
}
