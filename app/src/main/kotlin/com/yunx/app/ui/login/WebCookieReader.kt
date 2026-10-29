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

package com.yunx.app.ui.login

import android.webkit.CookieManager
import com.yunx.app.data.network.video.VideoPlatform
import com.yunx.app.util.CookieHeader

/**
 * 从 WebView 的 Cookie 库读「整站登录态」。
 *
 * ★ 为什么不能只 `getCookie("https://bilibili.com")` 一把梭：
 *   `CookieManager.getCookie(url)` 只返回**与该 URL 匹配**的 Cookie，而「登录态落在哪个主机上」
 *   是站点自己决定的，同一个站点的不同子域完全可能各有一套：
 *   - 有的平台登录后 `SESSDATA` 是 `Domain=.bilibili.com`（子域通吃）；
 *   - 有的平台把凭证写成 **host-only**（没有 Domain 属性，只有 `www.xxx.com` 能读到）；
 *   - 登录动作发生在 `passport.xxx.com`，跳回来之后凭证才种到主站。
 *
 *   只按裸域读一次，上面第二种情况就会**读到空串**，界面表现为「网页里明明已经登录了，
 *   点保存却说未检测到登录态」——这正是哔哩哔哩那类跳转式登录最容易踩的坑。
 *   所以这里按「登录页主机 → www → 裸域 → m 域」的顺序**逐个读并合并**。
 *
 * ★ 合并规则：按 **Cookie 名**去重，名字第一次出现时的值胜出。同名不同值几乎只可能是
 *   游客态与登录态并存（子域各写各的），先读到的那个更接近用户刚才操作的那条路径。
 *
 * ★ 读之前先 [CookieManager.flush]：WebView 的 Cookie 写入是异步落盘的，刚登录完马上读
 *   有概率读到旧集合；不 flush 还会导致**重启 App 后 Cookie 丢失**，表现为「昨天登录的，
 *   今天又要重登」。
 */
object WebCookieReader {

    /**
     * 该平台需要尝试的 Cookie URL，按优先级排列。
     *
     * 派生自 [VideoPlatform.cookieDomain] 与 [VideoPlatform.loginUrl]，不需要平台逐个配置。
     */
    fun candidateUrls(platform: VideoPlatform): List<String> {
        val bare = platform.cookieDomain
            .removePrefix("https://")
            .removePrefix("http://")
            .trim('/')
            .takeIf { it.isNotBlank() }
        val loginHost = platform.loginUrl
            .substringAfter("://", "")
            .substringBefore('/')
            .substringBefore(':')
            .takeIf { it.isNotBlank() }

        val hosts = LinkedHashSet<String>()
        loginHost?.let(hosts::add)
        if (bare != null) {
            // 登录态最常落在主站 www 上（登录页只是入口）
            hosts.add("www.$bare")
            hosts.add(bare)
            hosts.add("m.$bare")
        }
        return hosts.map { "https://$it" }
    }

    /**
     * 读取该平台当前的完整 Cookie（合并多域）。
     *
     * @return `k=v; k2=v2` 形式的 Cookie 头；一条都没有时返回空串
     */
    fun read(platform: VideoPlatform): String {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return ""
        // 先把 WebView 内存里的 Cookie 落盘，避免读到登录前的旧集合
        runCatching { manager.flush() }
        return merge(candidateUrls(platform).mapNotNull { url ->
            runCatching { manager.getCookie(url) }.getOrNull()
        })
    }

    /** 合并多份 Cookie 串：按 key 去重，先到先得；实现见 [CookieHeader.merge] */
    fun merge(cookies: List<String>): String = CookieHeader.merge(cookies)

    /** Cookie 里有没有这个键（判断"登录态是否真的写进来了"）；实现见 [CookieHeader] */
    fun hasKey(cookie: String, key: String): Boolean = CookieHeader.hasKey(cookie, key)

    /** 取某个键的值（没有则空串） */
    fun valueOf(cookie: String, key: String): String = CookieHeader.valueOf(cookie, key)
}
