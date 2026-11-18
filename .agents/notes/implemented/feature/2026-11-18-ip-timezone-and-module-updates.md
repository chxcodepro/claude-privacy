# Agent Note: 按 IPv4 显示时区与 GitHub 模块更新

Status: implemented

## Problem

设置页曾把手动预设显示成自动时区，删除 IP 后也可能显示旧结果。模块版本与安装包尚无公开的更新来源。

## Decision

地理时区与允许的 IPv4 一起保存，并随该地址删除。自动模式用仍在允许列表中的查询结果或 Claude 实测；手动时区保存为独立预设。旧配置没有 `ipTimezones` 时，读作空记录。

设置页和 SukiSU 读取同一个 GitHub `update.json`。WebUI 按 `versionCode` 决定是否提示更新，下载限制为本仓库版本对应的 ZIP；用户从浏览器下载后在 SukiSU 确认安装。打包核对模块版本、更新清单和 ZIP 命名。

## Alternatives considered

- 用全局时区作自动模式的占位：界面易有值，但无法说明这个时区对应哪个出口，也不会随地址删除。
- 自动下载并用 Root 安装：操作更少，但会在检查更新时引入不必要的设备写入和恢复负担。

## Consequences

自动时区和更新检查均会在缺少有效结果时显示可操作的状态。后续发布须同步 `module/module.prop` 与 `update.json`，并把对应 ZIP 上传到 Release。装机后的实际出口仍由 Claude 进程核验。
