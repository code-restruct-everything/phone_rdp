package com.example.phonerdp.domain.usecase

import com.example.phonerdp.domain.model.ConnectionConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class UpsertRecentConnectionUseCaseTest {
    private val useCase = UpsertRecentConnectionUseCase()

    @Test
    fun `should move same endpoint and user to head`() {
        val old = listOf(
            ConnectionConfig("a.example.com", 3389, "alice", "old-pass", "corp"),
            ConnectionConfig("b.example.com", 3389, "bob", "pass2", null),
        )
        val incoming = ConnectionConfig("a.example.com", 3389, "alice", "new-pass", "corp")

        val result = useCase(old, incoming, maxCount = 5)

        assertEquals(2, result.size)
        assertEquals("a.example.com", result[0].host)
        assertEquals("new-pass", result[0].password)
        assertEquals("b.example.com", result[1].host)
    }

    @Test
    fun `should enforce max count`() {
        val existing = (1..6).map { index ->
            ConnectionConfig("10.0.0.$index", 3389, "u$index", "p$index", null)
        }
        val incoming = ConnectionConfig("10.0.0.99", 3389, "u99", "p99", null)

        val result = useCase(existing, incoming, maxCount = 5)

        assertEquals(5, result.size)
        assertEquals("10.0.0.99", result.first().host)
        assertEquals("10.0.0.4", result.last().host)
    }
}
