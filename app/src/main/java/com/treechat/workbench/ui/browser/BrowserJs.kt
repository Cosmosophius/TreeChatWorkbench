package com.treechat.workbench.ui.browser

import org.json.JSONObject

/**
 * 内置轻量浏览器注入到第三方网页里的**全部**脚本与样式，集中在这一个文件里。
 *
 * 为什么要集中：往别人的页面里塞代码是本工程里侵入性最强的操作，
 * 审计时必须能一眼看全"到底注入了什么"。任何新增的注入都必须写在这里，
 * 不允许散落在 UI 代码里用字符串拼接。
 *
 * 纪律（与 A 线 WebReaderActivity 的桥接写法一致）：
 * 1. 只注入**只读**的监听与样式，绝不 preventDefault、绝不劫持网页交互；
 * 2. 不注入任何输入框、不注入按钮 DOM，选词工具栏由 Compose 侧绘制；
 * 3. 样式只加一个 `<style>` 节点 + 两个 html 属性，一键还原就是删掉它们；
 * 4. 脚本内不使用 `$` 字符（Kotlin 原始字符串里 `$` 会触发模板展开）。
 */
internal object BrowserJs {

    /** JS 桥对象名，必须与 addJavascriptInterface 注册的名字逐字一致。 */
    const val BRIDGE_NAME = "TreeChatBrowser"

    /** 启动主页：默认使用必应 (Bing)。 */
    const val START_URL = "https://cn.bing.com"

    /** 阅读模式注入的 style 节点 id；还原时按它精确删除。 */
    const val READER_STYLE_ID = "tc-reader-mode-style"

    /** 全网强制深色模式的 style 节点 id。 */
    const val GLOBAL_DARK_STYLE_ID = "tc-global-dark-style"

    /** 阅读模式标记属性名（挂在 documentElement 上）。 */
    const val READER_ATTR = "data-tc-reader-mode"

    /** 阅读模式深色标记属性名。 */
    const val READER_DARK_ATTR = "data-tc-reader-dark"

    // ========================================================================
    // 1) 选词监听
    // ========================================================================

