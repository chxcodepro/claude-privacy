# 验证记录

本文件保留各版本的验证结果和早期修复依据。当前使用说明见 [README](../README.md)，版本变化见[更新记录](../CHANGELOG.zh.md)。

## v0.1.15 开发版

- 构建、日志脱敏与轮转检查、状态恢复和导出入口的定向检查已通过。
- 已检查移动尺寸下的明暗界面，以及日志导出失败提示与重试入口。
- 守护实际退出原因仍待现场日志确认；目标手机的系统文件选择器保存流程尚待验证。

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
