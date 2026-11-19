import { expect, test } from "@playwright/test";

import defaults from "../../src/default-config.json" with { type: "json" };

test("choose a time zone and preview randomized identity before saving", async ({ page }, info) => {
  await page.addInitScript(
    (config) => {
      if (!localStorage.getItem("claude-privacy-preview"))
        localStorage.setItem("claude-privacy-preview", JSON.stringify(config));
    },
    {
      ...defaults,
      allowedIps: ["8.8.4.4"],
      values: { ...defaults.values, androidId: "0123456789abcdef" },
    },
  );
  await page.goto("/", { waitUntil: "networkidle" });

  const zone = page.getByRole("form", { name: "自定义时区设置" });
  await zone.getByLabel(/自定义时区：UTC · UTC\+0/).click();
  await page.getByRole("combobox", { name: "搜索时区" }).fill("UTC+5:45");
  const kathmandu = page.getByRole("option", { name: /Asia\/Katmandu.*UTC\+5:45/ });
  await expect(kathmandu).toBeVisible();
  await expect(page.locator('[data-slot="timezone-options"]')).toHaveCSS("opacity", "1");
  await page.screenshot({ path: info.outputPath("timezone-options.png") });
  await kathmandu.click();
  await expect(zone.getByLabel(/Asia\/Katmandu · UTC\+5:45/)).toBeVisible();
  await zone.getByRole("button", { name: "保存", exact: true }).click();
  await expect(zone.getByRole("button", { name: "保存", exact: true })).toBeDisabled();

  await page.getByRole("button", { name: "编辑厂商与机型" }).click();
  await page.getByRole("button", { name: "随机生成设备与构建" }).click();
  const model = await page.getByLabel("机型", { exact: true }).inputValue();
  expect(model).not.toBe(defaults.values.model);
  const [brand, product, device] = await Promise.all(
    ["品牌", "产品代号", "设备代号"].map((field) =>
      page.getByLabel(field, { exact: true }).inputValue(),
    ),
  );
  const fingerprint = await page.getByLabel("构建指纹", { exact: true }).inputValue();
  expect(fingerprint.startsWith(`${brand}/${product}/${device}:`)).toBe(true);
  await expect(page.getByText(/Google · Pixel 9 · tokay/)).toBeVisible();
  await page.screenshot({ path: info.outputPath("device-draft.png") });
  await page.getByRole("button", { name: "保存替身值" }).click();
  await expect(page.getByText(model, { exact: false })).toBeVisible();

  await page.getByRole("button", { name: "编辑系统构建信息" }).click();
  const build = page.getByLabel(/构建指纹（/);
  await expect(build).toHaveValue(fingerprint);
  await page.getByRole("button", { name: "随机生成构建指纹" }).click();
  await expect(build).not.toHaveValue(fingerprint);
  const nextBuild = await build.inputValue();
  expect(nextBuild.startsWith(`${brand}/${product}/${device}:`)).toBe(true);
  await page.getByRole("button", { name: "保存替身值" }).click();
  await page.reload({ waitUntil: "networkidle" });
  await expect(zone.getByLabel(/Asia\/Katmandu · UTC\+5:45/)).toBeVisible();
  await expect(page.getByText(model, { exact: false })).toBeVisible();
  await expect(page.getByText(nextBuild.replace("{release}", "16"), { exact: true })).toBeVisible();
});

test("recover a stopped watchdog, clear the stale failure and export diagnostics", async ({
  page,
}, info) => {
  await page.addInitScript(
    (config) => {
      localStorage.setItem("claude-privacy-preview", JSON.stringify(config));
      let running = false;
      Object.assign(globalThis, {
        ksu: {
          exec(command: string, _options: string, callback: string) {
            const reply: unknown = Reflect.get(globalThis, callback);
            if (typeof reply !== "function") return;
            if (command.endsWith(" watch-start")) {
              running = true;
              reply(0, "", "");
            } else if (command.endsWith(" watch-status")) {
              reply(0, running ? "running" : "stopped", "");
            } else if (command.endsWith(" status")) {
              reply(
                0,
                JSON.stringify({
                  state: "blocked",
                  reason: sessionStorage.getItem("gateReason") ?? "watchdog_unavailable",
                }),
                "",
              );
            } else if (command.endsWith(" export-diagnostics")) {
              if (sessionStorage.getItem("exportFailed") !== "1") {
                sessionStorage.setItem("exportFailed", "1");
                reply(1, "", "请先更新 Hook，再导出诊断日志。");
              } else {
                sessionStorage.setItem("diagnosticsExported", "1");
                reply(0, "", "");
              }
            } else reply(1, "", "");
          },
        },
      });
    },
    {
      ...defaults,
      allowedIps: ["8.8.4.4"],
      values: { ...defaults.values, androidId: "0123456789abcdef" },
    },
  );
  await page.goto("/", { waitUntil: "networkidle" });
  const status = page.getByRole("region", { name: "访问状态" });
  await expect(status.getByText("已停止")).toBeVisible();
  await expect(status.getByText("出口守护未就绪")).toBeVisible();
  await page.screenshot({ path: info.outputPath("watchdog-stopped.png") });
  const start = status.getByRole("button", { name: "启动守护" });
  await start.click();
  await expect(status.getByText("运行中")).toBeVisible();
  await expect(start).toBeDisabled();
  await expect(status.getByText("守护已就绪 · 等待重新核验")).toBeVisible();
  await expect(status.getByText("出口守护未就绪")).toHaveCount(0);
  await status.getByRole("button", { name: "导出诊断日志" }).click();
  await expect(page.getByText("请先更新 Hook，再导出诊断日志。")).toBeVisible();
  await expect(status.getByRole("button", { name: "导出诊断日志" })).toBeEnabled();
  await status.getByRole("button", { name: "导出诊断日志" }).click();
  await expect
    .poll(() => page.evaluate(() => sessionStorage.getItem("diagnosticsExported")))
    .toBe("1");
  await page.evaluate(() => {
    sessionStorage.setItem("gateReason", "process_exited");
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await expect(status.getByText("Claude 已退出，已收回放行")).toBeVisible();
  await page.screenshot({ path: info.outputPath("watchdog-running.png") });
  await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
  await page.screenshot({ path: info.outputPath("diagnostics-dark.png") });
});
