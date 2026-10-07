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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.prefs.ResolveHistoryItem
import com.yunx.app.data.prefs.ResolveHistoryRepository
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.resolve.DownloadLinkDialog
import com.yunx.app.ui.resolve.ShareDetailScreen
import com.yunx.app.ui.viewmodel.BaiduCloudViewModel
import com.yunx.app.ui.viewmodel.C139CloudViewModel
import com.yunx.app.ui.viewmodel.Pan123CloudViewModel
import com.yunx.app.ui.viewmodel.QuarkCloudViewModel
import com.yunx.app.ui.viewmodel.ResolveUiState
import com.yunx.app.ui.viewmodel.Pan115CloudViewModel
import com.yunx.app.ui.viewmodel.GuangYaCloudViewModel
import com.yunx.app.data.network.GitHubLinkParser
import com.yunx.app.ui.viewmodel.BookmarkViewModel
import com.yunx.app.ui.viewmodel.ResolveViewModel
import com.yunx.app.ui.viewmodel.UCCoudViewModel
import com.yunx.app.ui.viewmodel.XunleiCloudViewModel

/**
 * 解析页：
 * 输入分享链接与提取码 → 解析 → 展示分享详情 → 获取下载直链。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ResolveViewModel,

    /** 夸克云盘浏览 ViewModel */
    quarkCloudViewModel: QuarkCloudViewModel,

    /** 迅雷云盘浏览 ViewModel */
    xunleiCloudViewModel: XunleiCloudViewModel,

    /** 百度网盘浏览 ViewModel */
    baiduCloudViewModel: BaiduCloudViewModel,

    /** 移动云盘浏览 ViewModel */
    c139CloudViewModel: C139CloudViewModel,

    /** UC 网盘浏览 ViewModel */
    ucCloudViewModel: UCCoudViewModel,

    /** 123 云盘浏览 ViewModel */
    pan123CloudViewModel: Pan123CloudViewModel,
    pan115CloudViewModel: Pan115CloudViewModel,
    guangyaCloudViewModel: GuangYaCloudViewModel,

    bookmarkViewModel: BookmarkViewModel,
    onOpenBookmarks: () -> Unit,
    modifier: Modifier = Modifier
) {
    val homeBookmarks by bookmarkViewModel.homeBookmarks.collectAsState()
    val state = viewModel.uiState
    val downloadLink = viewModel.downloadLink
    val downloadError = viewModel.downloadError

    val context = LocalContext.current

    // -----------------------------
    // 最近解析记录
    // -----------------------------

    val historyRepository = remember {
        ResolveHistoryRepository(
            context.applicationContext
        )
    }

    var recentHistory by remember {
        mutableStateOf(
            historyRepository.load()
        )
    }

    var showHistoryManager by remember {
        mutableStateOf(false)
    }

    fun resolveAndRemember(
        rawLink: String,
        password: String?
    ) {
        val parsed =
            ShareLinkParser.parse(rawLink)

        if (parsed != null) {
            recentHistory =
                historyRepository.add(
                    rawLink,
                    password ?: parsed.pwd
                )
        }

        val github = GitHubLinkParser.parse(rawLink)
        if (github != null) viewModel.startGitHubResolve(github)
        else viewModel.startResolve(rawLink, password)
    }

    // -----------------------------
    // 详情页滚动状态
    // -----------------------------

    val detailListState =
        rememberLazyListState()

    val detailScrollPositions =
        remember {
            mutableStateMapOf<String, Int>()
        }

    // -----------------------------
    // 输入状态
    // -----------------------------

    var link by rememberSaveable {
        mutableStateOf("")
    }

    var pwd by rememberSaveable {
        mutableStateOf("")
    }

    var pwdEdited by rememberSaveable {
        mutableStateOf(false)
    }

    // -----------------------------
    // 剪贴板自动检测
    // -----------------------------

    var clipboardSuggestion by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    var ignoredClipboard by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    val maybeSuggestClipboard: () -> Unit = {
        val text =
            readClipboardSafely(context)

        if (
            com.yunx.app.ui.theme.ThemeController.clipboardSuggestEnabled &&
            text != null &&
            state is ResolveUiState.Idle &&
            text.isNotBlank() &&
            text != link &&
            text != ignoredClipboard &&
            (ShareLinkParser.parse(text) != null || GitHubLinkParser.parse(text) != null)
        ) {
            clipboardSuggestion = text
        }
    }

    val lifecycleOwner =
        LocalLifecycleOwner.current

    val clipboard =
        context.getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager

    DisposableEffect(
        lifecycleOwner,
        clipboard
    ) {
        val clipListener =
            ClipboardManager.OnPrimaryClipChangedListener {

                maybeSuggestClipboard()

                // 部分 ROM 剪贴板更新稍有延迟
                android.os.Handler(
                    android.os.Looper.getMainLooper()
                ).postDelayed(
                    {
                        maybeSuggestClipboard()
                    },
                    300
                )
            }

        clipboard.addPrimaryClipChangedListener(
            clipListener
        )

        val observer =
            LifecycleEventObserver { _, event ->

                if (
                    event ==
                    Lifecycle.Event.ON_RESUME
                ) {
                    maybeSuggestClipboard()
                }
            }

        lifecycleOwner.lifecycle.addObserver(
            observer
        )

        // 冷启动立即检测一次
        maybeSuggestClipboard()

        onDispose {
            clipboard
                .removePrimaryClipChangedListener(
                    clipListener
                )

            lifecycleOwner.lifecycle
                .removeObserver(observer)
        }
    }

    // Android 11 及以下轮询兜底
    if (
        Build.VERSION.SDK_INT <
        Build.VERSION_CODES.S
    ) {
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(2000)

                maybeSuggestClipboard()
            }
        }
    }

    // -----------------------------
    // 链接改变后自动识别提取码
    // -----------------------------

    LaunchedEffect(link) {
        if (
            !pwdEdited &&
            pwd.isEmpty()
        ) {
            ShareLinkParser
                .parse(link)
                ?.pwd
                ?.let {
                    pwd = it
                }
        }
    }

    // -----------------------------
    // 下载错误提示
    // -----------------------------

    LaunchedEffect(downloadError) {
        downloadError?.let {

            SnackbarController.show(it)

            viewModel.consumeDownloadError()
        }
    }

    // -----------------------------
    // 页面主体
    // -----------------------------

    Box(
        modifier =
            modifier.fillMaxSize()
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                fadeIn(
                    tween(200)
                ) togetherWith
                    fadeOut(
                        tween(140)
                    )
            },
            label = "resolveState"
        ) { currentState ->

            when (currentState) {

                is ResolveUiState.Detail -> {
                    ShareDetailScreen(
                        session =
                            currentState.session,

                        files =
                            currentState.files,

                        viewModel =
                            viewModel,

                        quarkCloudViewModel =
                            quarkCloudViewModel,

                        xunleiCloudViewModel =
                            xunleiCloudViewModel,

                        baiduCloudViewModel =
                            baiduCloudViewModel,

                        c139CloudViewModel =
                            c139CloudViewModel,

                        ucCloudViewModel =
                            ucCloudViewModel,

                        pan123CloudViewModel =
                            pan123CloudViewModel,

                        pan115CloudViewModel = pan115CloudViewModel,
                        guangyaCloudViewModel = guangyaCloudViewModel,
                        extraHeaderContent = if (viewModel.isGitHubPlatform) { { GitHubResolveHeader(viewModel) } } else null,
                        extraFooterContent = if (viewModel.githubAtRepoRoot) { { GitHubReadme(viewModel) } } else null,
                        fileBadge = if (viewModel.isGitHubPlatform) { { file -> viewModel.githubBadges[file.fid]?.let { Text(it) } } } else null,
                        onRefresh = if (viewModel.isGitHubPlatform) { { viewModel.refreshGitHubCurrentNode() } } else null,
                        refreshing = viewModel.githubRefreshing,
                        scrollBehavior =
                            scrollBehavior,

                        listState =
                            detailListState,

                        scrollPositions =
                            detailScrollPositions,

                        onExit = {
                            viewModel.backToInput()
                        },

                        onBack = {
                            viewModel.navigateBack()
                        }
                    )
                }

                is ResolveUiState.Loading -> {
                    LoadingContent()
                }

                else -> {
                    ResolveInputContent(
                        scrollBehavior =
                            scrollBehavior,

                        state =
                            currentState,

                        link =
                            link,

                        onLinkChange = {
                            link = it
                        },

                        pwd =
                            pwd,

                        onPwdChange = {
                            pwd = it
                            pwdEdited = true
                        },

                        onClearLink = {
                            link = ""
                            pwd = ""
                            pwdEdited = false
                        },

                        onClearPwd = {
                            pwd = ""
                            pwdEdited = true
                        },

                        onPasteClipboard = {
                            val clipboardText =
                                readClipboardSafely(
                                    context
                                )

                            if (
                                clipboardText
                                    .isNullOrBlank()
                            ) {
                                SnackbarController.show(
                                    "剪贴板为空"
                                )
                            } else {
                                val parsed =
                                    ShareLinkParser.parse(
                                        clipboardText
                                    )

                                if (parsed == null && GitHubLinkParser.parse(clipboardText) == null) {
                                    SnackbarController.show(
                                        "未检测到支持的分享链接"
                                    )
                                } else {
                                    link =
                                        clipboardText

                                    pwd =
                                        parsed?.pwd
                                            .orEmpty()

                                    pwdEdited = true

                                    clipboardSuggestion =
                                        null

                                    ignoredClipboard =
                                        clipboardText

                                    SnackbarController.show(
                                        "已识别${parsed?.let { platformLabel(it.platform) } ?: "GitHub"}链接"
                                    )
                                }
                            }
                        },

                        recentHistory =
                            recentHistory,

                        onStartResolve = {
                                rawLink,
                                password ->

                            resolveAndRemember(
                                rawLink,
                                password
                            )
                        },

                        onHistorySelect = {
                                item ->

                            link =
                                item.link

                            pwd =
                                item.password

                            pwdEdited = true

                            resolveAndRemember(
                                item.link,
                                item.password
                                    .ifBlank {
                                        null
                                    }
                            )
                        },
                onShowAllHistory = {
                    showHistoryManager = true
                },



                        homeBookmarks = homeBookmarks,
                        onOpenBookmarks = onOpenBookmarks,
                        onOpenShortcut = { b ->
                            link = b.link; pwd = b.pwd; pwdEdited = true
                            resolveAndRemember(b.link, b.pwd)
                        },
                        onRemoveShortcut = { b -> bookmarkViewModel.setHomePinned(b.id, false) },
                        onClearHistory = {
                            historyRepository.clear()

                            recentHistory =
                                emptyList()
                        }
                    )
                }
            }
        }

        // -----------------------------
        // 自动检测到剪贴板分享链接
        // -----------------------------

        var animatedSuggestion by remember {
            mutableStateOf<String?>(null)
        }

        LaunchedEffect(
            clipboardSuggestion
        ) {
            clipboardSuggestion?.let {
                animatedSuggestion = it
            }
        }

        AnimatedVisibility(
            visible =
                state is ResolveUiState.Idle &&
                    clipboardSuggestion != null,

            enter =
                fadeIn(
                    tween(200)
                ) +
                    slideInVertically(
                        tween(250)
                    ) {
                        -it / 2
                    } +
                    scaleIn(
                        tween(
                            250,
                            delayMillis = 60
                        )
                    ),

            exit =
                fadeOut(
                    tween(150)
                ) +
                    slideOutVertically(
                        tween(200)
                    ) {
                        -it / 2
                    } +
                    scaleOut(
                        tween(200)
                    ),

            modifier =
                Modifier
                    .align(
                        Alignment.TopCenter
                    )
                    .fillMaxWidth()
                    .padding(16.dp)
        ) {
            animatedSuggestion?.let {
                    suggestion ->

                val parsed =
                    ShareLinkParser.parse(
                        suggestion
                    )

                ClipboardSuggestCard(
                    platformName =
                        parsed
                            ?.platform
                            ?.let {
                                platformLabel(it)
                            }
                            ?: "网盘",

                    onPaste = {
                        link =
                            suggestion

                        pwd =
                            parsed
                                ?.pwd
                                .orEmpty()

                        pwdEdited = true

                        ignoredClipboard =
                            suggestion

                        clipboardSuggestion =
                            null

                        SnackbarController.show(
                            "链接已填入解析框"
                        )
                    },

                    onDismiss = {
                        ignoredClipboard =
                            suggestion

                        clipboardSuggestion =
                            null
                    }
                )
            }
        }
    }

    if (showHistoryManager) {
    HistoryManagerDialog(
        items = recentHistory,

        onSelect = { item ->
            showHistoryManager = false

            link = item.link
            pwd = item.password
            pwdEdited = true

            resolveAndRemember(
                item.link,
                item.password.ifBlank {
                    null
                }
            )
        },

        onDelete = { item ->
            recentHistory =
                historyRepository.remove(
                    item
                )
        },

        onClearAll = {
            historyRepository.clear()
            recentHistory = emptyList()
            showHistoryManager = false
        },

        onDismiss = {
            showHistoryManager = false
        }
    )
}

    // -----------------------------
    // 获取下载链接：非阻塞式轻量状态提示
    // -----------------------------

    AnimatedVisibility(
        visible = viewModel.isFetchingDownloadLink,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(140)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = "正在获取下载链接…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }

    // -----------------------------
    // 下载直链弹窗
    // -----------------------------

    downloadLink?.let {
            download ->

        DownloadLinkDialog(
            link =
                download,

            onDownload = {
                viewModel.startDownload(
                    download
                )
            },

            onDismiss = {
                viewModel
                    .dismissDownloadDialog()
            }
        )
    }
}

