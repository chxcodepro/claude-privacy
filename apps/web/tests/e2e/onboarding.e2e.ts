import { expect, test } from "@playwright/test";

test("first setup requires confirmation before saving the detected IPv4", async ({ page }) => {
  // The development page runs outside SukiSU. Supply one existing Claude observation.
  await page.addInitScript(() => {
    Object.assign(globalThis, {
      ksu: {
        exec(command: string, _options: string, callback: string) {
          const status = command.endsWith(" status");
          const reply: unknown = Reflect.get(globalThis, callback);
          if (typeof reply === "function")
            reply(
              status ? 0 : 1,
              status
                ? JSON.stringify({
                    state: "blocked",
                    ip: "8.8.4.4",
                    countryCode: "US",
                    checkedAt: 1760000000,
                  })
                : "",
              "",
            );
        },
      },
    });
  });
  await page.goto("/", { waitUntil: "networkidle" });
  await expect(page.getByRole("heading", { name: "安装后壳" })).toBeVisible();
  await page.getByRole("button", { name: "安装 Claude Hook" }).click();
  await expect(page.getByRole("heading", { name: "授予 Root 权限" })).toBeVisible();
  await page.getByRole("button", { name: "检查 Root 授权" }).click();
  await expect(page.getByText("8.8.4.4", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "地区与语言" })).toHaveCount(0);
  await page.getByRole("button", { name: "允许此 IPv4 并进入" }).click();
  await expect(page.getByRole("heading", { name: "地区与语言" })).toBeVisible();
  await page.reload({ waitUntil: "networkidle" });
  await expect(page.getByRole("heading", { name: "地区与语言" })).toBeVisible();
  await expect(page.getByText("8.8.4.4", { exact: true })).toBeVisible();
});

test("service preflight displays before launch, and a manual entry is saved pending Claude verification", async ({
  page,
}) => {
  await page.addInitScript(() => {
    const responses: Record<string, string> = {
      status: '{"state":"blocked","reason":"no_allowed_ips"}',
      "lookup-ip": '{"ip":"8.8.4.4","countryCode":"JP"}',
    };
    Object.assign(globalThis, {
      ksu: {
        exec(command: string, _options: string, callback: string) {
          const result = responses[command.split(" ").at(-1) ?? ""];
          const reply: unknown = Reflect.get(globalThis, callback);
          if (typeof reply === "function") reply(result ? 0 : 1, result ?? "", "");
        },
      },
    });
  });
  await page.goto("/", { waitUntil: "networkidle" });
  await page.getByRole("button", { name: "安装 Claude Hook" }).click();
  await page.getByRole("button", { name: "检查 Root 授权" }).click();
  await expect(page.getByText("8.8.4.4", { exact: true })).toBeVisible();
  await expect(page.getByText("日本 · Claude 服务可达")).toBeVisible();
  await expect(page.getByRole("heading", { name: "地区与语言" })).toHaveCount(0);
  await page.getByRole("textbox", { name: "手动输入公网 IPv4" }).fill("1.1.1.1");
  await page.getByRole("button", { name: "允许并进入", exact: true }).click();
  await expect(page.getByRole("heading", { name: "等待启动核验" })).toBeVisible();
  await expect(page.getByText("1.1.1.1", { exact: true })).toBeVisible();
  await expect(page.getByText("8.8.4.4", { exact: true })).toHaveCount(0);
  await page.reload({ waitUntil: "networkidle" });
  await expect(page.getByText("1.1.1.1", { exact: true })).toBeVisible();
  await expect(page.getByText("请先添加允许的公网 IPv4")).toHaveCount(0);

  await page.getByRole("button", { name: "移除 1.1.1.1" }).click();
  await page.getByRole("combobox", { name: "时区", exact: true }).selectOption("ip");
  await page.getByRole("button", { name: "添加", exact: true }).click();
  await page.getByRole("button", { name: "刷新出口 IPv4" }).click();
  await page.getByRole("button", { name: "确认添加" }).click();
  await expect(page.getByText("Claude 服务可达 · 等待应用核验")).toBeVisible();
  await expect(page.getByText("Asia/Tokyo · UTC+9")).toBeVisible();
  await expect(page.getByText("Claude 读回一致")).toHaveCount(0);
  await page.reload({ waitUntil: "networkidle" });
  await expect(page.getByText("Asia/Tokyo · UTC+9")).toBeVisible();
});
