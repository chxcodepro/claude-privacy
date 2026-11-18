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
  countryCode: string;
  checkedAt: number;
}

export interface GateStatus {
  state: "blocked" | "allowed" | "unconfigured";
  ip?: string;
  countryCode?: string;
  reason?: string;
  checkedAt?: number;
  timezone?: string;
  detail?: string;
  updatedAt?: number;
  revision?: number;
}

export const requiredHookVersion = 8;

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
  return readStatus();
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
  for (const key of ["ip", "reason", "timezone", "detail"] as const) {
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

export async function readClaudeExit(): Promise<ProbeResult | null> {
  const { ip, countryCode, timezone, checkedAt } = await readStatus();
  if (!ip || !countryCode || !checkedAt || checkedAt <= 0) return null;
  const region = new Intl.DisplayNames("zh-CN", { type: "region" }).of(countryCode) ?? countryCode;
  return {
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
