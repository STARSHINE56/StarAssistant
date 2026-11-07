package com.yunx.app.data.security

import android.content.Context
import android.util.Base64
import com.yunx.app.util.DiagnosticLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本机密钥不可用的两种口径。
 *
 * ★ 上层**必须区分**这两种（见 [CredentialStore.isKeyLost]）：[PermanentlyInvalid] 代表密文
 *   永远解不开了；[Unavailable] 只是**此刻**拿不到键，按「丢数据」处理会把用户本来还能读的
 *   账号白白废掉。
 *
 * ★ 上游 v1.3.1 / #208 起凭证改为**明文存储**，本类型不再由写入路径抛出；保留它是因为
 *   迁移期仍有「旧 `yunx:v1:` 密文读不出来」这条路径，数据层（`SecureAccountDaos` /
 *   `DownloadManager` / `GitHubTokenStore`）与界面层需要**一套统一**的故障判定口径。
 */
internal sealed class CredentialKeyException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * Keystore 里的条目**永久不可用**：查无此键（`Key not found`）/ 密钥 blob 无法解密
     * （`Invalid key blob`）/ 被系统永久作废（`KeyPermanentlyInvalidatedException`，典型诱因是
     * **用户改了锁屏密码或指纹**、OEM 的 keystore 升级）。
     *
     * 迁移期含义：磁盘上那条旧密文**永远**解不开了（旧密钥已经不在），只能让用户重新登录；
     * 但**不要**删数据 —— 备份导入 / 换机后仍可能救回来。
     */
    class PermanentlyInvalid(reason: String, cause: Throwable? = null) :
        CredentialKeyException("本机密钥不可用：$reason", cause)

    /**
     * Keystore 条目**本身完好，只是暂时取不到**：设备还处于「直接启动」未解锁状态等。
     * 此时**绝不能**按「读不出来」清数据，下次读取（或设备解锁后）就能恢复。
     */
    class Unavailable(reason: String, cause: Throwable? = null) :
        CredentialKeyException("本机密钥暂时不可用：$reason", cause)
}

/**
 * 凭证密钥的「丢了没丢」判定 + 丢失通知。
 *
 * 抽成独立对象是因为判定异常的地方不止加解密本身（还有各 DAO 的兜底），
 * 而通知必须**跨层**（数据层失钥 → UI 层告知用户），这里用一次性标记 + 启动时补发的方式解耦。
 *
 * ★ 合并说明（上游 #208 明文改造）：写入不再经过 Keystore，**不会**再有「密钥被重建」这件事，
 *   所以 [installRecovery] 不再挂 `onKeyProvisioned` 监听，只记录 application context；
 *   其余成员（[isKeyLost] / [markKeyLost] / [hasKeyLostNotice] / [consumeKeyLostNotice] /
 *   [LOST_TITLE] / [LOST_MESSAGE]）保持星辰既有签名不变 —— `XingChenApp`、`MainScreen`、
 *   `DownloadManager` 仍在调用，删掉会直接编不过。
 */
object CredentialStore {

    private const val PREFS = "yunx_settings"

    /** 一次性标记：本机密钥已丢失、凭证已清空，等界面弹一次提示。 */
    private const val KEY_LOST_FLAG = "credential_key_lost"

    /** [installRecovery] 记下的 application context：数据层深处（DAO）没有 Context 也能记标记 */
    @Volatile
    private var appContext: Context? = null

    internal const val LOST_TITLE = "登录状态已失效"
    internal const val LOST_MESSAGE =
        "本机加密密钥不可用（常见于修改锁屏密码、指纹或系统升级后），已保存的网盘登录凭证与 " +
            "GitHub Token 无法再解密，需要重新登录。\n\n" +
            "如果之前导出过认证备份，可在「设置 → 认证备份」里导入恢复。"

    /** 该异常是否属于「本机密钥故障」（区别于密文损坏、格式不支持等） */
    internal fun isKeyFailure(error: Throwable): Boolean = error is CredentialKeyException

    /**
     * 该异常是否代表**这条凭证已经彻底读不回来了**（密钥条目永久失效）。
     *
     * ★ 只有它为 true 时才允许清数据：`Unavailable`（Keystore 暂时进不去）时清掉等于
     *   把用户本来还能解开的账号白白作废，下次设备解锁后就没有了。
     */
    internal fun isKeyLost(error: Throwable): Boolean =
        error is CredentialKeyException.PermanentlyInvalid

    /** 记一次性标记（数据层用：Context 由 [installRecovery] 记着；未装配时静默跳过） */
    internal fun markKeyLost() {
        appContext?.let { markKeyLost(it) }
    }

    /** 记一次性标记：界面起来后弹一次提示 */
    internal fun markKeyLost(context: Context) {
        runCatching {
            // ★ 用 commit() 而不是 apply()：调用方可能刚写完就在这里读到（同一线程内先写后读），
            //   异步落盘会读到旧值，导致「密钥失效」这一屏不弹提示。
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LOST_FLAG, true)
                .commit()
        }
    }

    /** 启动时检查是否有未消费的「密钥失效」提示 */
    fun hasKeyLostNotice(context: Context): Boolean = runCatching {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOST_FLAG, false)
    }.getOrDefault(false)

    /** 提示已展示（或用户已确认），清标记 */
    fun consumeKeyLostNotice(context: Context) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LOST_FLAG)
                .commit()
        }
    }

    /**
     * 装配数据层的 application context（`YunXApp.onCreate` / `AppDatabase` 装配时调用）。
     *
     * ★ 明文改造后没有「密钥被重建」这回事，所以这里不再注册任何监听：重复调用只是覆盖
     *   同一个 context 引用，幂等。签名保持星辰原样，调用点无需改动。
     */
    fun installRecovery(context: Context) {
        appContext = context.applicationContext
    }
}