    /**
     * 选词监听脚本。幂等（重复注入直接 return），捕获阶段监听，不消费任何事件：
     *
     * - `selectionchange`：鼠标/键盘改选区时的兜底（带 180ms 防抖）；
     * - `mouseup` / `touchend`：真机上长按拖选结束的主要来源；
     * - `scroll`（捕获）：滚动后选区相对视口的矩形会变，需要重新上报，
     *   否则工具条会停在旧位置。
     *
     * 回传的矩形一律取自 `Range.getClientRects()` / `getBoundingClientRect()`，
     * 它们是**视口相对**的 CSS 像素（滚动量已经算进去了），因此 Kotlin 侧
     * 不需要再加 scrollY —— 这正是换算最容易写错的地方。
     *
     * 同时回传 `factor = window.devicePixelRatio`，它是"1 个 CSS 像素等于几个
     * 物理像素"的权威答案。**不要用 WebView.getScale()**：Android 4.4 之后
     * 它基本恒为 1，乘上去会算错；devicePixelRatio 才是等价于旧 scale 的那个比值
     * （WebView 默认把页面按 ~160dpi 基准缩放，devicePixelRatio == density）。
     */
    const val SELECTION_LISTENER = """
(function () {
  if (window.__tcBrowserSelectionHook) { return; }
  window.__tcBrowserSelectionHook = true;

  // 强力解除各类百科 (如 Minecraft Wiki)、文档站点设置的 -webkit-user-select: none 选词禁锢
  try {
    var unlockStyle = document.createElement('style');
    unlockStyle.id = '__tc_unlock_user_select';
    unlockStyle.textContent = '* { -webkit-user-select: text !important; user-select: text !important; -webkit-touch-callout: default !important; }';
    (document.head || document.documentElement).appendChild(unlockStyle);
  } catch(e) {}

  var lastKey = '';
  var timer = null;

  function pixelFactor() {
    var f = window.devicePixelRatio;
    if (!f || f <= 0) { f = 1; }
    return f;
  }

  function emptyPayload() {
    return {
      text: '',
      left: 0,
      top: 0,
      right: 0,
      bottom: 0,
      factor: pixelFactor(),
      url: String(location.href || ''),
      title: String(document.title || '')
    };
  }

  function unionRect(sel) {
    if (!sel || sel.rangeCount < 1) { return null; }
    var range = sel.getRangeAt(0);
    var rects = range.getClientRects();
    if (rects && rects.length > 0) {
      var l = rects[0].left;
      var t = rects[0].top;
      var r = rects[0].right;
      var b = rects[0].bottom;
      for (var i = 1; i < rects.length; i++) {
        if (rects[i].left < l) { l = rects[i].left; }
        if (rects[i].top < t) { t = rects[i].top; }
        if (rects[i].right > r) { r = rects[i].right; }
        if (rects[i].bottom > b) { b = rects[i].bottom; }
      }
      if (r > l && b > t) {
        return { left: l, top: t, right: r, bottom: b };
      }
    }
    var box = range.getBoundingClientRect();
    if (box && (box.right > box.left || box.bottom > box.top)) {
      return { left: box.left, top: box.top, right: box.right, bottom: box.bottom };
    }
    // 关键修复：长按未滑动时内核可能返回 0 宽 Range，若选区文本非空，
    // 从其父容器或起始节点提取坐标兜底，确保无需滑动也能秒定气泡！
    var node = range.startContainer;
    var el = (node && node.nodeType === 1) ? node : (node ? node.parentElement : null);
    if (el && el.getBoundingClientRect) {
      var pBox = el.getBoundingClientRect();
      if (pBox && (pBox.bottom > pBox.top)) {
        return { left: pBox.left, top: pBox.top, right: pBox.right, bottom: pBox.bottom };
      }
    }
    return null;
  }

  function buildPayload() {
    var sel = null;
    try { sel = window.getSelection(); } catch (e) { sel = null; }
    var text = sel ? String(sel) : '';
    text = text.replace(/[ \t\r\n]+/g, ' ').trim();
    if (!text) { return emptyPayload(); }
    var r = unionRect(sel);
    if (!r) { return emptyPayload(); }
    return {
      text: text.slice(0, 4000),
      left: r.left,
      top: r.top,
      right: r.right,
      bottom: r.bottom,
      factor: pixelFactor(),
      url: String(location.href || ''),
      title: String(document.title || '')
    };
  }

  function send() {
    var p;
    try { p = buildPayload(); } catch (e) { p = emptyPayload(); }
    var key = p.text + '|' + Math.round(p.left) + '|' + Math.round(p.top) + '|'
      + Math.round(p.right) + '|' + Math.round(p.bottom) + '|' + p.url;
    if (key === lastKey) { return; }
    lastKey = p.text ? key : '';
    var bridge = window.TreeChatBrowser;
    if (bridge && bridge.reportSelection) {
      try { bridge.reportSelection(JSON.stringify(p)); } catch (e2) { }
    }
  }

  function schedule(delay) {
    if (timer) { clearTimeout(timer); }
    timer = setTimeout(send, delay);
  }

  // 触屏选词冻结窗口：触屏长按选中后，系统会补发若干空选区帧
  // （selectionchange 空文本 / touchend 延迟采样），把刚出现的选区冲掉。
  // 记录最近一次触屏非空选区时间，800ms 内的空帧一律不上报。
  var lastTouchSelTime = 0;

  function send() {
    var p;
    try { p = buildPayload(); } catch (e) { p = emptyPayload(); }
    if (!p.text && (Date.now() - lastTouchSelTime) < 800) { return; }
    var key = p.text + '|' + Math.round(p.left) + '|' + Math.round(p.top) + '|'
      + Math.round(p.right) + '|' + Math.round(p.bottom) + '|' + p.url;
    if (key === lastKey) { return; }
    lastKey = p.text ? key : '';
    var bridge = window.TreeChatBrowser;
    if (bridge && bridge.reportSelection) {
      try { bridge.reportSelection(JSON.stringify(p)); } catch (e2) { }
    }
  }

  // 鼠标/触控板与触屏选区监听：
  // 采用多重渐进采样（长按抬手后 30ms、120ms、260ms 依次锁定）：
  // 彻底根治"长按后不会自动固定、得滑动才会保留气泡"的顽疾，长按一松手气泡稳稳锚定！
  document.addEventListener('selectionchange', function () {
    var sel = window.getSelection();
    var hasText = sel && String(sel).trim().length > 0;
    if (hasText && lastTouchDown) { lastTouchSelTime = Date.now(); }
    schedule(hasText ? 40 : 250);
  }, true);
  var lastTouchDown = false;
  document.addEventListener('touchstart', function () { lastTouchDown = true; }, true);
  document.addEventListener('mousedown', function () { lastTouchDown = false; }, true);
  document.addEventListener('mouseup', function () {
    schedule(20);
    setTimeout(send, 80);
    setTimeout(send, 200);
  }, true);
  document.addEventListener('touchend', function () {
    var sel = null;
    try { sel = window.getSelection(); } catch (e) { sel = null; }
    if (sel && String(sel).trim().length > 0) { lastTouchSelTime = Date.now(); }
    schedule(30);
    setTimeout(send, 100);
    setTimeout(send, 250);
    setTimeout(function () { lastTouchDown = false; }, 900);
  }, true);
  window.addEventListener('scroll', function () { schedule(150); }, true);
})();
"""

