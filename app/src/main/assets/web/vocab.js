/* =====================================================================
 *  vocab.js — 离线词库引擎（多考纲版）
 *  词库通过 vocab2-data.js 注入（window.__VOCAB2_RAW），或从 assets 拉取。
 *  数据格式： word \t 音标 \t 释义 \t 考纲(逗号分隔) \t 档位(逗号分隔)
 *  考纲：cet6(六级) / gre / ielts(雅思) / toefl(托福)
 *  档位：1 基础  2 高频  3 中频(标注门槛)  4 低频  5 核心/生僻
 * ===================================================================== */
(function (global) {
  'use strict';

  var DICT = {};          // lemma -> {ph, def, tier}
  var FLAGGED = null;     // Set，含无内置释义但需标注的词
  var COCA = {};          // 词 -> 频次排名
  var loaded = false;

  var STOP = new Set(('a an the and or but nor so yet for of in on at by with from to ' +
    'into onto upon about above across after against along among around as before behind ' +
    'below beneath beside between beyond during except inside like near off out outside ' +
    'over past since through throughout till toward under until up within without ' +
    'i me my mine myself you your yours yourself he him his himself she her hers herself ' +
    'it its itself we us our ours ourselves they them their theirs themselves this that ' +
    'these those who whom whose which what where when why how am is are was were be been ' +
    'being have has had having do does did done doing will would shall should can could ' +
    'may might must ought need dare let not no nor none nothing nobody nowhere never very ' +
    'too also just only even still already yet ever always often sometimes usually seldom ' +
    'rarely here there then than now soon again once more most much many few less least ' +
    'little lot all both each every either neither some any several other another such ' +
    'same own said says say went gone go goes going get got gotten gets getting make made ' +
    'makes making take took taken takes taking come came comes coming see saw seen sees ' +
    'seeing know knew known knows knowing think thought thinks give gave given gives ' +
    'giving find found finds finding tell told tells telling become became becomes ' +
    'becoming leave left leaves leaving feel felt feels bring brought brings begin began ' +
    'begun begins keep kept keeps hold held holds write wrote written writes stand stood ' +
    'stands hear heard hears mean meant meet met meets run ran runs pay paid pays sit sat ' +
    'sits speak spoke spoken grow grew grown lose lost loses fall fell fallen send sent ' +
    'sends build built builds understand understood draw drew drawn break broke broken ' +
    'spend spent rise rose risen drive drove driven buy bought wear wore worn choose chose ' +
    'chosen put puts putting set sets cut cuts lay laid lie lain seek sought teach taught ' +
    'catch caught fight fought one two three four five six seven eight nine ten first ' +
    'second third man men woman women child children person people boy girl sir madam ' +
    'day days night nights year years time times hour hours minute minutes way ways thing ' +
    'things place places home house room world life hand hands head eye eyes face heart ' +
    'mind water fire light dark good better best bad worse worst great small large long ' +
    'short high low old new young big little right next last well fine sure certain able ' +
    'yes oh ah reply replied answer answered ask asked try carry hurry worry marry study ' +
    'apply cry deny occupy shelf leaf self wife knife wolf thief').split(' '));

  var IRREG = {
    was:'be',were:'be',is:'be',are:'be',am:'be',been:'be',being:'be',had:'have',
    has:'have',did:'do',done:'do',said:'say',made:'make',took:'take',taken:'take',
    came:'come',saw:'see',seen:'see',knew:'know',known:'know',got:'get',gotten:'get',
    gave:'give',given:'give',found:'find',thought:'think',told:'tell',became:'become',
    left:'leave',felt:'feel',brought:'bring',began:'begin',begun:'begin',kept:'keep',
    held:'hold',wrote:'write',written:'write',stood:'stand',heard:'hear',meant:'mean',
    met:'meet',ran:'run',paid:'pay',sat:'sit',spoke:'speak',spoken:'speak',led:'lead',
    grew:'grow',grown:'grow',lost:'lose',fell:'fall',fallen:'fall',sent:'send',
    built:'build',understood:'understand',drew:'draw',drawn:'draw',broke:'break',
    broken:'break',spent:'spend',rose:'rise',risen:'rise',drove:'drive',driven:'drive',
    bought:'buy',wore:'wear',worn:'wear',chose:'choose',chosen:'choose',men:'man',
    women:'woman',children:'child',feet:'foot',teeth:'tooth',mice:'mouse',geese:'goose',
    people:'person',went:'go',gone:'go',lay:'lie',lain:'lie',laid:'lay',sought:'seek',
    taught:'teach',caught:'catch',fought:'fight',
    tried:'try',tries:'try',trying:'try',carried:'carry',carries:'carry',
    hurried:'hurry',hurries:'hurry',worried:'worry',worries:'worry',married:'marry',
    marries:'marry',studied:'study',studies:'study',applied:'apply',applies:'apply',
    replied:'reply',replies:'reply',cried:'cry',cries:'cry',denied:'deny',denies:'deny',
    occupied:'occupy',occupies:'occupy',
    shelves:'shelf',wolves:'wolf',leaves:'leaf',halves:'half',selves:'self',
    loaves:'loaf',thieves:'thief',scarves:'scarf',lives:'life',wives:'wife',
    knives:'knife',shelved:'shelve',shelving:'shelve'
  };

  function inDict(w) { return Object.prototype.hasOwnProperty.call(DICT, w); }
  function inFlagged(w) { return FLAGGED ? FLAGGED.has(w) : inDict(w); }

  function _cands(w) {
    var out = [], seen = Object.create(null);
    function add(x) {
      if (x && x.length > 1 && !seen[x]) { seen[x] = 1; out.push(x); }
    }
    if (IRREG[w]) add(IRREG[w]);
    if (inDict(w) || inFlagged(w)) add(w);
    if (w.length > 3) {
      if (/ies$/.test(w) && w.length > 4) add(w.slice(0, -3) + 'y');
      if (/ves$/.test(w) && w.length > 4) { add(w.slice(0, -3) + 'f'); add(w.slice(0, -3) + 'fe'); }
      if (/es$/.test(w)) {
        if (/(ches|shes|sses|xes|zes)$/.test(w)) add(w.slice(0, -2));
        else { add(w.slice(0, -1)); add(w.slice(0, -2)); }
      }
      if (/s$/.test(w) && !/ss$/.test(w)) add(w.slice(0, -1));
      if (/ing$/.test(w)) {
        add(w.slice(0, -3)); add(w.slice(0, -3) + 'e');
        if (w.length > 4 && w.charAt(w.length - 4) === w.charAt(w.length - 5)) add(w.slice(0, -4));
      }
      if (/ed$/.test(w)) {
        add(w.slice(0, -2)); add(w.slice(0, -1));
        if (w.length > 4 && w.charAt(w.length - 3) === w.charAt(w.length - 4)) add(w.slice(0, -3));
        if (/ied$/.test(w) && w.length > 4) add(w.slice(0, -3) + 'y');
      }
      if (/ly$/.test(w)) { add(w.slice(0, -2)); add(w.slice(0, -2) + 'e'); add(w.slice(0, -2) + 'le'); }
      if (/ily$/.test(w) && w.length > 4) add(w.slice(0, -3) + 'y');
      if (/est$/.test(w)) { add(w.slice(0, -3)); add(w.slice(0, -2)); }
      if (/er$/.test(w)) { add(w.slice(0, -2)); add(w.slice(0, -1)); }
      if (/ness$/.test(w)) add(w.slice(0, -4));
      if (/ment$/.test(w)) add(w.slice(0, -4));
      if (/(ance|ence)$/.test(w)) add(w.slice(0, -4));
      if (/(able|ible)$/.test(w)) { add(w.slice(0, -4)); add(w.slice(0, -4) + 'e'); }
      if (/ally$/.test(w)) { add(w.slice(0, -4)); add(w.slice(0, -2)); }
      if (/(tion|sion)$/.test(w)) { add(w.slice(0, -3) + 'e'); add(w.slice(0, -4)); }
    }
    return out;
  }

  /** 返回原形（无则 null）。基础词/高频词一律不标注。 */
  function lemma(w) {
    w = (w || '').toLowerCase().replace(/^[^a-z]+|[^a-z]+$/g, '');
    if (!w || w.length < 3 || STOP.has(w)) return null;
    var c = _cands(w), i;
    for (i = 0; i < c.length; i++) if (STOP.has(c[i])) return null;
    var hits = [];
    for (i = 0; i < c.length; i++) if ((inDict(c[i]) || inFlagged(c[i])) && !STOP.has(c[i])) hits.push(c[i]);
    if (!hits.length) return null;
    // 频次护栏：任一候选原形属极高频词（前 1000）则不标注。
    // 例：picking -> pick (#518) 是基础词；investigate (#1816) 属六级词，需标注。
    var GUARD = 1000;
    for (i = 0; i < c.length; i++) {
      var r0 = COCA[c[i]];
      if (r0 && r0 <= GUARD) return null;
    }
    // 表面形式自身极常用，也视为基础用法
    var rSelf = COCA[w];
    if (rSelf && rSelf <= 800) return null;
    return hits[0];
  }

  /**
   * 判定某词在指定考纲下是否应标注。
   * @param {string} w  词形还原后的原形
   * @param {string} exam  考纲：'cet6' | 'gre' | 'ielts' | 'toefl'
   * @param {number} minLevel  难度门槛（默认 3 = 中频及以上）
   * @param {boolean} withExt  是否同时纳入「超纲拓展词」(ext)
   * @returns {{level:number, def:object}|null}
   */
  function match(w, exam, minLevel, withExt) {
    var e = DICT[w];
    if (!e || !e.exams) return null;
    var lo = minLevel || 3;
    var lv = e.exams[exam];
    if (lv !== undefined && lv !== null && lv >= lo) return { level: lv, def: e };
    // 拓展词：不属于当前考纲，但属超纲罕见词
    if (withExt) {
      var le = e.exams.ext;
      if (le !== undefined && le !== null && le >= lo) return { level: le, def: e, ext: true };
    }
    return null;
  }

  function get(w) { return DICT[w] || null; }
  function tier(w, exam) {
    var e = DICT[w];
    if (!e) return 0;
    if (exam && e.exams) return e.exams[exam] || 0;
    // 兼容旧调用：返回该词在其所有考纲中的最低档（最容易被标注的档）
    if (e.exams) {
      var vals = Object.keys(e.exams).map(function (k) { return e.exams[k]; });
      return vals.length ? Math.min.apply(null, vals) : 0;
    }
    return e.tier || 0;
  }

  /** 该词所属的全部考纲 */
  function examsOf(w) { var e = DICT[w]; return (e && e.exams) ? Object.keys(e.exams) : []; }

  /**
   * 加载词库文本。
   * 新格式：word\tphonetic\tdef\texams(逗号分隔)\tlevels(逗号分隔)
   * 旧格式（兼容）：word\tphonetic\tdef\ttier
   */
  function ingest(text) {
    var lines = text.split('\n'), n = 0;
    for (var i = 0; i < lines.length; i++) {
      var ln = lines[i];
      if (!ln) continue;
      var p = ln.split('\t');
      if (!p[0]) continue;
      var entry = { ph: p[1] || '', def: p[2] || '', exams: null, tier: 3 };
      if (p[3] && p[4]) {
        var exs = p[3].split(','), lvs = p[4].split(',');
        entry.exams = {};
        for (var k = 0; k < exs.length; k++) {
          if (exs[k]) entry.exams[exs[k]] = parseInt(lvs[k], 10) || 3;
        }
      } else if (p[3]) {
        entry.tier = parseInt(p[3], 10) || 3;
      }
      DICT[p[0]] = entry;
      n++;
    }
    loaded = true;
    return n;
  }

  function load() {
    return new Promise(function (resolve, reject) {
      // 1) 注入式（APK 内置）：优先新格式，回退旧格式
      if (global.__VOCAB2_RAW || global.__VOCAB_RAW) {
        try {
          ingest(global.__VOCAB2_RAW || global.__VOCAB_RAW);
          if (global.__FLAGGED_RAW) FLAGGED = new Set(global.__FLAGGED_RAW.split('\n'));
          else FLAGGED = new Set(Object.keys(DICT));
          if (global.__COCA_RAW) global.__COCA_RAW.split('\n').forEach(function (l, i) {
            if (l) COCA[l] = i + 1;
          });
          return resolve({ mode: 'inline', count: Object.keys(DICT).length });
        } catch (e) { /* 降级 fetch */ }
      }
      // 2) fetch assets
      fetch('../vocab2.tsv').then(function (r) { return r.text(); })
        .then(function (t) {
          var n = ingest(t);
          return fetch('../flagged.txt').then(function (r) { return r.text(); })
            .then(function (f) { FLAGGED = new Set(f.split('\n').filter(Boolean)); })
            .catch(function () { FLAGGED = new Set(Object.keys(DICT)); })
            .then(function () { resolve({ mode: 'fetch', count: n }); });
        })
        .catch(function (e) {
          // 3) 无词库（仍可阅读，只是不标注）
          DICT = {}; FLAGGED = new Set(); loaded = true;
          resolve({ mode: 'none', count: 0, error: String(e) });
        });
    });
  }

  global.Vocab = {
    load: load, ingest: ingest, lemma: lemma, get: get, tier: tier,
    match: match, examsOf: examsOf,
    isLoaded: function () { return loaded; },
    count: function () { return Object.keys(DICT).length; },
    flaggedCount: function () { return FLAGGED ? FLAGGED.size : 0; },
    setCoca: function (obj) { COCA = obj || {}; }
  };
})(window);
