package com.yunx.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 移动云盘（和彩云）登录凭证（mail.10086.cn / yun.139.com cookie 落库，后续 API 请求携带）。
 * @param authorization 网页版直接下发的 Authorization（§3.5.5，形如 "Basic cGM6..."），解析时直接用
 */
@Entity(tableName = "c139_account")
data class C139AccountEntity(
    @PrimaryKey
    override val id: String = "c139",
    val cookie: String = "",
    override val nickname: String = "",
    val authorization: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    /** 是否该平台的主账号（多账号）：同平台只应有一条为 true，见 [MultiAccountEntity] */
    override val isPrimary: Boolean = false,
    /** 用户备注，便于区分同一平台的多个账号（空串 = 界面回退显示昵称） */
    override val remark: String = ""
) : MultiAccountEntity
