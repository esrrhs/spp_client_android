package com.esrrhs.spp.client.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "spp_settings")

/** 全局设置仓库。 */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.settingsStore

    private object Keys {
        val BOOT_START = booleanPreferencesKey("boot_start")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val DEFAULT_BYPASS_LAN = booleanPreferencesKey("default_bypass_lan")
    }

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            bootStart = prefs[Keys.BOOT_START] ?: false,
            autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: false,
            defaultBypassLan = prefs[Keys.DEFAULT_BYPASS_LAN] ?: false,
        )
    }

    suspend fun save(settings: AppSettings) {
        store.edit { prefs ->
            prefs[Keys.BOOT_START] = settings.bootStart
            prefs[Keys.AUTO_RECONNECT] = settings.autoReconnect
            prefs[Keys.DEFAULT_BYPASS_LAN] = settings.defaultBypassLan
        }
    }
}
