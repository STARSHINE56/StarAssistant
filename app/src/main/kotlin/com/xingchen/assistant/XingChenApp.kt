package com.xingchen.assistant

import android.app.Application
import com.yunx.app.crash.CrashHandler

class XingChenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
        com.yunx.app.data.security.CredentialStore.installRecovery(this)
        // 隐藏 WebView 解析通道：需要跟踪「当前前台 Activity」以便把隐藏 WebView 挂上窗口
        // （不挂窗口的话 requestAnimationFrame / visibilityState 会不对，页面初始化脚本可能卡住）
        com.yunx.app.data.network.video.HeadlessWebView.install(this)
        com.yunx.app.data.network.Pan123DeviceId.install(this)
        com.yunx.app.data.announcement.AnnouncementApi.install(this)
        com.yunx.app.util.DiagnosticLog.install(this)
        // 下载调试：读开关（默认关）。必须在任何下载开始之前装配，否则开关打开后先建的任务收不到事件
        com.yunx.app.data.download.DownloadDebugLog.install(this)
        // 链接唤起（深链接）：把用户设置刷到清单里那些 activity-alias 的启停状态。
        // 设置里改开关是即时同步的，这里兜安装后首次启动与「改过开关又重装/清数据」的对齐；
        // 任何异常都只记日志，绝不影响启动。
        runCatching { com.yunx.app.util.LinkOpener.syncAll(this) }
        val engine = com.yunx.app.data.gopeed.GopeedEngine
        engine.syncInstalledState(this)
        val settings = com.yunx.app.data.prefs.SettingsRepository(this)
        com.yunx.app.data.network.HttpClients.setProxy(
            settings.proxyHost.takeIf { settings.proxyEnabled && it.isNotBlank() }, settings.proxyPort)
        if (settings.downloadEngine == com.yunx.app.data.prefs.SettingsRepository.ENGINE_GOPEED && engine.isInstalled(this)) {
            Thread { runCatching { engine.start(this, engine.resolveDownloadDir(this)) } }.start()
        }
        Thread { runCatching { com.yunx.app.data.download.DownloadSaver.purgeOwnPendingFiles(this) } }.start()
        // 迅雷动态设备指纹：首次启动生成并持久化（开源分发后每台设备独立指纹）
        com.yunx.app.data.network.XunleiDeviceFingerprint.init(this)
    }
}