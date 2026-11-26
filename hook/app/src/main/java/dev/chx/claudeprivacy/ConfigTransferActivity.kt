package dev.chx.claudeprivacy

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ConfigTransferActivity : Activity() {
    private val control = "/data/adb/modules/claude_privacy/bin/control.sh"
    private var pending: String? = null
    private var pickerLaunched = false
    private val importing get() = intent.getStringExtra("operation") == "import"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pending = savedInstanceState?.getString("config")
        pickerLaunched = savedInstanceState?.getBoolean("pickerLaunched") ?: false
        if (pickerLaunched) return
        if (importing && pending != null) {
            confirmImport(JSONObject(checkNotNull(pending)))
        } else if (importing) {
            openPicker()
        } else {
            work("配置读取失败，请确认 Hook 的 Root 授权后重试。", {
                val text = root("get")
                check(text.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES)
                JSONObject(text).toString()
            }) { text ->
                pending = text
                Toast.makeText(this, "配置包含 IP 白名单与替身标识，请妥善保存。", Toast.LENGTH_LONG).show()
                openPicker()
            }
        }
    }

    private fun openPicker() {
        try {
            pickerLaunched = true
            startActivityForResult(Intent(if (importing) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                if (!importing) putExtra(Intent.EXTRA_TITLE, "claude-privacy-config.json")
            }, 1)
        } catch (_: Exception) {
            finishWith("未找到文件选择界面，请启用系统文件管理器后重试。")
        }
    }

    @Deprecated("Activity result API matches this framework-only Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        pickerLaunched = false
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            finishWith(if (importing) "已取消导入" else "已取消导出")
            return
        }
        if (importing) {
            work("配置读取失败，请重新选择文件。", {
                val text = checkNotNull(contentResolver.openInputStream(uri)).use { input ->
                    val bytes = ByteArray(MAX_CONFIG_BYTES + 1)
                    var count = 0
                    while (count < bytes.size) {
                        val read = input.read(bytes, count, bytes.size - count)
                        if (read < 0) break
                        count += read
                    }
                    require(count <= MAX_CONFIG_BYTES) { "配置文件超过 16 KiB" }
                    String(bytes, 0, count, Charsets.UTF_8)
                }
                parseConfigBackup(text, JSONObject(root("defaults")))
            }) { config ->
                pending = config.toString()
                confirmImport(config)
            }
        } else {
            work("配置保存失败，请重新选择位置。", {
                checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { output ->
                    output.write(checkNotNull(pending).toByteArray(Charsets.UTF_8))
                }
            }) {
                DiagnosticEvents.record("config_exported")
                finishWith("配置已导出")
            }
        }
    }

    private fun confirmImport(config: JSONObject) {
        val integrityWarning = if (config.getJSONObject("spoof").optBoolean("playIntegrity"))
            "\n\nPlay Integrity 拦截已开启。不建议开启，可能影响登录或支付。" else ""
        val message = "将覆盖当前配置并重新锁定目标应用。\n\n" +
            "允许的 IPv4：${config.getJSONArray("allowedIps").length()} 个\n" +
            "时区：${config.getString("timezone")}\n语言：${config.getString("language")}\n\n" +
            "Android ID、设备替身和全部开关将沿用文件中的值。导入后请重启 Claude／Duet。" + integrityWarning
        AlertDialog.Builder(this)
            .setTitle("导入配置？")
            .setMessage(message)
            .setNegativeButton("取消") { _, _ -> finishWith("已取消导入") }
            .setOnCancelListener { finishWith("已取消导入") }
            .setPositiveButton("覆盖并导入") { _, _ ->
                work("导入失败，请确认 Root 授权后重试。", {
                    val encoded = Base64.encodeToString(checkNotNull(pending).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    root("import-config $encoded")
                }) {
                    DiagnosticEvents.record("config_imported")
                    finishWith("配置已导入，请重启 Claude／Duet 生效。")
                }
            }.show()
    }

    private fun root(arguments: String): String {
        val output = File.createTempFile("config-transfer-", ".tmp", cacheDir)
        return try {
            val process = ProcessBuilder("su", "-c", "sh $control $arguments")
                .redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(12, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("root_timeout")
            }
            check(process.exitValue() == 0) { "root_failed" }
            output.readText(Charsets.UTF_8).trim()
        } finally {
            output.delete()
        }
    }

    private fun <T> work(failure: String, action: () -> T, complete: (T) -> Unit) {
        Toast.makeText(this, "正在处理配置…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching(action)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(complete) { error ->
                    finishWith(if (error is IllegalArgumentException) error.message ?: failure else failure)
                }
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("config", pending)
        outState.putBoolean("pickerLaunched", pickerLaunched)
        super.onSaveInstanceState(outState)
    }

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
