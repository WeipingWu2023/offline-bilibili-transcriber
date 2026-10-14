# 架构与文件职责

`MainActivity`负责Compose页面入口、系统投影授权、SAF导入导出、回听与对照音；`WorkspaceUi`负责模型导入、历史、识别任务、编辑及评估。

`CaptureService`是非导出mediaProjection前台服务，每次使用新授权，按源应用UID过滤MEDIA/GAME/UNKNOWN播放音频，AudioRecord以48kHz单声道PCM16写WAV。`WavFile`负责头部修补、PCM电平和中断恢复；`ProbeState`保存状态及诊断。切换应用不依赖Activity存活，不自动复用授权。

`ModelStore`从用户选择的本地ZIP导入模型，限制条目数、展开大小、空间、路径和manifest；必须有完整SHA256清单。清单校验不是发布方数字签名，应使用可信来源。

`AudioProcessing`对PCM16/float32 WAV直接读取，其他音视频用MediaExtractor/MediaCodec解码，拒绝加密轨；有界下混与低通重采样到16kHz。原音频保留。

`TranscriptionService`在工作线程运行VAD、非流式ASR和标点，CPU识别2线程、其他1线程，最长语音段20秒。逐段持久化，通知和UI显示进度；高温、暂停、系统超时保存已完成结果。恢复重新扫描VAD并跳过已存段。

`AppStore`用Room保存AudioEntry、AsrRun、TextSegment，外键级联删除；启动将未结束任务标为中断。每次重做创建新任务，原文、标点版、编辑稿分别保存。

`TranscriptText`保护词句与数字、生成时间范围、保守分段和导出内容。`Accuracy`对Unicode code point计算可复现的替换/删除/插入CER，忽略标点和空白，需人工参考。

运行库来自`app/libs`，模型完全来自私有本地目录。Manifest没有INTERNET权限，服务不导出；debug自检入口仅在BuildConfig.DEBUG启用，release版关闭。
