package com.yunx.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.ui.rememberGlobalSnackbarHostState
import com.yunx.app.ui.viewmodel.DownloadViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 使用下载任务表中的完成记录；删除记录默认保留已下载文件。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadHistoryScreen(viewModel: DownloadViewModel, onBack: () -> Unit) {
    val tasks by viewModel.tasks.collectAsState()
    val completed = tasks.filter { it.status == DownloadTaskEntity.STATUS_COMPLETED }
        .sortedByDescending { if (it.completedTime > 0) it.completedTime else it.createTime }
    val context = LocalContext.current
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    val snackbarHostState = rememberGlobalSnackbarHostState()
    var pendingDelete by remember { mutableStateOf<DownloadTaskEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("下载历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (completed.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text("清空历史") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        if (completed.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Outlined.History, contentDescription = null)
                Text("暂无已完成的下载", modifier = Modifier.padding(top = 12.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(completed, key = { it.id }) { task ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(task.fileName, style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val size = if (task.totalSize > 0) task.totalSize else task.downloadedSize
                            val timeLabel = if (task.completedTime > 0) {
                                "完成于 ${SimpleDateFormat("yyyy-MM-dd HH:mm", locale).format(Date(task.completedTime))}"
                            } else {
                                "完成时间未知（创建于 ${SimpleDateFormat("yyyy-MM-dd HH:mm", locale).format(Date(task.createTime))}）"
                            }
                            Text(
                                "${android.text.format.Formatter.formatFileSize(context, size)} · $timeLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { openSavedFile(context, task.savePath) },
                                    enabled = task.savePath.isNotBlank()) {
                                    Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("打开")
                                }
                                TextButton(onClick = { viewModel.redownload(task) }) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("重新下载")
                                }
                                IconButton(onClick = { pendingDelete = task }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "删除记录")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除下载记录？") },
            text = { Text("仅删除历史记录，已下载的文件会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.remove(task.id)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空下载历史？") },
            text = { Text("所有已完成的下载记录将被删除，已下载的文件会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearCompleted()
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }
}
