import moduleProp from "../../../module/module.prop?raw";

const installed = /^version=([^\r\n]+)$/m.exec(moduleProp)?.[1];
const installedCode = Number(/^versionCode=(\d+)$/m.exec(moduleProp)?.[1]);
const feed = /^updateJson=([^\r\n]+)$/m.exec(moduleProp)?.[1];
const releaseVersion = /^\d+\.\d+\.\d+$/;
const releaseBase = "https://github.com/chxcodepro/claude-privacy/releases/download";

export const installedModuleVersion = installed ?? "";

export function updateDownload(version: string): string {
  if (!releaseVersion.test(version)) throw new Error("更新版本格式无效");
  return releaseBase + "/v" + version + "/claude-privacy-" + version + "-dev.zip";
}

export async function checkModuleUpdate(): Promise<string | null> {
  if (!feed || !Number.isSafeInteger(installedCode)) throw new Error("本机模块版本信息无效");
  const response = await fetch(feed, { cache: "no-store", signal: AbortSignal.timeout(8000) });
  if (!response.ok) throw new Error("读取更新失败，请稍后重试");
  const entry: unknown = await response.json();
  if (
    !entry ||
    typeof entry !== "object" ||
    !("version" in entry) ||
    typeof entry.version !== "string" ||
    !entry.version.endsWith("-dev") ||
    !("versionCode" in entry) ||
    typeof entry.versionCode !== "number" ||
    !Number.isSafeInteger(entry.versionCode) ||
    entry.versionCode <= 0 ||
    !("zipUrl" in entry)
  )
    throw new Error("更新信息格式无效");
  const version = entry.version.slice(0, -4);
  if (entry.zipUrl !== updateDownload(version)) throw new Error("更新下载地址无效");
  return entry.versionCode > installedCode ? version : null;
}
