package com.example.phonerdp.domain.model

import com.example.phonerdp.native_bridge.RdpNativeCodes
import org.junit.Assert.assertEquals
import org.junit.Test

class RdpErrorTest {
    @Test
    fun `should map known error code`() {
        val error = RdpError.fromCode(RdpNativeCodes.NETWORK_FAILURE)
        assertEquals("Network Failure", error.title)
    }

    @Test
    fun `should map unknown error code`() {
        val error = RdpError.fromCode(-9999)
        assertEquals("Unknown Error", error.title)
    }
}
