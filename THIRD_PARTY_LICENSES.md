# Third-Party Notices and Open Source Licenses / 第三方开源许可与致谢

This document records the third-party open-source datasets, models, code libraries, and graphical assets used in TreeChatWorkbench (TalkTree), along with their upstream sources and licensing terms.

本项目（条畅 / TreeChatWorkbench）包含并使用了以下第三方开源数据集、预训练模型、代码库与图形资产。为遵循开源协议，特此致敬并声明许可如下：

---

## 1. Dictionaries and Lexical Data / 词典与语言资产

### 1.1 ECDICT (Free English-Chinese Dictionary Database)
- **Author / Originator**: skywind3000
- **Repository**: <https://github.com/skywind3000/ECDICT>
- **License**: MIT License
- **Included Assets**: `app/src/main/assets/ecdict.core.tsv`
- **Notice**:
  > Copyright (c) 2017 Lin Wei (skywind3000)
  > Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
  > The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

---

### 1.2 Princeton WordNet 3.0
- **Author / Originator**: Princeton University
- **Website**: <https://wordnet.princeton.edu/>
- **License**: WordNet 3.0 License (BSD-style)
- **Included Assets**: `app/src/main/assets/wordnet_senses.tsv`
- **Notice**:
  > WordNet Release 3.0 This software and database is being provided to you, the LICENSEE, by Princeton University under the following license. By obtaining, using and/or copying this software and database, you agree that you have read, understood, and will comply with these terms and conditions.:
  > Permission to use, copy, modify and distribute this software and database and its documentation for any purpose and without fee or royalty is hereby granted, provided that you agree to comply with the following copyright notice and statements, including the disclaimer, and that the same appear on ALL copies of the software, database and documentation, including modifications that you make for internal use or for distribution.
  > WordNet 3.0 Copyright 2006 by Princeton University. All rights reserved.

---

### 1.3 Tshet-Uinh Data & Examples (中古汉语音韵与切韵广韵推导)
- **Author / Originator**: nk2028
- **Repositories**:
  - Data: <https://github.com/nk2028/tshet-uinh-data> (CC0-1.0 Universal)
  - Examples / Derivations: <https://github.com/nk2028/tshet-uinh-examples> (MIT License)
- **Included Assets**: `app/src/main/assets/han_mc_guangyun.tsv` (derived using Karlgren, Wang Li, Pan Wuyun, Unt, and Msoeg phonological estimators)
- **License Details**:
  - The underlying Guangyun rhyme book data is dedicated to the public domain under Creative Commons Zero v1.0 Universal.
  - The algorithmic derivation scripts are licensed under the MIT License by the nk2028 contributors.

---

