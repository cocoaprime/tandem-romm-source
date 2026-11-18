package io.github.zahakj.tandem.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zahakj.tandem.*
import io.github.zahakj.tandem.romm.*
import io.github.zahakj.tandem.data.Media
import io.github.zahakj.tandem.data.MediaKind
import io.github.zahakj.tandem.data.ScanCache
import io.github.zahakj.tandem.data.Store
import io.github.zahakj.tandem.data.SystemPrefs
import kotlinx.coroutines.*
import java.io.File
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/** Main-thread coordinator shared by both displays and the foreground service. */
internal object RommDownloads {
    private const val PARALLELISM = 2
    private const val WAITING_LIMIT = 12
    private const val STATUS_LIMIT = 48
    private val scope = MainScope()
    private var initialized = false
    private lateinit var context: Context
    private lateinit var store: RommDownloadStore
    private var config = RommConfig()
    private var serverKey = ""
    private var wasClosed = false
    private var service: RommDownloadService? = null
    private var startingService = false
    private val queue = RommDownloadQueue<RommInstallOrigin, Task>(PARALLELISM, WAITING_LIMIT)
    private val states = mutableStateMapOf<RommInstallOrigin, Status>()
    private var pending: Request? = null
    private var session = 0
    private data class Publication(val server: String, val origin: RommInstallOrigin, val local: Game, val remoteName: String, val size: Long)
    private var publications by mutableStateOf<Map<RommInstallOrigin, Publication>>(emptyMap())
    private data class Request(val platform: RommPlatformInfo, val game: RommGameInfo, val layout: RommDownloadLayout,
                               val config: RommConfig)

    private class Task(
        val key: RommInstallOrigin, val platform: RommPlatformInfo, val game: RommGameInfo,
        val config: RommConfig, val revision: Int, val chosen: Pair<Request, RommDestination>?,
    ) {
        val control = RommTransferControl {
            NetGate.check()
            if (config != RommSettingsState.config) throw RommBrowseFailure(RommProblem.Disabled)
        }
        var job: Job? = null
        var cancelMessage = RommDownloadProblem.Cancelled.message
    }

    data class Status(
        val key: RommInstallOrigin, val title: String, val text: String = "Queued",
        val active: Boolean = true, val cancelling: Boolean = false, val finished: Boolean = false,
        val bytes: Long = 0, val total: Long = 0, val folder: String? = null, val note: String? = null,
    )
    data class FolderPrompt(val platform: RommPlatformInfo, val game: RommGameInfo,
                            val plan: RommDestinationPlan, val revision: Int)
    data class FolderPick(val prompt: FolderPrompt, val platform: Platform)
    private var activeFolderPick: FolderPick? = null

    var recovering by mutableStateOf(true)
        private set
    var recoveryWarning by mutableStateOf<String?>(null)
        private set
    var folderPrompt by mutableStateOf<FolderPrompt?>(null)
        private set
    var pickingFolder by mutableStateOf(false)
        private set
    var folderLoading by mutableStateOf(false)
        private set
    var mappingVersion by mutableStateOf(0)
        private set
    var mappedSystems by mutableStateOf<Map<Long, String>>(emptyMap())
        private set
    private var homeIndex by mutableStateOf<Pair<RommConfig, RommLocalIndex>?>(null)
    val homeIndexReady: Boolean get() = homeIndex?.first == RommSettingsState.config && !NetGate.closed
    val availabilityVersion: Any get() = listOf(homeIndex, publications)
    val activeDownloads: List<Status> get() = states.values.filter { it.active }

    fun environment(ctx: Context, next: RommConfig) {
        if (!initialized) {
            initialized = true; context = ctx.applicationContext
            store = RommDownloadStore(File(context.noBackupFilesDir, "romm-downloads"))
            scope.launch {
                val recovered = withContext(Dispatchers.IO) { runCatching { store.recover() }.getOrDefault(false) }
                recovering = false
                if (!recovered) recoveryWarning = RommDownloadProblem.Recovery.message
            }
        }
        val closed = NetGate.closed
        if (config != next || wasClosed != closed) {
            session++; dismissChoices()
            publications = emptyMap()
            homeIndex = null
            mappedSystems = emptyMap()
            folderLoading = false
            states.clear()
            cancelAll()
            config = next
            serverKey = if (next.configured) RommDownloadNames.serverKey(next.server) else ""
            wasClosed = closed
        }
    }

