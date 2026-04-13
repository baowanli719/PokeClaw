package io.agents.pokeclaw.agent.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBackendHealthTest {

    @Test
    fun `promotes pending gpu init crash on same device within age window`() {
        val now = 1_000_000L
        assertTrue(
            LocalBackendHealth.shouldPromotePendingGpuCrash(
                currentDeviceKey = "device-a",
                pendingDeviceKey = "device-a",
                pendingAtMs = now - 5_000L,
                nowMs = now,
            )
        )
    }

    @Test
    fun `does not promote pending gpu init crash for another device`() {
        val now = 1_000_000L
        assertFalse(
            LocalBackendHealth.shouldPromotePendingGpuCrash(
                currentDeviceKey = "device-a",
                pendingDeviceKey = "device-b",
                pendingAtMs = now - 5_000L,
                nowMs = now,
            )
        )
    }

    @Test
    fun `does not promote stale pending gpu init crash`() {
        val now = 1_000_000L
        assertFalse(
            LocalBackendHealth.shouldPromotePendingGpuCrash(
                currentDeviceKey = "device-a",
                pendingDeviceKey = "device-a",
                pendingAtMs = now - 100_000L,
                nowMs = now,
                maxAgeMs = 10_000L,
            )
        )
    }
}
