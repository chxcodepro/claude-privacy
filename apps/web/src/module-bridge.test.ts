import { exec } from "kernelsu";
import { afterEach, expect, test, vi } from "vite-plus/test";

import { readExitCandidate, saveConfig, statusForAllowlist } from "./module-bridge";
import { defaultConfig } from "./privacy-config";

// SukiSU's local shell bridge does not exist in the host test.
vi.mock("kernelsu", () => ({ exec: vi.fn() }));

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.resetAllMocks();
});

test("refresh reads the Claude observation locally without opening Claude or sending a request", async () => {
  vi.mocked(exec).mockResolvedValue({
    errno: 0,
    stdout: JSON.stringify({
      state: "blocked",
      reason: "background",
      ip: "8.8.4.4",
      countryCode: "US",
      checkedAt: 1760000000,
    }),
    stderr: "",
  });
  const fetch = vi.fn();
  vi.stubGlobal("fetch", fetch);
  await expect(readExitCandidate()).resolves.toEqual({
    source: "claude",
    ip: "8.8.4.4",
    region: "美国",
    countryCode: "US",
    checkedAt: 1760000000,
  });
  expect(vi.mocked(exec)).toHaveBeenCalledExactlyOnceWith(
    "sh /data/adb/modules/claude_privacy/bin/control.sh status",
  );
  expect(fetch).not.toHaveBeenCalled();
});

test.each([
  { state: "blocked", reason: "boot" },
  { state: "allowed", ip: "8.8.4.4", checkedAt: 1760000000 },
])("with no Claude observation, refresh only reads an IP from the network", async (status) => {
  vi.mocked(exec)
    .mockResolvedValueOnce({ errno: 0, stdout: JSON.stringify(status), stderr: "" })
    .mockResolvedValueOnce({ errno: 0, stdout: "8.8.4.4", stderr: "" });
  await expect(readExitCandidate()).resolves.toEqual({ source: "network", ip: "8.8.4.4" });
  expect(vi.mocked(exec)).toHaveBeenLastCalledWith(
    "sh /data/adb/modules/claude_privacy/bin/control.sh lookup-ip",
  );
});

test("a non-public network result cannot become an allowlist candidate", async () => {
  vi.mocked(exec)
    .mockResolvedValueOnce({ errno: 0, stdout: '{"state":"blocked"}', stderr: "" })
    .mockResolvedValueOnce({ errno: 0, stdout: "192.168.1.1", stderr: "" });
  await expect(readExitCandidate()).rejects.toThrow("公网 IPv4");
});

test("adding the first IPv4 replaces the stale empty-list warning with a pending check", () => {
  const previous = { state: "blocked", reason: "no_allowed_ips" } as const;
  expect(statusForAllowlist(["8.8.4.4"], previous)).toEqual({ state: "unconfigured" });
  expect(statusForAllowlist(["8.8.4.4"], { state: "blocked", reason: "mainland_ip" })).toEqual({
    state: "blocked",
    reason: "mainland_ip",
  });
  expect(
    statusForAllowlist(["8.8.4.4"], { state: "blocked", reason: "ip_denied", ip: "8.8.4.4" }),
  ).toEqual({ state: "unconfigured" });
});

test("an IPv4 is only reported as saved if the stored list includes the submitted address", async () => {
  vi.stubEnv("DEV", false);
  vi.mocked(exec)
    .mockResolvedValueOnce({ errno: 0, stdout: '{"ok":true}', stderr: "" })
    .mockResolvedValueOnce({ errno: 0, stdout: JSON.stringify(defaultConfig), stderr: "" });
  const config = { ...defaultConfig, allowedIps: ["8.8.4.4"] };
  await expect(saveConfig(config)).rejects.toThrow("保存后与提交不一致");
});
