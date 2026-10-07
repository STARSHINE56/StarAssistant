package com.yunx.app.data.backup
import com.yunx.app.data.db.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.*
import java.util.Base64
class AuthBackupCompatibilityTest {
    private inline fun <reified T> dao(): T {
        val state = MutableStateFlow<Any?>(null)
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            when(method.name) {
                "observeAccount" -> state
                "getAccount" -> state.value
                "upsert" -> { state.value = args!![0]; Unit }
                "clear" -> { state.value = null; Unit }
                else -> error(method.name)
            }
        } as T
    }
    @Test fun newFieldsRoundTripAndLegacyJsonKeepsAppLoginDefault() = runBlocking {
        val q = dao<QuarkAccountDao>(); val x = dao<XunleiAccountDao>()
        val gy = dao<GuangYaAccountDao>(); val il = dao<ILanzouAccountDao>(); val lz = dao<LanzouAccountDao>()
        val manager = AuthBackupManager(q,dao(),x,dao(),dao(),dao(),dao(),gy,il,lz)
        q.upsert(QuarkAccountEntity(cookie="old-cookie"))
        x.upsert(XunleiAccountEntity(accessToken="access",refreshToken="refresh",deviceId="device",captchaToken="captcha",authType="web"))
        gy.upsert(GuangYaAccountEntity(accessToken="gy-access",refreshToken="gy-refresh",deviceId="id",deviceSign="sign"))
        il.upsert(ILanzouAccountEntity(appToken="il-token",uuid="uuid",account="user",password="secret",userId="uid"))
        lz.upsert(LanzouAccountEntity(cookie="lz-cookie"))
        val backup = manager.export("test-password")
        assertFalse(backup.contains("secret")); assertFalse(backup.contains("old-cookie"))
        q.clear();x.clear();gy.clear();il.clear();lz.clear()
        assertEquals(5,manager.import(backup,"test-password"))
        assertEquals("old-cookie",q.getAccount()?.cookie)
        assertEquals("web",x.getAccount()?.authType);assertEquals("captcha",x.getAccount()?.captchaToken)
        assertEquals("sign",gy.getAccount()?.deviceSign);assertEquals("gy-refresh",gy.getAccount()?.refreshToken)
        assertEquals("secret",il.getAccount()?.password);assertEquals("uuid",il.getAccount()?.uuid)
        assertEquals("lz-cookie",lz.getAccount()?.cookie)
        assertEquals(1, manager.importJson("""{"app":"yunx_auth_backup","version":1,"accounts":[{"platform":"xunlei","accessToken":"legacy","refreshToken":"old"}]}"""))
        assertEquals("",x.getAccount()?.authType);assertEquals("legacy",x.getAccount()?.accessToken)
    }
    @Test fun oldV1EncryptedBackupIsReadableAndWrongPasswordFails() {
        val password="old-password"; val plain="{\"app\":\"yunx_auth_backup\",\"version\":1}"
        val salt=ByteArray(16){it.toByte()};val iv=ByteArray(12){(it+16).toByte()}
        val key = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(),salt,10000,256)).encoded,"AES")
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,GCMParameterSpec(128,iv))
        val payload="YUNX_AUTH_V1".toByteArray()+salt+iv+cipher.doFinal(plain.toByteArray())
        val old=Base64.getEncoder().encodeToString(payload)
        assertEquals(plain,AuthCrypto.decrypt(old,password))
        assertTrue(runCatching { AuthCrypto.decrypt(old,"incorrect") }.isFailure)
        payload[payload.lastIndex]=(payload.last().toInt() xor 1).toByte()
        assertTrue(runCatching { AuthCrypto.decrypt(Base64.getEncoder().encodeToString(payload),password) }.isFailure)
    }
}
