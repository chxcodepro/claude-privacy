import { createFileRoute } from "@tanstack/react-router";
import { Dice5, ExternalLink, Plus, RefreshCw, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";

import { Button } from "@claude-privacy/ui/components/button";
import { Input } from "@claude-privacy/ui/components/input";
import { Switch } from "@claude-privacy/ui/components/switch";

import { DiagnosticsButton } from "#src/components/diagnostics-button.tsx";
import { TimezonePicker } from "#src/components/timezone-picker.tsx";
import { randomizeBuild, randomizeDevice } from "#src/device-profile.ts";
import {
  checkHookRoot,
  getStatus,
  getHookVersion,
  getWatchdogStatus,
  getSystemRelease,
  installHook,
  loadConfig,
  openMore,
  readExitCandidate,
  saveConfig,
  startWatchdog,
  statusForAllowlist,
  requiredHookVersion,
  type GateStatus,
  type ProbeResult,
} from "#src/module-bridge.ts";
import {
  ipTimezone,
  isPublicIPv4,
  parseConfig,
  spoofGroups,
  spoofPreview,
  utcOffset,
  type PrivacyConfig,
} from "#src/privacy-config.ts";

type EditableKey = "androidId" | "device" | "build" | "carrier" | "battery" | "screen";
type ValueKey = keyof PrivacyConfig["values"];
const editFields: Record<EditableKey, readonly [ValueKey, string][]> = {
  androidId: [["androidId", "Android ID"]],
  device: [
    ["manufacturer", "厂商"],
    ["brand", "品牌"],
    ["model", "机型"],
    ["device", "设备代号"],
    ["product", "产品代号"],
    ["buildFingerprint", "构建指纹"],
  ],
  build: [["buildFingerprint", "构建指纹（{release} 表示设备系统版本）"]],
  carrier: [
    ["carrierName", "运营商名称"],
    ["simCountry", "SIM 国家代码"],
    ["networkCountry", "网络国家代码"],
  ],
  battery: [["batteryPercent", "电量 %"]],
  screen: [
    ["screenWidth", "宽度 px"],
    ["screenHeight", "高度 px"],
  ],
};

const mainlandLocked = "中国大陆公网 IPv4 已锁定";
const reasonText: Record<string, string> = {
  boot: "等待启动核验",
  startup: "正在启动核验",
  probe_failed: "出口检测失败",
  ip_denied: "IPv4 不在允许列表",
  mainland_ip: mainlandLocked,
  ip_region_unknown: "未能确认 IP 归属地",
  network_changed: "网络已变化",
  background: "Claude 已离开前台",
  process_exited: "Claude 已退出，已收回放行",
  watchdog_unavailable: "出口守护未就绪",
  watchdog_recovered: "守护已就绪 · 等待重新核验",
  unsupported_version: "旧版 Hook 限制，请更新 Hook",
  hook_failed: "Hook 初始化失败",
  checking: "正在检测出口",
};

function gateForDisplay(
  allowedIps: string[],
  status: GateStatus,
  watchdog: "running" | "stopped" | null,
) {
  if (watchdog === "running" && status.reason === "watchdog_unavailable")
    status = { ...status, state: "unconfigured", reason: "watchdog_recovered", detail: undefined };
  return statusForAllowlist(
    allowedIps,
    watchdog !== "running" && status.state === "allowed"
      ? { state: "unconfigured", reason: "watchdog_unavailable" }
      : status,
  );
}

function HomePage() {
  const [config, setConfig] = useState<PrivacyConfig | null>(null);
  const [recordedStatus, setStatus] = useState<GateStatus>({ state: "unconfigured" });
  const [watchdog, setWatchdog] = useState<"running" | "stopped" | null>(null);
  const [startingWatchdog, setStartingWatchdog] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [detecting, setDetecting] = useState(false);
  const [adding, setAdding] = useState(false);
  const [probe, setProbe] = useState<ProbeResult | null>(null);
  const [manualIp, setManualIp] = useState("");
  const [timezone, setTimezone] = useState("UTC");
  const [language, setLanguage] = useState("en-US");
  const [systemRelease, setSystemRelease] = useState("");
  const [hookVersion, setHookVersion] = useState(0);
  const [setupComplete, setSetupComplete] = useState(
    () => localStorage.getItem("claudePrivacy.setupComplete") === "1",
  );
  const [rootReady, setRootReady] = useState(false);
  const hookInstalled = hookVersion >= requiredHookVersion;
  const [editing, setEditing] = useState<EditableKey | null>(null);
  const [draft, setDraft] = useState<PrivacyConfig["values"] | null>(null);
  const [editError, setEditError] = useState("");

  async function reload(notify = false) {
    setBusy(true);
    setError("");
    try {
      const [next, nextStatus, release, version, watch] = await Promise.all([
        loadConfig(),
        getStatus(),
        getSystemRelease(),
        getHookVersion(),
        getWatchdogStatus().catch(() => null),
      ]);
      setConfig(next);
      setStatus(nextStatus);
      setTimezone(next.timezone);
      setLanguage(next.language);
      setSystemRelease(release);
      setHookVersion(version);
      setWatchdog(watch);
      if (next.allowedIps.length > 0) {
        localStorage.setItem("claudePrivacy.setupComplete", "1");
        setSetupComplete(true);
      }
      if (notify) {
        const current = gateForDisplay(next.allowedIps, nextStatus, watch);
        toast.success(
          next.allowedIps.length === 0
            ? "待添加 IPv4"
            : `状态已刷新 · ${reasonText[current.reason ?? ""] || (current.state === "allowed" ? "已放行" : "等待启动核验")}`,
        );
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "模块状态读取失败");
    } finally {
      setBusy(false);
    }
  }

  useEffect(() => {
    void reload();
    const onVisible = () => {
      if (document.visibilityState === "visible") {
        void getWatchdogStatus().then(setWatchdog, () => setWatchdog(null));
        void getStatus().then(setStatus, () => {});
      }
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, []);

  async function startExitGuard() {
    setStartingWatchdog(true);
    setError("");
    try {
      await startWatchdog();
      const [watch, nextStatus] = await Promise.all([getWatchdogStatus(), getStatus()]);
      setWatchdog(watch);
      setStatus(nextStatus);
      if (watch !== "running") throw new Error("出口守护尚未运行，请重试。");
      toast.success("出口守护已启动");
    } catch (cause) {
      setWatchdog(await getWatchdogStatus().catch(() => null));
      setError(cause instanceof Error ? cause.message : "出口守护启动失败，请重试。");
    } finally {
      setStartingWatchdog(false);
    }
  }

  async function commit(next: PrivacyConfig, success = "已保存 · Claude 重启后读取新设置") {
    setBusy(true);
    setError("");
    try {
      setConfig(await saveConfig(next));
      toast.success(success);
      return true;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "保存失败");
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function detect() {
    setDetecting(true);
    setError("");
    setProbe(null);
    try {
      setProbe(await readExitCandidate());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "出口查询失败，请重试。");
    } finally {
      setDetecting(false);
    }
  }

  async function addIp(ip: string) {
    if (!config) return;
    if (!isPublicIPv4(ip)) return setError("请输入有效的公网 IPv4");
    const candidate = probe?.ip === ip ? probe : null;
    if (candidate?.countryCode === "CN") return setError(mainlandLocked);
    if (config.allowedIps.includes(ip)) return setError("此 IPv4 已在允许列表中");
    const region = candidate?.region;
    const zone = candidate?.timezone;
    if (
      await commit(
        {
          ...config,
          allowedIps: [...config.allowedIps, ip],
          ipRegions: region ? { ...config.ipRegions, [ip]: region } : config.ipRegions,
          ipTimezones: zone ? { ...config.ipTimezones, [ip]: zone } : config.ipTimezones,
        },
        "IPv4 已添加 · 打开 Claude 核验",
      )
    ) {
      localStorage.setItem("claudePrivacy.setupComplete", "1");
      setSetupComplete(true);
      setAdding(false);
      setManualIp("");
    }
  }

  function beginEdit(key: EditableKey) {
    if (!config) return;
    setEditing(key);
    setDraft(structuredClone(config.values));
    setEditError("");
  }

  async function saveEdit() {
    if (!config || !draft || !editing) return;
    try {
      const next = parseConfig({ ...config, values: draft });
      if (
        editing === "androidId" &&
        draft.androidId !== config.values.androidId &&
        !window.confirm("更换 Android ID 后，Claude 可能需要重新登录。确认保存？")
      )
        return;
      if (await commit(next)) setEditing(null);
      else setEditError("保存失败，请重试");
    } catch (cause) {
      setEditError(cause instanceof Error ? cause.message : "替身值无效");
    }
  }

  async function setupHook() {
    setBusy(true);
    setError("");
    try {
      await installHook();
      setHookVersion(import.meta.env.DEV ? requiredHookVersion : await getHookVersion());
      setRootReady(false);
      if (setupComplete) await confirmRoot();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Hook 安装失败");
    } finally {
      setBusy(false);
    }
  }

  async function confirmRoot() {
    setBusy(true);
    setError("");
    try {
      await checkHookRoot();
      setRootReady(true);
      if (setupComplete) toast.success("Hook 授权正常");
      else await detect();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Root 授权检查失败");
    } finally {
      setBusy(false);
    }
  }

  if (!config)
    return (
      <div className="grid gap-4 py-12 text-center">
        <p className="text-muted-foreground text-sm">{error || "正在读取模块状态…"}</p>
        {error && (
          <div className="mx-auto grid justify-items-center gap-2">
            <Button onClick={() => void reload()} variant="outline">
              重试
            </Button>
            <DiagnosticsButton />
          </div>
        )}
      </div>
    );

  const probeAlreadyAllowed = Boolean(probe && config.allowedIps.includes(probe.ip));
  const ipForm = (
    <>
      <form
        className="flex gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          void addIp(manualIp.trim());
        }}
      >
        <Input
          aria-label="手动输入公网 IPv4"
          inputMode="decimal"
          onChange={(event) => setManualIp(event.target.value)}
          placeholder="手动输入 IPv4"
          value={manualIp}
        />
        <Button disabled={busy || detecting} type="submit" variant="outline">
          {setupComplete ? "添加" : "允许并进入"}
        </Button>
      </form>
      <Button disabled={busy || detecting} onClick={() => void detect()} variant="secondary">
        {detecting ? "正在获取…" : "刷新出口 IPv4"}
      </Button>
      {probe && (
        <div aria-live="polite" className="grid gap-2 text-sm">
          <span className="font-mono font-medium">{probe.ip}</span>
          <div className="flex items-center justify-between gap-3">
            <span className="text-muted-foreground">
              {probe.source === "claude"
                ? `${probe.region} · 实测于 ${new Date(probe.checkedAt * 1000).toLocaleString()}`
                : [probe.region, "Claude 服务可达"].filter(Boolean).join(" · ")}
            </span>
            <Button onClick={() => void openMore()} size="sm" variant="ghost">
              更多 <ExternalLink className="size-3.5" />
            </Button>
          </div>
          {probe.countryCode === "CN" && (
            <p role="alert" className="text-destructive">
              {mainlandLocked}
            </p>
          )}
          <Button
            disabled={busy || probeAlreadyAllowed || probe.countryCode === "CN"}
            onClick={() => void addIp(probe.ip)}
            size="sm"
          >
            {probeAlreadyAllowed ? "已添加" : setupComplete ? "确认添加" : "允许此 IPv4 并进入"}
          </Button>
        </div>
      )}
    </>
  );

  if (!setupComplete) {
    const step = !hookInstalled ? 1 : !rootReady ? 2 : 3;
    return (
      <section
        aria-labelledby="setup-title"
        className="bg-card grid gap-5 rounded-xl border p-5 shadow-xs sm:p-6"
      >
        <div className="grid gap-2">
          <p className="text-muted-foreground text-sm tabular-nums">{step} / 3</p>
          <h1 id="setup-title" className="text-xl font-semibold tracking-tight">
            {step === 1 ? "安装后壳" : step === 2 ? "授予 Root 权限" : "允许出口 IPv4"}
          </h1>
        </div>
        {step === 1 ? (
          <Button disabled={busy} onClick={() => void setupHook()}>
            {busy ? "正在安装…" : "安装 Claude Privacy Hook"}
          </Button>
        ) : step === 2 ? (
          <>
            <p className="text-muted-foreground text-sm leading-relaxed">
              在 SukiSU Ultra 的「超级用户」页给 Claude Privacy Hook 授予 Root。到 LSPosed
              启用该后壳，并勾选 Claude 作用域。
            </p>
            <Button disabled={busy} onClick={() => void confirmRoot()}>
              {busy ? "正在检查…" : "检查 Root 授权"}
            </Button>
          </>
        ) : (
          ipForm
        )}
        {error && (
          <p role="alert" className="text-destructive break-words text-sm">
            {error}
          </p>
        )}
        {hookInstalled && <DiagnosticsButton />}
      </section>
    );
  }

  const emptyAllowlist = config.allowedIps.length === 0;
  const status = gateForDisplay(config.allowedIps, recordedStatus, watchdog);
  const allowed = status.state === "allowed";
  const summary = emptyAllowlist
    ? "待添加 IPv4"
    : allowed
      ? "已放行"
      : status.state === "blocked"
        ? "已锁定"
        : "等待启动核验";
  const statusReason = reasonText[status.reason ?? ""];
  const ipZone = config.timezoneMode === "ip" ? ipTimezone(config, status, probe) : undefined;
  const previewZone = ipZone ?? config.timezone;
  const usesPreset = config.timezoneMode === "ip" && !ipZone;
  const zoneVerified = status.appliedTimezone === previewZone;
  const savedZoneLabel = `${previewZone} · ${utcOffset(previewZone)}`;
  const timezoneStatus = !config.spoof.timezone
    ? "使用系统时区"
    : [
        ipZone
          ? savedZoneLabel
          : timezone !== config.timezone
            ? `当前设置：${savedZoneLabel}`
            : usesPreset
              ? "使用预设时区"
              : null,
        zoneVerified ? "Claude 读回一致" : null,
      ]
        .filter(Boolean)
        .join(" · ");
  const languageVerified = status.appliedLanguage?.toLowerCase() === config.language.toLowerCase();
  const serviceReady = probe?.source === "preflight" && probeAlreadyAllowed;
  const addButton = (
    <Button
      aria-expanded={adding}
      disabled={busy || detecting}
      onClick={() => {
        setAdding(!adding);
        setProbe(null);
        setError("");
      }}
      size="sm"
      variant="outline"
    >
      <Plus className="size-4" />
      添加
    </Button>
  );
  return (
    <div className="grid gap-8 pb-12">
      <section
        aria-label="访问状态"
        className="bg-card border-border grid gap-3 rounded-xl border p-5 shadow-xs"
      >
        <div className="flex items-center justify-between gap-4">
          <div className="flex items-center gap-2.5">
            <span
              aria-hidden="true"
              className={`size-2.5 rounded-full ${emptyAllowlist || status.state === "unconfigured" ? "bg-muted-foreground" : "bg-success"}`}
            />
            <h1 className="text-lg font-semibold tracking-tight">{summary}</h1>
          </div>
          <Button
            aria-label="刷新状态"
            disabled={busy || detecting}
            onClick={() => void reload(true)}
            size="icon"
            variant="ghost"
          >
            <RefreshCw
              className={`size-4 ${busy ? "animate-spin motion-reduce:animate-none" : ""}`}
            />
          </Button>
        </div>
        {!emptyAllowlist && (
          <p className="text-muted-foreground text-sm">
            {status.ip
              ? [status.ip, config.ipRegions[status.ip], statusReason].filter(Boolean).join(" · ")
              : statusReason ||
                (status.state === "unconfigured"
                  ? serviceReady
                    ? "Claude 服务可达 · 等待应用核验"
                    : "打开 Claude 核验当前出口"
                  : "等待核验")}
          </p>
        )}
        <div className="border-border flex items-center justify-between gap-3 border-t pt-3">
          <div className="grid gap-1">
            <span className="text-sm font-medium">出口守护</span>
            <span
              role="status"
              className={`text-sm ${watchdog === "running" ? "text-success" : "text-muted-foreground"}`}
            >
              {watchdog === "running" ? "运行中" : watchdog === "stopped" ? "已停止" : "未确认"}
            </span>
          </div>
          <Button
            disabled={busy || startingWatchdog || watchdog === "running"}
            onClick={() => void startExitGuard()}
            size="sm"
            variant="outline"
          >
            {startingWatchdog ? "正在启动…" : "启动守护"}
          </Button>
        </div>
        <DiagnosticsButton />
      </section>

      {!emptyAllowlist && status.detail && !error && (
        <p role="status" className="text-destructive break-words text-sm">
          {status.detail}
        </p>
      )}

      {!hookInstalled && (
        <section className="border-border bg-muted/60 flex items-center justify-between gap-3 rounded-lg border p-4">
          <p className="text-sm">LSPosed Hook</p>
          <Button disabled={busy} onClick={() => void setupHook()} size="sm">
            {hookVersion > 0 ? "更新 Hook" : "安装"}
          </Button>
        </section>
      )}
      {!emptyAllowlist &&
        hookInstalled &&
        !rootReady &&
        (recordedStatus.reason === "boot" || recordedStatus.state === "unconfigured") && (
          <Button disabled={busy} onClick={() => void confirmRoot()} size="sm" variant="outline">
            {busy ? "正在检查…" : "检查 Hook 授权"}
          </Button>
        )}

      {error && (
        <p role="alert" className="text-destructive bg-destructive/8 rounded-lg px-3 py-2 text-sm">
          {error}
        </p>
      )}

      <section className="grid gap-4" aria-labelledby="allowed-title">
        <div className="flex items-center justify-between gap-3">
          <h2 id="allowed-title" className="text-base font-semibold tracking-tight">
            允许的公网 IPv4
          </h2>
          {!emptyAllowlist && addButton}
        </div>
        {emptyAllowlist ? (
          <div className="border-border flex items-center justify-between gap-3 rounded-lg border border-dashed px-4 py-3">
            <p className="text-muted-foreground text-sm">暂无允许的 IPv4</p>
            {addButton}
          </div>
        ) : (
          <ul className="border-border divide-border divide-y rounded-lg border">
            {config.allowedIps.map((ip) => (
              <li className="flex items-center gap-3 px-3 py-2.5" key={ip}>
                <div className="grid gap-0.5">
                  <span className="font-mono text-sm">{ip}</span>
                  {config.ipRegions[ip] && (
                    <span className="text-muted-foreground text-xs">{config.ipRegions[ip]}</span>
                  )}
                </div>
                <Button
                  aria-label={`移除 ${ip}`}
                  className="ml-auto"
                  disabled={busy}
                  onClick={() =>
                    void commit({
                      ...config,
                      allowedIps: config.allowedIps.filter((item) => item !== ip),
                      ipRegions: Object.fromEntries(
                        Object.entries(config.ipRegions).filter(([item]) => item !== ip),
                      ),
                      ipTimezones: Object.fromEntries(
                        Object.entries(config.ipTimezones).filter(([item]) => item !== ip),
                      ),
                    })
                  }
                  size="icon"
                  variant="ghost"
                >
                  <Trash2 className="size-4" />
                </Button>
              </li>
            ))}
          </ul>
        )}
        {adding && <div className="bg-muted/60 grid gap-4 rounded-lg p-4">{ipForm}</div>}
      </section>

      <section className="border-border grid gap-5 border-t pt-7" aria-labelledby="identity-title">
        <h2 id="identity-title" className="text-base font-semibold tracking-tight">
          地区与语言
        </h2>
        <div className="grid gap-3">
          <div className="flex items-center justify-between gap-3">
            <label className="text-sm font-medium" htmlFor="timezone-mode">
              时区
            </label>
            <Switch
              aria-label="伪造时区"
              checked={config.spoof.timezone}
              disabled={busy}
              onCheckedChange={(checked) =>
                void commit({ ...config, spoof: { ...config.spoof, timezone: checked } })
              }
            />
          </div>
          <select
            className="border-input bg-background focus-visible:ring-ring h-10 rounded-md border px-3 text-sm outline-none focus-visible:ring-2"
            disabled={busy}
            id="timezone-mode"
            onChange={(event) =>
              void commit({
                ...config,
                timezoneMode: event.target.value as PrivacyConfig["timezoneMode"],
              })
            }
            value={config.timezoneMode}
          >
            <option value="manual">自定义</option>
            <option value="ip">跟随公网 IPv4</option>
          </select>
          {(config.timezoneMode === "manual" || usesPreset) && (
            <form
              aria-label={usesPreset ? "预设时区设置" : "自定义时区设置"}
              className="flex items-center gap-2"
              onSubmit={(event) => {
                event.preventDefault();
                void commit({ ...config, timezone });
              }}
            >
              <TimezonePicker
                label={usesPreset ? "预设时区" : "自定义时区"}
                onChange={setTimezone}
                value={timezone}
                disabled={busy}
              />
              <Button
                disabled={busy || timezone === config.timezone}
                size="lg"
                type="submit"
                variant="outline"
              >
                保存
              </Button>
            </form>
          )}
          {timezoneStatus && (
            <p role="status" className="text-muted-foreground text-sm">
              {timezoneStatus}
            </p>
          )}
        </div>
        <form
          className="grid gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            void commit({ ...config, language: language.trim() });
          }}
        >
          <div className="flex items-center justify-between gap-3">
            <label className="text-sm font-medium" htmlFor="language">
              语言
            </label>
            <Switch
              aria-label="伪造语言"
              checked={config.spoof.language}
              disabled={busy}
              onCheckedChange={(checked) =>
                void commit({ ...config, spoof: { ...config.spoof, language: checked } })
              }
            />
          </div>
          <div className="flex gap-2">
            <Input
              id="language"
              onChange={(event) => setLanguage(event.target.value)}
              placeholder="en-US"
              value={language}
            />
            <Button disabled={busy || language === config.language} type="submit" variant="outline">
              保存
            </Button>
          </div>
          {!config.spoof.language ? (
            <p className="text-muted-foreground text-sm">使用系统语言 · 预设 {config.language}</p>
          ) : languageVerified ? (
            <p role="status" className="text-muted-foreground text-sm">
              Claude 读回一致
            </p>
          ) : null}
        </form>
      </section>

      {spoofGroups.map((group) => (
        <section
          aria-label={group.title}
          className="border-border grid gap-1 border-t pt-6"
          key={group.title}
        >
          <h2 className="mb-2 text-base font-semibold tracking-tight">{group.title}</h2>
          {group.items.map(([key, label]) => {
            const editable = key in editFields ? (key as EditableKey) : null;
            return (
              <div className="grid gap-2 py-3" key={key}>
                <div className="flex items-center justify-between gap-4">
                  <p className="text-sm font-medium">{label}</p>
                  <Switch
                    aria-label={label}
                    checked={config.spoof[key]}
                    disabled={busy}
                    onCheckedChange={(checked) =>
                      void commit({ ...config, spoof: { ...config.spoof, [key]: checked } })
                    }
                  />
                </div>
                <div className="flex min-w-0 items-start justify-between gap-2">
                  <p className="text-muted-foreground min-w-0 break-all text-sm">
                    {spoofPreview(key, config, systemRelease || "<系统版本>")}
                  </p>
                  {editable && (
                    <Button
                      aria-label={`${editing === editable ? "收起" : "编辑"}${label}`}
                      disabled={busy}
                      onClick={() =>
                        editing === editable ? setEditing(null) : beginEdit(editable)
                      }
                      size="sm"
                      variant="ghost"
                    >
                      {editing === editable ? "收起" : "编辑"}
                    </Button>
                  )}
                </div>
                {editable && editing === editable && draft && (
                  <form
                    className="bg-muted/60 grid gap-3 rounded-lg p-4"
                    onSubmit={(event) => {
                      event.preventDefault();
                      void saveEdit();
                    }}
                  >
                    {editFields[editable].map(([field, fieldLabel]) => (
                      <div className="grid gap-1.5 text-sm" key={field}>
                        <label htmlFor={`spoof-${field}`}>{fieldLabel}</label>
                        {field === "buildFingerprint" ? (
                          <textarea
                            id={`spoof-${field}`}
                            className="border-input dark:bg-input/30 focus-visible:border-ring focus-visible:ring-ring/50 min-h-16 resize-y break-all rounded-lg border bg-transparent px-2.5 py-2 font-mono text-sm outline-none focus-visible:ring-3"
                            onChange={(event) =>
                              setDraft({ ...draft, buildFingerprint: event.target.value })
                            }
                            rows={2}
                            value={draft.buildFingerprint}
                          />
                        ) : (
                          <Input
                            id={`spoof-${field}`}
                            onChange={(event) =>
                              setDraft({
                                ...draft,
                                [field]:
                                  typeof draft[field] === "number"
                                    ? Number(event.target.value)
                                    : event.target.value,
                              })
                            }
                            type={typeof draft[field] === "number" ? "number" : "text"}
                            value={draft[field]}
                          />
                        )}
                      </div>
                    ))}
                    {editable === "androidId" && (
                      <Button
                        onClick={() =>
                          setDraft({
                            ...draft,
                            androidId: Array.from(
                              crypto.getRandomValues(new Uint8Array(8)),
                              (byte) => byte.toString(16).padStart(2, "0"),
                            ).join(""),
                          })
                        }
                        size="sm"
                        type="button"
                        variant="outline"
                      >
                        重新生成
                      </Button>
                    )}
                    {(editable === "device" || editable === "build") && (
                      <Button
                        disabled={busy}
                        onClick={() => {
                          setDraft(
                            editable === "device" ? randomizeDevice(draft) : randomizeBuild(draft),
                          );
                          setEditError("");
                        }}
                        size="sm"
                        type="button"
                        variant="outline"
                      >
                        <Dice5 />
                        {editable === "device" ? "随机生成设备与构建" : "随机生成构建指纹"}
                      </Button>
                    )}
                    {editError && (
                      <p className="text-destructive text-sm" role="alert">
                        {editError}
                      </p>
                    )}
                    <Button disabled={busy} size="sm" type="submit">
                      保存替身值
                    </Button>
                  </form>
                )}
              </div>
            );
          })}
        </section>
      ))}
    </div>
  );
}

export const Route = createFileRoute("/")({ component: HomePage });
