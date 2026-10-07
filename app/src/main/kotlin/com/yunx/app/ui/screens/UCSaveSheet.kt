package com.yunx.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.rememberGlobalSnackbarHostState
import com.yunx.app.ui.resolve.BackToParentItem
import com.yunx.app.ui.resolve.CrumbBar
import com.yunx.app.ui.resolve.ShareFileRow
import com.yunx.app.ui.viewmodel.ResolveViewModel
import com.yunx.app.ui.viewmodel.UCCloudUiState
import com.yunx.app.ui.viewmodel.UCCoudViewModel
import com.yunx.app.ui.components.YunXLoading
import com.yunx.app.ui.theme.effectsDefault
import com.yunx.app.ui.theme.ListGroupGap
import com.yunx.app.ui.theme.effectsFast
import com.yunx.app.ui.theme.listGroupShape

/**
 * 「转存到UC网盘」步骤内容：浏览 UC 个人网盘目录（只进文件夹），确认后转存到当前目录。
 *
 * 不再是独立的底部弹窗 —— 它是解析页文件操作弹窗（`ResolveFileActionSheet`）里的二级步骤，
 * 头部（返回箭头 + 标题 + 文件名）由 [SaveStepScaffold] 提供；复用 UCCoudViewModel
 * 做目录浏览（与网盘页同一实例）。
 */
@Composable
internal fun UCSaveContent(
    resolveViewModel: ResolveViewModel,
    cloudViewModel: UCCoudViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val cloudState by cloudViewModel.uiState.collectAsState()
    val saving = resolveViewModel.isSaving
    val message = resolveViewModel.saveMessage

    LaunchedEffect(Unit) {
        cloudViewModel.loadRoot()
    }
// 转存结果提示
    LaunchedEffect(message) {
        if (message != null) {
            SnackbarController.show(message)
            resolveViewModel.consumeSaveMessage()
        }
    }

    // ModalBottomSheet 为独立窗口，需自带 Snackbar 宿主
    val snackbarHostState = rememberGlobalSnackbarHostState()

    SaveStepScaffold(
        title = "转存到UC网盘",
        subtitle = resolveViewModel.saveTarget?.fname ?: "",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            CrumbBar(
                rootTitle = "根目录",
                pathNames = (cloudState as? UCCloudUiState.Loaded)?.pathNames ?: emptyList(),
                onNavigate = { cloudViewModel.navigateToLevel(it) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 返回上一级：固定在目录区上方（与网盘移动弹窗一致）
            if ((cloudState as? UCCloudUiState.Loaded)?.pathNames?.isNotEmpty() == true) {
                BackToParentItem(onClick = { cloudViewModel.back() })
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 目录切换：淡入过渡（与网盘移动弹窗一致）
            AnimatedContent(
                targetState = cloudState,
                transitionSpec = { fadeIn(effectsDefault()) togetherWith fadeOut(effectsFast()) },
                label = "ucSaveState"
            ) { s ->
                when (s) {
                    is UCCloudUiState.Loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    YunXLoading()
                }

                is UCCloudUiState.Error -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { cloudViewModel.loadRoot() }) {
                            Text("重试")
                        }
                    }
                }

                is UCCloudUiState.Loaded -> {
                    val dirs = s.files.filter { it.isdir }
                    if (dirs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "当前目录没有子文件夹，可直接转存到此目录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(ListGroupGap)
                        ) {
                            // 目录列表拼成一组：首/末项大圆角、中间项小圆角
                            itemsIndexed(dirs, key = { _, d -> d.fid }) { index, dir ->
                                ShareFileRow(
                                    file = dir,
                                    shape = listGroupShape(index, dirs.size),
                                    onClick = { cloudViewModel.openFolder(dir) }
                                )
                            }
                        }
                    }
            }
            }
            }

            Spacer(modifier = Modifier.height(20.dp))

            val currentDirName =
                (cloudState as? UCCloudUiState.Loaded)?.pathNames?.lastOrNull() ?: "根目录"
            Button(
                onClick = {
                    val dirId = (cloudState as? UCCloudUiState.Loaded)?.dirFid ?: "0"
                    resolveViewModel.saveToCloud(dirId)
                },
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("转存到此目录（$currentDirName）")
                }
            }

            // 转存结果提示（ModalBottomSheet 为独立窗口，需自带 Snackbar 宿主）
            SnackbarHost(hostState = snackbarHostState)
        }
    }
}
