package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.data.Details
import io.github.zahakj.tandem.data.GameMeta
import io.github.zahakj.tandem.data.MediaKind
import io.github.zahakj.tandem.data.MetaType
import io.github.zahakj.tandem.data.mergeScrapedMetadata
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

internal object RommImport {
    fun merge(current: GameMeta, remote: RommGameInfo, origin: RommInstallOrigin, cover: File?): GameMeta {
        val metadata = remote.metadatum
        val details = Details(
            name = remote.name?.takeIf { it.isNotBlank() },
            description = remote.summary?.takeIf { it.isNotBlank() },
            genres = metadata?.genres.orEmpty().filter { it.isNotBlank() },
            developer = metadata?.developers.orEmpty().filter { it.isNotBlank() }.joinToString().takeIf { it.isNotBlank() },
            publisher = metadata?.publishers.orEmpty().filter { it.isNotBlank() }.joinToString().takeIf { it.isNotBlank() },
            year = metadata?.released?.let { runCatching { Instant.ofEpochSecond(it).atOffset(ZoneOffset.UTC).year }.getOrNull() }
                ?.takeIf { it in 1..9999 },
            players = metadata?.players?.takeIf { it.isNotBlank() },
        )
        val art = if (cover != null && current.box == null) mapOf(MediaKind.Box to cover.path) else emptyMap()
        return mergeScrapedMetadata(current, art, details, MetaType.entries.filter(details::has).toSet(), force = false)
            .copy(rommOrigin = origin)
    }

    fun saveCover(urls: List<String>, fetch: (String) -> RommBinaryResponse, save: (ByteArray) -> File?): File? =
        urls.firstNotNullOfOrNull { url ->
            val response = fetch(url)
            if (response.status == 200 && response.bytes.isNotEmpty()) save(response.bytes) else null
        }
}
