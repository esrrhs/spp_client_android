package com.esrrhs.spp.client.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.historyStore by preferencesDataStore(name = "spp_history")

/** 连接历史持久化：仅保留最近 [MAX_RECORDS] 条。 */
class HistoryRepository(context: Context) {

    private val store = context.applicationContext.historyStore
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ConnectionRecord.serializer())

    val records: Flow<List<ConnectionRecord>> = store.data.map { prefs ->
        prefs[Keys.RECORDS_JSON]?.let {
            runCatching { json.decodeFromString(serializer, it) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun append(record: ConnectionRecord) {
        store.edit { prefs ->
            val list = decode(prefs[Keys.RECORDS_JSON]).toMutableList()
            list.add(record)
            prefs[Keys.RECORDS_JSON] =
                json.encodeToString(serializer, HistoryRecords.trim(list, MAX_RECORDS))
        }
    }

    suspend fun clear() {
        store.edit { it.remove(Keys.RECORDS_JSON) }
    }

    private fun decode(stored: String?): List<ConnectionRecord> =
        if (stored == null) emptyList()
        else runCatching { json.decodeFromString(serializer, stored) }.getOrDefault(emptyList())

    private object Keys {
        val RECORDS_JSON = stringPreferencesKey("records_json")
    }

    companion object {
        const val MAX_RECORDS = 200
    }
}

/** 历史裁剪纯逻辑：超出上限时丢弃最旧的记录。 */
object HistoryRecords {
    fun trim(records: List<ConnectionRecord>, max: Int): List<ConnectionRecord> =
        if (records.size <= max) records else records.takeLast(max)
}
