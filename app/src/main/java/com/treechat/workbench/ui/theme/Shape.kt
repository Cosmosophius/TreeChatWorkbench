package com.treechat.workbench.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 形状阶。
 *
 * 全应用统一走这一套，不再各处写 RoundedCornerShape(8.dp) / (12.dp) 这类
 * 随手数值 —— 形状不一致是界面"看起来不像同一个系统"的主要来源之一。
 */
val TreeChatShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
