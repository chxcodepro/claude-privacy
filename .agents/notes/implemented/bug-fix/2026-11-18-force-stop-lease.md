# Agent Note: 划掉后台后的核验与规则回收

Status: implemented

## Problem

PJZ110 从最近任务划掉 Claude 后，系统通过 o-stop 强制结束进程。桥接仍注册，但临时 URI 授权消失，再启动在 Application.attach 阶段报 `Unknown authority`。前一次 IPv4 规则仍为 RETURN，状态文件仍为放行。

## Decision

在[原有双层门禁](../architecture/2026-11-17-claude-privacy-gate.md)上，准备桥接时只给 Claude 发放单个可持久化的读取授权，并在首次启动时保留它。Provider 继续以调用 UID 执行访问检查。AOSP 的强停路径清理临时授权，详见 [ActivityManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerService.java)。

每次探测由内核给出的 Binder 调用 PID 加上 `/proc` 的进程启动时间持有唯一租约；放行要求仍是同一个活进程。模块的 boot 服务维护独立 watchdog，约每 0.5 秒检查进程退出。正常离开前台仍立即收回；被强杀时由 watchdog 在共享文件锁内二次核对后收回并更新状态。旧进程的退出不会清除不同 PID 的新租约。观察与替身读回仍可用于回看；在下一轮启动时清空。

## Alternatives considered

- 安装时强制全局可查询：省去 URI 恢复步骤，但将 Hook 包元数据暴露给不相关应用。对 Claude 保留单个授权即可。
- 只在 Activity 的后台回调清规则：对正常退出延迟最小，但强杀可以跳过回调，真机已有残留证据。
- 用 iptables cgroup 匹配套接字：可直接区分进程，但 PJZ110 的现有 iptables 不提供此匹配，不能作为本模块的通用前置条件。

## Consequences

更新 ZIP 重启并更新 Hook 后第一次打开 Claude 才能保留新授权。正常后台立即收回；强停由守护进程收回，耗时取决于调度与内核规则写入。旧版 Hook 不提供 PID，升级过程中保持锁定直到更新。聊天内的云端时钟不消费本地时区读回，模块不向聊天服务附带该值。
