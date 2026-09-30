package com.esrrhs.spp.client.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.esrrhs.spp.client.spp.SppConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "spp_config")

/** DataStore 持久化的连接配置仓库。 */
class ConfigRepository(context: Context) {

    private val dataStore = context.applicationContext.dataStore

    private object Keys {
        val SERVER_HOST = stringPreferencesKey("server_host")
        val SERVER_PORT = intPreferencesKey("server_port")
        val PROTO = stringPreferencesKey("proto")
        val KEY = stringPreferencesKey("key")
        val ENCRYPT = stringPreferencesKey("encrypt")
        val COMPRESS = intPreferencesKey("compress")
        val ENABLE_IPV6 = booleanPreferencesKey("enable_ipv6")
    }

    val config: Flow<SppConfig> = dataStore.data.map { prefs ->
        SppConfig(
            serverHost = prefs[Keys.SERVER_HOST] ?: "",
            serverPort = prefs[Keys.SERVER_PORT] ?: 8888,
            proto = prefs[Keys.PROTO] ?: SppConfig.PROTOS.first(),
            key = prefs[Keys.KEY] ?: "",
            encrypt = prefs[Keys.ENCRYPT] ?: "",
            compress = prefs[Keys.COMPRESS] ?: 0,
            enableIpv6 = prefs[Keys.ENABLE_IPV6] ?: true,
        )
    }

    suspend fun save(config: SppConfig) {
        dataStore.edit { prefs ->
            prefs[Keys.SERVER_HOST] = config.serverHost.trim()
            prefs[Keys.SERVER_PORT] = config.serverPort
            prefs[Keys.PROTO] = config.proto
            prefs[Keys.KEY] = config.key
            prefs[Keys.ENCRYPT] = config.encrypt
            prefs[Keys.COMPRESS] = config.compress
            prefs[Keys.ENABLE_IPV6] = config.enableIpv6
        }
    }
}
