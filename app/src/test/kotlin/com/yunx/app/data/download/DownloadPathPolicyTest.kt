package com.yunx.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadPathPolicyTest {

    @Test
    fun rejectsTraversalComponentsAndAbsolutePaths() {
        assertNull(DownloadPathPolicy.sanitize("../secret.txt", "fallback"))
        assertNull(DownloadPathPolicy.sanitize("folder/../secret.txt", "fallback"))
        assertNull(DownloadPathPolicy.sanitize("folder/./secret.txt", "fallback"))
        assertNull(DownloadPathPolicy.sanitize("/absolute/secret.txt", "fallback"))
    }

    @Test
    fun preservesLegitimateNestedDownload() {
        val path = DownloadPathPolicy.sanitize("folder/sub/a?.mp4", "fallback")
        assertEquals("folder/sub", path?.relativeDirectory)
        assertEquals("a_.mp4", path?.fileName)
    }

    @Test
    fun trailingSlashMeansDirectoryWithoutFileName() {
        // 批量下载拼出的 "文件夹A/"（该项拿不到文件名）不能把最后一段**目录名**当文件名，
        // 否则文件会平铺到下载目录根、还顶着文件夹的名字（用户报的「只有一个文件进了文件夹」）。
        val nested = DownloadPathPolicy.sanitize("folder/sub/", "fallback.bin")
        assertEquals("folder/sub", nested?.relativeDirectory)
        assertEquals("fallback.bin", nested?.fileName)

        val oneLevel = DownloadPathPolicy.sanitize("folder/", "fallback.bin")
        assertEquals("folder", oneLevel?.relativeDirectory)
        assertEquals("fallback.bin", oneLevel?.fileName)
    }

    @Test
    fun canonicalContainmentRejectsSibling() {
        val base = File("build/policy-test/downloads")
        assertTrue(DownloadPathPolicy.isContained(base, File(base, "folder/file.bin")))
        assertFalse(DownloadPathPolicy.isContained(base, File(base, "../outside.bin")))
    }
}
