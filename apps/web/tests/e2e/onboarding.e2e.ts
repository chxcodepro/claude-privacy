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
  await page.getByRole("button", { name: "安装 Claude Privacy Hook" }).click();
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
