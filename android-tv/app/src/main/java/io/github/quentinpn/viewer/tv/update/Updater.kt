package io.github.quentinpn.viewer.tv.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.quentinpn.viewer.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** What the home's update card shows (ADR 0112). */
sealed interface UpdateState {
    data object Idle : UpdateState

    /** Downloaded, size, SHA-256 and signing certificate checked. */
    data class Ready(val versionCode: Int, val versionName: String, val notes: String, val file: File) : UpdateState

    /** Handed to Android's installer; its confirmation screen is up or about to be. */
    data class Installing(val ready: Ready) : UpdateState

    /** The install failed; [ready] is kept so « Installer » can try again. */
    data class Error(val message: String, val ready: Ready) : UpdateState
}

/** The outcome of a check, shown only by the manual check of Réglages (ADR 0112). */
sealed interface CheckResult {
    data object UpToDate : CheckResult

    data class Ready(val versionName: String) : CheckResult

    data class Failed(val message: String) : CheckResult
}

/** What a running check does, for the status line of Réglages. */
enum class CheckActivity { Checking, Downloading }

/** French message of a check that failed on [e]. */
internal fun checkFailure(e: Exception): String = when (e) {
    is ManifestException -> e.message ?: "Le serveur de mises à jour a répondu une erreur."
    is SecurityException -> "Android a refusé la vérification."
    else -> "Serveur de mises à jour injoignable. Il ne répond que sur le réseau de la maison."
}

/** A manifest or APK that cannot be used; its message is French and shown as is. */
internal class ManifestException(message: String) : IOException(message)

/**
 * Checks the manifest, downloads and checks the APK in `cacheDir/updates`, then installs it
 * through [PackageInstaller]. A background check or download that fails stays silent (logged):
 * off the home network there is simply no update. [checkNow] returns the outcome instead.
 */
object Updater {
    private const val TAG = "ViewerUpdate"
    private const val FOLDER = "updates"
    const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    /** « Plus tard » hides the card for this version until the app starts again. */
    @Volatile var dismissedVersion: Int = 0
        private set

    /** The settings for unknown sources were opened: install on return when allowed. */
    @Volatile var awaitingPermission = false

    private val checking = Mutex()
    @Volatile private var lastCheck = 0L

    fun dismiss() {
        (state.value as? UpdateState.Ready)?.let { dismissedVersion = it.versionCode }
        (state.value as? UpdateState.Error)?.let { dismissedVersion = it.ready.versionCode }
        (state.value as? UpdateState.Installing)?.let { dismissedVersion = it.ready.versionCode }
    }

    /** At start, then when 6 h have passed since the last check. */
    suspend fun checkIfDue(context: Context, http: OkHttpClient) {
        val now = SystemClock.elapsedRealtime()
        if (lastCheck != 0L && now - lastCheck < CHECK_INTERVAL_MS) return
        lastCheck = now
        // Silent: a failure is only logged
        check(context.applicationContext, http)
    }

    private val mutableActivity = MutableStateFlow<CheckActivity?>(null)
    val activity: StateFlow<CheckActivity?> = mutableActivity.asStateFlow()

    /**
     * « Rechercher une mise à jour » of Réglages: no 6 h throttle, and a « Plus tard » given
     * earlier no longer hides the card. Shares [check]'s lock: a background check running now
     * finishes first, and its download is reused, never made twice.
     */
    suspend fun checkNow(context: Context, http: OkHttpClient): CheckResult {
        dismissedVersion = 0
        lastCheck = SystemClock.elapsedRealtime()
        return check(context.applicationContext, http)
    }

    private suspend fun check(context: Context, http: OkHttpClient): CheckResult = checking.withLock {
        // Busy with an install: the next check will see the new version, or the same one
        (state.value as? UpdateState.Installing)?.let { return@withLock CheckResult.Ready(it.ready.versionName) }
        mutableActivity.value = CheckActivity.Checking
        try {
            withContext(Dispatchers.IO) { checkLocked(context, http) }
        } catch (e: IOException) {
            Log.i(TAG, "vérification impossible (${e.javaClass.simpleName})")
            CheckResult.Failed(checkFailure(e))
        } catch (e: SecurityException) {
            Log.w(TAG, "vérification refusée (${e.javaClass.simpleName})")
            CheckResult.Failed(checkFailure(e))
        } finally {
            mutableActivity.value = null
        }
    }

