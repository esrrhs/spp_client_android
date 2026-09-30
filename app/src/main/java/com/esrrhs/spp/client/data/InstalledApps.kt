package com.esrrhs.spp.client.data

import android.content.Context

/** 可代理的已安装 App。 */
data class InstalledApp(
    val packageName: String,
    val label: String,
)

/** 列出可启动的已安装 App（用于分应用代理勾选）。 */
object InstalledApps {

    fun list(context: Context, excludeSelf: Boolean = true): List<InstalledApp> {
        val pm = context.packageManager
        return pm.getInstalledApplications(0)
            .filter { app ->
                (!excludeSelf || app.packageName != context.packageName) &&
                    pm.getLaunchIntentForPackage(app.packageName) != null
            }
            .map { app ->
                InstalledApp(
                    packageName = app.packageName,
                    label = runCatching { app.loadLabel(pm).toString() }
                        .getOrDefault(app.packageName),
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}
