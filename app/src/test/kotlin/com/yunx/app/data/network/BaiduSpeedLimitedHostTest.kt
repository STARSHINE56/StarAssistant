package com.yunx.app.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 百度直链候选的**限速通道**判定。
 *
 * 背景：百度 `locatedownload` 会给非会员下发 `d2-ant` 系列的限速节点，
 * 速度被压到很低；会员走 `appallNN.baidupcs.com` 一类的明文通道。
 * 旧版只是「因为 rank1 的 d2-ant 恰好是 encrypt=1 加密通道」才顺带跳过它，
 * 现在显式按主机名排除，即使百度将来把限速通道改成明文也不会选错。
 *
 * 这里复刻 BaiduApi.isSpeedLimitedHost 的判定逻辑（原有实现是 private）。
 */
class BaiduSpeedLimitedHostTest {

    private val hosts = listOf("d2-ant.baidu.com", "ant.baidu.com")
    private val patterns = listOf(Regex("""^[a-z0-9-]*-ant[0-9]*\.baidu\.com$"""))

    private fun limited(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        if (host.isEmpty()) return false
        return hosts.any { host == it || host.endsWith(".$it") } ||
            patterns.any { it.containsMatchIn(host) }
    }

    @Test
    fun rejectsSpeedLimitedHosts() {
        assertTrue(limited("https://d2-ant.baidu.com/file/abc"))
        assertTrue(limited("https://d2-ant01.baidu.com/file/abc"))
        assertTrue(limited("https://d2-ant02.baidu.com/file/abc"))
        assertTrue(limited("https://ant.baidu.com/file/abc"))
        assertTrue(limited("https://cdn.d2-ant.baidu.com/file/abc"))
    }

    @Test
    fun keepsFastHosts() {
        assertFalse(limited("https://appall01.baidupcs.com/file/abc"))
        assertFalse(limited("https://appall02.baidupcs.com/file/abc"))
        assertFalse(limited("https://d.pcs.baidu.com/file/abc"))
        assertFalse(limited("https://pan.baidu.com/s/1abc"))
        assertFalse(limited("https://bj.baidupcs.com/file/abc"))
    }

    @Test
    fun doesNotOverMatch() {
        // 不能把不含 -ant 的普通主机误判成限速通道
        assertFalse(limited("https://notant.baidu.com/file/abc"))
        assertFalse(limited("https://plant.baidu.com/file/abc"))
        assertFalse(limited("https://antivirus.baidu.com/file/abc"))
    }

    @Test
    fun malformedUrlsAreNotMarkedLimited() {
        // 解析不出 host 时返回 false：宁可少剔除，也不能把可用直链全干掉
        assertFalse(limited(""))
        assertFalse(limited("not a url"))
        assertFalse(limited("//no-scheme"))
    }

    @Test
    fun caseInsensitive() {
        assertTrue(limited("https://D2-ANT.BAIDU.COM/file/abc"))
        assertFalse(limited("https://APPALL01.BAIDUPCS.COM/file/abc"))
    }
}
