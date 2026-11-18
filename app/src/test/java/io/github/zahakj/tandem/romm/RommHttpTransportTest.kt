package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.PrivateOffline
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class RommHttpTransportTest {
    private class Connection(private val code: Int = 200, private val bytes: ByteArray = "[]".toByteArray()) : HttpURLConnection(URL("https://fixture.example.test")) {
        var closed = false
        var bodyOpened = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(bytes).also { bodyOpened = true }
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() = Unit
    }

    @Test fun actualConnectionHasBearerTimeoutsAndNoRedirectsOrCache() {
        val connection = Connection()
        assertEquals(RommResponse(200, "[]"), RommHttpTransport({}, { connection }).get("https://fixture.example.test/api/platforms", "synthetic-token"))
        assertEquals("Bearer synthetic-token", connection.getRequestProperty("Authorization"))
        assertEquals("GET", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals(12_000, connection.connectTimeout)
        assertEquals(12_000, connection.readTimeout)
        assertTrue(connection.closed)
    }

    @Test fun anonymousOpenApiRequestHasNoAuthorization() {
        val connection = Connection()
        RommHttpTransport({}, { connection }).get("https://fixture.example.test/openapi.json", null)
        assertNull(connection.getRequestProperty("Authorization"))
    }

    @Test fun redirectAndErrorBodiesAreNeverReadOrRetained() {
        for (code in listOf(302, 401, 403, 500)) {
            val connection = Connection(code)
            assertEquals(RommResponse(code, ""), RommHttpTransport({}, { connection }).get("https://fixture.example.test", "synthetic-token"))
            assertFalse(connection.bodyOpened)
            assertTrue(connection.closed)
        }
    }

    @Test fun cleartextCannotEvenOpenAConnection() {
        var opened = false
        assertThrows(IllegalArgumentException::class.java) {
            RommHttpTransport({}, { opened = true; Connection() }).get("http://fixture.example.test", "synthetic-token")
        }
        assertFalse(opened)
    }

    @Test fun privateModeStopsBeforeOpeningConnection() {
        var opened = false
        assertThrows(PrivateOffline::class.java) {
            RommHttpTransport({ throw PrivateOffline() }, { opened = true; Connection() }).get("https://fixture.example.test", null)
        }
        assertFalse(opened)
    }

    @Test fun oversizedResponseFailsAndClosesConnection() {
        val connection = Connection(bytes = ByteArray(RommHttpTransport.MAX_BYTES + 1))
        assertThrows(IOException::class.java) { RommHttpTransport({}, { connection }).get("https://fixture.example.test", null) }
        assertTrue(connection.closed)
    }

    @Test fun switchingPrivateModeBeforeSendingClosesConnection() {
        val connection = Connection()
        var checks = 0
        assertThrows(PrivateOffline::class.java) {
            RommHttpTransport({ if (++checks == 2) throw PrivateOffline() }, { connection }).get("https://fixture.example.test", null)
        }
        assertTrue(connection.closed)
        assertFalse(connection.bodyOpened)
    }

    @Test fun binaryArtworkPreservesBytesAndUsesSameAuthenticatedTransportRules() {
        val expected = byteArrayOf(0, -1, 10, -128)
        val connection = Connection(bytes = expected)
        val response = RommHttpTransport({}, { connection }).bytes("https://fixture.example.test/assets/romm/resources/roms/4/9/cover/small.png", "synthetic-token", 16)
        assertEquals(200, response.status)
        assertArrayEquals(expected, response.bytes)
        assertEquals("image/*", connection.getRequestProperty("Accept"))
        assertEquals("Bearer synthetic-token", connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.closed)
    }

    @Test fun binaryArtworkHonorsItsSmallerLimitAndDiscardsRedirectBodies() {
        val oversized = Connection(bytes = ByteArray(17))
        assertThrows(IOException::class.java) { RommHttpTransport({}, { oversized }).bytes("https://fixture.example.test", null, 16) }
        assertTrue(oversized.closed)
        val redirect = Connection(302)
        val response = RommHttpTransport({}, { redirect }).bytes("https://fixture.example.test", "synthetic-token", 16)
        assertTrue(response.bytes.isEmpty())
        assertEquals(302, response.status)
        assertFalse(redirect.bodyOpened)
        assertTrue(redirect.closed)
    }

    @Test fun privateModeDuringBinaryReadDiscardsTheResponseAndClosesConnection() {
        val connection = Connection(bytes = ByteArray(16))
        var checks = 0
        assertThrows(PrivateOffline::class.java) {
            RommHttpTransport({ if (++checks == 4) throw PrivateOffline() }, { connection }).bytes("https://fixture.example.test", null, 32)
        }
        assertTrue(connection.bodyOpened)
        assertTrue(connection.closed)
    }
}
