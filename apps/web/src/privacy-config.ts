import defaults from "./default-config.json";

export interface PrivacyConfig {
  allowedIps: string[];
  ipRegions: Record<string, string>;
  ipTimezones: Record<string, string>;
  timezoneMode: "manual" | "ip";
  timezone: string;
  language: string;
  values: {
    androidId: string;
    manufacturer: string;
    brand: string;
    model: string;
    device: string;
    product: string;
    buildFingerprint: string;
    carrierName: string;
    simCountry: string;
    networkCountry: string;
    batteryPercent: number;
    screenWidth: number;
    screenHeight: number;
  };
  spoof: Record<
    | "timezone"
    | "language"
    | "androidId"
    | "device"
    | "build"
    | "hardware"
    | "localIps"
    | "carrier"
    | "network"
    | "battery"
    | "root"
    | "packages"
    | "screen"
    | "sentryScreenshot"
    | "sentryHierarchy"
    | "sentryPii"
    | "sentryTelemetry"
    | "datadog"
    | "firebaseId"
    | "fcm"
    | "sift"
    | "plaid"
    | "playIntegrity",
    boolean
  >;
}

export const spoofGroups = [
  {
    title: "设备与网络",
    items: [
      ["androidId", "Android ID"],
      ["device", "厂商与机型"],
      ["build", "系统构建信息"],
      ["hardware", "硬件与 ABI"],
      ["localIps", "本机网卡地址"],
      ["carrier", "SIM 与运营商"],
      ["network", "网络状态"],
      ["battery", "电池状态"],
      ["root", "Root 痕迹"],
      ["packages", "应用存在性"],
      ["screen", "屏幕尺寸"],
    ],
  },
  {
    title: "诊断与第三方",
    items: [
      ["sentryScreenshot", "崩溃截图"],
      ["sentryHierarchy", "界面层级"],
      ["sentryPii", "Sentry 个人信息"],
      ["sentryTelemetry", "Sentry 上报"],
      ["datadog", "Datadog 遥测"],
      ["firebaseId", "Firebase 安装 ID"],
      ["fcm", "FCM 推送 token"],
      ["sift", "Sift 指纹"],
      ["plaid", "Plaid 设备元数据"],
      ["playIntegrity", "阻止 Play Integrity"],
    ],
  },
] as const;

export function spoofPreview(
  key: keyof PrivacyConfig["spoof"],
  config: PrivacyConfig,
  release = "<系统版本>",
): string {
  if (key === "playIntegrity")
    return config.spoof.playIntegrity
      ? "已阻止申请与 token 上报；不建议开启，可能影响登录或支付"
      : "不建议开启 · 可能影响登录或支付";
  if (!config.spoof[key]) return "使用原值";
  const value = config.values;
  const previews: Record<keyof PrivacyConfig["spoof"], string> = {
    timezone: `${config.timezone} · ${utcOffset(config.timezone)}`,
    language: config.language,
    androidId: value.androidId || "首次生成时写入",
    device: `${value.manufacturer} · ${value.model} · ${value.device}`,
    build: value.buildFingerprint.replaceAll("{release}", release),
    hardware: "arm64-v8a · armeabi-v7a",
    localIps: "空列表",
    carrier: `${value.carrierName || "无运营商"} · SIM ${value.simCountry || "未知"} · 网络 ${value.networkCountry || "未知"}`,
    network: "CCT 上报省略网络类型和移动子类型",
    battery: `${value.batteryPercent}% · 未充电 · 健康`,
    root: "su 无 · Root 证据列表空",
    packages: "已安装应用列表为空 · 指定包隐藏",
    screen: `${value.screenWidth} × ${value.screenHeight}`,
    sentryScreenshot: "不附加截图",
    sentryHierarchy: "不附加界面层级",
    sentryPii: "不附加默认 PII",
    sentryTelemetry: "阻断 Sentry 上报",
    datadog: "阻断 Datadog 上报",
    firebaseId: "阻断 Firebase 注册",
    fcm: "阻断 FCM 注册",
    sift: "阻断 Sift 上报",
    plaid: "阻断 Plaid 上报",
    playIntegrity: "阻止 Play Integrity",
  };
  return previews[key];
}

export function isPublicIPv4(value: string): boolean {
  const parts = value.split(".");
  if (
    parts.length !== 4 ||
    parts.some(
      (part) =>
        !/^\d{1,3}$/.test(part) || (part.length > 1 && part.startsWith("0")) || Number(part) > 255,
    )
  ) {
    return false;
  }
  const [a, b, c] = parts.map(Number);
  if (a === undefined || b === undefined || c === undefined) return false;
  return !(
    a === 0 ||
    a === 10 ||
    a === 127 ||
    a >= 224 ||
    (a === 100 && b >= 64 && b <= 127) ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 192 && b === 0 && c <= 2) ||
    (a === 198 && b >= 18 && b <= 19) ||
    (a === 198 && b === 51 && c === 100) ||
    (a === 203 && b === 0 && c === 113)
  );
}

export function isTimeZone(value: string): boolean {
  try {
    new Intl.DateTimeFormat("en", { timeZone: value });
    return true;
  } catch {
    return false;
  }
}

