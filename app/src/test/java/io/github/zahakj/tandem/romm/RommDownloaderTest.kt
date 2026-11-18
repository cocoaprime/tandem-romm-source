package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.PrivateOffline
import io.github.zahakj.tandem.GameFinder
import io.github.zahakj.tandem.Platform
import io.github.zahakj.tandem.data.ScanCache
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RommDownloaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val config = RommConfig("https://fixture.example.test/romm", "synthetic-token", true)
    private val payload = ByteArray(160_000) { (it % 251).toByte() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun file() = RommDownloadFile(31, 9, "Fixture.iso", payload.size.toLong(), true, sha1 = hash(payload))
    private class Log : RommPartialLog {
        var value: RommPartial? = null
        override fun save(partial: RommPartial) { value = partial }
        override fun clear() { value = null }
    }
    private fun problem(expected: RommDownloadProblem, block: () -> Unit) =
        assertEquals(expected, assertThrows(RommDownloadFailure::class.java, block).problem)

    @Test fun streamsValidatesAndPublishesIntoTheExistingPlatformFolder() {
        val folder = temp.newFolder("PS2")
        val sentinel = File(folder, "Existing.iso").apply { writeText("keep") }
        val log = Log()
        var closed = false
        var largest = 0
        val input = object : ByteArrayInputStream(payload) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { largest = maxOf(largest, len); return super.read(b, off, len) }
        }
        val transport = RommStreamTransport { url, token, _ ->
            assertEquals("${config.server}/api/roms/9/content/Fixture.iso?file_ids=31", url)
            assertEquals(config.token, token)
            assertFalse(File(folder, "Fixture.iso").exists())
            RommStream(input, payload.size.toLong()) { closed = true }
        }
        val progress = mutableListOf<Long>()
        val phases = mutableListOf<RommTransferPhase>()
        val result = RommDownloader(transport, log).download(config, 9, file(), folder, RommTransferControl {}, phase = { phases += it }) { bytes, total ->
            assertEquals(payload.size.toLong(), total); progress += bytes
            assertFalse(File(folder, "Fixture.iso").exists())
        }
        assertTrue(result.checksumVerified); assertTrue(closed)
        assertArrayEquals(payload, result.file.readBytes())
        assertTrue(largest <= 64 * 1024)
        assertEquals(payload.size.toLong(), progress.last())
        assertEquals(listOf(RommTransferPhase.Downloading, RommTransferPhase.Verifying, RommTransferPhase.Publishing), phases)
        assertNull(log.value)
        assertEquals("keep", sentinel.readText())
        assertEquals(setOf("Existing.iso", "Fixture.iso"), folder.list()!!.toSet())
    }

    @Test fun firstDownloadIntoAnExplicitEmptyFolderIsDiscoveredAsTheChosenSystem() {
        val folder = temp.newFolder("My disc images")
        val platform = Platform("ps2", "PlayStation 2", "PS2", listOf(folder.canonicalPath), setOf("iso"), emptySet(), emptyList(), emptyList())
        val remote = RommPlatformInfo(11, "Custom remote name", "custom", "custom")
        val destination = RommDestinations.picked(platform, folder, file().name)
        val saved = "${platform.id}|${destination.folder.path}"
        val plan = RommDestinations.plan(remote, file().name, listOf(platform), emptyList(), emptyList(), saved)
        assertEquals(destination, plan.selected)
        val scanner = GameFinder(listOf(platform)) { dir ->
            val entries = dir.listFiles().orEmpty()
            ScanCache.Listing(0, entries.filter { it.isFile }.map { it.name }, entries.filter { it.isDirectory }.map { it.name })
        }
        RommDownloader(RommStreamTransport { _, _, _ -> RommStream(ByteArrayInputStream(payload), null) {} }, Log())
            .download(config, 9, file(), plan.selected!!.folder, RommTransferControl {}, validateDestination = {
                assertEquals(destination.folder, RommDestinations.candidates(remote, listOf(platform), emptyList(), emptyList(), platform.id).single().folder)
            }) { _, _ -> assertTrue(scanner.walk(folder, platform, null, 0, null).isEmpty()) }
        val found = scanner.walk(folder, platform, null, 0, null).single()
        assertEquals(platform.id, found.platform.id)
        assertEquals("Fixture.iso", found.file.name)
        assertArrayEquals(payload, found.file.readBytes())
    }

    @Test fun unknownHttpLengthStillChecksTheTrustedMetadataSize() {
        val result = RommDownloader(RommStreamTransport { _, _, _ -> RommStream(ByteArrayInputStream(payload), null) {} }, Log())
            .download(config, 9, file().copy(sha1 = null), temp.root, RommTransferControl {})
        assertFalse(result.checksumVerified); assertEquals(payload.size.toLong(), result.file.length())
    }

    @Test fun truncationOversizeAndChecksumMismatchNeverPublish() {
        for (body in listOf(payload.copyOf(10), payload + byteArrayOf(1), payload.copyOf().also { it[5] = 99 })) {
            val folder = temp.newFolder()
            val log = Log()
            problem(RommDownloadProblem.Integrity) {
                RommDownloader(RommStreamTransport { _, _, _ -> RommStream(ByteArrayInputStream(body), null) {} }, log)
                    .download(config, 9, file(), folder, RommTransferControl {})
            }
            assertFalse(File(folder, "Fixture.iso").exists())
            assertTrue(folder.list()!!.isEmpty()); assertNull(log.value)
        }
    }

    @Test fun wrongContentLengthFailsBeforeReadingTheBody() {
        var read = false
        val body = object : InputStream() { override fun read(): Int { read = true; return -1 } }
        problem(RommDownloadProblem.Integrity) {
            RommDownloader(RommStreamTransport { _, _, _ -> RommStream(body, 12) {} }, Log())
                .download(config, 9, file(), temp.root, RommTransferControl {})
        }
        assertFalse(read)
    }

    @Test fun cancelledDuringTransferCleansOnlyItsOwnedPartial() {
        val control = RommTransferControl {}
        val sentinel = File(temp.root, "Other.part").apply { writeText("keep") }
        problem(RommDownloadProblem.Cancelled) {
            RommDownloader(RommStreamTransport { _, _, _ -> RommStream(ByteArrayInputStream(payload), null) {} }, Log())
                .download(config, 9, file(), temp.root, control) { _, _ -> control.cancel() }
        }
        assertEquals(setOf("Other.part"), temp.root.list()!!.toSet()); assertEquals("keep", sentinel.readText())
    }

    @Test fun twoTransfersOverlapAndCancellingOneLeavesTheOtherIntact() {
        val games = temp.newFolder("games")
        val state = temp.newFolder("state")
        val store = RommDownloadStore(state)
        val opened = CountDownLatch(2)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val firstControl = RommTransferControl {}
        val transport = RommStreamTransport { _, _, _ ->
            opened.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            RommStream(ByteArrayInputStream(payload), payload.size.toLong()) {}
        }
        try {
            val first = pool.submit(Callable {
                runCatching { RommDownloader(transport, store.newLog()).download(config, 9, file(), games, firstControl) }
            })
            val second = pool.submit(Callable {
                val item = file().copy(id = 32, romId = 10, name = "Other.iso")
                RommDownloader(transport, store.newLog()).download(config, 10, item, games, RommTransferControl {})
            })
            assertTrue("Both transfers must reach the network before either completes", opened.await(10, TimeUnit.SECONDS))
            assertEquals(2, state.listFiles()!!.size)
            assertEquals(2, games.listFiles()!!.count { it.name.endsWith(".part") })
            assertFalse(File(games, "Other.iso").exists())
            firstControl.cancel()
            release.countDown()
            val cancelled = first.get(10, TimeUnit.SECONDS).exceptionOrNull() as RommDownloadFailure
            assertEquals(RommDownloadProblem.Cancelled, cancelled.problem)
            assertArrayEquals(payload, second.get(10, TimeUnit.SECONDS).file.readBytes())
            assertEquals(setOf("Other.iso"), games.list()!!.toSet())
            assertTrue(state.listFiles()!!.isEmpty())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun cancellationOrDestinationRevocationBeforeCommitPreventsPublication() {
        for (cancel in listOf(true, false)) {
            val folder = temp.newFolder()
            val control = RommTransferControl {}
            var validations = 0
            assertThrows(RommDownloadFailure::class.java) {
                RommDownloader(RommStreamTransport { _, _, _ -> RommStream(ByteArrayInputStream(payload), null) {} }, Log())
                    .download(config, 9, file(), folder, control, validateDestination = {
                        if (++validations == 2) { if (cancel) control.cancel() else throw RommDownloadFailure(RommDownloadProblem.Folder) }
                    })
            }
            assertFalse(File(folder, "Fixture.iso").exists())
        }
    }

    @Test fun existingNameAndLateCollisionAreNeverOverwritten() {
        val existing = File(temp.root, "Fixture.iso").apply { writeText("original") }
        var opened = false
        problem(RommDownloadProblem.Exists) {
            RommDownloader(RommStreamTransport { _, _, _ -> opened = true; error("not called") }, Log())
                .download(config, 9, file(), temp.root, RommTransferControl {})
        }
        assertFalse(opened); assertEquals("original", existing.readText())
        val another = temp.newFolder()
        problem(RommDownloadProblem.Exists) {
            RommDownloader(RommStreamTransport { _, _, _ -> RommStream(ByteArrayInputStream(payload), null) {} }, Log(), publish = { from, to ->
                to.writeText("arrived later"); Files.move(from.toPath(), to.toPath())
            }).download(config, 9, file(), another, RommTransferControl {})
        }
        assertEquals("arrived later", File(another, "Fixture.iso").readText())
    }

    @Test fun fullStorageAndPrivateModeDoNotContactTheServer() {
        var opened = false
        val transport = RommStreamTransport { _, _, _ -> opened = true; error("not called") }
        problem(RommDownloadProblem.Space) { RommDownloader(transport, Log(), usableSpace = { 1 }).download(config, 9, file(), temp.root, RommTransferControl {}) }
        assertThrows(PrivateOffline::class.java) { RommDownloader(transport, Log()).download(config, 9, file(), temp.root, RommTransferControl { throw PrivateOffline() }) }
        assertFalse(opened)
    }

    @Test fun readAndPublicationFailuresCloseResourcesAndLeaveNoGame() {
        var closed = false
        val body = object : InputStream() { override fun read(): Int = throw IOException("synthetic") }
        assertThrows(IOException::class.java) {
            RommDownloader(RommStreamTransport { _, _, _ -> RommStream(body, null) { closed = true } }, Log())
                .download(config, 9, file(), temp.root, RommTransferControl {})
        }
        assertTrue(closed); assertTrue(temp.root.list()!!.isEmpty())
        assertThrows(IOException::class.java) {
            RommDownloader(RommStreamTransport { _, _, _ -> RommStream(ByteArrayInputStream(payload), null) {} }, Log(), publish = { _, _ -> throw IOException("synthetic storage failure") })
                .download(config, 9, file(), temp.root, RommTransferControl {})
        }
        assertTrue(temp.root.list()!!.isEmpty())
    }

    @Test fun ledgerFailureCannotStartARequestOrDeleteAnExistingGame() {
        var opened = false
        val existing = File(temp.root, "Other.iso").apply { writeText("keep") }
        val log = object : RommPartialLog { override fun save(partial: RommPartial) = throw IOException("synthetic"); override fun clear() = Unit }
        assertThrows(IOException::class.java) {
            RommDownloader(RommStreamTransport { _, _, _ -> opened = true; error("not called") }, log)
                .download(config, 9, file(), temp.root, RommTransferControl {})
        }
        assertFalse(opened); assertEquals("keep", existing.readText())
    }

    @Test fun aClearFailureAfterPublicationDoesNotDiscardTheCompletedGame() {
        val log = object : RommPartialLog { override fun save(partial: RommPartial) = Unit; override fun clear() = throw IOException("synthetic") }
        val downloaded = RommDownloader(RommStreamTransport { _, _, _ -> RommStream(ByteArrayInputStream(payload), null) {} }, log)
            .download(config, 9, file(), temp.root, RommTransferControl {})
        assertArrayEquals(payload, downloaded.file.readBytes())
    }

    @Test fun progressCountsBeyondTwoGiBAndNearLongLimitDoNotOverflow() {
        assertEquals(2_147_549_184L, rommWrittenBytes(2_147_483_648L, 65_536, 3_000_000_000L))
        assertEquals(Long.MAX_VALUE, rommWrittenBytes(Long.MAX_VALUE - 1, 1, Long.MAX_VALUE))
        problem(RommDownloadProblem.Integrity) { rommWrittenBytes(Long.MAX_VALUE - 1, 2, Long.MAX_VALUE) }
        problem(RommDownloadProblem.Integrity) { rommWrittenBytes(-1, 1, 1) }
    }

    @Test fun scannerIgnoresStagingAndDiscoversOnlyTheCompletedPlatformGame() {
        val folder = File(temp.root, "ps2").apply { mkdir() }
        val platform = Platform("ps2", "PlayStation 2", "PS2", listOf("ps2"), setOf("iso"), emptySet(), emptyList(), emptyList())
        val scanner = GameFinder(listOf(platform)) { dir ->
            val entries = dir.listFiles().orEmpty()
            ScanCache.Listing(0, entries.filter { it.isFile }.map { it.name }, entries.filter { it.isDirectory }.map { it.name })
        }
        RommDownloader(RommStreamTransport { _, _, _ -> RommStream(ByteArrayInputStream(payload), null) {} }, Log())
            .download(config, 9, file(), folder, RommTransferControl {}) { _, _ ->
                assertTrue(scanner.find(temp.root, null, true).games.values.flatten().isEmpty())
            }
        val game = scanner.find(temp.root, null, true).games["ps2"]!!.single()
        assertEquals("Fixture.iso", game.file.name)
        assertEquals("ps2/Fixture.iso", game.key)
    }

    @Test fun publicationInvalidatesCachedStagingEvenWhenDirectoryTimestampDoesNotChange() {
        val folder = temp.newFolder("ps2")
        val partial = File(folder, ".tandem-romm-fixture.part").apply { writeText("temporary") }
        val old = ScanCache.list(folder, false)
        assertEquals(listOf(partial.name), old.files)
        Files.move(partial.toPath(), File(folder, "Fixture.iso").toPath())
        assertTrue(folder.setLastModified(old.modified))
        assertEquals(old.files, ScanCache.list(folder, false).files)
        ScanCache.invalidate(folder)
        assertEquals(listOf("Fixture.iso"), ScanCache.list(folder, false).files)
    }
}
