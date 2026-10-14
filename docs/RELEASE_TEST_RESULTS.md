# v1.0.1 公开发行检查 · 2026-10-14

- 新增满底色的自适应启动图标及roundIcon，波形处于安全区域，无白色背景层。最终圆形由桌面启动器决定。
- 实际执行 `gradlew.bat --offline :app:assembleRelease :app:assembleDebug :app:testDebugUnitTest :app:lintRelease --console=plain`，BUILD SUCCESSFUL。
- 4项debug单元检查通过；release lint 0错误、11警告。
- release BuildConfig.DEBUG=false，APK未声明debuggable；关闭debug ASR入口。
- apksigner verify通过，APK v2签名有效；RSA4096，发行证书SHA256为 `aae43aa7da59477cc30eeac37d54233050ee591102579b581ecaf57bbba26530`。
- aapt检查versionName1.0.1/versionCode3、minSdk29/targetSdk35；无INTERNET权限。
- 模型新增完整FunASR模型协议、Silero MIT和作者来源说明；模型权重本身未改变。
- 公开APK使用新发行签名，原测试手机仍保留debug签名与数据。release构建和签名已验证，未将新签名release覆盖安装在这台有现有数据的手机上。先前已验证功能详见OFFLINE_TEST_RESULTS.md。
- 首页推广海报为AI生成插画，不是实际页面截图。
