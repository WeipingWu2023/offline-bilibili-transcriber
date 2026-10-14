# 发行与签名

GitHub Release提供关闭debug自检入口的release APK，versionName1.0.1 / versionCode3。发行密钥仅保存于维护者本地`.tools`，没有进入仓库或源码压缩包。维护者必须安全备份`.tools/offline-notes-release.jks`及`.tools/signing.properties`，丢失签名将无法为现有安装覆盖升级。

## 自行构建签名版

安装JDK17并用keytool交互式生成自己的密钥（密码自行输入，不提交）：

```powershell
New-Item -ItemType Directory -Force .tools
keytool -genkeypair -keystore .tools/my-release.jks -alias offline-notes -keyalg RSA -keysize 4096 -validity 10000
```

创建本地`.tools/signing.properties`：

```properties
storeFile=.tools/my-release.jks
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=offline-notes
keyPassword=YOUR_PRIVATE_PASSWORD
```

该文件被Git忽略。不要把真实密码或密钥写进源码、Issue、Actions日志或公开附件。执行：

```powershell
.\gradlew.bat :app:assembleRelease :app:testDebugUnitTest :app:lintRelease --console=plain
```

产物在`app/build/outputs/apk/release/app-release.apk`。没有签名配置时，Gradle只生成未签名release，不应把它当成可直接安装版。

## 更新规则

保持applicationId与签名密钥，递增versionCode，构建并用AndroidSDK的apksigner verify检查签名，生成SHA256清单，再上传APK和模型附件至Release。模型与APK分开版本管理；旧用户模型可继续使用。GitHub自动Source code归档不含构建工具、密钥、录音或模型ZIP。

原手机开发debug版与公开release签名不同，不能直接覆盖。请先导出重要内容再自行迁移；不要用卸载来解决冲突而丢失数据。v1.0.1的图标测试debug版仍使用原debug签名，便于原测试设备保留数据。
