# 中古 / 上古汉语音韵扩充：资产、字段来源与许可

本次在既有 `assets/han_phonology.tsv`（Baxter-Sagart 2015，3,955 字）**之外并列新增**两张资产。
**既有资产一行未动**。下面逐字段写清来源、许可与覆盖；末尾列出**没拿到**的源与原因。

---

## 1. `assets/han_mc_guangyun.tsv` —— 中古（切韵 / 廣韻）

列序：`字 \t 韻目 \t 音韻地位 \t 反切 \t 高本漢 \t 王力 \t 潘悟雲 \t unt \t msoeg`
覆盖 **12,722 字**（多音字各读音用 ` / ` 并列）。

| 字段 | 来源 | 许可 | 性质 |
|---|---|---|---|
| 韻目 | nk2028/**tshet-uinh-data** `韻書/廣韻.csv`（澤存堂本《廣韻》，含校本订正） | **CC0-1.0** | 真实韵书数据 |
| 音韻地位 | 同上（形如 `影開三A真入`：母·呼·等·類·韻·調） | CC0-1.0 | 真实韵书数据 |
| 反切 | 同上 | CC0-1.0 | 真实韵书数据 |
| 高本漢 / 王力 / 潘悟雲 / unt / msoeg | nk2028/**tshet-uinh-examples** 的推导器 `karlgren.js / wangli.js / panwuyun.js / unt.js / msoeg_v8.js`，按上表「音韻地位」**逐条算出** | **MIT** | **规则推导**，非逐条抄录 |

- 上游：`https://github.com/nk2028/tshet-uinh-data`（CC0-1.0）、`https://github.com/nk2028/tshet-uinh-examples`（MIT）。
- 推导方式：把 3,590 个不同「音韻地位」喂给各推导器，得到该地位的拟音，再按字回填。
  **各栏保留推导器自己的记号**（如王力的 `꜀꜂꜄꜆` 调号、潘悟雲的 `˧˥˩` 调值、unt 的 `t̙uŋ`），未做二次改写。
- 抽字时按《廣韻》条目逐字取，只保留**单个 CJK 统一表意字**；条目里 `［］｛｝〈〉｀` 等校勘标记已剥除。
- **白-沙中古转写不在本文件里**（仍在 `han_phonology.tsv`），避免两处冲突。

## 2. `assets/han_oc_scholars.tsv` —— 上古（先秦）

列序：`字 \t 王力 \t 李方桂 \t 郑张尚芳 \t 许思莱`
覆盖 **14,315 字**。

来源：**direct-phonology/rephon**（`https://github.com/direct-phonology/rephon`，**MIT**），
其 `data/raw/source_statement.txt` 逐份记录了上游与许可：

| 字段 | rephon 内文件 | 上游原始来源 | 许可（按 rephon 记录） |
|---|---|---|---|
| 王力 | `data/segmented/先秦_王力系統.json`（6,964 条） | 李珍華、周長楫《漢字古今音表·修訂本》（中華書局 1999）王力体系 | CC0-1.0（按「小學堂」文件编号），**见下"许可提醒"** |
| 李方桂 | `data/segmented/先秦_李方桂系統.json`（11,167 条） | 李方桂《上古音研究》体系 | CC0-1.0（同上），**见下"许可提醒"** |
| 郑张尚芳 | `data/raw/zhengzhang_collapsed.tsv`（`old_chinese` 列，约 18,099 行） | 《上古音系》体系 | 按 rephon 仓库 **MIT**（rephon 未单列该文件的 source statement，**属登记不全**） |
| 许思莱 | `data/raw/schuessler_OCMtab.tsv`（`syl_bok` 列，10,113 行） | Schuessler, "Some Old Chinese and Han Chinese Reconstructions", **Zenodo**，DOI `10.5281/zenodo.14567004` | **CC0-1.0**（作者本人发布） |

**许可提醒（如实登记，务必保留）**：
- 王力、李方桂两份 xlsx 由 rephon 转自**台湾中研院「小學堂」**的数字化成果；
  rephon 与「小學堂」文件编号体系均标注 CC0-1.0，**但「小學堂」官网本身声明
  "数据库整体受著作权法保护、授权方式仍在研议"，仅字形图片为 CC0**。
  因此这两栏属**许可存在歧义**的第三方整理数据，使用/再分发时应保留来源标注并自行评估。
- 郑张尚芳一栏 rephon 未单列 source statement，来源与许可**登记不全**（引 rephon 的 MIT 为准）。

---

## 3. 代码接入
- 索引：`etymology/data/HanHistoricalIndex.kt`（加载上述两文件，KDoc 内同样写明来源与许可）。
- 模型：`etymology/data/EtymologyModels.kt` 的 `MiddleChineseData` / `OldChineseReconstruction`
  **新增字段**（`guangyunPosition / karlgen / panWuyun / unt / msoeg`、`schuessler`），旧字段不动。
- 装配：`etymology/repository/EtymologyRepository.cmnDataOf()` 按字回填；
  **各家拟音一律取可溯源的数据集**，词库没有的字段一律"未收录"。
- 界面：`etymology/ui/DictionarySections.kt` 的 `HanBlockCard` 逐行标出来源
  （`廣韻 · nk2028 CC0`、`规则推导 · tshet-uinh MIT`、`rephon · MIT`）。
- 加载：`TreeChatApp.kt` 只加了**一行** `HanHistoricalIndex.ensureLoaded(...)`。

---

## 4. **没拿到**的源（如实说明，未编造替代数据）

| 候选源 | 结果 |
|---|---|
| **复旦大学 东亚语言数据资源库**（ccdc.fudan.edu.cn，潘悟云团队） | **不可用**：页面版权声明为"**保留所有权利，不经允许请勿挪用**"，且查询需登录；无公开批量下载。 |
| **中研院「小學堂·漢字古今音資料庫」** | **未批量抓取**：官网声明数据库受著作权保护、授权"仍在研议"（仅字形图片 CC0），无官方批量下载页；其 王力/李方桂 数据仅经第三方向接获得（见上"许可提醒"）。 |
| **北大 OCPD 汉语上古音查询系统**（ccl.pku.edu.cn:8088/OCPD） | **不可用**：连接返回空响应（服务不可达），且未见许可声明。 |
| **古音小镜 kaom.net「諸家上古音表」** | **未采用**：站点原创内容标 **CC BY 4.0**，但其上古音表底层是《漢字古今音表》《上古音研究》等**第三方专著数据**，且为交互查询页（有 3 秒点击限流），批量抓取既不完整也越界。 |
| **digling/rhymes（詩經韻段多学者标注）** | **未采用**：仓库**未声明许可**（默认保留所有权利）。 |
| **MCPDict（MIT）** | **未采用**：含 20,902 字的**中古音**（廣韻系罗马字）与各方言，但**无上古音、无广韵音韻地位/反切**，与本库 BMP 资产的字段不重合；其现代方言列超出本次范围。 |
| **郑张尚芳 / 王力 / 李方桂 拟音的"原书"电子版** | 未见任何**宽松许可**的官方电子数据；现取的是第三方整理（rephon / 小學堂系）。 |

> 结论：中古侧拿到了 **CC0 的廣韻真实数据 + MIT 的多家推导拟音**；上古侧拿到了
> **王力 / 李方桂 / 郑张尚芳 / 许思莱 四家**（其中王力、李方桂的许可存在歧义，已登记）。
> 用户点名的**复旦**库与**小學堂**官方批量下载**都没拿到**，原因如上，**未用猜测数据填补**。
