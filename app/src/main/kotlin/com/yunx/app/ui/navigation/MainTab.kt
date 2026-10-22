package com.yunx.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 主页底部导航的 4 个 Tab：解析 / 账号 / 下载 / 设置。
 *
 * ★ [Drive] 的枚举名不改（它承载的仍是云盘账号页，只是把「视频解析」平台的登录入口也收进了同一页，
 *   所以对外标题从「网盘」改成更准确的「账号」），避免改动 MainScreen 里所有 `MainTab.Drive` 引用。
 */
enum class MainTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    Resolve("解析", Icons.Filled.Link, Icons.Outlined.Link),
    Drive("账号", Icons.Filled.AccountCircle, Icons.Outlined.AccountCircle),
    Download("下载", Icons.Filled.Download, Icons.Outlined.Download),
    Settings("设置", Icons.Filled.Settings, Icons.Outlined.Settings)
}
