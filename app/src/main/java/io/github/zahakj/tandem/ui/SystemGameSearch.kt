package io.github.zahakj.tandem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zahakj.tandem.Collections
import io.github.zahakj.tandem.Launcher
import io.github.zahakj.tandem.Libraries
import io.github.zahakj.tandem.Privacy
import io.github.zahakj.tandem.Selected
import io.github.zahakj.tandem.Selection
import io.github.zahakj.tandem.data.Store
import io.github.zahakj.tandem.romm.RommHomeSearchResults

internal object SystemGameSearch {
    data class Request(val system: String, val query: String, val route: Route, val popup: Route?, val folder: String?)

    private var request by mutableStateOf<Request?>(null)
    val current: Request? get() = request?.takeIf(::onSameSystem)

    private fun onSameSystem(request: Request): Boolean =
        request.route == Nav.route && request.popup == FolderPopup.route && request.folder == IconGrid.openFolder &&
            Privacy.canShowFolder(request.system, null)

    fun ask(system: String, query: String = "") {
        val location = Request(system, query, Nav.route, FolderPopup.route, IconGrid.openFolder)
        val title = Libraries.byId(system)?.platform?.name ?: RommHome.title(system) ?: "this system"
        TextEntry.ask("Search $title", query) { text ->
            val next = text.trim().take(200)
            if (next.isEmpty()) close()
            else if (onSameSystem(location)) {
                if (current?.query != next) FocusMemory.forget("system-search")
                request = location.copy(query = next)
            }
        }
    }

    fun close() {
        request = null
        Nav.focusTick++
    }

    fun discardStale() {
        if (request != null && current == null) close()
    }
}

@Composable
internal fun SystemSearchButton(system: String, modifier: Modifier = Modifier) {
    FocusCard(modifier.height(36.dp), glow = Palette.Sky, shape = RoundedCornerShape(50), onClick = { SystemGameSearch.ask(system) }) {
        Row(Modifier.padding(horizontal = 10.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Search, Palette.Text, Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
            Text("Search games", style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
internal fun SystemGameSearchHost() {
    val active = SystemGameSearch.current
    val shown = rememberLatest(active)
    LaunchedEffect(Nav.route, FolderPopup.route, IconGrid.openFolder, Privacy.view) { SystemGameSearch.discardStale() }
    PanelGuard(active != null)
    OverlayPanel(active != null, onClose = SystemGameSearch::close) {
        val request = shown ?: return@OverlayPanel
        val ctx = LocalContext.current
        val source = RommHome.searchSource(request.system)
        val platformIds = source?.system?.remote?.map { it.id }.orEmpty()
        var results by remember(request, source) { mutableStateOf(RommHomeSearchResults()) }
        var demand by remember(request, source) { mutableIntStateOf(0) }
        var loading by remember(request, source) { mutableStateOf(source != null) }
        LaunchedEffect(active, source, demand) {
            if (active != request || source == null) return@LaunchedEffect
            loading = true
            try {
                results = results.next(platformIds, request.query) { id, query, offset ->
                    RommHome.searchPage(source, id, query, offset)
                }
            } finally { loading = false }
        }

        val lib = Libraries.byId(request.system)
        val title = lib?.platform?.name ?: source?.system?.title ?: "Search games"
        val sort = Store.settings.sort
        val local = remember(lib, request.query, Store.meta, Privacy.view, sort) {
            val query = normalize(request.query)
            if (query.isEmpty()) emptyList() else io.github.zahakj.tandem.Library.sorted(
                Collections.visible(lib?.games.orEmpty()).filter {
                    normalize(it.title).contains(query) || normalize(it.libraryName).contains(query)
                }, sort,
            )
        }
        val cloud = remember(source, results, RommDownloads.availabilityVersion) {
            source?.let { RommHome.searchTiles(it, results, SystemColors.of(request.system)) }.orEmpty()
        }
        val retryConnection = RommHome.searchConnectionFailed
        val connectionStatus = RommHome.searchConnectionStatus
        val more = source != null && results.hasMore(platformIds)
        val moreLabel = when {
            loading -> "Searching RoMM…"
            retryConnection || results.failed.isNotEmpty() -> "Retry RoMM search"
            else -> "More matching games"
        }
        val showMore = more || retryConnection
        val resultKeys = homeGameKeys(local, cloud) + if (showMore) listOf("more") else emptyList()
        val route = "system-search"
        val target = rememberFocusTarget(route, resultKeys + listOf("edit", "close"))
        val grid = key(request) { rememberLazyGridState() }
        KeepInView(grid, resultKeys.indexOf(target))

        Column(
            Modifier.fillMaxSize().background(Palette.Card, RoundedCornerShape(26.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = FocusRoomSide), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(request.query, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PillButton("Edit search", Palette.Sky, Modifier.takesFocus(route, "edit", target == "edit")) {
                    SystemGameSearch.ask(request.system, request.query)
                }
                PillButton("Close", Palette.Lavender, Modifier.takesFocus(route, "close", target == "close"), onClick = SystemGameSearch::close)
            }
            val note = when {
                results.failed.isNotEmpty() -> "Some RoMM results could not be loaded"
                connectionStatus != null -> connectionStatus
                source != null && !RommDownloads.homeIndexReady -> "Checking local games…"
                local.isEmpty() && cloud.isEmpty() && !showMore -> "No matching games"
                else -> null
            }
            if (note != null) Text(note, Modifier.padding(horizontal = FocusRoomSide), color = Palette.Muted)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(116.dp), state = grid, modifier = Modifier.fillMaxWidth().weight(1f).fadingEdges(grid),
                contentPadding = PaddingValues(horizontal = FocusRoomSide, vertical = FocusRoom),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(local, key = { it.key }) { game ->
                    GameCard(game, Modifier.aspectRatio(0.8f).takesFocus(route, game.key, target == game.key),
                        onFocus = { Selection.current = Selected.GameItem(game) },
                        onClick = { SystemGameSearch.close(); Launcher.launch(ctx, game) }, showTitle = true)
                }
                items(cloud, key = { it.key }) { item ->
                    RommHomeTile(item, Modifier.aspectRatio(0.8f).takesFocus(route, item.key, target == item.key))
                }
                if (showMore) item("more", span = { GridItemSpan(maxLineSpan) }) {
                    PillButton(moreLabel, Palette.Sky, Modifier.takesFocus(route, "more", target == "more")) {
                        if (retryConnection) RommHome.retryConnectionIfFailed() else if (!loading) demand++
                    }
                }
            }
        }
    }
}
