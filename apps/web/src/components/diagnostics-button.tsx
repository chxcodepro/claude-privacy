import { FileDown } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import { Button } from "@claude-privacy/ui/components/button";

import { exportDiagnostics } from "#src/module-bridge.ts";

export function DiagnosticsButton() {
  const [exporting, setExporting] = useState(false);
  async function save() {
    setExporting(true);
    try {
      await exportDiagnostics();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "日志导出失败，请重试。");
    } finally {
      setExporting(false);
    }
  }
  return (
    <Button
      className="justify-self-start"
      disabled={exporting}
      aria-busy={exporting}
      onClick={() => void save()}
      size="sm"
      variant="ghost"
    >
      <FileDown className="size-4" />
      {exporting ? "正在导出…" : "导出诊断日志"}
    </Button>
  );
}
