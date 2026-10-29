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

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 哔哩哔哩 Web 端的 **WBI 请求签名**。
 *
 * 这是 B 站前端自己算的一套参数校验：`wts`（时间戳）+ `w_rid`（对排序后的查询串加
 * `mixinKey` 做 MD5）。它是**公开的前端算法**，浏览器打开播放页时算的是同一个东西 ——
 * 用它只是让自己的请求「像浏览器发出的」，不是什么破解。
 *
 * ★ 为什么要有它：不签名的 `x/player/playurl` 目前还能用，但 B 站近两年一直在往
 *   「所有 web 接口都要 WBI」的方向收；签了，接口改严时不会突然掉成低清。
 *   （2026-10 实测：不带签名也还能取到 1080P 档位，但 dash 里给不给高清轨由账号权限决定。）
 *
 * ★ 算法的三段（缺一段就对不上）：
 *   1. `imgKey` + `subKey` 拼成 64 字符串，按固定置换表重排后取前 32 位 = `mixinKey`；
 *   2. 参数**按键名升序**排序，值里去掉 `!'()*` 四个字符，拼成 `k=v&k2=v2`；
 *   3. 末尾追加 `wts`，对 `query + mixinKey` 取 MD5 = `w_rid`。
 *
 * 密钥（`imgKey`/`subKey`）每天会换，从 `x/web-interface/nav` 的 `data.wbi_img` 里取，
 * 缓存一小时足够（见 `BilibiliParser.wbiMixinKey`）。
 */
internal object BiliWbi {

    /**
     * 置换表：64 个下标，按它重排 `imgKey + subKey` 后取前 32 位。
     *
     * ★ 这是 B 站前端代码里的常量，**顺序不能动**，动了算出来的 `w_rid` 就永远是错的
     *   （服务端不会报错，只会降级/拒绝，排查起来非常费劲）。
     */
    private val MIXIN_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    )

    /** 参数值里必须剔除的字符（B 站前端就是这么干的，不剔除则签名对不上） */
    private const val STRIP = "!'()*"

    /**
     * 由 `wbi_img` 的两个文件名算 `mixinKey`。
     *
     * @param imgKey `wbi_img.img_url` 的文件名（不含扩展名），如 `7cd084941338484aae1ad9425b84077c`
     * @param subKey `wbi_img.sub_url` 的文件名（不含扩展名）
     */
    fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        if (raw.length < 64) return "" // 密钥不完整时宁可返回空（调用方退回不签名），也不要算出个错的
        val sb = StringBuilder(32)
        for (i in 0 until 32) {
            sb.append(raw[MIXIN_TAB[i]])
        }
        return sb.toString()
    }

    /**
     * 给一组参数签名，返回**可直接拼进 URL 的查询串**（含 `wts` 与 `w_rid`）。
     *
     * @param timestampSeconds 秒级时间戳；显式传入是为了单测能固定结果
     */
    fun sign(
        params: Map<String, String>,
        mixinKey: String,
        timestampSeconds: Long = System.currentTimeMillis() / 1000L
    ): String {
        val normalized = LinkedHashMap<String, String>()
        params.forEach { (key, value) ->
            normalized[key] = value.filterNot { it in STRIP }
        }
        normalized["wts"] = timestampSeconds.toString()

        val query = normalized.entries
            .sortedBy { it.key }
            .joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

        val wRid = md5(query + mixinKey)
        return "$query&w_rid=$wRid"
    }

    /** 拼出完整请求地址（`baseUrl` 不带查询串） */
    fun url(
        baseUrl: String,
        params: Map<String, String>,
        mixinKey: String,
        timestampSeconds: Long = System.currentTimeMillis() / 1000L
    ): String = "$baseUrl?${sign(params, mixinKey, timestampSeconds)}"

    /**
     * URL 编码，对齐 B 站前端的 `encodeURIComponent` 语义。
     *
     * ★ 不能用 `URLEncoder.encode` 了事：Java 版把空格编成 `+`、且**不编码** `*`，
     *   而 JS 的 `encodeURIComponent` 编成 `%20`、把 `*` 编码成 `%2A`。参数的形态一变，
     *   `w_rid` 就对不上。这里先按 JS 的规则做替换，再交给 URLEncoder。
     */
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~")

    private fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        digest.forEach { byte ->
            val v = byte.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }
}
