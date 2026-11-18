package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.Platform
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommDownloadMappingTest {
    @get:Rule val temp = TemporaryFolder()
    private val ps2 = Platform("ps2", "PlayStation 2", "PS2", listOf("ps2"), setOf("iso", "chd"), emptySet(), emptyList(), emptyList())
    private val remote = RommPlatformInfo(11, "Sony PlayStation 2", "ps2", "ps2")
    private val game = RommGameInfo(9, 11, "Fixture.iso", 64)
    private val file = RommDownloadFile(31, 9, "Fixture.iso", 64, true)

    @Test fun reusesExistingPlatformFolderInsteadOfCreatingAnotherRoot() {
        val root = temp.newFolder("Internal")
        val folder = File(root, "ps2").apply { mkdir() }
        File(folder, "Already.iso").writeText("existing")
        val result = RommDestinations.candidates(remote, listOf(ps2), emptyList(), listOf(root))
        assertEquals(folder.canonicalFile, result.single().folder)
        assertEquals(result.single(), RommDestinations.automatic(result, null))
        assertEquals(listOf("ps2"), root.list()!!.toList())
    }

    @Test fun multipleRootsRequireChoiceAndAnExistingRememberedFolderWins() {
        val internal = temp.newFolder("Internal"); File(internal, "ps2").mkdir()
        val sd = temp.newFolder("SD"); File(sd, "ps2").mkdir()
        val choices = RommDestinations.candidates(remote, listOf(ps2), emptyList(), listOf(internal, sd))
        assertEquals(2, choices.size); assertNull(RommDestinations.automatic(choices, null))
        val selected = choices[1]
        assertEquals(selected, RommDestinations.automatic(choices, "${ps2.id}|${selected.folder.path}"))
        assertNull(RommDestinations.automatic(choices, "ps2|/missing"))
    }

    @Test fun directSystemRootsAndExplicitUserFoldersAreRecognized() {
        val direct = temp.newFolder("PlayStation 2")
        assertEquals(direct.canonicalFile, RommDestinations.candidates(remote, listOf(ps2), emptyList(), listOf(direct)).single().folder)
        val custom = temp.newFolder("My disc images")
        val configured = ps2.copy(folders = listOf(custom.path))
        assertEquals(custom.canonicalFile, RommDestinations.candidates(remote, listOf(configured), emptyList(), emptyList()).single().folder)
    }

    @Test fun unmappedOrMissingFoldersDoNotInventAPathFromTheRemoteName() {
        assertTrue(RommDestinations.candidates(remote, listOf(ps2), emptyList(), listOf(temp.root)).isEmpty())
        assertTrue(RommDestinations.candidates(remote.copy(name = "Unknown", slug = "unknown", fsSlug = "unknown"), listOf(ps2), emptyList(), listOf(temp.root)).isEmpty())
        assertTrue(temp.root.list()!!.isEmpty())
    }

    @Test fun emptyPlatformFolderHasAPreviewBeforeDownloadingAnything() {
        val root = temp.newFolder("Library")
        val folder = File(root, "ps2").apply { mkdir() }
        val preview = RommDestinations.plan(remote, game.fileName, listOf(ps2), emptyList(), listOf(root), null)
        assertEquals(folder.canonicalFile, preview.selected?.folder)
        assertEquals(listOf(ps2), preview.platforms)
        assertTrue(folder.list()!!.isEmpty())
    }

    @Test fun missingFolderOffersTheKnownSystemWithoutCreatingFiles() {
        val preview = RommDestinations.plan(remote, game.fileName, listOf(ps2), emptyList(), listOf(temp.root), null)
        assertTrue(preview.choices.isEmpty())
        assertNull(preview.selected)
        assertEquals(listOf(ps2), preview.platforms)
        assertTrue(temp.root.list()!!.isEmpty())
    }

    @Test fun unavailableRememberedCardRequiresChoiceEvenWhenInternalFolderExists() {
        val root = temp.newFolder("Internal")
        File(root, "ps2").mkdir()
        val missing = File(temp.root, "Removed card/ps2").canonicalPath
        val preview = RommDestinations.plan(remote, game.fileName, listOf(ps2), emptyList(), listOf(root), "ps2|$missing")
        assertEquals(1, preview.choices.size)
        assertNull(preview.selected)
        assertEquals(missing, preview.unavailablePath)
    }

    @Test fun explicitFolderMappingSurvivesRestartAndUnknownRemoteNaming() {
        val unknown = remote.copy(name = "Remote custom system", slug = "custom", fsSlug = "custom")
        val folder = temp.newFolder("My disc images")
        val picked = RommDestinations.picked(ps2, folder, file.name)
        val key = "${RommDownloadNames.serverKey("https://fixture.example.test")}:${unknown.id}"
        val ledger = temp.newFolder("State")
        RommDownloadStore(ledger).remember(key, "${ps2.id}|${picked.folder.path}")
        val saved = RommDownloadStore(ledger).choice(key)
        val registered = ps2.copy(folders = ps2.folders + picked.folder.path)
        val preview = RommDestinations.plan(unknown, game.fileName, listOf(registered), emptyList(), emptyList(), saved)
        assertEquals(picked.folder, preview.selected?.folder)
        assertEquals(ps2.id, preview.selected?.platform?.id)
        // Removing the explicit registration invalidates the saved path; it is not a grant to write anywhere.
        assertNull(RommDestinations.plan(unknown, game.fileName, listOf(ps2), emptyList(), emptyList(), saved).selected)
    }

    @Test fun unknownPlatformRequiresExplicitSystemChoiceAndFiltersIncompatibleTypes() {
        val gba = ps2.copy(id = "gba", name = "Game Boy Advance", folders = listOf("gba"), extensions = setOf("gba"))
        val gc = ps2.copy(id = "gc", name = "GameCube", folders = listOf("gc"))
        val unknown = remote.copy(name = "Unknown", slug = "unknown", fsSlug = "unknown")
        val preview = RommDestinations.plan(unknown, game.fileName, listOf(ps2, gba, gc), emptyList(), emptyList(), null)
        assertEquals(listOf("ps2", "gc"), preview.platforms.map { it.id })
        assertNull(preview.selected)
        assertTrue(preview.choices.isEmpty())
    }

    @Test fun manualChoiceMustBeAnExistingFolderForACompatibleFileSystem() {
        assertThrows(RommDownloadFailure::class.java) { RommDestinations.picked(ps2, File(temp.root, "missing"), file.name) }
        assertThrows(RommDownloadFailure::class.java) { RommDestinations.picked(ps2, temp.newFile("Not a folder"), file.name) }
        assertThrows(RommDownloadFailure::class.java) { RommDestinations.picked(ps2, temp.root, "Fixture.gba") }
        assertThrows(RommDownloadFailure::class.java) { RommDestinations.picked(ps2, File(temp.root, "../other"), file.name) }
        assertThrows(RommDownloadFailure::class.java) { RommDestinations.picked(ps2, File("relative"), file.name) }
    }

    @Test fun previewDoesNotRememberAChoiceOrCreateAStateLedger() {
        val ledger = File(temp.root, "State")
        val store = RommDownloadStore(ledger)
        val preview = RommDestinations.plan(remote, file.name, listOf(ps2), emptyList(), emptyList(), store.choice("not-saved"))
        assertNull(preview.selected)
        assertFalse(ledger.exists())
    }

    @Test fun disabledSystemCanBeOfferedForExplicitRegistrationWithoutAutomaticSelection() {
        val folder = temp.newFolder("ps2")
        val disabled = ps2.copy(folders = listOf(folder.path))
        val preview = RommDestinations.plan(remote, file.name, emptyList(), emptyList(), emptyList(), null, listOf(disabled))
        assertEquals(listOf(disabled), preview.platforms)
        assertTrue(preview.choices.isEmpty())
        assertNull(preview.selected)
    }

    @Test fun manifestRejectsChangedIdentityBadHashesOrUnverifiedFolderPaths() {
        val manifest = RommDownloadManifest(9, 11, game.fileName, 64, listOf(file))
        assertEquals(file, manifest.layout(game).parts.single().file)
        for (invalid in listOf(manifest.copy(files = listOf(file, file.copy(id = 32))), manifest.copy(id = 12), manifest.copy(size = 65), manifest.copy(files = listOf(file.copy(topLevel = false))), manifest.copy(files = listOf(file.copy(sha1 = "bad"))))) {
            assertThrows(RommDownloadFailure::class.java) { invalid.layout(game) }
        }
    }

    @Test fun filenameTraversalControlsAndNonPortableNamesAreRejected() {
        for (name in listOf("../other.iso", "/tmp/other.iso", "x\\other.iso", ".hidden.iso", "x\n.iso", "x:other.iso", "x.iso ", "..")) {
            assertThrows(RommDownloadFailure::class.java) { RommDownloadNames.url("https://fixture.example.test", 9, file.copy(name = name)) }
        }
        assertEquals("https://fixture.example.test/proxy/api/roms/9/content/A%20%26%20B%23.iso?file_ids=31",
            RommDownloadNames.url("https://fixture.example.test/proxy", 9, file.copy(name = "A & B#.iso")))
    }

    @Test fun choicesAreScopedByServerWithoutPersistingTheAddress() {
        val one = RommDownloadNames.serverKey("https://fixture.example.test/proxy/")
        assertEquals(one, RommDownloadNames.serverKey("https://fixture.example.test/proxy"))
        assertNotEquals(one, RommDownloadNames.serverKey("https://other.example.test/proxy"))
        assertEquals(64, one.length); assertFalse(one.contains("fixture"))
    }

    @Test fun fileMetadataUsesVerifiedSchemaNamesAndAllowsAbsentChecksums() {
        val manifest = Json { ignoreUnknownKeys = true }.decodeFromString<RommDownloadManifest>("""{"id":9,"platform_id":11,"fs_name":"Fixture.iso","fs_size_bytes":64,"files":[{"id":31,"rom_id":9,"file_name":"Fixture.iso","file_size_bytes":64,"is_top_level":true,"sha1_hash":null,"future":true}]}""")
        assertEquals(file, manifest.layout(game).parts.single().file)
    }
}
