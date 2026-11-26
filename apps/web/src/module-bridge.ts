import { exec } from "kernelsu";

import { updateDownload } from "./module-update";
import {
  defaultConfig,
  isPublicIPv4,
  isTimeZone,
  parseConfig,
  type PrivacyConfig,
} from "./privacy-config";

const control = "/data/adb/modules/claude_privacy/bin/control.sh";

export type ProbeResult =
  | { source: "preflight"; ip: string; region?: string; countryCode?: string; timezone?: string }
  | {
      source: "claude";
      ip: string;
      region: string;
      timezone?: string;
      countryCode: string;
      checkedAt: number;
    };

export interface GateStatus {
  state: "blocked" | "allowed" | "unconfigured";
  ip?: string;
  countryCode?: string;
  reason?: string;
  checkedAt?: number;
  timezone?: string;
  appliedTimezone?: string;
  appliedLanguage?: string;
  detail?: string;
  updatedAt?: number;
  revision?: number;
}

export const requiredHookVersion = 17;

export function statusForAllowlist(allowedIps: readonly string[], status: GateStatus): GateStatus {
  const containsObservedIp = status.ip !== undefined && allowedIps.includes(status.ip);
  if (
    allowedIps.length === 0 ||
    status.reason === "boot" ||
    status.reason === "no_allowed_ips" ||
    (status.reason === "ip_denied" && containsObservedIp) ||
    (status.state === "allowed" && !containsObservedIp)
  )
    return { state: "unconfigured" };
  return status;
}

async function command(arg: string): Promise<string> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    const result = await Promise.race([
      exec(`sh ${control} ${arg}`),
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error("模块命令超时，请检查 SukiSU root 授权")), 15000);
      }),
    ]);
    if (result.errno !== 0)
      throw new Error(result.stderr.trim() || result.stdout.trim() || "模块命令执行失败");
    return result.stdout.trim();
  } finally {
    clearTimeout(timer);
  }
}

async function readConfig(): Promise<PrivacyConfig> {
  if (import.meta.env.DEV) {
    const saved = localStorage.getItem("claude-privacy-preview");
    return saved ? parseConfig(JSON.parse(saved) as unknown) : structuredClone(defaultConfig);
  }
  return parseConfig(JSON.parse(await command("get")) as unknown);
}

