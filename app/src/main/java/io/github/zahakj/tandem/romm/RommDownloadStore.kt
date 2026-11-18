package io.github.zahakj.tandem.romm

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

@Serializable
data class RommPartial(val directory: String, val temporary: String, val fileKey: String?, val marker: String = "",
                       val verifiedSize: Long? = null, val verifiedSha1: String? = null)

interface RommPartialLog {
    fun save(partial: RommPartial)
    fun clear()
}

/** Device-only recovery ledger and nonsecret destination choices. Network credentials never enter these files. */
class RommDownloadStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val choices get() = File(directory, "destinations.json")

    fun newLog(): RommPartialLog {
        val ledger = File(directory, "active-${java.util.UUID.randomUUID()}.json")
        return object : RommPartialLog {
            override fun save(partial: RommPartial) = write(ledger, json.encodeToString(partial))
            override fun clear() { Files.deleteIfExists(ledger.toPath()) }
        }
    }

    internal fun saveFolder(partial: RommFolderPartial) = write(folderLedger(partial), json.encodeToString(partial))
    internal fun clearFolder(partial: RommFolderPartial) { Files.deleteIfExists(folderLedger(partial).toPath()) }

    private fun folderLedger(partial: RommFolderPartial) = File(directory, "folder-${partial.id}.json")

    /** Call before starting any transfers. Also reads the legacy single-transfer ledger. */
    @Synchronized fun recover(): Boolean {
        if (!directory.exists()) return true
        val ledgers = directory.listFiles { file -> LEDGER.matches(file.name) } ?: return false
        val folders = directory.listFiles { file -> FOLDER_LEDGER.matches(file.name) } ?: return false
        // A bundle may have an active member transfer. Remove its owned file partial before the enclosing tree.
        val filesRecovered = ledgers.map { runCatching { recover(it) }.getOrDefault(false) }.all { it }
        val foldersRecovered = folders.map { ledger -> runCatching {
            if (!Files.isRegularFile(ledger.toPath(), NOFOLLOW_LINKS) || ledger.length() !in 1..MAX_BUNDLE_RECORD_BYTES)
                return@runCatching false
            val partial = json.decodeFromString<RommFolderPartial>(ledger.readText())
            if (folderLedger(partial) != ledger || !partial.clean()) return@runCatching false
            Files.deleteIfExists(ledger.toPath())
            true
        }.getOrDefault(false) }.all { it }
        return filesRecovered && foldersRecovered
    }

    private fun recover(ledger: File): Boolean {
        if (!Files.isRegularFile(ledger.toPath(), NOFOLLOW_LINKS)) return false
        val record = json.decodeFromString<RommPartial>(read(ledger))
        val folder = File(record.directory)
        if (!folder.isDirectory || folder.canonicalPath != record.directory || !Regex("\\.tandem-romm-[a-f0-9-]{36}\\.part").matches(record.temporary)) return false
        val file = File(folder, record.temporary)
        // A completed publication has no temporary file. Its final file is never removed during recovery.
        if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
            if (!owns(record, file)) return false
            Files.delete(file.toPath())
        }
        Files.deleteIfExists(ledger.toPath())
        return true
    }

    @Synchronized fun choice(key: String): String? = mappings()[key]
    @Synchronized fun remember(key: String, value: String) = write(choices, json.encodeToString(mappings() + (key to value)))
    @Synchronized fun rememberIfAbsent(key: String, value: String) {
        val saved = mappings()
        if (key !in saved) write(choices, json.encodeToString(saved + (key to value)))
    }
    @Synchronized fun mappedSystems(serverKey: String): Map<Long, String> = buildMap {
        val prefix = "$serverKey:"
        for ((key, value) in mappings()) {
            if (!key.startsWith(prefix)) continue
            val platform = key.removePrefix(prefix).toLongOrNull() ?: continue
            val system = value.substringBefore('|', "")
            if (system.isNotEmpty()) put(platform, system)
        }
    }
    private fun mappings(): Map<String, String> = if (!choices.exists()) emptyMap() else json.decodeFromString(read(choices))
    private fun read(file: File): String { require(file.length() <= 128 * 1024); return file.readText() }

    private fun write(file: File, value: String) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw RommDownloadFailure(RommDownloadProblem.Storage)
        val temp = File.createTempFile("state-", ".tmp", directory)
        try {
            FileOutputStream(temp).use { it.write(value.toByteArray()); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp.toPath()) }
    }

    companion object {
        private val LEDGER = Regex("active(?:-[a-f0-9-]{36})?\\.json")
        private val FOLDER_LEDGER = Regex("folder-[a-f0-9-]{36}\\.json")
        fun fileKey(file: File): String? = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString()
        fun owns(record: RommPartial, file: File): Boolean {
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.canonicalFile.parentFile?.path != record.directory) return false
            if (record.fileKey != null) return fileKey(file) == record.fileKey
            // Some JVM filesystems have no file IDs. A random footer identifies an uncommitted partial instead.
            val marker = record.marker.toByteArray()
            if (marker.size == 36 && file.length() >= marker.size) {
                val tail = ByteArray(marker.size)
                RandomAccessFile(file, "r").use { it.seek(it.length() - marker.size); it.readFully(tail) }
                if (tail.contentEquals(marker)) return true
            }
            // The footer is removed only after durable bookkeeping of the validated original payload.
            if (record.verifiedSize != file.length() || record.verifiedSha1 == null) return false
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) {
                val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read)
            } }
            return digest.digest().joinToString("") { "%02x".format(it) } == record.verifiedSha1
        }
    }
}
