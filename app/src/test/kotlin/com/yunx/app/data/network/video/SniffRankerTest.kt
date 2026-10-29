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
 * 嗅探候选排序的规则测试。
 *
 * 这些规则是「风控/改版之后最先要调的东西」，钉死在单测里，改阈值时才知道自己动了什么。
 */
class SniffRankerTest {

    private fun candidate(url: String, source: String = "fetch", w: Int = 0, h: Int = 0) =
        SniffCandidate(url = url, source = source, width = w, height = h)

    @Test
    fun `视频地址排在图片前面`() {
        val ranked = SniffRanker.rank(
            listOf(
                candidate("https://p1.example.com/cover.jpg"),
                candidate("https://v.example.com/play/abc.mp4")
            )
        )
        assertTrue("视频应排第一，实际 ${ranked.first().url}", ranked.first().url.endsWith(".mp4"))
    }

    @Test
    fun `头像与图标被重罚`() {
        val ranked = SniffRanker.rank(
            listOf(
                candidate("https://p.example.com/avatar/u123.jpg"),
                candidate("https://p.example.com/logo.png"),
                candidate("https://p.example.com/note/content.jpg", w = 1080, h = 1440)
            )
        )
        assertEquals("https://p.example.com/note/content.jpg", ranked.first().url)
    }

    @Test
    fun `同一媒体不同查询串会被去重`() {
        val ranked = SniffRanker.rank(
            listOf(
                candidate("https://v.example.com/a.mp4?sign=1&ts=111"),
                candidate("https://v.example.com/a.mp4?sign=2&ts=222")
            )
        )
        assertEquals(1, ranked.size)
    }

    @Test
    fun `m3u8 也当作视频并标记 HLS`() {
        val url = "https://v.example.com/hls/index.m3u8?k=1"
        assertEquals(VideoMediaKind.VIDEO, SniffRanker.kindOf(url))
        assertTrue(SniffRanker.isHls(url))
        // m3u8 对上 mp4 时略低，但高于任何图片
        val ranked = SniffRanker.rank(
            listOf(candidate("https://p.example.com/x.jpg", w = 1000, h = 1000), candidate(url))
        )
        assertEquals(url, ranked.first().url)
    }

    @Test
    fun `尺寸过小的图片被压下去`() {
        val ranked = SniffRanker.rank(
            listOf(
                candidate("https://p.example.com/emoji/smile.jpg", w = 48, h = 48),
                candidate("https://p.example.com/big.jpg", w = 1080, h = 1440)
            )
        )
        assertEquals("https://p.example.com/big.jpg", ranked.first().url)
    }

    @Test
    fun `DOM 里真实挂出的视频权重最高`() {
        val ranked = SniffRanker.rank(
            listOf(
                candidate("https://v.example.com/blob-data.mp4"),
                candidate("https://v.example.com/stream/main.mp4", source = "dom", w = 720, h = 1280)
            )
        )
        assertEquals("https://v.example.com/stream/main.mp4", ranked.first().url)
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(SniffRanker.rank(emptyList()).isEmpty())
    }

    @Test
    fun `认不出后缀时按视频处理`() {
        assertEquals(VideoMediaKind.VIDEO, SniffRanker.kindOf("https://v.example.com/getplayurl?id=1"))
    }
}
