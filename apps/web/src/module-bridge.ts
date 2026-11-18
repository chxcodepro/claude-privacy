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

export interface ProbeResult {
  ip: string;
  region: string;
  timezone?: string;
}

export interface GateStatus {
  state: "blocked" | "allowed" | "unconfigured";
  ip?: string;
  reason?: string;
  checkedAt?: number;
  timezone?: string;
  detail?: string;
  updatedAt?: number;
  revision?: number;
}

export const requiredHookVersion = 5;

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

export async function loadConfig(): Promise<PrivacyConfig> {
  let config: PrivacyConfig;
  if (import.meta.env.DEV) {
    const saved = localStorage.getItem("claude-privacy-preview");
    config = saved ? parseConfig(JSON.parse(saved) as unknown) : structuredClone(defaultConfig);
  } else {
    config = parseConfig(JSON.parse(await command("get")) as unknown);
  }
  if (config.values.androidId) return config;
  const generated = Array.from(crypto.getRandomValues(new Uint8Array(8)), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
  config = { ...config, values: { ...config.values, androidId: generated } };
  await saveConfig(config);
  return config;
}

export async function saveConfig(config: PrivacyConfig): Promise<void> {
  parseConfig(config);
  if (import.meta.env.DEV) {
    localStorage.setItem("claude-privacy-preview", JSON.stringify(config));
    return;
  }
  const bytes = new TextEncoder().encode(JSON.stringify(config));
  const encoded = btoa(String.fromCharCode(...bytes));
  await command(`set ${encoded}`);
}

export async function getStatus(): Promise<GateStatus> {
  if (import.meta.env.DEV) return { state: "unconfigured" };
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
  for (const key of ["ip", "reason", "timezone", "detail"] as const) {
    if (key in fields && typeof fields[key] !== "string") throw new Error("模块状态字段无效");
  }
  for (const key of ["checkedAt", "updatedAt", "revision"] as const) {
    if (key in fields && (typeof fields[key] !== "number" || !Number.isFinite(fields[key])))
      throw new Error("模块检测时间无效");
  }
  if ("ip" in value && typeof value.ip === "string" && !isPublicIPv4(value.ip))
    throw new Error("模块返回的公网 IPv4 无效");
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

export async function probeCurrentIp(): Promise<ProbeResult> {
  const trace = await command("lookup-claude-ip");
  const matches = [...trace.matchAll(/^ip=([^\r\n]+)\r?$/gm)];
  const ip = matches[0]?.[1];
  if (matches.length !== 1 || !ip || !isPublicIPv4(ip))
    throw new Error("出口查询未返回有效的公网 IPv4");
  try {
    const response = await fetch(`https://ipwho.is/${ip}`, { signal: AbortSignal.timeout(6000) });
    if (!response.ok) throw new Error("IP 地区查询失败");
    const geo: unknown = await response.json();
    if (
      !geo ||
      typeof geo !== "object" ||
      !("success" in geo) ||
      geo.success !== true ||
      !("ip" in geo) ||
      geo.ip !== ip
    )
      throw new Error("IP 地区查询失败");
    const fields = geo as Record<string, unknown>;
    const region = ["country", "region", "city"]
      .map((key) => fields[key])
      .filter((value) => typeof value === "string" && value.trim())
      .join(" · ");
    const time = fields.timezone;
    const zone = time && typeof time === "object" && "id" in time ? time.id : undefined;
    return {
      ip,
      region: region && region.length <= 160 ? region : "地区未知",
      ...(typeof zone === "string" && zone.length <= 80 && isTimeZone(zone)
        ? { timezone: zone }
        : {}),
    };
  } catch {
    return { ip, region: "地区未知" };
  }
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
