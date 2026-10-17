# 免 API Key 的联网搜索：原理、边界与本项目落地

> 本文说明「不填任何 API Key 也能联网检索」是怎么成立的、边界在哪、
> 为什么它**与 proot 无关**、主流 agent（ZCode / DSH 这类）各自怎么做，
> 以及本项目（TreeChatWorkbench）实际落地的通路优先级。
>
> 对应代码：`app/src/main/java/com/treechat/workbench/network/search/`
> （`WebSearchClient.kt`、`HtmlSearchResultParser.kt`）。

---

## 0. 结论先行

1. **免 Key 搜索的本质是"客户端自己发 HTTP 请求"**。请求的是搜索引擎**面向浏览器的公开页面**
   （`https://html.duckduckgo.com/html/?q=…`）或**公开的只读 JSON API**
   （`https://zh.wikipedia.org/w/api.php?action=query&list=search&…`）。打开一个网页从来不需要密钥，
   客户端只是把"人用浏览器看的那一页"取回来，再用解析器把标题/链接/摘要抠出来。
2. **它不需要 proot，也不需要 root 或任何原生 Linux 环境**。一个 OkHttp 请求 + 一个正则解析器就够了，
   这两者都是纯 JVM 库，直接编进 APK 就能跑。
3. **本项目在零配置（一把 Key 都没有）时的可用通路**：
   DuckDuckGo HTML 端点 → 搜狗移动端 → SearXNG 公共节点池 → Bing → Wikipedia。
4. **全部失败时如实报错，不返回空结果假装成功**。这是本文档与代码共同约定的铁律（见 §5.2）。

---

## 1. 原理：为什么这些搜索不需要 Key

### 1.1 三类"天然不需要 Key"的来源

| 类型 | 例子 | 为什么不用 Key |
|---|---|---|
| **元搜索/聚合器** | 自建或公共 SearXNG 实例 | 它自己是"客户端"，把 Google/Bing/DDG 等上游结果聚合后以 JSON/HTML 呈现。它是**服务**，不是**授权渠道**，因此不校验调用者身份 |
| **公开 HTML 端点** | `html.duckduckgo.com/html/`、`lite.duckduckgo.com/lite/`、`cn.bing.com/search`、`m.sogou.com/web/searchList.jsp` | 这些页面本来就是给浏览器用的。取回 HTML 只是"模拟浏览器"，不涉及任何凭据 |
| **公开只读 JSON API** | MediaWiki Action API（Wikipedia）、各开源项目的公开 API | 官方明确提供匿名只读调用（MediaWiki 只要求带一个有意义的 `User-Agent`，不要求 Key） |

### 1.2 关键区分："没有 Key"≠"官方授权"

前两类本质上是在**复用人类浏览器的公开界面**，第三类才是官方允许的匿名 API。三者的共同点是
**都不需要凭据**，但只有第三类有"官方承诺"可言。这个差别决定了它们的稳定性完全不同：

| | 需要 Key 的商业搜索 API（Tavily / Bing Web Search / Google CSE） | 免 Key 的公开端点 |
|---|---|---|
| 身份校验 | 有（你是谁、配额多少） | 无（谁都能打） |
| 响应结构 | 有契约、有版本、有文档 | 无契约，说改就改 |
| 限流 | 明确的 QPS/月度配额，超额报 429 | 隐式风控，超频可能直接返回**验证码页**（HTTP 200，但没有结果） |
| 可用性承诺 | 有 SLA | 无 SLA，实例随时下线 |
| 结果质量 | 有排序语义、有正文抽取 | 只有摘要，且很多实例不开放 JSON 输出 |

---

## 2. 局限（用之前必须知道的四件事）

1. **没有 SLA，页面改版即失效**。Bing / DuckDuckGo 换一次 class 名，解析器就可能整体失效 ——
   这是"抓网页"方案的结构性风险，不是实现缺陷。
