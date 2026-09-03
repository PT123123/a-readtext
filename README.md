# 朗读文本（ReadText / a-readtext）

基于 Android + sherpa-onnx 的**离线 EPUB 朗读器**：导入本地 EPUB，以 legado 风格的阅读界面逐句朗读，
**完全绕开系统 TTS 引擎**，用 sherpa-onnx 在设备本地合成语音（模型下载后全程离线）。

> 本项目的核心定位：**本地 EPUB 是内容源，离线合成是声音源**——不走 Android 系统 `TextToSpeech`
> 接口，而是把 TTS 引擎做成自己的第一公民（参考 HandyReader 的离线三引擎架构中离线路径）。

## 功能

- 📚 **书架**：导入本地 EPUB → 解析 → 缓存 → 书籍列表（长按删除）
- 📖 **阅读器**：legado 风格正文流，字号/行距/纸白/米黄/夜间主题
- 🔊 **逐句离线朗读**：sherpa-onnx 本机合成（VITS / Matcha / Melo / Kokoro / Zipvoice / SuperTonic / Pocket）
- 🧲 **逐句高亮 + 点句跳读**：当前朗读句高亮，点任意句子从此句开读
- 📡 **段/句/章导航 + 前台朗读**：退出应用、锁屏后通知栏继续控制（上一段/下一段/播放暂停/停止）
- ⏱️ **断点续读**：进度精确到句，暂停在句中恢复也不丢位置；按 (章节, 段, 句) 持久化
- ⚡ **语速调节**：0.75× ~ 2×（sherpa 合成期原生变速）
- 🛠️ **多模型管理**：自动下载、断点续传、SHA-256 校验、自定义模型 URL

## 内置 TTS 模型（sherpa-onnx tts-models）

| 模型 | 类型 | 语言 | 下载大小 |
| --- | --- | --- | --- |
| sherpa-onnx-zipvoice-zh-en-emilia | Zipvoice | 中英 | ~605 MB |
| sherpa-onnx-zipvoice-distill-int8-zh-en-emilia | Zipvoice 蒸馏 | 中英 | ~104 MB |
| sherpa-onnx-supertonic-3-tts-int8-2026-05-11 | SuperTonic-3 | 中英 | ~123 MB |
| sherpa-onnx-pocket-tts-int8-2026-01-26 | Pocket TTS | 中英 | ~94 MB |
| kokoro-int8-multi-lang-v1_1 | Kokoro | 多语言 | ~140 MB |
| matcha-icefall-zh-en | Matcha-TTS | 中英 | ~75 MB |
| vits-melo-tts-zh_en | Melo TTS | 中英 | ~159 MB |
| sherpa-onnx-vits-zh-ll | VITS | 中文 | ~113 MB |
| vits-zh-aishell3 | VITS | 中文 (174 音色) | ~140 MB |
| vits-cantonese-hf-xiaomaiiwn | VITS | 粤语 | ~103 MB |
| vits-piper-en_US-amy-low | VITS Piper | 英文 | ~64 MB |

下载地址规则：`https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/<id>.tar.bz2`
（见 `model/ModelCatalog.kt`）

## 架构：四套参考在 a-readtext 的落点

| 参考项目 | 借鉴点 | 落地位置 |
| --- | --- | --- |
| **HandyReader**（离线三引擎） | 绕开系统引擎，用 sherpa-onnx 本机合成；后台播放 + 通知 | `reader/OfflineTtsEngine.kt` 模型族自动探测（vits/matcha/kokoro/zipvoice/pocket/supertonic） |
| **MoRealm「墨境」**（段落级调度） | `TtsEngineHost` 单一事实来源 + 命令/状态总线 + 前台 MediaStyle 服务 + 断点续读 | `reader/TtsReadAloudEngine.kt` + `reader/TtsBus.kt` + `service/TtsReadAloudService.kt` |
| **FolioReader**（Media Overlays） | SMIL 式"文本区间 ↔ 音频时间"时间轴，播放位置驱动高亮推进 | `reader/SyncTimeline.kt`（运行时虚拟 SMIL，逐句累加实际合成时长） |
| **Lector**（逐句高亮交互） | 当前句高亮、上/下句、点句跳读、`onEnd` 自动推进、(章,句) 进度 | `ui/ReaderActivity.kt` + `ui/ParagraphAdapter.kt` |

