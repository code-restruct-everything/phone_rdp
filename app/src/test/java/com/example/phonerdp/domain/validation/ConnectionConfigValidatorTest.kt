package com.example.phonerdp.domain.validation

import com.example.phonerdp.domain.model.ConnectionConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionConfigValidatorTest {
    private val validator = ConnectionConfigValidator()

    @Test
    fun `valid config should pass`() {
        val result = validator.validate(
            ConnectionConfig(
                host = "192.168.1.8",
                port = 3389,
                username = "admin",
                password = "secret"
            )
        )

        assertTrue(result.isValid)
    }

    @Test
    fun `invalid port should fail`() {
        val result = validator.validate(
            ConnectionConfig(
                host = "rdp.example.com",
                port = 70000,
                username = "admin",
                password = "secret"
            )
        )

        assertFalse(result.isValid)
    }
}