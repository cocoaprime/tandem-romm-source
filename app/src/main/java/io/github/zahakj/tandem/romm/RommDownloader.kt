package io.github.zahakj.tandem.romm

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32

data class RommDownloaded(val file: File, val checksumVerified: Boolean, val bundle: RommInstalledBundle? = null)
enum class RommTransferPhase { Downloading, Verifying, Publishing }

/** Streams one file. The caller reserves its destination and gives each transfer its own recovery log. */
class RommDownloader(
    private val transport: RommStreamTransport,
    private val log: RommPartialLog,
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val publish: (File, File) -> Unit = { from, to -> Files.move(from.toPath(), to.toPath()); Unit },
) {
    fun download(config: RommConfig, gameId: Long, file: RommDownloadFile, directory: File, control: RommTransferControl,
                 validateDestination: () -> Unit = {}, phase: (RommTransferPhase) -> Unit = {},
                 progress: (Long, Long) -> Unit = { _, _ -> }): RommDownloaded {
        if (!config.enabled || !config.configured) throw RommBrowseFailure(RommProblem.Disabled)
        require(file.size >= 0)
        val url = RommDownloadNames.url(config.server, gameId, file)
        control.check(); validateDestination()
        val folder = directory.canonicalFile
        if (!folder.isDirectory || !folder.canWrite()) throw RommDownloadFailure(RommDownloadProblem.Folder)
        val target = File(folder, file.name)
        if (target.canonicalFile.parentFile != folder || Files.exists(target.toPath(), NOFOLLOW_LINKS)) throw RommDownloadFailure(RommDownloadProblem.Exists)
        val free = usableSpace(folder)
        if (free > 0 && free < file.size) throw RommDownloadFailure(RommDownloadProblem.Space)
        val temporary = File(folder, ".tandem-romm-${UUID.randomUUID()}.part")
        val output = FileChannel.open(temporary.toPath(), CREATE_NEW, WRITE)
        var record = try { RommPartial(folder.path, temporary.name, RommDownloadStore.fileKey(temporary), UUID.randomUUID().toString()) }
            catch (e: Exception) { output.close(); throw e }
        var published = false
        var recorded = false
        try {
            val marker = if (record.fileKey == null) record.marker.toByteArray() else byteArrayOf()
            if (file.size > Long.MAX_VALUE - marker.size) throw RommDownloadFailure(RommDownloadProblem.Integrity)
            if (marker.isNotEmpty()) { output.write(ByteBuffer.wrap(marker)); output.force(true) }
            log.save(record); recorded = true
            val sha1 = MessageDigest.getInstance("SHA-1")
            val md5 = MessageDigest.getInstance("MD5")
            val crc = CRC32()
            var written = 0L
            var lastProgress = 0L
            phase(RommTransferPhase.Downloading)
            transport.open(url, config.token, control).use { response ->
                if (response.length != null && response.length != file.size) throw RommDownloadFailure(RommDownloadProblem.Integrity)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    control.check()
                    val count = response.input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    val nextWritten = rommWrittenBytes(written, count, file.size)
                    control.check()
                    output.position(written)
                    val chunk = ByteBuffer.wrap(buffer, 0, count)
                    while (chunk.hasRemaining()) { control.check(); output.write(chunk) }
                    sha1.update(buffer, 0, count); md5.update(buffer, 0, count); crc.update(buffer, 0, count)
                    written = nextWritten
                    if (marker.isNotEmpty()) { val tail = ByteBuffer.wrap(marker); while (tail.hasRemaining()) output.write(tail) }
                    val now = System.nanoTime()
                    if (now - lastProgress >= 200_000_000 || written == file.size) { progress(written, file.size); lastProgress = now }
                }
                output.force(true)
            }
            phase(RommTransferPhase.Verifying)
            control.check()
            if (written != file.size || temporary.length() != file.size + marker.size) throw RommDownloadFailure(RommDownloadProblem.Integrity)
            fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
            val actualSha1 = hex(sha1.digest())
            for ((expected, actual) in listOf(file.sha1 to actualSha1, file.md5 to hex(md5.digest()), file.crc to "%08x".format(crc.value))) {
                if (!expected.isNullOrBlank() && !expected.equals(actual, true)) throw RommDownloadFailure(RommDownloadProblem.Integrity)
            }
            control.check(); validateDestination()
            if (directory.canonicalFile != folder || !RommDownloadStore.owns(record, temporary)) throw RommDownloadFailure(RommDownloadProblem.Folder)
            if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) throw RommDownloadFailure(RommDownloadProblem.Exists)
            control.check()
            if (marker.isNotEmpty()) {
                record = record.copy(verifiedSize = written, verifiedSha1 = actualSha1)
                log.save(record)
                output.truncate(written); output.force(true)
            }
            output.close()
            if (temporary.length() != file.size || !RommDownloadStore.owns(record, temporary)) throw RommDownloadFailure(RommDownloadProblem.Integrity)
            phase(RommTransferPhase.Publishing)
            control.check()
            try { publish(temporary, target) } catch (_: FileAlreadyExistsException) { throw RommDownloadFailure(RommDownloadProblem.Exists) }
            published = true
            // A ledger-clear failure must not turn a published, validated game into a failed download.
            runCatching { log.clear() }
            return RommDownloaded(target, listOf(file.sha1, file.md5, file.crc).any { !it.isNullOrBlank() })
        } finally {
            output.close()
            if (!published) {
                val removed = runCatching {
                    if (Files.exists(temporary.toPath(), NOFOLLOW_LINKS)) {
                        if (!RommDownloadStore.owns(record, temporary)) return@runCatching false
                        Files.delete(temporary.toPath())
                    }
                    true
                }.getOrDefault(false)
                if (recorded && removed) runCatching { log.clear() }
            }
        }
    }
}
