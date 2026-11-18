package io.github.zahakj.tandem

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zahakj.tandem.data.HttpError
import io.github.zahakj.tandem.data.Net
import io.github.zahakj.tandem.data.Store
import io.github.zahakj.tandem.ui.RommDownloads
import io.github.zahakj.tandem.ui.Toasts
import io.github.zahakj.tandem.ui.inBackground
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/*
 * Checks the Tandem RoMM GitHub release list when the library comes into view. An opted-in device can
 * install a verified update while Tandem has focus and no RoMM transfers are active. Android retains
 * control of install permission and confirmation. Manual updates are available in Settings and Start.
 */

/** A release newer than this build: its version ("0.4.1"), notes (Markdown) and where its files are. */
@Serializable
data class Release(val version: String, val notes: String, val apkName: String, val apkUrl: String, val sumsUrl: String)

/** Where an update stands. [Problem] is a short sentence for the user. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Downloading(val pct: Int) : UpdateState
    data object Verifying : UpdateState
    data object Installing : UpdateState
    data class Problem(val text: String) : UpdateState
}

/** The pure parts (versions, GitHub's answer, SHA256SUMS, the notes), so they can be tested without Android. */
object Releases {
    internal const val REPOSITORY = "cocoaprime/tandem-romm-source"
    private const val PREVIEW_APK = "Tandem-RoMM_Preview.apk"
    private val tagPattern = Regex("""(?:v|romm-)(\d{1,9}\.\d{1,9}\.\d{1,9})(?:-preview\.(\d{1,9}))?""")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class GhAsset(val name: String = "", @SerialName("browser_download_url") val url: String = "")

    @Serializable
    private class GhRelease(
        @SerialName("tag_name") val tag: String = "",
        val body: String? = null,
        val draft: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    )

    /**
     * Compares two versions ("v0.4.1", "0.10.0", "0.5.0-rc1") number by number, so 0.10 is newer than 0.9. Missing
     * numbers count as 0 (0.4 = 0.4.0), and a pre-release ("-rc1") comes before its release.
     */
    fun compare(a: String, b: String): Int {
        fun split(v: String): Pair<List<Int>, String?> {
            val clean = v.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            val pre = clean.substringAfter('-', "").ifEmpty { null }
            return clean.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } to pre
        }
        val (na, pa) = split(a)
        val (nb, pb) = split(b)
        for (i in 0 until maxOf(na.size, nb.size)) {
            val c = na.getOrElse(i) { 0 }.compareTo(nb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            pa == pb -> 0
            pa == null -> 1
            pb == null -> -1
            else -> comparePrerelease(pa, pb)
        }
    }

    private fun comparePrerelease(a: String, b: String): Int {
        val left = a.split('.')
        val right = b.split('.')
        for ((x, y) in left.zip(right)) {
            val nx = x.toLongOrNull()
            val ny = y.toLongOrNull()
            val order = when {
                nx != null && ny != null -> nx.compareTo(ny)
                nx != null -> -1
                ny != null -> 1
                else -> x.compareTo(y)
            }
            if (order != 0) return order
        }
        return left.size.compareTo(right.size)
    }

    /** Selects the newest compatible published release strictly newer than the installed version. */
    fun latest(text: String, installed: String): Release? = json.decodeFromString<List<GhRelease>>(text)
        .asSequence()
        .mapNotNull(::release)
        .filter { compare(it.version, installed) > 0 }
        .maxWithOrNull { a, b -> compare(a.version, b.version) }

    private fun release(r: GhRelease): Release? {
        if (r.draft) return null
        val tag = tagPattern.matchEntire(r.tag) ?: return null
        val base = tag.groupValues[1]
        val preview = tag.groupValues[2]
        val version = if (preview.isEmpty()) base else "$base-preview.$preview"
        val origin = "https://github.com/$REPOSITORY/releases/download/${r.tag}"
        fun asset(name: String): GhAsset? = r.assets.singleOrNull { it.name == name && it.url == "$origin/$name" }
        val apk = asset(PREVIEW_APK) ?: asset("tandem-$version.apk") ?: return null
        val sums = asset("SHA256SUMS") ?: return null
        return Release(version, r.body.orEmpty(), apk.name, apk.url, sums.url)
    }