    // ========================================================================
    // 2) 阅读模式
    // ========================================================================

    /**
     * 阅读模式的 CSS。
     *
     * 全部规则都加 `html[data-tc-reader-mode="1"]` 前缀 —— 这是"可一键还原"的关键：
     * 不开启时属性不存在，这些规则对页面**零影响**；还原只需摘掉属性并删掉 style 节点，
     * 全程不修改页面原有 DOM（不改结构、不写内联样式），因此页面不可能被改坏。
     *
     * 规则取向是**保守**：
     * - 只隐匿 `body > header/footer/nav/aside` 与 role 语义明确的辅助区，
     *   以及广告/ Cookie/评论/相关推荐这类以类名命名的垃圾容器；
     * - 不隐匿裸 `header` / `header.foo`，避免把正文标题一起吃掉；
     * - 不做"自动抽取正文"（那需要 readability 级别的算法），只做宽度/字号/行距约束。
     *
     * 因此遇到纯 Web 应用页（正文文字不足）时，脚本会**诚实拒绝**进入阅读模式，
     * 而不是把页面搞得面目全非还说成功。
     */
    val READER_CSS: String = """
html[$READER_ATTR="1"] body > header,
html[$READER_ATTR="1"] body > footer,
html[$READER_ATTR="1"] body > nav,
html[$READER_ATTR="1"] body > aside,
html[$READER_ATTR="1"] [role="banner"],
html[$READER_ATTR="1"] [role="navigation"],
html[$READER_ATTR="1"] [role="complementary"],
html[$READER_ATTR="1"] [role="search"],
html[$READER_ATTR="1"] .navbar,
html[$READER_ATTR="1"] .nav-bar,
html[$READER_ATTR="1"] .sidebar,
html[$READER_ATTR="1"] .side-bar,
html[$READER_ATTR="1"] .topbar,
html[$READER_ATTR="1"] .top-bar,
html[$READER_ATTR="1"] .breadcrumb,
html[$READER_ATTR="1"] .pagination,
html[$READER_ATTR="1"] .ads,
html[$READER_ATTR="1"] .advert,
html[$READER_ATTR="1"] .advertisement,
html[$READER_ATTR="1"] .cookie,
html[$READER_ATTR="1"] .cookies,
html[$READER_ATTR="1"] .cookie-banner,
html[$READER_ATTR="1"] .newsletter,
html[$READER_ATTR="1"] .social,
html[$READER_ATTR="1"] .sharing,
html[$READER_ATTR="1"] .share-buttons,
html[$READER_ATTR="1"] .comments,
html[$READER_ATTR="1"] .comment-list,
html[$READER_ATTR="1"] .recommend,
html[$READER_ATTR="1"] #nav,
html[$READER_ATTR="1"] #navbar,
html[$READER_ATTR="1"] #sidebar,
html[$READER_ATTR="1"] #topbar,
html[$READER_ATTR="1"] #footer,
html[$READER_ATTR="1"] #comments {
  display: none !important;
}

html[$READER_ATTR="1"] body,
html[$READER_ATTR="1"] main,
html[$READER_ATTR="1"] article {
  background: #FAF6EE !important;
  color: #24292E !important;
  width: 100% !important;
  max-width: 100% !important;
  box-sizing: border-box !important;
}

html[$READER_ATTR="1"] body {
  width: 100% !important;
  max-width: 100% !important;
  margin: 0 !important;
  padding: 24px 20px 64px 20px !important;
  font-size: 18px !important;
  line-height: 1.85 !important;
  -webkit-user-select: text !important;
  user-select: text !important;
  box-sizing: border-box !important;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif !important;
}

@media (min-width: 768px) {
  html[$READER_ATTR="1"] body {
    padding: 32px 36px 80px 36px !important;
    font-size: 19px !important;
  }
}

@media (min-width: 1024px) {
  html[$READER_ATTR="1"] body {
    padding: 36px 48px 96px 48px !important;
    font-size: 20px !important;
  }
}

html[$READER_ATTR="1"] p,
html[$READER_ATTR="1"] li,
html[$READER_ATTR="1"] dd,
html[$READER_ATTR="1"] blockquote,
html[$READER_ATTR="1"] h1,
html[$READER_ATTR="1"] h2,
html[$READER_ATTR="1"] h3,
html[$READER_ATTR="1"] article,
html[$READER_ATTR="1"] section {
  cursor: text !important;
}

html[$READER_ATTR="1"] p,
html[$READER_ATTR="1"] li,
html[$READER_ATTR="1"] dd {
  line-height: 1.85 !important;
  letter-spacing: 0.2px !important;
}

html[$READER_ATTR="1"] p {
  margin: 0 0 1.25em 0 !important;
}

/* 优雅精美的大标题排版：雅致衬线体，大屏自适应字号 */
html[$READER_ATTR="1"] h1 {
  font-size: 30px !important;
  line-height: 1.35 !important;
  font-weight: 800 !important;
  margin: 0 0 16px 0 !important;
  padding-bottom: 12px !important;
  border-bottom: 1px dashed rgba(0,0,0,0.12) !important;
  font-family: Georgia, "Songti SC", "SimSun", serif !important;
}

html[$READER_ATTR="1"] h2 {
  font-size: 24px !important;
  line-height: 1.40 !important;
  font-weight: 700 !important;
  margin: 28px 0 12px 0 !important;
}

html[$READER_ATTR="1"] h3 {
  font-size: 20px !important;
  line-height: 1.45 !important;
  font-weight: 600 !important;
  margin: 22px 0 10px 0 !important;
}

/* 引用块精美装饰竖线 */
html[$READER_ATTR="1"] blockquote {
  margin: 18px 0 !important;
  padding: 12px 18px !important;
  border-left: 4px solid #10B981 !important;
  background: rgba(0,0,0,0.03) !important;
  border-radius: 0 8px 8px 0 !important;
  font-style: italic !important;
}

/* 优雅代码块容器 */
html[$READER_ATTR="1"] pre {
  margin: 18px 0 !important;
  padding: 14px 16px !important;
  background: rgba(0,0,0,0.05) !important;
  border-radius: 8px !important;
  overflow-x: auto !important;
  font-family: Menlo, Monaco, Consolas, "Courier New", monospace !important;
  font-size: 15px !important;
}

/* 正文图片精美自适应排版：充满宽度、圆角、柔和阴影 */
html[$READER_ATTR="1"] img {
  max-width: 100% !important;
  height: auto !important;
  border-radius: 10px !important;
  box-shadow: 0 4px 18px rgba(0,0,0,0.08) !important;
  margin: 20px auto !important;
  display: block !important;
}

html[$READER_ATTR="1"] video,
html[$READER_ATTR="1"] figure,
html[$READER_ATTR="1"] table,
html[$READER_ATTR="1"] iframe {
  max-width: 100% !important;
  height: auto !important;
}

html[$READER_ATTR="1"] a { color: #0B5FA5 !important; text-decoration: underline !important; }

/* Via 浏览器 / Dark Reader 经典深色模式排版：与浏览器普通深色网页 100% 严格对齐底色与对比度 */
html[$READER_ATTR="1"][$READER_DARK_ATTR="1"],
html[$READER_DARK_ATTR="1"] {
  background: #141618 !important;
}

html[$READER_ATTR="1"][$READER_DARK_ATTR="1"] body,
html[$READER_ATTR="1"][$READER_DARK_ATTR="1"] main,
html[$READER_ATTR="1"][$READER_DARK_ATTR="1"] article {
  background: #141618 !important;
  color: #E6E1DA !important;
}

html[$READER_ATTR="1"][$READER_DARK_ATTR="1"] body *,
html[$READER_ATTR="1"][$READER_DARK_ATTR="1"] p,
html[$READER_ATTR="1"][$READER_DARK_ATTR="1"] li,
html[$READER_ATTR="1"][$READER_DARK_ATTR="1"] dd,
html[$READER_ATTR="1"][$READER_DARK_ATTR="1"] div {
  background-color: transparent !important;
  color: #E6E1DA !important;
  border-color: #262A2E !important;
}

html[$READER_ATTR="1"][$READER_DARK_ATTR="1"] h1 {
  border-bottom-color: rgba(255,255,255,0.12) !important;
}

html[$READER_ATTR="1"][$READER_DARK_ATTR="1"] blockquote {
  border-left-color: #10B981 !important;
  background: rgba(255,255,255,0.04) !important;
}

html[$READER_ATTR="1"][$READER_DARK_ATTR="1"] pre {
  background: #1C1F22 !important;
  color: #D1D5DB !important;
}

html[$READER_ATTR="1"][$READER_DARK_ATTR="1"] a { color: #64B5F6 !important; }

/* 媒体保真：图片、视频在深色下保持原本色彩与饱和度，绝不反色成底片 */
html[$READER_ATTR="1"][$READER_DARK_ATTR="1"] img,
html[$READER_ATTR="1"][$READER_DARK_ATTR="1"] video {
  filter: brightness(0.92) contrast(1.04) !important;
}
"""

