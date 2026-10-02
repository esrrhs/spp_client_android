package com.esrrhs.spp.client.spp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileTest {

    private fun validConfig() = SppConfig(
        serverHost = "example.com",
        serverPort = 8888,
        key = "secret",
    )

    @Test
    fun blankName_isRejected() {
        val profile = Profile(name = "", config = validConfig())
        assertEquals(ValidationError.NAME_REQUIRED, profile.validate())
    }

    @Test
    fun validNameAndConfig_pass() {
        val profile = Profile(name = "home", config = validConfig())
        assertNull(profile.validate())
    }

    @Test
    fun invalidConfig_surfacesConfigError() {
        val profile = Profile(name = "home", config = validConfig().copy(key = ""))
        assertEquals(ValidationError.KEY_REQUIRED, profile.validate())
    }

    @Test
    fun allowedModeWithoutApps_isRejected() {
        val profile = Profile(
            name = "home",
            config = validConfig(),
            perAppMode = PerAppMode.ALLOWED,
        )
        assertEquals(ValidationError.APPS_REQUIRED, profile.validate())
    }

    @Test
    fun newProfile_hasUniqueId() {
        val a = Profile()
        val b = Profile()
        assert(a.id != b.id)
    }
}
