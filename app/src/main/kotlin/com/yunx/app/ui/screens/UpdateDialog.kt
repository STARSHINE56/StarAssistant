package com.yunx.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yunx.app.data.update.UpdateChecker

@Composable
fun UpdateDialog(
    currentVersion: String,
    release: UpdateChecker.Release,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onIgnore: () -> Unit,
    downloading: Boolean = false,
    onDownloadMirror: (() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onLater,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 24.dp,
                        vertical = 24.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                UpdateIcon()

                Spacer(
                    modifier = Modifier.height(16.dp)
                )

                Text(
                    text = release.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(
                    modifier = Modifier.height(6.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = release.tagName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(
                        modifier = Modifier.width(8.dp)
                    )

                    Text(
                        text = "当前 $currentVersion",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (release.publishedAt.isNotBlank()) {
                    Text("发布日期：${release.publishedAt.take(10)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(22.dp))

                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "更新内容",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(
                                min = 120.dp,
                                max = 260.dp
                            ),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Text(
                            text = formatReleaseNotes(
                                release.body
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(
                                    rememberScrollState()
                                )
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                if (UpdateChecker.hasDownload(release)) Button(
                    onClick = onDownload,
                    enabled = !downloading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (downloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )

                        Spacer(
                            modifier = Modifier.width(8.dp)
                        )

                        Text("下载中…")
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )

                        Spacer(
                            modifier = Modifier.width(8.dp)
                        )

                        Text("下载更新")
                    }
                }

                if (onDownloadMirror != null) {
                    Spacer(
                        modifier = Modifier.height(10.dp)
                    )

                    OutlinedButton(
                        onClick = onDownloadMirror,
                        enabled = !downloading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )

                        Spacer(
                            modifier = Modifier.width(8.dp)
                        )

                        Text("使用镜像站下载")
                    }
                }

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onIgnore,
                        enabled = !downloading
                    ) {
                        Text(
                            text = "忽略本次",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    TextButton(
                        onClick = onLater,
                        enabled = !downloading
                    ) {
                        Text("稍后")
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateIcon() {
    Surface(
        modifier = Modifier.size(56.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.SystemUpdate,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

private fun formatReleaseNotes(
    raw: String
): String {
    if (raw.isBlank()) {
        return "暂无更新说明"
    }

    val headingRegex =
        Regex("""^\s*#{1,6}\s+""")

    val bulletRegex =
        Regex("""^\s*[-*+]\s+""")

    val linkRegex =
        Regex("""\[([^\]]+)]\([^)]+\)""")

    var result = raw
        .replace(
            "\r\n",
            "\n"
        )
        .lineSequence()
        .map { original ->
            var line = original.trimEnd()

            line = when {
                headingRegex.containsMatchIn(line) -> {
                    headingRegex.replaceFirst(
                        line,
                        ""
                    )
                }

                bulletRegex.containsMatchIn(line) -> {
                    "• " + bulletRegex.replaceFirst(
                        line,
                        ""
                    )
                }

                else -> line
            }

            line
        }
        .joinToString("\n")

    result = result
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")

    result = linkRegex.replace(
        result
    ) { match ->
        match.groupValues[1]
    }

    result = result.replace(
        Regex("""\n{3,}"""),
        "\n\n"
    )

    return result.trim()
        .ifBlank {
            "暂无更新说明"
        }
}
