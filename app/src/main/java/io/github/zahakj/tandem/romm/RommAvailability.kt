package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.Game
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Locale

@Serializable
data class RommInstallOrigin(val serverKey: String, val platformId: Long, val gameId: Long)

data class RommLocalFile(val platform: String, val platformNames: Set<String>, val fileName: String, val size: Long,
                         val origin: RommInstallOrigin? = null) {
    companion object {
        fun read(game: Game): RommLocalFile? {
            if (game.platform.source != null) return null
            val bundle = game.rommBundle?.let { if (!it.complete()) return null else it.record }
            if (bundle == null && (!game.file.isFile || !game.file.canRead())) return null
            return RommLocalFile(
                game.platform.id,
                (game.platform.folders.map { File(it).name } + game.platform.aliases + game.platform.name).toSet(),
                bundle?.remoteName ?: game.file.name, bundle?.size ?: game.file.length(), bundle?.origin ?: game.meta.rommOrigin,
            )
        }
    }
}

enum class RommAvailability(val label: String, val explanation: String, val installed: Boolean = false) {
    Remote("Remote", "No matching visible local file was found in the scanned library"),
    Downloaded("Game Downloaded", "Tandem downloaded this game and its local file is available", true),
    LocalMatch("Game Downloaded", "A local file has the same platform, filename and size. Contents have not been checksum-verified", true),
    Ambiguous("Check local files", "Several local files or platforms could match. No installed copy has been assumed"),
    DifferentSize("Different local file", "A local filename matches, but its size differs from the remote game"),
}

/** Built once on IO after a scan/privacy change; visible cards perform bounded lookups on the UI thread. */
class RommLocalIndex(private val local: List<RommLocalFile>, private val serverKey: String? = null,
                     private val mappedSystems: Map<Long, String> = emptyMap()) {
    private fun normalized(value: String) = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    private val index = buildMap<Pair<String, String>, MutableSet<Int>> {
        local.forEachIndexed { id, file ->
            for (name in file.platformNames + file.platform) getOrPut(normalized(name) to file.fileName) { mutableSetOf() }.add(id)
        }
    }
    private val bySystem = local.groupBy { it.platform to it.fileName }
    private val byOrigin = local.filter { it.origin != null }.groupBy { it.origin }

    fun match(platform: RommPlatformInfo, game: RommGameInfo, localSystem: String? = null): RommAvailability {
        if (game.platformId != platform.id) return RommAvailability.Remote
        if (serverKey != null && byOrigin[RommInstallOrigin(serverKey, platform.id, game.id)].orEmpty().any {
                it.fileName == game.fileName && it.size == game.size && game.size > 0
            }) return RommAvailability.Downloaded
        val mapped = (localSystem ?: mappedSystems[platform.id])?.let { bySystem[it to game.fileName] }?.takeIf { it.isNotEmpty() }
        val names = setOf(platform.slug, platform.fsSlug, platform.name).map(::normalized).filter { it.isNotEmpty() }.toSet()
        val candidates = mapped ?: names.flatMap { index[it to game.fileName].orEmpty() }.distinct().map { local[it] }
        if (candidates.isEmpty()) return RommAvailability.Remote
        if (candidates.size > 1) return RommAvailability.Ambiguous
        return if (game.size > 0 && candidates.single().size == game.size) RommAvailability.LocalMatch else RommAvailability.DifferentSize
    }
}

/** Small in-memory cache only. Failed requests cannot replace a previously successful page. */
class RommPageCache(private val capacity: Int = 8) {
    data class Key(val platform: Long, val search: String, val offset: Int)
    private val pages = LinkedHashMap<Key, RommGamePage>(16, 0.75f, true)
    init { require(capacity > 0) }
    @Synchronized fun get(key: Key): RommGamePage? = pages[key]
    @Synchronized fun put(key: Key, page: RommGamePage) {
        pages[key] = page
        while (pages.size > capacity) pages.remove(pages.keys.first())
    }
    @Synchronized fun clear() { pages.clear() }
}

/** Credential-free, bounded cover bytes. A config change clears this cache along with the session. */
class RommArtworkCache(private val maxBytes: Int = 8 * 1024 * 1024) {
    init { require(maxBytes > 0) }
    private val images = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var size = 0
    @Synchronized fun get(key: String): ByteArray? = images[key]
    @Synchronized fun put(key: String, bytes: ByteArray) {
        images.remove(key)?.let { size -= it.size }
        if (bytes.size > maxBytes) return
        images[key] = bytes
        size += bytes.size
        while (size > maxBytes) images.remove(images.keys.first())?.let { size -= it.size }
    }
    @Synchronized fun clear() { images.clear(); size = 0 }
}
