package com.yunx.app.ui.items

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** 多选底部批量操作项 */
internal data class MultiSelectAction(
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    val onClick: () -> Unit
)

/**
 * 多选模式底部批量操作栏（云盘页/解析页共用）。
 *
 * Expressive 悬浮工具栏：外形 / 进出场形变动效由组件提供，取代了原来「贴满整宽的 Surface + Row」。
 * ★ 只放图标不放文字：文字会让胶囊条变长、且与图标叠在一起显得拥挤（操作含义由无障碍标签给出）。
 * ★ 显式指定容器色与展开态阴影：默认展开态没有阴影，与页面背景贴在一起看不出边界。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MultiSelectBar(
    count: Int,
    actions: List<MultiSelectAction>
) {
    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 16.dp),
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            expandedShadowElevation = 6.dp
        ) {
            actions.forEach { action ->
                IconButton(onClick = action.onClick) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.label,
                        tint = action.tint
                    )
                }
            }
        }
    }
}
