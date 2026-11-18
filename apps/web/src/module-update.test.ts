import { afterEach, expect, test, vi } from "vite-plus/test";

import published from "../../../update.json";
import { checkModuleUpdate, updateDownload } from "./module-update";

afterEach(() => vi.unstubAllGlobals());

test("the released manifest for this build reports up to date", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(published)));
  await expect(checkModuleUpdate()).resolves.toBeNull();
});

test("only a newer version with the official ZIP URL is offered", async () => {
  const version = "99.0.0";
  const next = { ...published, version: version + "-dev", versionCode: published.versionCode + 1 };
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(Response.json({ ...next, zipUrl: updateDownload(version) }))
    .mockResolvedValueOnce(Response.json({ ...next, zipUrl: "https://example.org/zip" }));
  vi.stubGlobal("fetch", fetch);
  await expect(checkModuleUpdate()).resolves.toBe(version);
  await expect(checkModuleUpdate()).rejects.toThrow("更新下载地址无效");
});
