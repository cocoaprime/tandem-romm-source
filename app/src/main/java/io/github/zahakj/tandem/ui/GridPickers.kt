package io.github.zahakj.tandem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zahakj.tandem.Button
import io.github.zahakj.tandem.Collections
import io.github.zahakj.tandem.Game
import io.github.zahakj.tandem.Libraries
import io.github.zahakj.tandem.Privacy
import io.github.zahakj.tandem.Selected
import io.github.zahakj.tandem.Selection
import io.github.zahakj.tandem.data.IconGridStore
import io.github.zahakj.tandem.data.Store
import io.github.zahakj.tandem.plural
import io.github.zahakj.tandem.romm.RommShortcut
import io.github.zahakj.tandem.romm.gamePickerSystems

/*
 * Add here › Game…: choose a populated platform and pick games from its local and RoMM libraries.
 * Start or Add puts the picks in the free spots in the order they were picked.
 */

private const val PICK_ROUTE = "gridpick"

/** A game's key on the grid. */
private fun gameKey(g: Game) = "game:${g.key}"

private data class GamePick(val key: String, val title: String, val local: Game? = null, val cloud: GridItem? = null)

@Composable
internal fun GamePickPanel() {
    val ctx = LocalContext.current
    val target = IconGrid.pickingGames
    val visible = target != null
    val shownTarget = rememberLatest(target)
    val system = SystemFolders.systemOf(target?.folder)
    val systems = RommHome.systems
    val libraries = Libraries.all
    val metadata = Store.meta
    val privacy = Privacy.view
    val localBySystem = remember(libraries, metadata, privacy) { Collections.visibleGames().groupBy { it.platform.id } }
    val localNames = remember(localBySystem) { localBySystem.mapValues { it.value.first().platform.name } }
    val platforms = remember(localNames, systems, system) { gamePickerSystems(localNames, systems, system) }
    var selectedPlatform by remember(target) { mutableStateOf<String?>(null) }
    val active = selectedPlatform?.takeIf { id -> platforms.any { it.first == id } }
    val remoteVersion = RommHome.contentVersion
    val remote = remember(active, remoteVersion, systems) { active?.let { RommHome.tiles(it, SystemColors.of(it)) }.orEmpty() }
    RommHome.Load(active.takeIf { visible })
    PanelGuard(visible)
    var query by remember(target, active) { mutableStateOf("") }
    val picked = remember(target) { mutableStateListOf<String>() }
    val pickedRemote = remember(target) { mutableMapOf<String, GridItem>() }
    val validLocal = remember(localBySystem) { localBySystem.values.flatten().mapTo(HashSet(), ::gameKey) }
    // With no games at all, the first button picks the folder they are in instead.
    val pickFolder = rememberGameFolderPicker()
    val close: () -> Unit = { IconGrid.pickingGames = null; Nav.focusTick++ }
    val search: () -> Unit = {
        TextEntry.ask(if (active == null) "Find a platform" else "Find a game", query, onChange = { query = it }) { query = it }
    }
    val place: () -> Unit = place@{
        val t = shownTarget ?: return@place
        if (picked.isEmpty()) { Toasts.show("Pick a game with A first"); Sounds.error(); return@place }
        val keys = picked.filter { key ->
            if (RommShortcut.parse(key) == null) key in validLocal
            else pickedRemote[key]?.let(RommHome::canPick) == true
        }
        if (keys.isEmpty()) { Toasts.show("Pick an available game first"); Sounds.error(); return@place }
        val first = if (t.folder == null) {
            IconGridStore.addAllAt(keys, homeShown(), t.index)
        } else {
            val s = IconGrid.folderShown?.takeIf { IconGrid.openFolder == t.folder } ?: return@place
            Libraries.games.firstOrNull { gameKey(it) == keys.first() }?.let { g -> IconGrid.menuData?.first?.item(gameKey(g)) }?.let { suggestForFirst(ctx, t.folder, it) }
            IconGridStore.addAllToFolder(keys, t.folder, s, t.index)
        }
        if (first == null) return@place
        keys.mapNotNull(pickedRemote::get).forEach(RommHome::rememberShortcut)
        FocusMemory.remember(if (t.folder == null) Route.Home.key else IconGrid.folderRoute(t.folder), first)
        Sounds.confirm()
        Toasts.show(if (keys.size == 1) "Added a game here" else "Added ${keys.size} games here")
        close()
    }
    val back: () -> Unit = {
        if (active != null) {
            FocusMemory.remember(PICK_ROUTE, "platform:$active")
            selectedPlatform = null
            Nav.focusTick++
        } else close()
    }
    InterceptButtons(visible && TextEntry.request == null) { b ->
        when (b) {
            Button.X -> search()
            Button.Start -> place()
            else -> Unit
        }
        true
    }
    OverlayPanel(visible, onClose = back) {
        if (shownTarget == null) return@OverlayPanel
        val config = IconGridStore.config
        // Games already where they would go (on the home grid, or in this folder) show faded; they can't go in twice.
        // A folder holds shortcuts, so a game on the home grid or in another folder can still be added to it.
        val into = shownTarget.folder
        val taken = remember(config, into) {
            (if (into == null) config.added else IconGridStore.folder(into)?.items.orEmpty()).toHashSet()
        }
        val q = normalize(query)
        val choices = remember(platforms, q) { platforms.filter { (_, name) -> q.isEmpty() || normalize(name).contains(q) } }
        val games = remember(localBySystem, active, remote, q) {
            (localBySystem[active].orEmpty().map { GamePick(gameKey(it), it.title, local = it) } +
                remote.filter { it.selected is Selected.RommGame }.map { GamePick(it.key, it.title, cloud = it) })
                .filter { q.isEmpty() || normalize(it.title).contains(q) }.sortedBy { it.title.lowercase() }
        }
        val more = remote.firstOrNull { it.key.startsWith("romm-more:") }
        val keys = if (active == null) listOf("add") + choices.map { "platform:${it.first}" }
            else listOfNotNull("add", "platforms", more?.key) + games.map(GamePick::key)
        val focusTarget = rememberFocusTarget(PICK_ROUTE, keys)
        val grid = key(active) { rememberLazyGridState() }
        KeepInView(grid, keys.indexOf(focusTarget).let { index ->
            if (index < 0 || active == null || index == 0) index
            else (index - 1).coerceAtLeast(0)
        })
        FolderSheet(
            active?.let { id -> platforms.firstOrNull { it.first == id }?.second } ?: "Add games here",
            if (picked.isEmpty()) (if (active == null) plural(choices.size, "platform") else plural(games.size, "game")) + if (q.isEmpty()) "" else " match \"${query.trim()}\""
            else plural(picked.size, "game") + " picked",
            Palette.Mint, onBack = back, art = TileArt.Symbol(Glyph.Cartridge, Palette.Mint),
            actions = {
                ButtonHint("A", if (active == null) "Open" else "Pick", Palette.Mint); Spacer(Modifier.width(10.dp))
                ButtonHint("X", "Search", Palette.Sky); Spacer(Modifier.width(10.dp))
                ButtonHint("Start", "Add", Palette.Butter)
            },
        ) {
            PaddedScrolling {
                LazyVerticalGrid(
                    GridCells.Adaptive(88.dp),
                    Modifier.fillMaxSize().fadingEdges(grid),
                    state = grid,
                    contentPadding = PaddingValues(horizontal = FocusRoomSide + 4.dp, vertical = FocusRoom),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item("add", span = { GridItemSpan(maxLineSpan) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val none = platforms.isEmpty()
                            PillButton(
                                when {
                                    none -> "Pick your games folder"
                                    picked.isEmpty() -> "Add here"
                                    else -> "Add ${plural(picked.size, "game")} here"
                                },
                                Palette.Mint, Modifier.takesFocus(PICK_ROUTE, "add", focusTarget == "add"),
                            ) { if (none) pickFolder() else place() }
                            PillButton(if (q.isEmpty()) "Search…" else "Search: ${query.trim()}", Palette.Sky, Modifier) { search() }
                            if (q.isNotEmpty()) PillButton("Show all", Palette.Lavender, Modifier) { query = "" }
                            if (active != null) PillButton("All platforms", Palette.Lavender,
                                Modifier.takesFocus(PICK_ROUTE, "platforms", focusTarget == "platforms"), onClick = back)
                        }
                    }
                    if (more != null) item(more.key, span = { GridItemSpan(maxLineSpan) }) {
                        PillButton(more.title, Palette.Sky, Modifier.takesFocus(PICK_ROUTE, more.key, focusTarget == more.key), onClick = more.open)
                    }
                    if (active == null && choices.isEmpty() || active != null && games.isEmpty() && more == null) item("none", span = { GridItemSpan(maxLineSpan) }) {
                        PipNote(if (q.isEmpty()) "No games to pick" else "No matches",
                            if (q.isEmpty()) RommHome.connectionStatus?.takeIf { platforms.isEmpty() } ?: "Add a games folder or choose another platform."
                            else "Accents and punctuation don't matter.", mood = 2)
                    }
                    if (active == null) {
                        items(choices, key = { "platform:${it.first}" }, span = { GridItemSpan(maxLineSpan) }) { (id, name) ->
                            val key = "platform:$id"
                            FocusCard(Modifier.fillMaxWidth().takesFocus(PICK_ROUTE, key, focusTarget == key), glow = Palette.Sky,
                                onClick = {
                                    FocusMemory.remember(PICK_ROUTE, "add")
                                    selectedPlatform = id
                                    Nav.focusTick++
                                }) {
                                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f))
                                    if (id !in localBySystem) Text("RoMM", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    } else {
                        items(games, key = { it.key }, contentType = { if (it.local == null) "romm" else "game" }) { item ->
                            val k = item.key
                            val on = k in picked
                            val already = k in taken
                            Box(Modifier.aspectRatio(0.8f).alpha(if (already) 0.35f else 1f)) {
                                val focus = Modifier.fillMaxSize().takesFocus(PICK_ROUTE, k, focusTarget == k)
                                val choose: () -> Unit = {
                                    when {
                                        already -> Toasts.show("${item.title} is already on the grid")
                                        on -> { picked.remove(k); pickedRemote.remove(k) }
                                        else -> { picked.add(k); item.cloud?.let { pickedRemote[k] = it } }
                                    }
                                }
                                if (item.local != null) GameCard(item.local, focus,
                                    onFocus = { Selection.current = Selected.GameItem(item.local) },
                                    onClick = choose, showTitle = true)
                                else item.cloud?.let { RommHomeTile(it, focus, onClick = choose) }
                                if (on) PickMark(picked.indexOf(k) + 1, Modifier.align(Alignment.TopEnd).padding(6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The number of a picked game, in the order they go on the grid. */
@Composable
private fun PickMark(n: Int, modifier: Modifier) {
    Box(
        modifier.size(26.dp).graphicsLayer { shadowElevation = 6f * density; shape = CircleShape; clip = true }.background(Palette.Mint),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", style = MaterialTheme.typography.labelLarge, color = Palette.NightDeep, maxLines = 1)
    }
}
