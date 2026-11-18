package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.Game
import io.github.zahakj.tandem.Platform
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RommAvailabilityTest {
    @get:Rule val folder = TemporaryFolder()
    private val platform = RommPlatformInfo(4, "Game Boy Advance", "gba", "gba")
    private val game = RommGameInfo(9, 4, "Fixture.gba", 64)
    private val local = RommLocalFile("gba", setOf("Game Boy Advance", "gba"), "Fixture.gba", 64)

    @Test fun matchingNamesWithinOnePlatformAreOnlyFileCandidates() {
        assertEquals(RommAvailability.LocalMatch, RommLocalIndex(listOf(local)).match(platform, game))
        assertTrue(RommAvailability.LocalMatch.explanation.contains("not been checksum-verified"))
    }
    @Test fun aTitleMatchWithoutTheFilenameDoesNotCount() {
        assertEquals(RommAvailability.Remote, RommLocalIndex(listOf(local)).match(platform, game.copy(fileName = "Other.gba", name = "Fixture")))
    }
    @Test fun sameFileOnDifferentPlatformsIsNotAnInstalledMatch() {
        assertEquals(RommAvailability.Remote, RommLocalIndex(listOf(local.copy(platform = "snes", platformNames = setOf("Super Nintendo")))).match(platform, game))
    }
    @Test fun sizeMismatchIsNotALocalMatch() {
        assertEquals(RommAvailability.DifferentSize, RommLocalIndex(listOf(local.copy(size = 100))).match(platform, game))
    }
    @Test fun multipleFilesAreAmbiguousEvenWithIdenticalMetadata() {
        assertEquals(RommAvailability.Ambiguous, RommLocalIndex(listOf(local, local)).match(platform, game))
    }
    @Test fun filenamesStayCaseSensitive() {
        assertEquals(RommAvailability.Remote, RommLocalIndex(listOf(local.copy(fileName = "fixture.gba"))).match(platform, game))
    }
    @Test fun emptyOrUnknownPlatformsDoNotGuessBasedOnExtensions() {
        assertEquals(RommAvailability.Remote, RommLocalIndex(listOf(local)).match(platform.copy(name = "Unknown", slug = "", fsSlug = ""), game))
        assertEquals(RommAvailability.Remote, RommLocalIndex(emptyList()).match(platform, game))
    }
    @Test fun savedSystemDetectsOlderDownloadsOfUnmatchedPlatforms() {
        val unknown = platform.copy(name = "Unknown", slug = "", fsSlug = "")
        val state = folder.newFolder()
        RommDownloadStore(state).remember("source:4", "gba|/chosen")
        RommDownloadStore(state).remember("other:4", "snes|/elsewhere")
        val reopened = RommDownloadStore(state)
        val match = RommLocalIndex(listOf(local), "source", reopened.mappedSystems("source")).match(unknown, game)
        assertTrue(match.installed)
        assertEquals("Game Downloaded", match.label)
        assertFalse(RommLocalIndex(listOf(local), "other", reopened.mappedSystems("other")).match(unknown, game).installed)
    }
    @Test fun confirmedOriginSurvivesDestinationChangesButMustStillMatchTheLocalFile() {
        val unknown = platform.copy(name = "Unknown", slug = "", fsSlug = "")
        val oldDownload = local.copy(origin = RommInstallOrigin("source", platform.id, game.id))
        val newMapping = mapOf(platform.id to "snes")
        assertEquals(RommAvailability.Downloaded, RommLocalIndex(listOf(oldDownload), "source", newMapping).match(unknown, game))
        assertFalse(RommLocalIndex(emptyList(), "source", newMapping).match(unknown, game).installed)
        assertFalse(RommLocalIndex(listOf(oldDownload.copy(size = 65)), "source", newMapping).match(unknown, game).installed)
        assertFalse(RommLocalIndex(listOf(oldDownload), "other", newMapping).match(unknown, game).installed)
        assertFalse(RommLocalIndex(listOf(oldDownload), "source", newMapping).match(unknown, game.copy(id = 99)).installed)
        assertFalse(RommLocalIndex(listOf(oldDownload), "source", newMapping).match(unknown, game.copy(fileName = "Replacement.gba")).installed)
    }
    @Test fun zeroByteRecordsAreNeverInstalled() {
        assertFalse(RommLocalIndex(listOf(local.copy(size = 0))).match(platform, game.copy(size = 0)).installed)
    }
    @Test fun staleScanRecordStopsMatchingWhenTheDownloadedFileIsRemoved() {
        val file = folder.newFile(game.fileName).apply { writeBytes(ByteArray(game.size.toInt())) }
        val system = Platform("gba", "Game Boy Advance", "GBA", listOf("gba"), setOf("gba"), emptySet(), emptyList(), emptyList())
        val scanned = Game(system, file, "Fixture", null)
        fun availability() = RommLocalIndex(listOfNotNull(RommLocalFile.read(scanned))).match(platform, game)
        assertTrue(availability().installed)
        file.writeBytes(byteArrayOf(1))
        assertEquals(RommAvailability.DifferentSize, availability())
        assertTrue(file.delete())
        assertEquals(RommAvailability.Remote, availability())
    }
    @Test fun pageCacheIsBoundedAndRetainsRecentlyReadPages() {
        val cache = RommPageCache(2)
        val page = RommGamePage(listOf(game), 1, 50, 0)
        val a = RommPageCache.Key(4, "", 0); val b = a.copy(offset = 50); val c = a.copy(search = "new")
        cache.put(a, page); cache.put(b, page); assertNotNull(cache.get(a)); cache.put(c, page)
        assertNull(cache.get(b)); assertNotNull(cache.get(a)); assertNotNull(cache.get(c))
        cache.clear(); assertNull(cache.get(a))
    }
    @Test fun coverCacheBoundsBytesReplacesEntriesAndClears() {
        val cache = RommArtworkCache(4)
        cache.put("a", byteArrayOf(1, 2)); cache.put("b", byteArrayOf(3, 4)); cache.get("a")
        cache.put("c", byteArrayOf(5, 6)); assertNull(cache.get("b"))
        cache.put("a", byteArrayOf(7)); assertArrayEquals(byteArrayOf(7), cache.get("a"))
        cache.put("huge", ByteArray(5)); assertNull(cache.get("huge"))
        cache.clear(); assertNull(cache.get("a"))
    }
    @Test fun coverUrlsAreConfinedToTheConfiguredSubpathAndExactGame() {
        val cover = game.copy(coverSmall = "/assets/romm/resources/roms/4/9/cover/small.webp?ts=2026-11-16 12:00:00")
        assertEquals("https://fixture.example.test/romm/assets/romm/resources/roms/4/9/cover/small.webp", RommArtworkAddress.resolve("https://fixture.example.test/romm", cover))
        assertEquals(RommArtworkAddress.resolve("https://fixture.example.test", cover), RommArtworkAddress.resolve("https://fixture.example.test", cover.copy(coverSmall = "", coverLarge = cover.coverSmall)))
    }
    @Test fun untrustedOrNonCoverResourceUrlsAreNeverRequested() {
        val prefix = "/assets/romm/resources/roms/4/9/cover/"
        for (path in listOf("https://other.example.test/small.png", "http://fixture.example.test/small.png", "//other.example.test/small.png", "/api/roms/9/content/file", prefix + "../file", prefix + "%2e%2e/small.png", prefix + "small.png#fragment", prefix + "evil.svg", "/assets/romm/resources/roms/4/10/cover/small.png")) {
            assertNull(RommArtworkAddress.resolve("https://fixture.example.test", game.copy(coverSmall = path)))
        }
    }
}
