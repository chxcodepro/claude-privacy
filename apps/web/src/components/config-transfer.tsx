import { Download, Upload } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import { Button } from "@claude-privacy/ui/components/button";

import { transferConfig } from "#src/module-bridge.ts";

export function ConfigTransfer({ disabled = false }: { disabled?: boolean }) {
  const [pending, setPending] = useState<"export" | "import" | null>(null);

  async function open(operation: "export" | "import") {
    setPending(operation);
    try {
      await transferConfig(operation);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "文件选择界面启动失败，请重试。");
    } finally {
      setPending(null);
    }
  }

  return (
    <div className="flex flex-wrap gap-2">
      <Button
        aria-busy={pending === "export"}
        disabled={disabled || pending !== null}
        onClick={() => void open("export")}
        variant="outline"
      >
        <Download className="size-4" />
        {pending === "export" ? "正在打开…" : "导出配置"}
      </Button>
      <Button
        aria-busy={pending === "import"}
        disabled={disabled || pending !== null}
        onClick={() => void open("import")}
        variant="outline"
      >
        <Upload className="size-4" />
        {pending === "import" ? "正在打开…" : "导入配置"}
      </Button>
    </div>
  );
}
