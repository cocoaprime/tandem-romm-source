package io.github.zahakj.tandem.romm

import kotlinx.coroutines.runBlocking
import io.github.zahakj.tandem.Platform
import io.github.zahakj.tandem.data.HomeFolder
import io.github.zahakj.tandem.data.IconGridConfig
import io.github.zahakj.tandem.data.GridSlots
import org.junit.Assert.*
import org.junit.Test

class RommHomeCatalogTest {
    private val source = "a".repeat(64)
    private val shortcut = RommShortcut(source, 11, 9).key
    private val ps2 = Platform("ps2", "PlayStation 2", "PS2", listOf("ps2"), setOf("iso"), emptySet(), emptyList(), emptyList())
    private val gba = Platform("gba", "Game Boy Advance", "GBA", listOf("gba"), setOf("gba"), emptySet(), emptyList(), emptyList())
    private val remote = RommPlatformInfo(11, "PlayStation 2", "ps2", romCount = 2)
    private val game = RommGameInfo(9, 11, "Fixture.iso", 64)

    @Test fun homeUsesExistingSystemAndKeepsRemoteOnlyGroups() {
        val unknown = RommPlatformInfo(12, "Unrecognized System", romCount = 4)
        val groups = homeRommSystems(listOf(remote, unknown, remote.copy(id = 13, romCount = 0)), listOf(ps2, gba), emptyMap(), emptySet())
        assertEquals(setOf("ps2", "romm:12"), groups.keys)
        assertEquals(ps2, groups.getValue("ps2").platform)
        assertEquals(listOf(unknown), groups.getValue("romm:12").remote)
    }

    @Test fun explicitMappingTakesPrecedenceAndDisabledSystemsDoNotReappear() {
        val mapped = mapOf(remote.id to gba.id)
        val local = listOf(ps2, gba)
        assertEquals(setOf(gba.id), homeRommSystems(listOf(remote), local, mapped, emptySet()).keys)
        assertTrue(homeRommSystems(listOf(remote), local, mapped, setOf(gba.id)).isEmpty())
    }

    @Test fun ambiguousAliasesStaySeparateUntilTheUserMapsThem() {
        val second = ps2.copy(id = "ps2-alt")
        val groups = homeRommSystems(listOf(remote), listOf(ps2, second), emptyMap(), emptySet())
        assertEquals(setOf("romm:11"), groups.keys)
    }

    @Test fun movingFromRemoteToLocalSuppressesCloudCardAndHiddenCopiesAreNotExposed() {
        val file = RommLocalFile("ps2", setOf("PlayStation 2"), game.fileName, game.size)
        val empty = RommLocalIndex(emptyList())
        val scanned = RommLocalIndex(listOf(file))
        assertTrue(game.availableOnHome(empty.match(remote, game), empty.match(remote, game)))
        assertFalse(game.availableOnHome(RommAvailability.Downloaded, empty.match(remote, game)))
        assertFalse(game.availableOnHome(scanned.match(remote, game), scanned.match(remote, game)))
        assertFalse(game.availableOnHome(empty.match(remote, game), scanned.match(remote, game)))
        val conflict = RommLocalIndex(listOf(file.copy(size = 65))).match(remote, game)
        assertFalse(game.availableOnHome(conflict, conflict))
    }

    @Test fun unavailableOrUnsupportedRecordsDoNotOfferDownloadOnHome() {
        for (entry in listOf(game.copy(onDisk = false), game.copy(size = 0))) {
            assertFalse(entry.availableOnHome(RommAvailability.Remote, RommAvailability.Remote))
        }
        assertTrue(game.copy(simpleFile = false).availableOnHome(RommAvailability.Remote, RommAvailability.Remote))
    }

    @Test fun resolvedSystemIdMatchesFileEvenWhenAliasNamesUseDifferentPunctuation() {
        val file = RommLocalFile("ps2", setOf("PlayStation 2"), game.fileName, game.size)
        val remoteAlias = remote.copy(name = "Sony console", slug = "other-ps2-name")
        val index = RommLocalIndex(listOf(file))
        assertEquals(RommAvailability.Remote, index.match(remoteAlias, game))
        assertEquals(RommAvailability.LocalMatch, index.match(remoteAlias, game, localSystem = "ps2"))
    }

