package com.yunx.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 123 云盘登录凭证（JWT token 落库，后续 API 请求携带 Authorization: Bearer <token>）。
 * 凭证形态为 JWT（Bearer Token），两条登录路径产出的是同一种东西：
 * 网页登录后 localStorage 的 authorToken，或账号密码接口 sign_in 返回的 data.token（同源同形）。
 * JWT exp 约 90 天后过期，token 失效（code 非 0 或 401）时重新登录。
 */
@Entity(tableName = "pan123_account")
data class Pan123AccountEntity(
    @PrimaryKey
    val id: String = "pan123",
    /** Bearer JWT（ResolveViewModel.currentCredential 返回，作为 repository 的 cookie 参数） */
    val accessToken: String = "",
    /** 登录账号（账号密码登录时是手机号/邮箱；网页登录拿不到，留空后账号页回退显示昵称） */
    val account: String = "",
    val nickname: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)