package com.treechat.workbench.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treechat.workbench.ui.theme.AccentIceBlue
import com.treechat.workbench.ui.theme.TextSecondary

/**
 * 横向滚动的筛选条（一排可点的胶囊）。
 *
 * 通用组件：命令面板用它来放「检索范围 / 检索类型」两组选项。
 * 横��滚动是因为会话多了以后范围选项会很长，挤在一行里放不下。
 */
@Composable
fun <T> PaletteFilterRow(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { opt ->
            val isSelected = opt == selected
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) AccentIceBlue.copy(alpha = 0.18f) else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (isSelected) AccentIceBlue else Color(0xFF2C323D),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Text(
                    text = labelOf(opt),
                    color = if (isSelected) AccentIceBlue else TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}
