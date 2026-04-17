package com.example.phonerdp.domain.usecase

import com.example.phonerdp.domain.model.ConnectionConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentConnectionLimitUseCaseTest {
    private val useCase = RecentConnectionLimitUseCase()

    @Test
    fun `apply limit should keep first five`() {
        val list = (1..7).map {
            ConnectionConfig(
                host = "192.168.1.$it",
                port = 3389,
                username = "u$it",
                password = "p$it"
            )
        }

        val result = useCase.applyLimit(list, maxCount = 5)

        assertEquals(5, result.size)
        assertEquals("192.168.1.1", result.first().host)
        assertEquals("192.168.1.5", result.last().host)
    }
}