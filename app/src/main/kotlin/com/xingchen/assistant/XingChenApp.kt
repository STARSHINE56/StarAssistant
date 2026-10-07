package com.xingchen.assistant

import android.app.Application
import com.yunx.app.crash.CrashHandler

class XingChenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
        com.yunx.app.data.security.CredentialStore.installRecovery(this)
        com.yunx.app.data.network.Pan123DeviceId.install(this)
        com.yunx.app.data.announcement.AnnouncementApi.install(this)
        com.yunx.app.util.DiagnosticLog.install(this)
        val engine = com.yunx.app.data.gopeed.GopeedEngine
        engine.syncInstalledState(this)
        val settings = com.yunx.app.data.prefs.SettingsRepository(this)
        if (settings.downloadEngine == com.yunx.app.data.prefs.SettingsRepository.ENGINE_GOPEED && engine.isInstalled(this)) {
            Thread { runCatching { engine.start(this, engine.resolveDownloadDir(this)) } }.start()
        }
        Thread { runCatching { com.yunx.app.data.download.DownloadSaver.purgeOwnPendingFiles(this) } }.start()
        // 迅雷动态设备指纹：首次启动生成并持久化（开源分发后每台设备独立指纹）
        com.yunx.app.data.network.XunleiDeviceFingerprint.init(this)
    }
}