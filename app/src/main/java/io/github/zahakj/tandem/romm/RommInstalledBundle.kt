package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.Game
import io.github.zahakj.tandem.Discs
import io.github.zahakj.tandem.GameFinder
import io.github.zahakj.tandem.Platform
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

@Serializable
data class RommBundleRecord(
    val origin: RommInstallOrigin,
    val remoteName: String,
    val size: Long,
    val system: String,
    val entry: String?,
    val files: Map<String, Long>,
)

/** The scanner keeps one local tile for the original folder, even when several of its parts look playable. */
data class RommInstalledBundle(val root: File, val record: RommBundleRecord) {
    val entryFile: File get() = record.entry?.let { File(root, it) } ?: root

    internal fun game(platform: Platform, group: String?, tag: File?): Game? {
        if (record.system != platform.id || !complete()) return null
        // Feed the published playlist into upstream's first-disc emulator handling, without regrouping the bundle.
        val discs = if (entryFile.extension.equals(Discs.PLAYLIST, ignoreCase = true)) {
            Discs.resolve(entryFile, Discs.read(entryFile).map { it.substringBefore('|').trimEnd() })
                .filter { it.relativeToOrNull(root)?.invariantSeparatorsPath in record.files && it.isFile }
        } else emptyList()
        return Game(platform, entryFile, GameFinder.cleanTitle(record.remoteName), group, tag, this, discs)
    }

    internal fun complete(): Boolean = runCatching {
        if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) return@runCatching false
        val canonical = root.canonicalFile
        record.files.all { (path, size) ->
            val file = File(canonical, path)
            Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.canonicalFile == file && file.canRead() && file.length() == size
        }
    }.getOrDefault(false)

    internal fun containsOnlyBundleFiles(): Boolean =
        runCatching { knownBundleEntries(root, record.files.keys + MARKER + RommFolderPartial.OWNER) != null }.getOrDefault(false)

    companion object {
        const val MARKER = ".tandem-romm-game.json"
        private val json = Json { ignoreUnknownKeys = true }

        internal fun write(root: File, record: RommBundleRecord) {
            writeBundleFile(File(root, MARKER), json.encodeToString(record).toByteArray())
        }

        internal fun read(root: File): RommInstalledBundle? = runCatching {
            val marker = File(root, MARKER)
            if (!Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) || marker.length() !in 1..MAX_BUNDLE_RECORD_BYTES) return null
            val record = json.decodeFromString<RommBundleRecord>(marker.readText())
            if (record.files.size !in 1..RommDownloadLayout.MAX_PARTS + 1 || record.size <= 0 ||
                record.files.values.any { it < 0 } || (record.entry != null && record.entry !in record.files)) return null
            RommDownloadNames.requireSafe(record.remoteName)
            record.files.keys.forEach(RommDownloadNames::requireRelative)
            RommDownloadLayout.checkDistinctPaths(record.files.keys)
            RommInstalledBundle(root, record)
        }.getOrNull()
    }
}

internal const val MAX_BUNDLE_RECORD_BYTES = 4 * 1024 * 1024L

internal fun writeBundleFile(file: File, data: ByteArray) {
    java.nio.channels.FileChannel.open(file.toPath(), CREATE_NEW, WRITE).use { channel ->
        val buffer = java.nio.ByteBuffer.wrap(data)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }
}

/** Enumerate exactly the registered bundle tree and refuse links or unrelated content before any cleanup. */
internal fun knownBundleEntries(root: File, paths: Collection<String>): List<Path>? {
    val allowedFiles = paths.toSet()
    val allowedFolders = hashSetOf("")
    for (path in paths) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty() && allowedFolders.add(parent)) parent = parent.substringBeforeLast('/', "")
    }
    val base = root.toPath()
    val found = mutableListOf<Path>()
    Files.walk(base).use { entries ->
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val path = iterator.next()
            val relative = base.relativize(path).joinToString("/")
            val known = if (Files.isDirectory(path, NOFOLLOW_LINKS)) relative in allowedFolders
                else Files.isRegularFile(path, NOFOLLOW_LINKS) && relative in allowedFiles
            if (!known) return null
            found.add(path)
        }
    }
    return found.sortedByDescending { it.nameCount }
}
