# 1920 年代保守 RP（Conservative RP）：公开数据的考据结论与实现取舍

本文件回答的问题是：**"网上（到底）有没有 1910s–1940s 保守 RP 的音标数据或现成转换器？"**
并记录本项目在 `etymology/rp/ConservativeRp.kt` 里的取舍。**结论：没有现成可查表的数据，
只能用规则推导，且必须明说是推导而非数据。**

---

## 一、考据（逐条核实，含证据）

### 1. Daniel Jones《English Pronouncing Dictionary》早期版——公有领域，但只有书页扫描
- 初版 *An English Pronouncing Dictionary (on Strictly Phonetic Principles)*，**1917**，J. M. Dent & Sons；
  后续 2nd(1924)、3rd(1926) 等。1917 及 1924 等 1929 年前出版的作品在美国已入**公有领域**。
- Internet Archive 有扫描件：`https://archive.org/details/in.ernet.dli.2015.14973`
  （标题 "Everymans English Pronouncing Dictionary (1917)"，Digital Library of India 扫描，586 页，600 ppi）。
  该条目元数据里 **`rights` / `licenseurl` / `possible-copyright-status` 三个字段均为空**
  （检索当时），故"公有领域"是依出版年份推断，条目本身未标许可。
- 可下载的派生物只有：`_djvu.txt`（约 1.9 MB 的 **OCR 文本**）、`_djvu.xml`、
  `_abbyy.gz`（OCR）、扫描 PDF/JP2。**没有任何经过人工校对、可直接按词查表的机器可读音标文件。**
- **关键限制**：该书的音标是 Jones 自己的转写体系，OCR 对音标符号（ə、ʊ、ɔ、ː 及合字）识别
  极不可靠。把 586 页 OCR 文本"清洗成"可用的音标词典，工作量与出错风险都很大，
  且任何一步清洗都可能引入**无法溯源**的错误——与"绝不编造音标"的底线冲突。**故本项目不采用。**

### 2. 有没有开源的（保守）RP 数据集或转换器？——没有对得上年代的
在 GitHub 上逐一核实，找得到的都是**现代** RP 资源，没有一份是 1917–1940 的保守 RP：
- `rhdunn/en-GB-x-rp.dict`：Received Pronunciation 发音词典，仓库 **license = NOASSERTION**
  （未声明，实为 espeak-ng 系来源，通常为 GPL 系），内容为**当代** RP。
- `Shavian-info/readlex`：**MIT**，但为 Shavian 字母的**拼写**词典（rhotic RP），**当代** RP，非音变数据。
- `alanackart/English-phonetic-transcription`：来源是 *Oxford Advanced Learner's Dictionary* 3rd ed.，
  **受版权保护**，且是当代音标。
- 学术检索（accent conversion 综述）表明：**规则式**口音转换是旧范式，现代研究转向神经方法；
  未见公开发布过"现代 RP → 保守 RP"的规则库或平行数据。

### 3. 中研院「小學堂」/ 其它汉语库——与本问题无关（那是汉语音韵，不是英语）

### 结论一句话
> **公开不存在**可直接下载、可直接查表的 Conservative RP（Daniel Jones 1917–1940）音标数据，
> 也**不存在**现成的"现代 RP → 保守 RP"转换器。
>
> 所以"用程序把英文转写成 1920s 口音"在**原理上可以**（下面这些音变有文献描述，可写成规则），
> 但产出的是**规则推导的近似**，**不是**从某本词典查出的真实音标。任何声称"能直接转写出
> 真实 1920s 音标"的说法，都必须先拿出那份可查表的数据；目前拿不出来。

---

## 二、本项目的实现：`etymology/rp/ConservativeRp.kt`

把**现代英音 IPA** 按一组**有文献依据的音变规则**前推为保守 RP 近似转写。
代码 KDoc、界面 `DictCoverage.NO_HIST_EN / HIST_EN_RULES` 均标注
**"规则推导的近似，不是词典数据"**，并且**不把它混进"真实音标"字段**。

| 规则 | 变化 | 语言学依据 |
|---|---|---|
| R1 | `/əʊ/ → /ou/` | Jones《EPD》早期版把 GOAT 记作 ou；`/əʊ/` 是后起记法 |
| R2 | `/ɜː/ → /əː/` | 早期 RP 把 NURSE 记作 əː；ɜː 为后起（Gimson 之后） |
| R3 | `/ɛː/ → /eə/` | 早期 RP 的 SQUARE 是 eə 双元音；ɛː 为后起的单元音化记法 |
| R4 | 双元音尾 `/ɪ/ → /i/` | 早期记音用 [i] 作 FACE/PRICE/CHOICE 的尾滑音（aɪ→ai、ɔɪ→ɔi、eɪ→ei） |
| R5 | `/ɒ/ → /ɔ/` | Jones 用 ɔ 记 LOT；ɒ 为后起记法 |
| R6 | CLOTH 类 `/ɒ/ → /ɔː/` | CLOTH 词类（off/cloth/lost/cross/long/gone 等）在 RP 中读长 ɔː（lexical set） |
| R7 | 词首 `wh-` 的 `/w/ → /ʍ/` | 保守 RP 保留 which/witch 对立（送气清音 ʍ） |
| R8 | HAPPY 尾 `/i/ → /ɪ/` | 保守 RP 的 happy 元音更短促（-y/-ey/-ee/-ie/-ies 词尾） |
| R9 | `/ʊə/` 不处理（限制） | CURE 类在保守 RP 中尚未并入 /ɔː/，但现代 IPA 若已合并则无法从前端恢复 |

**已知局限（如实登记）**：
- R6 的 CLOTH 词表**不是穷举**，未覆盖的词按一般 `/ɒ/→/ɔ/` 处理（宁可少改，不臆造）。
- R7、R8 依赖**拼写**（IPA 看不出 wh- 与 -y/-ee），对不规则拼写可能漏改。
- R9 类已发生的合并（CURE、部分 NEAR/SQUARE）**无法从现代 IPA 还原**。
- 全部输出是**近似**：可作"听起来更接近老派英音"的朗读与展示，**不可**当作音标证据引用。

---

## 三、相关实现位置
- 规则：`etymology/rp/ConservativeRp.kt`
- 数据流：`EtymologyRepository.tradIpaOf()` 把词条的现代 IPA 交给上面的转换器，
  写入 `EnglishEtymWord.ipaTrad`（预设词条的 ipaTrad 也改为同一套规则推导，不再手写无出处的字符串）。
- 界面：`etymology/ui/DictionarySections.kt` 的 `EngBlockCard` 标"传统音标（保守 RP）"与规则说明；
  发音走 `IpaAudioSynthesizer` 的 `en_trad` 分支（共振峰合成，界面上标明"非录音"）。
