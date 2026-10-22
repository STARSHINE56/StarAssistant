package com.xingchen.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.yunx.app.ui.MainScreen
import com.yunx.app.ui.navigation.MainTab
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme

class MainActivity : ComponentActivity() {

    /**
     * 外部来源（目前是实况通知点击）请求直接打开的 Tab；null = 不指定，落在默认「解析」页。
     * 用 mutableStateOf 而不是 rememberSaveable：Compose 读它会自动重组，onNewIntent 里改它即可切页。
     */
    private val pendingTab = mutableStateOf<MainTab?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用内主题设置：始终深色/浅色时提前切换窗口主题，避免冷启动闪错背景色
        // （values-night 只跟随系统；应用内「始终深色」但系统浅色时，需显式使用深色窗口主题）
        val darkModePref = getSharedPreferences("yunx_settings", android.content.Context.MODE_PRIVATE)
            .getInt("dark_mode", 0)
        when (darkModePref) {
            1 -> setTheme(R.style.Theme_ComposeEmptyActivity_Light)
            2 -> setTheme(R.style.Theme_ComposeEmptyActivity_Dark)
        }
        super.onCreate(savedInstanceState)
        // ★ 调试构建开放 WebView 远程调试：真机上排查登录页问题（DevTools / chrome://inspect / CDP）
        //   只能靠它 —— 登录页的失败往往只发生在页面内部的 XHR 上，应用的加载回调看不到。
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        enableEdgeToEdge()
        // 实况通知点击进来：直接把初始 Tab 落成「下载」页
        pendingTab.value = tabFromIntent(intent)
        setContent {
            ComposeEmptyActivityTheme {
                MainScreen(
                    openTab = pendingTab.value,
                    onOpenTabConsumed = { pendingTab.value = null }
                )
            }
        }
    }

    // ★ 通知权限不再在启动时申请：统一收到引导页第 3 页（见 ui/screens/OnboardingPermissionPage.kt），
    //   之后只有设置页里的手动入口（「通知栏下载进度」）会再申请，避免每次启动都弹窗打扰。

    /**
     * 应用已在前台时再点实况通知：SINGLE_TOP 让系统把 intent 送到这里（而不是再起一个实例），
     * 更新 pendingTab 后由 MainScreen 消费并切到「下载」Tab。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTab.value = tabFromIntent(intent)
    }

    /** 实况通知（EXTRA_OPEN_TAB=download）→ 下载 Tab；其余情况不指定 Tab */
    private fun tabFromIntent(intent: Intent?): MainTab? =
        if (intent?.getStringExtra(EXTRA_OPEN_TAB) == TAB_DOWNLOAD) MainTab.Download else null

    companion object {
        /** 「点击后打开哪个 Tab」的 intent extra 键（由 DownloadService 的通知 contentIntent 写入） */
        const val EXTRA_OPEN_TAB = "com.yunx.app.extra.OPEN_TAB"

        /** [EXTRA_OPEN_TAB] 的取值：打开「下载」Tab */
        const val TAB_DOWNLOAD = "download"
    }
}