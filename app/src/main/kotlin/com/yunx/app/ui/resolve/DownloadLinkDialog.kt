/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunx.app.ui.resolve

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.model.DownloadLink

/**
 * 下载确认弹窗。
 *
 * 不再展示或复制直链，仅保留文件信息与开始下载操作。
 */
@Composable
fun DownloadLinkDialog(
    link: DownloadLink,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,

        title = {
            Text(
                text = link.filename,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },

        text = {
            Column {
                Text(
                    text = "文件已准备好，可以开始下载。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (link.size > 0L) {
                    Text(
                        text = "大小：${formatFileSize(link.size)}",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 直链原样展示（长按可复制）：出问题时用户能自己核对是不是拿错了地址
                Text(
                    text = link.downloadUrl,
                    modifier = Modifier
                        .padding(12.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClickLabel = "复制下载链接",
                            onLongClick = {
                                copyToClipboard(context, link.downloadUrl)
                                SnackbarController.show("下载链接已复制")
                            }
                        ),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = {
                    copyToClipboard(context, link.downloadUrl)
                    SnackbarController.show("下载链接已复制")
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("复制下载链接")
                }
                Text(
                    text = "点击「开始下载」后，将保存到 Download 目录。",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },

        confirmButton = {
            Button(
                onClick = onDownload
            ) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )

                Spacer(
                    modifier = Modifier.width(6.dp)
                )

                Text("开始下载")
            }
        },

        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("关闭")
            }
        },

        modifier = modifier
    )
}

private fun formatFileSize(
    bytes: Long
): String {
    if (bytes <= 0L) {
        return "未知"
    }

    val units = arrayOf(
        "B",
        "KB",
        "MB",
        "GB",
        "TB"
    )

    var value = bytes.toDouble()
    var unit = 0

    while (
        value >= 1024.0 &&
        unit < units.lastIndex
    ) {
        value /= 1024.0
        unit++
    }

    return if (unit == 0) {
        "$bytes ${units[unit]}"
    } else {
        String.format(
            java.util.Locale.US,
            "%.2f %s",
            value,
            units[unit]
        )
    }
}
