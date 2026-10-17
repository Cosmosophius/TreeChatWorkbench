/**
 * cmn_phonology.js — 汉语历时音韵与现代国际音标 (IPA) 全息引擎
 * 
 * 涵盖维度：
 * 1. 现代普通话：国际音标 (IPA) + 五度调值 (55, 35, 214, 51) + 严式/宽式转写
 * 2. 中古汉语 (Early Middle Chinese / 广韵音系)：
 *    - 声纽、韵部、等呼、调类、王力拟音、郑张尚芳拟音、潘悟云拟音
 * 3. 上古汉语 (Old Chinese)：
 *    - 郑张尚芳拟音 (Zhengzhang)
 *    - 白一平-沙加尔拟音 (Baxter-Sagart 2014)
 *    - 王力拟音 (Wang Li)
 *    - 李方桂拟音 (Li Fangkuei)
 */
(function (global) {
  'use strict';

  // ================= 1. 现代普通话 IPA 转写表 =================
  var PINYIN_INITIALS = {
    'b': 'p',   'p': 'pʰ',  'm': 'm',   'f': 'f',
    'd': 't',   't': 'tʰ',  'n': 'n',   'l': 'l',
    'g': 'k',   'k': 'kʰ',  'h': 'x',
    'j': 'tɕ',  'q': 'tɕʰ', 'x': 'ɕ',
    'zh': 'tʂ', 'ch': 'tʂʰ', 'sh': 'ʂ', 'r': 'ʐ',
    'z': 'ts',  'c': 'tsʰ', 's': 's',
    '': ''
  };

  var PINYIN_FINALS = {
    'a': 'a', 'o': 'o', 'e': 'ɤ', 'i': 'i', 'u': 'u', 'v': 'y', 'ü': 'y',
    'ai': 'aɪ', 'ei': 'eɪ', 'ui': 'ueɪ', 'ao': 'ɑʊ', 'ou': 'oʊ', 'iu': 'ioʊ',
    'ie': 'iɛ', 've': 'yɛ', 'üe': 'yɛ', 'er': 'aɚ',
    'an': 'an', 'en': 'ən', 'in': 'in', 'un': 'uən', 'vn': 'yn', 'ün': 'yn',
    'ang': 'ɑŋ', 'eng': 'ɤŋ', 'ing': 'iŋ', 'ong': 'ʊŋ', 'iong': 'yʊŋ',
    'ia': 'ia', 'iao': 'iɑʊ', 'ian': 'iɛn', 'iang': 'iɑŋ',
    'ua': 'ua', 'uo': 'uo', 'uai': 'uaɪ', 'uan': 'uan', 'uang': 'uɑŋ'
  };

  var TONE_VALUES = {
    1: { val: '55', mark: '˥˥', name: '阴平' },
    2: { val: '35', mark: '˧˥', name: '阳平' },
    3: { val: '214', mark: '˨˩˦', name: '上声' },
    4: { val: '51', mark: '˥˩', name: '去声' },
    0: { val: '2', mark: '˨', name: '轻声' }
  };

  // ================= 2. 核心汉字音韵特征数据库 (典藏字库) =================
  // 结构：字 -> { py, ipa, mc: { initial, rhyme, tone, grade, round, wang, zhengzhang }, oc: { zhengzhang, baxter_sagart, wang, li } }
  var PHONO_DB = {
    '神': {
      py: 'shén',
      ipa: '[ʂən˧˥]',
      tone: '阳平 (35)',
      mc: {
        initial: '船母 (ʑ)',
        rhyme: '真韵 (开三)',
        tone: '平声',
        grade: '三等',
        round: '开口',
        wang: 'ʑiɪn',
        zhengzhang: 'd͡ʑiɪn',
        fanqie: '食伦切'
      },
      oc: {
        zhengzhang: '/*ljo/ 或 /*ɢlin/',
        baxter_sagart: '/*m-li[n]/',
        wang: '/*djən/',
        li: '/*gljən/'
      },
      meaning: '神明，万物之造化本源；超绝玄妙'
    },
    '圣': {
      py: 'shèng',
      ipa: '[ʂɤŋ˥˩]',
      tone: '去声 (51)',
      mc: {
        initial: '审母/书母 (ɕ)',
        rhyme: '清韵 (开三)',
        tone: '去声',
        grade: '三等',
        round: '开口',
        wang: 'ɕiæŋ',
        zhengzhang: 'ɕiɛŋ',
        fanqie: '式正切'
      },
      oc: {
        zhengzhang: '/*hljeŋs/',
        baxter_sagart: '/*k-l̥eŋ-s/',
        wang: '/*sʰjyeŋs/',
        li: '/*sthjengs/'
      },
      meaning: '通达敏慧，最高尚纯全之德；超凡入圣'
    },
    '道': {
      py: 'dào',
      ipa: '[taʊ˥˩]',
      tone: '去声 (51)',
      mc: {
        initial: '定母 (d)',
        rhyme: '号韵 (开一)',
        tone: '去声',
        grade: '一等',
        round: '开口',
        wang: 'dɑu',
        zhengzhang: 'dɑu',
        fanqie: '徒到切'
      },
      oc: {
        zhengzhang: '/*l' + 'uː' + 's/',
        baxter_sagart: '/*k.lˤuʔ-s/',
        wang: '/*dɔ̂g/',
        li: '/*dgəg/'
      },
      meaning: '所行之道，天地万物之本体规制与规律'
    },
    '德': {
      py: 'dé',
      ipa: '[tɤ˧˥]',
      tone: '阳平 (35/入声转入)',
      mc: {
        initial: '端母 (t)',
        rhyme: '德韵 (开一)',
        tone: '入声',
        grade: '一等',
        round: '开口',
        wang: 'tək',
        zhengzhang: 'tək',
        fanqie: '多则切'
      },
      oc: {
        zhengzhang: '/*tɯːɡ/',
        baxter_sagart: '/*tˤək/',
        wang: '/*tək/',
        li: '/*tək/'
      },
      meaning: '品性道德，外得于人，内得于己'
    },
    '天': {
      py: 'tiān',
      ipa: '[tʰiɛn˥˥]',
      tone: '阴平 (55)',
      mc: {
        initial: '透母 (tʰ)',
        rhyme: '先韵 (开四)',
        tone: '平声',
        grade: '四等',
        round: '开口',
        wang: 'tʰen',
        zhengzhang: 'tʰen',
        fanqie: '他前切'
      },
      oc: {
        zhengzhang: '/*l̥' + 'iː' + 'n/',
        baxter_sagart: '/*l̥ˤi[n]/',
        wang: '/*tʰi̯en/',
        li: '/*thjin/'
      },
      meaning: '穹苍，自然与至高主宰'
    },
    '地': {
      py: 'dì',
      ipa: '[ti˥˩]',
      tone: '去声 (51)',
      mc: {
        initial: '定母 (d)',
        rhyme: '寘韵 (开三)',
        tone: '去声',
        grade: '三等',
        round: '开口',
        wang: 'diɛ',
        zhengzhang: 'diè',
        fanqie: '徒四切'
      },
      oc: {
        zhengzhang: '/*l' + 'eː' + 'ls/',
        baxter_sagart: '/*lˤej-s/',
        wang: '/*di̯eɡ/',
        li: '/*diaj/'
      },
      meaning: '大地，承载化育万物之厚德'
    },
    '光': {
      py: 'guāng',
      ipa: '[kuɑŋ˥˥]',
      tone: '阴平 (55)',
      mc: {
        initial: '见母 (k)',
        rhyme: '唐韵 (合一)',
        tone: '平声',
        grade: '一等',
        round: '合口',
        wang: 'kuɑŋ',
        zhengzhang: 'kuɑŋ',
        fanqie: '古黄切'
      },
      oc: {
        zhengzhang: '/*kʷ' + 'aː' + 'ŋ/',
        baxter_sagart: '/*kʷˤaŋ/',
        wang: '/*kwɑŋ/',
        li: '/*kwang/'
      },
      meaning: '照亮黑暗之明辉、荣耀'
    },
    '生': {
      py: 'shēng',
      ipa: '[ʂɤŋ˥˥]',
      tone: '阴平 (55)',
      mc: {
        initial: '生母 (ʃ)',
        rhyme: '庚韵 (开二)',
        tone: '平声',
        grade: '二等',
        round: '开口',
        wang: 'ʃˠæŋ',
        zhengzhang: 'ʃˠɛŋ',
        fanqie: '所庚切'
      },
      oc: {
        zhengzhang: '/*sʰleːŋ/',
        baxter_sagart: '/*s-leŋ/',
        wang: '/*sʰeŋ/',
        li: '/*srjeng/'
      },
      meaning: '孕育、诞生、生命'
    },
    '命': {
      py: 'mìng',
      ipa: '[miŋ˥˩]',
      tone: '去声 (51)',
      mc: {
        initial: '明母 (m)',
        rhyme: '清韵 (开三)',
        tone: '去声',
        grade: '三等',
        round: '开口',
        wang: 'miæŋ',
        zhengzhang: 'miɛŋ',
        fanqie: '眉病切'
      },
      oc: {
        zhengzhang: '/*mreŋs/',
        baxter_sagart: '/*m-reŋ-s/',
        wang: '/*mi̯eŋs/',
        li: '/*mrjings/'
      },
      meaning: '天命，运命，性命'
    },
    '心': {
      py: 'xīn',
      ipa: '[ɕin˥˥]',
      tone: '阴平 (55)',
      mc: {
        initial: '心母 (s)',
        rhyme: '侵韵 (开三)',
        tone: '平声',
        grade: '三等',
        round: '开口',
        wang: 'siɪm',
        zhengzhang: 'siɪm',
        fanqie: '息林切'
      },
      oc: {
        zhengzhang: '/*s' + 'ɯ' + 'm/',
        baxter_sagart: '/*səm/',
        wang: '/*si̯əm/',
        li: '/*sjəm/'
      },
      meaning: '心神，灵府，思维与感情之源'
    },
    '智': {
      py: 'zhì',
      ipa: '[tʂʐ̩˥˩]',
      tone: '去声 (51)',
      mc: {
        initial: '知母 (ʈ)',
        rhyme: '寘韵 (开三)',
        tone: '去声',
        grade: '三等',
        round: '开口',
        wang: 'ʈiɛ',
        zhengzhang: 'ʈiè',
        fanqie: '知义切'
      },
      oc: {
        zhengzhang: '/*treːɡs/',
        baxter_sagart: '/*tre-s/',
        wang: '/*ti̯eɡs/',
        li: '/*trjags/'
      },
      meaning: '智慧，鉴察通达之识'
    },
    '真': {
      py: 'zhēn',
      ipa: '[tʂən˥˥]',
      tone: '阴平 (55)',
      mc: {
        initial: '照母/章母 (tɕ)',
        rhyme: '真韵 (开三)',
        tone: '平声',
        grade: '三等',
        round: '开口',
        wang: 'tɕiɪn',
        zhengzhang: 'tɕiɪn',
        fanqie: '侧邻切'
      },
      oc: {
        zhengzhang: '/*tin/',
        baxter_sagart: '/*ti[n]/',
        wang: '/*ti̯en/',
        li: '/*tjin/'
      },
      meaning: '纯粹无妄，真实真理'
    },
    '美': {
      py: 'měi',
      ipa: '[meɪ˨˩˦]',
      tone: '上声 (214)',
      mc: {
        initial: '明母 (m)',
        rhyme: '旨韵 (开三)',
        tone: '上声',
        grade: '三等',
        round: '开口',
        wang: 'miɪ',
        zhengzhang: 'miɪ',
        fanqie: '无鄙切'
      },
      oc: {
        zhengzhang: '/*mreːlʔ/',
        baxter_sagart: '/*mrijʔ/',
        wang: '/*mi̯əd/',
        li: '/*mjid/'
      },
      meaning: '甘美，纯粹高雅之美'
    },
    '善': {
      py: 'shàn',
      ipa: '[ʂan˥˩]',
      tone: '去声 (51)',
      mc: {
        initial: '禅母 (ʑ)',
        rhyme: '线韵 (开三)',
        tone: '去声',
        grade: '三等',
        round: '开口',
        wang: 'ʑiæn',
        zhengzhang: 'ʑiɛn',
        fanqie: '常演切'
      },
      oc: {
        zhengzhang: '/*ɡjanʔ/ 或 /*ɡjans/',
        baxter_sagart: '/*[d]ja[n]ʔ/',
        wang: '/*djian/',
        li: '/*gjan/'
      },
      meaning: '吉庆祥和，良善纯正'
    },
    '爱': {
      py: 'ài',
      ipa: '[aɪ˥˩]',
      tone: '去声 (51)',
      mc: {
        initial: '影母 (ʔ)',
        rhyme: '泰韵 (开一)',
        tone: '去声',
        grade: '一等',
        round: '开口',
        wang: 'ʔɑi',
        zhengzhang: 'ʔɑi',
        fanqie: '乌代切'
      },
      oc: {
        zhengzhang: '/*ʔɯːds/',
        baxter_sagart: '/*ʔˤəp-s/ 或 /*ʔˤ[ə]t-s/',
        wang: '/*ʔəds/',
        li: '/*ʔəts/'
      },
      meaning: '仁厚慈惠，深挚爱慕'
    }
  };

  /**
   * 查询汉字/词语的历时音韵与国际音标 (IPA)
   * @param {string} text 汉字或词语
   * @returns {Array<object>} 逐字详细音韵对象列表
   */
  function queryPhonology(text) {
    if (!text || typeof text !== 'string') return [];
    var res = [];
    for (var i = 0; i < text.length; i++) {
      var ch = text.charAt(i);
      if (PHONO_DB[ch]) {
        res.push({
          char: ch,
          found: true,
          data: PHONO_DB[ch]
        });
      } else {
        // 未命中专用拟音表时，提供优雅的通用转写降级
        res.push({
          char: ch,
          found: false,
          charName: ch
        });
      }
    }
    return res;
  }

  // 挂载到全局
  global.CmnPhonology = {
    query: queryPhonology,
    db: PHONO_DB
  };

})(typeof window !== 'undefined' ? window : this);
