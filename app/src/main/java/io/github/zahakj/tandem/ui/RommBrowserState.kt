package io.github.zahakj.tandem.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zahakj.tandem.NetGate
import io.github.zahakj.tandem.Selected
import io.github.zahakj.tandem.Selection
import io.github.zahakj.tandem.romm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Shared by both library displays. Screen transfers do not discard browsing state or duplicate requests. */
internal object RommBrowser {
    private val scope = MainScope()
    private var config = RommConfig()
    private var privateMode = false
    private var active = false
    private var request: Job? = null
    private var serial = 0
    private var client: RommLibraryClient? = null
    private val cache = RommPageCache()
    private val artwork = RommArtworkCache()
    private var artworkConfig: RommConfig? = null
    private val imageSlots = Semaphore(3)
    private var offsets = listOf(0)

    var generation by mutableIntStateOf(0)
        private set
    var platforms by mutableStateOf<List<RommPlatformInfo>>(emptyList())
        private set
    var platform by mutableStateOf<RommPlatformInfo?>(null)
        private set
    var page by mutableStateOf<RommGamePage?>(null)
        private set
    var query by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var cached by mutableStateOf(false)
        private set
    var local by mutableStateOf(RommLocalIndex(emptyList()))
    val canPrevious: Boolean get() = offsets.size > 1

    fun environment(next: RommConfig, closed: Boolean, shown: Boolean) {
        if (config != next || privateMode != closed) {
            cancel()
            config = next
            privateMode = closed
            generation++
            platforms = emptyList(); platform = null; page = null; query = ""
            cache.clear(); artwork.clear(); client = null; offsets = listOf(0); cached = false; error = null
            if (Selection.current is Selected.RommGame) Selection.current = Selected.Home
        }
        if (active && !shown) { cancel(); generation++ }
        active = shown
        if (shown && ready() && platforms.isEmpty() && !busy && error == null) loadPlatforms()
    }

    private fun ready() = active && config.enabled && config.configured && !privateMode && !NetGate.closed

    fun shows(selection: Selected.RommGame) = RommHome.shows(selection) ||
        (ready() && Nav.route == Route.Tool("romm") && config == RommSettingsState.config &&
            platform == selection.platform && page?.items?.contains(selection.game) == true)

    private fun api(): RommLibraryClient = client ?: RommLibraryClient(config, RommHttpTransport(NetGate::check), NetGate::check).also { client = it }

    fun cancel() { serial++; request?.cancel(); request = null; busy = false }

    private fun <T> load(fetch: () -> T, publish: (T) -> Unit) {
        if (!ready()) return
        cancel()
        val attempt = serial
        busy = true; error = null
        request = scope.launch {
            try {
                val result = runInterruptible(Dispatchers.IO) { fetch() }
                if (attempt == serial && ready()) { publish(result); cached = false }
            } catch (e: CancellationException) { throw e
            } catch (e: RommBrowseFailure) {
                if (attempt == serial) { error = e.problem.message; cached = page != null || platforms.isNotEmpty() }
            } catch (_: Exception) {
                if (attempt == serial) { error = "Could not load RoMM. Try again"; cached = page != null || platforms.isNotEmpty() }
            } finally { if (attempt == serial) { busy = false; request = null } }
        }
    }

    fun loadPlatforms() {
        val api = api()
        load(api::platforms) { platforms = it }
    }

    fun open(selected: RommPlatformInfo) {
        platform = selected; query = ""; offsets = listOf(0); RommDetails.close()
        loadPage()
    }

    private fun loadPage(refresh: Boolean = false) {
        val selected = platform ?: return
        val key = RommPageCache.Key(selected.id, query, offsets.last())
        RommDetails.close()
        page = cache.get(key)
        cached = page != null
        if (page != null && !refresh) { cancel(); error = null; return }
        val api = api()
        load({ api.games(key.platform, key.search, key.offset) }) { loaded -> page = loaded; cache.put(key, loaded) }
    }

    fun refresh() {
        client = null
        artwork.clear(); generation++
        if (platform == null) loadPlatforms() else loadPage(refresh = true)
    }

    fun search(text: String) {
        if (text.length > 200) { Toasts.show("Use up to 200 characters"); return }
        query = text.trim(); offsets = listOf(0); loadPage()
    }

    fun next() { page?.nextOffset?.let { offsets = offsets + it; loadPage() } }
    fun previous() { if (canPrevious) { offsets = offsets.dropLast(1); loadPage() } }
    fun up() {
        cancel(); platform = null; page = null; RommDetails.close(); query = ""; offsets = listOf(0); error = null; cached = false
        Selection.current = Selected.Home
    }

    fun select(game: RommGameInfo) {
        val selected = platform ?: return
        val revision = generation
        Selection.follow {
            if (ready() && revision == generation && platform == selected && page?.items?.contains(game) == true)
                Selection.current = Selected.RommGame(selected, game)
        }
    }

    suspend fun cover(game: RommGameInfo, large: Boolean = false): ByteArray? {
        val snapshot = RommSettingsState.config
        fun allowed() = snapshot.enabled && snapshot.configured && snapshot == RommSettingsState.config && !NetGate.closed
        if (!allowed()) return null
        if (artworkConfig != snapshot) {
            artwork.clear()
            artworkConfig = snapshot
        }
        val revision = generation
        val url = RommArtworkAddress.resolve(snapshot.server, game, large) ?: return null
        val key = "$url|${if (large) game.coverLarge else game.coverSmall}"
        artwork.get(key)?.let { return it }
        return imageSlots.withPermit {
            if (!allowed() || revision != generation) return@withPermit null
            try {
                val response = runInterruptible(Dispatchers.IO) {
                    RommHttpTransport(NetGate::check).bytes(url, snapshot.token, RommArtworkAddress.MAX_BYTES)
                }
                if (response.status == 200 && allowed() && revision == generation) {
                    response.bytes.also { artwork.put(key, it) }
                } else null
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { null }
        }
    }
}
