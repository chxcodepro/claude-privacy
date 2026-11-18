import { exec } from "kernelsu";
import { afterEach, expect, test, vi } from "vite-plus/test";

import { readClaudeExit } from "./module-bridge";

// SukiSU's local shell bridge does not exist in the host test.
vi.mock("kernelsu", () => ({ exec: vi.fn() }));

afterEach(() => {
  vi.unstubAllGlobals();
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
  await expect(readClaudeExit()).resolves.toEqual({
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
])("missing or legacy observations are not offered as the current Claude exit", async (status) => {
  vi.mocked(exec).mockResolvedValue({ errno: 0, stdout: JSON.stringify(status), stderr: "" });
  await expect(readClaudeExit()).resolves.toBeNull();
});
