package com.esrrhs.spp.client.util

/**
 * 域名分流规则匹配（纯逻辑，便于单测）。
 *
 * 每行一条规则，忽略空行与 # 开头注释；支持以下写法（大小写不敏感）：
 *   example.com       匹配 example.com 及其所有子域名
 *   .example.com      同上（前导点写法）
 *   *.example.com     同上（通配写法）
 *
 * IP 地址与无法取得域名的 UDP 流量不在匹配范围内，统一走代理。
 */
object DomainRuleMatcher {

    /** 解析用户输入的规则文本为规整后的后缀集合。 */
    fun parse(text: String): Set<String> = text
        .lineSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { rule ->
            when {
                rule.startsWith("*.") -> rule.removePrefix("*.")
                rule.startsWith(".") -> rule.removePrefix(".")
                else -> rule
            }
        }
        .filter { it.isNotEmpty() }
        .toSet()

    /** [host] 是否命中规则集合（精确或为其子域名）。 */
    fun matches(host: String?, rules: Set<String>): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.trim().lowercase().removeSuffix(".")
        if (h.isEmpty()) return false
        return rules.any { rule -> h == rule || h.endsWith(".$rule") }
    }
}
