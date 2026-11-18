package io.github.zahakj.tandem.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import io.github.zahakj.tandem.*
import io.github.zahakj.tandem.data.Store
import io.github.zahakj.tandem.data.HomeStore
import io.github.zahakj.tandem.romm.*
import java.time.Instant
import java.time.ZoneOffset

/** Observes disable/forget/private-mode changes even when a browser composition moves between displays. */
@Composable
fun RommBrowserGuard() {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(Unit) { RommSettingsState.start(context) }
    val config = RommSettingsState.config
    val closed = NetGate.closed
    val shown = Nav.route == Route.Tool("romm")
    LaunchedEffect(config, closed, shown) { RommBrowser.environment(config, closed, shown) }
    LaunchedEffect(config, closed) { RommDownloads.environment(context, config) }
    RommHome.Observe(context)
    LaunchedEffect(config, closed, Libraries.all, Store.meta, Privacy.view, Nav.focusTick,
        RommDownloads.mappingVersion, RommDownloads.recovering) {
        if (!closed) RommDownloads.refreshLocalIndex(config)
    }
}

internal object RommDetails {
    data class Entry(val platform: RommPlatformInfo, val game: RommGameInfo, val route: Route,
                     val folder: Route?, val iconFolder: String?, val config: RommConfig)
    private var entry by mutableStateOf<Entry?>(null)
    val current: Entry? get() = entry?.takeIf {
        !NetGate.closed && it.config == RommSettingsState.config &&
            it.route == Nav.route && it.folder == FolderPopup.route && it.iconFolder == IconGrid.openFolder
    }

    fun open(platform: RommPlatformInfo, game: RommGameInfo) {
        val config = RommSettingsState.config
        if (!config.enabled || !config.configured || NetGate.closed) return
        RommDownloads.dismissChoices()
        FocusMemory.forget("romm-detail")
        entry = Entry(platform, game, Nav.route, FolderPopup.route, IconGrid.openFolder, config)
    }

    fun close() {
        entry = null
        RommDownloads.dismissChoices()
    }

    fun matches(platform: RommPlatformInfo, game: RommGameInfo): Boolean =
        current?.let { it.platform.id == platform.id && it.game.id == game.id } == true

    fun discardStale() {
        if (entry != null && current == null) close()
    }
}

