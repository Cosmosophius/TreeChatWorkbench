# 第三方图标来源与许可（THIRD_PARTY_ICONS）

本文件记录 TreeChatWorkbench 本次**新增**的矢量图标
（`app/src/main/res/drawable/ic_ip_*.xml`、`ic_zi_*.xml`，共 24 个）
的来源、许可证、转换方式与图标索引。

## 许可证与出处

### 1. IconPark（字节跳动）— Apache License 2.0
- 名称：IconPark
- 仓库：<https://github.com/bytedance/IconPark>（图标位于 `source/<分类>/<名称>.svg`）
- 许可证：**Apache License 2.0**；已核对仓库根目录 `LICENSE`，开头为
  `Apache License / Version 2.0, January 2004`。
- 许可允许商用与修改，需保留版权与许可声明（本文件即为此声明）。
- 本次取用 16 个图标（见下表 `ic_ip_*`），均为逐行描边（line-art）风格。

### 2. Zico（逐浪 CMS）— MIT
- 名称：Zico（"一个专为中国开发者而生的跨平台图标解决方案"）
- 仓库：<https://github.com/zoomla/zico>
- 许可证：**MIT**，依据仓库 `package.json` 中的 `"license": "MIT"`；仓库内未单独附
  `LICENSE` 文件，如需更稳妥可向作者索取正式许可文本。
- 重要说明：Zico 只以 **webfont** 形式分发（`fonts/zi-regular.ttf/.woff/.eot` 与 SVG 字体
  `fonts/zi-regular.svg`），仓库内**不含**独立的单个 SVG 图标文件。因此本次是从
  `fonts/zi-regular.svg` 的字体字形轮廓（`<glyph unicode="&#x..;" d="...">`）提取路径，
  再按 `css/zico.css` 中的 `content:"\eXXX"` 把类名对应到码位。
- 本次取用 8 个图标（见下表 `ic_zi_*`），均为实心剪影风格。

## 图标索引（中文名 / 原文件 / 适用场景）

| 文件 | 中文名 | 来源（原文件） | 风格 | 建议使用场景 |
| --- | --- | --- | --- | --- |
| `ic_ip_seal.xml` | 印章 | source/Hardware/seal.svg | 线性描边 (stroke) | 落款/署名/权威标记、专属标识 |
| `ic_ip_pavilion.xml` | 亭台楼阁 | source/Build/chinese-pavilion.svg | 线性描边 (stroke) | 中国古典建筑、文化主题入口 |
| `ic_ip_great_wall.xml` | 长城 | source/Build/great-wall.svg | 线性描边 (stroke) | 中华文化、历史典籍主题 |
| `ic_ip_hanfu.xml` | 汉服 | source/Clothes/hanfu-chinese-style.svg | 线性描边 (stroke) | 服饰/人物/传统礼仪主题 |
| `ic_ip_tcm.xml` | 中医中药 | source/Health/traditional-chinese-medicine.svg | 线性描边 (stroke) | 中医/养生/医古文内容 |
| `ic_ip_chinese.xml` | 汉字 | source/Office/chinese.svg | 线性描边 (stroke) | 中文/汉字/语言切换 |
| `ic_ip_fan.xml` | 折扇 | source/Life/fan.svg | 线性描边 (stroke) | 雅趣/文玩/收藏 |
| `ic_ip_teapot.xml` | 茶壶 | source/Foods/teapot.svg | 线性描边 (stroke) | 茶道/饮食/生活 |
| `ic_ip_lattice.xml` | 窗棂回纹 | source/Operate/lattice-pattern.svg | 线性描边 (stroke) | 古典纹样、装饰性分隔 |
| `ic_ip_mountain.xml` | 山水 | source/Travel/mountain.svg | 线性描边 (stroke) | 山水/游记/自然 |
| `ic_ip_search.xml` | 搜索 | source/Base/search.svg | 线性描边 (stroke) | 搜索框、检索动作 |
| `ic_ip_bookmark.xml` | 书签收藏 | source/Base/bookmark.svg | 线性描边 (stroke) | 收藏/书签/加入知识库 |
| `ic_ip_volume.xml` | 喇叭朗读 | source/Music/volume-up.svg | 线性描边 (stroke) | 朗读/播报/音效开关 |
| `ic_ip_plus.xml` | 加号 | source/Character/plus.svg | 线性描边 (stroke) | 新增/添加 |
| `ic_ip_delete.xml` | 删除 | source/Edit/delete.svg | 线性描边 (stroke) | 删除/清空 |
| `ic_ip_folder.xml` | 文件夹 | source/Office/folder.svg | 线性描边 (stroke) | 文件夹/目录 |
| `ic_zi_brush.xml` | 毛笔 | fonts/zi-regular.svg （字体字形 U+E62A） | 实心剪影 (fill) | 书写/批注/作文 |
| `ic_zi_lantern.xml` | 灯笼 | fonts/zi-regular.svg （字体字形 U+E642） | 实心剪影 (fill) | 节庆/古典氛围 |
| `ic_zi_knot.xml` | 中国结 | fonts/zi-regular.svg （字体字形 U+E657） | 实心剪影 (fill) | 传统祝福/吉祥 |
| `ic_zi_bagua.xml` | 八卦太极 | fonts/zi-regular.svg （字体字形 U+E649） | 实心剪影 (fill) | 玄学/术数/哲理 |
| `ic_zi_copper_coin.xml` | 铜钱 | fonts/zi-regular.svg （字体字形 U+E660） | 实心剪影 (fill) | 古钱/典故/财货 |
| `ic_zi_incense.xml` | 香炉 | fonts/zi-regular.svg （字体字形 U+E62E） | 实心剪影 (fill) | 香道/静心/书房 |
| `ic_zi_umbrella.xml` | 油纸伞 | fonts/zi-regular.svg （字体字形 U+E643） | 实心剪影 (fill) | 江南/民俗/意象 |
| `ic_zi_papercut.xml` | 剪纸 | fonts/zi-regular.svg （字体字形 U+E64B） | 实心剪影 (fill) | 民俗艺术/节庆 |

