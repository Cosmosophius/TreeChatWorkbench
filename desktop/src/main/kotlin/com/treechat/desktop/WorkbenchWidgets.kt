// 桌面壳工具集：模型快击菜单（对齐安卓 ProviderAndModelMenu 的两段结构：
// 服务商切换段 + 当前服务商的模型段）与浏览器 Tab 的桌面方案。
// ProviderAndModelMenu 原件在 MainActivity.kt（桌面不编译该文件），此为复刻实现。
package com.treechat.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.treechat.workbench.data.prefs.AppSettings
import com.treechat.workbench.data.prefs.ProviderStore
import com.treechat.workbench.ui.chat.ChatNodeViewModel
import com.treechat.workbench.ui.theme.TreeChatTheme
import kotlinx.coroutines.launch

@Composable
fun DesktopProviderAndModelMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    viewModel: ChatNodeViewModel,
    provider: com.treechat.workbench.network.protocol.ProviderConfig,
    providerStore: ProviderStore,
    onOpenSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val saved = viewModel.savedProviders

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // ---------- 服务商段 ----------
        Text(
            text = "服务商",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (saved.isEmpty()) {
            DropdownMenuItem(
                text = { Text("尚无已保存的服务商", style = MaterialTheme.typography.bodySmall) },
                onClick = { onDismiss(); onOpenSettings() },
            )
        }
        saved.forEach { item ->
            val isCurrent = item.id == provider.id
            DropdownMenuItem(
                text = {
                    Text(
                        text = item.name.ifBlank { item.id },
                        color = if (isCurrent) scheme.primary else scheme.onSurface,
                    )
                },
                trailingIcon = {
                    if (isCurrent) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                    }
                },
                onClick = {
                    viewModel.selectProvider(item.id)
                    onDismiss()
                },
            )
        }

        HorizontalDivider()

        // ---------- 模型段（属于当前服务商） ----------
        Text(
            text = "模型 · ${provider.name.ifBlank { provider.id }}",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (provider.availableModels.isEmpty()) {
            DropdownMenuItem(
                text = { Text("该服务商尚无模型，请先在设置中获取", style = MaterialTheme.typography.bodySmall) },
                onClick = { onDismiss(); onOpenSettings() },
            )
        }
        provider.availableModels.forEach { model ->
            val selected = model.id == provider.selectedModelId
            DropdownMenuItem(
                text = {
                    Text(
                        text = model.name + if (model.contextWindow > 0) {
                            "（${com.treechat.workbench.util.TokenFormat.format(model.contextWindow)} ctx）"
                        } else {
                            ""
                        },
                        color = if (selected) scheme.primary else scheme.onSurface,
                    )
                },
                trailingIcon = {
                    if (selected) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                    }
                },
                onClick = {
                    // 模型切换 = 更新该 provider 的 selectedModelId 并回写列表（共享 ViewModel 契约）
                    val updated = viewModel.savedProviders.map {
                        if (it.id == provider.id) it.copy(selectedModelId = model.id) else it
                    }
                    viewModel.replaceProviders(updated)
                    onDismiss()
                },
            )
        }

        HorizontalDivider()
        DropdownMenuItem(text = { Text("设置…") }, onClick = { onDismiss(); onOpenSettings() })
    }
}

/** 浏览器 Tab 桌面方案：URL 输入 → 系统默认浏览器（AppEnv.openLink）。 */
@Composable
fun DesktopBrowserPane(env: DesktopAppEnv, settings: AppSettings, toast: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("浏览器（桌面方案：系统默认浏览器打开）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入网址，例如 https://www.google.com") },
                singleLine = true,
            )
            Spacer(Modifier.size(8.dp))
            Button(onClick = {
                var target = url.trim()
                if (target.isEmpty()) return@Button
                if (!target.startsWith("http://") && !target.startsWith("https://")) {
                    target = "https://$target"
                }
                val ok = env.openLink(target)
                toast(if (ok) "已在系统浏览器打开：$target" else "打开失败（无可用浏览器）")
            }) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("打开")
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "说明：桌面版不再内嵌 WebView 覆盖层（安卓线的常驻浏览器是安卓专属实现）；" +
                "选中网页文字后复制，回条畅即可用「划词提问」同一套对话链路。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
