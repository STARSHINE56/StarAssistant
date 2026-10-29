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

import com.yunx.app.data.network.video.VideoPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「去哪些主机上找登录态」的规则测试。
 *
 * 这条顺序直接决定哔哩哔哩那类「登录在 passport、凭证种在主站」的平台能不能读到 Cookie：
 * 少读一个主机，现象就是「网页里明明登录了，点保存却说未检测到登录态」。
 */
class WebCookieReaderTest {

    @Test
    fun `哔哩哔哩要覆盖登录页主站与移动站`() {
        val urls = WebCookieReader.candidateUrls(VideoPlatform.BILIBILI)
        // 登录页主机排最前：凭证最先落在用户刚才操作的那条路径上
        assertEquals("https://passport.bilibili.com", urls.first())
        assertTrue(urls.contains("https://www.bilibili.com"))
        assertTrue(urls.contains("https://bilibili.com"))
        assertTrue(urls.contains("https://m.bilibili.com"))
    }

    @Test
    fun `小红书同样覆盖三个主机`() {
        val urls = WebCookieReader.candidateUrls(VideoPlatform.XHS)
        assertTrue(urls.contains("https://www.xiaohongshu.com"))
        assertTrue(urls.contains("https://xiaohongshu.com"))
    }

    @Test
    fun `没有重复主机`() {
        val urls = WebCookieReader.candidateUrls(VideoPlatform.BILIBILI)
        assertEquals(urls.size, urls.distinct().size)
    }

    @Test
    fun `全部是带 scheme 的完整 URL`() {
        // CookieManager.getCookie 的入参是 URL，裸域会读不到任何东西（见类注释）
        VideoPlatform.entries.forEach { platform ->
            WebCookieReader.candidateUrls(platform).forEach { url ->
                assertTrue("$url 缺少 scheme", url.startsWith("https://"))
            }
        }
    }
}
