package com.esrrhs.spp.client

import android.app.Application
import android.content.Context
import com.esrrhs.spp.client.data.SettingsRepository
import com.esrrhs.spp.client.util.AppLanguage
import com.esrrhs.spp.client.util.DirectProxySelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 进程入口。
 */
class SppApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrapIfNeeded(base))
    }

    override fun onCreate() {
        super.onCreate()
        DirectProxySelector.install()

        // 启动时初始化语言配置
        appScope.launch {
            val settings = SettingsRepository(this@SppApp).current()
            if (settings.appLanguage.isNotEmpty()) {
                AppLanguage.applyLanguage(this@SppApp, settings.appLanguage)
            }
        }
    }
}
