package com.esrrhs.spp.client.util

/**
 * 展示用格式化工具。
 */
internal object Formatters {

    /** 自适应单位格式化字节数（B/KB/MB/GB/TB），保留一位小数。 */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = -1
        do {
            value /= 1024.0
            unitIndex++
        } while (value >= 1024 && unitIndex < units.lastIndex)
        return String.format("%.1f%s", value, units[unitIndex])
    }
}
