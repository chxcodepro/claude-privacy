# Claude Privacy Gate（开发构建）

针对 `com.anthropic.claude` **1.261113.19** 的 SukiSU Ultra 模块与 LSPosed Hook。配置页由 VibeStart CLI 0.1.1 创建的 React SPA 构建，随系统明暗切换。

## 当前实现

- 从桌面打开 Claude，先进入「正在审核公网 IPv4」页面。两个 HTTPS 回显源结果一致且命中白名单时，显示对勾后进入 Claude；失败停在阻断页。离开前台会收回放行，回前台或网络变化时重新核验。允许规则绑定出站接口，IPv6 保持封锁。
- 「添加 → 刷新出口 IPv4」在设置页请求 [Net.Coffee Claude 检测](https://ip.net.coffee/claude/)使用的同一接口 https://claude.ai/cdn-cgi/trace，显示 IPv4 与地区。只有点「更多」才打开检测网页。设置页使用模块的网络路径，Claude 启动时仍由 Claude 进程独立核验。
- 手机 VPN 和软路由共用同一套核验：手机未开 VPN 时，Claude 可经 Wi‑Fi 和软路由核验出口，无需单独开启选项。软路由若按目标分流，请让 Claude API 和 `api4.ipify.org`、`ipv4.icanhazip.com` 使用同一出口。
- 每项设置展示替身值或阻断结果。Android ID、设备档案、运营商、电池、屏幕尺寸、时区和语言可编辑；Android ID 首次打开设置页时随机生成并持久保存。
- 自动时区只显示允许的 IPv4 对应的查询结果；移除该 IPv4 时一起清除。手动时区预设独立保留。
- Hook 覆盖样本中的 Sift 设备／电池／本机 IP／Root 证据载体、Plaid 设备元数据、Sentry 截图／层级／默认 PII 选项、WebView 文档开始时的语言／时区脚本，以及部分 Java 网络调用的遥测域名。

## 构建

需要 Node.js 24、Vite+、Android SDK 36、JDK 17+。在本目录运行：

```powershell
vp install
./scripts/package.ps1
```

生成 `artifacts/claude-privacy-0.1.6-dev.zip`。WebUI 源码在 `apps/web`，LSPosed APK 在 `hook`，SukiSU 脚本在 `module`。Android Hook 使用 Gradle wrapper 单独构建；其 API 82 仅作为编译依赖，运行时由 LSPosed 提供。

模块布局遵循 [KernelSU 模块](https://github.com/tiann/KernelSU/blob/main/website/docs/guide/module.md?plain=1)与 [WebUI](https://kernelsu.org/guide/module-webui.html)文档；Hook 采用 [LSPosed 支持的 Xposed 模块入口](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)。

## 装机验证顺序

ZIP 从 [GitHub Releases](https://github.com/chxcodepro/claude-privacy/releases) 下载。已安装的用户可在设置页点「检查更新」，下载后在 SukiSU 手动安装；更新保留白名单和替身设置。

1. 在 SukiSU Ultra 安装 ZIP 并重启。首次启动默认白名单为空，Claude 会被封锁。
2. 打开模块 WebUI 安装后壳；在 SukiSU Ultra → 超级用户为 `Claude Privacy Hook`（`dev.chx.claudeprivacy`）开启 Root。在 LSPosed 启用后壳并勾选 Claude 作用域，然后回 WebUI 点「检查 Root 授权」。
3. Root 检查通过后会自动查询 IPv4。确认地址后加入白名单并进入设置主页。若已有白名单，WebUI 会直接打开主页；从旧版升级时点「更新 Hook」，然后重新打开 Claude。
4. 手动点 Claude 图标，核对审核、通过和拒绝三种状态。按应用分流时，若设置页查询结果与 Claude 实测不同，以审核页显示的 Claude 实测为准。

配置保存在 `/data/adb/claude_privacy/config.json`，模块升级时保留。移除模块会移除 UID 防火墙规则并尝试卸载 Hook APK；如需恢复 Claude 原行为，禁用模块及其 LSPosed 作用域后重启手机。

## 待真机验收

需要在目标 SukiSU Ultra／LSPosed 环境检查：防火墙规则、Hook 注入、双回显出口、VPN 分流、前后台切换、Android WebView 的时区与语言、SDK 原生网络路径，以及登录、语音、推送等功能。Java DNS／URL 拦截覆盖不到原生网络栈；按域名分流也可能让 Claude API 使用不同于回显源的出口。Sift／Plaid／FCM 阻断可能影响登录、绑卡或推送。调试 APK 使用本机 debug 签名。

区域及自动时区来自 [ipwho.is](https://ipwhois.io/docs)；该数据是 IP 地理位置推测，手动时区始终可覆盖。

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
