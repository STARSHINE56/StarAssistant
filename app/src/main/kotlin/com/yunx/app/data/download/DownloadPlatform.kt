package com.yunx.app.data.download

/**
 * 下载来源平台标识（用于按平台独立设置下载线程数）。
 * 字符串常量而非枚举：便于直接持久化到 Room 字段，也与各 ViewModel 解耦。
 */
object DownloadPlatform {
    const val QUARK = "quark"
    const val UC = "uc"
    const val XUNLEI = "xunlei"
    const val BAIDU = "baidu"
    const val C139 = "c139"
    const val PAN123 = "pan123"
    const val PAN115 = "pan115"
    /** 光鸭云盘 */
    const val GUANGYA = "guangya"
    /** 蓝奏云优享版 */
    const val ILANZOU = "ilanzou"
    /** 蓝奏云 */
    const val LANZOU = "lanzou"
    /** 磁力链接（BT）：只能交给 Gopeed 引擎，内置分片下载器不支持 */
    const val MAGNET = "magnet"
    /** GitHub 仓库/Release 下载 */
    const val GITHUB = "github"
    /** 通用/未知来源（手动添加、应用更新下载等） */
    const val GENERIC = "generic"
}
