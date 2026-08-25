# 默迹（Moji）Android

默迹是一款 Local First 的 Android 自动记账应用。它在用户明确授权后，通过无障碍服务与可选的通知辅助识别微信、支付宝支付结果，在设备本地完成解析、去重、分类和记账。

## 当前版本

- 版本：`1.0`（versionCode 1）
- 最低 Android：7.0（API 24）
- 目标 Android：API 35
- 已完成华为真机链路验证：微信支付通知识别、未知商户自动入账、支付后悬浮卡片
- 悬浮卡片支持原地修改金额、商户和分类，以及撤销自动入账

## 隐私边界

- 账本和规则保存在本地 Room 数据库中。
- 普通诊断记录不保存完整页面文本、金额、商户或订单号。
- “脱敏调试采样”仅在用户主动开启后的 24 小时内记录结构特征。
- 仓库不包含真实账本、设备采样、通知正文、签名密钥或本机配置。

## 构建

Windows 环境推荐使用项目提供的缓存安全脚本，它会将 Gradle 缓存放在 `D:\Gradle\ConnectPad`：

```powershell
.\gradlew-d.bat testDebugUnitTest lintDebug assembleDebug --no-daemon
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

也可以使用标准 Gradle Wrapper；首次构建前需自行配置 Android SDK 的 `local.properties`，该文件不会提交到仓库。

## 从 GitHub 恢复

```powershell
git clone https://github.com/helh1723-lang/moji-android.git
cd moji-android
.\gradlew-d.bat assembleDebug --no-daemon
```

如果只需要安装包，可直接从 GitHub Releases 下载对应版本的 APK。

## 文档

- `默迹_Android自动记账APP_PRD.md`：产品需求与验收口径
- `docs/UI_DESIGN_SYSTEM.md`：界面设计系统

## 安全提醒

无障碍与通知访问均属于高敏感权限。安装后请只在理解用途并同意隐私披露的情况下开启；应用不会代替用户点击或发起支付。
