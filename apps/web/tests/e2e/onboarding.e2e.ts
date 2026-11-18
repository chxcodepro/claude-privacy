import { expect, test } from "@playwright/test";

test("first setup requires confirmation before saving the detected IPv4", async ({ page }) => {
  // The development page runs outside SukiSU. Stub only the IPv4 lookup bridge and geolocation.
  await page.addInitScript(() => {
    Object.assign(globalThis, {
      ksu: {
        exec(command: string, _options: string, callback: string) {
          const lookup = command.endsWith(" lookup-claude-ip");
          const reply: unknown = Reflect.get(globalThis, callback);
          if (typeof reply === "function")
            reply(lookup ? 0 : 1, lookup ? "ip=8.8.4.4\nloc=US" : "", "");
        },
      },
    });
  });
  await page.route("https://ipwho.is/8.8.4.4", (route) =>
    route.fulfill({ json: { success: true, ip: "8.8.4.4", country: "United States" } }),
  );
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
