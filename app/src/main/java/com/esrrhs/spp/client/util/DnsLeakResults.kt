package com.esrrhs.spp.client.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** DNS 泄漏检测到的一台解析器。 */
data class DnsResolver(
    val ip: String,
    val country: String,
    val asnName: String,
)

/**
 * bash.ws DNS leak API 返回 JSON 的纯解析逻辑，便于单测。
 * 元素形如 {"type":"dns","ip":"8.8.8.8","country_name":"US","asn_name":"Google"}，
 * 另有 type=ip（出口 IP）/ conclusion 的元素。
 */
object DnsLeakResults {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<DnsResolver> {
        val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.content != "dns") return@mapNotNull null
            val ip = obj["ip"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            DnsResolver(
                ip = ip,
                country = obj["country_name"]?.jsonPrimitive?.content.orEmpty(),
                asnName = obj["asn_name"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }
}
