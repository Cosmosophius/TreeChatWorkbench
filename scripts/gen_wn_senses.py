# -*- coding: utf-8 -*-
"""由 WordNet 3.0 原始 data.* 生成「按义项(synset)分组」的同义词资产。

输出列序：word \t pos \t gloss \t member1,member2,...
- pos      : n/v/a/r（WordNet ss_type，卫星形容词 s 归入 a）
- gloss    : synset 释义（只取 | 后第一分句，去引号，限 56 字符）
- members  : 同一 synset 内、且**在本 App 词表内**的其他成员词
只保留「至少还有一个词表成员」的 synset —— 否则这一组没有同义词可显示。
按组内词表成员数由多到少排序，每词最多 8 个义项、每组最多 10 个成员。
"""
import os, sys

ASSETS = '/Users/deepseekworkspace/TreeChatWorkbench/app/src/main/assets'
WN = '/tmp/dict'
POS_CN = {'n': 'n', 'v': 'v', 'a': 'a', 'r': 'r'}
SSMAP = {'n': 'n', 'v': 'v', 'a': 'a', 's': 'a', 'r': 'r'}
MAX_SENSES = 8
MAX_MEMBERS = 10
MAX_GLOSS = 56

# ---------- 1. 词表 ----------
vocab = set()
for fn in ('ecdict.core.tsv', 'vocab2.tsv'):
    with open(os.path.join(ASSETS, fn), encoding='utf-8') as f:
        for line in f:
            w = line.split('\t', 1)[0].strip().lower()
            if w and w.isascii() and ' ' not in w:
                vocab.add(w)
print('词表词数 =', len(vocab))

# ---------- 2. 解析 WordNet data.* ----------
# 每行一个 synset：offset lex_filenum ss_type w_cnt(hex) [word lex_id]* p_cnt [ptr]* | gloss
from collections import defaultdict
senses_of = defaultdict(list)   # word -> [(member_count, offset, pos, gloss, members)]

for fname, fallback_pos in (('data.noun', 'n'), ('data.verb', 'v'),
                            ('data.adj', 'a'), ('data.adv', 'r')):
    path = os.path.join(WN, fname)
    if not os.path.exists(path):
        sys.exit('missing ' + path)
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            bar = line.find('|')
            if bar < 0:
                continue
            head, gloss = line[:bar].strip(), line[bar + 1:].strip()
            toks = head.split()
            if len(toks) < 4:
                continue
            ss_type = toks[2]
            pos = SSMAP.get(ss_type, fallback_pos)
            try:
                w_cnt = int(toks[3], 16)
            except ValueError:
                continue
            # 词表部分：toks[4 .. 4+2*w_cnt-1]，每两个一组 (word, lex_id)
            if len(toks) < 4 + 2 * w_cnt:
                continue
            members = []
            for i in range(w_cnt):
                w = toks[4 + 2 * i].lower()
                if w in vocab and w not in members:
                    members.append(w)
            if len(members) < 2:      # 至少要有「本词 + 1 个词表同义词」
                continue
            # 义项释义：取第一分句、去引号、限长
            g = gloss.split(';')[0].strip().strip('"').strip()
            g = ' '.join(g.split())
            if len(g) > MAX_GLOSS:
                g = g[:MAX_GLOSS - 1].rstrip() + '…'
            offset = toks[0]
            for w in members:
                senses_of[w].append((len(members), offset, pos, g, members))

print('有义项分组的词数 =', len(senses_of))

# ---------- 3. 排序 + 截断 + 落盘 ----------
total_rows = 0
out_path = os.path.join(ASSETS, 'wordnet_senses.tsv')
with open(out_path, 'w', encoding='utf-8') as out:
    for word, lst in senses_of.items():
        # 组内词表成员越多越靠前（最常用义项在前），同分按 offset 稳定排序
        lst.sort(key=lambda x: (-x[0], x[1]))
        seen_gloss = set()
        rows = []
        for _, _, pos, gloss, members in lst:
            if gloss in seen_gloss:      # 同义 gloss 去重
                continue
            seen_gloss.add(gloss)
            mem = [m for m in members if m != word][:MAX_MEMBERS]
            if not mem:
                continue
            rows.append('%s\t%s\t%s\t%s' % (word, pos, gloss, ','.join(mem)))
            if len(rows) >= MAX_SENSES:
                break
        for r in rows:
            out.write(r + '\n')
            total_rows += 1

size = os.path.getsize(out_path)
print('行数 =', total_rows, ' 大小 = %.2f MB' % (size / 1048576))
# 抽查
for w in ('light', 'fire', 'run'):
    print('---', w)
    with open(out_path, encoding='utf-8') as f:
        for line in f:
            if line.startswith(w + '\t'):
                print('   ', line.rstrip()[:150])
