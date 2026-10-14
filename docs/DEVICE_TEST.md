# 实体设备验收

没有实体设备的 WAV 证据就记录为 **未测试**，不能写“兼容 Bilibili”。模拟器或系统录屏通过都不算 Bilibili 验收。

## 记录环境

填写 `docs/TEST_RESULTS.md` 的设备表：设备型号、厂商系统版本、Android/API、应用版本/包名、视频标识、播放类型（普通/直播/会员等）、音量、耳机/蓝牙路由、授权选择、日期。不同播放类型分别测试，成功不可推广为全部 Bilibili 内容可录。

USB 连接 Android 10+ 手机，启用开发者模式和 USB 调试，在手机上确认电脑的调试授权。保留官方 Bilibili，测试使用自己有权录制的普通中文讲话视频；30–60 秒已足够初验。

```powershell
$adbPath = 'D:\god\Sediment\.tools\android-sdk\platform-tools\adb.exe'
& $adbPath devices -l
& $adbPath shell getprop ro.build.version.sdk
& $adbPath shell getprop ro.product.model
& $adbPath shell dumpsys package tv.danmaku.bili | Select-String 'versionName|versionCode|userId'
& $adbPath install -r D:\god\Sediment\app\build\outputs\apk\debug\app-debug.apk
& $adbPath logcat -c
& $adbPath logcat -v threadtime 'CaptureProbe:I' 'AndroidRuntime:E' '*:S'
```

最后一条持续显示日志，用 Ctrl+C 退出；应用内也可导出每次会话的 `.log`。`logcat -c` 会清空设备全局日志缓冲区，仅在专用测试设备上使用；不愿清空可省略。

也可在停止录音后，通过调试 APK 的 `run-as` 权限收集本应用自己的最新 WAV / 日志（不使用 root，不访问 Bilibili 私有数据）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\collect-evidence.ps1 -Serial 手机序列号 -Label control
# Bilibili 测试后改用 -Label bilibili
```

证据保存在本项目 `evidence` 目录中，不加入源码包。脚本使用原始二进制流，避免 PowerShell 文本管道破坏 WAV。

## A：正向对照

1. 进入工具，点“对照：本应用”，目标变为 `com.example.captureprobe`。
2. 点“授权并开始录音”，同意 RECORD_AUDIO、通知和系统投影授权。Android 14+ 确认完整显示区域授权（厂商界面可能不同）。
3. 在录制状态下点“播放 15 秒可捕获对照音”。提高媒体音量，保持页面可见。
4. 确认电平明显变化，等待对照音结束，点“停止并保存 WAV”。
5. 选择该 WAV 并回听：应有交替 440/880 Hz 的声音，每秒末尾有短暂停顿。导出 WAV 与会话日志，标为 CONTROL。

对照失败意味着录音链或环境未验证，应先检查授权、设备、音量、音频路由及读错误。此时不能把 Bilibili 无声音归因为捕获策略。

## B：官方 Bilibili 捕获

1. 停止回听，点“Bilibili”。先确认目标包名与本机安装一致，应用在同一用户配置。
2. 点“授权并开始录音”，使用这次新的系统授权；看到“录制中”后再切换。
3. 手动打开官方 Bilibili，播放指定中文讲话片段 30–60 秒。不要启动系统录屏，不播放其他应用声音。
4. 暂停 5 秒再继续讲话 10 秒，便于比对时间连续性。记录播放起止点与暂停时间。
5. 从通知停止，或返回工具点停止。选择最新 WAV，回听完整片段、暂停位置和结尾，导出 WAV / 日志。
6. 在 Bilibili 静音/暂停时进行负向对照：录音过程中面对手机说话；回听不应出现你的环境讲话。若出现，不通过并检查目标音轨是否包含类似内容，不能宣称内部捕获成功。
7. 重复三次；至少另测一段不同的普通视频。蓝牙/耳机、直播或特殊内容需要时单列结果。

## C：生命周期与错误

| 场景 | 期望 |
|---|---|
| 拒绝 RECORD_AUDIO / 取消投影 | 不开始录音，显示明确日志 |
| Android 13+ 拒绝通知 | 服务仍遵守投影授权，可回到工具停止 |
| 来回切换 Bilibili / 工具、旋转工具 | 同一录音继续，无重复授权令牌使用 |
| 录制后返回页面回听 | 必须先停止；回听声不作为捕获来源 |
| 系统投影状态入口撤销 / 另一投影 | `onStop` 后保存、释放；再次录制须新授权 |
| 锁屏 | 记录实际系统行为；如撤销投影应处理 onStop，不能承诺所有系统持续录制 |
| 暂停 ≥8 秒 / 媒体音量为零 | 低电平提示，不擅自宣布屏蔽 |
| 音频服务失效 / 读取错误 | 错误码与停止原因入日志，部分音频仍可回听 |
| 空间不足 | 显示原因，尽可能保存已有 PCM |
| 连续录制 10 分钟 | PCM 时长与预期相符，无明显掉音；记录电量和温度 |
| 系统杀死进程 | 下次启动恢复 .part；标明中断，无自动重新授权 |
| 重复开始/停止 5 次 | 每次新授权，无残留活动服务、文件可播放 |
| 本地 WAV / M4A / 含音轨 MP4 导入 | 导入后重启应用仍可回听；不转写 |
| 无音轨/不支持格式/DRM 文件 | 提示原因，无绕过尝试 |
| 删除全部 | 私有测试文件和日志删除；导出副本不受影响 |

进程中断可在专用测试设备上运行 `adb shell am force-stop com.example.captureprobe`，再手动打开应用。force-stop 是恢复测试，不能用于验证正常后台录音。

## 判断标准

**PASS（限定环境）**：正向对照通过，官方 Bilibili 三次测试录到实际片段，WAV 有可辨识中文声音、顺序和结尾完整；人工对照没有额外环境音；切换应用期间不中断；导出的日志无读取错误或意外停止。PCM 时长应覆盖实际录音窗口，和墙钟的差距在初始化开销之外没有持续扩大；记录实际数值，10 分钟测试建议差距 ≤1 秒，超限需调查。

**FAIL_CAPTURE / 疑似策略或播放路径限制**：正向对照通过；同用户、正确 UID、已授权、片段持续可听、音量与路由正常；Bilibili 多次录制仍全零/近静音或无对应讲话。报告“该设备、版本和播放场景下第三方捕获失败”，策略原因只能标为推测。停止该捕获路线，使用用户本地文件导入。不 root、不解密、不改 Bilibili、不借系统特权录音。

**FAIL_INTERRUPTION**：能录到内容但出现错误、中途停流、缺段或结尾丢失。捕获质量不通过，需要修复或限定环境，不把部分有声音当作完整成功。

**INCONCLUSIVE**：正向对照失败、用户未授权、应用不在同一配置、没有可靠可听源、目标 UID 未确认或无法回听。这不证明 Bilibili 屏蔽。

**NOT_TESTED**：未连接实体设备或未执行上述操作。编译 APK 不改变该结论。

**FEASIBILITY_VERIFIED（初验）**：对照有有效 PCM，且一次明确的 Bilibili 片段已录到，并由人工确认讲话顺序和结尾。可以确认这一次场景具有捕获可行性，但不能代替上面的三次重复正式 PASS 与其他场景验收。本次交付当前处于此状态。
