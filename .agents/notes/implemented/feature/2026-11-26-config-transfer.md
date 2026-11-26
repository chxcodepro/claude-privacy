# Agent Note: 通过系统文档选择器迁移完整配置

Status: implemented

## Problem

用户需要将旧设备的白名单、替身标识与保护设置沿用到新设备。模块配置位于 Root 私有目录，手动复制不便；新设备也需要在添加 IPv4 之前恢复现有配置。

## Decision

WebUI 提供配置导出与导入，启动 Hook 内的 `ConfigTransferActivity`，复用[诊断导出](2026-11-18-private-diagnostics.md)的 Android 系统文档选择器路径。导出内容是现有 `config.json` 的 JSON 对象，完整保留配置值，无新包装层；运行状态、进程租约、Root 授权和 LSPosed 作用域由新设备建立。

原生导入按模块的默认配置读取当前替身字段与开关集合，复用现有公网 IPv4 判定，校验时区、语言、字段类型和取值范围；输入最多 16 KiB，与 root 配置写入限制一致。缺失 `ipTimezones` 时沿用现有已安装版本兼容规则补空对象。

校验完成后，由原生确认框展示白名单数量、时区、语言和覆盖影响。用户确认后，`control.sh import-config` 先回收当前用户两款应用的联网放行，再复用既有临时文件与原子替换路径保存。取消、文件无效或放行回收失败时保留原配置。目标应用保持进程状态，用户重启后读取新替身值。

返回 WebUI 时重新读取持久配置；内容变化才刷新表单并关闭旧草稿，取消操作保留未保存的编辑。首次配置步骤在 Root 授权后也提供迁移入口。

## Alternatives considered

- WebView Blob 下载与 HTML 文件输入：前端代码较少，但现有目标机文档选择器路径更明确，沿用已有原生导出方式。
- 新备份格式与独立字段映射：可独立演进，但当前完整配置已有稳定结构，直接保存并校验现有 JSON。

## Consequences

导出文件含 IP 白名单与稳定替身标识，保存时提示妥善保管。导入覆盖全部配置并重新锁定两款应用，随后需要重启目标应用。模块和 Hook 均需升级，Hook versionCode 为 16。

界面保留现有 Geist 字体、明暗令牌、分组间距与次级按钮层级，参考本机 Vercel DESIGN 的克制排版。回到页面刷新时保留滚动位置；按钮复用已有 focus、disabled 与 reduced-motion 状态。

本次按要求仅进行改动审查与界面检查，未运行测试。Android 构建及系统文件选择器的真机表现分别记录在 [验证记录](../../../../docs/VERIFICATION.md)。
