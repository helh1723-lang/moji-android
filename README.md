# 默迹（Moji）

<p align="center">
  <img src="moji_app_icon.svg" width="128" alt="默迹 App 图标">
</p>

<p align="center"><strong>支付完成，账目已记。</strong></p>

默迹是一款 Local First 的 Android 自动记账应用，面向希望减少手动录入、同时重视隐私和数据可迁移性的个人用户。账本、分类规则和统计默认保存在设备上，不要求注册账号，也不包含广告。

> 少一点记账，多一点生活。

## 项目特色

- **付款后自动记账**：在用户主动授权后，辅助识别微信、支付宝等支付结果中的必要信息，并在设备上生成待确认账单。
- **可纠正的自动化**：金额、商户、分类和备注都可以在保存前修改；识别不确定时保留人工确认和手动录入入口。
- **语音批量记账**：一句话描述多笔收入或支出，逐笔检查、编辑，最后一次确认后批量保存。
- **本地规则分类**：按商户或关键词创建、编辑、停用和删除自己的分类规则。
- **统计与预算**：查看月度支出、收入、结余、趋势、分类排行和预算剩余。
- **数据可迁移**：支持本地 ZIP 备份恢复，以及 CSV、XLSX 导出。
- **可选 AI 文本解析**：默认关闭；启用后使用用户自行选择的兼容 API，将当前输入匹配到已有分类。

## 使用指导

### 1. 开始使用

1. 从 [Releases](https://github.com/helh1723-lang/moji-android/releases) 下载 APK 并安装。
2. 打开应用，先在“我的”页面阅读隐私说明和权限用途。
3. 如果需要自动记账，按页面提示开启通知访问和无障碍服务；不需要时可以只使用手动或语音记账。
4. 在首页通过“+”手动新增账单，或进入语音输入流程。

### 2. 自动记账

开启自动记账后，应用会在获得系统授权的范围内观察支付结果，并尝试提取金额、商户和成功状态。识别结果会先进入确认流程，用户可以修改后再保存。

自动采集受 Android 版本、设备厂商、权限状态和第三方支付页面变化影响，不能保证所有机型和所有支付页面都能识别。应用不会代替用户点击或发起支付。

### 3. 语音与文本记账

在语音输入页面说出类似以下内容：

```text
早餐花了 15 元，买水果 10 元，晚上面包 3 元
```

应用会将内容拆分为多笔待确认账单。左右切换检查每一笔，修改金额、收支方向、分类、备注或日期，最后点击保存。也可以使用手动录入作为 fallback。

### 4. 统计、预算与导出

- “账本”页面：按月份浏览交易，可按时间、平台、来源和状态筛选。
- “统计”页面：查看月度/年度趋势、分类排行、收入和结余。
- “我的”页面：配置预算、分类规则、自动记账、AI 文本解析和数据管理。
- 数据管理：定期创建 ZIP 备份；需要在其他工具中处理时可导出 CSV 或 XLSX。

## 隐私与安全边界

- 账本、分类规则和统计默认保存在设备的 Room 数据库中。
- AI 文本解析默认关闭；启用后只发送当前待解析文本、可用分类名称和用户配置的 API Key，不发送历史账单。
- API Key 使用 Android Keystore 保护，不写入备份、导出文件或普通日志。
- 普通诊断日志不保存完整页面文本、金额、商户、备注或订单号。
- 无障碍与通知访问属于高敏感权限，请只在理解用途后开启，并可随时在系统设置中撤销。

## 架构概览

```text
支付结果 / 通知
        ↓
本地采集、解析与去重
        ↓
商户规则分类 ──→ 不确定时人工确认
        ↓
Room 本地账本
        ↓
统计、预算、备份与导出
```

主要代码区域：

- `app/src/main/java/com/moji/app/capture/`：支付结果、通知和无障碍采集
- `app/src/main/java/com/moji/app/voice/`：语音/文本拆分与解析
- `app/src/main/java/com/moji/app/data/`：Room 数据层和仓库
- `app/src/main/java/com/moji/app/ui/`：Compose 页面、导航和交互
- `docs/UI_DESIGN_SYSTEM.md`：界面设计系统
- `默迹_Android自动记账APP_PRD.md`：产品需求与验收口径

## 开发与构建

### 环境要求

- Android Studio（建议使用稳定版）
- JDK 17
- Android SDK，至少包含 API 24；编译目标为 API 35

首次构建前，请在 Android Studio 中配置 SDK，或使用标准 Gradle Wrapper 让项目按提示完成配置。SDK 配置文件、签名文件、API Key 和构建输出均不应提交到仓库。

### 构建 Debug APK

macOS / Linux：

```bash
./gradlew assembleDebug
```

Windows：

```powershell
.\gradlew.bat assembleDebug
```

APK 输出在：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 运行测试与检查

```bash
./gradlew testDebugUnitTest lintDebug
```

Windows 可将命令中的 `./gradlew` 替换为 `.\gradlew.bat`。正式 Release 构建需要通过环境变量提供外部签名配置；签名密钥和属性文件不得提交到仓库。

## 版本信息

当前版本：`1.2.0`（versionCode `13`）

1.2.0 重点修复多笔账单在第二笔确认时无法正确保存的问题，并完善了账单筛选、分类规则和数据管理流程。自动支付采集和第三方页面兼容性仍应以实际设备验证结果为准。

## 参与贡献

欢迎提交 Issue 和 Pull Request。提交前请：

1. 不要加入真实账本、通知正文、设备截图、API Key、签名密钥或其他个人数据。
2. 为解析器、数据层和关键交互补充测试。
3. 在 PR 中说明 Android 版本、设备范围、复现步骤和验证结果。
4. 涉及支付采集或权限行为的改动，请同时更新隐私说明和相关文档。

## 开源许可证

本项目采用 [MIT License](LICENSE)。

---

<p align="center">
  <img src="https://count.getloli.com/@helh1723-lang?name=moji-android&theme=minimal&padding=7&offset=0&align=center&scale=1&visibility=1" alt="访问量统计">
</p>
