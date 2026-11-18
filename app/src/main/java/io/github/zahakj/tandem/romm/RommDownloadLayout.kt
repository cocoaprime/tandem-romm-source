package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.DeletePlanner
import io.github.zahakj.tandem.Platform
import java.io.File
import java.nio.file.Paths
import java.text.NumberFormat
import java.util.Locale

data class RommDownloadPart(val file: RommDownloadFile, val path: String)

/** The launch file is relative to the game folder. Null denotes an emulator that launches the folder itself. */
data class RommBundleLaunch(val path: String?, val discs: List<String> = emptyList())

data class RommDownloadLayout(val name: String, val size: Long, val parts: List<RommDownloadPart>, val folder: Boolean) {
    fun launchFor(platform: Platform): RommBundleLaunch? {
        if (platform.source != null) return null
        if (!folder) return if (platform.matches(name)) RommBundleLaunch(name) else null
        val playable = parts.map { it.path }.filter { platform.matches(it.substringAfterLast('/')) }
        val playlists = playable.filter { extension(it) in PLAYLISTS }
        if (playlists.size == 1) return RommBundleLaunch(playlists.single())
        if (playlists.size > 1) return playlists.singleOrNull { it == "$name.m3u" || it == "$name.m3u8" }?.let { RommBundleLaunch(it) }
        if (playable.isEmpty() && platform.allPlayers.any { "dir" in it.byExtension }) return RommBundleLaunch(null)
        val discs = playable.filter { extension(it) in PLAYLIST_IMAGES || extension(it) in TRACK_SHEETS }
        val entries = discs.ifEmpty { playable }
        if (entries.size == 1) return RommBundleLaunch(entries.single())
        val playlist = "$name.m3u"
        if (discs.size < 2 || playlist.length > 240 || !platform.matches(playlist) ||
            parts.any { it.path.equals(playlist, ignoreCase = true) || it.path.startsWith("$playlist/", ignoreCase = true) }) return null
        if (discs.map { it.substringBeforeLast('.').lowercase(Locale.ROOT) }.distinct().size != discs.size) return null
        if (discs.map(::extension).distinct().size > 1 && discs.any { !DISC_NUMBER.containsMatchIn(it) }) return null
        val order = compareBy<String>({ DISC_NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }, { it.lowercase(Locale.ROOT) })
        return RommBundleLaunch(playlist, discs.sortedWith(order))
    }

    /** Resolve launch descriptors within the staged download and verify every referenced disc or track. */
    internal fun validateReferences(root: File, launch: RommBundleLaunch) {
        val paths = parts.mapTo(hashSetOf()) { it.path }
        val stack = hashSetOf<String>()
        val checked = hashSetOf<String>()
        fun visit(path: String) {
            if (path in checked) return
            if (!stack.add(path) || stack.size > 24) throw RommDownloadFailure(RommDownloadProblem.MissingParts)
            val extension = extension(path)
            if (extension in TRACK_SHEETS || extension in PLAYLISTS) {
                val references = DeletePlanner.partReferences(File(root, path))
                if (references.isEmpty()) throw RommDownloadFailure(RommDownloadProblem.MissingParts)
                for (reference in references) {
                    // Some cores allow a display label after the filename, e.g. VICE's disk.d64|Side A.
                    val member = if (extension in PLAYLISTS) reference.substringBefore('|').trimEnd() else reference
                    val relative = member.replace('\\', '/')
                    if (relative.isBlank() || relative.startsWith('/') || ':' in relative)
                        throw RommDownloadFailure(RommDownloadProblem.MissingParts)
                    val parent = Paths.get(path).parent ?: Paths.get("")
                    val resolved = parent.resolve(relative).normalize().joinToString("/")
                    if (resolved !in paths) throw RommDownloadFailure(RommDownloadProblem.MissingParts)
                    visit(resolved)
                }
            }
            stack.remove(path)
            checked.add(path)
        }
        if (launch.discs.isNotEmpty()) launch.discs.forEach(::visit)
        else launch.path?.let(::visit)
    }

    companion object {
        internal const val MAX_PARTS = 2_048
        private val PLAYLISTS = setOf("m3u", "m3u8")
        private val TRACK_SHEETS = setOf("cue", "gdi", "ccd", "mds")
        // A file can enter a generated playlist only when the selected platform accepts both its format and M3U.
        private val PLAYLIST_IMAGES = setOf(
            "chd", "iso", "pbp", "cso", "zso", "nrg", "isz", "rvz", "gcm", "wbfs", "cdi",
            "adf", "adz", "dms", "ipf", // Amiga
            "d64", "d71", "d81", "g64", // Commodore 64
            "st", "msa", "stx", "dim", // Atari ST
            "dsk", "d88", "u88", // Amstrad CPC and NEC PC-88
        )
        private val DISC_NUMBER = Regex("""(?:disc|disk|cd)[ ._-]*(\d+)""", RegexOption.IGNORE_CASE)
        private fun extension(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)

        /** Also reject file/directory and case collisions so a bundle is safe on case-insensitive SD storage. */
        internal fun checkDistinctPaths(paths: Collection<String>) {
            val files = paths.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
            if (files.size != paths.size) throw RommDownloadFailure(RommDownloadProblem.Layout)
            val folders = mutableMapOf<String, String>()
            for (path in paths) {
                var parent = path.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    val key = parent.lowercase(Locale.ROOT)
                    val previous = folders.putIfAbsent(key, parent)
                    if (key in files || (previous != null && previous != parent)) throw RommDownloadFailure(RommDownloadProblem.Layout)
                    parent = parent.substringBeforeLast('/', "")
                }
            }
        }
    }
}

/** Decimal MB and GB, with a fixed threshold and locale-aware digits. */
internal fun rommSizeLabel(bytes: Long, locale: Locale = Locale.getDefault()): String {
    val gb = bytes >= 1_000_000_000L
    val size = bytes.coerceAtLeast(0L) / if (gb) 1_000_000_000.0 else 1_000_000.0
    val display = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
    val amount = if (size > 0 && size < 0.01) "<${display.format(0.01)}" else display.format(size)
    return "$amount ${if (gb) "GB" else "MB"}"
}
