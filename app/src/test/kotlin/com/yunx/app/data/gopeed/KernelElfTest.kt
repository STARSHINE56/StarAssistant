package com.yunx.app.data.gopeed
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class KernelElfTest {
    @Test fun correctAbiIsAcceptedAndWrongOrTruncatedFilesAreRejected() {
        for ((abi, cls, machine) in listOf(Triple("arm64-v8a",2,183),Triple("armeabi-v7a",1,40),Triple("x86_64",2,62),Triple("x86",1,3))) {
            val file=File.createTempFile("kernel-test", ".so")
            try {
                val header=ByteArray(64);header[0]=127;header[1]=69;header[2]=76;header[3]=70
                header[4]=cls.toByte();header[5]=1;header[18]=machine.toByte()
                file.writeBytes(header);KernelElf.validate(file,abi)
                header[18]=0;file.writeBytes(header)
                assertTrue(runCatching { KernelElf.validate(file,abi) }.isFailure)
                file.writeBytes(byteArrayOf(127,69,76,70))
                assertTrue(runCatching { KernelElf.validate(file,abi) }.isFailure)
            } finally { file.delete() }
        }
    }
}
