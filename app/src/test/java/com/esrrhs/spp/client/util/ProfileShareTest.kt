package com.esrrhs.spp.client.util

import com.esrrhs.spp.client.spp.PerAppMode
import com.esrrhs.spp.client.spp.Profile
import com.esrrhs.spp.client.spp.SppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileShareTest {

    private fun sample() = Profile(
        id = "id-1",
        name = "home",
        config = SppConfig(
            serverHost = "example.com",
            serverPort = 8888,
            proto = "tcp",
            key = "secret",
            enableIpv6 = false,
        ),
        perAppMode = PerAppMode.DISALLOWED,
        perAppPackages = listOf("com.example.app"),
        bypassLan = true,
        pingMs = 42,
    )

    @Test
    fun roundTrip_preservesAllFields() {
        val encoded = ProfileShare.encode(sample())
        val decoded = ProfileShare.decode(encoded)
        assertEquals(sample(), decoded)
    }

    @Test
    fun encoded_hasSchemePrefix() {
        assert(ProfileShare.encode(sample()).startsWith(ProfileShare.SCHEME))
    }

    @Test
    fun decode_withoutScheme_returnsNull() {
        assertNull(ProfileShare.decode("not-scheme-data"))
    }

    @Test
    fun decode_corruptedBase64_returnsNull() {
        assertNull(ProfileShare.decode(ProfileShare.SCHEME + "!!!not-base64!!"))
    }
}
