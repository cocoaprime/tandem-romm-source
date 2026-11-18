package io.github.zahakj.tandem.romm

import io.github.zahakj.tandem.Platform
import io.github.zahakj.tandem.data.IconGridConfig
import kotlinx.coroutines.CancellationException

internal data class RommShortcut(val serverKey: String, val platformId: Long, val gameId: Long) {
    val key get() = "romm-game:$serverKey:$platformId:$gameId"

    companion object {
        fun parse(key: String): RommShortcut? {
            val (kind, server, platform, game) = key.split(':', limit = 4).takeIf { it.size == 4 } ?: return null
            if (kind != "romm-game" || server.length != 64 || server.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
            return RommShortcut(server, platform.toLongOrNull()?.takeIf { it > 0 } ?: return null,
                game.toLongOrNull()?.takeIf { it > 0 } ?: return null)
        }
    }
}

internal fun IconGridConfig.replaceRommShortcut(remote: String, local: String): IconGridConfig {
    fun replace(slots: List<String>): List<String> {
        if (remote !in slots) return slots
        val occupied = slots.indexOf(remote)
        return slots.mapIndexed { index, key ->
            when {
                index == occupied -> local
                key == local || key == remote -> ""
                else -> key
            }
        }
    }
    if (remote !in added && remote !in order && folders.none { remote in it.items }) return this
    return copy(order = replace(order), added = added.map { if (it == remote) local else it }.distinct(),
        folders = folders.map { folder -> if (remote in folder.items) folder.copy(items = replace(folder.items)) else folder })
}

internal fun mergedFolderGames(placed: List<String>, local: List<String>, remote: List<String>, title: (String) -> String): List<String> {
    val seen = placed.toHashSet()
    return placed + local.sortedBy { title(it).lowercase() }.filter(seen::add) +
        remote.sortedBy { title(it).lowercase() }.filter(seen::add)
}

/** Preserve gaps, widgets and folders while ordering the visible game slots by availability. */
internal fun localFirstGameSlots(slots: List<String>, visible: Set<String>): List<String> {
    fun game(key: String) = key in visible && (key.startsWith("game:") || key.startsWith("romm-game:"))
    val games = slots.filter(::game)
    if (games.none { it.startsWith("romm-game:") }) return slots
    val (local, remote) = games.partition { it.startsWith("game:") }
    val ordered = (local + remote).iterator()
    return slots.map { if (game(it)) ordered.next() else it }
}

/** Place the transient page action after the last visible tile, before trailing edit slots. */
internal fun withRommPageAction(slots: List<String>, visible: Set<String>, action: String?): List<String> =
    if (action == null) slots else slots.take(slots.indexOfLast { it in visible } + 1) + action

/** If paging held focus, hand it to a newly shown game. A filtered final page falls back to its neighbour. */
internal fun rommPageFocus(previous: List<String>, current: List<String>, focused: String?): String? {
    if (focused == null || !focused.startsWith("romm-more:") || focused !in previous) return null
    val before = previous.toHashSet()
    current.firstOrNull { it.startsWith("romm-game:") && it !in before }?.let { return it }
    if (focused in current) return null
    val present = current.toHashSet()
    return previous.takeWhile { it != focused }.lastOrNull { it in present }
}

internal fun keepExplicitRommSlots(slots: List<String>, saved: Set<String>): List<String> =
    slots.mapIndexed { index, key -> if (key.startsWith("romm-game:") && key !in saved) "romm-auto:$index" else key }

internal data class RommHomeSystem(val key: String, val platform: Platform?, val remote: List<RommPlatformInfo>) {
    val title: String get() = platform?.name ?: remote.single().title
}

internal fun homeRommSystems(remote: List<RommPlatformInfo>, local: List<Platform>,
                             mapped: Map<Long, String>, disabled: Set<String>): Map<String, RommHomeSystem> =
    remote.filter { it.romCount > 0 }.groupBy { system ->
        val saved = mapped[system.id]?.let { id -> local.firstOrNull { it.id == id && it.source == null } }
        (saved ?: RommDestinations.matchingPlatforms(system, local).singleOrNull())?.id ?: "romm:${system.id}"
    }.filterKeys { it !in disabled }.mapValues { (key, systems) ->
        RommHomeSystem(key, local.firstOrNull { it.id == key }, systems)
    }

internal data class RommHomePage(val games: List<RommGameInfo> = emptyList(), val nextOffset: Int? = 0) {
    fun append(page: RommGamePage): RommHomePage {
        require(page.offset == nextOffset)
        val merged = (games + page.items).distinctBy { it.id }
        return RommHomePage(merged, page.nextOffset.takeIf { merged.size > games.size })
    }
}

internal fun RommGameInfo.availableOnHome(visible: RommAvailability, allFiles: RommAvailability): Boolean =
    size > 0 && onDisk && visible == RommAvailability.Remote && allFiles == RommAvailability.Remote

/** A folder needs its detail response before Tandem can check the emulator entry. */
internal fun RommGameInfo.mayMatch(platform: Platform?): Boolean = !simpleFile || platform?.matches(fileName) != false

/** Search has its own cursor for each mapped RoMM platform. Browse pages never enter this state. */
internal data class RommHomeSearchResults(
    val pages: Map<Long, RommHomePage> = emptyMap(),
    val failed: Set<Long> = emptySet(),
) {
    fun hasMore(platformIds: List<Long>): Boolean =
        platformIds.any { it !in pages || pages[it]?.nextOffset != null }

    suspend fun next(platformIds: List<Long>, query: String, fetch: suspend (Long, String, Int) -> RommGamePage): RommHomeSearchResults {
        val targets = platformIds.filter { it in failed }.ifEmpty { platformIds }
        val updated = pages.toMutableMap()
        val errors = failed.toMutableSet()
        for (id in targets) {
            val page = pages[id] ?: RommHomePage()
            val offset = page.nextOffset ?: continue
            try {
                updated[id] = page.append(fetch(id, query, offset))
                errors.remove(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                errors.add(id)
            }
        }
        return RommHomeSearchResults(updated, errors)
    }
}

internal fun gamePickerSystems(local: Map<String, String>, remote: Map<String, RommHomeSystem>, preferred: String?): List<Pair<String, String>> {
    val names = remote.filterValues { system -> system.remote.any { it.romCount > 0 } }.mapValues { it.value.title } + local
    return names.toList().sortedWith(compareBy({ it.first != preferred }, { it.second.lowercase() }, { it.first }))
}
