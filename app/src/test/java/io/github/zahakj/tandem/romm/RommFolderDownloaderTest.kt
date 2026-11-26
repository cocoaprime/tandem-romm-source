package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.GameFinder
import io.github.zahakj.tandem.Discs
import io.github.zahakj.tandem.LaunchSpec
import io.github.zahakj.tandem.Platform
import io.github.zahakj.tandem.SpecPatch
import io.github.zahakj.tandem.data.ScanCache
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale
import java.util.UUID

class RommFolderDownloaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val config = RommConfig("https://fixture.example.test", "synthetic-token", true)
    private val origin = RommInstallOrigin(RommDownloadNames.serverKey(config.server), 11, 9)
    private val remote = RommPlatformInfo(11, "PlayStation", "psx")
    private val psx = Platform("psx", "PlayStation", "PS1", listOf("psx"), setOf("cue", "chd", "m3u"), emptySet(), emptyList(), emptyList())
    private val ps2 = psx.copy(id = "ps2", title = "PlayStation 2", extensions = setOf("iso", "chd", "m3u"))
    private val name = "Folder game"
    private val root = "roms/psx/$name"
    private val files = linkedMapOf(
        "Game.cue" to "FILE \"Tracks/Track 01.bin\" BINARY\n  TRACK 01 MODE2/2352\n".toByteArray(),
        "Tracks/Track 01.bin" to ByteArray(400) { it.toByte() },
        "Notes.txt" to byteArrayOf(),
    )

    private fun layout(parts: Map<String, ByteArray>): RommDownloadLayout {
        val size = parts.values.sumOf { it.size.toLong() }
        val metadata = parts.entries.mapIndexed { index, (path, bytes) ->
            RommDownloadFile(index + 31L, 9, path.substringAfterLast('/'), bytes.size.toLong(), '/' !in path,
                fullPath = "$root/$path")
        }
        return RommDownloadManifest(9, 11, name, size, metadata, root)
            .layout(RommGameInfo(9, 11, name, size, simpleFile = false))
    }

    private fun finder(platform: Platform) = GameFinder(listOf(platform)) { dir ->
        val entries = dir.listFiles().orEmpty()
        ScanCache.Listing(0, entries.filter { it.isFile }.map { it.name }, entries.filter { it.isDirectory }.map { it.name })
    }

    private fun downloader(parts: Map<String, ByteArray>, state: File, beforeOpen: (Int) -> Unit = {}): RommFolderDownloader {
        val originals = parts.values.toList()
        return RommFolderDownloader(RommStreamTransport { url, _, _ ->
            val id = url.substringAfter("file_ids=").toInt()
            beforeOpen(id)
            val bytes = originals[id - 31]
            RommStream(bytes.inputStream(), bytes.size.toLong()) {}
        }, RommDownloadStore(state))
    }

    @Test fun ps1CueAndNestedTracksArePublishedTogetherAndScannedAsOneGame() {
        val games = temp.newFolder("psx").canonicalFile
        val state = temp.newFolder("state")
        val layout = layout(files)
        val scanner = finder(psx)
        val progress = mutableListOf<Long>()
        val result = downloader(files, state) {
            assertTrue(scanner.walk(games, psx, null, 0, null).isEmpty())
        }.download(config, origin, layout, layout.launchFor(psx)!!, RommDestination(psx, games), RommTransferControl {}, {}) { done, total ->
            assertEquals(layout.size, total)
            progress += done
            assertFalse(File(games, name).exists())
        }
        assertEquals(layout.size, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        val game = scanner.walk(games, psx, null, 0, null).single()
        assertEquals("Game.cue", game.file.name)
        assertEquals("psx/$name", game.key)
        assertEquals(name, game.stem)
        assertEquals(result.file, game.file)
        assertNull(game.group)
        for ((path, bytes) in files) assertArrayEquals(bytes, File(games, "$name/$path").readBytes())
        val index = RommLocalIndex(listOf(RommLocalFile.read(game)!!), origin.serverKey)
        assertEquals(RommAvailability.Downloaded, index.match(remote, RommGameInfo(9, 11, name, layout.size, simpleFile = false)))
        assertTrue(state.listFiles()!!.isEmpty())
        File(games, "$name/Tracks/Track 01.bin").delete()
        assertNull(RommLocalFile.read(game))
        assertTrue(scanner.walk(games, psx, null, 0, null).isEmpty())
    }

    @Test fun folderGamesWithTheSameLaunchFilenameHaveDistinctLibraryAndArtworkKeys() {
        val games = temp.newFolder("psx").canonicalFile
        val state = temp.newFolder()
        val original = layout(files)
        for (folderName in listOf(name, "Second game")) {
            val layout = original.copy(name = folderName)
            downloader(files, state).download(config, origin, layout, layout.launchFor(psx)!!, RommDestination(psx, games),
                RommTransferControl {}, {})
        }
        val found = finder(psx).walk(games, psx, null, 0, null)
        assertEquals(listOf("Game.cue", "Game.cue"), found.map { it.file.name })
        assertEquals(setOf("psx/$name", "psx/Second game"), found.map { it.key }.toSet())
        assertEquals(setOf(name, "Second game"), found.map { it.stem }.toSet())
    }

    @Test fun ps2FolderDiscsGetOneOrderedPlaylistAndKeepTheirNames() {
        val files = linkedMapOf("Disc 10.iso" to byteArrayOf(10), "Disc 2.chd" to byteArrayOf(2))
        val games = temp.newFolder("ps2").canonicalFile
        val layout = layout(files)
        val launch = layout.launchFor(ps2)!!
        val result = downloader(files, temp.newFolder()).download(config, origin, layout, launch, RommDestination(ps2, games),
            RommTransferControl {}, {})
        assertEquals("$name.m3u", result.file.name)
        assertEquals(listOf("Disc 2.chd", "Disc 10.iso"), result.file.readLines())
        assertEquals(listOf(result.file), finder(ps2).walk(games, ps2, null, 0, null).map { it.file })
    }

    @Test fun existingPlaylistIsPreservedWithItsOwnDiscOrder() {
        val playlist = "Disc 2.chd\nGame.cue\n".toByteArray()
        val contents = files + mapOf("Set.m3u" to playlist, "Disc 2.chd" to byteArrayOf(2))
        val games = temp.newFolder("psx").canonicalFile
        val layout = layout(contents)
        val launch = layout.launchFor(psx)!!
        val result = downloader(contents, temp.newFolder()).download(config, origin, layout, launch, RommDestination(psx, games),
            RommTransferControl {}, {})
        assertEquals("Set.m3u", result.file.name)
        assertArrayEquals(playlist, result.file.readBytes())
        assertEquals(listOf(result.file), finder(psx).walk(games, psx, null, 0, null).map { it.file })
    }

    @Test fun downloadedPlaylistUsesFirstDiscPlayersWithoutStealingOtherGamesMetadata() {
        val contents = linkedMapOf("Disc 2.chd" to byteArrayOf(2), "Disc 10.chd" to byteArrayOf(10))
        val games = temp.newFolder("multi").canonicalFile
        val layout = layout(contents)
        downloader(contents, temp.newFolder()).download(config, origin, layout, layout.launchFor(psx)!!,
            RommDestination(psx, games), RommTransferControl {}, {})
        val game = finder(psx).walk(games, psx, null, 0, null).single()
        assertEquals("psx/$name", game.key)
        assertEquals(listOf("Disc 2.chd", "Disc 10.chd"), game.discs.map { it.name })
        val spec = LaunchSpec("Fixture", "fixture/.Main", "android.intent.action.VIEW", "data", emptyMap(), firstDisc = true)
        assertEquals(game.discs.first(), Discs.launchFile(game, spec))
        assertNull(Discs.launchFile(game, spec.copy(firstDisc = false)))
        assertTrue(Discs.formerKeys(game).isEmpty())
        assertTrue(game.rommBundle!!.containsOnlyBundleFiles())
    }

    @Test fun ordinaryDiscsAndPublishedRommBundleShareASystemWithoutDuplicateTiles() {
        val contents = linkedMapOf("Disc 1.chd" to byteArrayOf(1), "Disc 2.chd" to byteArrayOf(2))
        val games = temp.newFolder("mixed").canonicalFile
        val layout = layout(contents)
        downloader(contents, temp.newFolder()).download(config, origin, layout, layout.launchFor(psx)!!,
            RommDestination(psx, games), RommTransferControl {}, {})
        File(games, "Ordinary (Disc 1).chd").writeBytes(byteArrayOf(3))
        File(games, "Ordinary (Disc 2).chd").writeBytes(byteArrayOf(4))
        val found = finder(psx).walk(games, psx, null, 0, null)
        assertEquals(setOf("psx/Ordinary (Disc 1).chd", "psx/$name"), found.map { it.key }.toSet())
        assertEquals(2, found.single { it.rommBundle == null }.discs.size)
        assertEquals(2, found.single { it.rommBundle != null }.discs.size)
        assertTrue(found.all { it.group == null })
    }

    @Test fun generatedPlaylistsWorkForVerifiedDiscAndFloppyFormatsAcrossPlatforms() {
        val cases = mapOf("dreamcast" to "cdi", "amiga" to "adf", "c64" to "d64", "atarist" to "st",
            "amstradcpc" to "dsk", "pc88" to "d88")
        for ((id, ext) in cases) {
            val platform = psx.copy(id = id, extensions = setOf(ext, "m3u"))
            val first = "Disk 2.$ext"
            val second = "Disks/Disk 10.$ext"
            val parts = linkedMapOf(second to byteArrayOf(10), first to byteArrayOf(2))
            val games = temp.newFolder(id).canonicalFile
            val layout = layout(parts)
            val launch = layout.launchFor(platform)!!
            assertNull(layout.launchFor(platform.copy(extensions = setOf(ext))))
            val result = downloader(parts, temp.newFolder()).download(config, origin, layout, launch,
                RommDestination(platform, games), RommTransferControl {}, {})
            assertEquals("$name.m3u", result.file.name)
            assertEquals(listOf(first, second), result.file.readLines())
            assertEquals(listOf(result.file), finder(platform).walk(games, platform, null, 0, null).map { it.file })
        }
    }

    @Test fun existingCommodorePlaylistPreservesDocumentedDiskLabels() {
        val platform = psx.copy(id = "c64", extensions = setOf("d64", "m3u"))
        val playlist = "Side B.d64|Side B: Continue\nSide A.d64|Side A: Start\n".toByteArray()
        val parts = linkedMapOf("Set.m3u" to playlist, "Side A.d64" to byteArrayOf(1), "Side B.d64" to byteArrayOf(2))
        val games = temp.newFolder().canonicalFile
        val layout = layout(parts)
        val result = downloader(parts, temp.newFolder()).download(config, origin, layout, layout.launchFor(platform)!!,
            RommDestination(platform, games), RommTransferControl {}, {})
        assertArrayEquals(playlist, result.file.readBytes())
    }

    @Test fun dreamcastGdiWiiURpxAndPs3DirectoriesUseTheSameBundlePipeline() {
        val dreamcast = psx.copy(id = "dreamcast", extensions = setOf("gdi", "chd", "m3u"))
        val wiiu = psx.copy(id = "wiiu", extensions = setOf("rpx"))
        val player = LaunchSpec("Fixture emulator", "fixture.example/.Main", null, "data", emptyMap(),
            byExtension = mapOf("dir" to SpecPatch(null, "none", null, emptyMap())))
        val ps3 = psx.copy(id = "ps3", extensions = setOf("ps3", "iso"), allPlayers = listOf(player))
        val cases = listOf(
            Triple(dreamcast, "Game.gdi", mapOf(
                "Game.gdi" to "1\n1 0 4 2352 \"Tracks/Track 01.bin\" 0\n".toByteArray(),
                "Tracks/Track 01.bin" to byteArrayOf(1),
            )),
            Triple(wiiu, "code/Game.rpx", mapOf("code/Game.rpx" to byteArrayOf(1), "content/asset.bin" to byteArrayOf(2))),
            Triple(ps3, null, mapOf("PS3_GAME/USRDIR/EBOOT.BIN" to byteArrayOf(1), "PS3_GAME/PARAM.SFO" to byteArrayOf(2))),
        )
        for ((platform, entry, parts) in cases) {
            val games = temp.newFolder(platform.id).canonicalFile
            val layout = layout(parts)
            val launch = layout.launchFor(platform)!!
            assertEquals(entry, launch.path)
            val result = downloader(parts, temp.newFolder()).download(config, origin, layout, launch,
                RommDestination(platform, games), RommTransferControl {}, {})
            val folder = File(games, name)
            assertEquals(entry?.let { File(folder, it) } ?: folder, result.file)
            assertEquals(listOf(result.file), finder(platform).walk(games, platform, null, 0, null).map { it.file })
        }
    }

    @Test fun anExistingDestinationFolderIsNeverMergedOrReplaced() {
        val games = temp.newFolder("psx").canonicalFile
        val sentinel = File(File(games, name).apply { mkdir() }, "Original.cue").apply { writeText("keep") }
        val layout = layout(files)
        val failure = assertThrows(RommDownloadFailure::class.java) {
            downloader(files, temp.newFolder()) { fail("No network request is needed when the folder already exists") }
                .download(config, origin, layout, layout.launchFor(psx)!!, RommDestination(psx, games), RommTransferControl {}, {})
        }
        assertEquals(RommDownloadProblem.Exists, failure.problem)
        assertEquals("keep", sentinel.readText())
        assertEquals(listOf("Original.cue"), sentinel.parentFile.list()!!.toList())
    }

    @Test fun missingOrExternalTracksNeverPublishTheFolder() {
        for (path in listOf("absent.bin", "../outside.bin", "/absolute/track.bin")) {
            val staged = files + ("Game.cue" to "FILE \"$path\" BINARY\n".toByteArray())
            val games = temp.newFolder().canonicalFile
            val state = temp.newFolder()
            val layout = layout(staged)
            val failure = assertThrows(RommDownloadFailure::class.java) {
                downloader(staged, state).download(config, origin, layout, layout.launchFor(psx)!!, RommDestination(psx, games),
                    RommTransferControl {}, {})
            }
            assertEquals(RommDownloadProblem.MissingParts, failure.problem)
            assertTrue(games.listFiles()!!.isEmpty())
            assertTrue(state.listFiles()!!.isEmpty())
        }
    }

    @Test fun cancellationCleansTheStagedFolderAndLeavesUnrelatedFiles() {
        val games = temp.newFolder().canonicalFile
        val state = temp.newFolder()
        File(games, "Other.cue").writeText("unrelated")
        val layout = layout(files)
        val control = RommTransferControl {}
        val failure = assertThrows(RommDownloadFailure::class.java) {
            downloader(files, state) { if (it == 32) control.cancel() }.download(config, origin, layout, layout.launchFor(psx)!!,
                RommDestination(psx, games), control, {})
        }
        assertEquals(RommDownloadProblem.Cancelled, failure.problem)
        assertEquals(listOf("Other.cue"), games.list()!!.toList())
        assertTrue(state.listFiles()!!.isEmpty())
    }

    @Test fun restartRecoversOnlyRegisteredFoldersIncludingActivePartials() {
        val games = temp.newFolder().canonicalFile
        val state = temp.newFolder()
        val store = RommDownloadStore(state)
        fun stage() = RommFolderPartial(games.path, UUID.randomUUID().toString(), listOf("Game.cue")).also {
            assertTrue(it.staging.mkdir())
            File(it.staging, RommFolderPartial.OWNER).writeText(it.id)
            File(it.staging, "Game.cue").writeText("kept or recovered")
            store.saveFolder(it)
        }
        val clean = stage()
        val child = File(clean.staging, ".tandem-romm-${UUID.randomUUID()}.part")
        val marker = UUID.randomUUID().toString()
        child.writeText("bytes$marker")
        store.newLog().save(RommPartial(clean.staging.canonicalPath, child.name, null, marker))
        val uncertain = stage()
        val foreign = File(uncertain.staging, "foreign").apply { writeText("keep") }
        assertFalse(RommDownloadStore(state).recover())
        assertFalse(clean.staging.exists())
        assertEquals("keep", foreign.readText())
    }

    @Test fun manifestRejectsTraversalsCaseCollisionsAndFilesOutsideTheFolder() {
        for (bad in listOf("Tracks/../Track.bin", "Track.bin/", "../Elsewhere.bin", "Tracks//Track.bin")) {
            assertThrows(RommDownloadFailure::class.java) { layout(mapOf(bad to byteArrayOf(1))) }
        }
        assertThrows(RommDownloadFailure::class.java) {
            layout(mapOf("Tracks/A.bin" to byteArrayOf(1), "tracks/B.bin" to byteArrayOf(2)))
        }
        val outside = RommDownloadFile(31, 9, "Game.cue", 1, false, fullPath = "roms/psx/Other/Game.cue")
        assertThrows(RommDownloadFailure::class.java) {
            RommDownloadManifest(9, 11, name, 1, listOf(outside), root).layout(RommGameInfo(9, 11, name, 1, simpleFile = false))
        }
    }

    @Test fun sizeUsesMbBelowOneGbAndGbAtTheThreshold() {
        assertEquals("0 MB", rommSizeLabel(0, Locale.US))
        assertEquals("<0.01 MB", rommSizeLabel(64, Locale.US))
        assertEquals("650 MB", rommSizeLabel(650_000_000, Locale.US))
        assertEquals("1 GB", rommSizeLabel(1_000_000_000, Locale.US))
        assertEquals("4.21 GB", rommSizeLabel(4_210_000_000, Locale.US))
    }
}