    /**
     * 进入阅读模式：**先做正文智能抽取，再注入排版样式**，返回 '1' 表示成功。
     *
     * 抽取逻辑（readability 的简化版，全部在页面内完成）：
     *  1. 候选容器按 `<article>` / `<main>` / `[role=main]` / 常见正文类名选，兜底选
     *     `<p>` 文字量最大的 `<div>`；
     *  2. 候选克隆后删除 script/style/nav/footer/aside/form/iframe 等结构垃圾；
     *  3. 按 class/id/role 关键词（advert|ads|promo|sponsor|banner|cookie|newsletter|
     *     share|social|comment|recommend|related|sidebar|breadcrumb|pagination|widget|
     *     popup|modal|menu|nav|footer|header）再剔一层；
     *  4. **保留图片**：把懒加载占位（data-src / data-original / data-lazy-src）提升为
     *     真实 src，删掉 data-uri、1x1 追踪像素与 logo/icon/avatar 这类小图；
     *  5. 用 `<h1>标题 + 清理后的正文` 重建 body。原 body 存进
     *     `window.__tcReaderOriginalBody`，退出时完整还原。
     *
     * 之所以做 DOM 重写而不只是 CSS 隐藏：CSS 隐藏对未知站点类名无效，而且
     * 「导出 PDF」打印的是当前 DOM —— 只有真重写了，导出的 PDF 才是干净正文。
     */
    fun readerModeEnableScript(): String {
        val cssLiteral = JSONObject.quote(READER_CSS)
        // 用三引号原文写 JS：内部不能出现美元符号（Kotlin 模板），JS 侧全部用普通字符串。
        val extract = """
(function(){try{
var A='$READER_ATTR';
var root=document.documentElement;
var body=document.body;
if(!body){return '0';}
if(root.getAttribute(A)==='1'){return '1';}
var plain=String(body.innerText||'').replace(/[ \t\r\n]+/g,'');
if(plain.length<300){return '0';}
if(!window.__tcReaderOriginalBody){window.__tcReaderOriginalBody=body.innerHTML;}
function textLen(el){
  var ps=el.querySelectorAll?el.querySelectorAll('p,li,dd,blockquote,h2,h3'):[];
  var n=0;for(var i=0;i<ps.length;i++){n+=(ps[i].textContent||'').trim().length;}
  return n;
}

/* Mozilla Readability 链接密度打分（Link Density Scoring）：
   如果一个容器里的文本绝大部分都是 <a> 超链接，说明它是导航栏/相关文章列表/标签云，坚决剔除！ */
function linkDensity(el){
  var textLen = (el.textContent||'').trim().length;
  if(textLen === 0) return 0;
  var links = el.querySelectorAll('a');
  var linkLen = 0;
  for(var i=0;i<links.length;i++){ linkLen += (links[i].textContent||'').trim().length; }
  return linkLen / textLen;
}

var best=null,bs=0;
var cands=document.querySelectorAll('article,main,[role="main"],.post,.article,.entry-content,#content,.content');
var ci;
for(ci=0;ci<cands.length;ci++){
  var score = textLen(cands[ci]);
  if(linkDensity(cands[ci]) < 0.45 && score > bs){
    bs = score;
    best = cands[ci];
  }
}
if(!best){
  var divs=document.querySelectorAll('div,section');
  var cap=divs.length>400?400:divs.length;
  for(var d=0;d<cap;d++){
    var score2=textLen(divs[d]);
    if(linkDensity(divs[d]) < 0.45 && score2 > bs){
      bs = score2;
      best = divs[d];
    }
  }
}
if(!best||bs<240){return '0';}

var clone=best.cloneNode(true);
var junk=clone.querySelectorAll('script,style,noscript,link,meta,nav,header,footer,aside,form,button,select,svg,iframe,embed,object,video,audio');
for(var j=0;j<junk.length;j++){if(junk[j].parentNode){junk[j].parentNode.removeChild(junk[j]);}}
var all=clone.querySelectorAll('*');
for(var k=0;k<all.length;k++){
  var el=all[k];
  var sig=((el.className&&typeof el.className==='string')?el.className:'')+' '+(el.id||'')+' '+(el.getAttribute('role')||'');
  if(/(advert|\bads?\b|ad-|-ad|promo|sponsor|banner|cookie|newsletter|subscribe|share|social|comment|recommend|related|sidebar|breadcrumb|pagination|widget|popup|modal|menu|nav|footer|header)/i.test(sig)){
    if(el.parentNode){el.parentNode.removeChild(el);}
  }
}
var imgs=clone.querySelectorAll('img');
for(var m=0;m<imgs.length;m++){
  var im=imgs[m];
  var src=im.getAttribute('data-src')||im.getAttribute('data-original')||im.getAttribute('data-lazy-src')||im.src||'';
  if(!src||src.indexOf('data:')===0||/(1x1|pixel|spacer|blank|logo|icon|avatar)/i.test(src)){
    if(im.parentNode){im.parentNode.removeChild(im);}continue;
  }
  im.removeAttribute('srcset');im.removeAttribute('loading');
  im.setAttribute('src',src);
  im.style.maxWidth='100%';im.style.height='auto';
  im.style.borderRadius='8px';im.style.margin='16px auto';im.style.display='block';
}

// 提取文章标题与作者时间元数据（Mozilla Readability 经典元数据排版）
var tEl=document.querySelector('h1,.post-title,.entry-title');
var title=((tEl&&tEl.textContent)?tEl.textContent:(document.title||'')).trim();

var authorEl = document.querySelector('meta[name="author"],.author,.byline');
var author = authorEl ? (authorEl.getAttribute('content') || authorEl.textContent || '').trim() : '';

var art=document.createElement('article');
var h=document.createElement('h1');h.textContent=title||'正文';
art.appendChild(h);

if(author && author.length < 50) {
  var metaTag = document.createElement('div');
  metaTag.style.fontSize = '13px';
  metaTag.style.color = '#888';
  metaTag.style.marginBottom = '20px';
  metaTag.textContent = '作者 / 来源：' + author;
  art.appendChild(metaTag);
}

art.appendChild(clone);
body.innerHTML='';
body.appendChild(art);
if(!document.getElementById('$READER_STYLE_ID')){
  var st=document.createElement('style');
  st.id='$READER_STYLE_ID';
  st.setAttribute('type','text/css');
  st.appendChild(document.createTextNode($cssLiteral));
  (document.head||root).appendChild(st);
}
root.setAttribute(A,'1');
return '1';
}catch(e){return '0';}})()
""".trimIndent()
        return extract
    }

