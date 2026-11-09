package com.frederic22.hyperextreme.data

import java.util.concurrent.TimeUnit

/**
 * 最小的 root 通道：LSPosed 本身就要求 Magisk / KernelSU / APatch，所以 `su` 一般都在。
 *
 * 有了它，模块就能**不依赖打开锁屏编辑器**，直接把文件写进 com.miui.aod 的私有目录和
 * /data/system/theme_magic —— 那两条路径按 uid/SELinux 只有它的进程（或 root）能写。
 */
object RootShell {

    data class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    fun run(script: String, timeoutSeconds: Long = 60L): Result = runCatching {
        val process = ProcessBuilder("su", "-c", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy()
            Result(-1, output + "\n(执行超时)")
        } else {
            Result(process.exitValue(), output)
        }
    }.getOrElse { error ->
        Result(-1, "无法执行 su：${error.message}")
    }

    fun isAvailable(): Boolean {
        val result = run("id")
        return result.ok && result.output.contains("uid=0")
    }
}
