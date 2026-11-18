package io.github.zahakj.tandem.romm

import org.junit.Assert.*
import org.junit.Test

class RommDownloadApiTest {
    private val config = RommConfig("https://fixture.example.test", "synthetic-token", true)
    private val game = RommGameInfo(9, 11, "Fixture.iso", 64)
    private fun spec(content: Boolean) = """{"info":{"version":"5.4.1"},"paths":{
        "/api/platforms":{"get":{}},"/api/roms":{"get":{"parameters":[{"name":"platform_ids","in":"query"},{"name":"search_term","in":"query"},{"name":"limit","in":"query"},{"name":"offset","in":"query"}]}},
        "/api/roms/{id}":{"get":{}}${if (content) """, "/api/roms/{id}/content/{file_name}":{"get":{"parameters":[{"name":"file_ids","in":"query"}]}}""" else ""}}}"""

    @Test fun verifiesDetailAndContentContractsBeforeRequestingFileMetadata() {
        val calls = mutableListOf<Pair<String, String?>>()
        val client = RommLibraryClient(config, RommTransport { url, token ->
            calls += url to token
            RommResponse(200, if (token == null) spec(true) else """{"id":9,"platform_id":11,"fs_name":"Fixture.iso","fs_size_bytes":64,"name":"Updated title","summary":"A fixture description","metadatum":{"genres":["Racing"]},"path_cover_large":"/assets/romm/resources/roms/11/9/cover/big.png","files":[{"id":31,"rom_id":9,"file_name":"Fixture.iso","file_size_bytes":64,"is_top_level":true}]}""")
        }) {}
        val prepared = client.prepareDownload(game)
        assertEquals(31L, prepared.layout.parts.single().file.id)
        assertEquals("Updated title", prepared.game.title)
        assertEquals(listOf("Racing"), prepared.game.metadatum?.genres)
        assertEquals("${config.server}/assets/romm/resources/roms/11/9/cover/big.png",
            RommArtworkAddress.resolve(config.server, prepared.game, large = true))
        assertEquals(listOf("${config.server}/openapi.json" to null, "${config.server}/api/roms/9" to config.token), calls)
    }

    @Test fun detailFullPathsProduceOriginalFolderRelativePathsAndIndividualContentRequests() {
        val root = "roms/psx/Fixture"
        val folder = game.copy(fileName = "Fixture", simpleFile = false)
        val response = """{"id":9,"platform_id":11,"fs_name":"Fixture","full_path":"$root","fs_size_bytes":64,"has_simple_single_file":false,"files":[
            {"id":31,"rom_id":9,"file_name":"Game.cue","full_path":"$root/Game.cue","file_size_bytes":14,"is_top_level":true},
            {"id":32,"rom_id":9,"file_name":"Track.bin","full_path":"$root/Discs/Track.bin","file_size_bytes":50,"is_top_level":false}]}"""
        val client = RommLibraryClient(config, RommTransport { _, token -> RommResponse(200, if (token == null) spec(true) else response) }) {}
        val layout = client.prepareDownload(folder).layout
        assertTrue(layout.folder)
        assertEquals(listOf("Game.cue", "Discs/Track.bin"), layout.parts.map { it.path })
        assertEquals(listOf("Game.cue?file_ids=31", "Track.bin?file_ids=32"),
            layout.parts.map { RommDownloadNames.url(config.server, 9, it.file).substringAfterLast('/') })
    }

    @Test fun missingContentOperationStopsBeforeSendingBearer() {
        val tokens = mutableListOf<String?>()
        val client = RommLibraryClient(config, RommTransport { _, token -> tokens += token; RommResponse(200, spec(false)) }) {}
        assertEquals(RommProblem.Contract, assertThrows(RommBrowseFailure::class.java) { client.prepareDownload(game) }.problem)
        assertEquals(listOf<String?>(null), tokens)
    }

    @Test fun resolvingASavedShortcutUsesOnlyTheVerifiedReadOperation() {
        val requests = mutableListOf<Pair<String, String?>>()
        val shortcut = RommShortcut(RommDownloadNames.serverKey(config.server), 11, 9)
        val client = RommLibraryClient(config, RommTransport { url, token ->
            requests += url to token
            RommResponse(200, if (token == null) spec(true) else """{"id":9,"platform_id":11,"fs_name":"Fixture.iso","fs_size_bytes":64}""")
        }) {}
        assertEquals(game, client.shortcutGame(shortcut))
        assertThrows(RommBrowseFailure::class.java) { client.shortcutGame(shortcut.copy(serverKey = "b".repeat(64))) }
        assertEquals(listOf("${config.server}/openapi.json" to null, "${config.server}/api/roms/9" to config.token), requests)
        assertThrows(RommBrowseFailure::class.java) { client.shortcutGame(shortcut.copy(platformId = 12)) }
    }
}
