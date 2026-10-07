package com.yunx.app.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.yunx.app.ui.components.GitHubMarkdownImageTransformer
import com.yunx.app.ui.components.compactMarkdownTypography
import com.yunx.app.ui.viewmodel.ResolveViewModel

@Composable
internal fun GitHubResolveHeader(viewModel: ResolveViewModel) {
    if (viewModel.githubAtRepoRoot) viewModel.githubParentFullName?.let { parent ->
        TextButton(onClick = { viewModel.openGitHubParentRepo() }) { Text("forked from $parent") }
    }
}

@Composable
internal fun GitHubReadme(viewModel: ResolveViewModel) {
    val md = viewModel.githubReadme ?: return
    val owner = viewModel.githubRepoOwner ?: return
    val repo = viewModel.githubRepoName ?: return
    val branch = viewModel.githubDefaultBranch ?: return
    if (md.isBlank()) return
    val content = remember(md, owner, repo, branch) { preprocessReadme(md, owner, repo, branch) }
    val context = LocalContext.current
    Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
        HorizontalDivider()
        Text("README", style = MaterialTheme.typography.labelMedium)
        if (Build.VERSION.SDK_INT >= 24) {
            GitHubMarkdownImageTransformer.mirrorPrefix = remember(context) {
                com.yunx.app.data.prefs.SettingsRepository(context).githubMirrorPrefix?.ifBlank { null }
            }
            Markdown(content = content, typography = remember { compactMarkdownTypography() }, imageTransformer = GitHubMarkdownImageTransformer)
        } else {
            // Retain StarAssistant's Android 6 support; the renderer uses interface static methods.
            Text(content, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun preprocessReadme(md: String, owner: String, repo: String, branch: String): String {
    val blobBase = "https://github.com/$owner/$repo/blob/$branch/"
    val rawBase = "https://raw.githubusercontent.com/$owner/$repo/$branch/"
    // 图片 ![alt](url)
    var out = Regex("!\\[([^]]*)\\]\\(([^)]+)\\)").replace(md) { m ->
        val alt = m.groupValues[1]
        val url = m.groupValues[2].trim()
        val resolved = resolveRel(rawBase, url)
        "![$alt]($resolved)"
    }
    // 普通链接 [text](url)（排除已处理的图片）
    out = Regex("(?<!!)\\[([^]]+)\\]\\(([^)]+)\\)").replace(out) { m ->
        val label = m.groupValues[1]
        val url = m.groupValues[2].trim()
        val resolved = resolveRel(blobBase, url)
        "[$label]($resolved)"
    }
    return out
}

/**
 * 把 README 相对链接补全为绝对 URL。
 * - 绝对 URL（http/https/mailto）原样返回；
 * - 相对路径用 URI.resolve 处理 `./`、`../`（上溯目录），避免 `../` 被当作普通路径段拼错。
 */
private fun resolveRel(base: String, rel: String): String {
    if (rel.startsWith("http://") || rel.startsWith("https://") || rel.startsWith("mailto:")) return rel
    // 含转义括号的链接（如 [a](b\(c\))）按原样保留，不做路径补全，避免被错误补全
    if (rel.contains('\\')) return rel
    return runCatching { java.net.URI(base).resolve(rel).toString() }.getOrDefault(base + rel)
}
