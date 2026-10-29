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

package com.yunx.app.util

/**
 * `Cookie: k=v; k2=v2` 这种头的极简解析。
 *
 * 存在意义：判断「这份 Cookie 里到底有没有登录键」是各平台登录校验的第一步
 * （没有 `SESSDATA` 的 B 站 Cookie 一定是没登录），而且**不该为此发一次网络请求** ——
 * 空手去敲校验接口只会白挨一次风控。
 *
 * ★ 只做「查键 / 取值 / 列键名」三件事，不做转义与校验和：这里处理的是 WebView 读出来的
 *   标准 Cookie 头，值里不含分号（含分号的值必然被 URL 编码过）。
 */
object CookieHeader {

    /** 拆分出全部键名（保持顺序，去空） */
    fun keys(cookie: String): List<String> =
        cookie.split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }

    /** 有没有这个键 */
    fun hasKey(cookie: String, key: String): Boolean =
        cookie.split(';').any { it.trim().startsWith("$key=") }

    /**
     * 合并多份 Cookie 头（来自不同子域的 `CookieManager.getCookie(url)`）。
     *
     * ★ 同名去重、**先到先得**：同一个键在多个子域各写各的值时（游客态与登录态并存），
     *   调用方按「离登录动作最近的主机」排在前面的顺序传进来，这里保留第一条。
     */
    fun merge(cookies: List<String>): String {
        val merged = LinkedHashMap<String, String>()
        cookies.forEach { raw ->
            raw.split(';').forEach { pair ->
                val trimmed = pair.trim()
                if (trimmed.isEmpty()) return@forEach
                val name = trimmed.substringBefore('=').trim()
                if (name.isBlank()) return@forEach
                merged.putIfAbsent(name, trimmed)
            }
        }
        return merged.values.joinToString("; ")
    }

    /** 取这个键的值；不存在返回空串 */
    fun valueOf(cookie: String, key: String): String =
        cookie.split(';')
            .firstOrNull { it.trim().startsWith("$key=") }
            ?.substringAfter('=', "")
            ?.trim()
            .orEmpty()
}
