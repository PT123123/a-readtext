# 朗读文本（ReadText）

一个基于 Android + sherpa-onnx 的**离线文本转语音（TTS / 文本转录音）**应用。

> 当前状态：**脚手架阶段**。界面与模型管理沿用自 [aphone-s2t](https://github.com/k2-fsa/sherpa-onnx)（语音转写应用）的 UI，内置模型目录已替换为 sherpa-onnx 官方 **TTS 模型**；真正的合成引擎（把文本合成为音频）待后续实现。

## 计划功能

- ⌨️ **文本输入 → 语音合成**: 输入文本，离线合成 WAV 音频
- 📱 **完全离线**: 模型下载后无需网络即可合成
- 🧠 **多模型管理**: 支持 VITS / Matcha / Melo / Kokoro / Zipvoice / SuperTonic / Pocket 等 TTS 模型，自动下载、断点续传、SHA-256 校验
- 🔊 **播放 / 保存**: 合成音频可播放、保存、分享
- 🛠️ **自定义模型**: 支持添加自定义 TTS 模型 URL

## 已内置的 TTS 模型（sherpa-onnx tts-models 发布）

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

模型下载地址规则：`https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/<id>.tar.bz2`
（见 `app/src/main/java/com/example/areadtext/model/ModelCatalog.kt`）

## 技术架构

- 骨架：沿用 aphone-s2t 的界面 / 模型管理 / Room 历史记录结构
- TTS 引擎：sherpa-onnx `SherpaOnnxOfflineTts`（vits / matcha / melo / kokoro / zipvoice / supertonic / pocket）
- 模型下载：WorkManager + HTTP Range 断点续传 + 原子化安装
- sherpa-onnx 版本：v1.13.7（Jitpack，`com.github.k2-fsa:sherpa-onnx`）

## 项目结构

```
app/src/main/java/com/example/areadtext/
├── MainActivity.kt                 # 主界面（待改为文本输入 + 合成）
├── TranscriptionService.kt         # （ASR 遗留，待替换为合成服务）
├── ModelManagerActivity.kt         # 模型管理界面
├── HistoryActivity.kt              # 历史记录界面
├── asr/                            # （ASR 遗留，待替换为 TTS 引擎封装）
├── model/
│   ├── ModelCatalog.kt             # TTS 模型目录（已替换）
│   ├── ModelManager.kt             # 模型管理器
│   └── ...
├── data/                           # Room 历史记录（TranscriptEntity 等）
└── utils/
```

## 构建和运行

### 环境要求
- Android Studio Hedgehog (2023.1.1) 或更高
- JDK 17、Android SDK 34、最低 API 24
- `local.properties` 配置 `sdk.dir`（未纳入版本控制，本地构建需要）

### 使用 Just（推荐）
```bash
just build        # 构建 debug APK
just install      # 安装到设备
just run
just help         # 查看所有命令
```

### 手动构建
```bash
./gradlew assembleDebug
```

## 后续待办（TTS 实现）

- [ ] 用 `SherpaOnnxOfflineTts` 封装替换 `asr/SherpaStreamingAsr.kt`（含 `SherpaOnnxOfflineTtsConfig` 按模型类型 vits/matcha/kokoro/zipvoice/supertonic/pocket 构建）
- [ ] 主界面改为「文本输入 → 合成 → 播放/保存」
- [ ] `ModelManager.getActiveModelDirectory` 中的 ASR 校验替换为 TTS 加载校验
- [ ] 合成音频保存为 WAV，接入历史记录

## 许可证

Apache-2.0 License

## 致谢

- sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx
- 界面与工程骨架来源于 aphone-s2t（本仓库父目录）
