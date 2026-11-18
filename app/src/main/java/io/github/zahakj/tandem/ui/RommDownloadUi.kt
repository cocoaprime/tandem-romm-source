package io.github.zahakj.tandem.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.zahakj.tandem.Libraries
import io.github.zahakj.tandem.NetGate
import io.github.zahakj.tandem.Platform
import io.github.zahakj.tandem.data.SystemPrefs
import io.github.zahakj.tandem.romm.RommAvailability
import io.github.zahakj.tandem.romm.RommDestinationPlan
import io.github.zahakj.tandem.romm.RommGameInfo
import io.github.zahakj.tandem.romm.RommPlatformInfo
import io.github.zahakj.tandem.romm.rommSizeLabel

@Composable
internal fun RommDownloadComplete(platform: RommPlatformInfo, game: RommGameInfo) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(RommAvailability.Downloaded.label, style = MaterialTheme.typography.titleSmall, color = Palette.Mint)
        RommDownloads.status(platform, game)?.note?.let { note ->
            Text(note, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
    }
}

@Composable
internal fun RommDownloadControls(platform: RommPlatformInfo, game: RommGameInfo, target: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val transfer = RommDownloads.status(platform, game)
    val active = transfer?.active == true
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) RommDownloads.refreshNotification()
    }
    fun download() {
        if (!RommDownloads.offer(platform, game)) return
        if (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val preferences = ctx.getSharedPreferences("romm-downloads", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("notification_prompted", false)) {
            runCatching { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .onSuccess { preferences.edit().putBoolean("notification_prompted", true).apply() }
        }
    }
    var resumed by remember { mutableIntStateOf(0) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var preview by remember(platform.id, game.id) { mutableStateOf<RommDestinationPlan?>(null) }
    var checking by remember(platform.id, game.id) { mutableStateOf(true) }
    LaunchedEffect(platform, game, RommSettingsState.config, SystemPrefs.data, Libraries.all,
        RommDownloads.mappingVersion, RommDownloads.recovering, resumed, NetGate.closed) {
        checking = true
        preview = null
        try { preview = RommDownloads.preview(platform, game) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* The folder action remains available to recover from inaccessible storage. */ }
        finally { checking = false }
    }
    val path = transfer?.folder?.takeIf { active } ?: preview?.selected?.folder?.path
    val scroll = rememberScrollState()
    Column(modifier.fillMaxWidth().fadingEdges(scroll).verticalScroll(scroll).padding(horizontal = 8.dp, vertical = FocusRoom),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        transfer?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        FocusCard(Modifier.fillMaxWidth().takesFocus("romm-detail", "download-to", target == "download-to"), glow = Palette.Sky,
            onClick = { RommDownloads.selectFolder(platform, game) }) {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Text("Download to" + if (active) "" else " · Choose / change", style = MaterialTheme.typography.labelMedium, color = Palette.Sky)
                Text(path ?: when {
                    checking -> "Checking folders…"
                    preview?.unavailablePath != null -> "Folder unavailable: ${preview?.unavailablePath}"
                    preview?.choices?.isNotEmpty() == true -> "Choose a platform folder"
                    else -> "Choose download folder"
                }, Modifier.heightIn(max = 78.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (active && transfer != null) RommTransferProgress(transfer)
        FocusCard(Modifier.fillMaxWidth().heightIn(min = 46.dp).takesFocus("romm-detail", "download", target == "download"), glow = Palette.Mint,
            onClick = { if (active && transfer != null) { if (!transfer.cancelling) RommDownloads.cancel(transfer.key) } else download() }) {
            val label = when {
                transfer?.cancelling == true -> "Cancelling…"
                active -> "Cancel download"
                else -> "Download game"
            }
            Text(label, Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FocusCard(Modifier.fillMaxWidth().heightIn(min = 42.dp).takesFocus("romm-detail", "folders", target == "folders"), glow = Palette.Sky,
            onClick = { RommDetails.close(); Nav.go(Route.GameFolders) }) {
            Text("Game folders", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun RommTransferProgress(transfer: RommDownloads.Status) {
    if (transfer.total <= 0 || transfer.finished) return
    ProgressBar((transfer.bytes.toDouble() / transfer.total).toFloat())
    Text(
        "${rommSizeLabel(transfer.bytes)} / ${rommSizeLabel(transfer.total)}",
        style = MaterialTheme.typography.bodySmall, color = Palette.Sky, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun RommDestinationPicker() {
    val prompt = RommDownloads.folderPrompt
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> RommDownloads.finishFolderPick(uri) }
    fun pick(prompt: RommDownloads.FolderPrompt, platform: Platform) {
        if (RommDownloads.beginFolderPick(prompt, platform) == null) return
        try { picker.launch(null) } catch (_: Exception) {
            RommDownloads.finishFolderPick(null)
            Toasts.show("Android's folder picker could not open. Check Game folders")
        }
    }
    OverlayPanel(prompt != null && !RommDownloads.pickingFolder && !NetGate.closed && RommSettingsState.config.enabled,
        onClose = { RommDownloads.dismissChoices() }) {
        val shown = rememberLatest(prompt) ?: return@OverlayPanel
        val choices = shown.plan.choices
        var systemChoice by remember(shown) { mutableStateOf(choices.isEmpty() && shown.plan.platforms.size > 1) }
        Column(Modifier.width(580.dp).background(Palette.Card, RoundedCornerShape(22.dp)).padding(16.dp)) {
            Text(if (systemChoice) "Choose the local system" else "Download folder · ${shown.platform.title}", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (systemChoice) "Which Tandem system should scan this folder?" else "Choose an existing folder or pick one on the device or SD card. Your choice is remembered for this RoMM platform.",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            val route = "romm-download-destination"
            val options = if (systemChoice) shown.plan.platforms.map { it.id } else choices.map { "${it.platform.id}|${it.folder.path}" }
            val keys = options + listOfNotNull("pick-folder".takeIf { !systemChoice && shown.plan.platforms.isNotEmpty() }, "cancel")
            val target = rememberFocusTarget(route, keys)
            val list = rememberLazyListState()
            KeepInView(list, keys.indexOf(target) + if (shown.plan.platforms.isEmpty()) 1 else 0)
            LazyColumn(Modifier.weight(1f, fill = false).fadingEdges(list), state = list,
                contentPadding = PaddingValues(horizontal = FocusRoomSide, vertical = FocusRoom), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (systemChoice) itemsIndexed(shown.plan.platforms, key = { _, p -> p.id }) { index, platform ->
                    FocusCard(Modifier.fillMaxWidth().takesFocus(route, keys[index], target == keys[index]), glow = Palette.Sky,
                        onClick = { pick(shown, platform) }) {
                        Text(platform.name, Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
                    }
                } else itemsIndexed(choices, key = { i, _ -> keys[i] }) { index, destination ->
                    FocusCard(Modifier.fillMaxWidth().takesFocus(route, keys[index], target == keys[index]), glow = Palette.Sky,
                        onClick = { RommDownloads.choose(shown, destination) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(destination.platform.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(destination.folder.path, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (!systemChoice && shown.plan.platforms.isNotEmpty()) item("pick-folder") {
                    FocusCard(Modifier.fillMaxWidth().takesFocus(route, "pick-folder", target == "pick-folder"), glow = Palette.Mint,
                        onClick = { if (shown.plan.platforms.size == 1) pick(shown, shown.plan.platforms.single()) else systemChoice = true }) {
                        Text("Choose download folder…", Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
                    }
                }
                if (shown.plan.platforms.isEmpty()) item("unsupported") {
                    Text("No Tandem system supports this file type. Check your system configuration in Settings.", style = MaterialTheme.typography.bodySmall)
                }
                item("cancel") {
                    FocusCard(Modifier.fillMaxWidth().takesFocus(route, "cancel", target == "cancel"), glow = Palette.Lavender,
                        onClick = { RommDownloads.dismissChoices() }) {
                        Text("Cancel", Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}
