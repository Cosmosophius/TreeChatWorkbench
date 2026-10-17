/**
 * etym_cross_align.js — 汉-英-俄三语历史拟音、全域同义词与跨语言比较词源映射引擎
 * 
 * 功能：
 * 1. 提供全局跨语概念检索与对齐 (Cross-linguistic Concept Alignment)
 * 2. 支持三向双向查询：
 *    - 输入汉语（如“神圣”、“圣”、“光”、“智”）→ 展开汉语三代历时拟音 + 英语日耳曼/拉丁/希腊全同义词链 + 俄语斯拉夫/教会斯拉夫/外来借词链
 *    - 输入俄语（如“святой”、“свет”、“мудрый”）→ 展开俄语标准 IPA 与词源 + 映射汉语历史拟音 + 映射英语双口音全同义词
 *    - 输入英语（如“holy”、“sacred”、“luminous”）→ 展开英语双音标与词源族谱 + 映射汉字历时拟音 + 映射俄语斯拉夫语族对应词
 */
(function (global) {
  'use strict';

  // 跨语言核心概念枢纽注册表
  var CONCEPT_ALIGNMENTS = [
    {
      conceptId: 'holiness',
      canonicalZh: '神圣',
      zhKeywords: ['神圣', '圣', '神', '圣洁', '崇高', '尊崇'],
      engKey: 'holy',
      rusKey: 'святой',
      summary: '超验神圣体验、敬畏与至洁之德（汉：上古至德通明；英：日耳曼充盈本根与拉丁法律奉献；俄：原始斯拉夫光明神圣与南斯拉夫教会斯拉夫雅言）'
    },
    {
      conceptId: 'luminosity',
      canonicalZh: '光明',
      zhKeywords: ['光', '光明', '明', '照耀', '灿烂', '辉煌'],
      engKey: 'light',
      rusKey: 'свет',
      summary: '破除黑暗之明辉与荣耀（汉：见母唐韵古音；英：印欧*lewk-根系演变；俄：斯拉夫*světъ光芒与尘世合一）'
    },
    {
      conceptId: 'wisdom',
      canonicalZh: '智慧',
      zhKeywords: ['智', '智慧', '聪明', '睿智', '博学', '贤明'],
      engKey: 'wisdom',
      rusKey: 'мудрость',
      summary: '鉴照洞察与博雅哲思（汉：知母寘韵三等；英：日耳曼见闻人生之智与拉丁学者博雅；俄：长者深思与索菲亚神圣智慧）'
    }
  ];

  /**
   * 全息查询调度器
   * @param {string} inputQuery 任意输入的中文、英文或俄文词汇
   * @returns {object|null} 整合完整的跨语概念比对报告
   */
  function resolveEtymologyAlignment(inputQuery) {
    if (!inputQuery) return null;
    var q = inputQuery.trim();
    var lowerQ = q.toLowerCase();

    // 1. 尝试匹配预设概念枢纽
    var matchedAlignment = null;
    for (var i = 0; i < CONCEPT_ALIGNMENTS.length; i++) {
      var item = CONCEPT_ALIGNMENTS[i];
      // 匹配汉语关键词
      for (var z = 0; z < item.zhKeywords.length; z++) {
        if (q.indexOf(item.zhKeywords[z]) !== -1) {
          matchedAlignment = item;
          break;
        }
      }
      if (matchedAlignment) break;

      // 匹配英语
      if (global.EngEtymDb && global.EngEtymDb.clusters[item.engKey]) {
        var engCluster = global.EngEtymDb.clusters[item.engKey];
        for (var e = 0; e < engCluster.words.length; e++) {
          if (engCluster.words[e].word.toLowerCase() === lowerQ) {
            matchedAlignment = item;
            break;
          }
        }
      }
      if (matchedAlignment) break;

      // 匹配俄语
      if (global.RusEtymDb && global.RusEtymDb.clusters[item.rusKey]) {
        var rusCluster = global.RusEtymDb.clusters[item.rusKey];
        for (var r = 0; r < rusCluster.words.length; r++) {
          if (rusCluster.words[r].word.toLowerCase() === lowerQ) {
            matchedAlignment = item;
            break;
          }
        }
      }
      if (matchedAlignment) break;
    }

    if (!matchedAlignment) {
      // 容错匹配：如果单字命中汉语音韵库，构建单字专栏
      if (global.CmnPhonology && global.CmnPhonology.db[q.charAt(0)]) {
        return {
          type: 'cmn_single',
          query: q,
          cmnPhonology: global.CmnPhonology.query(q),
          engCluster: null,
          rusCluster: null
        };
      }
      return null;
    }

    // 2. 组装全息三语数据
    var cmnData = global.CmnPhonology ? global.CmnPhonology.query(matchedAlignment.canonicalZh) : [];
    var engData = global.EngEtymDb ? global.EngEtymDb.clusters[matchedAlignment.engKey] : null;
    var rusData = global.RusEtymDb ? global.RusEtymDb.clusters[matchedAlignment.rusKey] : null;

    return {
      type: 'full_alignment',
      query: q,
      canonicalZh: matchedAlignment.canonicalZh,
      summary: matchedAlignment.summary,
      cmnPhonology: cmnData,
      engCluster: engData,
      rusCluster: rusData
    };
  }

  global.EtymCrossAlign = {
    resolve: resolveEtymologyAlignment,
    alignments: CONCEPT_ALIGNMENTS
  };

})(typeof window !== 'undefined' ? window : this);
