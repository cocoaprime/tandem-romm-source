package io.github.zahakj.tandem.romm

import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

@Serializable
internal data class RommFolderPartial(val directory: String, val id: String, val parts: List<String>) {
    val staging: File get() = File(directory, ".tandem-romm-$id.bundle")
    val allowed: Set<String> get() = parts.toSet() + OWNER + RommInstalledBundle.MARKER

    fun ownsStage(): Boolean {
        val parent = File(directory)
        val owner = File(staging, OWNER)
        return Files.isDirectory(staging.toPath(), NOFOLLOW_LINKS) && staging.canonicalFile.parentFile == parent &&
            Files.isRegularFile(owner.toPath(), NOFOLLOW_LINKS) && owner.length() == id.length.toLong() && owner.readText() == id
    }

    fun clean(): Boolean {
        val parent = File(directory)
        if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id ||
            !parent.isDirectory || parent.canonicalFile != parent || parts.size !in 1..RommDownloadLayout.MAX_PARTS + 1) return false
        parts.forEach(RommDownloadNames::requireRelative)
        if (!Files.exists(staging.toPath(), NOFOLLOW_LINKS)) return true
        if (!ownsStage()) return false
        val tree = knownBundleEntries(staging, allowed) ?: return false
        val owner = File(staging, OWNER).toPath()
        // Keep the ownership proof until the other entries are gone, so interrupted cleanup can retry.
        tree.filter { it != owner && it != staging.toPath() }.forEach { Files.deleteIfExists(it) }
        Files.delete(owner)
        Files.delete(staging.toPath())
        return true
    }

    companion object {
        const val OWNER = ".tandem-romm-owner"
    }
}

/** Download originals into a hidden sibling folder, then publish the complete game with a single move. */
internal class RommFolderDownloader(private val transport: RommStreamTransport, private val store: RommDownloadStore) {
    fun download(
        config: RommConfig, origin: RommInstallOrigin, layout: RommDownloadLayout, launch: RommBundleLaunch,
        destination: RommDestination, control: RommTransferControl, validateDestination: () -> Unit,
        phase: (RommTransferPhase) -> Unit = {}, progress: (Long, Long) -> Unit = { _, _ -> },
    ): RommDownloaded {
        control.check()
        validateDestination()
        val folder = destination.folder.canonicalFile
        val target = File(folder, layout.name)
        if (!folder.isDirectory || !folder.canWrite()) throw RommDownloadFailure(RommDownloadProblem.Folder)
        if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) throw RommDownloadFailure(RommDownloadProblem.Exists)
        val space = folder.usableSpace
        if (space > 0 && space < layout.size) throw RommDownloadFailure(RommDownloadProblem.Space)
        val playlist = launch.discs.takeIf { it.isNotEmpty() }?.joinToString(separator = "\n", postfix = "\n") {
            if (it.startsWith("#")) "./$it" else it
        }?.toByteArray()
        val sizes = layout.parts.associate { it.path to it.file.size }.toMutableMap()
        if (playlist != null) sizes[checkNotNull(launch.path)] = playlist.size.toLong()
        val partial = RommFolderPartial(folder.path, UUID.randomUUID().toString(), sizes.keys.toList())
        val stage = partial.staging
        Files.createDirectory(stage.toPath())
        var published = false
        try {
            writeBundleFile(File(stage, RommFolderPartial.OWNER), partial.id.toByteArray())
            store.saveFolder(partial)
            phase(RommTransferPhase.Downloading)
            var transferred = 0L
            var checksums = true
            for (part in layout.parts) {
                control.check()
                val parent = File(stage, part.path).parentFile
                if (parent.canonicalFile != parent || !partial.ownsStage()) throw RommDownloadFailure(RommDownloadProblem.Folder)
                Files.createDirectories(parent.toPath())
                if (parent.canonicalFile != parent || !partial.ownsStage()) throw RommDownloadFailure(RommDownloadProblem.Folder)
                val result = RommDownloader(transport, store.newLog()).download(config, origin.gameId, part.file, parent, control) { bytes, _ ->
                    progress(transferred + bytes, layout.size)
                }
                transferred += part.file.size
                checksums = checksums && result.checksumVerified
            }
            phase(RommTransferPhase.Verifying)
            control.check()
            layout.validateReferences(stage, launch)
            if (playlist != null) writeBundleFile(File(stage, checkNotNull(launch.path)), playlist)
            val record = RommBundleRecord(origin, layout.name, layout.size, destination.platform.id, launch.path, sizes)
            RommInstalledBundle.write(stage, record)
            if (!partial.ownsStage() || knownBundleEntries(stage, partial.allowed) == null || !RommInstalledBundle(stage, record).complete())
                throw RommDownloadFailure(RommDownloadProblem.Integrity)
            validateDestination()
            if (destination.folder.canonicalFile != folder || !partial.ownsStage()) throw RommDownloadFailure(RommDownloadProblem.Folder)
            phase(RommTransferPhase.Publishing)
            control.check()
            try { Files.move(stage.toPath(), target.toPath()) }
            catch (_: FileAlreadyExistsException) { throw RommDownloadFailure(RommDownloadProblem.Exists) }
            published = true
            runCatching { Files.deleteIfExists(File(target, RommFolderPartial.OWNER).toPath()) }
            runCatching { store.clearFolder(partial) }
            val installed = RommInstalledBundle(target, record)
            return RommDownloaded(installed.entryFile, checksums, installed)
        } finally {
            if (!published) runCatching { if (partial.clean()) store.clearFolder(partial) }
        }
    }
}
