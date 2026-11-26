# Claude 1.261124.19：新增设备报告与现有保护

对比样本：Claude `1.261120.19`（`26112019`）→ `1.261124.19`（`26112419`），包名均为 `com.anthropic.claude`。同时适配 Claude Duet `1.261125.9`（`26112509`，`com.anthropic.aibo`）。

## 新增的主要采集面是硬件指纹报告

新版增加 `com.anthropic.claude.devicereport.DeviceFacts`，其序列化器 `h00.c` 声明 26 个可选字段。采集器读取实际硬件、系统文件和 Android API，再按服务端请求的字段集合组成报告。

| 类别 | 字段 |
| --- | --- |
| Android 版本 | `sdk_int` |
| CPU | `cpu_parts`、`cpu_features`、`cpu_cores`、`cpu_max_freqs_khz` |
| GPU | `gles_vendor`、`gles_renderer` |
| 多媒体与传感器 | `codec_names_sha256`、`sensor_names_sha256` |
| 显示设备 | `display_product`、`display_modes` |
| 内核与内存 | `kernel_release`、`total_ram_mb` |
| SoC | `soc_manufacturer`、`soc_model` |
| 设备与构建 | `build_model`、`build_device`、`build_manufacturer`、`build_brand`、`build_hardware`、`build_type`、`build_tags` |
| 系统分区与 ABI | `partition_builds`、`supported_abis` |
| 设备形态 | `feature_pc`、`feature_chromeos` |

`d41.q` 中可见 `/proc/cpuinfo`、CPU 最高频率 sysfs、OpenGL vendor/renderer、媒体编解码器列表、传感器名称与厂商、显示模式、`Os.uname()`、总内存及分区构建信息的读取。传感器摘要由排序后的「名称｜厂商」列表计算；硬件摘要可用于跨次访问的设备匹配。

### 同一链路还会申请带设备属性的证明证书

`h00.p` 将硬件字段与 `play_token` 状态组合到报告，以 `device-report/v1:` 前缀绑定账号上下文、触发时机、挑战和报告摘要。随后 `i63.k7.X` 用别名 `device_report_one_off` 在 AndroidKeyStore 生成 EC 密钥并读取证书链。

服务端请求同时包含 `build_brand`、`build_device`、`build_manufacturer`、`build_model` 且 Android API ≥ 31 时，代码调用 `setDevicePropertiesAttestationIncluded(true)`。该硬件证明路径可带出设备属性；Java `Build` 替身的覆盖范围之外还存在这条路径。

请求体以 `android_device_report` 包装，`DeviceReport` 含 `moment`、`challenge`、`report`、`chain`、`key_error`、`device_properties`、`timings`、`action`、`version`，经既有 `organizations/{organization}/device_check_token` 接口发送。新增点在报告内容与证明流程，原版已存在该接口及 Play Integrity 相关流程。

### 触发由注册、登录、支付配置控制

`DeviceReportConfig` 的三个字段为 `sign_up`、`sign_in`、`purchase`。本地默认值分别为 `false`、`off`、`false`；登录模式另有 `everyone` 和 `no_token`。入口还读取第三方分析可用性、组织与账号状态。实际启用取决于运行期的服务端配置。

v0.1.18 在该配置的反序列化出口强制恢复 `false / off / false`。DEX 控制流确认：三种时机的配置门禁均返回 false 时，报告协程直接结束，跳过挑战请求、硬件采集、证书申请和报告上传。保护始终启用；旧版 Claude 与本次 Duet 样本未含该配置协议，保留原有接口路径。已识别协议的字段或对象结构变化时，会记录错误并锁定联网。

## 另外两项变化

- **Datadog US5 接收端**：新增 `https://browser-intake-us5-datadoghq.com/` 客户端。原有 Datadog 域名列表覆盖其他主机名，v0.1.18 将该主机加入现有 Datadog 开关的 Java DNS／URL 拦截。
- **启动时可信设备登记**：新增 `trusted-device-enroll-when-due` 启动任务和 `trusted_device_enrollment_due` 字段，用于已有可信设备登记能力的启动触发。旧版已含 trusted-device token、硬件密钥、登记与重新证明流程。该账号安全链路沿用原行为，与新增一次性 DeviceReport 分开处理。

## 时区、语言与 IP 保护继续保留

- 时区沿用进程 `TimeZone` 默认值、所选 IP 时区或固定时区，并保留 WebView document-start 替身。
- 语言沿用 `Locale`、Plaid 编码字段与 WebView 替身。
- IP 沿用目标应用自身的 `claude.ai/cdn-cgi/trace` IPv4 核验、允许列表、地区判定、IPv6 拒绝、前后台与网络变化重新核验，以及进程租约到期回收。公网出口由应用的实际网络路由决定。
- Sift 本机地址与 CCT 网络元数据的替换继续按原开关启用。新版四个原有协议目标均各自唯一命中，字段键集合与 26112019 相同。

| 目标 | Claude 26112419 | Duet 26112509 |
| --- | --- | --- |
| Sift 设备属性 | `j19.d` | `ju.d` |
| Sift 应用状态 | `j19.b` | `ju.a` |
| Plaid | `DeviceMetadata$$serializer` | 样本未包含 |
| CCT | `m78.k` | `d40.h` |
| 新设备报告配置 | `h00.g` | 样本未包含 |

Duet 的 Sift 方法分别混淆为 `a()`、`b(encoder, Object)`。模块通过 descriptor 与编码接口的签名定位，避免绑定方法名。

## 权限与其他采集面

两版 Manifest 权限声明逐项相同。定位、日历、通讯录、健康数据、麦克风等已有权限继续受系统授权与应用功能控制。新增硬件报告使用的多数接口和文件读取无需增加运行时权限。

新增的 contact MIME 字符串位于文件类型归类代码 `g05.bb`，对应类型识别表。旧版已存在 `READ_CONTACTS` 声明。`device_app_settings_v0`、`device_app_usage_v0`、`device_status_v0` 三个设备诊断工具在旧版已存在，仍保留原模块记录的覆盖缺口。

## 证据与验收状态

- 原始样本：`D:/project/apk-filter/Claude_1.261124.19.apk`、`extract_1120/base.apk`、`Claude Duet_1.261125.9.apk`。
- 原始 DEX 目标与签名：`D:/project/apk-filter/artifacts/claude-26112419-wire-targets.txt`、`claude-26112019-wire-targets.txt`、`duet-wire-targets.txt`。
- DeviceReport 配置与入口分支：`D:/project/apk-filter/artifacts/claude-26112419-report-dex.txt` 中 `h00.g`、`h00.h`、`h00.p.B/F`；入口先检查配置，允许分支之后才建立挑战请求与采集任务。
- 可读源码：`D:/project/apk-filter/artifacts/claude-26112419/sources/` 下的 `h00/c.java`、`h00/p.java`、`d41/q.java`、`i63/k7.java`、`d02/q.java`、`f44/k.java`、`i64/bf.java`。

本次完成 APK 静态分析与改动代码审查。目标手机上的 LSPosed 注入、新报告关闭日志、硬件证书请求是否停止、两应用独立放行与退出回收、登录／支付行为仍待真机验收。
