# 移植上游 YunX 1.3.0 功能到星辰助手（分支 feature/port-yunx-1.3.0）

基线：本地 `improvement/download-reliability`（含自己的下载可靠性重写 + 桌面图标切换）
目标：YunX v1.3.0（2026-10-18 发版）全部**功能**，保留星辰自身品牌与已有功能。

## 一、整份新增的文件（74 个，直接取自上游）

| 模块 | 文件 |
| --- | --- |
| 视频解析 | `data/network/video/`（VideoParser、VideoLinkParser、VideoModels、VideoHttp、HeadlessWebView、Douyin/Xhs/Bilibili/Kuaishou/Weibo/Acfun/Pixiv Parser、VideoParsers、VideoPlatform） |
| 视频账号 | `data/db/VideoAccountEntity.kt`、`VideoAccountDao.kt`、`data/repository/VideoAccountRepository.kt`、`VideoParseRepository.kt`、`ui/viewmodel/VideoAccountViewModel.kt`、`ui/login/VideoLoginScreen.kt`、`ui/screens/VideoAccountSection.kt`、`VideoAccountSheet.kt`、`VideoResultSection.kt` |
| 多账号 | `data/db/MultiAccountEntity.kt`、`ui/screens/MultiAccountDownloadScreen.kt`、`ui/viewmodel/MultiAccountViewModel.kt`、`data/repository/AccountDirectory.kt` |
| 下载调试 | `data/download/DownloadDebugLog.kt`、`data/download/DownloadSource.kt`、`ui/screens/DownloadDebugScreen.kt` |
| B 站 DASH 合轨 | `data/download/DashMuxer.kt` |
| 在线预览 | `ui/preview/`（MediaPreviewScreen、PreviewLauncher、PreviewTextLoader）、`ui/components/ExperimentalBadge.kt`、`OperationSheet.kt` |
| 深链接 | `data/prefs/LinkOpenTarget.kt`、`util/DeepLinks.kt`、`util/LinkOpener.kt`、`ui/screens/LinkOpenScreen.kt` |
| 平台图标 | `ui/items/PlatformLogos.kt` + `res/drawable/logo_*.png` |
| 主题/布局 | `ui/theme/WindowLayout.kt`、`ColorOffset.kt`(ColorContrast) |
| 测试 | `app/src/test/.../AccountSlotTest`、`StarSeaApiTest`、`ExpressiveLayoutTest`、`SnackbarEventQueueTest`、`DeepLinksTest`，`androidTest` 两个 |

## 二、整份替换为上游版本（41 个）

主要是网盘各平台的 DAO / Entity / Repository / AccountSheet / CloudScreen / LoginScreen / ViewModel
（上游给每个平台加了多账号两列 `isPrimary` / `remark`，本地这些文件原本就等于基线）。

## 三、三方合并（58 个顺利合并 + 20 个手改）

手改要点：

1. **AppDatabase**：版本 17 → 22。本地 v17 已通过 `Sync129Schema` 铺好了上游 17→19 的 schema，
   因此补两个**空迁移** `MIGRATION_17_18` / `MIGRATION_18_19`（否则 Room 找不到路径会抛
   IllegalStateException），再原样接入 `MIGRATION_19_20`（多账号两列 + `altSourcesJson`）、
   `MIGRATION_20_21`（`video_account` 表）、`MIGRATION_21_22`（`mergeAudioUrl` / `mergeAudioHeadersJson`）。
2. **DownloadManager**：见下节（本地重写 + 上游新增并行）。
3. **ChunkDownloader**：保留本地 `expectedTotal/expectedEtag/onCongestion`，并入上游 `contentDigest`
   （分片级 SHA-256 校验）。
4. **SettingsRepository**：本地 `announcementBaseUrl` 与上游 `downloadDebug` / `fullFileSha256` 并存。
5. **MainActivity / XingChenApp**（已改名到 `com.xingchen.assistant`）：补 `openTab/onOpenTabConsumed`
   + `onNewIntent` + `EXTRA_OPEN_TAB`（实况通知点开直接进下载页）、调试构建开放 WebView 远程调试、
   `HeadlessWebView.install` / `DownloadDebugLog.install` / `LinkOpener.syncAll`。
6. **CredentialCipher**：保留**本地**版本（本地已有 `CredentialKeyException.Unavailable` +
   `discardStaleEntry` + `notifyLoss` 全套硬化），不引入上游同名实现以免两套机制互相打架。
7. **SecureAccountDaos / GitHubTokenStore**：取上游（失败时**不删数据**、不抛异常）。
8. **OnboardingScreen**：改用上游三页引导（含第 3 页权限页 `OnboardingPermissionPage.kt`），
   换来「通知权限不在启动时骚扰」的行为；品牌串改成星辰助手，QQ 群入口因无群号自动隐藏。
9. **AboutScreen**：改用上游版（各平台官方 logo 胶囊 + 视频平台），品牌串改星辰助手。
10. **DownloadScreen**：`taskStatusLine` 合并（本地「等待中 · 等待其他任务」+ 上游磁力元数据文案），
    并改为 `internal` 供新的 `DownloadDebugScreen` 调用。
11. **DownloadLinkDialog**：保留本地大小/提示文案，加入上游「复制下载链接」按钮。
12. **strings.xml**：本地品牌串 + 上游 `process_text_action`。

## 四、新增依赖

`gradle/libs.versions.toml` + `app/build.gradle.kts`：`androidx.webkit`、`media3`(exoplayer/ui/hls)、
`coil3`(compose/network-okhttp)、`mp4parser`(B 站 DASH 合轨，纯 Java，不需要 ffmpeg)。

## 五、与上游刻意不同之处

- `minSdk` 保持 **23**（上游升到 24 并注明「勿降回 23」）。本地已有对应兼容处理，若 CI/用户反馈
  新代码在 23 上出问题，再单独评估是否跟进升 24。
- 版本号沿用本地 `VERSION_CODE` / `VERSION_NAME` 属性方案（默认 1001020 / 1.2.0）。
- 深链接的 activity-alias：上游 v1.3.0 **清单里也没有** `com.yunx.app.link.*` 的注册项，
  `LinkOpener` 只会在日志里报「组件类名对不上」。按上游原样移植，功能处于待接线状态——
  若要真正接管网盘链接，需要再补 13 个 activity-alias。
- 不引入上游的 `LaunchGate`、`ArchiveProbe`（防篡改/启动闸门）与 `UpdateSheet`（上游自有更新通道），
  星辰继续走自己的 COS 公告/更新链路。

## 六、验证

- 本机无 Android SDK（PRoot aarch64），无法本地编译，只能靠仓库现有 GitHub Actions
  「Test Build APK」做编译验证。
