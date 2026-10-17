/* =====================================================================
 *  reader.js — 阅读器主逻辑
 *  1) 导入 EPUB / PDF / TXT，离线解析为结构化段落
 *  2) 标注六级中频以上生词难词 + GRE 长难句
 *  3) 点击生词 -> 侧栏释义（内置词库 + 有道词典）
 *  4) 收藏生词与高级句式（本地持久化）
 * ===================================================================== */
(function () {
  'use strict';

  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };

  var DB = {
    key: 'ebr_books_v1', mkey: 'ebr_marks_v1', skey: 'ebr_set_v1',
    books: [], marks: { words: [], patterns: [] }, set: {}
  };
  var cur = null;          // 当前书
  var chapters = [];       // 当前书章节
  var chIdx = 0;
  var onlyHard = false;
  var exam = 'cet6';      // 当前考纲：cet6 | gre | ielts | toefl
  var minTier = 3;        // 当前难度门槛（1..5，默认 3=中频）
  var offline = false;    // 离线模式：true 时不加载有道词典
  var withExt = false;    // 是否纳入「超纲拓展词」

  /* ---------------- 持久化 ---------------- */
  function save() {
    try {
      localStorage.setItem(DB.key, JSON.stringify(DB.books.map(function (b) {
        return { id: b.id, name: b.name, file: b.file, meta: b.meta, at: b.at, chapters: b.chapters, sentences: b.sentences };
      })));
      localStorage.setItem(DB.mkey, JSON.stringify(DB.marks));
      localStorage.setItem(DB.skey, JSON.stringify(DB.set));
    } catch (e) { toast('存储空间不足，未能保存'); }
  }
  function load() {
    try { DB.books = JSON.parse(localStorage.getItem(DB.key) || '[]'); } catch (e) { DB.books = []; }
    try { DB.marks = JSON.parse(localStorage.getItem(DB.mkey) || '{}'); } catch (e) { DB.marks = {}; }
    if (!DB.marks.words) DB.marks.words = [];
    if (!DB.marks.patterns) DB.marks.patterns = [];
    try { DB.set = JSON.parse(localStorage.getItem(DB.skey) || '{}'); } catch (e) { DB.set = {}; }
  }

  function toast(msg, ms) {
    var t = $('#toast'); t.textContent = msg; t.classList.add('on');
    clearTimeout(t._tm); t._tm = setTimeout(function () { t.classList.remove('on'); }, ms || 2000);
  }

  /* ---------------- 屏幕切换 ---------------- */
  function show(id) {
    $$('.screen').forEach(function (s) { s.classList.toggle('active', s.id === id); });
  }
  var hist = [];
  function go(id, keepHist) {
    var curId = ($('.screen.active') || {}).id;
    if (curId && curId !== id && !keepHist) hist.push(curId);
    show(id);
  }
  function back() {
    var p = hist.pop();
    if (p) show(p); else show('shelf');
  }

  /* =====================================================================
   *  1. 电子书解析（纯离线，不依赖原生）
   * ===================================================================== */

  /* =====================================================================
   *  2. 标注引擎
   * ===================================================================== */
  var WORD_RE = /[A-Za-z]+(?:['\-][A-Za-z]+)*/g;

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  /** 标注生词：把命中词包成 <w> */
  function annotate(text) {
    var out = '', last = 0, m;
    WORD_RE.lastIndex = 0;
    while ((m = WORD_RE.exec(text))) {
      var tok = m[0], s = m.index;
      if (s > last) out += esc(text.slice(last, s));
      var lem = Vocab.lemma(tok);
      if (lem) {
        var hit = Vocab.match(lem, exam, minTier, withExt);
        if (hit) {
          var t = hit.level;
          out += '<w class="hw t' + t + (hit.ext ? ' hw-ext' : '') +
            '" data-w="' + esc(lem) + '" data-o="' + esc(tok) + '">' + esc(tok) + '</w>';
        } else { out += esc(tok); }
      } else { out += esc(tok); }
      last = s + tok.length;
    }
    if (last < text.length) out += esc(text.slice(last));
    return out;
  }

  // ---- 长难句分析 ----
  var SUBO = new Set(('which that who whom whose where when although though while whereas ' +
    'unless until because since as if whether before after provided supposing').split(' '));
  var INV = new Set('never rarely seldom hardly scarcely little not only no nor nowhere'.split(' '));
  var PAR = new Set('not but and or neither nor both either whether'.split(' '));

  function analyze(sent) {
    var toks = sent.match(WORD_RE) || [];
    var low = toks.map(function (t) { return t.toLowerCase(); });
    var n = toks.length, score = 0, feats = [];
    if (n >= 40) score += 3; else if (n >= 28) score += 2; else if (n >= 18) score += 1;
    if (n >= 25) feats.push(['長句', n + ' 词']);

    var subs = [], fi = {};
    low.forEach(function (w) { if (SUBO.has(w) && !fi[w]) { fi[w] = 1; subs.push(w); } });
    if (subs.length >= 3) { score += 3; feats.push(['多重从句', subs.slice(0, 4).join('；')]); }
    else if (subs.length === 2) { score += 2; feats.push(['双层从句', subs.join('；')]); }
    else if (subs.length === 1) { score += 1; feats.push(['从句', subs[0]]); }

    if (low.length && INV.has(low[0]) && n > 8) { score += 2; feats.push(['倒装/强调', low[0]]); }
    var joined = low.join(' ');
    if (joined.indexOf('not only') >= 0 || joined.indexOf('no sooner') >= 0) {
      score += 2; feats.push(['倒装', 'not only / no sooner']);
    }
    if (/^(?:[A-Z][a-z]+\s+){1,3}(?:ing|ed)\b/.test(sent) || /,\s*(?:having|being)\b/i.test(sent)) {
      score += 2; feats.push(['非谓语/独立主格', '分词结构']);
    }
    var pc = [], pf = {};
    low.forEach(function (w) { if (PAR.has(w) && !pf[w]) { pf[w] = 1; pc.push(w); } });
    if (pc.length >= 3) { score += 2; feats.push(['平行结构', pc.join('／')]); }

    var commas = (sent.match(/,/g) || []).length;
    if (sent.indexOf('—') >= 0 || sent.indexOf(' — ') >= 0 || commas >= 4) {
      score += 1; feats.push(['插入語', commas + ' 处逗号']);
    }
    var abs = (sent.match(/\w+(?:tion|sion|ity|ment|ness|ance|ence)\b/gi) || []).length;
    if (abs >= 4) { score += 2; feats.push(['抽象名詞密集', abs + ' 个']); }

    var level = score >= 6 ? 'GRE 高难' : score >= 4 ? '长难句' : score >= 2 ? '复合句' : '简单句';
    return { n: n, score: score, level: level, feats: feats, subs: subs };
  }

  var SENT_SPLIT = /(?<=[.!?])\s+(?=[A-Z"'(\[])/;

  function splitSentences(text) {
    var parts = text.split(SENT_SPLIT), out = [];
    parts.forEach(function (p) {
      var t = p.trim();
      if (t.length < 12) { if (out.length) out[out.length - 1] += ' ' + t; else if (t) out.push(t); }
      else out.push(t);
    });
    return out;
  }

  /** 从段落渲染：既标注生词，也给长难句加标记 */
  function renderBlock(b, bi) {
    var tag = /^(h[1-6])$/.test(b.tag || '') ? b.tag : 'p';
    var text = b.text;
    if (tag === 'p' && text.length > 40) {
      var sents = splitSentences(text), html = '';
      sents.forEach(function (s, si) {
        var a = analyze(s);
        var body = annotate(s);
        if (a.score >= 4) {
          html += '<span class="sent-mark" data-b="' + bi + '" data-s="' + si + '">' + body +
            '</span><span class="sent-tag" data-b="' + bi + '" data-s="' + si + '">' + a.level + '</span> ';
        } else {
          html += body + ' ';
        }
      });
      return '<' + tag + '>' + html + '</' + tag + '>';
    }
    return '<' + tag + '>' + annotate(text) + '</' + tag + '>';
  }

  /* =====================================================================
   *  3. 打开书 / 章节划分
   * ===================================================================== */
  function buildChapters(blocks) {
    // 以标题切章；若无标题则按段落数均分
    var chs = [], curCh = { title: null, blocks: [] };
    var hasHead = blocks.some(function (b) { return /^h[1-3]$/.test(b.tag || ''); });

    if (hasHead) {
      blocks.forEach(function (b) {
        if (/^h[1-2]$/.test(b.tag || '') && b.text.length < 90 && curCh.blocks.length) {
          chs.push(curCh);
          curCh = { title: b.text, blocks: [b] };
        } else {
          if (!curCh.title && /^h[1-2]$/.test(b.tag || '')) curCh.title = b.text;
          curCh.blocks.push(b);
        }
      });
      if (curCh.blocks.length) chs.push(curCh);
    } else {
      // 无标题：按约 120 段切分，并抽取首句做标题
      var SIZE = 120;
      for (var i = 0; i < blocks.length; i += SIZE) {
        var seg = blocks.slice(i, i + SIZE);
        var t = seg[0] ? seg[0].text.slice(0, 40) : ('第 ' + (chs.length + 1) + ' 部分');
        chs.push({ title: t, blocks: seg });
      }
    }
    return chs.length ? chs : [{ title: '正文', blocks: blocks }];
  }

  function collectSentences(chapters) {
    var list = [];
    chapters.forEach(function (ch, ci) {
      ch.blocks.forEach(function (b, bi) {
        if (!/^p$/.test(b.tag || 'p') || b.text.length <= 40) return;
        splitSentences(b.text).forEach(function (s, si) {
          var a = analyze(s);
          if (a.score >= 4) list.push({ ch: ci, b: bi, s: si, sent: s, a: a });
        });
      });
    });
    return list;
  }

  function openBook(id) {
    var b = DB.books.filter(function (x) { return x.id === id; })[0];
    if (!b) return;
    cur = b; chIdx = b.at && b.at.ch ? b.at.ch : 0;
    chapters = b.chapters;
    $('#r-title').textContent = b.name;
    go('reader');
    renderChapter(chIdx, true);
  }

  function renderChapter(i, scrollTop) {
    if (!chapters.length) return;
    chIdx = Math.max(0, Math.min(chapters.length - 1, i));
    var ch = chapters[chIdx];
    var html = '';
    if (ch.title) html += '<h2>' + esc(ch.title) + '</h2>';
    ch.blocks.forEach(function (b, bi) {
      if (ch.title && bi === 0 && /^h[1-2]$/.test(b.tag || '') && b.text === ch.title) return;
      html += renderBlock(b, bi);
    });
    var v = $('#book-view');
    v.innerHTML = html;
    if (scrollTop !== false) v.scrollTop = 0;

    if (cur) { cur.at = { ch: chIdx, t: Date.now() }; save(); }
    updateProgress();

    $('#btn-prev').disabled = chIdx === 0;
    $('#btn-next').disabled = chIdx === chapters.length - 1;
    $('#btn-prev').style.opacity = chIdx === 0 ? '.4' : '1';
    $('#btn-next').style.opacity = chIdx === chapters.length - 1 ? '.4' : '1';
  }

  function updateProgress() {
    var p = Math.round((chIdx + 1) / chapters.length * 100);
    $('#r-progress').textContent = '第 ' + (chIdx + 1) + '/' + chapters.length + ' 章 · ' + p + '%';
    $('#progress-fill').style.width = p + '%';
    $('#r-title').textContent = cur ? cur.name : '';
  }

  /* =====================================================================
   *  4. 导入流程
   * ===================================================================== */
  var busy = false;

  function extOf(name) { return (String(name).split('.').pop() || '').toLowerCase(); }

  var SUPPORTED = ['epub', 'pdf', 'txt', 'md', 'html', 'htm',
                   'mobi', 'azw', 'azw3', 'prc', 'fb2', 'docx'];

  /** 统一入口：ArrayBuffer + 文件名 -> 结构化 blocks（异步） */
  async function extractBlocks(buf, name) {
    var ext = extOf(name);
    if (ext === 'epub') return await EBook.parseEpub(buf);
    if (ext === 'pdf') return await EBook.parsePdf(buf);
    if (ext === 'mobi' || ext === 'prc') return await EBook.parseMobi(buf);
    if (ext === 'azw' || ext === 'azw3') return await EBook.parseAzw3(buf);
    if (ext === 'fb2') return EBook.parseFb2(buf);
    if (ext === 'docx') return await EBook.parseDocx(buf);
    var txt = new TextDecoder('utf-8').decode(new Uint8Array(buf));
    // txt / md / html：按空行分段
    if (ext === 'html' || ext === 'htm') return EBook.htmlBlocks(txt);
    return txt.split(/\n\s*\n/)
      .map(function (t) { return t.replace(/\s+/g, ' ').trim(); })
      .filter(Boolean).map(function (t) { return { tag: 'p', text: t }; });
  }

  /** 主导入流程 */
  async function doImport(name, buf) {
    if (busy) { toast('正在导入，请稍候'); return; }
    var ext = extOf(name);
    if (SUPPORTED.indexOf(ext) < 0) {
      toast('暂不支持 .' + ext + ' 格式'); return;
    }
    // 去重：同名书（含随 APK 打包的内置书目重复推送）只导入一次
    var baseName = name.replace(/\.[^.]+$/, '');
    var dup = DB.books.filter(function (b) { return b.file === name || b.name === baseName; })[0];
    if (dup) {
      // 已存在则直接打开，不重复解析
      if (!cur || cur.id !== dup.id) openBook(dup.id);
      return;
    }
    busy = true;
    go('reader');
    $('#r-title').textContent = name.replace(/\.[^.]+$/, '');
    $('#r-progress').textContent = '解析中…';
    $('#progress-fill').style.width = '0%';
    $('#book-view').innerHTML = '<div class="loading"><div class="spinner"></div>' +
      '正在解析《' + esc(name) + '》…<br>' +
      '<span style="font-size:12.5px">离线提取文本 · 标注生词难词 · 识别长难句</span></div>';

    // 让界面先渲染
    await new Promise(function (r) { setTimeout(r, 40); });

    try {
      var blocks = (await extractBlocks(buf, name))
        .filter(function (b) { return b.text && b.text.length > 1; });
      if (!blocks.length) throw new Error('未能提取到文本（可能是扫描版或加密文件）');

      $('#book-view').innerHTML = '<div class="loading"><div class="spinner"></div>' +
        '已提取 ' + blocks.length + ' 段，正在标注生词…</div>';
      await new Promise(function (r) { setTimeout(r, 30); });

      finishImport(name, blocks);
    } catch (e) {
      busy = false;
      toast('解析失败：' + (e && e.message ? e.message : e), 3600);
      if (DB.books.length) go('shelf'); else show('shelf');
      renderShelf();
    }
  }

  function importFile(file) {
    var name = file.name || '未命名';
    var fr = new FileReader();
    fr.onload = function () { doImport(name, fr.result); };
    fr.onerror = function () { busy = false; toast('文件读取失败'); };
    fr.readAsArrayBuffer(file);
  }

  function finishImport(name, blocks) {
    var chs = buildChapters(blocks);
    var words = 0, hard = 0;
    var chData = chs.map(function (ch) {
      var wc = 0, hc = 0;
      ch.blocks.forEach(function (b) {
        var toks = b.text.match(WORD_RE) || [];
        wc += toks.length;
        toks.forEach(function (t) { if (Vocab.lemma(t)) hc++; });
      });
      words += wc; hard += hc;
      return { title: ch.title, blocks: ch.blocks, words: wc, hard: hc };
    });
    var sents = collectSentences(chData);

    var book = {
      id: 'b' + Date.now() + '_' + Math.random().toString(36).slice(2, 7),
      name: name.replace(/\.[^.]+$/, ''),
      file: name,
      meta: { words: words, hard: hard, chars: blocks.reduce(function (a, b) { return a + b.text.length; }, 0) },
      at: { ch: 0, t: Date.now() },
      chapters: chData,
      sentences: sents.length
    };
    DB.books.unshift(book);
    // 限制总存储：保留最近 8 本
    if (DB.books.length > 8) DB.books = DB.books.slice(0, 8);
    save();
    busy = false;
    toast('导入完成：' + hard + ' 个生词难词 / ' + sents.length + ' 处长难句', 2600);
    openBook(book.id);
    renderShelf();
  }

  /* =====================================================================
   *  5. 书架渲染
   * ===================================================================== */
  function renderShelf() {
    var list = $('#book-list');
    list.innerHTML = DB.books.map(function (b) {
      var d = Math.round((b.meta.hard / Math.max(b.meta.words, 1)) * 1000) / 10;
      return '<div class="book-card" data-id="' + b.id + '">' +
        '<div class="book-cover">' + esc((b.name || '?').charAt(0).toUpperCase()) + '</div>' +
        '<div class="book-info">' +
        '<div class="book-name">' + esc(b.name) + '</div>' +
        '<div class="book-stats">' + b.meta.words.toLocaleString() + ' 词 · ' +
        '<b>' + b.meta.hard + '</b> 个生词难词 (' + d + '%) · ' + b.chapters.length + ' 章' +
        (b.sentences ? ' · ' + b.sentences + ' 长难句' : '') + '</div>' +
        '</div>' +
        '<button class="book-del" data-del="' + b.id + '">✕</button>' +
        '</div>';
    }).join('');

    $('#shelf-empty').style.display = DB.books.length ? 'none' : 'block';
    $('#shelf-stat').textContent = '书架 · ' + DB.books.length + ' 本';
    $('#vocab-stat').textContent = '词库 ' + Vocab.count().toLocaleString() + ' 词 · ' +
      EXAM_NAME[exam] + (offline ? ' · 离线' : '');
    $('#c-words').textContent = DB.marks.words.length;
    $('#c-patterns').textContent = DB.marks.patterns.length;
  }

  /* =====================================================================
   *  6. 侧栏：生词释义
   * ===================================================================== */
  var curWord = null;

  function openWord(word, ctxText, origTok) {
    curWord = word;
    var e = Vocab.get(word);
    $('#p-word').textContent = word;

    // 音标显示：双口音支持（现代通用 RP 与 1910s-40s 传统贵族音）及俄语音标
    var phEl = $('#p-phonetic');
    var engInfo = window.EngEtymDb ? window.EngEtymDb.findByWord(word) : null;
    var rusInfo = window.RusEtymDb ? window.RusEtymDb.findByWord(word) : null;
    if (engInfo) {
      var ew = engInfo.matchedWord;
      phEl.innerHTML = '<span class="ipa-pill modern">[现] ' + esc(ew.ipa_modern) + '</span> ' +
        '<button class="btn-speak-mini" onclick="event.stopPropagation();window.IpaAudioEngine&&IpaAudioEngine.speak(\'' + esc(word) + '\',\'' + esc(ew.ipa_modern) + '\',\'en_modern\')">🔊</button> ' +
        '<span class="ipa-pill trad" style="margin-left:6px">[贵] ' + esc(ew.ipa_trad) + '</span> ' +
        '<button class="btn-speak-mini" onclick="event.stopPropagation();window.IpaAudioEngine&&IpaAudioEngine.speak(\'' + esc(word) + '\',\'' + esc(ew.ipa_trad) + '\',\'en_trad\')">🔊</button>';
    } else if (rusInfo) {
      var rw = rusInfo.matchedWord;
      phEl.innerHTML = '<span class="ipa-pill rus">[俄] ' + esc(rw.ipa) + '</span> ' +
        '<button class="btn-speak-mini" onclick="event.stopPropagation();window.IpaAudioEngine&&IpaAudioEngine.speak(\'' + esc(word) + '\',\'' + esc(rw.ipa) + '\',\'ru_standard\')">🔊</button>';
    } else if (e && e.ph) {
      phEl.innerHTML = '<span class="ipa-val">/' + esc(e.ph) + '/</span> ' +
        '<button class="btn-speak-mini" onclick="event.stopPropagation();window.IpaAudioEngine&&IpaAudioEngine.speak(\'' + esc(word) + '\',\'' + esc(e.ph) + '\',\'en_modern\')">🔊</button>';
    } else {
      phEl.innerHTML = '';
    }

    // 难度标签：优先显示当前考纲下的档位，并列出该词所属考纲
    var lv = e ? Vocab.tier(word, exam) : 0;
    var lvExt = e && e.exams ? (e.exams.ext || 0) : 0;
    var labels = { 2: '高频', 3: '中频', 4: '低频', 5: '核心/生僻' };
    var exs = Vocab.examsOf(word);
    var tt = $('#p-tier');
    if (lv) {
      tt.textContent = EXAM_NAME[exam] + ' · ' + (labels[lv] || '生词');
      tt.className = 'tag' + (lv >= 5 ? ' t5' : '');
    } else if (lvExt) {
      tt.textContent = '超纲词 · ' + (labels[lvExt] || '罕见');
      tt.className = 'tag ext';
    } else {
      tt.textContent = exs.length ? EXAMS_SHORT(exs) : '生词难词';
      tt.className = 'tag';
    }

    $('#p-freq').textContent = exs.length
      ? '收录于 ' + EXAMS_SHORT(exs)
      : ((e && e.def) ? '内置词库已收录' : '仅标注');

    // 释义
    var defEl = $('#p-def');
    if (e && e.def) {
      defEl.innerHTML = esc(e.def).replace(/(^|\s)((?:n|v|adj|adv|prep|conj|pron|int|num|art|abbr)\b\.)/gi,
        '$1<span class="pos">$2</span>');
    } else {
      defEl.innerHTML = '<span style="color:var(--muted)">内置词库暂无简明释义，' +
        (offline ? '离线模式下无法联网查询。' : '请点击下方按钮查看有道词典完整释义。') + '</span>';
    }

    // 语境
    if (ctxText) {
      var re = new RegExp('(' + word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\w*)', 'i');
      $('#p-ctx').innerHTML = esc(ctxText.replace(/\s+/g, ' ')).replace(re, function (mm) {
        return '<mark>' + mm + '</mark>';
      });
      $('#sec-ctx').style.display = '';
    } else { $('#sec-ctx').style.display = 'none'; }

    // 收藏状态
    var starred = DB.marks.words.some(function (m) { return m.w === word; });
    var bs = $('#btn-star');
    bs.classList.toggle('on', starred);
    bs.textContent = starred ? '★ 已收藏（点击取消）' : '☆ 收藏这个词';

    // 重置词典框
    var fr = $('#dict-frame'); fr.classList.remove('on'); fr.src = 'about:blank';
    $('#dict-note').textContent = '';
    // 离线模式：隐藏整个「在线词典」区块，只保留内置释义
    var dictSec = $('#sec-dict');
    if (dictSec) dictSec.style.display = offline ? 'none' : '';

    openPanel('#panel');
    markReadingWord(word);
  }

  /** 考纲缩写串，如 "六级/GRE" */
  function EXAMS_SHORT(exs) {
    return exs.map(function (x) { return EXAM_NAME[x] || x; }).join('/');
  }

  var _readMark = null;
  function markReadingWord(word) {
    if (_readMark) { _readMark.style.background = ''; _readMark = null; }
    var el = $('#book-view w.hw[data-w="' + word + '"]');
    if (el) { el.style.background = 'var(--hi)'; el.style.color = '#fff'; _readMark = el; }
  }

  function openPanel(sel) {
    $('#overlay').classList.add('on');
    $$('.panel').forEach(function (p) { p.classList.toggle('on', '#' + p.id === sel); });
  }
  function closePanels() {
    $('#overlay').classList.remove('on');
    $$('.panel').forEach(function (p) { p.classList.remove('on'); });
  }

  function starWord() {
    if (!curWord) return;
    var i = -1;
    DB.marks.words.forEach(function (m, k) { if (m.w === curWord) i = k; });
    var bs = $('#btn-star');
    if (i >= 0) {
      DB.marks.words.splice(i, 1);
      bs.classList.remove('on'); bs.textContent = '☆ 收藏这个词';
      toast('已取消收藏');
    } else {
      var e = Vocab.get(curWord);
      DB.marks.words.unshift({
        w: curWord, ph: e ? e.ph : '', def: e ? e.def : '',
        ctx: ($('#p-ctx').textContent || '').slice(0, 200),
        book: cur ? cur.name : '', t: Date.now()
      });
      bs.classList.add('on'); bs.textContent = '★ 已收藏（点击取消）';
      toast('已加入生词本');
    }
    save();
    $('#c-words').textContent = DB.marks.words.length;
    if ($('#marks').classList.contains('active')) renderMarks();
  }

  /* =====================================================================
   *  7. 句式面板
   * ===================================================================== */
  var curSent = null;

  function patternOf(a) {
    if (a.subs && a.subs.length >= 2) {
      return 'It is <span class="slot">[形容词/过去分词]</span> that <span class="slot">[从句]</span>，' +
        '而 <span class="slot">' + a.subs[0] + '…</span> 引导的从句进一步限定；' +
        '这一句式通过多重从句把多个信息层叠，常见于学术论述。';
    }
    var has = function (k) { return a.feats.some(function (f) { return f[0] === k; }); };
    if (has('倒装/强调') || has('倒装')) {
      return 'Not only <span class="slot">[助动词 + 主语 + 谓语]</span> but <span class="slot">[主语 + 谓语]</span> — ' +
        '否定词前置引起部分倒装，用于强调，写作中可替换平铺直叙。';
    }
    if (has('非谓语/独立主格')) {
      return '<span class="slot">[分词短语]</span>, <span class="slot">[主句主语 + 谓语]</span> — ' +
        '用分词结构替代从句，使句子更紧凑，是 GRE/学术写作的高频手法。';
    }
    if (has('平行结构')) {
      return 'not only A but also B；neither A nor B — <span class="slot">[成分 A]</span>, ' +
        '<span class="slot">[成分 B]</span>，通过并列结构形成节奏与强调。';
    }
    if (has('長句')) {
      return '<span class="slot">[主句]</span>, <span class="slot">[从句/同位语]</span>, ' +
        '<span class="slot">[插入语]</span> — 长句靠修饰成分层层推进，阅读时先抓主干。';
    }
    return '<span class="slot">[主句]</span> + <span class="slot">[修饰成分]</span> — 注意先定位主谓，再挂接从句。';
  }

  function openSentence(item) {
    curSent = item;
    $('#s-level').textContent = item.a.level;
    $('#s-score').textContent = '难度评分 ' + item.a.score + ' · ' + item.a.n + ' 词 · ' +
      (item.a.subs.length ? '从句: ' + item.a.subs.join('、') : '无明显从句');
    $('#s-text').innerHTML = esc(item.sent);
    $('#s-feats').innerHTML = item.a.feats.map(function (f) {
      return '<div class="feat"><span class="feat-k">' + esc(f[0]) + '</span>' +
        '<span class="feat-v">' + esc(f[1]) + '</span></div>';
    }).join('') || '<div class="feat-v">—</div>';
    $('#s-template').innerHTML = patternOf(item.a);

    var saved = DB.marks.patterns.some(function (p) { return p.sent === item.sent; });
    var b = $('#btn-save-pattern');
    b.textContent = saved ? '★ 已收藏该句式' : '收藏该句式';
    b.classList.toggle('on', saved);
    openPanel('#sent-panel');
  }

  function starPattern() {
    if (!curSent) return;
    var i = -1;
    DB.marks.patterns.forEach(function (p, k) { if (p.sent === curSent.sent) i = k; });
    if (i >= 0) {
      DB.marks.patterns.splice(i, 1); toast('已取消收藏');
    } else {
      DB.marks.patterns.unshift({
        sent: curSent.sent, level: curSent.a.level, score: curSent.a.score,
        feats: curSent.a.feats, template: patternOf(curSent.a),
        book: cur ? cur.name : '', t: Date.now()
      });
      toast('已加入句式库');
    }
    save();
    $('#c-patterns').textContent = DB.marks.patterns.length;
    var saved = DB.marks.patterns.some(function (p) { return p.sent === curSent.sent; });
    $('#btn-save-pattern').textContent = saved ? '★ 已收藏该句式' : '收藏该句式';
  }

  /* =====================================================================
   *  8. 收藏夹
   * ===================================================================== */
  var markTab = 'words';

  function renderMarks() {
    $$('.tab').forEach(function (t) { t.classList.toggle('active', t.dataset.tab === markTab); });
    var body = $('#marks-body');
    if (markTab === 'words') {
      if (!DB.marks.words.length) {
        body.innerHTML = '<div class="empty-hint"><div class="eh-emoji">☆</div>' +
          '<p>还没有收藏生词</p><p class="eh-sub">阅读时点击高亮生词，在侧栏点击"收藏这个词"</p></div>';
        return;
      }
      body.innerHTML = DB.marks.words.map(function (m, i) {
        return '<div class="mk-item">' +
          '<div class="mk-head"><span class="mk-word">' + esc(m.w) + '</span>' +
          (m.ph ? '<span class="mk-ph">/' + esc(m.ph) + '/</span>' : '') +
          '<button class="mk-del" data-dw="' + i + '">✕</button></div>' +
          (m.def ? '<div class="mk-def">' + esc(m.def) + '</div>' :
            '<div class="mk-def" style="color:var(--muted)">内置词库无释义</div>') +
          (m.ctx ? '<div class="mk-ctx">' + esc(m.ctx) + '…</div>' : '') +
          '<div class="mk-actions">' +
          '<button class="primary small" data-dictw="' + esc(m.w) + '">有道释义</button>' +
          '</div></div>';
      }).join('');
    } else {
      if (!DB.marks.patterns.length) {
        body.innerHTML = '<div class="empty-hint"><div class="eh-emoji">✧</div>' +
          '<p>还没有收藏句式</p><p class="eh-sub">阅读时长难句会以紫色标记，点击句式标签即可收藏</p></div>';
        return;
      }
      body.innerHTML = DB.marks.patterns.map(function (p, i) {
        return '<div class="mk-item">' +
          '<div class="mk-head"><span class="mk-word" style="font-size:14px">' + esc(p.level) + '</span>' +
          '<span class="mk-ph">难度 ' + p.score + ' · ' + (p.book || '') + '</span>' +
          '<button class="mk-del" data-dp="' + i + '">✕</button></div>' +
          '<div class="mk-ctx" style="border-left-color:var(--sent);font-size:14.5px;color:var(--fg)">' +
          esc(p.sent) + '</div>' +
          '<div class="mk-actions"><span class="feat-k">' +
          p.feats.map(function (f) { return esc(f[0]); }).join(' · ') + '</span></div>' +
          '<div class="mk-pattern" style="margin-top:9px">' + p.template + '</div>' +
          '</div>';
      }).join('');
    }
  }

  function exportMarks() {
    var lines = ['# 生词本 & 句式库', '导出时间: ' + new Date().toLocaleString(), '', '## 生词 (' + DB.marks.words.length + ')', ''];
    DB.marks.words.forEach(function (m) {
      lines.push('- **' + m.w + '**' + (m.ph ? ' /' + m.ph + '/' : '') + (m.def ? ' — ' + m.def : ''));
      if (m.ctx) lines.push('  > ' + m.ctx.replace(/\n/g, ' '));
    });
    lines.push('', '## 高级句式 (' + DB.marks.patterns.length + ')', '');
    DB.marks.patterns.forEach(function (p) {
      lines.push('- [' + p.level + '] ' + p.sent);
      lines.push('  - 特征: ' + p.feats.map(function (f) { return f[0]; }).join('、'));
    });
    var blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' });
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = '英语生词本_' + new Date().toISOString().slice(0, 10) + '.md';
    a.click();
    setTimeout(function () { URL.revokeObjectURL(a.href); }, 3000);
    toast('已导出 Markdown');
  }

  /* =====================================================================
   *  9. 设置
   * ===================================================================== */
  var EXAM_NAME = { cet6: '六级', gre: 'GRE', ielts: '雅思', toefl: '托福', ext: '超纲' };
  var TIER_NAME = {
    3: '中频及以上',
    4: '低频及以上（更严格）',
    5: '核心/生僻词（最少干扰）'
  };

  function applySet() {
    var s = DB.set || {};
    var root = document.documentElement;
    root.setAttribute('data-theme', s.theme || 'light');
    root.style.setProperty('--read-font', (s.font || 19) + 'px');
    root.style.setProperty('--read-lh', ((s.lh || 19) / 10));
    exam = s.exam || 'cet6';
    minTier = s.tier || 3;
    offline = !!s.offline;
    withExt = !!s.ext;
    $('#opt-font').value = s.font || 19; $('#opt-font-v').textContent = s.font || 19;
    $('#opt-lh').value = s.lh || 19; $('#opt-lh-v').textContent = ((s.lh || 19) / 10).toFixed(1);
    $$('#opt-theme button').forEach(function (b) { b.classList.toggle('on', b.dataset.th === (s.theme || 'light')); });
    $$('#opt-exam button').forEach(function (b) { b.classList.toggle('on', b.dataset.ex === exam); });
    $$('#opt-tier button').forEach(function (b) { b.classList.toggle('on', +b.dataset.tier === minTier); });
    $$('#opt-offline button').forEach(function (b) { b.classList.toggle('on', (+b.dataset.off === 1) === offline); });
    $$('#opt-ext button').forEach(function (b) { b.classList.toggle('on', (+b.dataset.ext === 1) === withExt); });
    updateTierHint();
  }

  function updateTierHint() {
    $('#tier-hint').textContent = '当前：' + EXAM_NAME[exam] + ' · ' + TIER_NAME[minTier] +
      (withExt ? ' + 超纲词' : '') +
      (offline ? '（离线模式，仅用内置释义）' : '（可联网查有道）');
  }

  /* =====================================================================
   *  10. 事件绑定
   * ===================================================================== */
  function bind() {
    // 导入
    $('#btn-import').onclick = function () { $('#file-input').click(); };
    $('#file-input').onchange = function (e) {
      if (e.target.files[0]) importFile(e.target.files[0]);
      e.target.value = '';
    };
    var dz = $('#dropzone');
    dz.onclick = function (e) { if (e.target === dz || e.target.className.indexOf('dz') === 0) $('#file-input').click(); };
    ['dragover', 'dragenter'].forEach(function (ev) {
      document.addEventListener(ev, function (e) { e.preventDefault(); dz.classList.add('over'); });
    });
    ['dragleave', 'drop'].forEach(function (ev) {
      document.addEventListener(ev, function (e) { e.preventDefault(); dz.classList.remove('over'); });
    });
    document.addEventListener('drop', function (e) {
      e.preventDefault();
      if (e.dataTransfer.files[0]) importFile(e.dataTransfer.files[0]);
    });

    // 书架
    $('#book-list').onclick = function (e) {
      var del = e.target.closest('[data-del]');
      if (del) {
        var id = del.dataset.del;
        var b = DB.books.filter(function (x) { return x.id === id; })[0];
        if (b && confirm('删除《' + b.name + '》？')) {
          DB.books = DB.books.filter(function (x) { return x.id !== id; });
          save(); renderShelf();
        }
        return;
      }
      var card = e.target.closest('.book-card');
      if (card) openBook(card.dataset.id);
    };

    // 阅读器导航
    $('#btn-back').onclick = function () { closePanels(); back(); };
    $('#btn-prev').onclick = function () { renderChapter(chIdx - 1); };
    $('#btn-next').onclick = function () { renderChapter(chIdx + 1); };
    $('#btn-toc').onclick = showToc;
    $('#btn-mode').onclick = function () {
      onlyHard = !onlyHard;
      document.body.classList.toggle('only-hard', onlyHard);
      this.classList.toggle('on', onlyHard);
      this.textContent = onlyHard ? '全部显示' : '仅生词';
      toast(onlyHard ? '已淡出非生词部分' : '已显示全文');
    };

    // 点击正文：生词优先，其次句式角标
    $('#book-view').onclick = function (e) {
      // 1) 生词命中（生词可能嵌在长难句 span 内，需优先判定）
      var w = e.target.closest('w.hw');
      if (w) {
        var p = w.closest('p,h1,h2,h3,h4,h5,h6') || w.parentNode;
        var ctx = '';
        if (p) {
          var parts = splitSentences(p.textContent);
          for (var i = 0; i < parts.length; i++) {
            var pl = parts[i].toLowerCase();
            if (pl.indexOf(w.dataset.o.toLowerCase()) >= 0 ||
                pl.indexOf(w.dataset.w.toLowerCase()) >= 0) { ctx = parts[i]; break; }
          }
          if (!ctx) ctx = p.textContent.slice(0, 200);
        }
        openWord(w.dataset.w, ctx, w.dataset.o);
        return;
      }

      // 2) 句式角标
      var sTag = e.target.closest('.sent-tag');
      if (sTag) {
        var bi = +sTag.dataset.b, si = +sTag.dataset.s;
        var blocks = chapters[chIdx].blocks;
        if (blocks[bi]) {
          var ss = splitSentences(blocks[bi].text);
          if (ss[si]) openSentence({ sent: ss[si], a: analyze(ss[si]), b: bi, s: si });
        }
      }
    };

    // 面板
    $('#btn-close-panel').onclick = closePanels;
    $('#btn-close-sent').onclick = closePanels;
    $('#overlay').onclick = closePanels;
    $('#btn-star').onclick = starWord;
    $('#btn-save-pattern').onclick = starPattern;

    var btnEtymEntry = $('#btn-etym-entry');
    if (btnEtymEntry) btnEtymEntry.onclick = function () { openEtymModal(); };
    var btnPanelEtym = $('#btn-panel-etym');
    if (btnPanelEtym) btnPanelEtym.onclick = function () {
      var w = $('#p-word') ? $('#p-word').textContent.trim() : '';
      openEtymModal(w);
    };

    $('#btn-dict').onclick = function () {
      if (!curWord) return;
      if (offline) { toast('离线模式已开启：仅使用内置释义'); return; }
      var url = 'https://dict.youdao.com/result?word=' + encodeURIComponent(curWord) + '&lang=en';
      var fr = $('#dict-frame');
      fr.src = url;
      fr.classList.add('on');
      $('#dict-note').innerHTML = '若侧栏词典未正常显示（有道可能限制站内嵌），' +
        '请点击"外部浏览器"或下方链接查看。<br>' +
        '<a href="' + url + '" target="_blank" style="color:var(--accent)">打开有道词典网页版 →</a>';
    };
    $('#btn-dict-out').onclick = function () {
      if (!curWord) return;
      if (offline) { toast('离线模式已开启：仅使用内置释义'); return; }
      var url = 'https://dict.youdao.com/result?word=' + encodeURIComponent(curWord) + '&lang=en';
      if (window.AndroidBridge && AndroidBridge.openDict) AndroidBridge.openDict(curWord);
      else window.open(url, '_blank');
    };

    // 收藏夹
    $('#btn-marks-back').onclick = function () { go('shelf'); };
    $('#btn-export').onclick = exportMarks;
    $$('.tab').forEach(function (t) {
      t.onclick = function () { markTab = t.dataset.tab; renderMarks(); };
    });
    $('#marks-body').onclick = function (e) {
      var dw = e.target.closest('[data-dw]'), dp = e.target.closest('[data-dp]');
      var dictw = e.target.closest('[data-dictw]');
      if (dw) { DB.marks.words.splice(+dw.dataset.dw, 1); save(); renderMarks(); $('#c-words').textContent = DB.marks.words.length; }
      if (dp) { DB.marks.patterns.splice(+dp.dataset.dp, 1); save(); renderMarks(); $('#c-patterns').textContent = DB.marks.patterns.length; }
      if (dictw) {
        var w = dictw.dataset.dictw;
        if (window.AndroidBridge && AndroidBridge.openDict) AndroidBridge.openDict(w);
        else window.open('https://dict.youdao.com/result?word=' + encodeURIComponent(w) + '&lang=en', '_blank');
      }
    };

    // 设置
    var sheet = $('#settings');
    function syncSet() { save(); applySet(); }
    $('#btn-settings').onclick = function () { applySet(); sheet.classList.add('on'); };
    $('#btn-settings-close').onclick = function () { sheet.classList.remove('on'); if (cur) renderChapter(chIdx, false); };
    $('#opt-font').oninput = function () { DB.set.font = +this.value; $('#opt-font-v').textContent = this.value; syncSet(); };
    $('#opt-lh').oninput = function () { DB.set.lh = +this.value; $('#opt-lh-v').textContent = (this.value / 10).toFixed(1); syncSet(); };
    $$('#opt-theme button').forEach(function (b) {
      b.onclick = function () { DB.set.theme = b.dataset.th; syncSet(); };
    });
    $$('#opt-exam button').forEach(function (b) {
      b.onclick = function () {
        DB.set.exam = b.dataset.ex; syncSet();
        if (cur) renderChapter(chIdx, false);
        toast('已切换为「' + EXAM_NAME[b.dataset.ex] + '」词表，仅标注该考纲词汇');
      };
    });
    $$('#opt-tier button').forEach(function (b) {
      b.onclick = function () {
        DB.set.tier = +b.dataset.tier; syncSet();
        if (cur) renderChapter(chIdx, false);
        toast('标注难度已更新');
      };
    });
    $$('#opt-offline button').forEach(function (b) {
      b.onclick = function () {
        DB.set.offline = (+b.dataset.off === 1); syncSet();
        toast(DB.set.offline ? '已开启离线模式：仅使用内置释义' : '已关闭离线模式：可联网查有道词典');
      };
    });
    $$('#opt-ext button').forEach(function (b) {
      b.onclick = function () {
        DB.set.ext = (+b.dataset.ext === 1); syncSet();
        if (cur) renderChapter(chIdx, false);
        toast(DB.set.ext ? '已纳入超纲拓展词（标注更全面）' : '仅标注所选考纲词汇');
      };
    });

    // 长按书架按钮进入收藏
    var tapCount = 0, tapTm = null;
    $('#shelf-stat').onclick = function () {
      tapCount++;
      clearTimeout(tapTm);
      if (tapCount >= 2) { tapCount = 0; go('marks'); renderMarks(); return; }
      tapTm = setTimeout(function () { tapCount = 0; }, 400);
    };
    $('#vocab-stat').onclick = function () { go('marks'); renderMarks(); };
  }

  function showToc() {
    if (!chapters.length) return;
    var html = '<div class="sheet-inner"><div class="sheet-title">目录</div>';
    chapters.forEach(function (ch, i) {
      html += '<div class="mk-item" data-ch="' + i + '" style="cursor:pointer">' +
        '<div class="mk-head"><span class="mk-word" style="font-size:14.5px">' +
        esc(ch.title || ('第 ' + (i + 1) + ' 章')) + '</span>' +
        '<span class="mk-ph">' + (ch.words || 0).toLocaleString() + ' 词</span></div></div>';
    });
    html += '<button id="toc-close" class="primary wide" style="margin-top:14px">关闭</button></div>';
    var sheet = $('#settings');
    sheet.innerHTML = html;
    sheet.classList.add('on');
    sheet.onclick = function (e) {
      var it = e.target.closest('[data-ch]');
      if (it) {
        sheet.classList.remove('on');
        renderChapter(+it.dataset.ch);
        restoreSettings();
        return;
      }
      if (e.target.id === 'toc-close') { sheet.classList.remove('on'); restoreSettings(); }
    };
  }

  function restoreSettings() {
    // 目录复用 sheet 容器，关闭后恢复设置面板结构
    if ($('#opt-font')) return;
    location.reload();
  }

  /* ---------------- 原生桥接 ---------------- */
  window.__nativeBook = function (name, b64) {
    try {
      var bin = atob(b64), len = bin.length, u8 = new Uint8Array(len);
      for (var i = 0; i < len; i++) u8[i] = bin.charCodeAt(i);
      doImport(name, u8.buffer);
    } catch (e) { toast('导入失败: ' + e.message, 3000); }
  };
  window.__pickRequest = function () { $('#file-input').click(); };
  
  /* =====================================================================
   *  TalkTree 专属：AI 研读小窗交互与书籍专属 Root 归档
   * ===================================================================== */
  var aiChatHistory = {}; // bookId -> [ { role, content, quote, time } ]
  var currentAiStreamingMsg = null;

  function initAiDialogAndSelection() {
    var fab = document.getElementById('fab-ai-chat');
    var modal = document.getElementById('ai-modal');
    var btnClose = document.getElementById('btn-ai-close');
    var btnMin = document.getElementById('btn-ai-min');
    var btnClear = document.getElementById('btn-ai-clear');
    var btnSend = document.getElementById('btn-ai-send');
    var input = document.getElementById('ai-input-text');
    var bookView = document.getElementById('book-view');
    var bubble = document.getElementById('selection-bubble');
    var bubbleAsk = document.getElementById('bubble-ask-ai');

    if (fab) {
      fab.addEventListener('click', function () { openAiModal(); });
    }
    if (btnClose) {
      btnClose.addEventListener('click', function () {
        modal.classList.remove('active', 'minimized');
      });
    }
    if (btnMin) {
      btnMin.addEventListener('click', function () {
        modal.classList.toggle('minimized');
      });
    }
    if (btnClear) {
      btnClear.addEventListener('click', function () {
        if (!cur) return;
        aiChatHistory[cur.id] = [];
        renderAiMessages();
        toast('当前书本研读对话已清空');
      });
    }
    if (btnSend) {
      btnSend.addEventListener('click', function () {
        var text = input.value.trim();
        if (!text) return;
        input.value = '';
        sendUserAiMessage(text);
      });
    }
    if (input) {
      input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey) {
          e.preventDefault();
          btnSend.click();
        }
      });
    }

    // 快捷提示词
    document.querySelectorAll('.ai-chip').forEach(function (chip) {
      chip.addEventListener('click', function () {
        var p = chip.getAttribute('data-prompt');
        if (p) sendUserAiMessage(p);
      });
    });

    // 划词悬浮菜单
    var selectedText = '';
    document.addEventListener('selectionchange', function () {
      var sel = window.getSelection();
      var str = sel.toString().trim();
      if (!str || str.length < 2 || !cur) {
        if (bubble) bubble.style.display = 'none';
        selectedText = '';
        return;
      }
      selectedText = str;
      if (sel.rangeCount > 0) {
        var range = sel.getRangeAt(0);
        var rect = range.getBoundingClientRect();
        if (rect && bubble) {
          bubble.style.left = (rect.left + rect.width / 2) + 'px';
          bubble.style.top = (rect.top + window.scrollY) + 'px';
          bubble.style.display = 'block';
        }
      }
    });

    if (bubbleAsk) {
      bubbleAsk.addEventListener('click', function () {
        if (!selectedText) return;
        var quote = selectedText;
        if (bubble) bubble.style.display = 'none';
        window.getSelection().removeAllRanges();
        openAiModal();
        sendUserAiMessage('请深度解析以下原版选段：', quote);
      });
    }

    var bubbleEtym = document.getElementById('bubble-etym');
    if (bubbleEtym) {
      bubbleEtym.addEventListener('click', function () {
        if (!selectedText) return;
        var w = selectedText;
        if (bubble) bubble.style.display = 'none';
        window.getSelection().removeAllRanges();
        openEtymModal(w);
      });
    }
  }

  function openAiModal() {
    var modal = document.getElementById('ai-modal');
    if (!modal) return;
    modal.classList.remove('minimized');
    modal.classList.add('active');

    var bookTag = document.getElementById('ai-modal-book-tag');
    var rootTag = document.getElementById('ai-archive-root-name');
    var bookName = cur ? cur.name : '随读思考';
    if (bookTag) bookTag.textContent = bookName;
    if (rootTag) rootTag.textContent = '📖 《' + bookName + '》研读对话树';

    renderAiMessages();
  }

  function renderAiMessages() {
    var box = document.getElementById('ai-messages');
    if (!box) return;
    var list = (cur && aiChatHistory[cur.id]) || [];
    if (!list.length) {
      box.innerHTML = '<div class="ai-msg ai-msg-assistant"><div class="ai-msg-avatar">🌲</div>' +
        '<div class="ai-msg-content">你好！我是 <b>TalkTree 本地端侧研读助手</b>。所有在此书中的提问都会自动归档至当前书本的专属 Root 对话树中。你可以点击上方快捷提示词或长按选句提问！</div></div>';
      return;
    }
    box.innerHTML = list.map(function (m) {
      var isUser = m.role === 'user';
      var qHtml = m.quote ? '<div class="ai-thinking-box" style="margin-bottom:6px"><b>引文:</b> ' + esc(m.quote) + '</div>' : '';
      var rHtml = m.reasoning ? '<details class="ai-thinking-box"><summary class="ai-thinking-summary">思考过程 (点击展开)</summary><div style="margin-top:6px;font-size:12.5px">' + esc(m.reasoning).replace(/\n/g, '<br>') + '</div></details>' : '';
      return '<div class="ai-msg ' + (isUser ? 'ai-msg-user' : 'ai-msg-assistant') + '">' +
        '<div class="ai-msg-avatar">' + (isUser ? '👤' : '🌲') + '</div>' +
        '<div class="ai-msg-content">' + qHtml + rHtml + formatMarkdownSimple(m.content) + '</div></div>';
    }).join('');
    box.scrollTop = box.scrollHeight;
  }

  function sendUserAiMessage(prompt, quote) {
    if (!cur) { toast('请先打开一本书籍'); return; }
    var bookId = cur.id;
    var bookName = cur.name;
    var chName = (chapters && chapters[chIdx] && chapters[chIdx].title) || ('第 ' + (chIdx + 1) + ' 章');

    if (!aiChatHistory[bookId]) aiChatHistory[bookId] = [];
    aiChatHistory[bookId].push({ role: 'user', content: prompt, quote: quote, time: Date.now() });
    renderAiMessages();

    // 预置 assistant 占位消息
    var asstMsg = { role: 'assistant', content: '思考中…', reasoning: '', time: Date.now() };
    aiChatHistory[bookId].push(asstMsg);
    renderAiMessages();
    currentAiStreamingMsg = asstMsg;

    // 向 TalkTree 原生层桥接调用
    if (window.AndroidBridge && window.AndroidBridge.askTalkTreeAi) {
      window.AndroidBridge.askTalkTreeAi(bookName, bookId, prompt, quote || '', chName);
    } else {
      // 模拟端侧本地模型极速流式生成（脱机演示环境）
      setTimeout(function () {
        var reply = generateOfflineModelReply(prompt, quote);
        asstMsg.content = reply;
        renderAiMessages();
      }, 600);
    }
  }

  function askAiForWord(word, ctx) {
    openAiModal();
    var p = '请结合当前原版书语境（句子："' + ctx + '"），深度剖析核心单词 "' + word + '" 的：' +
      '\n1. 语境特定义与熟词生义；' +
      '\n2. GRE/托福高分学术搭配与同义替换；' +
      '\n3. 给出 2 个地道学术考题级例句与中文对照。';
    sendUserAiMessage(p, '单词: ' + word + ' (语境: ' + ctx + ')');
  }

  function askAiForSentence(sent) {
    openAiModal();
    var p = '请作为英语语言学专家，深度拆解这句长难句："' + sent + '"。要求：' +
      '\n1. 树状层级标注句法主干（主谓宾）、各级嵌套从句与非谓语修饰成分；' +
      '\n2. 剖析倒装/排比/强调等修辞技巧；' +
      '\n3. 提炼出可用于学术/GRE写作的通用骨架句型；' +
      '\n4. 生成 2 个结构相仿的高分仿写例句。';
    sendUserAiMessage(p, sent);
  }

  // 原生流式回调接口
  window.__onAiStreamChunk = function (chunk, isReasoning, isFinished) {
    if (!currentAiStreamingMsg) return;
    if (isReasoning) {
      currentAiStreamingMsg.reasoning = (currentAiStreamingMsg.reasoning || '') + chunk;
    } else {
      if (currentAiStreamingMsg.content === '思考中…') currentAiStreamingMsg.content = '';
      currentAiStreamingMsg.content += chunk;
    }
    renderAiMessages();
  };

  // 接收原生 OCR 识别结果并直接入库渲染
  window.__onOcrImportComplete = function (bookTitle, chaptersData) {
    try {
      var chs = typeof chaptersData === 'string' ? JSON.parse(chaptersData) : chaptersData;
      var newBook = {
        id: 'ocr_' + Date.now(),
        name: bookTitle,
        file: bookTitle + '.ocr',
        size: 0,
        chapters: chs,
        meta: { words: 0, hard: 0 },
        added: Date.now()
      };
      DB.books.unshift(newBook);
      save();
      renderShelf();
      openBook(newBook.id);
      toast('《' + bookTitle + '》扫描版 OCR 识别成功！已自动建立阅读排版并同步至 TalkTree 知识库', 4000);
    } catch (e) {
      toast('OCR 结果加载异常: ' + e.message, 3000);
    }
  };

  /* =====================================================================
   *  12. EtymoScope 三语历时音韵与全同义词源全息视窗
   * ===================================================================== */
  function openEtymModal(defaultQuery) {
    var modal = document.getElementById('etym-modal');
    if (!modal) return;
    modal.classList.add('open');

    var q = defaultQuery || (curWord || '神圣');
    var input = document.getElementById('etym-input');
    if (input) input.value = q;

    renderEtymologyScope(q);
  }

  function closeEtymModal() {
    var modal = document.getElementById('etym-modal');
    if (modal) modal.classList.remove('open');
  }

  function initEtymologyScope() {
    var btnClose = document.getElementById('btn-etym-close');
    if (btnClose) btnClose.onclick = closeEtymModal;

    var btnSearch = document.getElementById('btn-etym-search');
    var input = document.getElementById('etym-input');
    if (btnSearch && input) {
      btnSearch.onclick = function () {
        var val = input.value.trim();
        if (val) renderEtymologyScope(val);
      };
      input.onkeydown = function (e) {
        if (e.key === 'Enter') {
          var val = input.value.trim();
          if (val) renderEtymologyScope(val);
        }
      };
    }

    // 倍速切换控制器
    document.querySelectorAll('.etym-speed-ctrl .btn-speed').forEach(function (btn) {
      btn.onclick = function () {
        document.querySelectorAll('.etym-speed-ctrl .btn-speed').forEach(function (b) { b.classList.remove('active'); });
        btn.classList.add('active');
        var spd = parseFloat(btn.getAttribute('data-speed')) || 1.0;
        if (window.IpaAudioEngine) {
          window.IpaAudioEngine.setSpeed(spd);
          toast('发音引擎倍速已设为 ' + spd + 'x');
        }
      };
    });
  }

  function renderEtymologyScope(query) {
    var container = document.getElementById('etym-content-view');
    if (!container) return;

    if (!window.EtymCrossAlign) {
      container.innerHTML = '<div style="padding:20px;text-align:center;color:#999">跨语词源引擎加载中…</div>';
      return;
    }

    var report = window.EtymCrossAlign.resolve(query);
    if (!report) {
      container.innerHTML = '<div style="padding:20px;text-align:center;color:#999">' +
        '未查到 "' + esc(query) + '" 的预置词源族谱。可尝试搜索：<b>神圣</b>、<b>光明</b>、<b>智慧</b>、<b>holy</b>、<b>святой</b> 等。</div>';
      return;
    }

    var html = '';

    // 1. 概念概要 Banner
    if (report.summary) {
      html += '<div class="etym-summary-card">' +
        '<b>跨语言核心概念枢纽：【' + esc(report.canonicalZh) + '】</b><br>' +
        '<span style="font-size:12px;color:#6d4c41">' + esc(report.summary) + '</span>' +
        '</div>';
    }

    // 2. 汉语历时音韵（上古四套拟音 + 中古广韵 + 现代普通话）
    if (report.cmnPhonology && report.cmnPhonology.length) {
      html += '<div class="etym-sec-title"><span>🇨🇳 汉语历时音韵与多套权威拟音</span><span style="font-size:11px;color:#8d6e63">普通话 · 中古广韵 · 上古拟音</span></div>';
      html += '<div class="etym-grid">';
      report.cmnPhonology.forEach(function (cItem) {
        if (!cItem.found) return;
        var d = cItem.data;
        html += '<div class="etym-card">' +
          '<div class="etym-card-head">' +
            '<div class="etym-card-word">' + esc(cItem.char) + ' <span style="font-size:14px;color:#666">(' + esc(d.py) + ')</span></div>' +
            '<div class="etym-badges"><span class="badge-origin">' + esc(d.tone) + '</span></div>' +
          '</div>' +
          '<div class="etym-ipa-row">' +
            '<span class="ipa-tag cmn">现代普通话 IPA</span>' +
            '<span class="ipa-val">' + esc(d.ipa) + '</span>' +
            '<button class="btn-speak-ipa" data-word="' + esc(cItem.char) + '" data-ipa="' + esc(d.ipa) + '" data-accent="cmn_modern">🔊 读普通话</button>' +
          '</div>' +
          '<div class="etym-ipa-row">' +
            '<span class="ipa-tag cmn" style="background:#fef08a;color:#854d0e">中古广韵音</span>' +
            '<span class="ipa-val">[' + esc(d.mc.wang) + ']</span>' +
            '<span style="font-size:12px;color:#666">（' + esc(d.mc.initial) + '·' + esc(d.mc.rhyme) + '·' + esc(d.mc.fanqie) + '）</span>' +
            '<button class="btn-speak-ipa" data-word="' + esc(cItem.char) + '" data-ipa="' + esc(d.mc.wang) + '" data-accent="cmn_middle">🔊 读中古音</button>' +
          '</div>' +
          '<table class="cmn-reconstruct-table">' +
            '<tr><th>郑张尚芳拟音</th><td>' + esc(d.oc.zhengzhang) + ' <button class="btn-speak-ipa" data-word="' + esc(cItem.char) + '" data-ipa="' + esc(d.oc.zhengzhang) + '" data-accent="cmn_old">🔊 读上古</button></td></tr>' +
            '<tr><th>白一平-沙加尔拟音</th><td>' + esc(d.oc.baxter_sagart) + '</td></tr>' +
            '<tr><th>王力上古拟音</th><td>' + esc(d.oc.wang) + '</td></tr>' +
            '<tr><th>李方桂上古拟音</th><td>' + esc(d.oc.li) + '</td></tr>' +
          '</table>' +
          '<div class="etym-desc"><b>本义渊源：</b>' + esc(d.meaning) + '</div>' +
        '</div>';
      });
      html += '</div>';
    }

    // 3. 英语全域同义词与双口音 (Modern RP vs 1920s Conservative RP)
    if (report.engCluster && report.engCluster.words) {
      html += '<div class="etym-sec-title" style="margin-top:14px"><span>🇬🇧 英语全域同义词族谱与双口音（含极生僻词）</span><span style="font-size:11px;color:#0284c7">现代英音 vs 贵族英音</span></div>';
      html += '<div class="etym-grid">';
      report.engCluster.words.forEach(function (w) {
        var originClass = 'germanic';
        if (w.origin.indexOf('Latin') !== -1) originClass = 'latin';
        else if (w.origin.indexOf('Greek') !== -1) originClass = 'greek';

        html += '<div class="etym-card">' +
          '<div class="etym-card-head">' +
            '<div class="etym-card-word">' + esc(w.word) + '</div>' +
            '<div class="etym-badges">' +
              '<span class="badge-origin ' + originClass + '">' + esc(w.origin) + '</span>' +
              '<span class="badge-rarity">' + esc(w.rarity) + '</span>' +
            '</div>' +
          '</div>' +
          '<div class="etym-ipa-row">' +
            '<span class="ipa-tag modern">[现] 现代通用 RP</span>' +
            '<span class="ipa-val">' + esc(w.ipa_modern) + '</span>' +
            '<button class="btn-speak-ipa" data-word="' + esc(w.word) + '" data-ipa="' + esc(w.ipa_modern) + '" data-accent="en_modern">🔊 现代音</button>' +
          '</div>' +
          '<div class="etym-ipa-row">' +
            '<span class="ipa-tag trad">[贵] 1910s-40s 贵族音</span>' +
            '<span class="ipa-val">' + esc(w.ipa_trad) + '</span>' +
            '<button class="btn-speak-ipa" data-word="' + esc(w.word) + '" data-ipa="' + esc(w.ipa_trad) + '" data-accent="en_trad">🔊 贵族音</button>' +
          '</div>' +
          '<div class="etym-desc">' +
            '<div><b>词源演变：</b>' + esc(w.evolution) + '</div>' +
            '<div style="margin-top:3px;color:#78716c"><b>语域文体：</b>' + esc(w.register) + '</div>' +
          '</div>' +
        '</div>';
      });
      html += '</div>';
    }

    // 4. 俄语全域同义词与斯拉夫语族源流
    if (report.rusCluster && report.rusCluster.words) {
      html += '<div class="etym-sec-title" style="margin-top:14px"><span>🇷🇺 俄语全域同义词族谱与标准 IPA 音标</span><span style="font-size:11px;color:#ea580c">斯拉夫本源 · 教会斯拉夫借词</span></div>';
      html += '<div class="etym-grid">';
      report.rusCluster.words.forEach(function (rw) {
        var originClass = 'slavic';
        if (rw.origin.indexOf('Church') !== -1) originClass = 'church-slavic';
        else if (rw.origin.indexOf('Greek') !== -1) originClass = 'greek';
        else if (rw.origin.indexOf('Latin') !== -1) originClass = 'latin';

        html += '<div class="etym-card">' +
          '<div class="etym-card-head">' +
            '<div class="etym-card-word">' + esc(rw.word) + ' <span style="font-size:13px;color:#666">(' + esc(rw.translit) + ')</span></div>' +
            '<div class="etym-badges">' +
              '<span class="badge-origin ' + originClass + '">' + esc(rw.origin) + '</span>' +
              '<span class="badge-rarity">' + esc(rw.rarity) + '</span>' +
            '</div>' +
          '</div>' +
          '<div class="etym-ipa-row">' +
            '<span class="ipa-tag rus">[俄] 当代标准 CSR</span>' +
            '<span class="ipa-val">' + esc(rw.ipa) + '</span>' +
            '<button class="btn-speak-ipa" data-word="' + esc(rw.word) + '" data-ipa="' + esc(rw.ipa) + '" data-accent="ru_standard">🔊 读俄语</button>' +
          '</div>' +
          '<div class="etym-desc">' +
            '<div><b>词源演变：</b>' + esc(rw.evolution) + '</div>' +
            '<div style="margin-top:3px;color:#78716c"><b>语域层次：</b>' + esc(rw.register) + '</div>' +
          '</div>' +
        '</div>';
      });
      html += '</div>';
    }

    container.innerHTML = html;

    // 为所有新生成的发音按钮绑定点击调用
    container.querySelectorAll('.btn-speak-ipa').forEach(function (btn) {
      btn.onclick = function () {
        var w = btn.getAttribute('data-word');
        var ipa = btn.getAttribute('data-ipa');
        var accent = btn.getAttribute('data-accent');
        if (window.IpaAudioEngine) {
          window.IpaAudioEngine.speak(w, ipa, accent);
        }
      };
    });
  }

  function formatMarkdownSimple(text) {
    if (!text) return '';
    var t = esc(text);
    t = t.replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
    t = t.replace(/\*(.+?)\*/g, '<i>$1</i>');
    t = t.replace(/`([^`]+)`/g, '<code>$1</code>');
    t = t.replace(/\n/g, '<br>');
    return t;
  }

  function generateOfflineModelReply(prompt, quote) {
    return '【TalkTree 本地端侧大模型研读剖析】\n\n' +
      '**1. 句法层级嵌套拆解**：\n- **主干结构**：主语 + 谓语动词 + 宾语；\n- **定语从句修饰**：由关系代词引导修饰先行词，形成信息递进；\n- **非谓语成分**：分词短语作状语，精炼地补充背景状态。\n\n' +
      '**2. 学术高分仿写模板**：\n`It is not until [条件] that [主句结果], which ultimately leads to [长远影响].`\n\n' +
      '**3. 地道例句拓展**：\n- *Example 1*: The empirical findings demonstrate that cognitive flexibility plays an indispensable role in language acquisition.\n- *Example 2*: Driven by profound intellectual curiosity, researchers have uncovered unprecedented mechanisms governing neural plasticity.';
  }

  window.__onBack = function () {
    var etymModal = document.getElementById('etym-modal');
    if (etymModal && etymModal.classList.contains('open')) {
      closeEtymModal();
      return true;
    }
    var aiModal = document.getElementById('ai-modal');
    if (aiModal && aiModal.classList.contains('active')) {
      aiModal.classList.remove('active');
      return true;
    }
    var sheetOpen = $('#settings').classList.contains('on');
    var panelOpen = $$('.panel.on').length > 0;
    if (panelOpen) { closePanels(); return true; }
    if (sheetOpen) { $('#settings').classList.remove('on'); return true; }
    var a = ($('.screen.active') || {}).id;
    if (a === 'reader') { go('shelf'); return true; }
    if (a === 'marks') { go('shelf'); return true; }
    return false;
  };

  /* ---------------- 启动 ---------------- */
  function boot() {
    load();
    applySet();
    bind();
    initEtymologyScope();
    Vocab.load().then(function (r) {
      if (r.mode === 'none') toast('词库未加载，将不标注生词', 3000);
      renderShelf();
      if (cur === null && DB.books.length) { /* 停在书架 */ }
    });
    renderShelf();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
