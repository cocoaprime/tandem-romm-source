package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.Platform
import io.github.zahakj.tandem.PlatformLibrary
import io.github.zahakj.tandem.SystemNames
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest

@Serializable
data class RommDownloadFile(
    val id: Long,
    @SerialName("rom_id") val romId: Long,
    @SerialName("file_name") val name: String,
    @SerialName("file_size_bytes") val size: Long,
    @SerialName("is_top_level") val topLevel: Boolean,
    @SerialName("sha1_hash") val sha1: String? = null,
    @SerialName("md5_hash") val md5: String? = null,
    @SerialName("crc_hash") val crc: String? = null,
    @SerialName("full_path") val fullPath: String? = null,
)

@Serializable
data class RommDownloadManifest(
    val id: Long,
    @SerialName("platform_id") val platformId: Long,
    @SerialName("fs_name") val name: String,
    @SerialName("fs_size_bytes") val size: Long,
    val files: List<RommDownloadFile>,
    @SerialName("full_path") val fullPath: String? = null,
) {
    fun layout(game: RommGameInfo): RommDownloadLayout {
        if (id != game.id || platformId != game.platformId || name != game.fileName || size != game.size || size <= 0 ||
            files.isEmpty() || files.size > RommDownloadLayout.MAX_PARTS || files.map { it.id }.distinct().size != files.size)
            throw RommDownloadFailure(RommDownloadProblem.Changed)
        RommDownloadNames.requireSafe(name)
        val single = game.simpleFile && files.size == 1 && files[0].topLevel && files[0].name == name &&
            (fullPath == null || files[0].fullPath == fullPath)
        val prefix = if (single) "" else fullPath?.takeIf { it.endsWith("/$name") }?.plus("/")
            ?: throw RommDownloadFailure(RommDownloadProblem.Layout)
        var total = 0L
        val parts = files.map { file ->
            if (file.id <= 0 || file.romId != id || file.size < 0 || file.size > size - total)
                throw RommDownloadFailure(RommDownloadProblem.Changed)
            total += file.size
            RommDownloadNames.requireSafe(file.name)
            for ((hash, length) in listOf(file.sha1 to 40, file.md5 to 32, file.crc to 8)) {
                if (!hash.isNullOrBlank() && !Regex("[a-fA-F0-9]{$length}").matches(hash))
                    throw RommDownloadFailure(RommDownloadProblem.Integrity)
            }
            val path = if (single) file.name else file.fullPath?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
                ?: throw RommDownloadFailure(RommDownloadProblem.Layout)
            if (path.substringAfterLast('/') != file.name || file.topLevel != !path.contains('/'))
                throw RommDownloadFailure(RommDownloadProblem.Layout)
            RommDownloadNames.requireRelative(path)
            RommDownloadPart(file, path)
        }
        if (total != size) throw RommDownloadFailure(RommDownloadProblem.Changed)
        RommDownloadLayout.checkDistinctPaths(parts.map { it.path })
        return RommDownloadLayout(name, size, parts, !single)
    }
}

data class RommPreparedDownload(val layout: RommDownloadLayout, val game: RommGameInfo)

object RommDownloadNames {
    fun requireSafe(name: String) {
        if (name.isBlank() || name.length > 240 || name.startsWith('.') || name.endsWith('.') || name.endsWith(' ') ||
            name.any { it.isISOControl() || it in "/\\:*?\"<>|" })
            throw RommDownloadFailure(RommDownloadProblem.Filename)
    }

    fun requireRelative(path: String) {
        val segments = path.split('/')
        if (path.length > 1024 || segments.size > 24) throw RommDownloadFailure(RommDownloadProblem.Filename)
        segments.forEach(::requireSafe)
    }

    fun url(server: String, gameId: Long, file: RommDownloadFile): String {
        require(gameId > 0 && file.id > 0 && file.romId == gameId)
        requireSafe(file.name)
        val encoded = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        return "${RommAddress.normalize(server)}/api/roms/$gameId/content/$encoded?file_ids=${file.id}"
    }

    /** Namespace local choices without persisting private server addresses or tokens. */
    fun serverKey(server: String): String = MessageDigest.getInstance("SHA-256")
        .digest(RommAddress.normalize(server).toByteArray()).joinToString("") { "%02x".format(it) }
}

data class RommDestination(val platform: Platform, val folder: File)

data class RommDestinationPlan(
    val choices: List<RommDestination>,
    val platforms: List<Platform>,
    val selected: RommDestination?,
    val unavailablePath: String?,
)

object RommDestinations {
    fun matchingPlatforms(remote: RommPlatformInfo, platforms: List<Platform>): List<Platform> {
        val names = SystemNames(platforms.filter { it.source == null })
        return listOf(remote.slug, remote.fsSlug, remote.name).filter { it.isNotBlank() }
            .flatMap { names.allOf(it) }.distinctBy { it.id }
    }

