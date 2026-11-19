# Claude Privacy Gate

面向 Claude Android 的 SukiSU Ultra 模块与 LSPosed Hook，提供公网 IPv4 启动核验、应用内隐私替身配置和本地诊断日志。设置界面跟随系统明暗主题。

**当前开发版：v0.1.15** · 适配 Claude **1.261113.19**（`versionCode: 26111319`）

[下载 ZIP](https://github.com/chxcodepro/claude-privacy/releases/download/v0.1.15/claude-privacy-0.1.15-dev.zip) · [Releases](https://github.com/chxcodepro/claude-privacy/releases) · [更新记录](CHANGELOG.zh.md) · [问题反馈](https://github.com/chxcodepro/claude-privacy/issues)

> [!WARNING]
> 当前为开发构建。守护恢复异常仍需现场日志定位，目标手机的日志保存流程尚待验证。部分采集拦截可能影响登录、绑卡、语音或推送；建议保留原配置备份。

## 目录

- [主要功能](#主要功能)
- [运行要求](#运行要求)
- [安装与首次配置](#安装与首次配置)
- [更新已有安装](#更新已有安装)
- [使用与故障排查](#使用与故障排查)
- [卸载与恢复](#卸载与恢复)
- [从源码构建](#从源码构建)
- [验证记录与反馈](#验证记录与反馈)
- [友情链接](#友情链接)

## 主要功能

- **出口核验**：Claude 启动、回到前台或网络变化时核验公网 IPv4。白名单为空时锁定；实测地址精确命中白名单、国家码有效且非中国大陆（CN）后才放行。IPv6 保持拒绝。
- **隐私替身**：配置 Android ID、厂商与机型、系统构建、运营商、电池、屏幕尺寸、时区和语言，按项开关并预览或编辑替身值。
- **随机设备档案**：生成一组匹配的设备字段与合成构建指纹，先进入草稿，确认保存后固定使用。Android ID 在首次配置时随机生成并保存。
- **时区选择**：支持自定义或按 IP 使用已保存的时区；从离线 IANA 列表按名称、UTC 偏移搜索，例如 `UTC+8`、`UTC-7`。
- **采集拦截**：覆盖适配样本中的 Sift、Plaid、Sentry、Google CCT、WebView 相关信息及部分 Java 遥测网络调用。
- **出口守护**：查看运行状态、手动启动守护，按核验进程回收临时放行。临时 IPv4 例外由守护续租，距上次续租 8 秒后在内核中到期。
- **本地诊断**：记录启动、心跳、退出、锁状态和核验阶段，可导出 TXT。导出排除配置原文、实际替身值、完整公网 IP、登录信息和聊天内容。

## 运行要求

| 项目 | 要求 |
| --- | --- |
| Android | Android 12L / API 32 及以上，满足 Hook APK 的最低 SDK 要求 |
| Root | SukiSU Ultra；为 `Claude Privacy Hook` 授予 Root 权限 |
| Hook 框架 | LSPosed 已启用，并将 Claude 加入后壳作用域 |
| Claude | 包名 `com.anthropic.claude`，版本 **1.261113.19**，`versionCode` **26111319** |
| 网络 | 手机应用级 VPN 或软路由；当前节点应可连接 `claude.ai:443` |
| 系统能力 | 内核支持 iptables `time` 匹配；设置页自动预检使用系统 `curl` |

其他 Claude 版本需重新核对采集字段映射，当前门禁会保持锁定。已记录的真机环境为 OnePlus PJZ110，详细结果见[验证记录](docs/VERIFICATION.md)。

## 安装与首次配置

1. 从 [Releases](https://github.com/chxcodepro/claude-privacy/releases) 的 **Assets** 下载 `claude-privacy-0.1.15-dev.zip`，在 SukiSU Ultra 中安装并重启手机。
2. 打开模块 WebUI，点击 **安装 Claude Privacy Hook**。
3. 在 SukiSU Ultra → 超级用户中，为 `Claude Privacy Hook`（`dev.chx.claudeprivacy`）开启 Root；在 LSPosed 中启用该后壳并勾选 Claude 作用域。
4. 回到模块 WebUI，点击 **检查 Root 授权**。
5. 获取并确认出口 IPv4，或手动输入允许的公网 IPv4，添加后进入主界面调整隐私替身。
6. 从桌面打开 Claude，查看启动核验。地址符合允许条件后显示对勾，再进入应用。

> [!TIP]
> 下载 Assets 中的模块 ZIP。GitHub 自动提供的 **Source code (zip)** 用于查看源码。

## 更新已有安装

1. 在模块设置中点击 **检查更新**，或从 [Releases](https://github.com/chxcodepro/claude-privacy/releases) 下载开发版 ZIP。
2. 在 SukiSU Ultra 中覆盖安装模块并重启。
3. 回到模块设置，按提示点击 **更新 Hook**。v0.1.15 对应的 Hook `versionCode` 为 **13**。
4. 重新打开 Claude 完成核验。

升级保留白名单和替身设置，配置文件位于 `/data/adb/claude_privacy/config.json`。后壳采用覆盖安装，保留其应用数据；更新后可复查 Root 授权和 LSPosed 作用域。

## 使用与故障排查

### 添加允许的公网 IPv4

点击 **刷新出口 IPv4**，查看候选地址和地区后确认添加。设置页优先读取 Claude 的本机实测记录；没有记录时，匿名预检 `claude.ai/cdn-cgi/trace`。实际放行仍以 Claude 进程的启动核验为准。

探测使用独立请求，不复用登录 Cookie、令牌、账号或设备标识，也不向第三方归属地服务提交 IP。**更多**按钮可按需打开 [Net.Coffee](https://ip.net.coffee/claude/) 作对比。

软路由节点应能正常访问 Claude。应用级 VPN 和软路由共用当前入口；显式 Android HTTP/SOCKS 代理会保持锁定。按目标域名分流时，核验结果对应 `claude.ai`，其他服务域名可能走不同出口。

### 设置时区与语言

可选自定义时区，或复用该 IP 保存的时区。国家码只对应一个时区时可离线确定；多时区或未知地区使用可编辑的预设。移除允许 IP 时，会清除该 IP 的专属时区，独立预设保留。

时区和语言显示 **Claude 读回一致** 时，表示应用进程中的读回值与当前配置匹配。聊天中的云端时钟回答可能只提供 UTC；本地替身值请在模块设置中查看。

### 导出守护异常日志

出现 **出口守护未就绪**、退出后重新打开异常等情况时：

1. 在恢复守护或重启前，打开模块设置。
2. 点击状态卡内的 **导出诊断日志**，通过系统文件选择器保存 TXT。
3. 记录复现步骤，将 TXT 附在 [Issue](https://github.com/chxcodepro/claude-privacy/issues) 中。
4. 日志保存后，再尝试 **启动守护** 并重新打开 Claude。

日志在本机生成，Root 日志按 64 KiB 轮转并保留一份旧记录。若提示先更新 Hook，请完成后壳更新再导出。

## 卸载与恢复

- 临时恢复 Claude 原行为：禁用模块及其 LSPosed 作用域，然后重启手机。
- 卸载模块：卸载脚本会移除 Claude UID 的防火墙规则，并尝试卸载 Hook APK。
- 如需保留设置，操作前备份 `/data/adb/claude_privacy/config.json`。

## 从源码构建

需要 **Node.js 24、Vite+、Android SDK 36、JDK 17+ 和 PowerShell**。

```powershell
git clone https://github.com/chxcodepro/claude-privacy.git
cd claude-privacy
vp install
./scripts/package.ps1
```

产物：`artifacts/claude-privacy-0.1.15-dev.zip`，包含 WebUI、Hook APK 和模块脚本。Hook APK 当前使用本机 debug 签名。

### 项目结构

```text
apps/web/       React WebUI 与配置管理
hook/           Kotlin / LSPosed Hook 与桥接后壳
module/         SukiSU 脚本、守护与默认配置
packages/ui/    共享界面组件
scripts/        构建及定向检查脚本
docs/           验证记录与技术资料
```

WebUI 基于 VibeStart CLI 0.1.1 创建的 React SPA。模块布局参考 [KernelSU 模块规范](https://github.com/tiann/KernelSU/blob/main/website/docs/guide/module.md?plain=1)及 [WebUI 文档](https://kernelsu.org/guide/module-webui.html)，Hook 入口使用 LSPosed 支持的 Xposed API 82。

## 验证记录与反馈

v0.1.15 构建及相关功能的定向检查已通过，详细历史证据和待验证项见[验证记录](docs/VERIFICATION.md)。Java 层网络拦截的覆盖范围有限，SDK 原生网络路径、WebView 实际页面以及登录、语音和推送等行为仍需目标设备验证。

提交问题时请附模块版本、Hook 版本、Claude 版本、Android 系统版本、复现步骤及导出的诊断日志。请先检查附件，移除账号、密钥和其他个人信息。

- [提交问题](https://github.com/chxcodepro/claude-privacy/issues)
- [查看更新记录](CHANGELOG.zh.md)

## 友情链接

- [LINUX DO](https://linux.do/)