2. **限流常常表现为"成功但空"**，而不是 4xx。被风控时服务器会返回 200 + 验证码页或空结果页。
   因此**"HTTP 200 但没有解析出结果"**是一个需要单独识别的失败态（本项目已单独报错，见 §6）。
3. **地域与网络**：`duckduckgo.com`、`wikipedia.org` 在**中国大陆直连不可达**；
   `m.sogou.com`、`cn.bing.com` 在国内可达但海外可能慢或不通。**没有一条通路是全球通吃的** ——
   这正是必须做多通路降级、而不是"选一个最好的引擎"的原因。
4. **只拿到摘要，不是正文**。搜索给的是"哪个网页可能相关"，不是"网页里写了什么"。
   想读正文是另一件事（reader/抓取能力），不在本文范围。

另外一点实现层面的现实：**SearXNG 的 `format=json` 默认是关闭的**。官方默认只输出 HTML，
实例管理员需要在 `settings.yml` 里显式开启 `json` 格式。所以公共节点池里"有的节点可用、
有的节点 403、有的返回 HTML 而不是 JSON"是常态，必须逐个降级（见 §5.2）。

---

## 3. 为什么原生 Android agent 不需要 proot

### 3.1 proot 是什么，用来解决什么

proot 是一个**纯用户态的系统调用重定向工具**：它用 `ptrace` 把 Linux 程序发出的
文件路径、`chroot`、`uid/gid` 之类的请求"翻译"成 Android 文件系统里的一次次真实调用，
从而让一个**未经修改的 Linux 发行版（及其命令行程序）**在 Android 上跑起来。
它解决的是 **"我要跑一个只提供 Linux 命令行形态的工具"** 这个问题 ——
典型场景是 Termux / DSH 这类把 Python、bash、git、ffmpeg 搬进手机的需求。

### 3.2 联网搜索需要的是什么

联网搜索需要的东西只有两样：

1. **一个 HTTP 客户端**（发请求、带 User-Agent、设超时、跟随重定向）；
2. **一个解析器**（把 JSON 或 HTML 变成 `title / url / snippet`）。

这两样在 Android 上是**现成的 SDK 级能力**：OkHttp 与 Gson 都是纯 Java/Kotlin 实现，
`compileSdk` 需要的 API 早已包含，直接进 APK 即可。整个链路里**没有一行需要 native 代码、
需要 root、或需要一个 Linux 用户态环境**。

### 3.3 所以两者是正交的

| | proot 方案 | 原生 HTTP 方案（本项目） |
|---|---|---|
| 解决的问题 | 跑 Linux 命令行程序 | 发 HTTP 请求、解析结果 |
| 依赖 | 一份几十 MB 的 rootfs + ptrace | OkHttp + Gson（纯 JVM 库） |
| 运行形态 | 额外进程/环境，涉及保活与兼容性 | 与应用同进程，就是普通网络调用 |
| 网络能力 | 内部程序自己发请求 | 应用自己发请求，**完全一样** |

**结论：能用 OkHttp 直接发 HTTP 请求的应用，没有任何理由为了"联网搜索"引入 proot。**
只有当你要执行的工具**只以 Linux 命令行程序的形式存在**时，proot 才是必要的。
搜索不属于这种情况。

> 本项目实证：`WebSearchClient` 的依赖只有 OkHttp + Gson + kotlinx.coroutines，
> 零 native 库、零 root、零 proot。

---

## 4. 主流 agent（含 ZCode、DSH 这类）通常怎么做

有且只有两种模式，区别在于**"搜索到底在哪台机器上执行、Key 在谁手里"**。

### 4.1 模式 A：服务端 / 厂商内置搜索工具

代表：Anthropic Messages API 的**服务端工具** `web_search_20250305`（DeepSeek 官方端点亦兼容此协议）、
各家模型厂商提供的 `web_search` 类工具、Perplexity 等自带检索的产品。

- **执行位置**：检索、抓取、排序、摘要全在**厂商后端**完成。
- **Key 在谁手里**：**服务方**。客户端请求里只是"声明我要用这个工具"，
  凭据是厂商自己的，客户端不持有任何搜索服务商的 Key。
