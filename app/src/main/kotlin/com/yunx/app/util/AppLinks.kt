/*
 * 星辰助手 (StarAssistant) - 网盘与视频链接解析、在线预览、高速下载。
 *
 * 本文件遵循 GNU AGPL-3.0 协议分发。
 */

package com.yunx.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 应用对外的联系方式与仓库地址（引导页 / 设置页 / 关于页共用）。
 *
 * ★ 为什么要集中放：同一个仓库地址会在多个界面出现，就地写死的话改一处漏一处，
 *   所以对外入口一律从这里取常量。新增对外入口时先往这里加常量，再到界面里引用。
 * ★ 星辰助手不设 QQ 群：[QQ_GROUP] 留空，界面据此隐藏该入口（见 OnboardingScreen 的 QQGroupPill）。
 */
object AppLinks {

    /** 官方源码仓库 */
    const val GITHUB_REPO = "https://github.com/STARSHINE56/StarAssistant"

    /** 仓库地址的展示形式（去掉协议前缀，窄屏放得下） */
    const val GITHUB_REPO_DISPLAY = "github.com/STARSHINE56/StarAssistant"

    /** QQ 交流群号：星辰助手暂不设群，留空以隐藏入口 */
    const val QQ_GROUP = ""

    /** 打开源码仓库（交给系统浏览器；没有可用浏览器时返回 false） */
    fun openRepo(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO)))
        true
    }.getOrDefault(false)

    /** 拉起 QQ 群卡片；未安装 QQ 或未配置群号时返回 false（调用方负责退回复制群号） */
    fun openQQGroup(context: Context): Boolean {
        if (QQ_GROUP.isBlank()) return false
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(qqGroupScheme()))
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** 用于拉起 QQ 群卡片的 scheme（mqqapi://card/show_pslcard?src_type=internal&uin=<群号>） */
    fun qqGroupScheme(): String =
        "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$QQ_GROUP&card_type=group&source=qrcode"
}
