package com.yunx.app.data.network

import java.security.MessageDigest
import java.util.UUID
import java.util.prefs.Preferences

/** Desktop counterpart to Android's SharedPreferences adapter. No shared fixed device ID. */
object XunleiDeviceFingerprint {
    private val prefs = Preferences.userRoot().node("com/xingchen/assistant/device")
    private fun stored(key: String): String = prefs.get(key, null) ?: UUID.randomUUID().toString().replace("-", "").also { prefs.put(key, it) }
    private val id = stored("device_id")
    private val peer = stored("peer_id")
    fun deviceId() = id
    fun peerId() = peer
    fun deviceSign(): String {
        fun hash(algorithm: String, value: String) = MessageDigest.getInstance(algorithm).digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        return "div101.$id" + hash("MD5", hash("SHA-1", id + "com.xunlei.downloadprovider" + "40" + "34a062aaa22f906fca4fefe9fb3a3021"))
    }
}
