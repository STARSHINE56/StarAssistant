package com.yunx.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yunx.app.data.db.BookmarkEntity
import com.yunx.app.data.network.GitHubLinkParser
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.ui.SnackbarController
import com.mikepenz.markdown.m3.Markdown
import com.yunx.app.ui.components.FileNameText
import com.yunx.app.ui.components.GitHubMarkdownImageTransformer
import com.yunx.app.ui.components.compactMarkdownTypography
import com.yunx.app.ui.resolve.DownloadLinkDialog
import com.yunx.app.ui.resolve.ShareDetailScreen
import com.yunx.app.ui.viewmodel.BaiduCloudViewModel
import com.yunx.app.ui.viewmodel.BookmarkViewModel
import com.yunx.app.ui.viewmodel.C139CloudViewModel
import com.yunx.app.ui.viewmodel.GuangYaCloudViewModel
import com.yunx.app.ui.viewmodel.Pan115CloudViewModel
import com.yunx.app.ui.viewmodel.Pan123CloudViewModel
import com.yunx.app.ui.viewmodel.QuarkCloudViewModel
import com.yunx.app.ui.viewmodel.ResolveUiState
import com.yunx.app.ui.viewmodel.ResolveViewModel
import com.yunx.app.ui.viewmodel.UCCoudViewModel
import com.yunx.app.ui.viewmodel.XunleiCloudViewModel
import com.yunx.app.ui.components.YunXLoading
import com.yunx.app.ui.theme.ThemeController
import com.yunx.app.ui.theme.effectsDefault
import com.yunx.app.ui.theme.effectsFast
import com.yunx.app.ui.theme.spatialDefault
import com.yunx.app.ui.theme.spatialFast

/** 主页快捷方式列数（4 列在窄屏也能放下两字标题，观感贴近桌面图标网格） */
private const val HOME_SHORTCUT_COLUMNS = 4

/**
 * 主页快捷方式区块：展示已「添加到主页」的收藏链接。
 *
 * 外层已是 verticalScroll，这里不能用 LazyVerticalGrid（同方向嵌套滚动会崩），
 * 因此用 chunked 手写行网格，末行用 Spacer 占位保证每个格子等宽。
 */
@Composable
internal fun HomeShortcutsSection(
    bookmarks: List<BookmarkEntity>,
    onOpen: (BookmarkEntity) -> Unit,
    onOpenBookmarks: () -> Unit,
    onRemove: (BookmarkEntity) -> Unit
) {
    // 待确认移除的快捷方式（长按触发，避免误触直接消失）
    var removing by remember { mutableStateOf<BookmarkEntity?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "快捷方式",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onOpenBookmarks) {
                Icon(
                    imageVector = Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "管理", style = MaterialTheme.typography.labelLarge)
            }
        }

        if (bookmarks.isEmpty()) {
            // 空态：引导到收藏页添加，避免这里只剩一片空白
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddToHomeScreen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "还没有主页快捷方式",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "在「收藏网盘链接」页长按一条收藏 → 添加到主页",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                bookmarks.chunked(HOME_SHORTCUT_COLUMNS).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowItems.forEach { bookmark ->
                            HomeShortcutTile(
                                bookmark = bookmark,
                                modifier = Modifier.weight(1f),
                                onClick = { onOpen(bookmark) },
                                onLongClick = { removing = bookmark }
                            )
                        }
                        // 末行不足一列时补空位，保证每个格子宽度一致
                        repeat(HOME_SHORTCUT_COLUMNS - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    removing?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("从主页移除") },
            text = {
                Text(
                    text = "「${bookmark.title.ifBlank { bookmark.link }}」将不再显示在主页快捷方式中",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(bookmark)
                        removing = null
                    }
                ) {
                    Text(text = "移除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) {
                    Text(text = "取消")
                }
            }
        )
    }
}

/** 主页快捷方式单个瓦片：圆角色块（平台简称）+ 标题，长按移除 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeShortcutTile(
    bookmark: BookmarkEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            val label = homeTileLabel(bookmark)
            if (label.isEmpty()) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                Text(
                    text = label,
                    // 字越多字号越小：4 个字（自定义上限）也能塞进 48dp 方块
                    style = when {
                        label.length <= 2 -> MaterialTheme.typography.titleMedium
                        label.length == 3 -> MaterialTheme.typography.labelLarge
                        else -> MaterialTheme.typography.labelSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        FileNameText(
            text = bookmark.title.ifBlank { bookmark.link },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
    }
}

/** 快捷方式色块最多显示几个字（标题截断、自定义文字都受它约束） */
internal const val HOME_LABEL_MAX_LENGTH = 4

/**
 * 快捷方式色块文字：自定义文字 > 标题前几个字 > 平台简称（标题为空时的兜底）。
 * 返回空串表示没有可显示的文字，调用处退回通用链接图标。
 * 收藏页的「自定义图标文字」弹窗也用它做占位提示，保证"自动文字"只有一个实现。
 */
internal fun homeTileLabel(bookmark: BookmarkEntity): String {
    val custom = bookmark.homeLabel.trim()
    if (custom.isNotEmpty()) return custom.take(HOME_LABEL_MAX_LENGTH)
    val title = bookmark.title.trim()
    if (title.isNotEmpty()) return title.take(HOME_LABEL_MAX_LENGTH)
    return (platformShortLabel(bookmark.platform) ?: "").take(HOME_LABEL_MAX_LENGTH)
}

/** 平台简称（色块兜底文字）；未知平台返回 null，调用处退回通用链接图标 */
private fun platformShortLabel(platform: String): String? = when (platform) {
    "QUARK" -> "夸克"
    "UC" -> "UC"
    "XUNLEI" -> "迅雷"
    "BAIDU" -> "百度"
    "C139" -> "移动"
    "PAN123" -> "123"
    "PAN115" -> "115"
    "GUANGYA" -> "光鸭"
    "ILANZOU" -> "优享"
    "LANZOU" -> "蓝奏"
    "GITHUB" -> "GitHub"
    else -> null
}

