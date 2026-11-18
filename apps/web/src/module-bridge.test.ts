import { exec } from "kernelsu";
import { afterEach, expect, test, vi } from "vite-plus/test";

import { probeCurrentIp } from "./module-bridge";

// Android's root bridge and geolocation service are unavailable to the host test.
vi.mock("kernelsu", () => ({ exec: vi.fn() }));

afterEach(() => {
  vi.unstubAllGlobals();
  vi.resetAllMocks();
});

test("refresh uses the trace lookup without starting Claude", async () => {
  vi.mocked(exec).mockResolvedValue({
    errno: 0,
    stdout: "h=claude.ai\nip=8.8.4.4\nloc=US",
    stderr: "",
  });
  const fetch = vi.fn().mockResolvedValue(
    Response.json({
      success: true,
      ip: "8.8.4.4",
      country: "United States",
      country_code: "US",
      region: "California",
    }),
  );
  vi.stubGlobal("fetch", fetch);
  await expect(probeCurrentIp()).resolves.toEqual({
    ip: "8.8.4.4",
    region: "United States · California",
    countryCode: "US",
  });
  expect(vi.mocked(exec)).toHaveBeenCalledExactlyOnceWith(
    "sh /data/adb/modules/claude_privacy/bin/control.sh lookup-claude-ip",
  );
  expect(fetch).toHaveBeenCalledExactlyOnceWith("https://ipwho.is/8.8.4.4", expect.anything());
});

test.each(["ip=2001:db8::1", "ip=192.168.0.1", "ip=8.8.4.4\nip=1.1.1.1", "invalid response"])(
  "a non-IPv4 or ambiguous reply never becomes an allowlist candidate: %s",
  async (stdout) => {
    vi.mocked(exec).mockResolvedValue({ errno: 0, stdout, stderr: "" });
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    await expect(probeCurrentIp()).rejects.toThrow("公网 IPv4");
    expect(fetch).not.toHaveBeenCalled();
  },
);

test("a geo lookup outage leaves the validated IP available", async () => {
  vi.mocked(exec).mockResolvedValue({ errno: 0, stdout: "ip=8.8.4.4", stderr: "" });
  vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
  await expect(probeCurrentIp()).resolves.toEqual({ ip: "8.8.4.4", region: "地区未知" });
});