    @Test fun paginationRetainsPriorGamesDeduplicatesAndStopsAtEnd() {
        val second = game.copy(id = 10)
        val firstPage = RommHomePage().append(RommGamePage(listOf(game), 3, 1, 0))
        val next = firstPage.append(RommGamePage(listOf(game, second), 3, 2, 1))
        assertEquals(1, firstPage.nextOffset)
        assertEquals(listOf(game, second), next.games)
        assertNull(next.nextOffset)
        assertNull(firstPage.append(RommGamePage(listOf(game), 20, 1, 1)).nextOffset)
        assertThrows(IllegalArgumentException::class.java) { firstPage.append(RommGamePage(listOf(second), 3, 1, 2)) }
    }

    @Test fun moreGamesTakesTheNextVisibleSlotBeforeTheEmptyEditRow() {
        val local = List(6) { "game:$it" }
        val action = "romm-more:ps2"
        val first = withRommPageAction(local + listOf("", "hidden"), local.toSet(), action)
        assertEquals(6, first.indexOf(action))

        val loaded = local + shortcut
        val after = withRommPageAction(loaded, loaded.toSet(), action)
        assertEquals(7, after.indexOf(action))
        assertEquals(shortcut, after[first.indexOf(action)])
    }

    @Test fun pageActionKeepsInteriorGapsAndDoesNotBecomeAnArrangeableSlot() {
        val saved = listOf("game:one", "", "folder:two", "missing")
        val visible = setOf("game:one", "folder:two")
        assertEquals(saved, withRommPageAction(saved, visible, null))
        assertEquals(listOf("game:one", "", "folder:two", "romm-more:ps2"),
            withRommPageAction(saved, visible, "romm-more:ps2"))
        assertEquals(listOf("romm-more:ps2"), withRommPageAction(emptyList(), emptySet(), "romm-more:ps2"))
    }

    @Test fun pagingFocusFollowsNewGamesWithoutInterruptingOtherNavigation() {
        val more = "romm-more:ps2"
        val old = listOf("game:local", shortcut, more, "gap:3")
        val new = RommShortcut(source, 11, 10).key
        val appended = listOf("game:local", shortcut, new, more)
        assertEquals(new, rommPageFocus(old, appended, more))
        assertEquals(new, rommPageFocus(old, listOf("game:local", new, shortcut), more))
        assertNull(rommPageFocus(old, appended, shortcut))
        assertNull(rommPageFocus(old, old, more))
        assertEquals(shortcut, rommPageFocus(old, listOf("game:local", shortcut, "gap:2"), more))
    }

    @Test fun localGamesFillTheFirstGameSlotsEvenWithManyEarlierRoMMNames() {
        val cloud = List(100) { "romm-game:$source:11:${it + 20}" }
        val local = listOf("game:ps2/Sonic.iso", "game:ps2/Zelda.iso")
        val slots = cloud.take(1) + listOf("folder:arcade", "") + cloud.drop(1) + local
        val sorted = localFirstGameSlots(slots, slots.toSet())
        assertEquals(local + cloud, sorted.filter { it in local || it in cloud })
        assertEquals("folder:arcade", sorted[1])
        assertEquals("", sorted[2])
    }

    @Test fun manualFolderKeepsPlacedTilesAndOrdersUnplacedLocalGamesBeforeRoMM() {
        val placed = listOf("folder:Racing", "Zelda")
        assertEquals(listOf("folder:Racing", "Zelda", "A Link", "Sonic", "Metroid"),
            mergedFolderGames(placed, listOf("Sonic", "A Link"), listOf("Metroid", "Zelda", "Metroid")) { it })
    }

