package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.PrivateOffline
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import javax.net.ssl.SSLException

/** Minimal projections of the verified 5.4.1 schemas; no remote record is a local Tandem Game. */
@Serializable
data class RommPlatformInfo(
    val id: Long,
    val name: String,
    val slug: String = "",
    @SerialName("fs_slug") val fsSlug: String = "",
    @SerialName("custom_name") val customName: String? = null,
    @SerialName("rom_count") val romCount: Long = 0,
) {
    val title: String get() = customName?.takeIf { it.isNotBlank() } ?: name
}

/** Filter a display projection only; keep the original platform response available when the preference changes. */
fun visibleRommPlatforms(platforms: List<RommPlatformInfo>, hideEmpty: Boolean): List<RommPlatformInfo> =
    if (hideEmpty) platforms.filter { it.romCount > 0 } else platforms

@Serializable
data class RommMetadata(
    val genres: List<String> = emptyList(),
    val developers: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    @SerialName("player_count") val players: String = "",
    @SerialName("first_release_date") val released: Long? = null,
)

@Serializable
data class RommGameInfo(
    val id: Long,
    @SerialName("platform_id") val platformId: Long,
    @SerialName("fs_name") val fileName: String,
    @SerialName("fs_size_bytes") val size: Long,
    val name: String? = null,
    val summary: String? = null,
    val metadatum: RommMetadata? = null,
    @SerialName("path_cover_small") val coverSmall: String? = null,
    @SerialName("path_cover_large") val coverLarge: String? = null,
    @SerialName("has_file_on_disk") val onDisk: Boolean = true,
    @SerialName("has_simple_single_file") val simpleFile: Boolean = true,
) {
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: fileName
}

@Serializable
data class RommGamePage(val items: List<RommGameInfo>, val total: Long? = null, val limit: Int, val offset: Int) {
    val nextOffset: Int? get() {
        if (items.isEmpty() || offset > Int.MAX_VALUE - limit) return null
        return (offset + limit).takeIf { if (total == null) items.size == limit else it.toLong() < total }
    }
}

/** Safe typed failures only: never display raw proxy bodies, URLs, credentials or parser exceptions. */
class RommBrowseFailure(val problem: RommProblem) : IOException(problem.message)

