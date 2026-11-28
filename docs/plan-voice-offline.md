# 语音输入改成「内置离线识别」：方案与实测数字

> 2026-11-28。起因：真机 vivo V2405A 上系统语音识别走不通（5 个包没定论），
> 大肥鱼提出更根本的问题 —— **“装到别的品牌上是不是每个型号都得单独适配”**。
> 结论：代码层面不用适配（只有一个 `SpeechRecognizer` API），但**行为按厂商分化**，
> 交付质量不可控。本页是改方向的方案，**未动代码**。

## 一、为什么放弃“用系统识别”

| 机型情况 | 我们这边看到的现象 |
|---|---|
| 有机内（离线）模型 | `isOnDeviceRecognitionAvailable=true`，可用，但不一定准 |
| 只有云端 | 断网/服务端故障时**只沉默**（本机：25 秒零回调） |
| 服务声明了签名级权限 | 第三方结构上用不了（本机待证实：`声明=` 那格） |
| 没有识别服务 | 直接不可用 |

同一份代码，在这些机器上是四种产品体验；而且**本机编不了 `:app`、没有模拟器**，
每台机器都只能靠真机日志一轮轮试 —— 2026-11-27/28 语音这一条已经吃掉 5 个包。
这不符合“交付可控”的要求。

**顺带记录本机实测到的全部事实**（详见 [M35](dev/verification/2026-11-27-M35.md)）：

- 麦克风这条线**是通的**：`AudioRecord` 自检峰值 136–362（满量程 32768）；
- `startListening` 单发 → 25 秒零回调；补发第 2 发 → 104ms 后 `客户端错误`
  （AOSP 原话 `concurrent startListening received`，是**我们**打的）；
- 唯一一次引擎回话是 v186 的 `ERROR_SERVER_DISCONNECTED`（开始听 15.7 秒后）；
- 引擎 `com.vivo.ai.copilot` 是 vivo 的**系统 AI 助手本体**（蓝心小V），视障社区有记录
  它的语音接口曾整体故障、之后恢复 —— 即“对第三方开放，但云端会挂且只沉默”。

## 二、三个选项

| | 做法 | 一致性 | 体积 | 质量 | 服务器 |
|---|---|---|---|---|---|
| A | 继续用系统识别 | ❌ 按机型分化 | 0 | 有机内模型时最好 | 无（但依赖厂商云） |
| **B** | **内置离线引擎（sherpa-onnx）** | ✅ 每台机器一样 | **+≈35MB** | 中（小模型，无标点） | 无 |
| C | 云端 ASR API（自己的 key） | ✅ | 小 | 好 | 需要（违反“无服务端”的取向） |

选 **B**：唯一同时满足“每台机器一致 + 不联网 + 不吃第三方状态”的。

## 三、实测数字（2026-11-28，都是拉真实文件量出来的）

引擎库（[sherpa-onnx v2.0.3 release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.3)）：

| 文件 | 体积 |
|---|---|
| `sherpa-onnx-2.0.3.aar`（含全部 ABI + 动态 onnxruntime） | **50.0 MB** |
| `sherpa-onnx-2.0.3-static-link-onnxruntime.aar` | 38.1 MB |
| 单 ABI（arm64-v8a）切片 | ≈ 12 MB（**估算**：AAR 里 4 个 ABI 均摊，待实测） |

模型（[asr-models release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)，tar.bz2 体积）：

| 模型 | tar.bz2 | 说明 |
|---|---|---|
| `streaming-zipformer-small-ctc-zh-int8-2025-04-01` | **20.3 MB** | 流式、中文、int8，**首选** |
| `streaming-zipformer-zh-14M-2023-02-23-mobile` | 51.8 MB | 老一点的 14M 流式 |
| `streaming-zipformer-multi-zh-hans-int8-2023-12-13` | 59.2 MB | 多口音普通话 |
| `streaming-zipformer-zh-int8-2025-06-30` | 126.5 MB | 更大更准 |
| `paraformer-zh-small-2024-03-09` | 74.3 MB | **非流式**（说完才出字） |

**P0 组合估算**：arm64 引擎 ≈12MB + 20MB 模型 + 少量胶水 ≈ **APK 从 15MB 涨到 ~48MB**。
（增长大头是 native 库与模型，压缩率低，别指望 zip 再省多少。）

许可：sherpa-onnx **Apache-2.0**（已核）；onnxruntime MIT（待核）；**模型许可要逐个核**
（WenetSpeech/aishell 系多带 CC-BY 或 Apache 条款），核完写进 `assets/notices/`。

## 四、架构（最小改动面）

```
ChatScreen ⋮「语音输入」
  └─ OfflineAsr（新，app/voice/）
       ├─ AudioRecord 16k/mono/PCM16  ← 已有 RECORD_AUDIO
       ├─ sherpa OnlineRecognizer（流式 zipformer）→ 部分结果
       └─ 回调 partial/final → viewModel.onInputChange，与现在完全同一条路
```

- 现在的 `VoiceInput` / `VoiceListen`（系统 `SpeechRecognizer` 那一套）**整块删掉**，
  连 Manifest 里的 `<queries>` 两条也删；`RECORD_AUDIO` 保留（自己要录）。
- 模型放 `assets/`，首次使用复制到 `filesDir/models/`（约 20MB，一次几十毫秒级拷贝）；
  或者 sherpa 直接读 asset（待确认 API）。
- **模型不进 git**：CI 构建时从 sherpa 的 release 下载 + 校验 sha256（钉死 URL 与哈希），
  再打进 APK。仓库体积不变，APK 自包含。
- 停止条件：P0 手动停（再点一次）+ 30 秒上限；P1 再加 VAD（sherpa 自带 Silero VAD）自动断句。

## 五、风险（提前说清，别到时候当 bug）

1. **质量**：20MB 小模型的中文准确率明显不如 vivo/讯飞云端，专有名词、人名更差；
2. **没有标点**：流式 zipformer 输出不带标点，输入框里会是一长串；补标点要另做一层规则
   （效果好不了多少，工作量大），建议先接受；
3. **CPU/发热**：int8 + 小模型在 8 核上通常够快（RTF << 1），但要真机确认；
4. **只能靠 CI + 真机**：本机编不了 `:app`、无模拟器，接线错误仍要靠 CI 抓；
5. **体积**：+35MB 对 sideload 的 debug 包可接受，但比现在大一倍多；
6. **许可**：模型许可没核完之前不能发版。

## 六、验收判据（能不能算“成了”）

- 断网飞行模式下，说一句中文，输入框出字（说明真离线）；
- 说完 1 秒内开始出字，整句延迟 ≤ 2 秒；
- 连说 10 句，8 句以上基本正确（可选：给出错误示例对比）；
- APK 体积增量与实测一致（±3MB）；
- 任意品牌机器上表现一致（不需要另一台机器也能从“无网络依赖”推出）。

## 七、要拍板的三件事

1. **体积上限**：`+≈35MB`（20MB 模型）能接受吗？还是要更小（更差）或更大（更准，126MB 档）？
2. **系统识别那条路**：**完全删掉**（行为统一，推荐）还是留成隐藏备选（默认关）？
3. **标点**：先接受“无标点”（推荐，P0），还是这一版就要补？
