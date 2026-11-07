package com.xingchen.desktop

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class Store(val directory: Path = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "XingChenAssistant")) {
    private val file = directory.resolve("state.dat")
    val data: JSONObject
    init {
        Files.createDirectories(directory)
        data = if (Files.exists(file)) JSONObject(String(unprotect(Files.readAllBytes(file)), Charsets.UTF_8)) else JSONObject()
    }
    private fun localKey(): ByteArray {
        val path = directory.resolve("local.key")
        if (!Files.exists(path)) {
            Files.write(path, ByteArray(32).also { SecureRandom().nextBytes(it) })
            runCatching { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        }
        return Files.readAllBytes(path)
    }
    private fun protect(bytes: ByteArray): ByteArray = if (Platform.isWindows()) Crypt32Util.cryptProtectData(bytes) else crypt(bytes, localKey(), true)
    private fun unprotect(bytes: ByteArray): ByteArray = if (Platform.isWindows()) Crypt32Util.cryptUnprotectData(bytes) else crypt(bytes, localKey(), false)
    @Synchronized fun save() {
        val temporary = directory.resolve("state.tmp")
        Files.write(temporary, protect(data.toString().toByteArray(Charsets.UTF_8)))
        try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
    }
    fun credentials(): JSONObject = data.optJSONObject("accounts") ?: JSONObject().also { data.put("accounts", it) }
    fun settings(): JSONObject = data.optJSONObject("settings") ?: JSONObject().also { data.put("settings", it) }
    fun credential(platform: String) = credentials().optJSONObject(platform)?.optString("credential").orEmpty()
    fun exportAccounts(path: Path, password: String) {
        require(password.length >= 8) { "备份密码至少 8 位" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        Files.write(path, salt + crypt(credentials().toString().toByteArray(Charsets.UTF_8), derive(password, salt), true))
    }
    fun importAccounts(path: Path, password: String) {
        val bytes = Files.readAllBytes(path)
        require(bytes.size > 44) { "认证备份无效" }
        val accounts = JSONObject(String(crypt(bytes.copyOfRange(16, bytes.size), derive(password, bytes.copyOfRange(0,16)), false), Charsets.UTF_8))
        data.put("accounts", accounts); save()
    }
    private fun derive(password: String, salt: ByteArray) = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(password.toCharArray(), salt, 210000, 256)).encoded
    private fun crypt(bytes: ByteArray, key: ByteArray, encrypt: Boolean): ByteArray {
        val iv = if (encrypt) ByteArray(12).also { SecureRandom().nextBytes(it) } else bytes.copyOfRange(0,12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key,"AES"), GCMParameterSpec(128,iv))
        val result = cipher.doFinal(if (encrypt) bytes else bytes.copyOfRange(12,bytes.size))
        return if (encrypt) iv + result else result
    }
}