### 朗读流水线

```
EPUB → EpubParser（container.xml→OPF→spine→XHTML 清洗）
     → TextSegmenter（段/句 + 偏移，单一坐标系供渲染与朗读共享）
     → TtsReadAloudEngine.speakLoop（逐句 OfflineTtsEngine.synthesize → AudioTrack）
     → SyncTimeline.add(文本区间, 实际音频时长)   ← FolioReader 式虚拟 SMIL
     → publishPosition(paragraphIndex, sentenceIndex, 文本区间)
     → UI 高亮当前句 + 点句 JumpTo(para, sent)
     → (章节,段,句) 写 Room，服务内常驻循环
```

### 模块结构

```
app/src/main/java/com/example/areadtext/
├── ShelfActivity.kt               # 书架（启动页）：导入/打开/删除
├── MainActivity.kt                # ASR 遗留界面（保留，不再作为入口）
├── ModelManagerActivity.kt        # 模型管理（下载/启用/自定义）
├── ui/
│   ├── ReaderActivity.kt          # 阅读器：正文流 + 朗读控制条 + 点句跳读
│   └── ParagraphAdapter.kt        # 逐句 ClickableSpan 高亮 + 主题配色
├── reader/
│   ├── EpubModels.kt              # Book/Chapter/Paragraph/Sentence
│   ├── TextSegmenter.kt           # 段/句切分（保留偏移）
│   ├── EpubParser.kt              # EPUB 解析 + JSON 缓存
│   ├── OfflineTtsEngine.kt        # sherpa-onnx 离线引擎封装（模型族探测）
│   ├── SyncTimeline.kt            # FolioReader 式文本↔音频时间轴
│   ├── TtsBus.kt                  # 命令 SharedFlow + 状态 StateFlow
│   ├── TtsReadAloudEngine.kt      # 段落/逐句调度主机（MoRealm + Lector）
│   └── ReaderPreferences.kt       # 字号/行距/主题/语速
├── service/TtsReadAloudService.kt # 前台朗读服务 + MediaStyle 通知 + 音频焦点
├── data/                          # Room：books / reading_progress / transcripts
├── model/                         # 模型目录、下载、校验（getActiveModelDirectory 已切 TTS）
└── asr/                           # ASR 遗留代码（保留未删）
```

## 构建和运行

环境：JDK 17、Android SDK 34、最低 API 24；`local.properties` 配置 `sdk.dir`。

```bash
./gradlew assembleDebug            # 手动构建
just build / just install          # 或使用 justfile
```

## 待办

- [x] `OfflineTtsEngine`（sherpa-onnx 离线合成，绕开系统引擎）
- [x] `ModelManager.getActiveModelDirectory` 校验切到 TTS 原生加载测试
- [x] EPUB 解析 + 书架 + 阅读器（逐句高亮 / 点句跳读）
- [x] 段落级调度 + 前台朗读服务（通知栏控制 / 音频焦点 / 唤醒锁）
- [x] FolioReader 式同步时间轴（音频时间 → 文本高亮）
- [x] 断点续读（(章,段,句) 持久化 + 句中续读）
- [ ] 章节列表弹层 / 翻页动画 / 更多 legado 阅读体验
- [ ] 合成音频预取与句间无缝衔接（需解决 sherpa 实例并发限制）
- [ ] 封面图提取显示

## 许可证 / 致谢

Apache-2.0 License
- sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx
- 参考：HandyReader / MoRealm「墨境」/ FolioReader-Android / Lector
- 工程骨架源于 aphone-s2t（本仓库父目录）
