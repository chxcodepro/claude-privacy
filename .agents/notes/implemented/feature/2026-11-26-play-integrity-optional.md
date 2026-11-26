# Agent Note: 默认关闭的 Play Integrity 拦截

Status: implemented

## Problem

用户授权加入 Play Integrity 拦截，同时明确要求不建议开启。Claude 与 Duet 的 Google SDK 和应用序列化器均已混淆，验证涉及登录和购买，默认开启会改变现有账号流程。

## Decision

使用独立 `spoof.playIntegrity` 布尔开关，默认 false。旧配置与旧备份缺失该字段时补 false；显式 true 保留。设置页标注不建议开启及登录／支付影响，用户开启前确认；旧 Hook 下该新开关禁用。配置导入若含 true，在覆盖确认中再次展示同一风险。

开启时才扫描并安装 Play Integrity 适配器。DEX 根据 Google SDK 稳定日志标记与方法签名定位 Classic 申请、Standard 预热和 Standard 异步请求。Classic 与预热调用返回新建、已完成的失败 Task；Standard 异步发送前通过该请求的完成源设置失败并跳过 Binder 发送。错误为本地 SecurityException，客户端沿用原失败分支。

同次扫描定位包含 Play Integrity token 键的应用 API 序列化器，在编码边界省略 `play_integrity_token` 与 `play_integrity_standard_token`。OAuth `token`、`join_token`、`login_token` 及 mint-error 字段保持原值。该路径同时覆盖已有请求对象；独立于[设备报告拦截](2026-11-26-duet-device-report.md)。

三个 SDK 入口或 token 载体识别不完整、歧义或运行时契约错误时，沿用隐私失败锁定。开关关闭时跳过额外方法扫描、SDK Hook 与 token 字段过滤，保留应用原行为。所有 Hook 随目标进程初始化，开关变更需重启 Claude／Duet。

## Alternatives considered

- 只修改本地成功状态：客户端状态与服务端验签结果分离，采用明确的失败任务。
- 只阻止 SDK 申请：改动更少，但已构造请求对象仍可能携带证明；同时在现有编码边界省略指定 token 字段。
- 全局修改 Google Task 或阻断 Google 域名：波及登录等其他功能；Hook 限于目标应用内三个 SDK 入口。

## Consequences

服务端决定缺少证明时是否接受业务请求，因此此项默认关闭且不建议开启。时区、语言、IP 白名单与新设备报告策略沿用。配置导入保留用户显式选择，并展示开启风险。

实际 APK 静态接口与 Task 契约见[验证记录](../../../../docs/VERIFICATION.md)。回归用例覆盖默认关闭、旧配置兼容、失败 Task 和 token 字段省略。本次按要求仅审查改动，未运行自动化测试；目标机登录／支付行为待验收。
