# Claude Privacy Gate（开发构建）

针对 `com.anthropic.claude` **1.261113.19** 的 SukiSU Ultra 模块与 LSPosed Hook。配置页由 VibeStart CLI 0.1.1 创建的 React SPA 构建，随系统明暗切换。

## 当前实现

- 从桌面打开 Claude，先审核公网 IPv4。白名单为空时立即锁定，不发出探测。白名单非空时，由 Claude 进程通过其当前网络请求正式服务域名 `claude.ai` 的诊断路径。服务边缘回显的 IPv4 命中白名单、国家码有效且非中国大陆（CN），才显示对勾并进入。回到前台或网络变化时重新核验。允许规则绑定出站接口，IPv6 保持封锁。
- 探测创建独立 HTTPS 客户端，不复用 Claude 登录会话，不发送 Cookie、令牌、账号或设备标识，也不把 IP 交给第三方归属地服务。模块设置页的「刷新出口 IPv4」只读取本机保存的实测结果；「更多」仍可按需打开 [Net.Coffee](https://ip.net.coffee/claude/) 作对比。
- 手机应用级 VPN 和软路由使用同一入口。显式配置的 Android HTTP/SOCKS 代理保持锁定，避免探测绕过代理。按目标域名进一步分流时，核验结果对应 `claude.ai`，其他服务域名可能使用不同出口。
- 每项设置展示替身值或阻断结果。Android ID、设备档案、运营商、电池、屏幕尺寸、时区和语言可编辑；Android ID 首次打开设置页时随机生成并持久保存。
- 自动时区复用该 IP 已保存的时区，或在国家码只对应一个时区时离线确定。多时区地区且无已保存值时使用用户的时区预设；移除 IP 会清除该 IP 的专属时区，独立预设仍保留。
- Hook 覆盖样本中的 Sift 设备／电池／本机 IP／Root 证据载体、Plaid 设备元数据、Sentry 截图／层级／默认 PII 选项、WebView 文档开始时的语言／时区脚本，以及部分 Java 网络调用的遥测域名。

## 构建

需要 Node.js 24、Vite+、Android SDK 36、JDK 17+。在本目录运行：

```powershell
vp install
./scripts/package.ps1
```

生成 `artifacts/claude-privacy-0.1.9-dev.zip`。WebUI 源码在 `apps/web`，LSPosed APK 在 `hook`，SukiSU 脚本在 `module`。Android Hook 使用 Gradle wrapper 单独构建；其 API 82 作为编译需用的接口及测试依赖，设备运行时由 LSPosed 提供。

模块布局遵循 [KernelSU 模块](https://github.com/tiann/KernelSU/blob/main/website/docs/guide/module.md?plain=1)与 [WebUI](https://kernelsu.org/guide/module-webui.html)文档；Hook 采用 [LSPosed 支持的 Xposed 模块入口](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)。

## 装机验证顺序

ZIP 从 [GitHub Releases](https://github.com/chxcodepro/claude-privacy/releases) 下载。已安装的用户可在设置页点「检查更新」，下载后在 SukiSU 手动安装；更新保留白名单和替身设置。

1. 在 SukiSU Ultra 安装 ZIP 并重启。首次启动默认白名单为空，Claude 会被封锁。
2. 打开模块 WebUI 安装后壳；在 SukiSU Ultra → 超级用户为 `Claude Privacy Hook`（`dev.chx.claudeprivacy`）开启 Root。在 LSPosed 启用后壳并勾选 Claude 作用域，然后回 WebUI 点「检查 Root 授权」。
3. 初次设置无实测记录时，手动输入路由或 VPN 的出口 IPv4，确认后进入主页。已有白名单会跳过此步；从旧版升级时点「更新 Hook」。
4. 手动点 Claude 图标，核对审核、通过和拒绝三种状态。若提示地址不在允许列表，可回设置点「刷新出口 IPv4」读取刚才的实测结果，再确认是否加入。

配置保存在 `/data/adb/claude_privacy/config.json`，模块升级时保留。移除模块会移除 UID 防火墙规则并尝试卸载 Hook APK；如需恢复 Claude 原行为，禁用模块及其 LSPosed 作用域后重启手机。

## 待真机验收

0.1.8 Hook 已在 PJZ110 实测：空白名单下启动 Claude 会显示阻断页，要求先添加允许的公网 IPv4。

需要在目标 SukiSU Ultra／LSPosed 环境检查：首次解锁后的桥接准备、Claude 进程的同域名出口、VPN 分流、前后台切换、Android WebView 的时区与语言、SDK 原生网络路径，以及登录、语音、推送等功能。Java DNS／URL 拦截覆盖不到原生网络栈。Sift／Plaid／FCM 阻断可能影响登录、绑卡或推送。调试 APK 使用本机 debug 签名。

## 0.1.4 修复与自检

- 「网络状态」开启后，Google CCT 序列化时省略网络信息字段，包含网络类型和移动子类型；系统网络状态不变。
- WebView 的默认日期格式也使用所选语言，明确指定的语言和时区照常保留。`node scripts/check-webview-profile.mjs` 验证 JavaScript 的语言、时区、夏令时及显式选项。
- 可用 `adb logcat -d -s ClaudePrivacyAudit:I` 查看 Hook 自检。`verified` 表示进程内读回匹配；`cctFixture` 是未发送的 CCT 测试对象，`cctQueuedEvent` 是实际事件的序列化前读回；`webviewDocumentStart=registered` 表示脚本已注册。`javaBlock:*` 只检查 Java 连接对象是否被拒绝，未发出探测请求，也不覆盖 SDK 的原生网络路径。日志不写入替身或真实设备标识。

2026-11-18，PJZ110 上覆盖安装 Hook 0.1.4，保留原 SukiSU ZIP 和配置。Claude 放行并读回 `America/Los_Angeles`。语言、厂商和机型等设备字段、构建指纹、ABI、运营商及 Sentry 三项选项读回匹配；电量和本机 IP 的事件对象也通过。CCT 测试对象与一次实际事件均读回空网络信息。已启用的遥测域名通过 Java 连接对象拦截检查。期间 Android ID 开关关闭；屏幕尺寸、应用包可见性、WebView 实际页面和 SDK 原生上报未取得运行样本。

## 0.1.3 修复

刷新出口在设置页完成，不启动 Claude，不写入时区，也不改白名单。模块使用手机上的系统 curl 强制 IPv4；未提供系统 curl 的设备仍可手动添加地址。Hook APK 沿用 0.1.2。

## 0.1.2 修复

PJZ110 上的 0.1.1 会在 Hook 启动 `su` 失败时显示 `Bundle[{}]`。新版本会区分 Hook 无法运行 `su`、空响应及其他检查错误。只有 Hook 进程实际取得 UID 0 才会继续检测 Claude 出口。

## 0.1.1 修复

- 使用当前 Android 用户下的显式 Activity 入口，读取启动命令中的实际错误。
- 将 DNS 与 IP 时区请求移出应用主线程；允许 VPN Fake-IP DNS 地址参与探测，并一次提交完整的 IPv4 探测规则。
- 通过受控 URI 授权让 Claude 能发现桥接组件，检查 Hook root 通道；详细错误回传设置页。
- 刷新显示等待状态、结果与超时，识别旧 Hook 并提示更新。回到设置页后保留刚完成的测量值。

Android 的[主线程联网异常](https://developer.android.com/reference/android/os/NetworkOnMainThreadException)与[自动包可见性](https://developer.android.com/training/package-visibility/automatic)文档说明了这两处运行条件。
