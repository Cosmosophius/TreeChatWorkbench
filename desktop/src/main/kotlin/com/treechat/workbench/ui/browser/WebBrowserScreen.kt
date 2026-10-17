// 桌面替身：ui.browser.WebBrowserScreen —— M4 换独立窗口方案前的占位。
// 保持与安卓一致的 API 面（ReaderScreen 的常驻浏览器挂载点）。
@file:Suppress("unused", "unused_parameter", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.treechat.workbench.ui.chat.ChatNodeViewModel
import com.treechat.workbench.ui.window.FloatingWindowManager

@Composable
fun WebBrowserScreen(
    viewModel: ChatNodeViewModel,
    windowManager: FloatingWindowManager,
    onExit: () -> Unit,
    controller: BrowserController = androidx.compose.runtime.remember { BrowserController() },
    isForeground: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "桌面浏览器在 M4 换为独立顶层窗口方案（不沿用 WebView 覆盖层）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