    private fun checkLocked(context: Context, http: OkHttpClient): CheckResult {
        val manifest = fetchManifest(http)
        val installed = installedInfo(context) ?: throw ManifestException("Version installée illisible.")
        val folder = File(context.cacheDir, FOLDER).apply { mkdirs() }
        val target = File(folder, apkName(manifest.versionCode))
        // Older or partial downloads go; the current APK stays if it is still the one
        folder.listFiles()?.filter { it != target || !isNewer(manifest, installed.longVersionCode) }?.forEach { it.delete() }
        if (!isNewer(manifest, installed.longVersionCode)) {
            mutableState.value = UpdateState.Idle
            return CheckResult.UpToDate
        }
        val current = state.value
        if (current is UpdateState.Ready && current.versionCode == manifest.versionCode && current.file.isFile) {
            return CheckResult.Ready(current.versionName)
        }
        if (!matchesManifest(target, manifest.size, manifest.sha256)) {
            mutableActivity.value = CheckActivity.Downloading
            download(http, manifest, target)
            if (!matchesManifest(target, manifest.size, manifest.sha256)) {
                target.delete()
                Log.w(TAG, "APK ${manifest.versionName} : taille ou SHA-256 différents du manifeste, ignoré")
                throw ManifestException("Le fichier téléchargé ne correspond pas au manifeste.")
            }
        }
        if (!sameSigner(context, installed, target)) {
            target.delete()
            Log.w(TAG, "APK ${manifest.versionName} : certificat de signature différent de l'app installée, ignoré")
            throw ManifestException("La mise à jour n'est pas signée comme l'app installée.")
        }
        mutableState.value = UpdateState.Ready(manifest.versionCode, manifest.versionName, manifest.notes.trim(), target)
        return CheckResult.Ready(manifest.versionName)
    }

    private fun fetchManifest(http: OkHttpClient): UpdateManifest {
        val request = Request.Builder().url(BuildConfig.UPDATE_MANIFEST_URL).header("Cache-Control", "no-cache").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.i(TAG, "manifeste indisponible (HTTP ${response.code})")
                throw ManifestException("Le serveur de mises à jour a répondu une erreur (HTTP ${response.code}).")
            }
            return parseManifest(response.body.string())
                ?: throw ManifestException("Le manifeste de mise à jour est illisible.").also { Log.w(TAG, "manifeste illisible") }
        }
    }

    private fun download(http: OkHttpClient, manifest: UpdateManifest, target: File) {
        val part = File(target.parentFile, target.name + ".part")
        http.newCall(Request.Builder().url(manifest.url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            part.outputStream().use { out ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        // Never more than announced: a wrong or endless body stops here
                        if (total > manifest.size) throw IOException("plus long que le manifeste")
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("renommage impossible")
        }
    }

    private fun installedInfo(context: Context): PackageInfo? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }.getOrNull()

    /** The APK is signed by exactly the installed app's certificates; any doubt is a mismatch. */
    private fun sameSigner(context: Context, installed: PackageInfo, apk: File): Boolean {
        val pm = context.packageManager
        // Below API 33, getPackageArchiveInfo leaves signingInfo null for an APK file;
        // the legacy `signatures` field is still filled there (same certificates).
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        val flags = if (legacy) PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val mine = installed.signingInfo?.apkContentsSigners?.toSet() ?: return false
        @Suppress("DEPRECATION")
        val theirs = (if (legacy) archive.signatures?.toSet() else archive.signingInfo?.apkContentsSigners?.toSet())
            ?: return false
        return mine.isNotEmpty() && mine == theirs
    }

    /** Android lets this app install packages (« sources inconnues »). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Writes the APK into an installer session and commits it; the result comes to [UpdateReceiver]. */
    suspend fun install(context: Context) {
        val ready = when (val s = state.value) {
            is UpdateState.Ready -> s
            is UpdateState.Error -> s.ready
            else -> return
        }
        val app = context.applicationContext
        mutableState.value = UpdateState.Installing(ready)
        try {
            withContext(Dispatchers.IO) {
                if (!ready.file.isFile) throw IOException("fichier absent")
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(ready.file.length())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, ready.file.length()).use { out ->
                        ready.file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                    val callback = PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java).setPackage(app.packageName), flags)
                    session.commit(callback.intentSender)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "installation impossible (${e.javaClass.simpleName})")
            mutableState.value = UpdateState.Error("L'installation n'a pas pu démarrer.", ready)
        } catch (e: SecurityException) {
            Log.w(TAG, "installation refusée (${e.javaClass.simpleName})")
            mutableState.value = UpdateState.Error("Android a refusé l'installation.", ready)
        }
    }

    /** The user closed Android's confirmation screen: the card comes back as it was. */
    internal fun cancelled() {
        (state.value as? UpdateState.Installing)?.let { mutableState.value = it.ready }
    }

    internal fun failed(status: Int, message: String?) {
        Log.w(TAG, "installation en échec (statut $status${message?.let { ", $it" } ?: ""})")
        val ready = (state.value as? UpdateState.Installing)?.ready ?: (state.value as? UpdateState.Ready) ?: return
        mutableState.value = UpdateState.Error(installFailure(status), ready)
    }
}

/** French message of a [PackageInstaller] failure status. */
internal fun installFailure(status: Int): String = when (status) {
    PackageInstaller.STATUS_FAILURE_STORAGE -> "Pas assez d'espace pour installer la mise à jour."
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_CONFLICT ->
        "Cette mise à jour n'est pas compatible avec l'app installée."
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android a bloqué l'installation."
    PackageInstaller.STATUS_FAILURE_INVALID -> "Le fichier de mise à jour est invalide."
    else -> "L'installation a échoué."
}
