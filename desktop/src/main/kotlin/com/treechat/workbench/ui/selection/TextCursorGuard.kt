// 桌面替身：ui.selection.TextCursorGuard 的桌面版（无 LocalView，系统自带文本光标）。
@file:Suppress("unused")
package com.treechat.workbench.ui.selection

import androidx.compose.ui.Modifier

fun Modifier.assureTextCursor(): Modifier = this
