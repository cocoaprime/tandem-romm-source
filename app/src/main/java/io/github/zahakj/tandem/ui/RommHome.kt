package io.github.zahakj.tandem.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zahakj.tandem.HiddenFolders
import io.github.zahakj.tandem.NetGate
import io.github.zahakj.tandem.Platform
import io.github.zahakj.tandem.Platforms
import io.github.zahakj.tandem.Selected
import io.github.zahakj.tandem.Selection
import io.github.zahakj.tandem.data.SystemPrefs
import io.github.zahakj.tandem.romm.*
import kotlinx.coroutines.*

internal object RommHome {
    private val scope = MainScope()
    private var config by mutableStateOf<RommConfig?>(null)
    private var platforms by mutableStateOf<List<RommPlatformInfo>?>(null)
    private var localSystems by mutableStateOf<List<Platform>>(emptyList())
    private var pages by mutableStateOf<Map<Long, RommHomePage>>(emptyMap())
    private var client: RommLibraryClient? = null
    private var serverKey by mutableStateOf("")
    private var knownShortcuts by mutableStateOf<Map<RommShortcut, RommGameInfo>>(emptyMap())
    private var opening: Job? = null
    private var connectingJob: Job? = null
    private var connecting by mutableStateOf(false)
    private var connectionError by mutableStateOf<String?>(null)
    private var retryConnection by mutableIntStateOf(0)
    private var revision = 0
    private var demand by mutableStateOf<Pair<String, Int>?>(null)
    private var served: Pair<String, Int>? = null
    private var failedSystem by mutableStateOf<String?>(null)
    private var loadingSystem by mutableStateOf<String?>(null)
    val contentVersion: Any get() = listOf(pages, knownShortcuts, failedSystem, loadingSystem, RommDownloads.availabilityVersion)
    private val ready get() = config?.let { it == RommSettingsState.config && it.enabled && it.configured } == true && !NetGate.closed
    val systems by derivedStateOf {
        if (!ready) emptyMap() else homeRommSystems(platforms.orEmpty(), localSystems, RommDownloads.mappedSystems,
            SystemPrefs.data.systems.filterValues { !it.enabled }.keys)
            .filterKeys { !HiddenFolders.isHidden(it, null) }
    }

    fun title(id: String): String? = systems[id]?.title

    fun shows(selection: Selected.RommGame): Boolean {
        if (!ready || !atHomeLibrary()) return false
        val group = systems.values.firstOrNull { selection.platform in it.remote } ?: return false
        if (RommDetails.matches(selection.platform, selection.game)) return true
        val key = RommShortcut(serverKey, selection.platform.id, selection.game.id)
        return (pages[selection.platform.id]?.games?.any { it == selection.game } == true || knownShortcuts[key] == selection.game) &&
            selection.game.mayMatch(group.platform) &&
            RommDownloads.homeRemote(selection.platform, selection.game, group.platform?.id)
    }

    private fun atHomeLibrary() = Nav.route == Route.Home || Nav.route is Route.System

    val connectionStatus: String? get() = if (RommSettingsState.config.enabled && !NetGate.closed)
        when {
            connecting || config != RommSettingsState.config -> "Connecting to RoMM…"
            connectionError != null -> connectionError
            platforms != null -> "Connected · ${platforms?.size} platforms"
            else -> null
        } else null

    fun retryConnectionIfFailed() {
        if (connectionError != null) retryConnection++
    }

    data class SearchSource(val system: RommHomeSystem, val revision: Int)

    fun searchSource(systemId: String): SearchSource? = systems[systemId]?.let { SearchSource(it, revision) }

    val searchConnectionStatus: String? get() = if (platforms == null && RommSettingsState.config.configured) connectionStatus else null
    val searchConnectionFailed: Boolean get() = ready && connectionError != null

    suspend fun searchPage(source: SearchSource, platformId: Long, query: String, offset: Int): RommGamePage {
        if (source != searchSource(source.system.key)) throw CancellationException()
        val api = client ?: throw CancellationException()
        val page = runInterruptible(Dispatchers.IO) { api.games(platformId, search = query, offset = offset) }
        if (source != searchSource(source.system.key)) throw CancellationException()
        return page
    }

    fun searchTiles(source: SearchSource, result: RommHomeSearchResults, color: Color): List<GridItem> {
        val system = source.system
        if (source != searchSource(system.key)) return emptyList()
        return system.remote.flatMap { platform ->
            result.pages[platform.id]?.games.orEmpty().filter {
                it.mayMatch(system.platform) && RommDownloads.homeRemote(platform, it, system.platform?.id)
            }.map { game ->
                tile(system, platform, game, color) {
                    source == searchSource(system.key) &&
                        RommDownloads.homeRemote(platform, game, system.platform?.id)
                }
            }
        }
    }