### 1.4 Historical Chinese Phonology Datasets (上古汉语音韵学构拟)
- **Source Project**: `direct-phonology/rephon` (<https://github.com/direct-phonology/rephon>), licensed under MIT License.
- **Included Assets**: `app/src/main/assets/han_oc_scholars.tsv`, `app/src/main/assets/han_phonology.tsv`
- **Component Sub-sources & Licenses**:
  - **Baxter-Sagart (2015)**: Baxter, William H. and Laurent Sagart. 2014. *Old Chinese: A New Reconstruction*. Oxford University Press.
  - **Axel Schuessler (2009/2024)**: *Some Old Chinese and Han Chinese Reconstructions*, Zenodo, DOI: [10.5281/zenodo.14567004](https://doi.org/10.5281/zenodo.14567004). Released under **CC0-1.0 Universal**.
  - **Wang Li & Li Fang-Kuei Systems**: Phonological reconstruction tables referenced for educational and linguistic research purposes.

---

### 1.5 Etymological WordNet (EtymWN)
- **Author / Originator**: Gerard de Melo
- **Website / Reference**: Gerard de Melo (2014). *Etymological Wordnet: Tracing the History of Words*. LREC 2014.
- **License**: Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)
- **Included Assets**: `app/src/main/assets/etym_relations.tsv`
- **Notice**:
  > This dataset is based on derivations extracted from Wiktionary data and is licensed under CC BY-SA 4.0. To view a copy of this license, visit <https://creativecommons.org/licenses/by-sa/4.0/>.

---

### 1.6 Russian Phonetics and Lexical Data
- **Sources**: Open Russian lexical datasets, Wiktionary phonetic transcriptions, and community linguistic resources.
- **Included Assets**: `app/src/main/assets/ru_ipa.tsv`, `app/src/main/assets/han_ru.tsv`, `app/src/main/assets/vocab_ru.tsv`
- **License**: Creative Commons Attribution-ShareAlike (CC BY-SA 3.0 / 4.0).

---

## 2. Machine Learning Models / 机器学习与 OCR 模型

### 2.1 PaddleOCR PP-OCRv4 (Baidu)
- **Author / Originator**: Baidu PaddlePaddle Authors
- **Repository**: <https://github.com/PaddlePaddle/PaddleOCR>
- **License**: Apache License, Version 2.0
- **Included Assets**:
  - `desktop/app-resources/paddleocr/ch_PP-OCRv4_det_infer.onnx`
  - `desktop/app-resources/paddleocr/ch_PP-OCRv4_rec_infer.onnx`
- **Notice**:
  > Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at: <http://www.apache.org/licenses/LICENSE-2.0>

---

## 3. Graphical Icons / 矢量图形与图标

### 3.1 IconPark (ByteDance)
- **Author / Originator**: ByteDance Inc.
- **Repository**: <https://github.com/bytedance/IconPark>
- **License**: Apache License, Version 2.0
- **Included Assets**: Vector drawables in `app/src/main/res/drawable/` prefixed with `ic_ip_*.xml` (e.g., `ic_ip_bookshelf.xml`, `ic_ip_connection_box.xml`, etc.).

### 3.2 Zico Icons (Zoomla)
- **Author / Originator**: Zoomla / zico.tzfont.com
- **Repository**: <https://github.com/zoomla/zico>
- **License**: MIT License
- **Included Assets**: Vector drawables in `app/src/main/res/drawable/` prefixed with `ic_zi_*.xml` (e.g., `ic_zi_forbid.xml`, `ic_zi_read.xml`).

---

## 4. Public Domain Literary Works / 公有领域样本

### 4.1 Alice's Adventures in Wonderland
- **Author**: Lewis Carroll (Charles Lutwidge Dodgson, 1832–1898)
- **Included Assets**: `app/src/main/assets/books/alice-in-wonderland.epub`
- **License Status**: Public Domain worldwide. The copyright for this work has expired in all jurisdictions with a copyright term of life of the author plus 70 years or fewer.

---

## 5. Summary and Disclaimer

All third-party trademarks, service marks, trade names, and copyrights are the property of their respective owners. The inclusion of these third-party works does not imply endorsement by the original authors.

---

## 5. Cultural Heritage, Nomenclature & Original Design / 文化出处、命名渊源与原创设计

### 5.1 素扉仿古宣纸卡片与题签设计 (SuFei Ancient Xuan Paper Card & Seal Design)
- **Design Component**: `app/src/main/java/com/treechat/workbench/ui/settings/SettingsDialog.kt` (`SuFeiClassicsCard`, `TreeChatSealCard`)
- **Cultural Source / 命名与文化出处**: 
  - 《诗经·大雅·旱麓》：“瞻彼旱麓，榛楛济济。岂弟君子，干禄岂弟。瑟彼玉瓒，黄流在中。岂弟君子，福禄攸降。鸢飞戾天，鱼跃于渊。岂弟君子，遐不作人。清酒既载，骍牡既备。以享以祀，以介景福。瑟彼柞棫，民所燎矣。岂弟君子，神所劳矣。莫莫葛藟，施于条枚。岂弟君子，求福不回。”
  - 本项目命名**「条畅」**（TalkTree）即取意于“榛楛济济，条枚畅畅”，寓意枝条舒畅通达、本固枝荣、思绪生发如林木葱郁。
- **Visual Design Identity / 视觉设计与版权说明**:
  - 素扉风格采用仿古籍书扉版式、温润熟宣纸色（`#F6F3EB`）、玄墨底色（`#1B201F`）、金石衬线题签与朱砂印泥（`#B33928`「大雅」朱印）设计语言。
  - 本设计与题签代码为条畅项目原创文化界面组件，供开源社区在遵循本项目整体许可前提下研读、研习与使用。
