# 第三方来源与许可

本应用自编源码为MIT，依赖及模型各自保留原许可。模型文件名和来源保留，没有重新训练或改写模型；上游ONNX INT8转换和元数据由sherpa-onnx社区提供。

| 项目 | 作者/来源 | 许可材料 |
|---|---|---|
| sherpa-onnx 1.13.8 | k2-fsa/sherpa-onnx | [Apache-2.0](licenses/sherpa-onnx-Apache-2.0.txt) |
| SenseVoice Small INT8 2024-07-17 | FunAudioLLM / Alibaba / FunASR | [FunASR模型协议v1.1](licenses/FunASR-MODEL-LICENSE.txt) |
| CT-Transformer zh-en vocab272727 INT8 2024-04-12 | Alibaba / ModelScope / FunASR | [FunASR模型协议v1.1](licenses/FunASR-MODEL-LICENSE.txt) |
| Silero VAD | Silero Team / snakers4/silero-vad | [MIT](licenses/Silero-MIT.txt) |

FunASR源码的当前MIT许可与其模型权重许可不同，模型请依照模型协议使用和再分发。模型包附来源、作者、完整模型许可和原归档说明；不要删掉这些材料。

官方链接：

- [运行库版本与Android AAR](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)。AAR SHA256：633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96。
- [SenseVoice官方项目](https://github.com/QwenAudio/SenseVoice) / [ONNX INT8归档](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2)。
- [CT-Transformer原模型](https://modelscope.cn/models/iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch/summary) / [ONNX INT8归档](https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2)。
- [Silero项目](https://github.com/snakers4/silero-vad) / [本项目使用的ONNX文件](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx)。

AndroidX、Kotlin及运行库内ONNX Runtime等组件保留各自许可和通知；AAR原样随附，本应用MIT不替代其条款。公开仓库和发行附件不包含用户录音、转写历史或签名密钥。
