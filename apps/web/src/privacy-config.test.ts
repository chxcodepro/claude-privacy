import { expect, test } from "vite-plus/test";

import { defaultConfig, ipTimezone, parseConfig } from "./privacy-config";

test("auto timezone waits for a matching lookup and clears on removing the IP", () => {
  const config = { allowedIps: ["8.8.4.4"], ipTimezones: {} };
  const unmeasured = { state: "blocked" };
  const exchange = { ip: "8.8.4.4", timezone: "America/Los_Angeles" };
  expect(ipTimezone(config, unmeasured)).toBeUndefined();
  expect(ipTimezone(config, unmeasured, exchange)).toBe(exchange.timezone);
  expect(ipTimezone(config, unmeasured, { ...exchange, ip: "1.1.1.1" })).toBeUndefined();
  expect(ipTimezone(config, { state: "allowed", ...exchange })).toBe(exchange.timezone);
  expect(
    ipTimezone({ ...config, allowedIps: [] }, { state: "allowed", ...exchange }, exchange),
  ).toBeUndefined();
});

test("existing installations without cached IP timezones keep the rest of their config", () => {
  const { ipTimezones, ...legacy } = defaultConfig;
  const previous = { ...legacy, allowedIps: ["8.8.4.4"], timezone: "Asia/Shanghai" };
  expect(parseConfig(previous)).toEqual({ ...previous, ipTimezones });
});

test("Play Integrity blocking stays off by default and for existing configurations", () => {
  const { playIntegrity, ...previousFlags } = defaultConfig.spoof;
  expect(playIntegrity).toBe(false);
  const previous = { ...defaultConfig, spoof: previousFlags, timezone: "Asia/Shanghai" };
  expect(parseConfig(previous)).toEqual({
    ...previous,
    spoof: { ...previousFlags, playIntegrity: false },
  });
  expect(
    parseConfig({ ...defaultConfig, spoof: { ...defaultConfig.spoof, playIntegrity: true } }).spoof
      .playIntegrity,
  ).toBe(true);
});
