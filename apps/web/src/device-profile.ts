import type { PrivacyConfig } from "./privacy-config";

type Values = PrivacyConfig["values"];
type Device = Pick<Values, "manufacturer" | "brand" | "model" | "device" | "product">;

const profiles: readonly Device[] = [
  { manufacturer: "Google", brand: "google", model: "Pixel 8", device: "shiba", product: "shiba" },
  { manufacturer: "Google", brand: "google", model: "Pixel 9", device: "tokay", product: "tokay" },
  {
    manufacturer: "Google",
    brand: "google",
    model: "Pixel 9 Pro",
    device: "caiman",
    product: "caiman",
  },
  {
    manufacturer: "samsung",
    brand: "samsung",
    model: "SM-S921B",
    device: "e1s",
    product: "e1sxeea",
  },
  {
    manufacturer: "OnePlus",
    brand: "OnePlus",
    model: "CPH2581",
    device: "waffle",
    product: "waffle",
  },
];

// A small set of synthetic builds avoids generating a unique fingerprint on every click.
const buildVariants = [
  "generic/00000000",
  "release.01/00010000",
  "release.02/00020000",
  "release.03/00030000",
];

function choose<T>(choices: readonly T[]): T {
  const selected = choices[Math.floor(Math.random() * choices.length)];
  if (selected === undefined) throw new Error("没有可用的设备档案");
  return selected;
}

function buildSegment(value: string): string {
  return (
    value
      .trim()
      .replace(/[^\w.-]+/g, "_")
      .slice(0, 40) || "generic"
  );
}

export function randomizeBuild(values: Values): Values {
  const device = [values.brand, values.product, values.device].map(buildSegment).join("/");
  const candidates = buildVariants.map((build) => `${device}:{release}/${build}:user/release-keys`);
  return {
    ...values,
    buildFingerprint: choose(candidates.filter((item) => item !== values.buildFingerprint)),
  };
}

export function randomizeDevice(values: Values): Values {
  const profile = choose(
    profiles.filter((item) => item.device !== values.device && item.model !== values.model),
  );
  return randomizeBuild({ ...values, ...profile });
}
