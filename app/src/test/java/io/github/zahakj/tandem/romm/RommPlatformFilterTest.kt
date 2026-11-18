package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.data.HomeSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RommPlatformFilterTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val empty = RommPlatformInfo(1, "Empty", romCount = 0)
    private val populated = RommPlatformInfo(2, "Games", romCount = 3)

    @Test fun newAndExistingSettingsHideEmptyPlatformsByDefault() {
        assertTrue(HomeSettings().rommHideEmptyPlatforms)
        // An old settings record has no RoMM preference. Existing unrelated choices must survive decoding.
        val old = json.decodeFromString<HomeSettings>("""{"showDock":false,"screensaverMinutes":5}""")
        assertTrue(old.rommHideEmptyPlatforms)
        assertFalse(old.showDock)
        assertEquals(5, old.screensaverMinutes)
    }

    @Test fun optingOutSurvivesSettingsSerialization() {
        val settings = HomeSettings(rommHideEmptyPlatforms = false)
        val reloaded = json.decodeFromString<HomeSettings>(json.encodeToString(settings))
        assertFalse(reloaded.rommHideEmptyPlatforms)
        assertTrue(json.decodeFromString<HomeSettings>(json.encodeToString(reloaded.copy(rommHideEmptyPlatforms = true))).rommHideEmptyPlatforms)
    }

    @Test fun hideEmptyUsesCountsAndPreservesOrderWithoutChangingTheLoadedLibrary() {
        val large = populated.copy(id = 3, romCount = Long.MAX_VALUE)
        val loaded = listOf(empty, large, populated)
        assertEquals(listOf(large, populated), visibleRommPlatforms(loaded, true))
        assertEquals(listOf(empty, large, populated), loaded)
        assertEquals(loaded, visibleRommPlatforms(loaded, false))
    }

    @Test fun allEmptyOrNoPlatformsYieldAnEmptyProjection() {
        assertTrue(visibleRommPlatforms(listOf(empty), true).isEmpty())
        assertEquals(listOf(empty), visibleRommPlatforms(listOf(empty), false))
        assertTrue(visibleRommPlatforms(emptyList(), true).isEmpty())
    }
}
