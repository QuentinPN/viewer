package io.github.quentinpn.viewer.tv.stremio

import android.util.Log
import io.github.quentinpn.viewer.tv.writeAtomically
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Where a Stremio playback stopped, per content (`imdb[:S:E]`, the target the addons are asked for), not per
// stream: the next playback of that film or episode offers « Reprendre » whatever stream it picks. Kept in the
// app's private files, across Stremio sign-outs; Emby never hears of it. As the desktop's `resume.go`.

/** The file name, in the app's private `filesDir`. */
const val RESUME_FILE = "stremio-resume.json"

/** How many contents the file keeps; the oldest update goes first. */
private const val RESUME_LIMIT = 500

private const val TAG = "StremioResume"

/** Where a content stopped, as the list sees it. */
data class ResumePoint(val positionSeconds: Double, val durationSeconds: Double)

/** A point in the file, with when it was written. */
@Serializable
private class StoredPoint(val positionSeconds: Double, val durationSeconds: Double, val updatedAt: String)

private class Stamped(val point: ResumePoint, val updatedAt: Instant)

private val resumeJson = Json { ignoreUnknownKeys = true }

internal fun finitePositive(v: Double) = v > 0 && v.isFinite()

/**
 * `stremio-resume.json`: target → where it stopped. Read on first use; a missing or unreadable file is an empty
 * store, a corrupt one is logged without its content and replaced on the next write. Thread-safe: the list and
 * the player share one instance. A write that fails is logged and the store goes on in memory.
 */
class ResumeStore(
    private val file: File,
    internal val now: () -> Instant = Instant::now,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val lock = Any()
    private var read: MutableMap<String, Stamped>? = null

    /** The point saved for [target], or null. */
    fun get(target: String): ResumePoint? = synchronized(lock) { entries()[target]?.point }

    /** Saves [point] for [target], dropping the oldest entries past [RESUME_LIMIT]. */
    fun put(target: String, point: ResumePoint) = synchronized(lock) {
        val entries = entries()
        entries[target] = Stamped(point, now())
        evict(entries)
        save(entries)
    }

    /** Forgets [target]; nothing is written when it was not there. */
    fun delete(target: String) = synchronized(lock) {
        val entries = entries()
        if (entries.remove(target) != null) save(entries)
    }

    override fun toString() = "ResumeStore"

    private fun entries(): MutableMap<String, Stamped> = read ?: load().also { read = it }

    private fun load(): MutableMap<String, Stamped> {
        val entries = HashMap<String, Stamped>()
        val text = try {
            file.readText()
        } catch (_: FileNotFoundException) {
            return entries
        } catch (e: IOException) {
            log("Stremio resume file unreadable (${e.javaClass.simpleName})")
            return entries
        }
        val stored = try {
            resumeJson.decodeFromString<Map<String, StoredPoint>>(text)
        } catch (_: IllegalArgumentException) {
            // Never the exception's message: it quotes the content
            log("Stremio resume file corrupt, starting empty")
            return entries
        }
        for ((target, s) in stored) {
            // Entries that make no sense are dropped: finite, positive, the position inside the duration
            if (!finitePositive(s.positionSeconds) || !finitePositive(s.durationSeconds) || s.positionSeconds >= s.durationSeconds) continue
            val at = try {
                Instant.parse(s.updatedAt)
            } catch (_: DateTimeParseException) {
                continue
            }
            entries[target] = Stamped(ResumePoint(s.positionSeconds, s.durationSeconds), at)
        }
        evict(entries)
        return entries
    }

    /** Drops the oldest updates (then the smallest target) past [RESUME_LIMIT]. */
    private fun evict(entries: MutableMap<String, Stamped>) {
        while (entries.size > RESUME_LIMIT) {
            val oldest = entries.entries.minWith(compareBy<Map.Entry<String, Stamped>> { it.value.updatedAt }.thenBy { it.key })
            entries.remove(oldest.key)
        }
    }

    private fun save(entries: Map<String, Stamped>) {
        val text = resumeJson.encodeToString(entries.mapValues { (_, e) -> StoredPoint(e.point.positionSeconds, e.point.durationSeconds, e.updatedAt.toString()) })
        try {
            file.parentFile?.mkdirs()
            writeAtomically(file, text)
        } catch (e: IOException) {
            log("Stremio resume not saved (${e.javaClass.simpleName})")
        }
    }
}
