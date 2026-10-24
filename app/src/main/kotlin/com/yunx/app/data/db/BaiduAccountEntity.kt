package com.yunx.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 百度网盘登录凭证（cookie 落库，后续所有 API 请求携带）。
 * 关键字段：BDUSS / STOKEN（WebView 登录后从 document.cookie 提取）。
 */
@Entity(tableName = "baidu_account")
data class BaiduAccountEntity(
    @PrimaryKey
    override val id: String = "baidu",
    val cookie: String = "",
    override val nickname: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    /** 是否该平台的主账号（多账号）：同平台只应有一条为 true，见 [MultiAccountEntity] */
    override val isPrimary: Boolean = false,
    /** 用户备注，便于区分同一平台的多个账号（空串 = 界面回退显示昵称） */
    override val remark: String = ""
) : MultiAccountEntity