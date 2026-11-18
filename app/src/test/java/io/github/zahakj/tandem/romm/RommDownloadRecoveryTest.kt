package io.github.zahakj.tandem.romm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class RommDownloadRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private val marker = UUID.randomUUID().toString()
    private fun partial(folder: File) = File(folder, ".tandem-romm-${UUID.randomUUID()}.part")

    @Test fun restartRemovesOnlyTheExactRecordedOwnedTemporaryFile() {
        val games = temp.newFolder("PS2")
        val stage = partial(games).apply { writeText("partial$marker") }
        val other = partial(games).apply { writeText("unrelated") }
        val completed = File(games, "Completed.iso").apply { writeText("keep completed") }
        val state = temp.newFolder("state")
        // A device upgraded from the single-transfer downloader can still have this ledger.
        File(state, "active.json").writeText(Json.encodeToString(RommPartial(games.canonicalPath, stage.name, RommDownloadStore.fileKey(stage), marker)))
        assertTrue(RommDownloadStore(state).recover())
        assertFalse(stage.exists()); assertEquals("unrelated", other.readText()); assertEquals("keep completed", completed.readText())
        assertTrue(RommDownloadStore(state).recover())
    }

    @Test fun replacedOrUnownedTemporaryFileIsRetained() {
        val games = temp.newFolder("PS2")
        val stage = partial(games).apply { writeText("not our payload") }
        val store = RommDownloadStore(temp.newFolder("state"))
        store.newLog().save(RommPartial(games.canonicalPath, stage.name, null, marker))
        assertFalse(store.recover()); assertEquals("not our payload", stage.readText())
    }

    @Test fun aCrashAfterFooterRemovalCanRecoverTheValidatedStagingFile() {
        val games = temp.newFolder("PS2")
        val data = "validated payload".toByteArray()
        val stage = partial(games).apply { writeBytes(data) }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }
        val store = RommDownloadStore(temp.newFolder("state"))
        store.newLog().save(RommPartial(games.canonicalPath, stage.name, null, marker, data.size.toLong(), sha1))
        assertTrue(store.recover()); assertFalse(stage.exists())
    }

    @Test fun publicationBeforeBookkeepingNeverDeletesTheFinalGame() {
        val games = temp.newFolder("PS2")
        val completed = File(games, "Completed.iso").apply { writeText("keep") }
        val store = RommDownloadStore(temp.newFolder("state"))
        store.newLog().save(RommPartial(games.canonicalPath, partial(games).name, "old-key", marker))
        assertTrue(store.recover()); assertEquals("keep", completed.readText())
    }

    @Test fun unsafeOrCorruptRecordsCannotDeleteArbitraryFiles() {
        val games = temp.newFolder("PS2")
        val sentinel = File(games, "Existing.iso").apply { writeText("keep") }
        val state = temp.newFolder("state")
        val store = RommDownloadStore(state)
        store.newLog().save(RommPartial(games.canonicalPath, "Existing.iso", RommDownloadStore.fileKey(sentinel), marker))
        assertFalse(store.recover()); assertTrue(sentinel.exists())
        File(state, "active.json").writeText("broken")
        assertFalse(store.recover()); assertEquals("keep", sentinel.readText())
    }

    @Test fun recoveryVisitsEveryInterruptedTransferAndKeepsUncertainPartials() {
        val folder = temp.newFolder("games")
        val state = temp.newFolder("state")
        val store = RommDownloadStore(state)
        val first = partial(folder).apply { writeText("first$marker") }
        val second = partial(folder).apply { writeText("second$marker") }
        val uncertain = partial(folder).apply { writeText("someone else's file") }
        for (file in listOf(first, second, uncertain))
            store.newLog().save(RommPartial(folder.canonicalPath, file.name, null, marker))

        assertFalse(RommDownloadStore(state).recover())
        assertFalse(first.exists())
        assertFalse(second.exists())
        assertEquals("someone else's file", uncertain.readText())
        assertEquals(1, state.listFiles()!!.size)
    }

    @Test fun destinationChoiceSurvivesRestartAndContainsNoCredentials() {
        val state = temp.newFolder("state")
        val key = RommDownloadNames.serverKey("https://fixture.example.test") + ":11"
        RommDownloadStore(state).remember(key, "ps2|/synthetic/PS2")
        RommDownloadStore(state).rememberIfAbsent(key, "ps2|/synthetic/AnotherFolder")
        assertEquals("ps2|/synthetic/PS2", RommDownloadStore(state).choice(key))
        assertFalse(File(state, "destinations.json").readText().contains("fixture.example.test"))
    }
}
