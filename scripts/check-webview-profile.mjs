import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";

const source = readFileSync(
  new URL("../hook/app/src/main/java/dev/chx/claudeprivacy/WebViewSpoof.kt", import.meta.url),
  "utf8",
);
const scripts = Object.fromEntries(
  [
    ...source.matchAll(
      /val (preamble|language|timezone|formatters) = (?:if \([^)]+\) )?"""([\s\S]*?)"""/g,
    ),
  ].map(([, name, script]) => [name, script]),
);
const run = (settings, assertion) =>
  runInNewContext(
    "(function(){" +
      ["preamble", ...settings]
        .map((key) => scripts[key] ?? "")
        .join("\n")
        .replaceAll("${JSONObject.quote(lang)}", '"de-DE"')
        .replaceAll("${JSONObject.quote(zone)}", '"America/Los_Angeles"') +
      "})();\n" +
      assertion,
    { navigator: {} },
  );

assert.ok(
  run(
    ["language", "timezone", "formatters"],
    `
  navigator.language === "de-DE" &&
  navigator.languages.join(",") === "de-DE" &&
  Intl.DateTimeFormat().resolvedOptions().locale === "de-DE" &&
  Intl.DateTimeFormat().resolvedOptions().timeZone === "America/Los_Angeles" &&
  new Date("2026-01-01T12:00:00Z").getTimezoneOffset() === 480 &&
  new Date("2026-07-01T12:00:00Z").getTimezoneOffset() === 420 &&
  Intl.DateTimeFormat("en-US", {timeZone: "Asia/Shanghai"}).resolvedOptions().timeZone === "Asia/Shanghai" &&
  new Date("2026-01-01T12:00:00Z").toLocaleDateString().includes("2026")
`,
  ),
);
assert.ok(
  run(
    ["language", "formatters"],
    `
  Intl.DateTimeFormat().resolvedOptions().locale === "de-DE" &&
  Intl.DateTimeFormat("en-GB").resolvedOptions().locale === "en-GB"
`,
  ),
);
console.log("WebView profile: language, timezone, DST and explicit formatting options passed");
