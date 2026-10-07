package com.yunx.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.GitHubApi
import com.yunx.app.data.network.GitHubTokenStore
import com.yunx.app.data.network.TokenCheck
import kotlinx.coroutines.launch

@Composable
internal fun GitHubTokenDialog(onDismiss: () -> Unit, onChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val githubApi = remember { GitHubApi() }
        var tokenInput by rememberSaveable { mutableStateOf(GitHubTokenStore.getToken(context) ?: "") }
        var passwordVisible by remember { mutableStateOf(false) }
        // 校验失败提示（显示在输入框下方），修改输入即清除
        var tokenError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { onDismiss() },
            title = { Text("GitHub Token") },
            text = {
                Column {
                    Text(
                        text = "Token 仅用于提升 API 限额（匿名 60/小时，认证后 5000/小时）。经 Android Keystore AES-GCM 加密存储。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "如何获取 Token：",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "1. 电脑浏览器打开 GitHub，右上角头像 → Settings\n" +
                            "2. 左侧 Developer settings → Personal access tokens → Tokens (classic) → Generate new token\n" +
                            "3. 勾选 public_repo 即可浏览公开仓库；如需在主页看到自己的私有仓库，再勾选 repo\n" +
                            "4. 有效期建议选 90 天或 No expiration，生成后复制粘贴到上方输入框",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "安全提示：仅给最小权限，勿勾选删除/管理类权限；Token 不明文保存、不上传，清除只需清空后保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = {
                            tokenInput = it
                            tokenError = null
                        },
                        singleLine = true,
                        isError = tokenError != null,
                        label = { Text("Personal Access Token") },
                        supportingText = { tokenError?.let { Text(it) } },
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Outlined.Visibility
                                    else Icons.Outlined.VisibilityOff,
                                    contentDescription = if (passwordVisible) "隐藏" else "显示"
                                )
                            }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val input = tokenInput.trim()
                    if (input.isBlank()) {
                        // 清空即清除 Token（无需联网校验）
                        GitHubTokenStore.setToken(context, null)
                        onChanged(false)
                        onDismiss()
                        SnackbarController.show("已清除 GitHub Token")
                    } else {
                        // 保存前先校验：无效 Token 会让 GitHub 拒绝之后的所有请求（含公开仓库解析）
                        scope.launch {
                            when (val check = githubApi.validateToken(input)) {
                                is TokenCheck.Valid -> {
                                    GitHubTokenStore.setToken(context, input)
                                    onChanged(true)
                                    onDismiss()
                                    SnackbarController.show("GitHub Token 已保存（@${check.login}）")
                                }
                                TokenCheck.Invalid -> tokenError = "Token 无效或已过期，请重新生成后再保存"
                                TokenCheck.Unknown -> tokenError = "无法校验 Token（网络异常），请联网后重试"
                            }
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { onDismiss() }) { Text("取消") }
            }
        )
}
