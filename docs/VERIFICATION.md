# 验证记录

本文件保留各版本的验证结果和早期修复依据。当前使用说明见 [README](../README.md)，版本变化见[更新记录](../CHANGELOG.zh.md)。

## v0.1.18：Claude 26112419 与 Duet 26112509

新增权限对比、硬件报告、设备属性证明和协议目标记录在 [APK 审查](APK-1.261124.19.md)。原始 DEX 确认新报告配置 `h00.g` 唯一命中，三个配置字段对应 `h00.h` 的两个布尔值与一个字符串；报告入口在挑战请求与采集任务之前检查这些值。Duet 的 descriptor 与编码方法签名满足现有编码替换契约。

改动代码审查覆盖包名作用域、桥接调用者、独立防火墙与租约、旧路径保留、配置关闭及失败锁定。按本次要求未运行测试；回归用例随代码更新。Hook APK 与模块 ZIP 由项目构建脚本生成。真机 LSPosed 生效、两应用并行状态、设备报告停止以及登录／支付业务结果待验收。

## v0.1.17：兼容 Claude 1.261120.19

对 `Claude_1.261120.19.apks`（versionCode `26112019`）用 v0.1.16 的同一识别函数复查，四个保护目标仍各自唯一命中：

| 协议目标                              | 26111319                                                         | 26111719 | 26112019 |
| ------------------------------------- | ---------------------------------------------------------------- | -------- | -------- |
| Sift AndroidDeviceProperties 序列化器 | `gt0`                                                            | `d30.d`  | `u30.d`  |
| Sift AndroidAppState 序列化器         | `eo0`                                                            | `d30.a`  | `u30.a`  |
| Plaid DeviceMetadata 序列化器         | `com.plaid.internal.networking.model.DeviceMetadata$$serializer` | 同左     | 同左     |
| CCT LogEvent 编码器                   | `v04`                                                            | `ng0.h`  | `lh0.h`  |

受保护字段键集合与 26111719 逐项相同（DEVICE 17 键、STATE 9 键、Plaid 10 键），`getDescriptor()`、静态自类型单例、`serialize(Lqr0/d;Ljava/lang/Object;)V` 与 CCT 唯一 `(Ljava/lang/Object;Ljava/lang/Object;)V` 入口均满足适配器契约。只有混淆类名随 R8 重新分配而移动，运行期按协议名称与字段键解析，因此 `WireTarget` 标记集、`wireReplacements` 与 Hook 二进制（versionCode 14）都无需改动。

新包仍有一处本模块不覆盖的采集面：`device_app_settings_v0`、`device_app_usage_v0`、`device_status_v0` 三个设备诊断工具，读取已装应用清单、各应用权限状态、使用时长/夜间活动/通知量/流量与电池热状态。它们不经过 Sift／Plaid／CCT 三处编码边界，现有拦截策略不会作用于这些工具结果。

检查方式：apktool 3.0.3 全量反编译后，按 `WireTarget.markers` 在 smali 中复现 `findWireTargets` 的取值规则（`<clinit>`，以及序列化器类的 `<init>`／`getDescriptor` 字符串常量），对 26111719 跑同一脚本可复现 `d30.d`／`d30.a`／`ng0.h`，与下方 v0.1.16 记录一致。

## v0.1.16：按包名启用与编码接口识别

直接读取两份实际 APK 的 DEX，项目内同一识别函数获得下列目标，无应用版本号分支：

| 协议目标                              | Claude 26111319                                                  | Claude 26111719 |
| ------------------------------------- | ---------------------------------------------------------------- | --------------- |
| Sift AndroidDeviceProperties 序列化器 | `gt0`                                                            | `d30.d`         |
| Sift AndroidAppState 序列化器         | `eo0`                                                            | `d30.a`         |
| Plaid DeviceMetadata 序列化器         | `com.plaid.internal.networking.model.DeviceMetadata$$serializer` | 同左            |
| CCT LogEvent 编码器                   | `v04`                                                            | `ng0.h`         |

检查同时确认：序列化单例、方法签名、descriptor 的字段名称/数量读取接口、结构编码入口，以及 CCT 上下文和字段描述符均满足适配器契约。JVM 中的编码器检查覆盖字段重排、混淆方法名、整数参数顺序、CCT 链式调用、独立开关、未选择字段透传，以及异常传播。

新包 JADX 输出有部分解码错误；上述识别和接口检查使用 dexlib2 直接读取原始 DEX，不依赖这些 Java 输出。扫描耗时的主机记录仅反映静态检查过程。

手机当前未连接。目标进程中的扫描耗时、LSPosed 注入、实际生效读回、首次启动和划掉后台后的核验仍待真机验证。既有出口守护异常也保留现场日志排查项。

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
