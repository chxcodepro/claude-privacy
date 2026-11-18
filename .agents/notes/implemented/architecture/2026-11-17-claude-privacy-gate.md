# Agent Note: Claude 隐私模块的双层门禁

Status: implemented

## Problem

Claude 的设备元数据采集与联网可能早于首个 Activity。仅在界面出现后检查公网 IP，会留下启动期出口；仅修改 Java 返回值也覆盖不到应用的所有网络路径。配置需要在 SukiSU WebUI、root 防火墙和 LSPosed 进程间共享。

## Decision

启动审核的界面与时序由 [桌面启动审核](2026-11-18-launch-assessment.md) 接管；本篇保留防火墙和桥接契约。该版本将以下原先在 Application.attach 前的出口核验移到审核页期间，Application.attach 前仍封锁网络并初始化 Hook。

以下双回显、第三方归属地和 WebUI 查询是 0.1.8 及以前的行为；当前探测契约见 [Claude 进程的匿名同域出口核验](2026-11-18-claude-edge-exit.md)。

SukiSU 模块保存一份 `/data/adb/claude_privacy/config.json`，WebUI 经固定参数的 `control.sh` 写入；LSPosed Hook 通过只接受 Claude UID 的 `BridgeProvider` 读取配置并请求 root 防火墙操作。模块在启动阶段为 Claude UID 插入 IPv4/IPv6 OUTPUT 规则。Hook 在 `Application.attach` 前从 Claude UID 的 HTTPS 连接取得两个一致的公网 IPv4，并由 provider 核对白名单。实测值连同时间写入状态。允许规则绑定当时的出站接口；IPv6 保持拒绝。进入后台时重新封锁，回到前台与网络变化后重新核验。

IP 自动时区使用第三个 HTTPS 地理位置请求，完成时区及 WebView document-start 脚本更新后才放行。替身值由 WebUI 展示并持久化；本次样本的 `ht0`／`fo0`／`com.plaid.internal.s1` 构造载体按字段覆盖。样本 versionCode 固定为 `26111319`，版本变化时启动门禁保持封锁，直到重新审计映射。

DNS 和 IP 地理位置 HTTP 请求在工作线程执行，避免 Android 的主线程网络限制。探测规则包含目标域名解析出的全部 IPv4，接受 VPN 的 Fake-IP 地址；公网白名单仅接受回显的公网地址。规则由 `iptables-restore --noflush` 一次替换，保留兜底拒绝。桥接 provider 在 root 发起的检查或开机准备中授予 Claude 受控 URI 访问权，以建立 Android 11+ 的包可见性。

「刷新出口 IPv4」从模块网络路径读取 Net.Coffee 页面使用的 `claude.ai/cdn-cgi/trace`，用系统 curl 强制 IPv4；查询结果仅供确认添加，与门禁的 Claude 实测分开。查询本身不启动 Claude、不写入时区或白名单。自动启动并轮询结果曾让用户无意切换到 Claude，因而移除；用户手动打开 Claude 时仍触发原核验。

健康检查以 Hook 自身运行的 `id -u` 为准。PJZ110 上 `ProcessBuilder("su")` 启动失败会抛出 `IOException`，Binder 无法将该异常直接传给 `content call`，后者显示空 Bundle。诊断通过 Bundle 返回错误类别与详情；ADB shell 的 Root 不参与通过判定。

Google CCT 的两个网络属性在发送前合并为可选字段；保护开启时，Hook 在此处省略整个字段，已排队的事件也适用。旧的通用元数据 setter 绕过了直接写入的两个键；仅删除键也会让读取端使用默认枚举。进程读回只记录匹配结果；合成对象、实际事件、脚本注册和 Java 拦截分别记账，避免将局部检查当成完整网络验证。

## Alternatives considered

- 只用 WebUI 的公网 IP 查询：界面所在 UID 可能走不同的 VPN／代理规则，无法作为 Claude 的放行依据。
- 只用 LSPosed 在首屏关闭 Activity：应用初始化和后台组件仍可能先联网。
- 直接修改系统全局属性：影响其他应用，并让运行所需的 SDK／ABI 判断承担额外风险。

## Consequences

单一 JSON 配置跨三个层使用，root 规则提供启动前的封锁，Hook 提供同 UID 的出口读数和字段级替身。代价是安装时需要启用 LSPosed 作用域并给 Hook APK 授予 SukiSU root；无此条件时 Claude 保持封锁。三个探测目标的共享 IP、按域名分流、原生 SDK 网络栈、回到前台的慢网络时延，以及 Android WebView 的实际注入效果仍需在目标设备逐项验证。当前 ZIP 是开发构建，不据此宣称真机防泄漏验收通过。
