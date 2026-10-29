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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cookie 头解析与多域合并的规则测试。
 *
 * 这些规则直接决定「网页登录完能不能保存成功」，而且失败时的现象（读到空串 / 缺登录键）
 * 在界面上看起来一模一样，只能靠单测钉死。
 */
class CookieHeaderTest {

    @Test
    fun `hasKey 精确匹配键名而不是前缀`() {
        val cookie = "bili_jct=abc; SESSDATA=def; DedeUserID=1"
        assertTrue(CookieHeader.hasKey(cookie, "SESSDATA"))
        assertTrue(CookieHeader.hasKey(cookie, "bili_jct"))
        // SESS 不是键名：不能因为 SESSDATA 存在就认成 SESS
        assertFalse(CookieHeader.hasKey(cookie, "SESS"))
        assertFalse(CookieHeader.hasKey(cookie, "SESSDATAX"))
    }

    @Test
    fun `valueOf 取值并去掉空白`() {
        val cookie = "a=1; SESSDATA=  xyz%2C123 ;b=2"
        assertEquals("xyz%2C123", CookieHeader.valueOf(cookie, "SESSDATA"))
        assertEquals("", CookieHeader.valueOf(cookie, "missing"))
    }

    @Test
    fun `keys 保持顺序且忽略空片段`() {
        val keys = CookieHeader.keys("a=1; ; b=2;")
        assertEquals(listOf("a", "b"), keys)
    }

    @Test
    fun `merge 同名键先到先得`() {
        // 主站(登录态)在前、裸域(游客态)在后：必须保留主站那份
        val merged = CookieHeader.merge(
            listOf(
                "SESSDATA=logged_in; buvid3=main",
                "SESSDATA=guest; buvid3=guest; b_nut=1"
            )
        )
        assertEquals("logged_in", CookieHeader.valueOf(merged, "SESSDATA"))
        assertEquals("main", CookieHeader.valueOf(merged, "buvid3"))
        // 只在后面那份里出现的键也要并进来（否则会丢掉子域独有的凭证）
        assertEquals("1", CookieHeader.valueOf(merged, "b_nut"))
    }

    @Test
    fun `merge 处理空串与空列表`() {
        assertEquals("", CookieHeader.merge(emptyList()))
        assertEquals("a=1", CookieHeader.merge(listOf("", "a=1", "   ")))
    }

    @Test
    fun `合并后不会出现重复键`() {
        val merged = CookieHeader.merge(listOf("a=1", "a=2", "a=3"))
        assertEquals("a=1", merged)
        assertEquals(1, merged.split("; ").size)
    }
}
