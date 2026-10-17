/**
 * etym_eng_database.js — 英语双轨音标、全域同义词矩阵（含极端生僻词）与词源谱系库
 * 
 * 音标规范：
 * - [Modern RP]  现代通用英音
 * - [Trad. RP]   1910s~1940s 英国公学/贵族 Conservative RP
 * 
 * 词源谱系族：
 * - Proto-Germanic / Old English (原始日耳曼语/古英语本土底层)
 * - Latin / Classical Latin (古典拉丁语)
 * - Old French / Anglo-Norman (古法语/诺曼征服借词)
 * - Ancient Greek (古希腊哲学/神学/科学渊源)
 */
(function (global) {
  'use strict';

  var ENG_SYNONYM_CLUSTERS = {
    // ==========================================
    // 概念簇 1：神圣 / 圣洁 / 崇高 (HOLY / SACRED)
    // ==========================================
    'holy': {
      concept: '神圣 / 圣洁 / 尊崇',
      concept_zh: '神圣',
      words: [
        {
          word: 'holy',
          ipa_modern: '/ˈhəʊli/',
          ipa_trad: '/ˈhoulɪ/',
          origin: 'Proto-Germanic (日耳曼祖语)',
          evolution: 'PIE *kailo- (完整/无损) → Proto-Germanic *hailagaz → Old English hālig',
          register: '核心基础 / 宗教本土根词',
          rarity: '常用 (星级 ★★★★★)'
        },
        {
          word: 'sacred',
          ipa_modern: '/ˈseɪkrɪd/',
          ipa_trad: '/ˈseɪkrɪd/',
          origin: 'Old French via Latin (古法语←拉丁语)',
          evolution: 'Latin sacrare (奉献) / sacer (神圣的) → Old French sacré → Middle English sacred',
          register: '通用庄重 / 典礼与神圣性',
          rarity: '常用 (星级 ★★★★☆)'
        },
        {
          word: 'hallowed',
          ipa_modern: '/ˈhæləʊd/',
          ipa_trad: '/ˈhæloud/',
          origin: 'Old English (古英语)',
          evolution: 'Old English hālgian (祝圣、尊为圣) → 衍生出 Halloween (All Hallows Eve)',
          register: '庄严文学 / 敬奉典故',
          rarity: '进阶高阶 (星级 ★★★☆☆)'
        },
        {
          word: 'divine',
          ipa_modern: '/dɪˈvaɪn/',
          ipa_trad: '/dɪˈvaɪn/',
          origin: 'Latin (拉丁语)',
          evolution: 'PIE *dyeu- (发光的天空/神) → Latin divinus (属神的) → Old French devin',
          register: '超越神性 / 天赋绝伦',
          rarity: '常用核心 (星级 ★★★★☆)'
        },
        {
          word: 'sacrosanct',
          ipa_modern: '/ˈsækrəʊsæŋkt/',
          ipa_trad: '/ˈsækrousæŋkt/',
          origin: 'Classical Latin (古典拉丁复合词)',
          evolution: 'Latin sacro (神圣宣誓) + sanctus (不可侵犯的法律神圣) = 罗马保民官神圣不可侵犯权',
          register: '极庄重法律与政治 / 不容置疑',
          rarity: '高阶生僻 (星级 ★★☆☆☆)'
        },
        {
          word: 'numinous',
          ipa_modern: '/ˈnjuːmɪnəs/',
          ipa_trad: '/ˈsjuːmɪnəs/ (或 /ˈnjuːmɪnəs/)',
          origin: 'Latin (拉丁宗教学术造词)',
          evolution: 'Latin numen (神之意志/神性光芒)；鲁道夫·奥托在《论神圣》中确立此词指敬畏之超验体验',
          register: '神学与哲学 / 超验神秘敬畏',
          rarity: '极生僻文学/学术罕词 (星级 ★☆☆☆☆)'
        },
        {
          word: 'hieratic',
          ipa_modern: '/ˌhaɪəˈrætɪk/',
          ipa_trad: '/ˌhaɪəˈrætɪk/',
          origin: 'Ancient Greek (古希腊语)',
          evolution: 'Ancient Greek ἱερατικός (hieratikós) ← ἱερός (hierós, 崇高祭司神圣的)',
          register: '古典考古与古宗教祭司专有',
          rarity: '极生僻文辞罕词 (星级 ★☆☆☆☆)'
        },
        {
          word: 'consecrated',
          ipa_modern: '/ˈkɒnsɪkreɪtɪd/',
          ipa_trad: '/ˈkɔːnsɪkreɪtɪd/',
          origin: 'Latin (拉丁语)',
          evolution: 'Latin com- (加强) + sacrare (立为圣物) = 祝圣专用于神明的',
          register: '宗教祝圣仪式 / 敬奉专用',
          rarity: '进阶中频 (星级 ★★★☆☆)'
        },
        {
          word: 'inviolable',
          ipa_modern: '/ɪnˈvaɪələbl/',
          ipa_trad: '/ɪnˈvaɪələbl/',
          origin: 'Latin (拉丁语)',
          evolution: 'Latin in- (否定) + violare (亵渎/侵犯) = 圣洁崇高而神圣不可亵渎的',
          register: '庄严宪制与不可侵犯准则',
          rarity: '高阶高级 (星级 ★★☆☆☆)'
        },
        {
          word: 'hierophantic',
          ipa_modern: '/ˌhaɪərəʊˈfæntɪk/',
          ipa_trad: '/ˌhaɪərouˈfæntɪk/',
          origin: 'Ancient Greek (古希腊神秘宗教)',
          evolution: 'Greek hieros (圣) + phainein (显现) = 揭示至圣奥秘的大祭司般的',
          register: '极限生僻神秘学词汇',
          rarity: '罕见古雅生僻词 (星级 ☆☆☆☆☆)'
        }
      ]
    },

    // ==========================================
    // 概念簇 2：光明 / 照耀 / 璀璨 (LIGHT / BRILLIANT)
    // ==========================================
    'light': {
      concept: '光明 / 照耀 / 璀璨辉煌',
      concept_zh: '光',
      words: [
        {
          word: 'light',
          ipa_modern: '/laɪt/',
          ipa_trad: '/laɪt/',
          origin: 'Proto-Germanic (日耳曼祖语)',
          evolution: 'PIE *lewk- (明亮/看) → Proto-Germanic *leuhtą → Old English lēoht',
          register: '本土最核心基石词',
          rarity: '常用 (星级 ★★★★★)'
        },
        {
          word: 'luminous',
          ipa_modern: '/ˈluːmɪnəs/',
          ipa_trad: '/ˈljuːmɪnəs/',
          origin: 'Latin (拉丁语)',
          evolution: 'Latin lumen (光芒) + -osus (充满) → Late Middle English',
          register: '高雅书面与自然发光',
          rarity: '进阶中频 (星级 ★★★☆☆)'
        },
        {
          word: 'radiant',
          ipa_modern: '/ˈreɪdiənt/',
          ipa_trad: '/ˈreɪdɪənt/',
          origin: 'Latin (拉丁语)',
          evolution: 'Latin radiare (辐射光束) ← radius (光芒/辐条)',
          register: '欢欣焕发与光彩照人',
          rarity: '常用高阶 (星级 ★★★★☆)'
        },
        {
          word: 'fulgent',
          ipa_modern: '/ˈfʊldʒənt/',
          ipa_trad: '/ˈfʌldʒənt/',
          origin: 'Classical Latin (古典拉丁语)',
          evolution: 'Latin fulgere (像闪电般闪烁/发光) → 15世纪借入英语',
          register: '古典修辞 / 耀眼夺目',
          rarity: '极生僻典雅文学词 (星级 ★☆☆☆☆)'
        },
        {
          word: 'effulgent',
          ipa_modern: '/ɪˈfʊldʒənt/',
          ipa_trad: '/ɪˈfʌldʒənt/',
          origin: 'Latin (拉丁语)',
          evolution: 'Latin ex- (彻底地/向外) + fulgere (闪闪发光) = 光辉四射灿烂无比',
          register: '弥尔顿等古典史诗级修辞词',
          rarity: '极生僻古典史诗词 (星级 ★☆☆☆☆)'
        },
        {
          word: 'refulgent',
          ipa_modern: '/rɪˈfʊldʒənt/',
          ipa_trad: '/rɪˈfʌldʒənt/',
          origin: 'Latin (拉丁语)',
          evolution: 'Latin re- (反射/辉映) + fulgere (闪耀) = 交相辉映的灿烂',
          register: '高阶古雅华美文学',
          rarity: '极生僻文学词 (星级 ★☆☆☆☆)'
        },
        {
          word: 'coruscating',
          ipa_modern: '/ˈkɒrəskeɪtɪŋ/',
          ipa_trad: '/ˈkɔːrəskeɪtɪŋ/',
          origin: 'Classical Latin (古典拉丁语)',
          evolution: 'Latin coruscare (急速闪烁/金光激射)',
          register: '形容智慧或珠宝如电光闪烁般夺目',
          rarity: '超生僻学术级文学辞藻 (星级 ☆☆☆☆☆)'
        },
        {
          word: 'lambent',
          ipa_modern: '/ˈlæmbənt/',
          ipa_trad: '/ˈlæmbənt/',
          origin: 'Latin (拉丁语)',
          evolution: 'Latin lambere (温柔舔舐) → 像柔和火苗般在表面轻轻游走的清辉',
          register: '抒情诗歌与柔和灵秀之光',
          rarity: '极生僻诗歌词汇 (星级 ★☆☆☆☆)'
        },
        {
          word: 'lucid',
          ipa_modern: '/ˈluːsɪd/',
          ipa_trad: '/ˈljuːsɪd/',
          origin: 'Latin (拉丁语)',
          evolution: 'Latin lucidus (清晰明澈的) ← lux (光)',
          register: '通透澄澈 / 神志清明',
          rarity: '常用中高频 (星级 ★★★☆☆)'
        },
        {
          word: 'pellucid',
          ipa_modern: '/pəˈluːsɪd/',
          ipa_trad: '/pɛˈljuːsɪd/',
          origin: 'Latin (拉丁语)',
          evolution: 'Latin per- (彻底贯通) + lucidus (清澈) = 晶莹剔透纤尘不染',
          register: '精粹散文与哲学风格',
          rarity: '高阶罕词 (星级 ★★☆☆☆)'
        }
      ]
    },

    // ==========================================
    // 概念簇 3：智慧 / 睿智 / 博学 (WISDOM / SAPIENT)
    // ==========================================
    'wisdom': {
      concept: '智慧 / 渊博 / 睿知洞察',
      concept_zh: '智',
      words: [
        {
          word: 'wise',
          ipa_modern: '/waɪz/',
          ipa_trad: '/waɪz/',
          origin: 'Proto-Germanic (日耳曼祖语)',
          evolution: 'PIE *weyd- (看见/知晓) → Proto-Germanic *wīsaz → Old English wīs',
          register: '本土根词 / 朴素深邃的人生智慧',
          rarity: '常用 (星级 ★★★★★)'
        },
        {
          word: 'sagacious',
          ipa_modern: '/səˈɡeɪʃəs/',
          ipa_trad: '/səˈɡeɪʃəs/',
          origin: 'Classical Latin (古典拉丁语)',
          evolution: 'Latin sagax (嗅觉灵敏、洞察敏锐的) ← PIE *sag- (探求)',
          register: '老练精明 / 政治与历史洞见',
          rarity: '进阶高难词 (星级 ★★☆☆☆)'
        },
        {
          word: 'sapient',
          ipa_modern: '/ˈseɪpiənt/',
          ipa_trad: '/ˈseɪpɪənt/',
          origin: 'Latin (古典拉丁语)',
          evolution: 'Latin sapere (尝其滋味/辨识通晓) → Homo sapiens (智人)',
          register: '生物人类学 / 哲学高深反讽或赞颂',
          rarity: '高阶典雅生僻词 (星级 ★★☆☆☆)'
        },
        {
          word: 'perspicacious',
          ipa_modern: '/ˌpɜːspɪˈkeɪʃəs/',
          ipa_trad: '/ˌpɜːspɪˈkeɪʃəs/',
          origin: 'Classical Latin (古典拉丁语)',
          evolution: 'Latin per- (穿透) + specere (看) = 具极强透视力与洞察力的',
          register: '学术批评与深刻心智辨析',
          rarity: '极生僻考研/GRE顶级词 (星级 ★☆☆☆☆)'
        },
        {
          word: 'erudite',
          ipa_modern: '/ˈerudaɪt/',
          ipa_trad: '/ˈerjʊdaɪt/',
          origin: 'Classical Latin (古典拉丁语)',
          evolution: 'Latin e- (去除) + rudis (粗野未开化/生硬) = 经过深厚洗练之博学',
          register: '饱读诗书与宏大博学',
          rarity: '高阶学术词 (星级 ★★☆☆☆)'
        },
        {
          word: 'polymathic',
          ipa_modern: '/ˌpɒliˈmæθɪk/',
          ipa_trad: '/ˌpɔːlɪˈmæθɪk/',
          origin: 'Ancient Greek (古希腊语)',
          evolution: 'Greek poly- (多) + manthanein (学习) = 通晓百科全书多学科的通才',
          register: '文理全才与达芬奇式博雅心智',
          rarity: '高阶学术专词 (星级 ★★☆☆☆)'
        },
        {
          word: 'sophic',
          ipa_modern: '/ˈsɒfɪk/',
          ipa_trad: '/ˈsɔːfɪk/',
          origin: 'Ancient Greek (古希腊哲学)',
          evolution: 'Greek σοφία (sophia, 智慧) → sophic',
          register: '古希腊哲学玄思之智',
          rarity: '极生僻古典哲学词 (星级 ☆☆☆☆☆)'
        }
      ]
    }
  };

  /**
   * 按单词反查所属同义词簇及完整词源家族
   */
  function findEngClusterByWord(word) {
    if (!word) return null;
    var w = word.toLowerCase().trim();
    for (var key in ENG_SYNONYM_CLUSTERS) {
      var cluster = ENG_SYNONYM_CLUSTERS[key];
      for (var i = 0; i < cluster.words.length; i++) {
        if (cluster.words[i].word.toLowerCase() === w) {
          return {
            matchedWord: cluster.words[i],
            cluster: cluster
          };
        }
      }
    }
    return null;
  }

  global.EngEtymDb = {
    clusters: ENG_SYNONYM_CLUSTERS,
    findByWord: findEngClusterByWord
  };

})(typeof window !== 'undefined' ? window : this);
