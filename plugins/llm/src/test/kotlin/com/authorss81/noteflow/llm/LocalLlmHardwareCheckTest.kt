package com.authorss81.noteflow.llm

import com.authorss81.noteflow.llm.policy.LocalLlmHardwareCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 29: pure-JVM tests for the downloadable LLM plugin's device-capability
 * gate. The plugin must never depend on the app's own `DeviceCompatibilityManager`,
 * so its gate logic is a self-contained pure function tested here directly.
 */
class LocalLlmHardwareCheckTest {

    private val gib = 1024L * 1024L * 1024L

    @Test
    fun `mid range hardware is supported`() {
        assertEquals(
            LocalLlmHardwareCheck.Result.Supported,
            LocalLlmHardwareCheck.evaluate(cpuCores = 4, totalMemoryBytes = 4 * gib, isLowRamDevice = false)
        )
    }

    @Test
    fun `two cores are rejected with a core-count reason`() {
        val result = LocalLlmHardwareCheck.evaluate(cpuCores = 2, totalMemoryBytes = 6 * gib, isLowRamDevice = false)
        assertTrue(result is LocalLlmHardwareCheck.Result.Unsupported)
        assertTrue((result as LocalLlmHardwareCheck.Result.Unsupported).reason.contains("2"))
    }

    @Test
    fun `less than three gigabytes of ram is rejected`() {
        val result = LocalLlmHardwareCheck.evaluate(cpuCores = 4, totalMemoryBytes = 2 * gib, isLowRamDevice = false)
        assertTrue(result is LocalLlmHardwareCheck.Result.Unsupported)
        assertTrue((result as LocalLlmHardwareCheck.Result.Unsupported).reason.contains("RAM"))
    }

    @Test
    fun `the low ram flag rejects regardless of other specs`() {
        val result = LocalLlmHardwareCheck.evaluate(cpuCores = 8, totalMemoryBytes = 8 * gib, isLowRamDevice = true)
        assertTrue(result is LocalLlmHardwareCheck.Result.Unsupported)
        assertTrue((result as LocalLlmHardwareCheck.Result.Unsupported).reason.contains("low-RAM"))
    }

    @Test
    fun `unknown ram size does not reject a device with enough cores`() {
        assertEquals(
            LocalLlmHardwareCheck.Result.Supported,
            LocalLlmHardwareCheck.evaluate(cpuCores = 4, totalMemoryBytes = 0, isLowRamDevice = false)
        )
    }

    @Test
    fun `boundary of exactly three cores is supported`() {
        assertEquals(
            LocalLlmHardwareCheck.Result.Supported,
            LocalLlmHardwareCheck.evaluate(cpuCores = 3, totalMemoryBytes = 4 * gib, isLowRamDevice = false)
        )
    }
}
