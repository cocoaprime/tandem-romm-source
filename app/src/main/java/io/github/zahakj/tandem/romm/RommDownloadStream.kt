package io.github.zahakj.tandem.romm

import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One transfer's explicit cancellation handle also disconnects blocked socket reads. */
class RommTransferControl(private val checkNetwork: () -> Unit) {
    private val cancelled = AtomicBoolean(false)
    private val connection = AtomicReference<HttpURLConnection?>(null)
    val isCancelled: Boolean get() = cancelled.get()
    fun check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw RommDownloadFailure(RommDownloadProblem.Cancelled)
        checkNetwork()
    }
    fun attach(value: HttpURLConnection) { connection.set(value); check() }
    fun detach(value: HttpURLConnection) { connection.compareAndSet(value, null) }
    fun cancel(disconnect: (HttpURLConnection) -> Unit = { it.disconnect() }) {
        cancelled.set(true)
        connection.getAndSet(null)?.let(disconnect)
    }
}

class RommStream(
    val input: InputStream,
    val length: Long?,
    private val finish: () -> Unit,
) : Closeable {
    override fun close() { try { input.close() } finally { finish() } }
}

fun interface RommStreamTransport {
    fun open(url: String, token: String, control: RommTransferControl): RommStream
}

/** Original-file streaming. No redirects, HTTP caches, decompression or whole-ROM buffering. */
class RommHttpStreamTransport(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : RommStreamTransport {
    override fun open(url: String, token: String, control: RommTransferControl): RommStream {
        control.check()
        require(RommAddress.validToken(token))
        val target = URL(url)
        require(target.protocol == "https" && target.userInfo == null)
        val connection = openConnection(target)
        try {
            control.attach(connection)
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 12_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("Accept-Encoding", "identity")
            control.check()
            when (connection.responseCode) {
                200 -> Unit
                401 -> throw RommBrowseFailure(RommProblem.Authentication)
                403 -> throw RommBrowseFailure(RommProblem.LibraryPermission)
                in 300..399 -> throw RommBrowseFailure(RommProblem.Redirect)
                404 -> throw RommBrowseFailure(RommProblem.Contract)
                else -> throw RommBrowseFailure(RommProblem.Server)
            }
            val encoding = connection.getHeaderField("Content-Encoding")
            val type = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
            if ((!encoding.isNullOrBlank() && !encoding.equals("identity", true)) || type in setOf("text/html", "application/json", "application/xml", "text/xml"))
                throw RommDownloadFailure(RommDownloadProblem.Response)
            val rawLength = connection.getHeaderField("Content-Length")
            val length = rawLength?.toLongOrNull()
            if (rawLength != null && (length == null || length < 0)) throw RommDownloadFailure(RommDownloadProblem.Response)
            control.check()
            return RommStream(connection.inputStream, length) { control.detach(connection); connection.disconnect() }
        } catch (e: Exception) {
            control.detach(connection); connection.disconnect()
            throw e
        }
    }
}