/** One configured session. Only GET; no requests until called explicitly by the browser. Blocking: use IO. */
class RommLibraryClient(
    private val config: RommConfig,
    private val transport: RommTransport,
    private val checkNetwork: () -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var verified = false
    private var parameters = emptySet<String>()
    private var downloadContract = false

    fun platforms(): List<RommPlatformInfo> = safely {
        verify()
        val list = json.decodeFromString<List<RommPlatformInfo>>(get("/api/platforms"))
        require(list.all { it.id > 0 && it.name.isNotBlank() && it.romCount >= 0 } && list.map { it.id }.distinct().size == list.size)
        list.sortedBy { it.title.lowercase() }
    }

    fun games(platformId: Long, search: String = "", offset: Int = 0): RommGamePage = safely {
        require(platformId > 0 && offset >= 0 && search.length <= 200)
        verify()
        val query = mutableListOf("platform_ids=$platformId", "limit=$PAGE_SIZE", "offset=$offset")
        if (search.isNotBlank()) query += "search_term=" + URLEncoder.encode(search.trim(), "UTF-8")
        // Disable optional full-library indexes only when the running OpenAPI advertises those parameters.
        for (name in listOf("with_char_index", "with_filter_values", "with_rom_id_index")) {
            if (name in parameters) query += "$name=false"
        }
        if ("with_total" in parameters) query += "with_total=true"
        val page = json.decodeFromString<RommGamePage>(get("/api/roms?${query.joinToString("&")}"))
        require(page.offset == offset && page.limit in 1..PAGE_SIZE && page.items.size <= page.limit)
        require(page.total == null || page.total >= 0)
        require(page.items.all { it.id > 0 && it.platformId == platformId && it.fileName.isNotBlank() && it.size >= 0 })
        require(page.items.map { it.id }.distinct().size == page.items.size)
        page
    }

    fun prepareDownload(game: RommGameInfo): RommPreparedDownload = safely {
        verify()
        require(downloadContract)
        val body = get("/api/roms/${game.id}")
        val details = json.decodeFromString<RommGameInfo>(body)
        val layout = json.decodeFromString<RommDownloadManifest>(body).layout(game.copy(simpleFile = details.simpleFile))
        RommPreparedDownload(layout, details)
    }

    internal fun shortcutGame(shortcut: RommShortcut): RommGameInfo = safely {
        verify()
        require(downloadContract && shortcut.serverKey == RommDownloadNames.serverKey(config.server))
        val game = json.decodeFromString<RommGameInfo>(get("/api/roms/${shortcut.gameId}"))
        require(game.id == shortcut.gameId && game.platformId == shortcut.platformId && game.fileName.isNotBlank())
        game
    }

    private fun verify() {
        if (verified) return
        val root = json.parseToJsonElement(response(transport.get("${root()}/openapi.json", null))) as? JsonObject
            ?: throw RommBrowseFailure(RommProblem.Contract)
        val version = (root["info"] as? JsonObject)?.get("version")?.jsonPrimitive
        if (version?.isString != true || version.content.removePrefix("v") != "5.4.1") throw RommBrowseFailure(RommProblem.Unsupported)
        val paths = root["paths"] as? JsonObject ?: throw RommBrowseFailure(RommProblem.Contract)
        val content = (paths["/api/roms/{id}/content/{file_name}"] as? JsonObject)?.get("get") as? JsonObject
        downloadContract = (paths["/api/roms/{id}"] as? JsonObject)?.get("get") is JsonObject &&
            (content?.get("parameters") as? JsonArray).orEmpty().any {
                val p = it as? JsonObject
                p?.get("name")?.jsonPrimitive?.content == "file_ids" && p["in"]?.jsonPrimitive?.content == "query"
            }
        for (path in listOf("/api/platforms", "/api/roms")) {
            require((paths[path] as? JsonObject)?.get("get") is JsonObject)
        }
        val operation = (paths["/api/roms"] as JsonObject)["get"] as JsonObject
        parameters = (operation["parameters"] as? JsonArray).orEmpty().mapNotNull {
            val parameter = it as? JsonObject
            if (parameter?.get("in")?.jsonPrimitive?.content == "query") parameter["name"]?.jsonPrimitive?.content else null
        }.toSet()
        require(parameters.containsAll(setOf("platform_ids", "search_term", "limit", "offset")))
        verified = true
    }

    private fun root(): String {
        if (!config.enabled) throw RommBrowseFailure(RommProblem.Disabled)
        if (!RommAddress.validToken(config.token)) throw RommBrowseFailure(RommProblem.Configuration)
        return try { RommAddress.normalize(config.server) } catch (_: IllegalArgumentException) {
            throw RommBrowseFailure(RommProblem.Configuration)
        }
    }

    private fun get(path: String): String {
        checkNetwork()
        return response(transport.get(root() + path, config.token))
    }

    private fun response(response: RommResponse): String = when (response.status) {
        200 -> response.body
        401 -> throw RommBrowseFailure(RommProblem.Authentication)
        403 -> throw RommBrowseFailure(RommProblem.LibraryPermission)
        in 300..399 -> throw RommBrowseFailure(RommProblem.Redirect)
        404 -> throw RommBrowseFailure(RommProblem.Contract)
        else -> throw RommBrowseFailure(RommProblem.Server)
    }

    private fun <T> safely(block: () -> T): T {
        try {
            root()
            checkNetwork()
            val value = block()
            checkNetwork()
            return value
        } catch (e: RommBrowseFailure) { throw e
        } catch (e: RommDownloadFailure) { throw e
        } catch (e: InterruptedException) { throw e
        } catch (e: CancellationException) { throw e
        } catch (_: PrivateOffline) { throw RommBrowseFailure(RommProblem.PrivateMode)
        } catch (_: SocketTimeoutException) { throw RommBrowseFailure(RommProblem.Timeout)
        } catch (_: SSLException) { throw RommBrowseFailure(RommProblem.Tls)
        } catch (_: IOException) { throw RommBrowseFailure(RommProblem.Network)
        } catch (_: Exception) { throw RommBrowseFailure(RommProblem.Contract) }
    }

    companion object { const val PAGE_SIZE = 50 }
}

/** Only a returned cover for this exact game under the verified same-server resource directory is eligible. */
object RommArtworkAddress {
    const val MAX_BYTES = 4 * 1024 * 1024

    fun resolve(server: String, game: RommGameInfo, large: Boolean = false): String? = candidates(server, game, large).firstOrNull()

    fun candidates(server: String, game: RommGameInfo, large: Boolean): List<String> =
        (if (large) listOf(game.coverLarge, game.coverSmall) else listOf(game.coverSmall, game.coverLarge))
            .filterNotNull().mapNotNull { resolvePath(server, game, it) }.distinct()

    private fun resolvePath(server: String, game: RommGameInfo, supplied: String): String? {
        return runCatching {
            val base = URI(RommAddress.normalize(server))
            val raw = URI(supplied.replace(" ", "%20"))
            require(raw.rawUserInfo == null && raw.rawFragment == null && raw.rawAuthority == null && raw.scheme == null)
            val prefix = "/assets/romm/resources/roms/${game.platformId}/${game.id}/cover/"
            val path = raw.rawPath
            require(path.startsWith(prefix))
            val file = path.removePrefix(prefix)
            require(Regex("(?:small|big)\\.(?:png|jpg|jpeg|webp|avif)", RegexOption.IGNORE_CASE).matches(file))
            // The resource exists without its cache-busting query; ignore untrusted query values entirely.
            "https://${base.rawAuthority}${base.rawPath.trimEnd('/')}$path"
        }.getOrNull()
    }
}
