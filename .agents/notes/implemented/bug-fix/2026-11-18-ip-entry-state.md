# Agent Note: 添加 IPv4 后以已保存名单显示状态

Status: implemented

## Problem

设置页没有 Claude 实测记录时缺少可添加的地址。刚添加第一个 IPv4 后，旧的 `no_allowed_ips` 仍用于状态卡和刷新结果，出现名单有地址却提示先添加的矛盾。

## Decision

保留[Claude 同进程匿名核验](../architecture/2026-11-18-claude-edge-exit.md)。设置页在没有实测记录时，使用独立网络查询只提取 `claude.ai` 回显的 IPv4。查询不启动 Claude、不带登录信息，结果只作为需确认添加的候选，不写入归属地、时区或放行状态。

添加后读回已写入的名单；名单与提交一致才显示添加成功。状态卡和刷新提示共用一个基于当前名单的判定：之前的空名单或不匹配结果与现有名单矛盾时显示等待核验，地区拒绝等真实限制继续显示。

0.1.11 扩展为[Claude 服务预检和配置读回](../feature/2026-11-18-preflight-and-readback.md)，仍保留名单保存后的读回确认。

## Alternatives considered

- 添加后启动 Claude 立即重测：会把用户从设置页带到 Claude。保留用户手动启动，应用按原规则读取新名单。
- 直接使用网络候选放行：设置进程可能不走 Claude 的 VPN 路由，候选和放行证据必须分开。

## Consequences

配置页可在没有 Claude 实测记录时提供候选，且不会把候选当成已放行。设置进程若使用不同出口或查询失败，仍可手动填写，最终以 Claude 启动时核验为准。
