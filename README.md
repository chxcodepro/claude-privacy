# Claude Hook

面向 Claude Android 与 Claude Duet 的 SukiSU Ultra 模块与 LSPosed Hook，提供公网 IPv4 启动核验、应用内隐私替身配置和本地诊断日志。设置界面跟随系统明暗主题。

**支持 `com.anthropic.claude` 与 `com.anthropic.aibo`** · 已对 Claude **1.261113.19 / 1.261117.19 / 1.261120.19 / 1.261124.19** 和 Claude Duet **1.261125.9** 完成 APK 接口静态检查。

[下载 ZIP](https://github.com/chxcodepro/claude-privacy/releases) · [更新记录](CHANGELOG.zh.md) · [问题反馈](https://github.com/chxcodepro/claude-privacy/issues)

> [!WARNING]
> 当前为开发构建。守护恢复异常仍需现场日志定位，目标手机的日志保存流程尚待验证。部分采集拦截可能影响登录、绑卡、语音或推送；建议保留原配置备份。

## 目录

- [主要功能](#主要功能)
- [运行要求](#运行要求)
- [安装与首次配置](#安装与首次配置)
- [更新已有安装](#更新已有安装)
- [迁移到新设备](#迁移到新设备)
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
- **设备报告拦截**：关闭新版 DeviceReport 的注册、登录和支付采集入口，阻止该链路的 26 项硬件指纹采集、报告上传及一次性设备属性证明申请。此项始终启用。
- **Play Integrity 可选拦截**：默认关闭，标注「不建议开启」。开启后阻止 Classic token 申请、Standard 预热与请求，并在登录和购买载体中省略对应 token 字段；可能影响登录或支付。
- **出口守护**：查看运行状态、手动启动守护，按核验进程回收临时放行。临时 IPv4 例外由守护续租，距上次续租 8 秒后在内核中到期。
- **本地诊断**：记录启动、心跳、退出、锁状态和核验阶段，可导出 TXT。导出排除配置原文、实际替身值、完整公网 IP、登录信息和聊天内容。
- **配置迁移**：导出或导入完整 JSON 配置，沿用 IP 白名单、时区、语言、Android ID、设备替身和全部开关。
- **更新后自动识别**：从当前 APK 的协议名称和字段键定位接口，适应混淆类名变化。Claude 版本号变化本身不会触发锁定。

两款应用共用白名单与替身设置，各自维护防火墙链、核验状态和进程租约。设置页展示最近更新的应用核验状态；「出口守护」检查当前用户已安装的两款应用，启动操作会为它们分别恢复守护。Duet 样本包含 Sift 与 CCT；Plaid 替身适用于含该 SDK 的 Claude。

## 运行要求

| 项目      | 要求                                                         |
| --------- | ------------------------------------------------------------ |
| Android   | Android 12L / API 32 及以上，满足 Hook APK 的最低 SDK 要求   |
| Root      | SukiSU Ultra；为 `Claude Hook` 授予 Root 权限        |
| Hook 框架 | LSPosed 已启用，将使用的 Claude／Claude Duet 加入后壳作用域 |
| 应用      | 包名 `com.anthropic.claude` 或 `com.anthropic.aibo`，按当前 APK 的接口结构自动识别 |
| 网络      | 手机应用级 VPN 或软路由；当前节点应可连接 `claude.ai:443`    |
| 系统能力  | 内核支持 iptables `time` 匹配；设置页自动预检使用系统 `curl` |

已记录的真机环境为 OnePlus PJZ110。未来采集协议或原生路径变化仍需审计；已开启保护的接口识别缺失、歧义或字段契约错误时，模块锁定联网并记录原因。详细结果见[验证记录](docs/VERIFICATION.md)。

## 安装与首次配置

1. 从 [Releases](https://github.com/chxcodepro/claude-privacy/releases) 的 **Assets** 下载 `claude-privacy-<版本>-dev.zip`，在 SukiSU Ultra 中安装并重启手机。
2. 打开模块 WebUI，点击 **安装 Claude Hook**。
3. 在 SukiSU Ultra → 超级用户中，为 `Claude Hook`（`dev.chx.claudeprivacy`）开启 Root；在 LSPosed 中启用该后壳，勾选使用的 Claude 和／或 Claude Duet。
4. 回到模块 WebUI，点击 **检查 Root 授权**。
5. 获取并确认出口 IPv4，或手动输入允许的公网 IPv4，添加后进入主界面调整隐私替身。
6. 从桌面打开 Claude，查看启动核验。地址符合允许条件后显示对勾，再进入应用。

> [!TIP]
> 下载 Assets 中的模块 ZIP。GitHub 自动提供的 **Source code (zip)** 用于查看源码。

## 更新已有安装

1. 在模块设置中点击 **检查更新**，或从 [Releases](https://github.com/chxcodepro/claude-privacy/releases) 下载开发版 ZIP。
2. 在 SukiSU Ultra 中覆盖安装模块并重启。
3. 回到模块设置，按提示点击 **更新 Hook**。v0.1.21 对应的 Hook `versionCode` 为 **18**；使用 Duet 时在 LSPosed 中补勾其作用域。
4. 强制停止已运行的目标应用，再重新打开并完成核验。

升级保留白名单和替身设置，配置文件位于 `/data/adb/claude_privacy/config.json`。后壳采用覆盖安装，保留其应用数据；更新后可复查 Root 授权和 LSPosed 作用域。

## 迁移到新设备

1. 旧设备打开模块设置，在底部 **配置迁移** 点击 **导出配置**，选择保存位置。
2. 将 `claude-privacy-config.json` 复制到新设备。
3. 新设备安装模块与 Hook 并授予 Root。首次配置的 IPv4 步骤或设置页底部点击 **导入配置**。
4. 选中文件，核对时区、语言与白名单数量，点击 **覆盖并导入**。
5. 重启 Claude／Duet，让进程重新读取替身值并核验当前出口。新设备的公网 IPv4 需符合导入的白名单；出口变化时在设置页确认添加。

文件包含完整白名单、IP 地区与时区映射、语言、替身值和保护开关，请妥善保存。导入采用完整覆盖，Android ID 也沿用原值。Root 授权、LSPosed 作用域与联网放行状态由新设备重新建立。

## 使用与故障排查

### Play Integrity 保持默认关闭

「诊断与第三方」中的 **阻止 Play Integrity** 默认关闭，旧配置与旧备份也按关闭处理。该项不建议开启，开启前会确认登录／支付风险；导入开启该项的备份时也会提示。

开启后返回失败任务并省略对应证明 token，客户端继续走原有失败分支，服务端决定是否接受请求。出现登录或支付问题时关闭该项，并重启 Claude／Duet。此项独立于时区、语言、IP 核验与新版设备报告拦截。

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
- 卸载模块：卸载脚本会移除两款应用的防火墙规则，并尝试卸载 Hook APK。
- 如需保留设置，操作前备份 `/data/adb/claude_privacy/config.json`。

## 从源码构建

需要 **Node.js 24、Vite+、Android SDK 36、JDK 17+ 和 PowerShell**。

```powershell
git clone https://github.com/chxcodepro/claude-privacy.git
cd claude-privacy
vp install
./scripts/package.ps1
```

产物：`artifacts/claude-privacy-0.1.21-dev.zip`，包含 WebUI、Hook APK 和模块脚本。Hook APK 当前使用本机 debug 签名。

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

APK 接口识别与编码替换的定向检查及历史证据见[验证记录](docs/VERIFICATION.md)。Java 层网络拦截的覆盖范围有限，SDK 原生网络路径、WebView 实际页面以及登录、语音和推送等行为仍需目标设备验证。

提交问题时请附模块版本、Hook 版本、Claude 版本、Android 系统版本、复现步骤及导出的诊断日志。请先检查附件，移除账号、密钥和其他个人信息。

- [提交问题](https://github.com/chxcodepro/claude-privacy/issues)
- [查看更新记录](CHANGELOG.zh.md)

## 友情链接

- [LINUX DO](https://linux.do/)