    /**
     * 退出阅读模式：还原抽取前的原始 body（[readerModeEnableScript] 存的快照），
     * 摘掉两个属性并删除注入的 style 节点。**只换 body 的内容**，
     * head 里的选词监听（[SELECTION_LISTENER]）不受影响，退出后选词照常可用。
     */
    fun readerModeDisableScript(): String =
        "(function(){try{" +
            "var root=document.documentElement;" +
            "if(window.__tcReaderOriginalBody&&document.body){" +
            "document.body.innerHTML=window.__tcReaderOriginalBody;" +
            "}" +
            "root.removeAttribute('$READER_ATTR');" +
            "root.removeAttribute('$READER_DARK_ATTR');" +
            "var st=document.getElementById('$READER_STYLE_ID');" +
            "if(st&&st.parentNode){st.parentNode.removeChild(st);}" +
            "return '1';" +
            "}catch(e){return '0';}})()"

    /** 切换阅读模式的深色分支（只动属性，样式早已在 style 节点里）。 */
    fun readerDarkScript(dark: Boolean): String =
        if (dark) {
            "(function(){try{document.documentElement.setAttribute('$READER_DARK_ATTR','1');" +
                "return '1';}catch(e){return '0';}})()"
        } else {
            "(function(){try{document.documentElement.removeAttribute('$READER_DARK_ATTR');" +
                "return '1';}catch(e){return '0';}})()"
        }

