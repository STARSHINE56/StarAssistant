package com.yunx.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.unit.dp

internal enum class ListGroupPos { FIRST, MIDDLE, LAST, SINGLE }
internal val ListGroupGap = 3.dp
internal fun listGroupShape(pos: ListGroupPos): RoundedCornerShape {
    val top = if (pos == ListGroupPos.FIRST || pos == ListGroupPos.SINGLE) 16.dp else 2.dp
    val bottom = if (pos == ListGroupPos.LAST || pos == ListGroupPos.SINGLE) 16.dp else 2.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}
internal fun listGroupShape(index: Int, count: Int) = listGroupShape(when {
    count <= 1 -> ListGroupPos.SINGLE
    index == 0 -> ListGroupPos.FIRST
    index == count - 1 -> ListGroupPos.LAST
    else -> ListGroupPos.MIDDLE
})
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal val AppMotionScheme = MotionScheme.standard()
