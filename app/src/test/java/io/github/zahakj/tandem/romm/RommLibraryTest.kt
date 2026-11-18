package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.PrivateOffline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class RommLibraryTest {
    private val config = RommConfig("https://fixture.example.test/romm", "synthetic-token", true)
    private fun spec(version: String = "5.4.1", params: String = "platform_ids,search_term,limit,offset,with_char_index,with_total") = RommResponse(200,
        """{"info":{"version":"$version"},"paths":{"/api/platforms":{"get":{}},"/api/roms":{"get":{"parameters":[${params.split(',').joinToString { """{"name":"$it","in":"query"}""" }}]}}}}""")
    private val platformBody = RommResponse(200, """[{"id":4,"name":"Game Boy Advance","slug":"gba","fs_slug":"gba","rom_count":51,"custom_name":null}]""")
    private val game = """{"id":9,"platform_id":4,"fs_name":"Fixture.gba","fs_size_bytes":64,"name":"Fixture","summary":"Synthetic description","metadatum":{"genres":["Puzzle"],"developers":["Example"],"first_release_date":1000},"path_cover_small":"/assets/romm/resources/roms/4/9/cover/small.png"}"""
    private fun page(items: String = game, offset: Int = 0, total: String = "51", limit: Int = 50) = RommResponse(200, """{"items":[$items],"total":$total,"limit":$limit,"offset":$offset}""")
    private class Transport(vararg responses: RommResponse) : RommTransport {
        val calls = mutableListOf<Pair<String, String?>>()
        val responses = responses.toMutableList()
        override fun get(url: String, bearer: String?): RommResponse {
            calls += url to bearer
            return responses.removeAt(0)
        }
    }
    private fun failure(expected: RommProblem, action: () -> Unit) {
        assertEquals(expected, assertThrows(RommBrowseFailure::class.java, action).problem)
    }

    @Test fun validatesLiveOperationsBeforeSendingTokenThenReusesSessionContract() {
        val transport = Transport(spec(), platformBody, page())
        val client = RommLibraryClient(config, transport) {}
        assertEquals("Game Boy Advance", client.platforms().single().title)
        val loaded = client.games(4)
        assertEquals("Puzzle", loaded.items.single().metadatum!!.genres.single())
        assertEquals(3, transport.calls.size)
        assertEquals("${config.server}/openapi.json" to null, transport.calls[0])
        assertEquals("${config.server}/api/platforms" to config.token, transport.calls[1])
        assertTrue(transport.calls[2].first.contains("platform_ids=4&limit=50&offset=0"))
        assertTrue(transport.calls[2].first.contains("with_char_index=false"))
        assertFalse(transport.calls[2].first.contains("with_rom_id_index"))
        assertEquals(config.token, transport.calls[2].second)
        assertEquals(50, loaded.nextOffset)
    }

    @Test fun encodesSearchAsQueryDataAndHonorsOffsets() {
        val transport = Transport(spec(), page(offset = 50, total = "51"))
        val loaded = RommLibraryClient(config, transport) {}.games(4, "A&B #1", 50)
        assertTrue(transport.calls.last().first.contains("search_term=A%26B+%231"))
        assertTrue(transport.calls.last().first.contains("offset=50"))
        assertNull(loaded.nextOffset)
    }

    @Test fun homeSearchFindsARomAbsentFromTheCurrentBrowsePage() = runBlocking {
        val laterGame = """{"id":91,"platform_id":4,"fs_name":"Sonic.gba","fs_size_bytes":64,"name":"Sonic"}"""
        val transport = Transport(spec(), page(), page(items = laterGame, total = "1"))
        val client = RommLibraryClient(config, transport) {}
        val browse = RommHomePage().append(client.games(4))
        val search = RommHomeSearchResults().next(listOf(4), "Sonic") { id, term, offset -> client.games(id, term, offset) }
        assertEquals(listOf(9L), browse.games.map { it.id })
        assertEquals(50, browse.nextOffset)
        assertEquals(listOf(91L), search.pages.getValue(4).games.map { it.id })
        assertTrue(transport.calls.last().first.contains("platform_ids=4&limit=50&offset=0&search_term=Sonic"))
    }

    @Test fun unknownFieldsAndMissingOptionalMetadataAreAllowed() {
        val transport = Transport(spec(), page("""{"id":9,"platform_id":4,"fs_name":"File.gba","fs_size_bytes":0,"future":"ok"}"""))
        assertEquals("File.gba", RommLibraryClient(config, transport) {}.games(4).items.single().title)
    }

    @Test fun unsupportedVersionOrMissingQueryContractReceivesNoToken() {
        for (response in listOf(spec("5.5.0"), spec(params = "limit,offset"))) {
            val transport = Transport(response)
            assertThrows(RommBrowseFailure::class.java) { RommLibraryClient(config, transport) {}.games(4) }
            assertEquals(1, transport.calls.size)
            assertNull(transport.calls.single().second)
        }
    }

    @Test fun missingRomOperationIsRejectedBeforeAuthentication() {
        val transport = Transport(RommResponse(200, """{"info":{"version":"5.4.1"},"paths":{"/api/platforms":{"get":{}}}}"""))
        failure(RommProblem.Contract) { RommLibraryClient(config, transport) {}.platforms() }
        assertEquals(1, transport.calls.size)
    }

    @Test fun disabledIncompleteAndCleartextConfigurationsMakeNoRequests() {
        for (candidate in listOf(config.copy(enabled = false), config.copy(token = ""), config.copy(server = "http://fixture.example.test"))) {
            val transport = Transport()
            assertThrows(RommBrowseFailure::class.java) { RommLibraryClient(candidate, transport) {}.platforms() }
            assertTrue(transport.calls.isEmpty())
        }
    }

    @Test fun privateModeBlocksTheAnonymousRequest() {
        val transport = Transport()
        failure(RommProblem.PrivateMode) { RommLibraryClient(config, transport) { throw PrivateOffline() }.games(4) }
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun enteringPrivateModeAfterSchemaLoadStopsTheAuthenticatedRequest() {
        val transport = Transport(spec())
        var checks = 0
        failure(RommProblem.PrivateMode) { RommLibraryClient(config, transport) { if (++checks == 2) throw PrivateOffline() }.games(4) }
        assertEquals(1, transport.calls.size)
        assertNull(transport.calls.single().second)
    }

    @Test fun authenticationPermissionRedirectAndServerErrorsStaySafe() {
        for ((code, problem) in listOf(401 to RommProblem.Authentication, 403 to RommProblem.LibraryPermission, 302 to RommProblem.Redirect, 404 to RommProblem.Contract, 503 to RommProblem.Server)) {
            val transport = Transport(spec(), RommResponse(code, config.token))
            val error = assertThrows(RommBrowseFailure::class.java) { RommLibraryClient(config, transport) {}.games(4) }
            assertEquals(problem, error.problem)
            assertFalse(error.toString().contains(config.token))
        }
    }

    @Test fun mismatchedPlatformDuplicateIdsAndWrongPagingAreContractErrors() {
        val invalid = listOf(page(game.replace("\"platform_id\":4", "\"platform_id\":8")), page("$game,$game"), page(offset = 1), page(limit = 51), page(total = "-1"))
        for (response in invalid) failure(RommProblem.Contract) { RommLibraryClient(config, Transport(spec(), response)) {}.games(4) }
    }

    @Test fun malformedOrUnexpectedJsonCannotReplaceValidPages() {
        for (body in listOf("<html>login</html>", "[]", "null", "{}")) {
            failure(RommProblem.Contract) { RommLibraryClient(config, Transport(spec(), RommResponse(200, body))) {}.games(4) }
        }
    }

    @Test fun socketTlsAndNetworkExceptionsAreClassifiedWithoutTheirDetails() {
        for ((error, problem) in listOf(SocketTimeoutException(config.token) to RommProblem.Timeout, SSLHandshakeException(config.token) to RommProblem.Tls, IOException(config.token) to RommProblem.Network)) {
            failure(problem) { RommLibraryClient(config, RommTransport { _, _ -> throw error }) {}.platforms() }
        }
    }

    @Test(expected = InterruptedException::class) fun cancellationPropagates() {
        RommLibraryClient(config, RommTransport { _, _ -> throw InterruptedException() }) {}.platforms()
    }

    @Test fun emptyPagesStopEvenIfTheServerCountChanged() {
        val result = RommLibraryClient(config, Transport(spec(), page(items = "", total = "100"))) {}.games(4)
        assertNull(result.nextOffset)
    }

    @Test fun unknownTotalUsesPageSizeAndDoesNotOverflowOffsets() {
        assertEquals(50, RommGamePage(List(50) { RommGameInfo(it.toLong(), 4, "file$it", 1) }, null, 50, 0).nextOffset)
        assertNull(RommGamePage(listOf(RommGameInfo(1, 4, "file", 1)), null, 50, 0).nextOffset)
        assertNull(RommGamePage(listOf(RommGameInfo(1, 4, "file", 1)), Long.MAX_VALUE, 50, Int.MAX_VALUE - 3).nextOffset)
    }
}