- **返回什么**：已经是结构化的引用块（`url` + `title` + `cited_text`），
  甚至附带模型基于这些资料写好的回答。
- **客户端能否"复用它的代码"**：**不能**。它是服务方的后端能力，不开源、不可下载。
  客户端唯一能做的是**通过支持该工具的协议去调用它**（发一个带 `tools` 字段的请求）。

> 本项目的 `SearchBackend.NATIVE` 就是这条路的客户端：向 `/messages` 端点发
> `tools: [{ type: "web_search_20250305", name: "web_search", max_uses: N }]`，
> 再把响应里的 `web_search_tool_result` 块与 `citations` 映射成 `SearchResult`。
> 换句话说，**这条路的"搜索能力"是借来的，不是自己实现的**。

### 4.2 模式 B：客户端自建 / 自选搜索后端

代表：ZCode、Codex、Cursor 这类 **"客户端实现搜索工具，用标准 function calling 让模型按需调用"** 的
agent，以及所有自建 SearXNG 的方案。

- **执行位置**：客户端（或客户端指定的第三方搜索服务）。
- **Key 在谁手里**：客户端自己（自建 SearXNG 则完全不需要 Key；
  用 Tavily/Bing API 则需要客户端配置 Key）。
- **返回什么**：客户端把 HTTP 响应解析成统一的检索结果，再压成一段文本作为
  `tool_result` 回填进对话，由模型决定怎么引用。
- **能复用的到底是什么**：**只有"搜索通路 + 结果解析"这一层** ——
  也就是"该请求哪些端点、带什么请求头、失败了怎么降级、HTML 里的哪几个 class 是标题/链接/摘要"。
  这部分是公开信息与通用工程做法，谁都能照做，也正是本项目 `WebSearchClient`
  与 `HtmlSearchResultParser` 所实现的东西。

### 4.3 一句话说清"能复用"与"不能复用"

| 对象 | 能否复用 | 说明 |
|---|---|---|
| 公开端点 + 请求方式 + 降级顺序（搜索通路） | **能** | 公开信息，直接照做 |
| HTML → `title/url/snippet` 的解析规则 | **能** | 通用工程做法 |
| 厂商内置搜索工具的**后端能力** | **不能** | 那是它的服务和它的 Key，只能"调用"，不能"搬走" |
| 厂商内置搜索工具的**响应结构** | **不能** | 私有协议，需按其规范解析（本项目按 Anthropic `web_search_*` 结构实现） |

### 4.4 本项目的取舍

两条通路**并存**，而不是二选一：

- `NATIVE`：借你**已有的**那把聊天 Key，走服务商后端拿高质量结果 —— 零额外成本，但有前提
  （服务商/中转站真的实现了该服务端工具）。
- **免 Key 公开端点**：一把 Key 都没有、或用的是小米 MiMo / 标准 OpenAI 协议 / 不支持服务端搜索的
  中转站时，仍然能检索。

---

## 5. 本项目落地

### 5.1 通路优先级与降级顺序

数值越小越先试；**任一步拿到非空结果就返回**，不再往下走。