export async function loadConfig(): Promise<PrivacyConfig> {
  const config = await readConfig();
  if (config.values.androidId) return config;
  const generated = Array.from(crypto.getRandomValues(new Uint8Array(8)), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
  return saveConfig({ ...config, values: { ...config.values, androidId: generated } });
}

export async function saveConfig(config: PrivacyConfig): Promise<PrivacyConfig> {
  parseConfig(config);
  if (import.meta.env.DEV) {
    localStorage.setItem("claude-privacy-preview", JSON.stringify(config));
    return readConfig();
  }
  const bytes = new TextEncoder().encode(JSON.stringify(config));
  const encoded = btoa(String.fromCharCode(...bytes));
  await command(`set ${encoded}`);
  const saved = await readConfig();
  if (
    saved.allowedIps.length !== config.allowedIps.length ||
    saved.allowedIps.some((ip, index) => ip !== config.allowedIps[index])
  )
    throw new Error("允许的 IPv4 保存后与提交不一致，请刷新状态后重试。");
  return saved;
}

export async function getStatus(): Promise<GateStatus> {
  if (import.meta.env.DEV && !("ksu" in globalThis)) return { state: "unconfigured" };
  return readStatus();
}

export async function getWatchdogStatus(): Promise<"running" | "stopped"> {
  if (import.meta.env.DEV && !("ksu" in globalThis)) return "stopped";
  const status = await command("watch-status");
  if (status === "running" || status === "stopped") return status;
  throw new Error("无法确认出口守护状态");
}

export async function startWatchdog(): Promise<void> {
  try {
    await command("watch-start");
  } catch (error) {
    if (error instanceof Error && error.message === "WATCHDOG_UNAVAILABLE")
      throw new Error("出口守护启动失败，请检查 SukiSU 权限后重试。", { cause: error });
    throw error;
  }
}

export async function exportDiagnostics(): Promise<void> {
  await command("export-diagnostics");
}

export async function transferConfig(operation: "export" | "import"): Promise<void> {
  if (import.meta.env.DEV && !("ksu" in globalThis))
    throw new Error("请在手机的模块 WebUI 中导入或导出配置。");
  await command(operation === "export" ? "export-config" : "import-config-file");
}

async function readStatus(): Promise<GateStatus> {
  const value: unknown = JSON.parse(await command("status"));
  if (
    !value ||
    typeof value !== "object" ||
    !("state" in value) ||
    !["blocked", "allowed", "unconfigured"].includes(String(value.state))
  ) {
    throw new Error("模块状态数据无效，请重新打开设置页");
  }
  const fields = value as Record<string, unknown>;
  for (const key of [
    "ip",
    "reason",
    "timezone",
    "detail",
    "appliedTimezone",
    "appliedLanguage",
  ] as const) {
    if (key in fields && typeof fields[key] !== "string") throw new Error("模块状态字段无效");
  }
  for (const key of ["checkedAt", "updatedAt", "revision"] as const) {
    if (key in fields && (typeof fields[key] !== "number" || !Number.isFinite(fields[key])))
      throw new Error("模块检测时间无效");
  }
  if ("ip" in value && typeof value.ip === "string" && !isPublicIPv4(value.ip))
    throw new Error("模块返回的公网 IPv4 无效");
  if (
    "countryCode" in fields &&
    (typeof fields.countryCode !== "string" || !/^[A-Z]{2}$/.test(fields.countryCode))
  )
    throw new Error("模块返回的地区无效");
  return value as GateStatus;
}

export async function getSystemRelease(): Promise<string> {
  return import.meta.env.DEV ? "16" : command("release");
}

export async function getHookVersion(): Promise<number> {
  if (import.meta.env.DEV) return 0;
  const value: unknown = JSON.parse(await command("hook-status"));
  if (
    !value ||
    typeof value !== "object" ||
    !("versionCode" in value) ||
    typeof value.versionCode !== "number" ||
    !Number.isInteger(value.versionCode)
  )
    throw new Error("Hook 版本读取失败");
  return value.versionCode;
}

export async function installHook(): Promise<void> {
  if (import.meta.env.DEV) return;
  await command("install-hook");
}

export async function checkHookRoot(): Promise<void> {
  if (!import.meta.env.DEV) await command("diagnose-hook");
}

export async function readExitCandidate(): Promise<ProbeResult> {
  const { ip, countryCode, timezone, checkedAt } = await readStatus();
  const regions = new Intl.DisplayNames("zh-CN", { type: "region", fallback: "none" });
  if (!ip || !countryCode || !checkedAt || checkedAt <= 0) {
    const entry: unknown = JSON.parse(await command("lookup-ip"));
    if (
      !entry ||
      typeof entry !== "object" ||
      !("ip" in entry) ||
      typeof entry.ip !== "string" ||
      !isPublicIPv4(entry.ip)
    )
      throw new Error("Claude 服务未回显有效公网 IPv4。");
    const country =
      "countryCode" in entry &&
      typeof entry.countryCode === "string" &&
      /^[A-Z]{2}$/.test(entry.countryCode)
        ? entry.countryCode
        : undefined;
    const region = country ? regions.of(country) : undefined;
    const locale = region && country ? new Intl.Locale(`und-${country}`) : undefined;
    const zones: unknown =
      locale && "getTimeZones" in locale && typeof locale.getTimeZones === "function"
        ? locale.getTimeZones()
        : locale && "timeZones" in locale
          ? locale.timeZones
          : undefined;
    const zone =
      Array.isArray(zones) &&
      zones.length === 1 &&
      typeof zones[0] === "string" &&
      isTimeZone(zones[0])
        ? zones[0]
        : undefined;
    return {
      source: "preflight",
      ip: entry.ip,
      ...(country ? { countryCode: country } : {}),
      ...(region ? { region } : {}),
      ...(zone ? { timezone: zone } : {}),
    };
  }
  const region = regions.of(countryCode) ?? countryCode;
  return {
    source: "claude",
    ip,
    countryCode,
    region,
    checkedAt,
    ...(timezone && isTimeZone(timezone) ? { timezone } : {}),
  };
}

export async function openMore(): Promise<void> {
  const url = "https://ip.net.coffee/claude/";
  if (import.meta.env.DEV) {
    window.open(url, "_blank", "noopener,noreferrer");
    return;
  }
  await command(`more`);
}

export async function downloadUpdate(version: string): Promise<void> {
  const url = updateDownload(version);
  if (import.meta.env.DEV) {
    window.open(url, "_blank", "noopener,noreferrer");
    return;
  }
  await command("download-update " + version);
}
