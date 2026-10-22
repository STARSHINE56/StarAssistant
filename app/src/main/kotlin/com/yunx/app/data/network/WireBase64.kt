package com.yunx.app.data.network

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** RFC 4648 encoding usable on API 23 and the JVM; no Android framework dependency. */
object WireBase64 {
    const val DEFAULT = 0
    const val NO_WRAP = 2
    const val NO_PADDING = 1
    const val URL_SAFE = 8
    fun decode(value: String, @Suppress("UNUSED_PARAMETER") flags: Int): ByteArray =
        value.decodeBase64()?.toByteArray() ?: throw IllegalArgumentException("Invalid Base64")
    fun encodeToString(value: ByteArray, @Suppress("UNUSED_PARAMETER") flags: Int): String =
        value.toByteString().base64()
}