    /**
     * 全网页强制深色模式脚本（基于 Via 浏览器与 Dark Reader 经典反色保真算法）。
     * 当软件本体处于深色模式时，自动注入使任何普通网页变成深色；开启阅读模式时无缝衔接。
     */
    fun globalPageDarkScript(dark: Boolean): String {
        val css = """
html {
  filter: invert(90%) hue-rotate(180deg) !important;
  background: #141618 !important;
}
img, video, canvas, svg, [style*="background-image"] {
  filter: invert(100%) hue-rotate(180deg) !important;
}
        """.trimIndent()
        val cssLiteral = JSONObject.quote(css)
        return if (dark) {
            "(function(){try{" +
                "var st=document.getElementById('$GLOBAL_DARK_STYLE_ID');" +
                "if(!st){" +
                "st=document.createElement('style');" +
                "st.id='$GLOBAL_DARK_STYLE_ID';" +
                "st.type='text/css';" +
                "st.appendChild(document.createTextNode($cssLiteral));" +
                "(document.head||document.documentElement).appendChild(st);" +
                "}" +
                "return '1';" +
                "}catch(e){return '0';}})()"
        } else {
            "(function(){try{" +
                "var st=document.getElementById('$GLOBAL_DARK_STYLE_ID');" +
                "if(st&&st.parentNode){st.parentNode.removeChild(st);}" +
                "return '1';" +
                "}catch(e){return '0';}})()"
        }
    }
}