/**
 * 凭证读写的统一入口。
 *
 * ★ 2026-10 起凭证**不再加密**：Android Keystore 的字段级加密带来过真实事故
 *   （改锁屏密码 / 指纹会让密钥被系统永久作废，历史上还因误判「条目损坏」删掉好密钥，
 *   导致六家网盘账号当场全丢），收益与代价不成比例，因此改为明文存储。
 */
internal interface CredentialCipher {
    fun encrypt(plaintext: String, purpose: String): String
    fun decrypt(stored: String, purpose: String): String
    fun isEncrypted(stored: String): Boolean

    /**
     * 注册「密钥是**新建的**」监听 —— 明文实现下**没有密钥可建**，因此永远不会回调。
     *
     * ★ 仅作为兼容保留：星辰侧的历史调用约定里有这个方法，留一个默认空实现，
     *   免得任何一处旧调用点（以及后续同步上游时的注释引用）编译不过。
     */
    fun onKeyProvisioned(listener: () -> Unit) {
        // no-op：明文存储不涉及 Keystore 密钥的生成与替换
    }
}

/**
 * 明文实现：写入直通、读取兼容历史密文。
 *
 * ★ 写入：原样返回，不再做任何加密。
 * ★ 读取：若读到的是旧版本留下的 `yunx:v1:` 密文，用**旧 Keystore 密钥**解一次，
 *   调用方拿到明文后会以明文回写覆盖（见 `SecureAccountDaos` / `DownloadManager` /
 *   `GitHubTokenStore`），老用户无需重新登录即可平滑过渡。
 * ★ 这条旧密钥路径**只读**：绝不 `generateKey()`、绝不 `deleteEntry()` —— 任何「自愈/重建」
 *   都可能把用户还能读的凭证永久作废，那正是本次改动要根除的行为。
 *
 * ★ 星辰侧兼容（本分支合并要点）：星辰 1.2.x 用的就是同一条旧路径 ——
 *   别名 `yunx.account.credentials.v1`、`AES/GCM/NoPadding`、AAD = `purpose`、
 *   `yunx:v1:<base64(iv)>:<base64(ciphertext)>`。参数完全一致，因此星辰已存的加密凭证
 *   也能被这里原样解开、随后由调用方明文回写，**不需要**再保留一份「加密写入」的实现。
 */
internal class PlaintextCredentialCipher : CredentialCipher {

    override fun encrypt(plaintext: String, purpose: String): String = plaintext

    override fun isEncrypted(stored: String): Boolean = stored.startsWith("$PREFIX:")

    override fun decrypt(stored: String, purpose: String): String {
        if (!isEncrypted(stored)) return stored
        // 诊断日志：这条路径只在升级后首次读到旧数据时走一次（只记用途与长度，不记内容）
        DiagnosticLog.log(
            DiagnosticLog.CRYPTO, "decrypt-legacy", size = stored.length.toLong(),
            summary = "purpose=$purpose"
        )
        val parts = stored.split(':', limit = 4)
        require(parts.size == 4 && parts[0] == "yunx" && parts[1] == "v1") {
            "Unsupported encrypted credential format"
        }
        val iv = Base64.decode(parts[2], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[3], Base64.NO_WRAP)
        val aad = purpose.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, legacyKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    /**
     * 取旧版本的 Keystore 密钥（只读）。
     *
     * ★ 失败按 [CredentialKeyException] 的口径抛出，让上层能区分「暂时取不到」和「永远解不开」：
     *   - [CredentialKeyException.Unavailable]：Keystore 本身进不去（设备未解锁 / 服务抖动），
     *     下次读取就能恢复，上层**必须**保留原数据；
     *   - [CredentialKeyException.PermanentlyInvalid]：条目已不存在或 blob 无法解密，
     *     这条旧密文永远解不开 —— 上层据此记「登录状态已失效」提示（见 `DownloadManager`），
     *     但**依然不清库**：备份导入 / 换机后仍可能救回来。
     */
    private fun legacyKey(): SecretKey {
        val keyStore = try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }
        } catch (error: Throwable) {
            throw CredentialKeyException.Unavailable("AndroidKeystore 不可用", error)
        }
        val key = try {
            keyStore.getKey(KEY_ALIAS, null)
        } catch (error: Throwable) {
            throw CredentialKeyException.PermanentlyInvalid("旧密钥条目无法读取", error)
        }
        return key as? SecretKey
            ?: throw CredentialKeyException.PermanentlyInvalid("旧密钥条目已不存在，无法解密历史凭证")
    }

    companion object {
        /** 旧密文前缀：`yunx:v1:<base64(iv)>:<base64(ciphertext)>` */
        private const val PREFIX = "yunx:v1"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "yunx.account.credentials.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        /**
         * ★ **全进程唯一实例**：数据库 DAO、GitHub Token、下载请求头三处共用，保证
         *   「读到旧密文 → 第一次就识破」的判断口径一致。
         */
        val shared: CredentialCipher by lazy { PlaintextCredentialCipher() }
    }
}
