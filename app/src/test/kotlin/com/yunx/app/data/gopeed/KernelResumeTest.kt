package com.yunx.app.data.gopeed

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class KernelResumeTest {
    @Test fun keepsSameLayoutButDiscardsIncompatibleOffsets() {
        val dir = File(System.getProperty("java.io.tmpdir"), "kernel-resume-${System.nanoTime()}").apply { mkdirs() }
        try {
            KernelProvisioner.prepareResumeLayout(dir, 100, 4)
            val part = File(dir, "part_1").apply { writeText("partial") }
            val other = File(dir, "keep").apply { writeText("unrelated") }
            KernelProvisioner.prepareResumeLayout(dir, 100, 4)
            assertTrue(part.exists())
            KernelProvisioner.prepareResumeLayout(dir, 100, 8)
            assertFalse(part.exists())
            assertTrue(other.exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun validatesSha256AndRejectsCorruptionAndMalformedDigests() {
        val file = File.createTempFile("kernel-digest-", ".aar")
        try {
            file.writeText("original")
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            KernelProvisioner.verifyDigest(file, "sha256:$digest")
            file.writeText("corrupted")
            assertThrows(IllegalStateException::class.java) { KernelProvisioner.verifyDigest(file, "sha256:$digest") }
            assertThrows(IllegalArgumentException::class.java) { KernelProvisioner.verifyDigest(file, "sha256:broken") }
        } finally { file.delete() }
    }
}
