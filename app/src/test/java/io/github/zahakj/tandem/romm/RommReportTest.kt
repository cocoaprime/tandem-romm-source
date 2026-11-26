package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.bugReportRedactor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RommReportTest {
    @Test fun reportsRedactRommEvenWhenDisabledAndSecretsAreShort() {
        val config = RommConfig(server = "https://library.example.test/proxy", token = "short-token", enabled = false)
        val redactor = bugReportRedactor(config, listOf("another-secret"), listOf("Hidden title"), emptyList())
        val result = redactor.text("Connected to ${config.server}\nhost library.example.test, ${config.token}, another-secret, Hidden title")
        for (secret in listOf(config.server, "library.example.test", config.token, "another-secret", "Hidden title")) {
            assertFalse("A known private value escaped redaction", result.contains(secret))
        }
        assertTrue(result.contains("Connected to"))
    }

    @Test fun unconfiguredRommStillUsesUpstreamRedaction() {
        val redactor = bugReportRedactor(RommConfig(), listOf("synthetic-secret"), emptyList(), listOf("psx"))
        val result = redactor.text("synthetic-secret /storage/emulated/0/ROMs/psx/Example.chd")
        assertFalse(result.contains("synthetic-secret"))
        assertFalse(result.contains("Example"))
        assertTrue(result.contains("/ROMs/psx/"))
    }
}