| 序 | 通路 | 实际请求的端点 | 需要 Key？ | 退到下一级的条件 |
|---|---|---|---|---|
| **P0** | 服务商原生搜索（`NATIVE`） | `<base>/v1/messages` + `tools:[web_search_20250305]`；命中 DeepSeek Key 时用 `https://api.deepseek.com/anthropic/v1/messages` | **用你已有的聊天 Key**（无独立搜索 Key） | 无 Key / 协议不是 Anthropic / 端点 404 / 未返回结果 |
| **P1** | DuckDuckGo HTML（`DUCKDUCKGO` 及免 Key 管道首步） | `https://html.duckduckgo.com/html/`（先 **POST**，再 **GET**），最后退 `https://lite.duckduckgo.com/lite/` | 否 | 三个端点全部失败或全部解析不出结果 |
| **P2** | 搜狗移动端（`SOGOU`） | `https://m.sogou.com/web/searchList.jsp?keyword=…` | 否 | 请求失败或 0 条结果（海外网络常见） |
| **P3** | SearXNG 公共节点池 | `searx.be` / `baresearch.org` / `search.ononoki.org` / `searx.tiekoetter.com` 的 `/search?format=json`，**逐个试** | 否 | 池内**无一节点**返回可用结果（见 §5.2） |
| **P4** | Bing（`BING`） | `https://cn.bing.com/search?q=…`，空结果退 `https://www.bing.com/search?q=…` | 否 | 请求失败或 0 条结果 |
| **P5** | Wikipedia | `zh.wikipedia.org` → `en.wikipedia.org` 的 `w/api.php?action=query&list=search` | 否 | 两个语言都失败或 0 条结果 |

两条**旁路**（用户显式指定时只走它，不做静默替换）：

| 通路 | 端点 | Key | 失败行为 |
|---|---|---|---|
| 自建 SearXNG（用户填了实例地址） | `<你填的地址>/search?format=json` | 否 | 直接报错，不会偷偷换成别的引擎 |
| Tavily（`TAVILY`） | `https://api.tavily.com/search` | **需要** | 未填 Key 直接报错 |

两点补充说明：

- **总时间预算 20 秒**（`WebSearchClient.PIPELINE_BUDGET_MS`）。免 Key 管道里的每条通路各有自己的
  超时，但整条管道共享一个总预算：到点就停止剩余通路，并把"未尝试（超出时间预算）"如实写进失败原因。
  否则"每条通路各自超时"最坏能拖到一分钟，而用户在界面上只看到"正在检索…"转圈。
- **顺序是按"大概率最快可用"排的**，不是按"质量"排：国内网络下 P1 会先失败（约 5 秒），
  随后 P2 几乎必然命中；海外网络下 P1 第一步就命中。

### 5.2 失败语义（本项目的铁律）

> **只要有一条通路正常响应（哪怕是 0 条结果），就返回空列表 —— 这是真实的"零结果"。
> 如果一条都没成功，就抛异常，并把每条通路的失败原因拼进错误消息。
> 绝不返回空列表冒充"搜索无结果"。**

为什么这条约定重要：把"四个节点全 403"报成"搜索无结果"，用户会以为**网上真没这资料**，
进而怀疑模型在胡说；而真实原因是网络/风控，排查方向完全被带偏。现在错误消息形如：

```
联网检索全部失败（共尝试 5 个免密钥通道）：
DuckDuckGo → DuckDuckGo 全部端点失败：html(POST)：搜索请求失败：HTTP 403；…
搜狗 → 搜索请求失败：HTTP 403；
SearXNG 节点池 → SearXNG 公共节点池全部失败：https://searx.be → HTTP 403；…
Bing → 返回 0 条结果；
Wikipedia → zh.wikipedia.org → 搜索请求失败：HTTP 403
```

用户一眼能看出"是全部被墙/被风控"，而不是"网上没资料"。

### 5.3 结果解析器覆盖了哪些结构

解析逻辑集中在 `HtmlSearchResultParser.kt`，**零第三方依赖**（工程里已有 okhttp + gson，
不引入 Jsoup 之类的 HTML 解析库）。覆盖：

1. **DuckDuckGo HTML 端点** `html.duckduckgo.com/html/`
   - `a.result__a` —— 标题 + 链接
   - `a.result__snippet` / `div.result__snippet` —— 摘要
   - 按"标题锚点 → 其后的第一段摘要"配对（而不是两个列表按下标硬配，避免有一条缺摘要就整体错位）
2. **DuckDuckGo Lite 端点** `lite.duckduckgo.com/lite/`
   - `a.result-link` + `td.result-snippet`（表格布局，交替出现，就近配对）
