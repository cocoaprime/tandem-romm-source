package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.data.GameMeta
import io.github.zahakj.tandem.data.MediaKind
import io.github.zahakj.tandem.data.MetaField
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class RommImportTest {
    @get:Rule val folder = TemporaryFolder()
    private val origin = RommInstallOrigin("source", 11, 9)
    private val remote = RommGameInfo(9, 11, "Fixture.iso", 64, name = "Fixture name", summary = "Fixture summary",
        metadatum = RommMetadata(listOf("Racing"), listOf("Studio"), listOf("Publisher"), "2",
            Instant.parse("2001-02-03T00:00:00Z").epochSecond))

    @Test fun usesTheNormalLibraryMetadataFormatAndKeepsExistingFields() {
        val local = GameMeta(title = "My title", favorite = true, lockedFields = setOf(MetaField.DEVELOPER))
        val cover = folder.newFile("cover.jpg")
        val merged = RommImport.merge(local, remote, origin, cover)
        val restored = Json.decodeFromString<GameMeta>(Json.encodeToString(merged))
        assertEquals("My title", restored.title)
        assertEquals("Fixture summary", restored.description)
        assertEquals(listOf("Racing"), restored.genres)
        assertNull(restored.developer)
        assertEquals("Publisher", restored.publisher)
        assertEquals(2001, restored.year)
        assertEquals("2", restored.players)
        assertTrue(restored.favorite)
        assertEquals(cover.path, restored.box)
        assertNull(restored.coverUrl)
        assertEquals(origin, restored.rommOrigin)
    }

    @Test fun keepsSelectedOrLockedArtEvenWhenServerSuppliesACover() {
        val cover = folder.newFile("cover.jpg")
        assertEquals("/selected.jpg", RommImport.merge(GameMeta(box = "/selected.jpg"), remote, origin, cover).box)
        assertNull(RommImport.merge(GameMeta(lockedFields = setOf(MediaKind.Box.field)), remote, origin, cover).box)
    }

    @Test fun missingCoverDoesNotDropMetadataOrCompletionAssociation() {
        val saved = RommImport.saveCover(listOf("large"), { RommBinaryResponse(404, byteArrayOf(1)) }) {
            error("No failed response body should be saved")
        }
        val imported = RommImport.merge(GameMeta(), remote, origin, saved)
        assertNull(imported.box)
        assertEquals("Fixture name", imported.title)
        assertEquals(origin, imported.rommOrigin)
    }

    @Test fun triesASecondCoverWhenThePreferredOneCannotBeSaved() {
        val fixture = folder.newFile("cover.png")
        val saved = RommImport.saveCover(listOf("large", "small"),
            { source -> RommBinaryResponse(200, source.toByteArray()) },
            { data -> fixture.takeIf { data.decodeToString() == "small" } },
        )
        assertEquals(fixture, saved)
    }
}
