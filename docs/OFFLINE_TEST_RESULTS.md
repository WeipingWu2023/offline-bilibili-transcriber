# 完整应用验证记录 · 2026-10-14

## 已执行

- 红米 Note 13，设备型号2312DRAABC，Android14/API34。升级安装完整应用成功，保留原录音。
- 用户通过应用导入默认模型ZIP，在“文稿”中完成既有 Bilibili 普通点播短录音的离线识别，确认“能看到中文原文和标点版”。页面显示完成100%、6个语音段。没有人工纠正参考稿，未计算该视频CER，也不声称逐字正确。
- 最后一次源码构建：`gradlew.bat --offline :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain`，BUILD SUCCESSFUL。4个单元测试通过；Lint 0错误、14警告。重采样检查涵盖8/16/44.1/48kHz时长与抗混叠，另检查WAV恢复、文字和数字保护、CER。
- APK权限检查没有 INTERNET；应用没有联网转写路径。未另行做断网抓包测试。
- 官方中文PCM16/16kHz短样本通过生产 TranscriptionService 完成 VAD → SenseVoice → CT标点 → Room。源文件178988字节、5.592秒（89472样本）；设备SHA256与本地一致：b77f1794fe374a0ba1ee1dc458bfaf9349496cbbfc32780c50ba3c5a7ad8e373。

## 官方短样本结果

| 项目 | 结果 |
|---|---|
| 状态 | DONE，1段 |
| 预设参考 | 开饭时间早上九点至下午五点 |
| 原文 | 派饭时间早上九点至下午五点 |
| 标点版 | 派饭时间早上九点至下午五点。 |
| CER | 1/13 = 7.69%，一次替换 |

参考为开发自检预设文本，并非用户 Bilibili 录音的人工真值。一个短样本只能证明该样本链路及计算结果，不代表通用准确率。自检结果JSON保存于本机 `evidence/offline-20261014/official-sample.json`，源码包不包含用户录音或完整数据库。

初次自检失败：此前通过ADB标准输入传入的样本被截断到131072字节，无法解码；使用ADB push再复制至本应用私有目录并核对SHA256后成功。辅助脚本已改为此流程并强制哈希校验。

## 未执行与限制

- Android instrumentation测试源码及测试APK已构建；独立测试包安装被手机限制，未运行该测试，不宣称其通过。后续官方样本结果来自已安装应用内仅debug版启用的自检入口。
- 按用户指示未做直播、会员内容、长录音、完整重复捕获验收。Phase0兼容性证据仅支持这台设备及该次普通点播。
- Android10–13、Android15+、粤语、Qwen3/Paraformer、持续发热、低存储、长任务暂停恢复、全部导入编码和全部导出提供方尚未做实体覆盖测试。代码有相应处理，但不能称为这些场景已经验收。
- 用户文本不由模型自动润色；原文、标点版、手工编辑稿独立。漏词或识别幻觉仍需回听和人工参考稿评估。

## 复现官方自检

在本人已授权的调试设备上，准备来自官方SenseVoice归档的 `test_wavs/zh.wav`，按下面步骤写入本应用私有测试文件。仅debug版本接受DEBUG_ASR动作；不录音、不请求投影。

```powershell
powershell -ExecutionPolicy Bypass -File scripts\push-private-file.ps1 -Serial YOUR_SERIAL -Source .tools\test-zh.wav -Name test-zh.wav
# 先通过应用“模型”页导入默认包；确认无录制/转写任务运行，再重开应用
.\.tools\android-sdk\platform-tools\adb.exe -s YOUR_SERIAL shell am force-stop com.example.captureprobe
.\.tools\android-sdk\platform-tools\adb.exe -s YOUR_SERIAL shell am start -n com.example.captureprobe/.MainActivity -a com.example.captureprobe.DEBUG_ASR --es audioName test-zh.wav
# 等待任务完成，输出目录需预先创建
powershell -ExecutionPolicy Bypass -File scripts\read-private-result.ps1 -Serial YOUR_SERIAL -Remote files/debug-last-run.json -Output evidence\official-sample.json
```

不同设备运行速度和结果可能不同。对真实内容，使用“准确度评估”保存人工纠正参考稿，并用同一音频、同一参考比较模型。