/**
 * 解析输入页
 */

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolveInputContent(
    scrollBehavior: TopAppBarScrollBehavior,
    state: ResolveUiState,

    link: String,
    onLinkChange: (String) -> Unit,

    pwd: String,
    onPwdChange: (String) -> Unit,

    onClearLink: () -> Unit,
    onClearPwd: () -> Unit,

    onPasteClipboard: () -> Unit,

    recentHistory: List<ResolveHistoryItem>,

    onStartResolve: (
        String,
        String?
    ) -> Unit,

    onHistorySelect: (
        ResolveHistoryItem
    ) -> Unit,

    onShowAllHistory: () -> Unit,

    onClearHistory: () -> Unit,
    homeBookmarks: List<com.yunx.app.data.db.BookmarkEntity>,
    onOpenBookmarks: () -> Unit,
    onOpenShortcut: (com.yunx.app.data.db.BookmarkEntity) -> Unit,
    onRemoveShortcut: (com.yunx.app.data.db.BookmarkEntity) -> Unit
) {
    val isLoading =
        state is ResolveUiState.Loading

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .nestedScroll(
                    scrollBehavior
                        .nestedScrollConnection
                )
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    horizontal = 18.dp,
                    vertical = 12.dp
                ),

        verticalArrangement =
            Arrangement.spacedBy(
                18.dp
            )
    ) {

        // ==================================================
        // 主解析卡片
        // ==================================================

        Card(
            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(
                    24.dp
                ),

            colors =
                CardDefaults.cardColors(
                    containerColor =
                        MaterialTheme
                            .colorScheme
                            .surfaceContainerLow
                ),

            elevation =
                CardDefaults.cardElevation(
                    defaultElevation =
                        2.dp
                )
        ) {
            Column(
                modifier =
                    Modifier.padding(
                        horizontal = 16.dp,
                        vertical = 16.dp
                    ),

                verticalArrangement =
                    Arrangement.spacedBy(
                    13.dp
                )
            ) {

                // ------------------------------------------
                // 分享链接标题
                // ------------------------------------------

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Box(
                        modifier =
                            Modifier.size(
                                42.dp
                            ),

                        contentAlignment =
                            Alignment.Center
                    ) {
                        Card(
                            shape =
                                RoundedCornerShape(
                                    14.dp
                                ),

                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        MaterialTheme
                                            .colorScheme
                                            .primaryContainer
                                )
                        ) {
                            Box(
                                modifier =
                                    Modifier.size(
                                        42.dp
                                    ),

                                contentAlignment =
                                    Alignment.Center
                            ) {
                                Icon(
                                    imageVector =
                                        Icons.Outlined.Link,

                                    contentDescription =
                                        null,

                                    tint =
                                        MaterialTheme
                                            .colorScheme
                                            .primary,

                                    modifier =
                                        Modifier.size(
                                            23.dp
                                        )
                                )
                            }
                        }
                    }

                    Spacer(
                        modifier =
                            Modifier.width(
                                12.dp
                            )
                    )

                    Text(
                        text =
                            "分享链接",

                        style =
                            MaterialTheme
                                .typography
                                .titleLarge,

                        fontWeight =
                            FontWeight.SemiBold
                    )
                }

                // ------------------------------------------
                // 链接大输入区
                // ------------------------------------------

                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value =
                            link,

                        onValueChange =
                            onLinkChange,

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(
                                min = 128.dp
                            ),

                        placeholder = {
                            Text(
                                text =
                                    "粘贴网盘分享链接……",

                                style =
                                    MaterialTheme
                                        .typography
                                        .bodyLarge,

                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant
                            )
                        },

                        trailingIcon = {
                            if (
                                link.isNotEmpty()
                            ) {
                                IconButton(
                                    onClick =
                                        onClearLink
                                ) {
                                    Icon(
                                        imageVector =
                                            Icons.Filled.Close,

                                        contentDescription =
                                            "清空链接"
                                    )
                                }
                            }
                        },

                        minLines = 4,
                        maxLines = 6,

                        shape =
                            RoundedCornerShape(
                                22.dp
                            )
                        )

                        FilledTonalButton(
                            onClick = onPasteClipboard,
                            modifier =
                                Modifier
                                    .align(
                                        Alignment.BottomEnd
                                    )
                                    .padding(
                                        end = 10.dp,
                                        bottom = 10.dp
                                    )
                                    .height(36.dp),
                            shape =
                                MaterialTheme.shapes.medium,
                            contentPadding =
                                PaddingValues(
                                    horizontal = 10.dp,
                                    vertical = 0.dp
                                )
                        ) {
                            Icon(
                                imageVector =
                                    Icons.Outlined.ContentPaste,
                                contentDescription = null,
                                modifier =
                                    Modifier.size(15.dp)
                            )

                            Spacer(
                                modifier =
                                    Modifier.width(4.dp)
                            )

                            Text(
                                text = "粘贴",
                                style =
                                    MaterialTheme
                                        .typography
                                        .labelMedium,
                                fontWeight =
                                    FontWeight.Medium
                            )
                        }
                    }
                // ------------------------------------------
                // 提取码
                // ------------------------------------------

                OutlinedTextField(
                    value =
                        pwd,

                    onValueChange =
                        onPwdChange,

                    modifier =
                        Modifier.fillMaxWidth(),

                    leadingIcon = {
                        Icon(
                            imageVector =
                                Icons.Outlined.Lock,

                            contentDescription =
                                null
                        )
                    },

                    label = {
                        Text(
                            "提取码（可选）"
                        )
                    },

                    placeholder = {
                        Text(
                            "自动识别，可手动修改"
                        )
                    },

                    trailingIcon = {
                        if (
                            pwd.isNotEmpty()
                        ) {
                            IconButton(
                                onClick =
                                    onClearPwd
                            ) {
                                Icon(
                                    imageVector =
                                        Icons.Filled.Close,

                                    contentDescription =
                                        "清空提取码"
                                )
                            }
                        }
                    },

                    singleLine = true,

                    shape =
                        RoundedCornerShape(
                        16.dp
                    )
                )

                // ------------------------------------------
                // 开始解析
                // ------------------------------------------

                Button(
                    onClick = {
                        onStartResolve(
                            link,
                            pwd.ifBlank {
                                null
                            }
                        )
                    },

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(
                            54.dp
                        ),

                    enabled =
                        link.isNotBlank() &&
                            !isLoading,

                    shape =
                        MaterialTheme.shapes.large
                ) {
                    if (
                        isLoading
                    ) {
                        CircularProgressIndicator(
                            modifier =
                                Modifier.size(
                                    20.dp
                                ),

                            strokeWidth =
                                2.dp
                        )

                        Spacer(
                            modifier =
                                Modifier.width(
                                    10.dp
                                )
                        )

                        Text(
                            text =
                                "解析中…",

                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium,

                            fontWeight =
                                FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            text =
                                "开始解析",

                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium,

                            fontWeight =
                                FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // ==================================================
        // 错误提示
        // ==================================================

        if (
            state is
                ResolveUiState.Error
        ) {
            Card(
                modifier =
                    Modifier.fillMaxWidth(),

                shape =
                    RoundedCornerShape(
                        20.dp
                    ),

                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            MaterialTheme
                                .colorScheme
                                .errorContainer
                    )
            ) {
                Row(
                    modifier =
                        Modifier.padding(
                            14.dp
                        ),

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector =
                            Icons.Outlined.ErrorOutline,

                        contentDescription =
                            null,

                        tint =
                            MaterialTheme
                                .colorScheme
                                .onErrorContainer
                    )

                    Spacer(
                        modifier =
                            Modifier.width(
                                10.dp
                            )
                    )

                    Text(
                        text =
                            state.message,

                        style =
                            MaterialTheme
                                .typography
                                .bodyMedium,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onErrorContainer
                    )
                }
            }
        }

        // ==================================================
        // 最近解析
        // ==================================================

        if (
            recentHistory.isNotEmpty()
        ) {

            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        10.dp
                    )
            ) {

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Icon(
                        imageVector =
                            Icons.Outlined.History,

                        contentDescription =
                            null,

                        tint =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,

                        modifier =
                            Modifier.size(
                                23.dp
                            )
                    )

                    Spacer(
                        modifier =
                            Modifier.width(
                                9.dp
                            )
                    )

                    Text(
                        text =
                            "最近解析",

                        modifier =
                            Modifier.weight(
                                1f
                            ),

                        style =
                            MaterialTheme
                                .typography
                                .titleLarge,

                        fontWeight =
                            FontWeight.SemiBold
                    )

                    TextButton(
                        onClick =
                            onShowAllHistory
                    ) {
                        Text(
                            text =
                                "查看全部",

                            fontWeight =
                                FontWeight.Medium
                        )

                        Spacer(
                            modifier =
                                Modifier.width(
                                    2.dp
                                )
                        )

                        Icon(
                            imageVector =
                                Icons.Outlined.ChevronRight,

                            contentDescription =
                                null,

                            modifier =
                                Modifier.size(
                                    18.dp
                                )
                        )
                    }
                }

                RecentHistoryCard(
                    items =
                        recentHistory.take(
                            2
                        ),

                    onItemClick =
                        onHistorySelect
                )
            }
        }

        Spacer(
            modifier =
                Modifier.height(
                    8.dp
                )
        )
        HomeShortcutsSection(homeBookmarks, onOpenShortcut, onOpenBookmarks, onRemoveShortcut)
    }
}


