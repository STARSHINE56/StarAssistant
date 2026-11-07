package com.xingchen

import com.yunx.app.data.network.*
import org.junit.Assert.*
import org.junit.Test

class SharedTest {
    @Test fun recognizesAllPlatformsAndPassword() {
        val links=mapOf(
            "https://pan.quark.cn/s/abc123" to SharePlatform.QUARK,
            "https://drive.uc.cn/s/abc123" to SharePlatform.UC,
            "https://pan.xunlei.com/s/abc123" to SharePlatform.XUNLEI,
            "https://pan.baidu.com/s/1abc123" to SharePlatform.BAIDU,
            "https://www.123pan.com/s/abc123-def456" to SharePlatform.PAN123,
            "https://yun.139.com/shareweb/#/w/i/abc123" to SharePlatform.C139)
        links.forEach { (link,p) -> assertEquals(p,ShareLinkParser.parse(link)?.platform) }
    }
    @Test fun portableBase64SupportsWhitespaceAndUrlAlphabet() {
        val content=byteArrayOf(-5,-1,1,2)
        assertArrayEquals(content,WireBase64.decode("+/8BAg==",WireBase64.DEFAULT))
        assertArrayEquals(content,WireBase64.decode("-_8BAg",WireBase64.URL_SAFE))
        assertArrayEquals(content,WireBase64.decode("+/8B\nAg==",WireBase64.DEFAULT))
        assertEquals("+/8BAg==",WireBase64.encodeToString(content,WireBase64.NO_WRAP))
    }
}
