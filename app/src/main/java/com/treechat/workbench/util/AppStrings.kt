package com.treechat.workbench.util

/**
 * 轻量四语言国际化字符串映射表。
 *
 * 严格支持四种界面语言：
 * - zh-CN: 简体汉语
 * - zh-TW: 繁体汉语
 * - en: English
 * - ru: Русский
 *
 * 纪律规定：严禁出现“中文”字样，一律使用「简体汉语」或「繁体汉语」。
 */
object AppStrings {

    const val LANG_ZH_CN = "zh-CN"
    const val LANG_ZH_TW = "zh-TW"
    const val LANG_EN = "en"
    const val LANG_RU = "ru"

    val SUPPORTED_LANGUAGES = listOf(
        LANG_ZH_CN to "简体汉语",
        LANG_ZH_TW to "繁体汉语",
        LANG_EN to "English",
        LANG_RU to "Русский"
    )

    private val STRINGS = mapOf(
        // 导航 Tab
        "tab_explorer" to mapOf(
            LANG_ZH_CN to "文件夹",
            LANG_ZH_TW to "文件夾",
            LANG_EN to "Folders",
            LANG_RU to "Папки"
        ),
        "tab_chat" to mapOf(
            LANG_ZH_CN to "对话",
            LANG_ZH_TW to "對話",
            LANG_EN to "Chat",
            LANG_RU to "Чат"
        ),
        "tab_tree" to mapOf(
            LANG_ZH_CN to "思维导图",
            LANG_ZH_TW to "思維導圖",
            LANG_EN to "Mind Map",
            LANG_RU to "Интеллект-карта"
        ),
        "tab_browser" to mapOf(
            LANG_ZH_CN to "浏览器",
            LANG_ZH_TW to "瀏覽器",
            LANG_EN to "Browser",
            LANG_RU to "Браузер"
        ),
        "tab_workbench" to mapOf(
            LANG_ZH_CN to "工作台",
            LANG_ZH_TW to "工作台",
            LANG_EN to "Canvas",
            LANG_RU to "Доска"
        ),
        "tab_notes" to mapOf(
            LANG_ZH_CN to "笔记",
            LANG_ZH_TW to "筆記",
            LANG_EN to "Notes",
            LANG_RU to "Заметки"
        ),
        "tab_shelf" to mapOf(
            LANG_ZH_CN to "书架",
            LANG_ZH_TW to "書架",
            LANG_EN to "Bookshelf",
            LANG_RU to "Книги"
        ),
        "tab_dictionary" to mapOf(
            LANG_ZH_CN to "词典",
            LANG_ZH_TW to "詞典",
            LANG_EN to "Dictionary",
            LANG_RU to "Словарь"
        ),

        // 通用操作与按钮
        "send" to mapOf(
            LANG_ZH_CN to "发送",
            LANG_ZH_TW to "發送",
            LANG_EN to "Send",
            LANG_RU to "Отправить"
        ),
        "copy" to mapOf(
            LANG_ZH_CN to "复制",
            LANG_ZH_TW to "複製",
            LANG_EN to "Copy",
            LANG_RU to "Копировать"
        ),
        "copied" to mapOf(
            LANG_ZH_CN to "已复制",
            LANG_ZH_TW to "已複製",
            LANG_EN to "Copied",
            LANG_RU to "Скопировано"
        ),
        "regenerate" to mapOf(
            LANG_ZH_CN to "重新生成",
            LANG_ZH_TW to "重新生成",
            LANG_EN to "Regenerate",
            LANG_RU to "Перегенерировать"
        ),
        "fork_whole" to mapOf(
            LANG_ZH_CN to "整段分叉",
            LANG_ZH_TW to "整段分叉",
            LANG_EN to "Fork Branch",
            LANG_RU to "Ветвить"
        ),
        "delete" to mapOf(
            LANG_ZH_CN to "删除",
            LANG_ZH_TW to "刪除",
            LANG_EN to "Delete",
            LANG_RU to "Удалить"
        ),
        "delete_confirm_title" to mapOf(
            LANG_ZH_CN to "确认删除消息",
            LANG_ZH_TW to "確認刪除訊息",
            LANG_EN to "Delete Message",
            LANG_RU to "Удалить сообщение"
        ),
        "delete_confirm_desc" to mapOf(
            LANG_ZH_CN to "确定要删除这条消息吗？删除后不可恢复。",
            LANG_ZH_TW to "確定要刪除這條訊息嗎？刪除後不可恢復。",
            LANG_EN to "Are you sure you want to delete this message? This cannot be undone.",
            LANG_RU to "Вы уверены, что хотите удалить это сообщение? Действие необратимо."
        ),
        "confirm" to mapOf(
            LANG_ZH_CN to "确定",
            LANG_ZH_TW to "確定",
            LANG_EN to "Confirm",
            LANG_RU to "ОК"
        ),
        "cancel" to mapOf(
            LANG_ZH_CN to "取消",
            LANG_ZH_TW to "取消",
            LANG_EN to "Cancel",
            LANG_RU to "Отмена"
        ),
        "new_conversation" to mapOf(
            LANG_ZH_CN to "新建对话",
            LANG_ZH_TW to "新建對話",
            LANG_EN to "New Chat",
            LANG_RU to "Новый чат"
        ),
        "new_folder" to mapOf(
            LANG_ZH_CN to "新建文件夹",
            LANG_ZH_TW to "新建文件夾",
            LANG_EN to "New Folder",
            LANG_RU to "Новая папка"
        ),
        "export_zip" to mapOf(
            LANG_ZH_CN to "压缩打包导出 (ZIP)",
            LANG_ZH_TW to "壓縮打包導出 (ZIP)",
            LANG_EN to "Export as ZIP",
            LANG_RU to "Экспорт в ZIP"
        ),
        "export_talktree" to mapOf(
            LANG_ZH_CN to "导出为 .talktree 跨平台档案",
            LANG_ZH_TW to "導出為 .talktree 跨平台檔案",
            LANG_EN to "Export as .talktree",
            LANG_RU to "Экспорт в .talktree"
        ),
        "import_talktree" to mapOf(
            LANG_ZH_CN to "导入 .talktree 对话",
            LANG_ZH_TW to "導入 .talktree 對話",
            LANG_EN to "Import .talktree",
            LANG_RU to "Импорт .talktree"
        ),
        "settings" to mapOf(
            LANG_ZH_CN to "设置",
            LANG_ZH_TW to "設置",
            LANG_EN to "Settings",
            LANG_RU to "Настройки"
        ),
        "appearance_prefs" to mapOf(
            LANG_ZH_CN to "外观与偏好",
            LANG_ZH_TW to "外觀與偏好",
            LANG_EN to "Appearance & Prefs",
            LANG_RU to "Внешний вид и настройки"
        ),
        "provider_model" to mapOf(
            LANG_ZH_CN to "服务商与模型",
            LANG_ZH_TW to "服務商與模型",
            LANG_EN to "Providers & Models",
            LANG_RU to "Провайдеры и модели"
        ),
        "app_language" to mapOf(
            LANG_ZH_CN to "界面语言",
            LANG_ZH_TW to "介面語言",
            LANG_EN to "Interface Language",
            LANG_RU to "Язык интерфейса"
        ),
        "app_language_desc" to mapOf(
            LANG_ZH_CN to "切换应用界面显示语言，支持简体汉语、繁体汉语、English 与 Русский",
            LANG_ZH_TW to "切換應用介面顯示語言，支援簡體漢語、繁體漢語、English 與 Русский",
            LANG_EN to "Switch interface display language (Simplified Chinese, Traditional Chinese, English, Russian)",
            LANG_RU to "Переключение языка интерфейса (Упрощенный китайский, Традиционный китайский, Английский, Русский)"
        ),
        "command_palette" to mapOf(
            LANG_ZH_CN to "快捷命令面板",
            LANG_ZH_TW to "快捷命令面板",
            LANG_EN to "Command Palette",
            LANG_RU to "Палитра команд"
        ),
        "command_palette_search" to mapOf(
            LANG_ZH_CN to "键入命令或搜索功能…",
            LANG_ZH_TW to "鍵入命令或搜尋功能…",
            LANG_EN to "Type a command or search…",
            LANG_RU to "Введите команду или поиск…"
        ),

        // 敏感枝条与防风控
        "sensitive_branch_title" to mapOf(
            LANG_ZH_CN to "敏感枝条标记",
            LANG_ZH_TW to "敏感枝條標記",
            LANG_EN to "Sensitive Branch Tag",
            LANG_RU to "Конфиденциальная ветка"
        ),
        "sensitive_branch_warning" to mapOf(
            LANG_ZH_CN to "此分支已标记为敏感枝条。切换至 DeepSeek 等含强内容审查的模型时可能会触发风控拦截或拒答。",
            LANG_ZH_TW to "此分支已標記為敏感枝條。切換至 DeepSeek 等含強內容審查的模型時可能會觸發風控攔截或拒答。",
            LANG_EN to "This branch is flagged as sensitive. Switching to models like DeepSeek may trigger safety moderation filters.",
            LANG_RU to "Эта ветка помечена как чувствительная. Использование моделей вроде DeepSeek может вызвать блокировку фильтрами безопасности."
        ),
        "mark_sensitive" to mapOf(
            LANG_ZH_CN to "标记为敏感枝条",
            LANG_ZH_TW to "標記為敏感枝條",
            LANG_EN to "Mark as Sensitive",
            LANG_RU to "Пометить как чувствительную"
        ),
        "unmark_sensitive" to mapOf(
            LANG_ZH_CN to "解除敏感标记",
            LANG_ZH_TW to "解除敏感標記",
            LANG_EN to "Remove Sensitive Tag",
            LANG_RU to "Снять пометку"
        ),
        "audit_branch_safety" to mapOf(
            LANG_ZH_CN to "枝条敏感内容检测",
            LANG_ZH_TW to "枝條敏感內容檢測",
            LANG_EN to "Audit Branch Safety",
            LANG_RU to "Проверить безопасность ветки"
        ),
        "audit_in_progress" to mapOf(
            LANG_ZH_CN to "正在审查枝条内容敏感度…",
            LANG_ZH_TW to "正在審查枝條內容敏感度…",
            LANG_EN to "Auditing branch safety...",
            LANG_RU to "Проверка безопасности ветки..."
        ),
        "audit_clean" to mapOf(
            LANG_ZH_CN to "检测完成：未发现明显风控敏感内容，可安全与各类模型对话。",
            LANG_ZH_TW to "檢測完成：未發現明顯風控敏感內容，可安全與各類模型對話。",
            LANG_EN to "Audit clean: No sensitive triggers detected.",
            LANG_RU to "Проверка завершена: нарушений не обнаружено."
        ),
        "audit_flagged" to mapOf(
            LANG_ZH_CN to "检测提醒：检测到可能触发国内模型风控的内容，已自动标为敏感枝条。",
            LANG_ZH_TW to "檢測提醒：檢測到可能觸發國內模型風控的內容，已自動標為敏感枝條。",
            LANG_EN to "Safety notice: Content may trigger domestic model moderation. Tagged as sensitive.",
            LANG_RU to "Предупреждение: обнаружен контент, который может быть заблокирован цензурой."
        ),
        "decouple_sanitize_branch" to mapOf(
            LANG_ZH_CN to "一键脱敏解耦分叉",
            LANG_ZH_TW to "一鍵脫敏解耦分叉",
            LANG_EN to "Decouple & Sanitize",
            LANG_RU to "Очистить и ответвить"
        ),
        "handover_to_domestic" to mapOf(
            LANG_ZH_CN to "脱敏并切至国内模型接力",
            LANG_ZH_TW to "脫敏並切至國內模型接力",
            LANG_EN to "Sanitize & Handover",
            LANG_RU to "Очистить и переключить"
        ),
        "decouple_success" to mapOf(
            LANG_ZH_CN to "已成功脱敏解耦，历史长敏感上下文已切断，新分支可安全对话！",
            LANG_ZH_TW to "已成功脫敏解耦，歷史長敏感上下文已切斷，新分支可安全對話！",
            LANG_EN to "Decoupled successfully. Toxic history pruned, ready for clean chat!",
            LANG_RU to "Успешно очищено. Чувствительная история отсечена."
        ),
        "view_sensitive_reason" to mapOf(
            LANG_ZH_CN to "审查原因与关键词",
            LANG_ZH_TW to "審查原因與關鍵詞",
            LANG_EN to "Audit Details",
            LANG_RU to "Детали проверки"
        )
    )

    fun get(key: String, lang: String = LANG_ZH_CN): String {
        val entry = STRINGS[key] ?: return key
        return entry[lang] ?: entry[LANG_ZH_CN] ?: key
    }
}
