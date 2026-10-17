/**
 * etym_rus_database.js — 俄语标准 IPA 国际音标、全谱系同义词矩阵（含古典生僻词）与词源数据库
 * 
 * 音标标准：
 * - 当代标准俄语 (Contemporary Standard Russian / CSR) 精准严式 IPA
 * - 标注：元音弱化（o/a → [ɐ], [ə]; e/я → [ɪ]）、软辅音颚化 (ʲ)、长辅音双拼 (ː)、清浊同化与词尾清化。
 * 
 * 词源谱系分类：
 * - Proto-Slavic (原始斯拉夫语本土古根词)
 * - Old Church Slavonic (古教会斯拉夫语雅言/宗教文学典范借词)
 * - Ancient Greek Borrowing (拜占庭/古希腊正教会经典借词)
 * - Latin / Romance Borrowing (拉丁语及西欧近代学术文化借词)
 */
(function (global) {
  'use strict';

  var RUS_SYNONYM_CLUSTERS = {
    // ==========================================
    // 概念簇 1：神圣 / 圣洁 / 庄严 (СВЯТОЙ / САМИ)
    // ==========================================
    'святой': {
      concept: '神圣 / 圣洁 / 庄严超凡',
      concept_zh: '神圣',
      words: [
        {
          word: 'святой',
          translit: 'svyatóy',
          ipa: '[svʲɪˈtoj]',
          origin: 'Proto-Slavic (原始斯拉夫语)',
          evolution: 'PIE *ḱwen- (神圣/充盈) → Proto-Slavic *svętъ → 东斯拉夫语 святой',
          register: '斯拉夫本土最高神圣核心词',
          rarity: '常用 (星级 ★★★★★)'
        },
        {
          word: 'священный',
          translit: 'svyashchénnyy',
          ipa: '[svʲɪˈɕːenːɨj]',
          origin: 'Old Church Slavonic (古教会斯拉夫语借词)',
          evolution: '源自教会斯拉夫语圣经翻译；带 -щ- (来自原始斯拉夫语 *tj 发展成南斯拉夫特征音 щ)，指礼拜祭祀与神圣不可侵犯之物（如 Священная война 神圣的战争）',
          register: '庄严崇高文体 / 誓言与宗教典仪',
          rarity: '核心高频 (星级 ★★★★☆)'
        },
        {
          word: 'сакральный',
          translit: 'sakrál’nyy',
          ipa: '[sɐˈkralʲnɨj]',
          origin: 'Latin borrowing (拉丁语借词)',
          evolution: '借自西欧拉丁语 sacralis ← sacer (神圣的)；现代进入俄语高阶学术与哲学批评体系',
          register: '宗教学术与哲学理论专用',
          rarity: '进阶书面 (星级 ★★★☆☆)'
        },
        {
          word: 'праведный',
          translit: 'právednyy',
          ipa: '[ˈpravʲɪdnɨj]',
          origin: 'Proto-Slavic (原始斯拉夫语)',
          evolution: 'Proto-Slavic *pravьdьnъ ← *pravъ (正直、真实、合法) = 义人圣行与纯全',
          register: '道德神圣与宗教纯洁',
          rarity: '高阶文学 (星级 ★★★☆☆)'
        },
        {
          word: 'пречистый',
          translit: 'prechístyy',
          ipa: '[prʲɪˈtɕistɨj]',
          origin: 'Old Church Slavonic (古教会斯拉夫语)',
          evolution: 'OCS 最高级尊显前缀 пре- (至极) + чистый (纯洁) = 至圣至洁无染者（常颂赞圣母）',
          register: '教会古典赞歌与古典诗歌典雅语汇',
          rarity: '极生僻典雅文学词 (星级 ★★☆☆☆)'
        },
        {
          word: 'благочестивый',
          translit: 'blagochestívyy',
          ipa: '[bləɡətɕɪsʲˈtʲivɨj]',
          origin: 'Old Church Slavonic (古教会斯拉夫语复合词)',
          evolution: '意译自古希腊语 εὐσεβής (eusebēs): благо (善好) + честь (尊荣/敬畏) = 虔敬纯洁',
          register: '传统东正教历史与古典叙事',
          rarity: '高阶古雅词 (星级 ★★☆☆☆)'
        },
        {
          word: 'иератический',
          translit: 'ieratícheskiy',
          ipa: '[ɪ(j)ɪrɐˈtʲitɕɪskʲɪj]',
          origin: 'Ancient Greek via Academic Russian (古希腊借词)',
          evolution: '借自古希腊语 ἱερατικός (hieratikós, 祭司神圣的)，指神秘严整的古代宗教艺术风貌',
          register: '极限艺术史与宗教学术词汇',
          rarity: '极生僻学术罕词 (星级 ★☆☆☆☆)'
        }
      ]
    },

    // ==========================================
    // 概念簇 2：光明 / 照耀 / 璀璨 (СВЕТ / ЛУЧ)
    // ==========================================
    'свет': {
      concept: '光明 / 辉映 / 灿烂照耀',
      concept_zh: '光',
      words: [
        {
          word: 'свет',
          translit: 'svet',
          ipa: '[svʲet]',
          origin: 'Proto-Slavic (原始斯拉夫语)',
          evolution: 'PIE *ḱweyt- (闪烁/白色) → Proto-Slavic *světъ → 俄语 свет (兼具光线与人间世界双重含义)',
          register: '斯拉夫语根本基石词',
          rarity: '常用 (星级 ★★★★★)'
        },
        {
          word: 'светлый',
          translit: 'svétlyy',
          ipa: '[ˈsvʲetlɨj]',
          origin: 'Proto-Slavic (原始斯拉夫语)',
          evolution: 'Proto-Slavic *světьlъ → 衍生出明澈、光明纯良之意',
          register: '通用描写 / 心性明澈',
          rarity: '常用 (星级 ★★★★★)'
        },
        {
          word: 'лучезарный',
          translit: 'luchezárnyy',
          ipa: '[lʊtɕɪˈzarnɨj]',
          origin: 'Old Church Slavonic (古教会斯拉夫语复合词)',
          evolution: 'луч (光束) + заря (晨曦霞光) = 普照万方的夺目光华；普希金诗歌高频贵族修辞',
          register: '高贵抒情诗与华美文学',
          rarity: '进阶雅词 (星级 ★★★☆☆)'
        },
        {
          word: 'сияющий',
          translit: 'siyáyushchiy',
          ipa: '[sʲɪˈjæjʉɕːɪj]',
          origin: 'Proto-Slavic (原始斯拉夫语形动词)',
          evolution: 'Proto-Slavic *sьjati (发光/微笑) 的现在时主动形动词',
          register: '生动光彩照人',
          rarity: '常用中高频 (星级 ★★★★☆)'
        },
        {
          word: 'светозарный',
          translit: 'svetozárnyy',
          ipa: '[svʲɪtɐˈzarnɨj]',
          origin: 'Old Church Slavonic (古教会斯拉夫语)',
          evolution: 'свет (光) + о + заря (破晓红光)；古代编年史与东正教礼仪极高尊誉',
          register: '极限古典史诗与古代颂词',
          rarity: '极生僻史诗词 (星级 ★☆☆☆☆)'
        },
        {
          word: 'лучистый',
          translit: 'luchístyy',
          ipa: '[lʊˈtɕistɨj]',
          origin: 'Proto-Slavic (原始斯拉夫语)',
          evolution: 'луч + 丰盈后缀 -ист- = 放射状神采飞扬的光芒',
          register: '温煦明澈的目光与光芒',
          rarity: '高频纯正词 (星级 ★★★★☆)'
        }
      ]
    },

    // ==========================================
    // 概念簇 3：智慧 / 睿智 / 哲思 (МУДРОСТЬ)
    // ==========================================
    'мудрость': {
      concept: '智慧 / 深邃明达 / 睿识',
      concept_zh: '智',
      words: [
        {
          word: 'мудрый',
          translit: 'múdryy',
          ipa: '[ˈmudrɨj]',
          origin: 'Proto-Slavic (原始斯拉夫语)',
          evolution: 'PIE *men- (心智/思考) / *mondʰ- → Proto-Slavic *mǫdrъ (贤明深思的)',
          register: '斯拉夫本土至高长者智慧',
          rarity: '常用核心 (星级 ★★★★★)'
        },
        {
          word: 'премудрый',
          translit: 'premúdryy',
          ipa: '[prʲɪˈmudrɨj]',
          origin: 'Old Church Slavonic (古教会斯拉夫语)',
          evolution: 'пре- (极度超越) + мудрый = 索菲亚神圣至圣智慧 (Премудрость Божия)',
          register: '神学古典典故 / 童话哲理极尊称',
          rarity: '进阶古雅生僻词 (星级 ★★☆☆☆)'
        },
        {
          word: 'разумный',
          translit: 'razúmnyy',
          ipa: '[rɐˈzumnɨj]',
          origin: 'Proto-Slavic (原始斯拉夫语)',
          evolution: 'раз- (区分/分清) + ум (理智) = 具备辨识理智与分寸的智慧',
          register: '理性常识与哲学明智',
          rarity: '常用 (星级 ★★★★★)'
        },
        {
          word: 'эрудированный',
          translit: 'erudírovannyy',
          ipa: '[ɛrʊˈdʲirəvənːɨj]',
          origin: 'Latin borrowing via French (拉丁语借词)',
          evolution: '借自法语 érudit ← 拉丁语 eruditus (剥去蒙昧的博学之人)',
          register: '现代学者学富五车之博雅',
          rarity: '高阶书面 (星级 ★★★☆☆)'
        },
        {
          word: 'многознающий',
          translit: 'mnogoznáyushchiy',
          ipa: '[mnəɡɐˈznajʉɕːɪj]',
          origin: 'Proto-Slavic (斯拉夫本土复合词)',
          evolution: 'много (多) + знающий (通晓知晓的) = 斯拉夫语博古通今的通儒',
          register: '传统民间与古典叙事纯正词',
          rarity: '进阶高雅词 (星级 ★★☆☆☆)'
        }
      ]
    }
  };

  /**
   * 按俄语单词反查所属同义词簇及词源
   */
  function findRusClusterByWord(word) {
    if (!word) return null;
    var w = word.toLowerCase().trim();
    for (var key in RUS_SYNONYM_CLUSTERS) {
      var cluster = RUS_SYNONYM_CLUSTERS[key];
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

  global.RusEtymDb = {
    clusters: RUS_SYNONYM_CLUSTERS,
    findByWord: findRusClusterByWord
  };

})(typeof window !== 'undefined' ? window : this);
