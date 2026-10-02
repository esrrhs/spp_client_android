package com.esrrhs.spp.client.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRuleMatcherTest {

    @Test
    fun parse_ignoresBlankLinesCommentsAndNormalizesPrefixes() {
        val text = """
            # comment
            baidu.com

            .taobao.com
            *.tmall.com
              Example.COM
        """.trimIndent()
        assertEquals(
            setOf("baidu.com", "taobao.com", "tmall.com", "example.com"),
            DomainRuleMatcher.parse(text),
        )
    }

    @Test
    fun matches_apexAndSubdomains_caseInsensitive() {
        val rules = setOf("baidu.com")
        assertTrue(DomainRuleMatcher.matches("baidu.com", rules))
        assertTrue(DomainRuleMatcher.matches("www.baidu.com", rules))
        assertTrue(DomainRuleMatcher.matches("WWW.Baidu.Com.", rules))
    }

    @Test
    fun matches_doesNotOverlapUnrelatedSuffix() {
        val rules = setOf("baidu.com")
        assertFalse(DomainRuleMatcher.matches("notbaidu.com", rules))
        assertFalse(DomainRuleMatcher.matches("evilbaidu.com", rules))
    }

    @Test
    fun matches_nullBlankOrEmptyRules() {
        assertFalse(DomainRuleMatcher.matches(null, setOf("a.com")))
        assertFalse(DomainRuleMatcher.matches("  ", setOf("a.com")))
        assertFalse(DomainRuleMatcher.matches("a.com", emptySet()))
    }
}