private fun askRommSearch() {
    if (RommBrowser.platform != null && !NetGate.closed) TextEntry.ask("Search RoMM games", RommBrowser.query) { RommBrowser.search(it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RommBrowserScreen() {
    val config = RommSettingsState.config
    val privateMode = NetGate.closed
    val platform = RommBrowser.platform
    val page = RommBrowser.page
    val hideEmpty = HomeStore.settings.rommHideEmptyPlatforms
    val shownPlatforms = remember(RommBrowser.platforms, hideEmpty) { visibleRommPlatforms(RommBrowser.platforms, hideEmpty) }
    val app = BuiltInApps.byId("romm") ?: return
    val active = toolActive("romm")
    val downloads = RommDownloads.activeDownloads
    val recoveryWarning = RommDownloads.recoveryWarning
    BackHandler(active && platform != null) { RommBrowser.up() }
    InterceptButtons(active) { button ->
        when (button) {
            Button.X -> { askRommSearch(); true }
            Button.Y -> { if (!privateMode) RommBrowser.refresh(); true }
            else -> false
        }
    }
    val route = "romm:${platform?.id ?: "platforms"}:${RommBrowser.query}:${page?.offset ?: 0}"
    val controls = buildList {
        add("refresh")
        if (platform != null) { add("platforms"); add("search"); if (RommBrowser.canPrevious) add("previous") }
        if (RommBrowser.busy) add("cancel")
    }
    val empty = !RommBrowser.busy && RommBrowser.error == null &&
        (if (platform == null) shownPlatforms.isEmpty() else page?.items?.isEmpty() == true)
    val allHidden = platform == null && hideEmpty && RommBrowser.platforms.isNotEmpty() && shownPlatforms.isEmpty()
    val tiles = if (platform == null) shownPlatforms.map { "platform:${it.id}" } else page?.items.orEmpty().map { "game:${it.id}" }
    val keys = buildList {
        addAll(controls)
        addAll(downloads.filterNot { it.finished || it.cancelling }.map { "download:${it.key.platformId}:${it.key.gameId}" })
        addAll(tiles)
        if (empty) add("empty-action")
        if (page?.nextOffset != null) add("next")
    }
    val target = rememberFocusTarget(route, keys)
    // Control buttons share one full-width grid item. Notices/counts still occupy real item indices.
    val itemKeys = buildList {
        add("actions")
        if (recoveryWarning != null) add("download-recovery")
        addAll(downloads.map { "download:${it.key.platformId}:${it.key.gameId}" })
        if (RommBrowser.error != null) add("error") else if (RommBrowser.cached) add("cached")
        addAll(tiles)
        if (page != null) add("count")
        if (empty) add("empty")
        if (page?.nextOffset != null) add("next")
    }
    val grid = rememberLazyGridState()
    KeepInView(grid, itemKeys.indexOf(when (target) { in controls -> "actions"; "empty-action" -> "empty"; else -> target }))
    ToolScaffold(app, platform?.title ?: "Platforms", hints = {
        ButtonHint("X", "Search", Palette.Sky, onClick = ::askRommSearch)
        ButtonHint("Y", "Refresh", Palette.Mint, onClick = { RommBrowser.refresh() })
        ButtonHint("B", if (platform != null) "Platforms" else "Back", Palette.Lavender)
    }, sideWidth = 145.dp) {
        when {
            RommSettingsState.busy && !config.configured -> PipNote("Loading RoMM settings…", "One moment")
            !config.enabled || !config.configured -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PipNote("Configure RoMM first", "Set the server and token, then enable RoMM")
                BrowserAction("RoMM settings", Modifier) { Nav.go(Route.SettingsPage("library")) }
            }
            privateMode -> PipNote("Private mode is on", "RoMM stays offline. Your local library remains available")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(if (platform == null) 150.dp else 120.dp),
                modifier = Modifier.fillMaxSize().fadingEdges(grid), state = grid,
                contentPadding = PaddingValues(horizontal = FocusRoomSide, vertical = FocusRoom),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item("actions", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            BrowserAction(if (RommBrowser.busy) "Loading…" else "Refresh RoMM", Modifier.width(126.dp).takesFocus(route, "refresh", target == "refresh")) { RommBrowser.refresh() }
                            if (platform != null) {
                                BrowserAction("All platforms", Modifier.width(126.dp).takesFocus(route, "platforms", target == "platforms")) { RommBrowser.up() }
                                BrowserAction("Search games", Modifier.width(126.dp).takesFocus(route, "search", target == "search"), ::askRommSearch)
                                if (RommBrowser.canPrevious) BrowserAction("Previous page", Modifier.width(126.dp).takesFocus(route, "previous", target == "previous")) { RommBrowser.previous() }
                            }
                            if (RommBrowser.busy) BrowserAction("Cancel loading", Modifier.width(126.dp).takesFocus(route, "cancel", target == "cancel")) { RommBrowser.cancel() }
                        }
                        if (RommBrowser.query.isNotEmpty()) Text("Search: ${RommBrowser.query}", color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (recoveryWarning != null) item("download-recovery", span = { GridItemSpan(maxLineSpan) }) {
                    PipNote("Check interrupted downloads", recoveryWarning)
                }
                items(downloads, key = { "download:${it.key.platformId}:${it.key.gameId}" }, span = { GridItemSpan(maxLineSpan) }) { download ->
                    val id = "download:${download.key.platformId}:${download.key.gameId}"
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(download.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(download.note ?: download.text, color = if (download.finished) Palette.Mint else Palette.Sky)
                            RommTransferProgress(download)
                        }
                        if (!download.finished && !download.cancelling)
                            BrowserAction("Cancel", Modifier.width(98.dp).takesFocus(route, id, target == id)) { RommDownloads.cancel(download.key) }
                    }
                }
                RommBrowser.error?.let { message -> item("error", span = { GridItemSpan(maxLineSpan) }) { PipNote(if (RommBrowser.cached) "Showing cached results" else "Could not load RoMM", message) } }
                if (RommBrowser.cached && RommBrowser.error == null) item("cached", span = { GridItemSpan(maxLineSpan) }) { Text("Cached this session · Refresh to update", color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (platform == null) {
                    items(shownPlatforms, key = { "platform:${it.id}" }) { p ->
                        RommPlatformTile(p, Modifier.takesFocus(route, "platform:${p.id}", target == "platform:${p.id}"))
                    }
                } else {
                    items(page?.items.orEmpty(), key = { "game:${it.id}" }) { game ->
                        RommGameTile(platform, game, Modifier.takesFocus(route, "game:${game.id}", target == "game:${game.id}"))
                    }
                }
                if (page != null) item("count", span = { GridItemSpan(maxLineSpan) }) {
                    Text(if (page.items.isEmpty()) "0 games on this page" else "${page.offset + 1L}–${page.offset + page.items.size.toLong()} of ${page.total ?: "unknown"} games", color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (empty) item("empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyNote(
                        if (allHidden) "No platforms with games" else if (platform == null) "No platforms returned" else "No games found",
                        if (allHidden) "Show all platforms" else if (platform == null) "Look again" else "Search games",
                        Modifier.takesFocus(route, "empty-action", target == "empty-action"),
                    ) {
                        if (allHidden) HomeStore.updateSettings { it.copy(rommHideEmptyPlatforms = false) }
                        else if (platform == null) RommBrowser.refresh() else askRommSearch()
                    }
                }
                if (page?.nextOffset != null) item("next", span = { GridItemSpan(maxLineSpan) }) { BrowserAction("Next page", Modifier.takesFocus(route, "next", target == "next")) { RommBrowser.next() } }
            }
        }
    }
}

@Composable
internal fun RommDetailsHost() {
    val current = RommDetails.current
    val shown = rememberLatest(current)
    LaunchedEffect(Nav.route, FolderPopup.route, IconGrid.openFolder, RommSettingsState.config, NetGate.closed) { RommDetails.discardStale() }
    PanelGuard(current != null)
    OverlayPanel(current != null, onClose = RommDetails::close) {
        val entry = shown ?: return@OverlayPanel
        if (NetGate.closed || entry.config != RommSettingsState.config) return@OverlayPanel
        val game = entry.game
        val system = entry.platform
        val installed = RommDownloads.availability(system, game).installed
        val detailTarget = rememberFocusTarget("romm-detail", if (installed) listOf("back") else listOf("download-to", "download", "folders", "back"))
        Column(Modifier.width(600.dp).background(Palette.Card, RoundedCornerShape(22.dp)).padding(16.dp)) {
            RommRemoteDetails(system, game, Modifier.weight(1f, fill = false), showAvailabilityPill = !installed)
            if (installed) RommDownloadComplete(system, game) else RommDownloadControls(system, game, detailTarget, Modifier.weight(1f, fill = false))
            BrowserAction("Back to games", Modifier.takesFocus("romm-detail", "back", detailTarget == "back"), RommDetails::close)
        }
    }
    RommDestinationPicker()
}

@Composable
private fun BrowserAction(title: String, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(modifier.fillMaxWidth().heightIn(min = 46.dp), glow = Palette.Mint, onClick = onClick) {
        Text(title, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RommPlatformTile(platform: RommPlatformInfo, modifier: Modifier) {
    FocusCard(
        modifier.fillMaxWidth().height(140.dp), glow = Palette.Sky, shape = RoundedCornerShape(16.dp), entry = true,
        onFocus = { Selection.current = Selected.Setting(platform.title, "RoMM platform", "${platform.romCount} games available from your server", Palette.Sky, "Browse") },
        onClick = { RommBrowser.open(platform) },
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GlyphIcon(Glyph.Cartridge, Palette.Sky, Modifier.size(28.dp))
            Text(platform.title, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${platform.romCount} games", style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RommGameTile(platform: RommPlatformInfo, game: RommGameInfo, modifier: Modifier) {
    val availability = RommDownloads.availability(platform, game)
    val download = RommDownloads.status(platform, game)?.takeIf { it.active }
    val color = if (availability.installed) Palette.Mint else Palette.Sky
    FocusCard(
        modifier.fillMaxWidth(), glow = color, shape = RoundedCornerShape(16.dp), entry = true,
        onFocus = { RommBrowser.select(game) }, onClick = { RommBrowser.select(game); RommDetails.open(platform, game) },
    ) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RommCover(game, Modifier.fillMaxWidth().aspectRatio(3f / 4f))
            Text(game.title, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (availability.installed) availability.label else download?.text ?: availability.label,
                Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall, color = color, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun RommCover(game: RommGameInfo, modifier: Modifier, large: Boolean = false) {
    val context = LocalContext.current
    val revision = RommBrowser.generation
    val config = RommSettingsState.config
    val route = Nav.route
    val closed = NetGate.closed
    val session = remember(game, config, closed) { Any() }
    val result by produceState<Pair<Any, ByteArray>?>(null, session, revision, route) {
        value = null
        if (!closed) value = RommBrowser.cover(game, large)?.let { session to it }
    }
    val bytes = result?.takeIf { it.first === session }?.second
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.NightDeep), contentAlignment = Alignment.Center) {
        val request = remember(bytes, context) {
            bytes?.let { ImageRequest.Builder(context).data(it).size(if (large) 480 else 192, if (large) 640 else 256)
                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build() }
        }
        GlyphIcon(Glyph.Cartridge, Palette.Sky, Modifier.size(32.dp))
        if (request != null && !closed && config.enabled) AsyncImage(request, "RoMM cover", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/** Shared by the game's popup and Tandem's companion display, with no local-game launch/delete actions. */
@Composable
fun RommRemoteDetails(platform: RommPlatformInfo, game: RommGameInfo, modifier: Modifier = Modifier, showAvailabilityPill: Boolean = true) {
    val scroll = rememberScrollState()
    LaunchedEffect(platform.id, game.id) { scroll.scrollTo(0) }
    val availability = RommDownloads.availability(platform, game)
    Column(modifier.fillMaxWidth().fadingEdges(scroll).verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(game.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(platform.title, color = Palette.Sky, maxLines = 2, overflow = TextOverflow.Ellipsis)
        RommCover(game, Modifier.fillMaxWidth().height(180.dp), large = true)
        if (showAvailabilityPill) Pill(availability.label, if (availability.installed) Palette.Mint else Palette.Sky)
        Text(availability.explanation, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        Text(game.fileName, style = MaterialTheme.typography.bodyMedium)
        Text(rommSizeLabel(game.size), color = Palette.Muted)
        game.metadatum?.let { metadata ->
            if (metadata.genres.isNotEmpty()) Text("Genres: ${metadata.genres.joinToString()}")
            if (metadata.developers.isNotEmpty()) Text("Developer: ${metadata.developers.joinToString()}")
            if (metadata.publishers.isNotEmpty()) Text("Publisher: ${metadata.publishers.joinToString()}")
            if (metadata.players.isNotBlank()) Text("Players: ${metadata.players}")
            metadata.released?.let { timestamp ->
                val date = runCatching { Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate().toString() }.getOrNull()
                if (date != null) Text("Released: $date")
            }
        }
        Text(game.summary?.takeIf { it.isNotBlank() } ?: "No description available", style = MaterialTheme.typography.bodyMedium)
    }
}
