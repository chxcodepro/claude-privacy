package dev.chx.claudeprivacy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.io.File
import java.util.concurrent.TimeUnit

class ExportDiagnosticsActivity : Activity() {
    private var report: File? = null
    private var pickerLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = savedInstanceState?.getString("report")
        if (saved != null) {
            report = File(saved).canonicalFile.takeIf {
                it.parentFile == cacheDir.canonicalFile && it.name.startsWith("diagnostics-") && it.isFile
            }
            if (savedInstanceState.getBoolean("pickerLaunched")) {
                pickerLaunched = true
                if (report == null) finishWith("诊断日志已过期，请重新导出。")
                return
            }
            report?.delete()
        }
        Toast.makeText(this, "正在生成诊断日志…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching {
                val file = File.createTempFile("diagnostics-", ".txt", cacheDir)
                report = file
                val process = ProcessBuilder(
                    "su", "-c", "sh /data/adb/modules/claude_privacy/bin/control.sh diagnostics",
                ).redirectOutput(file).redirectError(File("/dev/null")).start()
                if (!process.waitFor(12, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    error("diagnostics_timeout")
                }
                check(process.exitValue() == 0 && file.length() > 0) { "diagnostics_failed" }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result.isFailure) {
                    DiagnosticEvents.record("export_failed")
                    finishWith("日志生成失败，请确认后壳的 Root 授权后重试。")
                } else {
                    try {
                        pickerLaunched = true
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TITLE, "claude-privacy-diagnostics.txt")
                        }, 1)
                    } catch (_: Exception) {
                        finishWith("未找到文件保存界面，请启用系统文件管理器后重试。")
                    }
                }
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        report?.let { outState.putString("report", it.absolutePath) }
        outState.putBoolean("pickerLaunched", pickerLaunched)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Activity result API matches this framework-only Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        val destination = data?.data
        if (resultCode != RESULT_OK || destination == null) {
            finishWith("已取消导出")
            return
        }
        Thread {
            val result = runCatching {
                val file = checkNotNull(report)
                checkNotNull(contentResolver.openOutputStream(destination, "wt")).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                }
            }
            runOnUiThread {
                finishWith(if (result.isSuccess) "诊断日志已保存" else "日志保存失败，请重新选择位置。")
            }
        }.start()
    }

    private fun finishWith(message: String) {
        report?.delete()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) report?.delete()
        super.onDestroy()
    }
}
