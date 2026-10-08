package com.yunx.app.data.gopeed

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GopeedOutputTest {
    @Test fun doneStatusRequiresRealCompleteOutputAfterRecovery() {
        val dir = File(System.getProperty("java.io.tmpdir"), "gopeed-check-${System.nanoTime()}").apply { mkdirs() }
        try {
            val file = File(dir, "file")
            assertNotNull(GopeedEngine.completedOutputError(file, 3, false))
            file.writeText("ab")
            assertNotNull(GopeedEngine.completedOutputError(file, 3, false))
            file.writeText("abc")
            assertNull(GopeedEngine.completedOutputError(file, 3, false))
            assertNull(GopeedEngine.completedOutputError(dir, 0, true))
        } finally { dir.deleteRecursively() }
    }
}
