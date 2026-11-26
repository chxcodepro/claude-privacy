import { useState } from "react";
import { toast } from "sonner";

import { Button } from "@claude-privacy/ui/components/button";

import { downloadUpdate } from "#src/module-bridge.ts";
import { checkModuleUpdate, installedModuleVersion } from "#src/module-update.ts";

export function Header() {
  const [checking, setChecking] = useState(false);
  const [available, setAvailable] = useState<string | null>(null);

  async function update() {
    setChecking(true);
    try {
      if (available) {
        await downloadUpdate(available);
      } else {
        const version = await checkModuleUpdate();
        setAvailable(version);
        if (version === null) toast.success("已是最新版本");
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "更新检查失败");
    } finally {
      setChecking(false);
    }
  }

  return (
    <header className="bg-background/90 sticky top-0 z-10 border-b backdrop-blur-md">
      <div className="mx-auto flex h-14 max-w-xl items-center justify-between gap-4 px-4">
        <div className="flex min-w-0 items-baseline gap-2">
          <span className="text-sm font-semibold tracking-tight">Claude Hook</span>
          <span className="text-muted-foreground text-xs tabular-nums">
            {installedModuleVersion}
          </span>
        </div>
        <Button
          className="min-h-10 shrink-0"
          variant="ghost"
          size="sm"
          disabled={checking}
          onClick={() => void update()}
        >
          {checking
            ? available
              ? "正在打开…"
              : "正在检查…"
            : available
              ? "下载 v" + available
              : "检查更新"}
        </Button>
      </div>
    </header>
  );
}