    @Composable
    fun Observe(context: Context) {
        val current = RommSettingsState.config
        val closed = NetGate.closed
        val enabled = current.enabled && current.configured && !closed
        LaunchedEffect(current, closed, retryConnection) {
            val next = current.takeIf { enabled }
            if (config != next) {
                revision++
                config = next
                platforms = null
                localSystems = emptyList()
                pages = emptyMap()
                serverKey = ""
                knownShortcuts = emptyMap()
                opening?.cancel()
                opening = null
                connectingJob?.cancel()
                connectingJob = null
                demand = null
                served = null
                client = null
                loadingSystem = null
                failedSystem = null
                connecting = false
                connectionError = null
            }
            if (!ready || platforms != null || connectingJob?.isActive == true) return@LaunchedEffect
            val stamp = revision
            val api = RommLibraryClient(current, RommHttpTransport(NetGate::check), NetGate::check)
            connectionError = null
            connecting = true
            connectingJob = scope.launch {
                try {
                    val result = runInterruptible(Dispatchers.IO) {
                        Triple(api.platforms(), Platforms.all(context), RommDownloadNames.serverKey(current.server))
                    }
                    if (stamp == revision && ready) {
                        client = api
                        serverKey = result.third
                        platforms = result.first
                        localSystems = result.second
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (stamp == revision && ready) connectionError = (e as? RommBrowseFailure)?.problem?.message ?: "Could not load RoMM platforms"
                } finally {
                    if (stamp == revision) connecting = false
                }
            }
        }
    }

    @Composable
    fun Load(systemId: String?) {
        val system = systems[systemId]
        val current = config
        val request = demand?.takeIf { it.first == systemId }
        LaunchedEffect(current, system?.key, system?.remote, request) {
            if (system == null || !ready || !atHomeLibrary() || loadingSystem == system.key) return@LaunchedEffect
            val missing = system.remote.filter { it.id !in pages }
            val targets = if (missing.isNotEmpty()) missing else if (request != null && request != served)
                system.remote.filter { pages[it.id]?.nextOffset != null }.take(1) else emptyList()
            if (targets.isEmpty()) return@LaunchedEffect
            val api = client ?: return@LaunchedEffect
            val stamp = revision
            loadingSystem = system.key
            try {
                for (remote in targets) {
                    val page = pages[remote.id] ?: RommHomePage()
                    val offset = page.nextOffset ?: continue
                    val result = runInterruptible(Dispatchers.IO) { api.games(remote.id, offset = offset) }
                    if (stamp != revision || !ready) return@LaunchedEffect
                    val updated = pages + (remote.id to page.append(result))
                    val active = system.remote.mapTo(HashSet()) { it.id }
                    val spare = updated.filterKeys { it !in active }.entries.toList().takeLast((6 - active.size).coerceAtLeast(0))
                    pages = spare.associate { it.toPair() } + updated.filterKeys { it in active }
                }
                if (failedSystem == system.key) failedSystem = null
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (stamp == revision) failedSystem = system.key
            } finally {
                if (stamp == revision && loadingSystem == system.key) loadingSystem = null
                if (stamp == revision && request != null && demand == request) served = request
            }
        }
    }

    fun rememberShortcut(item: GridItem) {
        val selection = item.selected as? Selected.RommGame ?: return
        val shortcut = RommShortcut.parse(item.key) ?: return
        if (ready && shortcut.serverKey == serverKey) knownShortcuts = knownShortcuts + (shortcut to selection.game)
    }

    fun canPick(item: GridItem): Boolean {
        val selected = item.selected as? Selected.RommGame ?: return false
        if (!ready || RommShortcut.parse(item.key) != RommShortcut(serverKey, selected.platform.id, selected.game.id)) return false
        val group = systems.values.firstOrNull { selected.platform in it.remote } ?: return false
        return selected.game.mayMatch(group.platform) &&
            RommDownloads.homeRemote(selected.platform, selected.game, group.platform?.id)
    }

    fun shortcut(key: String): GridItem? {
        if (!ready || platforms == null) return null
        val entry = RommShortcut.parse(key)?.takeIf { it.serverKey == serverKey } ?: return null
        val system = systems.values.firstOrNull { group -> group.remote.any { it.id == entry.platformId } } ?: return null
        val platform = system.remote.first { it.id == entry.platformId }
        val game = knownShortcuts[entry] ?: pages[platform.id]?.games?.firstOrNull { it.id == entry.gameId }
        if (game != null) return tile(system, platform, game, SystemColors.of(system.key)).takeIf {
            game.mayMatch(system.platform) && RommDownloads.homeRemote(platform, game, system.platform?.id)
        }
        if (!RommDownloads.homeIndexReady) return null
        return GridItem(key, TileKind.Game, "RoMM game", "Available to download", system.title, SystemColors.of(system.key),
            TileArt.Symbol(Glyph.Cartridge, Palette.Muted), emptyList(),
            Selected.Setting("RoMM game", "Available to download", system.title, Palette.Sky, "Open"),
            default = false, open = { openShortcut(entry, system, platform) })
    }

    private fun openShortcut(entry: RommShortcut, system: RommHomeSystem, platform: RommPlatformInfo) {
        val api = client ?: return
        if (!ready || opening?.isActive == true) return
        val route = Nav.route
        val folder = IconGrid.openFolder
        val stamp = revision
        Toasts.show("Loading RoMM game…")
        opening = scope.launch {
            try {
                val game = runInterruptible(Dispatchers.IO) { api.shortcutGame(entry) }
                if (!ready || stamp != revision || route != Nav.route || folder != IconGrid.openFolder || system != systems[system.key]) return@launch
                knownShortcuts = knownShortcuts + (entry to game)
                if (game.mayMatch(system.platform) && RommDownloads.homeRemote(platform, game, system.platform?.id)) {
                    Selection.current = Selected.RommGame(platform, game)
                    RommDetails.open(platform, game)
                } else Toasts.show("This game is already local or is no longer available to download")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (ready && stamp == revision) Toasts.show("Could not open the RoMM game") }
        }
    }

    private fun tile(system: RommHomeSystem, platform: RommPlatformInfo, game: RommGameInfo, color: Color,
                     canOpen: () -> Boolean = { shows(Selected.RommGame(platform, game)) }): GridItem {
        val selected = Selected.RommGame(platform, game)
        return GridItem(RommShortcut(serverKey, platform.id, game.id).key, TileKind.Game, game.title, "Available to download", system.title,
            color, TileArt.RommBox(game), emptyList(), selected, default = false, platformId = system.key, open = {
                if (canOpen()) {
                    Selection.current = selected
                    RommDetails.open(platform, game)
                }
            })
    }

    fun tiles(systemId: String, color: Color): List<GridItem> {
        val system = systems[systemId] ?: return emptyList()
        val cards = system.remote.flatMap { platform ->
            pages[platform.id]?.games.orEmpty().filter {
                it.mayMatch(system.platform) && RommDownloads.homeRemote(platform, it, system.platform?.id)
            }.map { game -> tile(system, platform, game, color) }
        }.sortedBy { it.title.lowercase() }
        if (system.remote.all { pages[it.id]?.nextOffset == null && it.id in pages }) return cards
        val title = when (systemId) {
            loadingSystem -> "Loading RoMM games…"
            failedSystem -> "Retry RoMM games"
            else -> "More RoMM games"
        }
        return cards + GridItem("romm-more:$systemId", TileKind.BuiltIn, title, "RoMM", "", color,
            TileArt.Symbol(Glyph.Cartridge, color), emptyList(), Selected.Setting(title, "RoMM", system.title, color, "Load"),
            default = false, open = { if (ready && loadingSystem != systemId) demand = systemId to ((demand?.second ?: 0) + 1) })
    }
}

/** Scroll and transfer focus from the page action once the view has actually gained tiles. */
@Composable
internal fun FollowRommPage(route: String, keys: List<String>) {
    val previous = remember(route) { arrayOf(emptyList<String>()) }
    val next = remember(route, keys) {
        rommPageFocus(previous[0], keys, FocusMemory.get(route))?.also { FocusMemory.remember(route, it) }
    }
    SideEffect { previous[0] = keys }
    LaunchedEffect(route, next) {
        if (next != null && FocusMemory.get(route) == next) Nav.focusTick++
    }
}

internal fun homeGameKeys(games: List<io.github.zahakj.tandem.Game>, remote: List<GridItem>): List<String> =
    games.map { it.key } + remote.map { it.key }

@Composable
internal fun RommHomeTile(item: GridItem, modifier: Modifier, onFocus: () -> Unit = { Selection.current = item.selected },
                          onClick: () -> Unit = item.open) {
    FocusCard(modifier.fillMaxWidth(), glow = item.color, entry = true, onFocus = onFocus, onClick = onClick) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth()) { TileArtView(item.art, 100.dp) }
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.art is TileArt.RommBox)
                Text("Available to download", color = Palette.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
        }
    }
}
