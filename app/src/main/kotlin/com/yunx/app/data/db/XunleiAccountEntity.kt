package com.yunx.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 迅雷网盘登录凭证（access_token 落库，pan API 请求携带 Bearer）。
 */
@Entity(tableName = "xunlei_account")
data class XunleiAccountEntity(
    @PrimaryKey
    override val id: String = "xunlei",
    val accessToken: String = "",
    val refreshToken: String = "",
    val deviceId: String = "",
    val captchaToken: String = "",
    override val nickname: String = "",
    /**
     * 登录方式：空串 = App 通道（账号密码 / 短信，用 App OAuth 客户端刷新）；
     * [com.yunx.app.data.network.XunleiWebCredential.AUTH_TYPE] = 网页登录，
     * 刷新必须换成网页 OAuth 客户端（否则刷新必失败，用户会看到「刚登录就过期」）。
     */
    val authType: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    /** 是否该平台的主账号（多账号）：同平台只应有一条为 true，见 [MultiAccountEntity] */
    override val isPrimary: Boolean = false,
    /** 用户备注，便于区分同一平台的多个账号（空串 = 界面回退显示昵称） */
    override val remark: String = ""
) : MultiAccountEntity