3. **Bing 结果页**
   - `li.b_algo` / `div.b_algo` 结果块 + 块内 `h2 > a` 标题 + `p` 摘要
   - 一条都没切出来时退化为整页 `h2 > a` 宽松提取（Bing 改过 class 名的历史发生过）
4. **跳转链接还原**（不让模型拿到点不开的跳板地址）
   - DuckDuckGo：`//duckduckgo.com/l/?uddg=<urlencoded>` → 真实 URL
   - Bing：`https://www.bing.com/ck/a?…&u=a1<base64url>` → base64url 解码
   - 协议相对地址 `//host/path` → 补 `https:`
   - 还原后仍指向搜索引擎自身的内链（广告、`y.js` 统计跳板）**直接丢弃**
5. **HTML 实体解码**：命名实体 + 十进制 + 十六进制数字实体（`&amp;` `&#39;` `&#x27;` …），
   避免标题里出现 `&#39;` 这类字面量

解析器只负责"尽力提取"，**取不到就返回空列表、绝不抛异常**；
"这条通路算不算失败"由 `WebSearchClient` 判定。

### 5.4 与调用方的接口（未改动，故上层零改动）

- `SearchResult(title, url, snippet)` 字段语义与顺序**完全不变**
  （`ChatMessageView` 用 Gson 反序列化它，改字段会破坏历史消息里的来源卡片）。
- `WebSearchClient.search(query, config, provider)`、`probe(config, provider)`、
  `formatForModel(results)` 的签名不变。
- 失败通过 `Result.failure` 上传，`AiStreamClient` 会把它变成 `ChatEvent.SearchFinished(error=…)`
  并在对话里以"搜索失败：…"回填给模型（不会让模型误以为检索成功）。
- 解析出来的结果仍由 `formatForModel()` 压成 `[编号] 标题 / URL / 摘要` 的文本喂给模型，
  并要求模型用 `[编号]` 标注来源。

---

## 6. 排错指引

1. **设置 → 联网搜索 → 逐个后端测试**（`WebSearchClient.probe`）。它会真的发请求，
   把每个后端的**实际请求地址、状态码、失败原因**摊开 —— 比只看一句"搜索失败"有用得多。
2. 常见现象对照：

   | 现象 | 含义 | 处置 |
   |---|---|---|
   | `HTTP 403` / `HTTP 429` | 被风控/限流 | 换通路（本就会自动降级），或降低频率 |
   | `返回了页面但未解析出结果` | 拿到了验证码页/无结果页，或页面结构已改版 | 换通路；若多个通路同时出现，说明 code 需要跟着页面改 |
   | `公共节点池全部失败` | 该网络下所有 SearXNG 节点不可达，或节点都没开 `format=json` | 自建一个 SearXNG 实例并填地址 |
   | `未尝试（超出时间预算）` | 前面几条通路把 20 秒预算耗尽了 | 属预期行为；可换更快的网络或改用 NATIVE |
   | 国内网络下 DuckDuckGo / Wikipedia 失败 | 这两个域名在国内直连不可达 | **正常现象**，预期由 P2/P4 兜底 |

3. **国内网络的预期路径**：P1 失败（约 5 秒）→ P2 命中。看到"先失败一次再出结果"是设计使然。

---

## 7. 刻意不做的事

- **不在代码里内联任何真实 Key**。免 Key 通路里根本没有 Key 这个字段；Tavily 的 Key 只存用户本地配置。
- **不引入新的 Gradle 依赖**（不加 Jsoup、不加 HTML 解析库、不加搜索 SDK）。
- **不抓取落地页正文**。搜索只负责"找到哪些链接"，"读出正文"是另一件事。
- **不做静默替换**。用户显式选了某个后端，就用那个后端；它失败就如实报错，
  不会偷偷换成别的引擎再假装是它返回的结果。
- **不把公共实例的可用性当作承诺**。本文档与代码里的任何端点都可能明天就失效，
  这正是"多通路 + 如实报错"这套设计存在的原因。
