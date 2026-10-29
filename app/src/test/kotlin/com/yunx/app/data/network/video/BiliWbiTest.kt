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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WBI 签名测试。
 *
 * 基准值是用**同一套算法**在 Python 里算出来的（置换表 + 排序 + 剔除 `!'()*` + MD5），
 * 并与真实 `x/player/wbi/playurl` 请求验证过。签名错了服务端不会报错、只会降级，
 * 所以这些常量是唯一能防回归的东西。
 */
class BiliWbiTest {

    // 2026-10-29 实测的 wbi_img 文件名与算出来的 mixinKey
    private val imgKey = "7cd084941338484aae1ad9425b84077c"
    private val subKey = "4932caff0ff746eab6f01bf08b70ac45"
    private val mixin = "ea1db124af3c7062474693fa704f4ff8"

    @Test
    fun `mixinKey 按置换表重排`() {
        assertEquals(mixin, BiliWbi.mixinKey(imgKey, subKey))
    }

    @Test
    fun `密钥不完整时返回空串而不是算错`() {
        assertEquals("", BiliWbi.mixinKey("short", "key"))
    }

    @Test
    fun `签名结果与基准一致`() {
        val query = BiliWbi.sign(
            mapOf(
                "bvid" to "BV1GJ411x7h7",
                "cid" to "137649199",
                "fnval" to "4048",
                "qn" to "127",
                "fourk" to "1"
            ),
            mixin,
            timestampSeconds = 1700000000L
        )
        assertEquals(
            "bvid=BV1GJ411x7h7&cid=137649199&fnval=4048&fourk=1&qn=127&wts=1700000000" +
                "&w_rid=e024151d327dcf61690b4bf93bd1d61a",
            query
        )
    }

    @Test
    fun `参数值里的特殊字符会被剔除`() {
        // ! ' ( ) * 五个字符必须去掉，否则服务端算出来的 w_rid 与我们不一致
        val query = BiliWbi.sign(
            mapOf("search" to "a!b'c(d)e*f", "page" to "1"),
            mixin,
            timestampSeconds = 1700000001L
        )
        assertEquals(
            "page=1&search=abcdef&wts=1700000001&w_rid=df05ab69a5080ebbc9125d946bd2a90b",
            query
        )
    }

    @Test
    fun `参数按键名升序而不是按传入顺序`() {
        val a = BiliWbi.sign(mapOf("b" to "2", "a" to "1"), mixin, 1700000000L)
        val b = BiliWbi.sign(mapOf("a" to "1", "b" to "2"), mixin, 1700000000L)
        assertEquals(a, b)
    }

    @Test
    fun `url 拼接带上问号`() {
        val url = BiliWbi.url(
            "https://api.bilibili.com/x/player/wbi/playurl",
            mapOf("a" to "1"),
            mixin,
            1700000000L
        )
        assertTrue(url.startsWith("https://api.bilibili.com/x/player/wbi/playurl?a=1&wts="))
    }
}
