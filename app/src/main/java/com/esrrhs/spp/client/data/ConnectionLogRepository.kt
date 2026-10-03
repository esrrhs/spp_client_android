package com.esrrhs.spp.client.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.connectionLogStore by preferencesDataStore(name = "spp_connection_log")

/** 单连接历史持久化：最多 [MAX_ENTRIES] 条、保留 [MAX_AGE_MS]，按结束时间倒序。 */
class ConnectionLogRepository(context: Context) {

    private val store = context.applicationContext.connectionLogStore
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ConnectionLogEntry.serializer())

    val entries: Flow<List<ConnectionLogEntry>> = store.data.map { prefs ->
        prefs[Keys.ENTRIES_JSON]?.let {
            runCatching { json.decodeFromString(serializer, it) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    /** 追加已结束的记录并裁剪；同 key 的旧记录会被新记录覆盖。 */
    suspend fun append(finished: List<ConnectionLogEntry>, nowMs: Long) {
        if (finished.isEmpty()) return
        store.edit { prefs ->
            val current = decode(prefs[Keys.ENTRIES_JSON])
            val byKey = LinkedHashMap<String, ConnectionLogEntry>(current.size + finished.size)
            current.forEach { byKey[it.key + "@" + it.startMs] = it }
            finished.forEach { byKey[it.key + "@" + it.startMs] = it }
            val pruned = byKey.values
                .asSequence()
                .filter { nowMs - maxOf(it.endMs, it.startMs) <= MAX_AGE_MS }
                .sortedByDescending { maxOf(it.endMs, it.startMs) }
                .take(MAX_ENTRIES)
                .toList()
            prefs[Keys.ENTRIES_JSON] = json.encodeToString(serializer, pruned)
        }
    }

    suspend fun clear() {
        store.edit { it.remove(Keys.ENTRIES_JSON) }
    }

    suspend fun delete(key: String, startMs: Long) {
        store.edit { prefs ->
            val list = decode(prefs[Keys.ENTRIES_JSON])
                .filterNot { it.key == key && it.startMs == startMs }
            prefs[Keys.ENTRIES_JSON] = json.encodeToString(serializer, list)
        }
    }

    private fun decode(stored: String?): List<ConnectionLogEntry> =
        if (stored == null) {
            emptyList()
        } else {
            runCatching { json.decodeFromString(serializer, stored) }.getOrDefault(emptyList())
        }

    private object Keys {
        val ENTRIES_JSON = stringPreferencesKey("entries_json")
    }

    companion object {
        const val MAX_ENTRIES = 1000
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 30 天
    }
}