    /** The SHA-256 (lowercase hex) SHA256SUMS lists for [file], or null. Lines are `<sha256>  <name>` (`*name` too). */
    fun sumFor(sums: String, file: String): String? = sums.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val (hash, name) = parts
        if (name.removePrefix("*") != file || !hash.matches(Regex("[0-9a-fA-F]{64}"))) null else hash.lowercase()
    }.firstOrNull()

    /** Release notes in Markdown as plain text: no heading marks, emphasis or link targets; list items get a dot. */
    fun plainNotes(md: String): String = md.replace("\r\n", "\n").lines().joinToString("\n") { raw ->
        var line = raw.trimEnd()
        line = line.replace(Regex("^#{1,6}\\s+"), "")
        line = line.replace(Regex("^(\\s*)[-*+]\\s+"), "$1• ")
        line = line.replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        line = line.replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2").replace(Regex("`([^`]*)`"), "$1")
        line
    }.replace(Regex("\n{3,}"), "\n\n").trim()

    /**
     * The downloaded APK's signer may install over the installed one: the same certificates ([installed], [apk], as
     * SHA-256 hex), or a rotated key whose history ([apkHistory]) holds the installed one.
     */
    fun sameSigner(installed: Set<String>, apk: Set<String>, apkHistory: Set<String>): Boolean =
        installed.isNotEmpty() && (installed == apk || installed.all { it in apkHistory })

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

object Updates {
    private const val LIST = "https://api.github.com/repos/${Releases.REPOSITORY}/releases?per_page=30"
    private const val PREFS = "romm_updates"
    private const val CHECKED = "checked_at"
    private const val FOUND = "found"
    private const val TOLD = "told"
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val RETRY_MS = 15 * 60 * 1000L
    private var triedAt = 0L
    private var autoAttempted: String? = null

    /** A release newer than this build, once a check found one (kept across restarts until it is installed). */
    var found by mutableStateOf<Release?>(null)
        private set
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    /** When GitHub last answered (0: never), for "Up to date". */
    var checkedAt by mutableStateOf(0L)
        private set

    private var loaded = false

    /** The updater exists in this build and the user leaves it on. */
    val enabled: Boolean get() = BuildConfig.SELF_UPDATE && Store.settings.checkUpdates

    val busy: Boolean get() = when (state) {
        UpdateState.Idle, is UpdateState.Problem -> false
        else -> true
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Reads what an earlier check found, once per process; forgets a release this build already is (or passed). */
    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(ctx)
        checkedAt = p.getLong(CHECKED, 0L)
        val kept = p.getString(FOUND, null)?.let { runCatching { Store.json.decodeFromString<Release>(it) }.getOrNull() }
        if (kept != null && Releases.compare(kept.version, BuildConfig.VERSION_NAME) > 0) found = kept
        else {
            p.edit().remove(FOUND).apply()
            // An APK left from an update that is now installed.
            val left = File(ctx.cacheDir, "updates")
            Thread({ left.deleteRecursively() }, "tandem-update-tidy").start()
        }
    }

    /** The library is in front again. A failed scheduled check backs off before trying again. */
    fun daily(ctx: Context) {
        if (!BuildConfig.SELF_UPDATE) return
        load(ctx)
        if (!enabled || NetGate.closed || busy) return
        val now = System.currentTimeMillis()
        if (now - checkedAt in 0 until DAY_MS || now - triedAt in 0 until RETRY_MS) {
            maybeAutoInstall(ctx)
            return
        }
        check(ctx, manual = false)
    }

    /** Asks GitHub now. Manual checks also work when scheduled checking is switched off. */
    fun check(ctx: Context, manual: Boolean = true) {
        if (!BuildConfig.SELF_UPDATE || busy) return
        load(ctx)
        val app = ctx.applicationContext
        triedAt = System.currentTimeMillis()
        state = UpdateState.Checking
        var result: Result<Release?> = Result.success(null)
        inBackground({
            result = runCatching {
                val body = UpdateNetwork.text(LIST, "application/vnd.github+json", 1 shl 20)
                Releases.latest(body, BuildConfig.VERSION_NAME)
            }
        }) {
            result.fold({ newer ->
                if (NetGate.closed) {
                    state = if (manual) UpdateState.Problem(problem(PrivateOffline())) else UpdateState.Idle
                    return@fold
                }
                checkedAt = System.currentTimeMillis()
                val p = prefs(app).edit().putLong(CHECKED, checkedAt)
                found = newer
                if (newer != null) p.putString(FOUND, Store.json.encodeToString(newer)) else p.remove(FOUND)
                p.apply()
                state = UpdateState.Idle
                if (!manual && newer != null && !maybeAutoInstall(app) && prefs(app).getString(TOLD, null) != newer.version) {
                    prefs(app).edit().putString(TOLD, newer.version).apply()
                    Toasts.show("Tandem ${newer.version} is out · open Start to update", 4500)
                }
            }) { t ->
                Log.i("Tandem", "Update check failed: ${t.javaClass.simpleName}")
                state = if (manual) UpdateState.Problem(problem(t)) else UpdateState.Idle
            }
        }
    }

    private fun allowedAutomatically(ctx: Context): Boolean =
        enabled && Store.settings.autoInstallUpdates && !NetGate.closed && Gamepads.tandemHasController &&
            RommDownloads.activeDownloads.isEmpty() && ctx.packageManager.canRequestPackageInstalls()

    private fun maybeAutoInstall(ctx: Context): Boolean {
        val release = found ?: return false
        if (busy || autoAttempted == release.version || !allowedAutomatically(ctx)) return false
        autoAttempted = release.version
        install(ctx, automatic = true)
        return true
    }

    private fun problem(t: Throwable): String = when (t) {
        is PrivateOffline -> "Offline in private mode"
        is HttpError -> when (t.code) {
            404 -> "GitHub releases are unavailable"
            403, 429 -> "GitHub limited this check · try again later"
            else -> "GitHub said ${t.code}"
        }
        is IOException -> "Couldn't download the update"
        else -> "Couldn't read or install the update"
    }

    /** The row's words: this version and where the update stands. */
    fun statusLine(): String {
        val now = BuildConfig.VERSION_NAME
        return when (val s = state) {
            UpdateState.Checking -> "$now · Checking…"
            is UpdateState.Downloading -> "Downloading ${s.pct} %"
            UpdateState.Verifying -> "Verifying"
            UpdateState.Installing -> "Installing"
            is UpdateState.Problem -> s.text
            UpdateState.Idle -> found?.let { "${it.version} available" } ?: if (checkedAt > 0) "$now · Up to date" else now
        }
    }

    fun install(ctx: Context) = install(ctx, automatic = false)

    private class DeferredUpdate : IOException()

    private fun install(ctx: Context, automatic: Boolean) {
        val release = found ?: return
        if (!BuildConfig.SELF_UPDATE || busy) return
        val app = ctx.applicationContext
        fun checkAllowed() {
            NetGate.check()
            if (automatic && !allowedAutomatically(app)) throw DeferredUpdate()
        }
        state = UpdateState.Downloading(0)
        var failure: Throwable? = null
        var rejected: String? = null
        inBackground({
            val dir = File(app.cacheDir, "updates")
            val apk = File(dir, release.apkName)
            try {
                checkAllowed()
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create update cache")
                UpdateNetwork.download(release.apkUrl, apk, ::checkAllowed) { state = UpdateState.Downloading(it) }
                state = UpdateState.Verifying
                rejected = verify(app, release, apk)
                if (rejected == null) {
                    checkAllowed()
                    state = UpdateState.Installing
                    if (automatic || !Shell.ready() || !installWithShizuku(apk)) {
                        installWithSession(app, apk, ::checkAllowed)
                    }
                }
            } catch (t: Exception) {
                failure = t
            } finally {
                apk.delete()
            }
        }) {
            when {
                failure is DeferredUpdate -> {
                    autoAttempted = null
                    state = UpdateState.Idle
                }
                failure != null -> state = UpdateState.Problem(problem(checkNotNull(failure)))
                rejected != null -> state = UpdateState.Problem(checkNotNull(rejected))
            }
        }
    }

    /**
     * Null when [apk] is the release and may install over this Tandem: its SHA-256 is the one SHA256SUMS lists, it is
     * Tandem, newer, and signed with the installed app's key. Otherwise why not.
     */
    private fun verify(ctx: Context, rel: Release, apk: File): String? {
        val sums = UpdateNetwork.text(rel.sumsUrl, "application/octet-stream", 64 shl 10)
        val want = Releases.sumFor(sums, rel.apkName) ?: return "SHA256SUMS doesn't list ${rel.apkName}"
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != want) return "Download damaged (checksum differs)"
        val pm = ctx.packageManager
        val theirs = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return "Not a readable APK"
        val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (theirs.packageName != ctx.packageName) return "Not a Tandem APK"
        if (theirs.longVersionCode <= mine.longVersionCode) return "Not newer than this Tandem"
        val history = theirs.signingInfo?.signingCertificateHistory.orEmpty().map { Releases.sha256(it.toByteArray()) }.toSet()
        if (!Releases.sameSigner(signers(mine), signers(theirs), history)) {
            // A debug build has the debug key: the release can't replace it. Install it by hand after uninstalling.
            return if (BuildConfig.DEBUG) "Can't install over a debug build" else "Not signed with Tandem's key: not installed"
        }
        return null
    }

    private fun signers(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().map { Releases.sha256(it.toByteArray()) }.toSet()

    /** `pm install -r` through Shizuku, the APK on its stdin (the shell can't read Tandem's cache). True when it worked. */
    private fun installWithShizuku(apk: File): Boolean {
        val p = Shell.spawn(listOf("pm", "install", "-r", "-S", apk.length().toString())) ?: return false
        return try {
            p.outputStream.use { out -> apk.inputStream().use { it.copyTo(out) } }
            val said = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if ("Success" !in said) Log.w("Tandem", "pm install said: ${said.trim()}")
            "Success" in said
        } catch (t: Throwable) {
            Log.w("Tandem", "Shizuku install failed", t)
            false
        } finally {
            Shell.release(p)
        }
    }

    /**
     * Android's installer: writes the APK into a session and commits it. Android asks the user to confirm (and, the
     * first time, to allow installs from Tandem); its answers arrive at [UpdateInstallReceiver].
     */
    private fun installWithSession(ctx: Context, apk: File, checkAllowed: () -> Unit) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            // Android 12+ may then let Tandem update itself without a second question, once installs are allowed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite(apk.name, 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                checkAllowed()
                val answer = Intent(ctx, UpdateInstallReceiver::class.java)
                val pending = PendingIntent.getBroadcast(ctx, id, answer, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(id) }
            throw error
        }
    }

    /** Android's installer answered with a problem, or the user said no. */
    internal fun failed(text: String) { state = UpdateState.Problem(text) }

    /** The user backed out of Android's question: the update stays offered. */
    internal fun cancelled() { state = UpdateState.Idle }
}

/** GitHub HTTPS transfers with bounded redirects and size limits. */
private object UpdateNetwork {
    private val downloadHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
        "github-releases.githubusercontent.com")
    private const val APK_LIMIT = 256L shl 20

    fun text(url: String, accept: String, limit: Int): String = request(url, accept) { connection ->
        val out = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            copy(input, limit.toLong(), NetGate::check) { buffer, count, _ -> out.write(buffer, 0, count) }
        }
        out.toString(Charsets.UTF_8.name())
    }

    fun download(url: String, to: File, checkAllowed: () -> Unit, progress: (Int) -> Unit) {
        request(url, "application/octet-stream") { connection ->
            val total = connection.contentLengthLong
            if (total > APK_LIMIT) throw IOException("APK exceeds size limit")
            var shown = 0
            connection.inputStream.use { input ->
                to.outputStream().use { out ->
                    copy(input, APK_LIMIT, checkAllowed) { buffer, count, done ->
                        out.write(buffer, 0, count)
                        val percent = if (total > 0) (done * 100 / total).toInt().coerceAtMost(100) else 0
                        if (percent != shown) {
                            shown = percent
                            progress(percent)
                        }
                    }
                }
            }
        }
    }

    private fun copy(input: InputStream, limit: Long, checkAllowed: () -> Unit, write: (ByteArray, Int, Long) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        while (true) {
            checkAllowed()
            val count = input.read(buffer)
            if (count == -1) return
            done += count
            if (done > limit) throw IOException("GitHub response exceeds size limit")
            write(buffer, count, done)
        }
    }

    private fun <T> request(url: String, accept: String, read: (HttpURLConnection) -> T): T {
        var target = URL(url)
        val api = target.host == "api.github.com"
        repeat(5) {
            NetGate.check()
            if (target.protocol != "https" || target.userInfo != null || (target.port != -1 && target.port != 443) ||
                (if (api) target.host != "api.github.com" else target.host !in downloadHosts)) {
                throw IOException("Unexpected update host")
            }
            val connection = target.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", Net.AGENT)
                connection.setRequestProperty("Accept", accept)
                when (val code = connection.responseCode) {
                    in 200..299 -> return read(connection)
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: throw IOException("Missing GitHub redirect")
                        target = URL(target, location)
                    }
                    else -> throw HttpError(code, "")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many update redirects")
    }
}

/** Android's installer's answers for an update (see [Updates.install]). */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return Updates.failed("Android didn't ask to install")
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Updates.failed("Couldn't open Android's installer") }
            }
            // Tandem is replaced and restarts; nothing to do.
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updates.cancelled()
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("Tandem", "Update install failed ($status): $msg")
                Updates.failed(if (status == PackageInstaller.STATUS_FAILURE_STORAGE) "Not enough space to install" else "Android didn't install it")
            }
        }
    }
}
