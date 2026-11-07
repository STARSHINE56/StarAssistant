package com.yunx.app.data.announcement

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object AnnouncementSource {
    /** 官方 COS 直链。公告源现在是写死的，不再支持用户自定义。 */
    const val OFFICIAL_URL = "https://starassistant-1308286552.cos.ap-beijing.myqcloud.com/announcements.json"

    /** 静态 JSON（COS 直链）与兼容 API 的区分：看路径是否以 .json 结尾。 */
    fun isStatic(value: String): Boolean = value.toHttpUrlOrNull()?.encodedPath?.endsWith(".json", true) == true
}