export function utcOffset(zone: string): string {
  const name =
    new Intl.DateTimeFormat("en-US", { timeZone: zone, timeZoneName: "shortOffset" })
      .formatToParts(new Date())
      .find((part) => part.type === "timeZoneName")?.value ?? "GMT";
  return name === "GMT" ? "UTC+0" : name.replace("GMT", "UTC");
}

export function ipTimezone(
  config: Pick<PrivacyConfig, "allowedIps" | "ipTimezones">,
  status: { state: string; ip?: string; timezone?: string },
  probe?: { ip: string; timezone?: string } | null,
): string | undefined {
  const ip =
    (status.ip && config.allowedIps.includes(status.ip) ? status.ip : undefined) ??
    (probe && config.allowedIps.includes(probe.ip) ? probe.ip : undefined) ??
    (config.allowedIps.length === 1 ? config.allowedIps[0] : undefined);
  if (!ip) return undefined;
  if (status.ip === ip && status.timezone && isTimeZone(status.timezone)) return status.timezone;
  return (probe?.ip === ip ? probe.timezone : undefined) ?? config.ipTimezones[ip];
}

export function parseConfig(value: unknown): PrivacyConfig {
  if (!value || typeof value !== "object") throw new Error("设置格式无效");
  const data = value as Partial<PrivacyConfig>;
  if (
    !Array.isArray(data.allowedIps) ||
    !data.allowedIps.every((ip) => typeof ip === "string" && isPublicIPv4(ip))
  ) {
    throw new Error("允许的 IPv4 列表无效");
  }
  if (
    !data.ipRegions ||
    typeof data.ipRegions !== "object" ||
    Object.entries(data.ipRegions).some(
      ([ip, region]) => !isPublicIPv4(ip) || typeof region !== "string" || region.length > 160,
    )
  ) {
    throw new Error("IPv4 地区数据无效");
  }
  if (data.timezoneMode !== "manual" && data.timezoneMode !== "ip") throw new Error("时区模式无效");
  if (typeof data.timezone !== "string" || !isTimeZone(data.timezone)) throw new Error("时区无效");
  // Installed versions before 0.1.6 did not store GPS-free IP timezone results.
  const ipTimezones = data.ipTimezones === undefined ? {} : data.ipTimezones;
  if (
    !ipTimezones ||
    typeof ipTimezones !== "object" ||
    Array.isArray(ipTimezones) ||
    Object.entries(ipTimezones).some(
      ([ip, zone]) =>
        !isPublicIPv4(ip) || typeof zone !== "string" || zone.length > 80 || !isTimeZone(zone),
    )
  )
    throw new Error("IPv4 时区数据无效");
  if (typeof data.language !== "string" || !/^[a-z]{2,3}(?:-[A-Za-z]{2,8})*$/.test(data.language)) {
    throw new Error("语言代码无效");
  }
  if (!data.spoof || typeof data.spoof !== "object") throw new Error("伪造设置无效");
  const spoof = {
    ...data.spoof,
    playIntegrity: data.spoof.playIntegrity === undefined ? false : data.spoof.playIntegrity,
  };
  const values = data.values;
  if (!values || typeof values !== "object") throw new Error("替身值无效");
  for (const key of [
    "manufacturer",
    "brand",
    "model",
    "device",
    "product",
    "carrierName",
    "simCountry",
    "networkCountry",
  ] as const) {
    if (typeof values[key] !== "string" || values[key].length > 80) throw new Error(`${key} 无效`);
  }
  for (const key of ["manufacturer", "brand", "model", "device", "product"] as const) {
    if (!values[key].trim()) throw new Error(`${key} 不能为空`);
  }
  if (
    typeof values.buildFingerprint !== "string" ||
    !values.buildFingerprint ||
    values.buildFingerprint.length > 200 ||
    /[\s"']/.test(values.buildFingerprint)
  )
    throw new Error("构建指纹无效");
  for (const key of ["simCountry", "networkCountry"] as const) {
    if (values[key] && !/^[a-zA-Z]{2}$/.test(values[key]))
      throw new Error(`${key} 需使用两位国家代码`);
  }
  if (
    typeof values.androidId !== "string" ||
    (values.androidId !== "" && !/^[0-9a-f]{16}$/.test(values.androidId))
  )
    throw new Error("Android ID 无效");
  if (
    !Number.isInteger(values.batteryPercent) ||
    values.batteryPercent < 0 ||
    values.batteryPercent > 100
  )
    throw new Error("电池电量无效");
  if (
    !Number.isInteger(values.screenWidth) ||
    values.screenWidth < 320 ||
    values.screenWidth > 10000 ||
    !Number.isInteger(values.screenHeight) ||
    values.screenHeight < 320 ||
    values.screenHeight > 10000
  )
    throw new Error("屏幕尺寸无效");
  for (const group of spoofGroups) {
    for (const [key] of group.items) {
      if (typeof spoof[key] !== "boolean") throw new Error(`缺少 ${key} 开关`);
    }
  }
  if (typeof data.spoof.timezone !== "boolean" || typeof data.spoof.language !== "boolean")
    throw new Error("地区与语言开关无效");
  return { ...data, ipTimezones, spoof } as PrivacyConfig;
}

export const defaultConfig = parseConfig(defaults);
