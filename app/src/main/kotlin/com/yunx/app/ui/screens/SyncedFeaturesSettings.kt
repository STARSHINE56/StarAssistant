package com.yunx.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.data.network.HttpClients
import com.yunx.app.ui.theme.ThemeController
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.util.DiagnosticLog
import com.yunx.app.util.LogExporter
import com.yunx.app.ui.SnackbarController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SyncedFeaturesSettings(onEngineClick: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val settingsRepo = settings
    var githubMirror by remember { mutableStateOf(settings.githubMirrorPrefix) }
    var showMirrorDialog by remember { mutableStateOf(false) }
    var showProxyDialog by remember { mutableStateOf(false) }
    var proxyEnabled by remember { mutableStateOf(settings.proxyEnabled) }
    var proxyHost by remember { mutableStateOf(settings.proxyHost) }
    var proxyPort by remember { mutableStateOf(settings.proxyPort.toString()) }
    var noSave by remember { mutableStateOf(settings.quarkNoSaveDownload) }
    var diagnostic by remember { mutableStateOf(settings.diagnosticMode) }
    var sourceDialog by remember { mutableStateOf(false) }
    var announcementSource by remember { mutableStateOf(settings.announcementBaseUrl) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onEngineClick) { Text("下载引擎：内置 / Gopeed（磁力下载）") }
            Row(Modifier.fillMaxWidth()) {
                Text("夸克免转存下载", Modifier.weight(1f))
                Switch(noSave, { noSave = it; settings.quarkNoSaveDownload = it })
            }
            Text("开启后优先通过分享凭证取链，失败时回退转存。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth()) {
                Text("诊断模式（模块日志）", Modifier.weight(1f))
                Switch(diagnostic, { diagnostic = it; settings.diagnosticMode = it; DiagnosticLog.setEnabled(context, it) })
            }
            TextButton(onClick = { scope.launch {
                val file = withContext(Dispatchers.IO) { LogExporter.exportDiagnosticZip(context) }
                if (file != null) LogExporter.share(context, file) else SnackbarController.show("暂无诊断日志，请开启诊断模式后复现问题")
            } }) { Text("导出诊断日志") }
            Row(Modifier.fillMaxWidth()) {
                Text("剪贴板自动识别", Modifier.weight(1f))
                Switch(ThemeController.clipboardSuggestEnabled, { ThemeController.setClipboardSuggestEnabled(context, it) })
            }
            Row(Modifier.fillMaxWidth()) {
                Text("接收预发布更新", Modifier.weight(1f))
                Switch(ThemeController.acceptPrereleaseUpdate, { ThemeController.setAcceptPrereleaseUpdate(context, it) })
            }
            TextButton(onClick = { showMirrorDialog = true }) { Text("GitHub 下载镜像") }
            TextButton(onClick = { showProxyDialog = true }) { Text("网络代理") }
            TextButton(onClick = { sourceDialog = true }) { Text(if (announcementSource.isBlank()) "公告来源：官方 COS" else "公告来源：已配置") }
        }
    }
    Spacer(Modifier.height(6.dp))
    if (sourceDialog) {
        var input by remember { mutableStateOf(announcementSource) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { sourceDialog = false }, title = { Text("星辰助手公告来源") }, text = {
            Column {
                Text("默认使用官方 COS，无需填写。可选填 HTTPS JSON 地址或兼容 API 地址；留空恢复官方来源，保存后重启生效。")
                OutlinedTextField(input, { input = it; error = null }, label = { Text("HTTPS 地址") }, isError = error != null, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = {
            runCatching { settings.announcementBaseUrl = input }.onSuccess {
                announcementSource = settings.announcementBaseUrl; sourceDialog = false
                SnackbarController.show("公告来源已保存，重启应用后生效")
            }.onFailure { error = it.message }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { sourceDialog = false }) { Text("取消") } })
    }
    // GitHub 下载镜像前缀设置弹窗（留空 = 使用内置默认镜像）
    if (showMirrorDialog) {
        // 弹窗内临时输入：打开时带出当前已保存的自定义前缀（无则空）
        var mirrorInput by remember { mutableStateOf(githubMirror ?: "") }
        AlertDialog(
            onDismissRequest = { showMirrorDialog = false },
            title = { Text("GitHub 下载镜像") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = mirrorInput,
                        onValueChange = { mirrorInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("镜像前缀 URL") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true
                    )
                    Text(
                        text = "留空使用默认镜像 ${UpdateChecker.MIRROR_PREFIX}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { mirrorInput = "" }) {
                        Text("恢复默认")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val raw = mirrorInput.trim()
                        if (raw.isBlank()) {
                            // 空：恢复内置默认镜像
                            settingsRepo.githubMirrorPrefix = null
                            githubMirror = null
                            showMirrorDialog = false
                            SnackbarController.show("已恢复默认镜像")
                        } else if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                            // 必须是 http/https 开头，否则报错不保存
                            SnackbarController.show("镜像前缀需以 http:// 或 https:// 开头")
                        } else {
                            // 规范化：统一以 / 结尾，拼接原直链时不会粘连
                            val normalized = if (raw.endsWith("/")) raw else "$raw/"
                            settingsRepo.githubMirrorPrefix = normalized
                            githubMirror = normalized
                            showMirrorDialog = false
                            SnackbarController.show("GitHub 镜像已更新")
                        }
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showMirrorDialog = false }) { Text("取消") }
            }
        )
    }

    // 网络代理设置弹窗（HTTP 代理；未启用时直连）
    if (showProxyDialog) {
        // 弹窗内临时变量：取消时不回写已保存值
        var tempEnabled by remember { mutableStateOf(proxyEnabled) }
        var tempHost by remember { mutableStateOf(proxyHost) }
        var tempPort by remember { mutableStateOf(proxyPort) }
        AlertDialog(
            onDismissRequest = { showProxyDialog = false },
            title = { Text("网络代理") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "启用代理",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = tempEnabled, onCheckedChange = { tempEnabled = it })
                    }
                    OutlinedTextField(
                        value = tempHost,
                        onValueChange = { tempHost = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("代理主机地址（如 127.0.0.1）") },
                        singleLine = true,
                        enabled = tempEnabled
                    )
                    OutlinedTextField(
                        value = tempPort,
                        onValueChange = { tempPort = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("代理端口（如 7890）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = tempEnabled
                    )
                    Text(
                        text = "代理用于加速 GitHub 等海外资源；不启用时所有请求直连。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (tempEnabled) {
                            val host = tempHost.trim()
                            val port = tempPort.toIntOrNull()
                            when {
                                // 校验失败仅提示，不关闭弹窗
                                host.isBlank() ->
                                    SnackbarController.show("请填写代理主机地址")
                                port == null || port !in 1..65535 ->
                                    SnackbarController.show("代理端口需为 1-65535 之间的数字")
                                else -> {
                                    settingsRepo.proxyEnabled = true
                                    settingsRepo.proxyHost = host
                                    settingsRepo.proxyPort = port
                                    HttpClients.setProxy(host, port)
                                    proxyEnabled = true
                                    proxyHost = host
                                    proxyPort = port.toString()
                                    showProxyDialog = false
                                    SnackbarController.show("代理已启用：$host:$port")
                                }
                            }
                        } else {
                            // 关闭代理：恢复直连
                            settingsRepo.proxyEnabled = false
                            HttpClients.setProxy(null, 0)
                            proxyEnabled = false
                            showProxyDialog = false
                            SnackbarController.show("已关闭代理，恢复直连")
                        }
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showProxyDialog = false }) { Text("取消") }
            }
        )
    }
}