    private fun key(platform: RommPlatformInfo, game: RommGameInfo) = RommInstallOrigin(serverKey, platform.id, game.id)

    fun status(platform: RommPlatformInfo, game: RommGameInfo): Status? = states[key(platform, game)]

    private fun update(task: Task, change: (Status) -> Status) {
        if (task.revision != session || queue[task.key] !== task) return
        states[task.key]?.let { states[task.key] = change(it) }
    }

    fun availability(platform: RommPlatformInfo, game: RommGameInfo): RommAvailability {
        val recent = publications[key(platform, game)]
        if (recent != null && recent.server == RommSettingsState.config.server &&
            recent.origin.platformId == platform.id && game.platformId == platform.id && recent.origin.gameId == game.id &&
            recent.remoteName == game.fileName && recent.size == game.size && !Privacy.isHidden(recent.local))
            return RommAvailability.Downloaded
        return RommBrowser.local.match(platform, game)
    }

    fun homeRemote(platform: RommPlatformInfo, game: RommGameInfo, localSystem: String?): Boolean =
        !NetGate.closed && homeIndex?.takeIf { it.first == RommSettingsState.config }?.second?.let {
            game.availableOnHome(availability(platform, game), it.match(platform, game, localSystem))
        } == true

    suspend fun refreshLocalIndex(snapshot: RommConfig) {
        if (!initialized || recovering || !snapshot.enabled || !snapshot.configured) return
        val recent = publications
        val games = Libraries.games.map { it to !Privacy.isHidden(it) }
        val (index, allIndex, discovered) = withContext(Dispatchers.IO) {
            val serverKey = RommDownloadNames.serverKey(snapshot.server)
            val mapped = runCatching { store.mappedSystems(serverKey) }.getOrDefault(emptyMap())
            val local = games.mapNotNull { (game, visible) -> runCatching { RommLocalFile.read(game)?.let { it to visible } }.getOrNull() }
            val files = local.filter { it.second }.map { it.first }
            val clear = recent.filterValues { publication ->
                local.any { (file, _) -> file.origin == publication.origin && file.fileName == publication.remoteName && file.size == publication.size } ||
                    runCatching { RommLocalFile.read(publication.local)?.size != publication.size }.getOrDefault(true)
            }
            Triple(RommLocalIndex(files, serverKey, mapped), mapped to RommLocalIndex(local.map { it.first }, serverKey, mapped), clear)
        }
        if (snapshot != RommSettingsState.config || NetGate.closed) return
        RommBrowser.local = index
        mappedSystems = allIndex.first
        homeIndex = snapshot to allIndex.second
        publications = publications.filter { (key, value) -> discovered[key] !== value }
    }

    fun dismissChoices() { folderPrompt = null; pending = null; pickingFolder = false; activeFolderPick = null }

    private fun choiceKey(server: String, platform: RommPlatformInfo) = "${RommDownloadNames.serverKey(server)}:${platform.id}"

    private fun plan(platform: RommPlatformInfo, game: RommGameInfo, snapshot: RommConfig,
                     layout: RommDownloadLayout? = null): RommDestinationPlan {
        val all = Platforms.all(context)
        val filename = game.fileName.takeIf { game.simpleFile }
        return RommDestinations.plan(platform, filename, all.filter { SystemPrefs.config(it.id).enabled },
            Libraries.all.orEmpty(), Library.roots(), store.choice(choiceKey(snapshot.server, platform)), all) { local ->
            if (layout != null) layout.launchFor(local) != null else filename?.let(local::matches) != false
        }
    }

    /** No network request: safe to show before the user presses Download. */
    suspend fun preview(platform: RommPlatformInfo, game: RommGameInfo): RommDestinationPlan? {
        if (!initialized || recovering) return null
        val snapshot = RommSettingsState.config
        return runInterruptible(Dispatchers.IO) { plan(platform, game, snapshot) }
    }

