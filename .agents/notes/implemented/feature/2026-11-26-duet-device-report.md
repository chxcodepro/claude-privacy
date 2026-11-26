# Agent Note: Duet 作用域与设备报告采集入口

Status: implemented

## Problem

Claude Duet 使用独立包名和 UID，Sift 序列化方法也被重命名。共用单套防火墙状态会使两应用互相撤销规则。Claude 26112419 新增 DeviceFacts 硬件报告及一次性设备属性证明，现有 Sift／Plaid／CCT 编码边界覆盖不到该链路。用户要求继续保留时区、语言与 IP 保护，并授权拦截新报告。

## Decision

`TargetApp` 保存两应用经 APK 审查的协议集合，LSPosed 作用域和 provider 可见性同时包含两包名。桥接根据 Binder UID 对应的包名选择 root 操作目标，调用者提供的参数仅承载该目标内的操作。

共享 `config.json` 和默认配置；`targets.sh` 统一包名、目录与链名。Claude 继续使用根数据目录和 `CP_CLAUDE`，保留已安装版本的入口与状态路径；Duet 使用 `aibo/` 子目录和 `CP_AIBO`。各应用独立持有 firewall/watch/supervisor 锁、UID、观察记录和进程租约。开机、手动恢复与卸载遍历两款已安装应用；设置页读取最近更新的核验状态，守护状态要求当前用户已安装的目标均在运行。

主进程持有启动审核与生命周期门禁。其他普通进程安装隐私适配器，沿用该 UID 的防火墙；无应用权限及桥接授权的隔离 PDF 渲染进程跳过 Hook 初始化，避免后台渲染覆盖主进程租约。

同次 DEX 扫描以 `com.anthropic.claude.devicereport.DeviceReportConfig` 及 `sign_up`、`sign_in`、`purchase` 定位新版配置序列化器。在反序列化出口将两个布尔字段置 false、登录模式置 `off`，使报告入口在建立挑战请求前结束。该保护始终启用，覆盖新硬件采集、报告上传及该报告的一次性证明；旧版 Claude 与本次 Duet 样本均未包含该协议，继续沿用其现有路径。已识别协议字段错误、候选歧义或配置对象契约变化时，沿用原隐私失败锁定路径。

Datadog 现有开关增加实际新主机 `browser-intake-us5-datadoghq.com`，保留既有开关含义。

## Alternatives considered

- 只增加包名：改动少，但桥接拒绝 Duet UID，单状态防火墙互相覆盖，且 Duet 的方法名已变化。
- 在 DeviceReport 编码时清空硬件与证书字段：可复用编码替换，但采集与证书申请已发生，报告摘要与证明也会失配；选择在采集配置处关闭入口。
- 全局拦截密钥证明 API：覆盖面广，但会影响既有可信设备登录及其他账号安全功能；采用本次新增报告的专属配置入口。

## Consequences

时区、语言、IP 白名单、地区限制与进程租约策略保持原行为。关闭新报告可能影响注册、登录或支付风控。旧有设备诊断工具和账号可信设备流程保留各自的行为与已知覆盖范围。

APK 权限差异、协议、字段与原始 DEX 门禁分支已经静态审查，详见 [APK 审查](../../../../docs/APK-1.261124.19.md)。新增回归用例覆盖配置关闭、混淆方法定位和包名协议选择，shell 夹具加入双应用状态隔离场景。本次按要求仅作改动审查，未执行测试，目标设备尚未验收。打包脚本使用项目已安装的 Vite+，遵循工作区锁定的工具链版本。