    /** Local-only preview; the original file is checked again after fresh download metadata arrives. Blocking IO. */
    fun plan(remote: RommPlatformInfo, filename: String?, platforms: List<Platform>, libraries: List<PlatformLibrary>,
             roots: List<File>, saved: String?, selectable: List<Platform> = platforms,
             supports: (Platform) -> Boolean = { filename?.let(it::matches) != false }): RommDestinationPlan {
        val choices = candidates(remote, platforms, libraries, roots, saved?.substringBefore('|'))
            .filter { supports(it.platform) }
        val selected = automatic(choices, saved)
        val compatible = selectable.filter { it.source == null && supports(it) }
        val mapped = compatible.filter { it.id == saved?.substringBefore('|') }
        val systems = (mapped + matchingPlatforms(remote, compatible)).distinctBy { it.id }.ifEmpty { compatible }
        return RommDestinationPlan(choices, systems, selected,
            saved?.substringAfter('|', "")?.takeIf { selected == null && it.isNotEmpty() })
    }

    /** Actual configured/scanned directories, not a folder invented from a remote display name. Blocking: IO. */
    fun candidates(remote: RommPlatformInfo, platforms: List<Platform>, libraries: List<PlatformLibrary>, roots: List<File>,
                   mappedPlatformId: String? = null): List<RommDestination> {
        val filePlatforms = platforms.filter { it.source == null }
        val names = SystemNames(filePlatforms)
        val matches = (matchingPlatforms(remote, filePlatforms) + filePlatforms.filter { it.id == mappedPlatformId }).distinctBy { it.id }
        return matches.flatMap { p ->
            val configured = p.folders.filter { File(it).isAbsolute }.map(::File) +
                roots.flatMap { root -> p.folders.filter { !File(it).isAbsolute }.map { name -> File(root, name) } }
            val rootSystems = roots.filter { root -> names.allOf(root.name).any { it.id == p.id } }
            val scanned = libraries.filter { it.platform.id == p.id }.flatMap { it.folders }
            val allowed = (roots + p.folders.filter { File(it).isAbsolute }.map(::File)).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            (scanned + configured + rootSystems).filter { runCatching { it.isDirectory && it.canRead() && it.canWrite() }.getOrDefault(false) }
                .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.filter { folder -> allowed.any { folder == it || folder.path.startsWith(it.path + File.separator) } }
                .map { RommDestination(p, it) }
        }.distinctBy { it.platform.id to it.folder.path }
    }

    fun automatic(candidates: List<RommDestination>, saved: String?): RommDestination? =
        if (saved != null) candidates.singleOrNull { "${it.platform.id}|${it.folder.path}" == saved } else candidates.singleOrNull()

    /** A user-picked local folder must already exist and be usable; never create a directory from a remote name. */
    fun picked(platform: Platform, directory: File, filename: String?): RommDestination {
        if (!directory.isAbsolute || directory.path.split('/', '\\').any { it == "." || it == ".." } ||
            platform.source != null || filename?.let(platform::matches) == false) throw RommDownloadFailure(RommDownloadProblem.Folder)
        val folder = directory.canonicalFile
        if (!folder.isDirectory || !folder.canRead() || !folder.canWrite()) throw RommDownloadFailure(RommDownloadProblem.Folder)
        return RommDestination(platform, folder)
    }
}

enum class RommDownloadProblem(val message: String) {
    Changed("The remote file changed or has incomplete metadata. Refresh RoMM and try again"),
    Layout("Tandem could not verify this game's file or folder layout"),
    LaunchEntry("No unambiguous launch file was found for this game's folder and system"),
    MissingParts("A disc or track listed by this game is missing or points outside its folder"),
    Filename("The server filename cannot be safely saved on this storage"),
    Integrity("Download validation failed. The file was not added to your library"),
    Exists("A file with this name already exists locally. Existing games will not be overwritten"),
    BusyDestination("Another download uses this filename for the same system or folder. Wait for it to finish"),
    Folder("The game folder is missing, read-only or no longer configured. Check Game folders and storage access"),
    Space("There is not enough free space in the game folder"),
    Storage("The download could not be saved. Check storage access, free space and the SD card"),
    Response("The server did not return the expected original file. Check the server and proxy"),
    Cancelled("Download cancelled. Retry starts a fresh download"),
    Recovery("An interrupted temporary file could not be safely removed. Check the destination before retrying"),
}

class RommDownloadFailure(val problem: RommDownloadProblem) : IOException(problem.message)

/** Bound each write before adding it, including transfers beyond 2 GiB and near Long's limit. */
fun rommWrittenBytes(written: Long, count: Int, expected: Long): Long {
    if (written < 0 || count < 0 || written > expected || count.toLong() > expected - written)
        throw RommDownloadFailure(RommDownloadProblem.Integrity)
    return written + count
}