    @Test fun searchPagesAreIndependentOfBrowsePagesAndRetainResultsAfterAFailedRequest() = runBlocking {
        val alias = remote.id + 1
        val platformIds = listOf(remote.id, alias)
        val first = game.copy(fileName = "Sonic.iso")
        val second = game.copy(id = 10, fileName = "Sonic 2.iso")
        val aliasMatch = game.copy(id = 11, platformId = alias, fileName = "Sonic CD.iso")
        val calls = mutableListOf<Triple<Long, String, Int>>()
        val one = RommHomeSearchResults().next(platformIds, "Sonic") { id, query, offset ->
            calls += Triple(id, query, offset)
            if (id == alias) throw java.io.IOException()
            RommGamePage(listOf(first), 2, 1, offset)
        }
        assertEquals(listOf(Triple(remote.id, "Sonic", 0), Triple(alias, "Sonic", 0)), calls)
        assertEquals(setOf(alias), one.failed)
        assertEquals(listOf(first), one.pages.getValue(remote.id).games)
        val retry = one.next(platformIds, "Sonic") { id, query, offset ->
            calls += Triple(id, query, offset)
            RommGamePage(listOf(aliasMatch), 1, 1, offset)
        }
        assertEquals(Triple(alias, "Sonic", 0), calls.last())
        val more = retry.next(platformIds, "Sonic") { id, query, offset ->
            calls += Triple(id, query, offset)
            RommGamePage(listOf(second), 2, 1, offset)
        }
        assertEquals(Triple(remote.id, "Sonic", 1), calls.last())
        assertEquals(listOf(first, second), more.pages.getValue(remote.id).games)
        assertEquals(listOf(aliasMatch), more.pages.getValue(alias).games)
        assertFalse(more.hasMore(platformIds))
    }

    @Test fun genericGridEditsDoNotTurnAutomaticCloudTilesIntoShortcuts() {
        val implicit = RommShortcut(source, 11, 20).key
        val slots = listOf("game:ps2/A.iso", implicit, "", shortcut)
        val saved = keepExplicitRommSlots(slots, setOf(shortcut))
        assertEquals(listOf("game:ps2/A.iso", "romm-auto:1", "", shortcut), saved)
        assertEquals(slots, GridSlots.shown(saved, 4, 4, slots.filter { it.isNotEmpty() }))
        assertEquals(slots, keepExplicitRommSlots(slots, setOf(shortcut, implicit)))
    }

    @Test fun downloadedShortcutBecomesLocalInItsOriginalSlotAndDropsDuplicate() {
        val local = "game:ps2/Fixture.iso"
        val other = RommShortcut("b".repeat(64), 11, 9).key
        val config = IconGridConfig(order = listOf(other, shortcut), added = listOf(other, shortcut),
            folders = listOf(HomeFolder("system", "PS2", listOf(local, "", shortcut), system = "ps2")))
        val updated = config.replaceRommShortcut(shortcut, local)
        assertEquals(listOf(other, local), updated.added)
        assertEquals(listOf(other, local), updated.order)
        assertEquals(listOf("", "", local), updated.folders.single().items)
        assertSame(updated, updated.replaceRommShortcut(shortcut, local))
    }

    @Test fun shortcutRequiresFingerprintAndPositiveNumericIds() {
        assertEquals(RommShortcut(source, 11, 9), RommShortcut.parse(shortcut))
        for (bad in listOf("romm-game:11:9", "romm-game:$source:0:9", "romm-game:$source:11:-9", "romm-game:$source:11:9/evil")) {
            assertNull(RommShortcut.parse(bad))
        }
    }

    @Test fun gamePickerIncludesLocalOnlyRemoteOnlyAndSharedPlatformsButNotEmptyOnes() {
        val gbaRemote = RommPlatformInfo(12, "Game Boy Advance", "gba", romCount = 1)
        val empty = RommPlatformInfo(13, "Empty platform")
        val systems = homeRommSystems(listOf(remote, gbaRemote, empty), listOf(ps2, gba), emptyMap(), emptySet())
        val local = mapOf("ps2" to "PS2", "nes" to "Nintendo Entertainment System")
        assertEquals(listOf("ps2" to "PS2", "gba" to "Game Boy Advance", "nes" to "Nintendo Entertainment System"),
            gamePickerSystems(local, systems, preferred = "ps2"))
        assertEquals(listOf("nes", "ps2"), gamePickerSystems(local, emptyMap(), preferred = null).map { it.first })
        assertTrue(gamePickerSystems(emptyMap(), emptyMap(), preferred = "ps2").isEmpty())
    }
}
