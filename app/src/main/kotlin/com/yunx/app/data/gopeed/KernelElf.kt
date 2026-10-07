package com.yunx.app.data.gopeed

import java.io.File

/** Validate the ABI and ELF header before replacing an installed native kernel. */
internal object KernelElf {
    fun validate(file: File, abi: String) {
        require(file.length() >= 64) { "内核文件不完整" }
        val header = file.inputStream().use { input -> ByteArray(20).also { require(input.read(it) == it.size) } }
        require(header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))) { "内核不是有效的 ELF 文件" }
        require(header[5].toInt() == 1) { "内核字节序不支持" }
        val (elfClass, machine) = when (abi) {
            "arm64-v8a" -> 2 to 183
            "armeabi-v7a" -> 1 to 40
            "x86_64" -> 2 to 62
            "x86" -> 1 to 3
            else -> error("不支持的内核架构：$abi")
        }
        val actualMachine = (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
        require(header[4].toInt() == elfClass && actualMachine == machine) { "内核架构与设备不匹配：$abi" }
    }
}
