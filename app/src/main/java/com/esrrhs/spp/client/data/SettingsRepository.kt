package com.esrrhs.spp.client.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
        val FAILOVER = booleanPreferencesKey("failover")
        val DEFAULT_BYPASS_LAN = booleanPreferencesKey("default_bypass_lan")
        val TRUSTED_WIFI_ENABLED = booleanPreferencesKey("trusted_wifi_enabled")
        val TRUSTED_WIFI_SSIDS = stringSetPreferencesKey("trusted_wifi_ssids")
        val DOMAIN_DIRECT_ENABLED = booleanPreferencesKey("domain_direct_enabled")
        val DOMAIN_DIRECT_RULES = stringPreferencesKey("domain_direct_rules")
    }

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            bootStart = prefs[Keys.BOOT_START] ?: false,
            autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: false,
            failover = prefs[Keys.FAILOVER] ?: false,
            defaultBypassLan = prefs[Keys.DEFAULT_BYPASS_LAN] ?: false,
            trustedWifiEnabled = prefs[Keys.TRUSTED_WIFI_ENABLED] ?: false,
            trustedWifiSsids = prefs[Keys.TRUSTED_WIFI_SSIDS] ?: emptySet(),
            domainDirectEnabled = prefs[Keys.DOMAIN_DIRECT_ENABLED] ?: false,
            domainDirectRulesText = prefs[Keys.DOMAIN_DIRECT_RULES] ?: "",
        )
    }

    suspend fun save(settings: AppSettings) {
        store.edit { prefs ->
            prefs[Keys.BOOT_START] = settings.bootStart
            prefs[Keys.AUTO_RECONNECT] = settings.autoReconnect
            prefs[Keys.FAILOVER] = settings.failover
            prefs[Keys.DEFAULT_BYPASS_LAN] = settings.defaultBypassLan
            prefs[Keys.TRUSTED_WIFI_ENABLED] = settings.trustedWifiEnabled
            prefs[Keys.TRUSTED_WIFI_SSIDS] = settings.trustedWifiSsids
            prefs[Keys.DOMAIN_DIRECT_ENABLED] = settings.domainDirectEnabled
            prefs[Keys.DOMAIN_DIRECT_RULES] = settings.domainDirectRulesText
        }
    }
}