## 转换规格

- 所有图标：`<vector android:width="24dp" android:height="24dp"
  android:viewportWidth="48" android:viewportHeight="48">`。
- 颜色统一为 `#FF000000`，**在代码里用 `tint` 即可重新上色**
  （`Icon(painterResource(R.drawable.ic_ip_seal), null, tint = ...)` 或
  `ColorFilter.tint(...)`；`android:tint` 对描边与填充同样生效）。
- **IconPark 图标用 `android:strokeColor`（而非 `fillColor`）**：IconPark 的源文件是
  描边型线稿，直线与曲线若改用填充会变成实心色块甚至完全不可见（例如
  `Operate/lattice-pattern` 全是短直线，填充面积为零）。故保留其描边语义
  （`strokeWidth=4`、圆角线端），颜色同样写成黑色，可被 tint 覆盖。
- **Zico 图标用 `android:fillColor`**：字形轮廓本身就是闭合实心图形，直接填充即可。
- 未保留 SVG 中的 `<style>`/`class`/`clip-path`/`<defs>`；未引入任何 `<group>` 嵌套
  （龙形图标 `Animals/dragon-zodiac` 带的一层 `clip-path` 组因未被采用而不涉及）。
- `pathData` 直接取自 SVG 的 `d`；IconPark 中的 `<rect>/<circle>` 已手工折算成等价
  路径（圆角矩形用 `A` 圆弧、圆用双弧）。

## 为什么说明文档不放在 `res/drawable/` 下

本仓库的说明文件放在**仓库根目录**（`THIRD_PARTY_ICONS.md`、`ICON_INDEX.md`），
而不是 `app/src/main/res/drawable/ICON_INDEX.md`。原因是 AGP 资源合并对 `res/`
子目录中的非资源文件较敏感，把 `.md` 放进 `res/drawable/` 有触发资源编译/合并
报错的风险（只能保证 aapt2 单文件编译通过，无法在本次会话中验证 AGP 合并行为，
因为不允许运行 gradle）。内容与放置于 `res/drawable/` 时完全相同。

## 本次核验范围（如实说明）

已核验：
- 24 个 drawable 逐个执行 `aapt2 compile`（Android SDK build-tools 34.0.0），
  全部返回退出码 0。
- 24 个文件逐个通过 XML 良构解析；并断言：`width/height=24dp`、
  `viewport=48`、颜色token全部为 `#FF000000`、无 `<group>`/`<style>`/`<defs>`/
  `clip-path`/`fill-rule` 残留、`ic_zi_*` 只有一个 `fillColor`、`ic_ip_*` 含
  `strokeColor` 且无 `fillColor`。
- 24 个图标的几何全部经 `qlmanage` 光栅化目视检查，确认形状可识别、方向正确、
  大小一致、无空图。

未核验（需你在接界面时自行确认）：
- 在真机/模拟器上的实际渲染效果（`tint` 后的观感、与既有 Material 图标混排的效果）。
- AGP 资源合并对根目录 `.md` 之外的任何构建集成问题。
- 未运行 gradle（按任务要求），故未做整包构建验证。
