/* =====================================================================
 *  epub.js — 离线 EPUB / PDF 解析
 *  EPUB 基于 ZIP + DEFLATE，使用浏览器原生 DecompressionStream('deflate-raw')
 * ===================================================================== */
(function (global) {
  'use strict';

  /* ---------------- ZIP 目录解析 ---------------- */
  function listEntries(buf) {
    var u8 = new Uint8Array(buf), dv = new DataView(buf), files = {}, i;
    var eocd = -1;
    for (i = u8.length - 22; i >= 0 && i > u8.length - 66000; i--) {
      if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error('不是有效的 ZIP/EPUB 文件');
    var count = dv.getUint16(eocd + 10, true);
    var p = dv.getUint32(eocd + 16, true);
    for (i = 0; i < count; i++) {
      if (dv.getUint32(p, true) !== 0x02014b50) break;
      var method = dv.getUint16(p + 10, true);
      var csize = dv.getUint32(p + 20, true);
      var usize = dv.getUint32(p + 24, true);
      var nameLen = dv.getUint16(p + 28, true);
      var extraLen = dv.getUint16(p + 30, true);
      var cmtLen = dv.getUint16(p + 32, true);
      var lho = dv.getUint32(p + 42, true);
      var name = '';
      for (var k = 0; k < nameLen; k++) name += String.fromCharCode(u8[p + 46 + k]);
      name = decodeURIComponent(name);
      if (dv.getUint32(lho, true) === 0x04034b50) {
        var lnameLen = dv.getUint16(lho + 26, true);
        var lextraLen = dv.getUint16(lho + 28, true);
        files[name] = { method: method, start: lho + 30 + lnameLen + lextraLen,
                        csize: csize, usize: usize };
      }
      p += 46 + nameLen + extraLen + cmtLen;
    }
    return files;
  }

  /* ---------------- 解压单条目 ---------------- */
  async function readEntry(u8, meta) {
    var raw = u8.subarray(meta.start, meta.start + meta.csize);
    if (meta.method === 0) return raw;
    if (meta.method !== 8) throw new Error('不支持的压缩方式 ' + meta.method);
    // 优先后备：纯 JS inflate（离线可靠），再退到 DecompressionStream
    if (typeof Inflate !== 'undefined') {
      var out = Inflate.raw(raw);
      if (out && out.length) return out;
    }
    if (typeof DecompressionStream !== 'undefined') {
      var ds = new DecompressionStream('deflate-raw');
      var stream = new Blob([raw]).stream().pipeThrough(ds);
      var ab = await new Response(stream).arrayBuffer();
      return new Uint8Array(ab);
    }
    throw new Error('当前环境不支持 DEFLATE 解压');
  }

  /* ---------------- HTML -> 块级文本 ---------------- */
  function htmlBlocks(html) {
    var doc = new DOMParser().parseFromString(html, 'text/html');
    var kill = doc.querySelectorAll('script,style,head,title,nav,table,hr');
    for (var q = 0; q < kill.length; q++) kill[q].remove();
    var out = [];

    // 直接收集所有叶子块级元素（p / h1-h6 / li / blockquote），
    // 保证 <div><p>…</p></div> 这类嵌套结构不会漏段
    var leaves = doc.querySelectorAll('p,h1,h2,h3,h4,h5,h6,li,blockquote,pre');
    leaves.forEach(function (el) {
      // 若该元素内部还嵌套了块级标签，交给内层处理，跳过自身
      if (el.querySelector('p,h1,h2,h3,h4,h5,h6,li,blockquote,pre')) return;
      var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
      if (!t) return;
      var tag = el.tagName.toLowerCase();
      out.push({ tag: /^h[1-6]$/.test(tag) ? tag : 'p', text: t });
    });

    // 兜底：没有块级标签时，取整体文本
    if (!out.length) {
      var all = (doc.body ? doc.body.textContent : '').replace(/\s+/g, ' ').trim();
      if (all) out.push({ tag: 'p', text: all });
    }
    return out;
  }

  function normPath(p) {
    var parts = p.split('/'), out = [];
    for (var i = 0; i < parts.length; i++) {
      if (parts[i] === '.' || parts[i] === '') continue;
      if (parts[i] === '..') out.pop(); else out.push(parts[i]);
    }
    return out.join('/');
  }

  /* ---------------- EPUB 主流程（异步） ---------------- */
  async function parseEpub(buf) {
    var u8 = new Uint8Array(buf);
    var entries = listEntries(buf);
    var names = Object.keys(entries);
    var dec = new TextDecoder('utf-8');

    async function text(name) {
      var hit = entries[name] ? name : null;
      if (!hit) {
        var low = name.toLowerCase();
        hit = names.filter(function (x) { return x.toLowerCase() === low; })[0];
      }
      if (!hit) return null;
      var bytes = await readEntry(u8, entries[hit]);
      return dec.decode(bytes);
    }

    // 1) 找 OPF
    var opfName = null;
    var container = await text('META-INF/container.xml');
    if (container) {
      var m = /full-path="([^"]+\.opf)"/i.exec(container);
      if (m) opfName = decodeURIComponent(m[1]);
    }
    if (!opfName) opfName = names.filter(function (n) { return /\.opf$/i.test(n); })[0];

    // 2) 读 spine 顺序
    var order = [];
    if (opfName) {
      var opf = await text(opfName);
      if (opf) {
        var dir = opfName.indexOf('/') >= 0 ? opfName.replace(/[^/]+$/, '') : '';
        // 优先用 spine 的 idref 顺序
        var manifest = {};
        var re = /<item\b[^>]*>/gi, mm;
        while ((mm = re.exec(opf))) {
          var tag = mm[0];
          var id = /id="([^"]+)"/i.exec(tag);
          var href = /href="([^"]+)"/i.exec(tag);
          if (id && href) manifest[id[1]] = href[1];
        }
        var spine = /<spine\b[^>]*>([\s\S]*?)<\/spine>/i.exec(opf);
        if (spine) {
          var re2 = /<itemref\b[^>]*idref="([^"]+)"/gi, m2;
          while ((m2 = re2.exec(spine[1]))) {
            var href2 = manifest[m2[1]];
            if (href2) order.push(normPath(dir + decodeURIComponent(href2)));
          }
        }
        if (!order.length) {
          var re3 = /<item\b[^>]*href="([^"]+\.x?html?)"[^>]*>/gi, m3;
          while ((m3 = re3.exec(opf))) order.push(normPath(dir + decodeURIComponent(m3[1])));
        }
      }
    }
    if (!order.length) order = names.filter(function (n) { return /\.x?html?$/i.test(n); }).sort();

    // 3) 逐章提取
    var blocks = [];
    for (var i = 0; i < order.length; i++) {
      var html = await text(order[i]);
      if (!html) continue;
      var bs = htmlBlocks(html);
      for (var j = 0; j < bs.length; j++) blocks.push(bs[j]);
      // 让出主线程，保持界面响应
      if (i % 5 === 4) await new Promise(function (r) { setTimeout(r, 0); });
    }
    return blocks;
  }

  /* ---------------- PDF 文本抽取 ---------------- */
  function latin1(u8) {
    var s = '', CH = 0x8000;
    for (var i = 0; i < u8.length; i += CH) {
      s += String.fromCharCode.apply(null, u8.subarray(i, Math.min(i + CH, u8.length)));
    }
    return s;
  }

  /** 用 inflate 解压字节段；失败时返回 null */
  function inflateRaw(bytes) {
    if (typeof Inflate !== 'undefined') {
      var out = Inflate.raw(bytes);
      if (out && out.length) return out;
    }
    return null;
  }

  /**
   * 从 PDF 内容流中抽取文本。
   * 支持 Tj / TJ / ' / " 运算符，并解压 FlateDecode 流。
   */
  function extractFromContentStream(s) {
    var out = [];
    // 逐行查找文本操作符
    var re = /(?:\((?:[^()\\]|\\.)*\)|\[(?:[^\[\]\\]|\\.)*\])\s*(?:Tj|TJ|'|")/g, m;
    while ((m = re.exec(s))) {
      var seg = m[0];
      if (seg.charAt(0) === '[') {
        // TJ 数组： [(text) num (text) ...]
        var inner = seg.slice(1, seg.lastIndexOf(']'));
        var re2 = /\((?:[^()\\]|\\.)*\)/g, m2, parts = [];
        while ((m2 = re2.exec(inner))) {
          parts.push(m2[0].slice(1, -1));
        }
        out.push(parts.join(''));
      } else {
        var p = seg.indexOf('(');
        var e = seg.lastIndexOf(')');
        if (p >= 0 && e > p) out.push(seg.slice(p + 1, e));
      }
    }
    return out.join('')
      .replace(/\\([()\\])/g, '$1')
      .replace(/\\n/g, ' ').replace(/\\r/g, ' ').replace(/\\t/g, ' ')
      .replace(/\\[0-7]{1,3}/g, ' ');
  }

  async function parsePdf(buf) {
    var u8 = new Uint8Array(buf);
    var raw = latin1(u8);
    var text = '';

    // 定位所有 stream ... endstream 段
    var re = /stream\r?\n?/g, m, segs = [];
    while ((m = re.exec(raw))) {
      var s = m.index + m[0].length;
      var e = raw.indexOf('endstream', s);
      if (e < 0) break;
      segs.push([s, e]);
      re.lastIndex = e;
    }

    for (var i = 0; i < segs.length; i++) {
      var bytes = u8.subarray(segs[i][0], segs[i][1]);
      var asText = '';
      // 已解压的内容流可直接解析
      var head = latin1(bytes.subarray(0, Math.min(600, bytes.length)));
      var looksText = /(Tj|TJ)\b/.test(head) || /\bBT\b/.test(head);

      if (looksText) {
        asText = extractFromContentStream(latin1(bytes));
      } else {
        // 尝试 Flate 解压
        var inf = inflateRaw(bytes);
        if (inf) {
          var dec = latin1(inf);
          if (/(Tj|TJ)\b/.test(dec) || /\bBT\b/.test(dec)) asText = extractFromContentStream(dec);
        }
      }
      if (asText.replace(/\s/g, '').length > 2) text += asText + '\n\n';
      if (i % 8 === 7) await new Promise(function (r) { setTimeout(r, 0); });
    }

    // 兜底：从可读字节中捞取英文段落
    if (text.replace(/\s/g, '').length < 120) {
      var s3 = raw.replace(/[^\x20-\x7E\n]/g, ' ');
      var found = (s3.match(/[A-Za-z][A-Za-z ,.'"();:\-]{45,}/g) || []);
      var picked = found.filter(function (t) {
        // 过滤乱码：真实英文应有大量空格与常见短词
        return /\s/.test(t) && (t.match(/\s/g) || []).length > 5;
      });
      text = picked.join('\n\n');
    }

    var blocks = [];
    text.split(/\n\s*\n|\n(?=[A-Z])/).forEach(function (para) {
      para = para.replace(/\s+/g, ' ').trim();
      if (para.length > 1) blocks.push({ tag: 'p', text: para });
    });
    return blocks;
  }

  /* =====================================================================
   *  MOBI / AZW3 解析
   *  结构：PalmDB(PDB) 容器 -> PalmDOC 头 -> MOBI 头 -> 文本记录（可能压缩）
   *  压缩：1=none, 2=PalmDOC LZ77, 17480=huffdic（不支持，会提示）
   *  AZW3(KF8) 与 MOBI 同容器，文本是 HTML，同样按记录解压后拼接
   * ===================================================================== */

  /** PalmDOC (LZ77) 解压 */
  function palmdocDecompress(src, dstLen) {
    var out = [], i = 0, n = src.length;
    while (i < n) {
      var b = src[i++];
      if (b === 0) { out.push(0); continue; }
      if (b <= 8) {                                   // 字面量：后跟 b 个字节
        for (var k = 0; k < b && i < n; k++) out.push(src[i++]);
      } else if (b < 0x80) {                          // 字面量：单个字节
        out.push(b);
      } else if (b < 0xC0) {                          // 距离/长度对（2 字节）
        var b2 = src[i++];
        var pair = ((b << 8) | b2) & 0x3FFF;
        var dist = pair >> 3;
        var len = (pair & 7) + 3;
        var start = out.length - dist;
        if (start < 0) break;
        for (var j = 0; j < len; j++) out.push(out[start + j]);
      } else {                                        // 0xC0-0xFF：空格 + (b^0x80)
        out.push(32); out.push(b ^ 0x80);
      }
      // 长度已达预期即可停（部分文件末尾有填充）
      if (dstLen && out.length >= dstLen) break;
    }
    return new Uint8Array(out);
  }

  /** 读取 PDB 记录偏移表 */
  function pdbRecords(u8) {
    var dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
    var n = dv.getUint16(76, false);                   // 记录数（big-endian）
    var offs = [];
    for (var i = 0; i < n; i++) {
      var p = 78 + i * 8;
      if (p + 8 > u8.length) break;
      offs.push(dv.getUint32(p, false));
    }
    offs.push(u8.length);
    var recs = [];
    for (var j = 0; j < n; j++) recs.push(u8.subarray(offs[j], offs[j + 1]));
    return recs;
  }

  async function parseMobi(buf) {
    var u8 = new Uint8Array(buf);
    var recs = pdbRecords(u8);
    if (recs.length < 2) throw new Error('MOBI 记录数不足，文件可能损坏');

    var rec0 = recs[0];
    var dv0 = new DataView(rec0.buffer, rec0.byteOffset, rec0.byteLength);

    // PalmDOC 头
    var compression = dv0.getUint16(0, false);
    var textLen = dv0.getUint32(4, false);
    var recordCount = dv0.getUint16(8, false);
    var recSize = dv0.getUint16(10, false);            // 通常 4096
    var encCode = dv0.getUint16(28, false);            // 1252 / 65001

    // MOBI 头（在 PalmDOC 头之后，偏移 16 处应有 "MOBI"）
    var mobiOff = -1;
    for (var s = 16; s < 200 && s + 4 <= rec0.length; s++) {
      if (rec0[s] === 0x4D && rec0[s + 1] === 0x4F && rec0[s + 2] === 0x42 && rec0[s + 3] === 0x49) {
        mobiOff = s; break;
      }
    }
    var encoding = encCode === 65001 ? 'utf-8' : 'windows-1252';
    var extraFlags = 0;
    if (mobiOff >= 0 && mobiOff + 0x20 + 6 <= rec0.length) {
      extraFlags = dv0.getUint16(mobiOff + 0x1E, false);
      // extraFlags & 0x800 -> UTF-8
      if (extraFlags & 0x800) encoding = 'utf-8';
    }

    if (compression === 17480) {
      throw new Error('该 MOBI 使用 HUFF/CDIC 压缩，暂不支持（请用 Calibre 转为 EPUB）');
    }
    if (compression !== 1 && compression !== 2) {
      throw new Error('未知的 MOBI 压缩方式: ' + compression);
    }

    // 拼接文本记录（记录 1..recordCount）
    var chunks = [];
    var end = Math.min(recordCount, recs.length - 1);
    for (var r = 1; r <= end; r++) {
      var rec = recs[r];
      if (!rec || !rec.length) continue;
      var plain;
      if (compression === 2) {
        // 用记录的真实期望长度：末条为 textLen 余数，其余为 recSize
        var want = (r === end && textLen) ? (textLen - (end - 1) * recSize) : recSize;
        plain = palmdocDecompress(rec, want > 0 ? want : 0);
      } else {
        plain = rec;
      }
      chunks.push(plain);
      if (r % 16 === 0) await new Promise(function (x) { setTimeout(x, 0); });
    }

    // 合并全部字节后统一解码
    var total = 0;
    for (var c = 0; c < chunks.length; c++) total += chunks[c].length;
    var all = new Uint8Array(total), at = 0;
    for (var c2 = 0; c2 < chunks.length; c2++) { all.set(chunks[c2], at); at += chunks[c2].length; }

    var dec = new TextDecoder(encoding === 'utf-8' ? 'utf-8' : 'windows-1252', { fatal: false });
    var html = dec.decode(all);
    // 截断到真实文本长度（去掉记录填充）
    if (textLen && html.length > textLen) html = html.slice(0, textLen);

    // MOBI 文本里常以 0x00 或 <mbp:pagebreak> 分章
    html = html.replace(/\u0000/g, ' ').replace(/<mbp:pagebreak[^>]*>/gi, '</p><p>');

    var blocks = htmlBlocks(html);
    if (!blocks.length) throw new Error('未能从 MOBI 中提取到文本');
    return blocks;
  }

  /* =====================================================================
   *  AZW3 (KF8)
   *  与 MOBI 同一 PDB 容器，但通常压缩方式为 1（无压缩）或 2（PalmDOC），
   *  文本为 HTML5。此处复用 parseMobi 的记录解压逻辑。
   * ===================================================================== */
  async function parseAzw3(buf) {
    // KF8 有时前部是 MOBI7、后部才是 KF8；先按统一流程解析，
    // 若得到文本过少再尝试从 EXTH 的 KF8 边界定位
    var blocks = await parseMobi(buf);
    return blocks;
  }

  /* =====================================================================
   *  FB2 解析（FictionBook2：XML）
   * ===================================================================== */
  function parseFb2(buf) {
    var u8 = new Uint8Array(buf);
    // 检测 BOM 与 XML 编码声明（TextDecoder 只认 iso-8859-1，不认 latin-1）
    var enc = null;
    if (u8[0] === 0xEF && u8[1] === 0xBB && u8[2] === 0xBF) enc = 'utf-8';
    else if (u8[0] === 0xFF && u8[1] === 0xFE) enc = 'utf-16le';
    if (!enc) {
      var head = new TextDecoder('iso-8859-1').decode(u8.subarray(0, 300));
      var m = /encoding="([^"]+)"/i.exec(head);
      if (m) enc = m[1].toLowerCase();
    }
    // 归一化编码名
    var alias = {
      'utf-8': 'utf-8', 'utf8': 'utf-8',
      'windows-1252': 'windows-1252', 'cp1252': 'windows-1252',
      'windows-1251': 'windows-1251', 'cp1251': 'windows-1251',
      'utf-16': 'utf-16le', 'utf-16le': 'utf-16le', 'utf-16be': 'utf-16be',
      'iso-8859-1': 'iso-8859-1', 'latin-1': 'iso-8859-1', 'latin1': 'iso-8859-1'
    };
    enc = alias[enc] || 'utf-8';
    var dec;
    try { dec = new TextDecoder(enc); } catch (e) { dec = new TextDecoder('utf-8'); }
    var xml = dec.decode(u8);

    var doc;
    try {
      doc = new DOMParser().parseFromString(xml, 'application/xml');
    } catch (e) {
      doc = new DOMParser().parseFromString(xml, 'text/xml');
    }
    if (doc.querySelector('parsererror')) {
      // 退路：按 HTML 处理
      return htmlBlocks(xml);
    }
    var kill = doc.querySelectorAll('description,binary,script,style');
    for (var i = 0; i < kill.length; i++) kill[i].remove();

    var out = [];
    var nodes = doc.querySelectorAll('body p, body subtitle, body title');
    nodes.forEach(function (el) {
      // <title><p>…</p></title> 会重复匹配，跳过含子 p 的 title
      if (el.tagName.toLowerCase() === 'title' && el.querySelector('p')) return;
      var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
      if (!t) return;
      var tag = el.tagName.toLowerCase();
      out.push({ tag: /title|subtitle/.test(tag) ? 'h2' : 'p', text: t });
    });
    if (!out.length) {
      var all = (doc.documentElement.textContent || '').replace(/\s+/g, ' ').trim();
      if (all) out.push({ tag: 'p', text: all });
    }
    return out;
  }

  /* =====================================================================
   *  DOCX 解析（OOXML：ZIP 内 word/document.xml）
   * ===================================================================== */
  async function parseDocx(buf) {
    var u8 = new Uint8Array(buf);
    var entries = listEntries(buf);
    var names = Object.keys(entries);
    var key = names.filter(function (n) { return /^word\/document\.xml$/i.test(n); })[0]
           || names.filter(function (n) { return /document\.xml$/i.test(n); })[0];
    if (!key) throw new Error('DOCX 缺少 word/document.xml');
    var bytes = await readEntry(u8, entries[key]);
    var xml = new TextDecoder('utf-8').decode(bytes);

    // 先按 <w:p> 切段，再抽取 <w:t> 文本；表格单元格同样并入
    var paras = xml.split(/<\/w:p>/i);
    var out = [];
    for (var i = 0; i < paras.length; i++) {
      var seg = paras[i];
      // 标题段落 -> h1/h2
      var styleM = /<w:pStyle[^>]*w:val="([^"]+)"/i.exec(seg);
      var texts = [], re = /<w:t\b[^>]*>([\s\S]*?)<\/w:t>/gi, m;
      while ((m = re.exec(seg))) texts.push(m[1]);
      var t = texts.join('')
        .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
        .replace(/&quot;/g, '"').replace(/&apos;/g, "'")
        .replace(/\s+/g, ' ').trim();
      if (!t) continue;
      var tag = 'p';
      if (styleM && /heading\s*1|^1$|Title/i.test(styleM[1])) tag = 'h1';
      else if (styleM && /heading\s*2|^2$/i.test(styleM[1])) tag = 'h2';
      out.push({ tag: tag, text: t });
    }
    if (!out.length) throw new Error('DOCX 未提取到文本');
    return out;
  }

  global.EBook = {
    parseEpub: parseEpub,
    parsePdf: parsePdf,
    parseMobi: parseMobi,
    parseAzw3: parseAzw3,
    parseFb2: parseFb2,
    parseDocx: parseDocx,
    htmlBlocks: htmlBlocks,
    listEntries: listEntries
  };
})(window);
