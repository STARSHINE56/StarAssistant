package com.yunx.app.data.repository

import com.yunx.app.data.db.C139AccountDao
import com.yunx.app.data.db.C139AccountEntity
import com.yunx.app.data.db.resolveAccountSlot
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.C139Constants
import kotlinx.coroutines.flow.Flow

/**
 * 移动云盘账号数据仓库：Room 持久化 + Cookie 校验。
 * 登录态 = mail.10086.cn 的 Os_SSo_Sid + RMKEY（WebView 登录后提取）。
 */
class C139AccountRepository(
    private val dao: C139AccountDao
) {

    fun observeAccount(): Flow<C139AccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): C139AccountEntity? = dao.getAccount()

    /** 该平台全部账号的 Cookie（主账号在前）：多账号下载（实验性 C1）轮换取链用 */
    suspend fun allCredentials(): List<String> =
        dao.getAccounts().map { it.cookie }.filter { it.isNotBlank() }

    /**
     * 校验 139 Cookie 有效性（Os_SSo_Sid+RMKEY 或 authorization 任一成立）；
     * 有效则提取账号与 authorization 并落库，返回 true。
     * @param newAccount true = 网盘页「添加账号」进来的，落成新的一行；false = 覆盖当前主账号那一行
     */
    suspend fun saveC139Account(cookie: String, newAccount: Boolean = false): Boolean {
        if (!C139Constants.isValidCookie(cookie)) return false
        val nickname = C139Constants.extractAccount(cookie) ?: "139用户"
        val authorization = C139Constants.extractAuthorization(cookie).orEmpty()
        val slot = resolveAccountSlot(DownloadPlatform.C139, dao.getAccount(), newAccount)
        dao.upsert(
            C139AccountEntity(
                id = slot.id,
                cookie = cookie,
                nickname = nickname,
                authorization = authorization,
                isPrimary = slot.isPrimary,
                remark = slot.remark
            )
        )
        return true
    }
}