package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.PrivateOffline
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class RommHttpStreamTest {
    private class Connection(val code: Int = 200, val headers: Map<String, String> = emptyMap()) : HttpURLConnection(URL("https://fixture.example.test")) {
        var closed = false
        var read = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String) = headers[name]
        override fun getContentType() = headers["Content-Type"]
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf(1, 2, 3)).also { read = true }
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() = Unit
    }

    @Test fun rawAuthenticatedStreamRejectsRedirectsAndCachesAndCloses() {
        val connection = Connection(headers = mapOf("Content-Length" to "3000000000"))
        RommHttpStreamTransport { connection }.open("https://fixture.example.test/api/roms/9/content/Fixture.iso", "synthetic-token", RommTransferControl {}).use {
            assertEquals(3_000_000_000L, it.length)
            assertFalse(connection.instanceFollowRedirects); assertFalse(connection.useCaches)
            assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
            assertEquals("Bearer synthetic-token", connection.getRequestProperty("Authorization"))
            assertEquals(12_000, connection.connectTimeout); assertEquals(15_000, connection.readTimeout)
        }
        assertTrue(connection.closed)
    }

    @Test fun authenticationErrorsRedirectsAndPartialResponsesDoNotOpenBodies() {
        for (code in listOf(401, 403, 302, 307, 404, 500, 206)) {
            val connection = Connection(code)
            assertThrows(RommBrowseFailure::class.java) { RommHttpStreamTransport { connection }.open("https://fixture.example.test", "synthetic-token", RommTransferControl {}) }
            assertFalse(connection.read); assertTrue(connection.closed)
        }
    }

    @Test fun compressedLoginAndMalformedLengthResponsesAreRejected() {
        for (headers in listOf(mapOf("Content-Encoding" to "gzip"), mapOf("Content-Type" to "text/html; charset=utf-8"), mapOf("Content-Type" to "application/json"), mapOf("Content-Length" to "bad"))) {
            val connection = Connection(headers = headers)
            assertThrows(RommDownloadFailure::class.java) { RommHttpStreamTransport { connection }.open("https://fixture.example.test", "synthetic-token", RommTransferControl {}) }
            assertFalse(connection.read); assertTrue(connection.closed)
        }
    }

    @Test fun cancelledConnectionIsDisconnectedAndCannotBeReused() {
        val connection = Connection()
        val control = RommTransferControl {}
        RommHttpStreamTransport { connection }.open("https://fixture.example.test", "synthetic-token", control).use {
            control.cancel(); assertTrue(connection.closed)
            assertThrows(RommDownloadFailure::class.java) { control.check() }
        }
    }

    @Test fun privateModeAndCleartextStopBeforeOpeningAConnection() {
        var opened = false
        val transport = RommHttpStreamTransport { opened = true; Connection() }
        assertThrows(PrivateOffline::class.java) { transport.open("https://fixture.example.test", "synthetic-token", RommTransferControl { throw PrivateOffline() }) }
        assertThrows(IllegalArgumentException::class.java) { transport.open("http://fixture.example.test", "synthetic-token", RommTransferControl {}) }
        assertFalse(opened)
    }
}
