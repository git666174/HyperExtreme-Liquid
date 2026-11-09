package com.frederic22.hyperextreme.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「执行结果」的持久化日志（2026-10-04 用户要求）。
 *
 * 原来那套是「设置页 → 测试 → 上次执行结果」卡片：日志只活在内存（`MaskUiState.replaceLog`）里，
 * 替换完那次灭亮屏 + 系统回收把进程一杀，再打开设置页就什么都没有了。现在改为：
 *
 * * 卡片**删除**；
 * * 每次「立即替换」把整段结果日志**追加**到应用数据目录下的文本文件；
 * * 每条带时间戳，最多保留 [MAX_LINES] 行（超了从最早的开始丢）。
 *
 * 文件路径：`/data/user/0/com.frederic22.hyperextreme/run_log.txt`（`context.dataDir` 下，
 * 就是用户指定的那个目录）。用 root 或 MT 管理器直接看即可。
 */
object RunLog {

    const val FILE_NAME = "run_log.txt"

    /** 最多保留这么多行。 */
    private const val MAX_LINES = 500

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val lock = Any()

    /** 追加一段执行结果；整个读-改-写都在 [lock] 里做，界面连点也不会交叉。 */
    fun append(context: Context, title: String, lines: List<String>, ok: Boolean = true) {
        val file = File(context.dataDir, FILE_NAME)
        synchronized(lock) {
            runCatching {
                val block = buildList {
                    add("===== ${stamp.format(Date())} $title（${if (ok) "成功" else "失败"}）=====")
                    lines.forEach { line ->
                        line.lineSequence().forEach { add(it) }
                    }
                }
                val merged = file.takeIf { it.isFile }?.readLines().orEmpty() + block
                file.writeText(merged.takeLast(MAX_LINES).joinToString("\n") + "\n")
            }
        }
    }
}