@Composable
private fun RecentHistoryCard(
    items: List<ResolveHistoryItem>,
    onItemClick: (
        ResolveHistoryItem
    ) -> Unit
) {
    Card(
        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(
                26.dp
            ),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme
                        .colorScheme
                        .surfaceContainerLow
            ),

        elevation =
            CardDefaults.cardElevation(
                defaultElevation =
                    1.dp
            )
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
        ) {
            items.forEachIndexed {
                    index,
                    item ->

                RecentHistoryRow(
                    item =
                        item,

                    onClick = {
                        onItemClick(
                            item
                        )
                    }
                )

                if (
                    index <
                        items.lastIndex
                ) {
                    HorizontalDivider(
                        modifier =
                            Modifier.padding(
                                start = 68.dp,
                                end = 16.dp
                            ),

                        color =
                            MaterialTheme
                                .colorScheme
                                .outlineVariant
                    )
                }
            }
        }
    }
}


@Composable
private fun RecentHistoryRow(
    item: ResolveHistoryItem,
    onClick: () -> Unit
) {
    Card(
        onClick =
            onClick,

        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(
                0.dp
            ),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme
                        .colorScheme
                        .surfaceContainerLow
            ),

        elevation =
            CardDefaults.cardElevation(
                defaultElevation =
                    0.dp
            )
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 16.dp,
                        vertical = 15.dp
                    ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Card(
                shape =
                    RoundedCornerShape(
                        16.dp
                    ),

                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            MaterialTheme
                                .colorScheme
                                .primaryContainer
                    )
            ) {
                Box(
                    modifier =
                        Modifier.size(
                            46.dp
                        ),

                    contentAlignment =
                        Alignment.Center
                ) {
                    Icon(
                        imageVector =
                            Icons.Outlined.Link,

                        contentDescription =
                            null,

                        tint =
                            MaterialTheme
                                .colorScheme
                                .primary,

                        modifier =
                            Modifier.size(
                                24.dp
                            )
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.width(
                        12.dp
                    )
            )

            Column(
                modifier =
                    Modifier.weight(
                        1f
                    ),

                verticalArrangement =
                    Arrangement.spacedBy(
                        3.dp
                    )
            ) {

                Text(
                    text =
                        buildString {
                            append(
                                item.platformName
                            )

                            if (
                                item.password
                                    .isNotBlank()
                            ) {
                                append(
                                    " · "
                                )

                                append(
                                    item.password
                                )
                            }
                        },

                    style =
                        MaterialTheme
                            .typography
                            .titleSmall,

                    fontWeight =
                        FontWeight.SemiBold,

                    maxLines = 1,

                    overflow =
                        TextOverflow.Ellipsis
                )

                Text(
                    text =
                        item.link,

                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,

                    maxLines = 1,

                    overflow =
                        TextOverflow.Ellipsis
                )

                val historyTime =
                    formatHistoryTime(
                        item.timestamp
                    )

                if (
                    historyTime.isNotBlank()
                ) {
                    Text(
                        text =
                            historyTime,

                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,

                        color =
                            MaterialTheme
                                .colorScheme
                                .outline
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.width(
                        8.dp
                    )
            )

            Icon(
                imageVector =
                    Icons.Outlined.ChevronRight,

                contentDescription =
                    null,

                tint =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant,

                modifier =
                    Modifier.size(
                        22.dp
                    )
            )
        }
    }
}


/**
 * 将解析时间转换为简短的相对时间。
 */
private fun formatHistoryTime(
    timestamp: Long,
    now: Long = System.currentTimeMillis()
): String {
    if (timestamp <= 0L) {
        return ""
    }

    val diff =
        (now - timestamp)
            .coerceAtLeast(0L)

    val minute =
        60_000L

    val hour =
        60L * minute

    val day =
        24L * hour

    return when {
        diff < minute ->
            "刚刚"

        diff < hour ->
            "${diff / minute} 分钟前"

        diff < day ->
            "${diff / hour} 小时前"

        diff < 2L * day ->
            "昨天"

        diff < 7L * day ->
            "${diff / day} 天前"

        else ->
            "较早"
    }
}

/**
 * 最近解析单条卡片
 */
@Composable
private fun HistoryCard(
    item: ResolveHistoryItem,
    onClick: () -> Unit
) {
    Card(
        onClick =
            onClick,

        modifier =
            Modifier.fillMaxWidth(),

        shape =
            MaterialTheme
                .shapes
                .large,

        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme
                        .colorScheme
                        .surfaceContainerLow
            )
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),

            verticalAlignment =
                Alignment.CenterVertically
        ) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp),

                contentAlignment =
                    Alignment.Center
            ) {
                Icon(
                    imageVector =
                        Icons.Outlined.Link,

                    contentDescription =
                        null,

                    tint =
                        MaterialTheme
                            .colorScheme
                            .primary
                )
            }

            Spacer(
                modifier =
                    Modifier.width(10.dp)
            )

            Column(
                modifier =
                    Modifier.weight(1f),

                verticalArrangement =
                    Arrangement.spacedBy(
                        3.dp
                    )
            ) {
                Text(
                    text =
                        buildString {

                            append(
                                item.platformName
                            )

                            if (
                                item.password
                                    .isNotBlank()
                            ) {
                                append(
                                    " · 提取码 "
                                )

                                append(
                                    item.password
                                )
                            }
                        },

                    style =
                        MaterialTheme
                            .typography
                            .titleSmall,

                    fontWeight =
                        FontWeight.Medium,

                    maxLines = 1,

                    overflow =
                        TextOverflow.Ellipsis
                )

                Text(
                    text =
                        item.link,

                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,

                    maxLines = 1,

                    overflow =
                        TextOverflow.Ellipsis
                )

                val historyTime =
                    formatHistoryTime(
                        item.timestamp
                    )

                if (historyTime.isNotBlank()) {
                    Text(
                        text =
                            historyTime,

                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 完整解析历史管理。
 */
@Composable
private fun HistoryManagerDialog(
    items: List<ResolveHistoryItem>,
    onSelect: (ResolveHistoryItem) -> Unit,
    onDelete: (ResolveHistoryItem) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable {
        mutableStateOf("")
    }

    val filtered =
        remember(
            items,
            query
        ) {
            val keyword =
                query.trim()

            if (keyword.isBlank()) {
                items
            } else {
                items.filter { item ->
                    item.platformName.contains(
                        keyword,
                        ignoreCase = true
                    ) ||
                        item.link.contains(
                            keyword,
                            ignoreCase = true
                        ) ||
                        item.shareId.contains(
                            keyword,
                            ignoreCase = true
                        )
                }
            }
        }

    AlertDialog(
        onDismissRequest =
            onDismiss,

        title = {
            Text(
                "解析历史"
            )
        },

        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(
                            max = 520.dp
                        )
            ) {
                OutlinedTextField(
                    value =
                        query,

                    onValueChange = {
                        query = it
                    },

                    modifier =
                        Modifier.fillMaxWidth(),

                    placeholder = {
                        Text(
                            "搜索平台或分享链接"
                        )
                    },

                    leadingIcon = {
                        Icon(
                            imageVector =
                                Icons.Outlined.Search,

                            contentDescription =
                                null
                        )
                    },

                    singleLine = true,

                    shape =
                        MaterialTheme
                            .shapes
                            .large
                )

                Spacer(
                    modifier =
                        Modifier.height(
                            12.dp
                        )
                )

                if (filtered.isEmpty()) {
                    Text(
                        text =
                            if (items.isEmpty()) {
                                "暂无解析历史"
                            } else {
                                "没有找到匹配的记录"
                            },

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    vertical =
                                        24.dp
                                ),

                        style =
                            MaterialTheme
                                .typography
                                .bodyMedium,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier =
                            Modifier
                                .verticalScroll(
                                    rememberScrollState()
                                ),

                        verticalArrangement =
                            Arrangement.spacedBy(
                                8.dp
                            )
                    ) {
                        filtered.forEach { item ->
                            Card(
                                onClick = {
                                    onSelect(item)
                                },

                                modifier =
                                    Modifier
                                        .fillMaxWidth(),

                                colors =
                                    CardDefaults.cardColors(
                                        containerColor =
                                            MaterialTheme
                                                .colorScheme
                                                .surfaceContainerLow
                                    )
                            ) {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                12.dp
                                            ),

                                    verticalAlignment =
                                        Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier =
                                            Modifier.weight(
                                                1f
                                            )
                                    ) {
                                        Text(
                                            text =
                                                buildString {
                                                    append(
                                                        item.platformName
                                                    )

                                                    if (
                                                        item.password
                                                            .isNotBlank()
                                                    ) {
                                                        append(
                                                            " · 提取码 "
                                                        )

                                                        append(
                                                            item.password
                                                        )
                                                    }
                                                },

                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .titleSmall,

                                            fontWeight =
                                                FontWeight.Medium,

                                            maxLines = 1,

                                            overflow =
                                                TextOverflow.Ellipsis
                                        )

                                        Spacer(
                                            modifier =
                                                Modifier.height(
                                                    3.dp
                                                )
                                        )

                                        Text(
                                            text =
                                                item.link,

                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall,

                                            color =
                                                MaterialTheme
                                                    .colorScheme
                                                    .onSurfaceVariant,

                                            maxLines = 1,

                                            overflow =
                                                TextOverflow.Ellipsis
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            onDelete(
                                                item
                                            )
                                        }
                                    ) {
                                        Icon(
                                            imageVector =
                                                Icons.Outlined.Delete,

                                            contentDescription =
                                                "删除历史",

                                            tint =
                                                MaterialTheme
                                                    .colorScheme
                                                    .error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },

        confirmButton = {
            TextButton(
                onClick =
                    onDismiss
            ) {
                Text(
                    "关闭"
                )
            }
        },

        dismissButton = {
            if (items.isNotEmpty()) {
                TextButton(
                    onClick =
                        onClearAll
                ) {
                    Text(
                        text =
                            "清空全部",

                        color =
                            MaterialTheme
                                .colorScheme
                                .error
                    )
                }
            }
        }
    )
}

/**
 * 全屏加载
 */
@Composable
private fun LoadingContent() {
    Box(
        modifier =
            Modifier.fillMaxSize(),

        contentAlignment =
            Alignment.Center
    ) {
        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                modifier =
                    Modifier.size(28.dp),

                strokeWidth =
                    3.dp
            )

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            Text(
                text =
                    "加载中…",

                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

/**
 * 安全读取剪贴板文字。
 */
private fun readClipboardSafely(
    context: Context
): String? =
    runCatching {

        val clipboardManager =
            context.getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

        clipboardManager
            .primaryClip
            ?.takeIf {
                it.itemCount > 0
            }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()

    }.getOrNull()

/**
 * 网盘显示名称。
 */
private fun platformLabel(
    platform: SharePlatform
): String =
    when (platform) {

        SharePlatform.QUARK ->
            "夸克网盘"

        SharePlatform.UC ->
            "UC 网盘"

        SharePlatform.XUNLEI ->
            "迅雷网盘"

        SharePlatform.BAIDU ->
            "百度网盘"

        SharePlatform.C139 ->
            "移动云盘"

        SharePlatform.PAN123 -> "123 云盘"
        SharePlatform.PAN115 -> "115 网盘"
        SharePlatform.GUANGYA -> "光鸭云盘"
        SharePlatform.ILANZOU -> "蓝奏云优享版"
        SharePlatform.LANZOU -> "蓝奏云"
        SharePlatform.GITHUB -> "GitHub"
    }

/**
 * 自动检测剪贴板分享链接后的顶部提示卡片。
 *
 * 现在只负责“粘贴”，不直接开始解析。
 */
@Composable
private fun ClipboardSuggestCard(
    platformName: String,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier =
            modifier.fillMaxWidth(),

        shape =
            MaterialTheme
                .shapes
                .large,

        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme
                        .colorScheme
                        .primaryContainer
            )
    ) {
        Column(
            modifier =
                Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Icon(
                    imageVector =
                        Icons.Outlined.Link,

                    contentDescription =
                        null,

                    tint =
                        MaterialTheme
                            .colorScheme
                            .onPrimaryContainer
                )

                Spacer(
                    modifier =
                        Modifier.width(10.dp)
                )

                Column(
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text(
                        text =
                            "检测到 $platformName 分享链接",

                        style =
                            MaterialTheme
                                .typography
                                .titleSmall,

                        fontWeight =
                            FontWeight.Medium,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onPrimaryContainer
                    )

                    Text(
                        text =
                            "是否填入解析框？",

                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onPrimaryContainer
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )

            Row(
                modifier =
                    Modifier.fillMaxWidth(),

                horizontalArrangement =
                    Arrangement.End,

                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                TextButton(
                    onClick =
                        onDismiss
                ) {
                    Text(
                        text =
                            "忽略",

                        color =
                            MaterialTheme
                                .colorScheme
                                .onPrimaryContainer
                    )
                }

                Spacer(
                    modifier =
                        Modifier.width(4.dp)
                )

                Button(
                    onClick =
                        onPaste,

                    colors =
                        ButtonDefaults
                            .buttonColors(
                                containerColor =
                                    MaterialTheme
                                        .colorScheme
                                        .primary,

                                contentColor =
                                    MaterialTheme
                                        .colorScheme
                                        .onPrimary
                            )
                ) {
                    Icon(
                        imageVector =
                            Icons.Outlined
                                .ContentPaste,

                        contentDescription =
                            null,

                        modifier =
                            Modifier.size(18.dp)
                    )

                    Spacer(
                        modifier =
                            Modifier.width(6.dp)
                    )

                    Text(
                        "粘贴"
                    )
                }
            }
        }
    }
}