    fun selectFolder(platform: RommPlatformInfo, game: RommGameInfo) {
        if (status(platform, game)?.active == true || pickingFolder || folderLoading) {
            Toasts.show("Finish this game's download or the current folder choice first")
            return
        }
        if (!initialized || recovering || NetGate.closed || !config.enabled || config != RommSettingsState.config) return
        dismissChoices()
        val revision = session
        val snapshot = config
        folderLoading = true
        scope.launch {
            try {
                val plan = runInterruptible(Dispatchers.IO) { plan(platform, game, snapshot) }
                if (revision == session && RommDetails.matches(platform, game))
                    folderPrompt = FolderPrompt(platform, game, plan, revision)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { Toasts.show("Could not check download folders. Check Game folders and storage access")
            } finally { if (revision == session) folderLoading = false }
        }
    }

    private fun current(prompt: FolderPrompt): Boolean = folderPrompt === prompt && prompt.revision == session &&
        config.enabled && config == RommSettingsState.config && !NetGate.closed &&
        RommDetails.matches(prompt.platform, prompt.game)

    fun beginFolderPick(prompt: FolderPrompt, platform: Platform): FolderPick? {
        if (!current(prompt) || pickingFolder || platform !in prompt.plan.platforms) return null
        pickingFolder = true
        return FolderPick(prompt, platform).also { activeFolderPick = it }
    }

    fun finishFolderPick(uri: Uri?) {
        val pick = activeFolderPick ?: return
        activeFolderPick = null
        if (!current(pick.prompt)) { if (folderPrompt === pick.prompt) dismissChoices(); return }
        pickingFolder = false
        if (uri == null) return // Android picker cancellation leaves the chooser available, with no mapping changes.
        val path = treePath(uri)
        if (path == null) { Toasts.show("Choose a folder on the device or SD card. Cloud folders are not supported"); return }
        saveFolder(pick.prompt, RommDestination(pick.platform, File(path)), uri)
    }

    fun choose(prompt: FolderPrompt, destination: RommDestination) {
        if (destination in prompt.plan.choices) saveFolder(prompt, destination, null)
    }

    private fun saveFolder(prompt: FolderPrompt, destination: RommDestination, uri: Uri?) {
        if (!current(prompt) || pickingFolder) return
        val snapshot = config
        val request = pending
        pickingFolder = true
        scope.launch {
            try {
                val checked = runInterruptible(Dispatchers.IO) {
                    val platform = Platforms.all(context).firstOrNull { it.id == destination.platform.id }
                        ?: throw RommDownloadFailure(RommDownloadProblem.Folder)
                    if (request != null && request.layout.launchFor(platform) == null)
                        throw RommDownloadFailure(RommDownloadProblem.LaunchEntry)
                    val filename = prompt.game.fileName.takeIf { request?.layout?.folder == false || (request == null && prompt.game.simpleFile) }
                    val checked = RommDestinations.picked(platform, destination.folder, filename)
                    if (uri == null && plan(prompt.platform, prompt.game, snapshot, request?.layout).choices.none {
                            it.platform.id == platform.id && it.folder == checked.folder })
                        throw RommDownloadFailure(RommDownloadProblem.Folder)
                    checked
                }
                if (!current(prompt)) return@launch
                if (uri != null) withContext(Dispatchers.IO) {
                    context.contentResolver.takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                if (!current(prompt)) return@launch
                withContext(Dispatchers.IO) {
                    store.remember(choiceKey(snapshot.server, prompt.platform), "${checked.platform.id}|${checked.folder.path}")
                }
                if (!current(prompt)) return@launch
                if (uri != null) {
                    // Register the explicit system folder, not a generic root: even an empty or unusually named
                    // folder is scanned under the system the user selected, and emulator URI handoff retains its grant.
                    SystemPrefs.update { data ->
                        val old = SystemPrefs.config(checked.platform.id)
                        data.copy(trees = data.trees + (checked.folder.path to uri.toString()),
                            systems = data.systems + (checked.platform.id to old.copy(enabled = true,
                                folders = (old.folders + checked.folder.path).distinct())))
                    }
                }
                mappingVersion++
                dismissChoices()
                if (request != null) enqueue(request.platform, request.game, request to checked)
                else Toasts.show("Download folder saved for ${checked.platform.name}")
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (current(prompt)) Toasts.show("Could not use that folder. Choose a writable folder on the device or SD card")
            } finally {
                if (folderPrompt === prompt) {
                    if (current(prompt)) pickingFolder = false else dismissChoices()
                }
            }
        }
    }

    fun offer(platform: RommPlatformInfo, game: RommGameInfo): Boolean {
        if (!RommDetails.matches(platform, game) || availability(platform, game).installed) return false
        if (recovering) { Toasts.show("Checking interrupted downloads"); return false }
        if (pickingFolder || folderLoading) { Toasts.show("Finish choosing the download folder first"); return false }
        dismissChoices()
        return enqueue(platform, game)
    }

    private fun enqueue(platform: RommPlatformInfo, game: RommGameInfo, chosen: Pair<Request, RommDestination>? = null): Boolean {
        if (!config.enabled || !config.configured || config != RommSettingsState.config || NetGate.closed) {
            Toasts.show("Enable RoMM and leave private mode before downloading")
            return false
        }
        val task = Task(key(platform, game), platform, game, config, session, chosen)
        if (queue[task.key] != null) return false
        if (!queue.enqueue(task.key, task)) { Toasts.show("The download queue is full"); return false }
        if (states.size >= STATUS_LIMIT) states.entries.firstOrNull { !it.value.active }?.let { states.remove(it.key) }
        states[task.key] = Status(task.key, game.title, folder = chosen?.second?.folder?.path)
        if (service != null) {
            pump()
        } else if (!startingService) {
            startingService = true
            try { RommDownloadService.request(context) }
            catch (_: RuntimeException) {
                serviceFailed()
                Toasts.show("Open Tandem and retry the download")
                return false
            }
        }
        return true
    }

    fun serviceStarted(started: RommDownloadService) {
        startingService = false
        service = started
        pump()
    }

    fun serviceFailed() {
        startingService = false
        cancelAll("Could not start background downloads. Open Tandem and retry")
    }

    fun serviceStopped(stopped: RommDownloadService) {
        if (service !== stopped) return
        service = null
        cancelAll("Android stopped the downloads. Open Tandem and retry")
    }

    fun refreshNotification() {
        service?.update(queue.runningCount, queue.waitingCount, force = true)
    }

    private fun pump() {
        val current = service ?: return
        while (true) {
            val task = queue.startNext() ?: break
            update(task) { it.copy(text = "Preparing download…") }
            task.job = scope.launch { run(task) }.also { job ->
                job.invokeOnCompletion {
                    scope.launch { finished(task) }
                }
            }
        }
        if (queue.hasWork) current.update(queue.runningCount, queue.waitingCount)
        else {
            service = null
            current.finish()
        }
    }

    private fun finished(task: Task) {
        update(task) { state ->
            val text = if (state.cancelling && !state.finished) task.cancelMessage else state.text
            state.copy(text = text, active = false, cancelling = false)
        }
        queue.finish(task.key)
        pump()
    }

    private suspend fun run(task: Task) {
        try {
            task.control.check()
            val (request, destination) = task.chosen ?: prepare(task) ?: return
            task.control.check()
            val launch = request.layout.launchFor(destination.platform) ?: throw RommDownloadFailure(RommDownloadProblem.LaunchEntry)
            val gameKey = "${destination.platform.id}/${request.layout.name}"
            val targetPath = File(destination.folder, request.layout.name).path
            if (!queue.reserve(task.key, "game:" + gameKey.lowercase(Locale.ROOT), "path:" + targetPath.lowercase(Locale.ROOT)))
                throw RommDownloadFailure(RommDownloadProblem.BusyDestination)
            val recent = publications.values.filter { it.local.key == gameKey }
            if (recent.isNotEmpty() && withContext(Dispatchers.IO) { recent.any { it.local.file.exists() } })
                throw RommDownloadFailure(RommDownloadProblem.Exists)
            transfer(task, request, destination, launch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update(task) { if (it.finished) it else it.copy(text = error(e, task.control)) }
        }
    }

    private suspend fun prepare(task: Task): Pair<Request, RommDestination>? {
        val (request, plan) = runInterruptible(Dispatchers.IO) {
            task.control.check()
            val (layout, details) = RommLibraryClient(task.config, RommHttpTransport(task.control::check), task.control::check)
                .prepareDownload(task.game)
            val plan = plan(task.platform, details, task.config, layout)
            if (plan.platforms.isEmpty()) throw RommDownloadFailure(RommDownloadProblem.LaunchEntry)
            rejectLocalDuplicate(Libraries.all.orEmpty(), plan.choices.map { it.platform.id }.toSet(), layout)
            Request(task.platform, details, layout, task.config) to plan
        }
        task.control.check()
        plan.selected?.let { return request to it }
        if (RommDetails.matches(task.platform, task.game) && folderPrompt == null && !folderLoading) {
            pending = request
            folderPrompt = FolderPrompt(task.platform, task.game, plan, task.revision)
        }
        update(task) { it.copy(text = "Choose a download folder to continue") }
        return null
    }

    private suspend fun transfer(task: Task, request: Request, destination: RommDestination, launch: RommBundleLaunch) {
        val transfer = task.control
        val completed = AtomicReference<RommDownloaded?>(null)
        val cover = AtomicReference<File?>(null)
        var coverPending = false
        var local: Game? = null
        fun recordPublication(result: RommDownloaded): Game {
            val name = result.bundle?.record?.remoteName ?: result.file.nameWithoutExtension
            val saved = Game(destination.platform, result.file, GameFinder.cleanTitle(name), null, rommBundle = result.bundle)
            local = saved
            if (!Privacy.isHidden(saved))
                Store.update(saved.key) { meta -> RommImport.merge(meta, request.game, task.key, null) }
            val shortcut = RommShortcut(task.key.serverKey, task.key.platformId, task.key.gameId).key
            val grid = io.github.zahakj.tandem.data.IconGridStore
            val localKey = "game:${saved.key}"
            if (grid.config.replaceRommShortcut(shortcut, localKey) != grid.config)
                grid.update { it.replaceRommShortcut(shortcut, localKey) }
            if (!NetGate.closed && task.revision == session && request.config == RommSettingsState.config) {
                publications = publications + (task.key to Publication(request.config.server, task.key, saved, request.layout.name, request.layout.size))
                update(task) { it.copy(bytes = request.layout.size, text = RommAvailability.Downloaded.label, finished = true) }
            }
            return saved
        }

        val outputFolder = if (request.layout.folder) File(destination.folder, request.layout.name) else destination.folder
        update(task) { it.copy(text = "Downloading…", total = request.layout.size, folder = outputFolder.path) }
        try {
            runInterruptible(Dispatchers.IO) {
                transfer.check()
                val validate = {
                    transfer.check()
                    val currentLibraries = Libraries.all.orEmpty()
                    val allowed = RommDestinations.candidates(request.platform, Platforms.load(context), currentLibraries, Library.roots(), destination.platform.id)
                        .filter { request.layout.launchFor(it.platform) == launch }
                    if (allowed.none { it.platform.id == destination.platform.id && it.folder == destination.folder })
                        throw RommDownloadFailure(RommDownloadProblem.Folder)
                    rejectLocalDuplicate(currentLibraries, setOf(destination.platform.id), request.layout)
                }
                store.rememberIfAbsent("${task.key.serverKey}:${request.platform.id}", "${destination.platform.id}|${destination.folder.path}")
                val transport = RommHttpStreamTransport()
                val showPhase: (RommTransferPhase) -> Unit = { phase ->
                        scope.launch {
                            update(task) { status ->
                                if (status.cancelling || status.finished) status else status.copy(text = when (phase) {
                                    RommTransferPhase.Downloading -> "Downloading…"
                                    RommTransferPhase.Verifying -> "Verifying download…"
                                    RommTransferPhase.Publishing -> "Adding game to its platform folder…"
                                })
                            }
                        }
                }
                val showProgress: (Long, Long) -> Unit = { done, expected ->
                    scope.launch {
                        update(task) { status ->
                            if (status.cancelling || status.finished) status else status.copy(bytes = done, total = expected)
                        }
                    }
                }
                val result = if (request.layout.folder) {
                    RommFolderDownloader(transport, store).download(request.config, task.key, request.layout, launch, destination,
                        transfer, validate, showPhase, showProgress)
                } else RommDownloader(transport, store.newLog()).download(request.config, request.game.id,
                    request.layout.parts.single().file, destination.folder, transfer, validate, showPhase, showProgress)
                completed.set(result)
                runCatching { ScanCache.invalidate(destination.folder) }
            }
            val savedGame = completed.get()?.let(::recordPublication) ?: return
            if (!Privacy.isHidden(savedGame)) {
                val covers = RommArtworkAddress.candidates(request.config.server, request.game, large = true)
                if (covers.isNotEmpty() && savedGame.meta.box == null && MediaKind.Box.field !in savedGame.meta.lockedFields) {
                    coverPending = true
                    update(task) { it.copy(note = "Saving cover artwork…") }
                    coverPending = !runInterruptible(Dispatchers.IO) {
                        transfer.check()
                        val kind = MediaKind.Box
                        if (Media.has(savedGame, kind) || Libraries.of(savedGame)?.art?.find(ArtIndex.Kind.Box, savedGame)?.isFile == true)
                            return@runInterruptible true
                        val fetch = RommHttpTransport(transfer::check)
                        val saved = RommImport.saveCover(covers, { url ->
                            fetch.bytes(url, request.config.token, RommArtworkAddress.MAX_BYTES)
                        }) { data ->
                            transfer.check()
                            if (Privacy.isHidden(savedGame) || savedGame.meta.box != null || kind.field in savedGame.meta.lockedFields) null
                            else Media.save(savedGame, kind, data, replaceExisting = false)
                        }
                        cover.set(saved)
                        saved != null
                    }
                }
            }
        } finally {
            completed.get()?.let {
                val savedGame = local ?: recordPublication(it)
                cover.get()?.let { savedCover ->
                    if (!Privacy.isHidden(savedGame))
                        Store.update(savedGame.key) { meta -> RommImport.merge(meta, request.game, task.key, savedCover) }
                }
                val scanFailed = runCatching { LibraryScan.request(context, announce = false, onlyIfChanged = false) }.isFailure
                update(task) { status ->
                    val note = listOfNotNull(
                        "Cover artwork could not be saved. The game file is ready.".takeIf { coverPending && cover.get() == null },
                        "Library refresh could not start; rescan Game folders.".takeIf { scanFailed },
                    ).joinToString(" ").takeIf { it.isNotEmpty() }
                    status.copy(text = RommAvailability.Downloaded.label, note = note, finished = true)
                }
            }
        }
    }

    private fun rejectLocalDuplicate(libraries: List<PlatformLibrary>, platforms: Set<String>, layout: RommDownloadLayout) {
        if (libraries.filter { it.platform.id in platforms }.any { lib ->
                lib.games.any { it.libraryName == layout.name && it.file.exists() }
            })
            throw RommDownloadFailure(RommDownloadProblem.Exists)
    }

    fun cancel(key: RommInstallOrigin) {
        if (queue.removeWaiting(key) != null) finishWaiting(key, RommDownloadProblem.Cancelled.message)
        else queue[key]?.let { cancel(it, RommDownloadProblem.Cancelled.message) }
        pump()
    }

    fun cancelAll(message: String = RommDownloadProblem.Cancelled.message) {
        queue.drainWaiting().forEach { finishWaiting(it.key, message) }
        queue.active.toList().forEach { cancel(it, message) }
        pump()
    }

    private fun cancel(task: Task, message: String) {
        task.cancelMessage = message
        update(task) { if (it.finished) it else it.copy(text = "Cancelling download…", cancelling = true) }
        task.control.cancel { connection -> scope.launch(Dispatchers.IO) { runCatching { connection.disconnect() } } }
        task.job?.cancel()
    }

    private fun finishWaiting(key: RommInstallOrigin, message: String) {
        states[key]?.let { states[key] = it.copy(text = message, active = false) }
    }

    private fun error(e: Exception, transfer: RommTransferControl): String = when {
        transfer.isCancelled -> RommDownloadProblem.Cancelled.message
        e is RommDownloadFailure -> e.problem.message
        e is RommBrowseFailure -> e.problem.message
        e is PrivateOffline -> RommProblem.PrivateMode.message
        e is SocketTimeoutException -> RommProblem.Timeout.message
        e is SSLException -> RommProblem.Tls.message
        e is SecurityException -> RommDownloadProblem.Folder.message
        else -> "Download stopped. Check network access, free space and storage, then retry"
    }
}
