package com.excp.podroid.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val latestVersion: String,
    val releaseUrl: String,
)

/**
 * A published APK of *our* fork, already resolved to a single download URL.
 *
 * [version] is the tag with the leading `v` stripped (`1.3.0-9`) — it is what the
 * Settings dialog shows and what [isNewerOwn] compares against
 * [com.excp.podroid.BuildConfig.VERSION_NAME].
 */
data class ApkRelease(
    val version: String,
    val tag: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/**
 * Returns true when the cache has expired and a fresh network check is warranted.
 *
 * Uses wall-clock milliseconds (currentTimeMillis) — not uptimeMillis, which
 * resets to zero on reboot and causes the post-reboot delta to be a large
 * negative number, permanently skipping the check. A negative delta is clamped
 * to "stale" so a clock going backwards also triggers a re-check rather than
 * suppressing it indefinitely.
 */
internal fun shouldCheck(now: Long, lastCheck: Long, validityMs: Long): Boolean {
    val elapsed = now - lastCheck
    return elapsed < 0L || elapsed >= validityMs
}

/**
 * Timestamp to persist after a failed update check so [shouldCheck] re-opens the
 * gate at the right time. A definitive outcome (GitHub was actually reached)
 * backs off the full [validityMs]. A transient failure (offline / DNS / timeout)
 * is back-dated so the existing validity gate re-opens after only [retryFloorMs]
 * instead of a full day — one offline launch must not suppress the check for 24h.
 */
internal fun backoffStamp(now: Long, transient: Boolean, validityMs: Long, retryFloorMs: Long): Long =
    if (transient) now - validityMs + retryFloorMs else now

/**
 * Returns true if `latest` is a higher version than `current`. Compares the
 * numeric core (`1.2.3`) first; if those are equal, treats a prerelease suffix
 * (`-rc2`) as lower than a release, and compares prerelease numeric chunks
 * numerically so `rc10 > rc9`.
 */
internal fun isNewer(latest: String, current: String): Boolean {
    fun splitCore(v: String) = v.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
    val l = splitCore(latest)
    val c = splitCore(current)
    val maxLen = maxOf(l.size, c.size)
    for (i in 0 until maxLen) {
        val lv = l.getOrElse(i) { 0 }
        val cv = c.getOrElse(i) { 0 }
        if (lv > cv) return true
        if (lv < cv) return false
    }
    // Numeric cores match — break ties by suffix. Empty suffix > any prerelease suffix.
    val ls = latest.substringAfter("-", "")
    val cs = current.substringAfter("-", "")
    return when {
        ls == cs -> false
        ls.isEmpty() -> true   // "1.2.0" is newer than "1.2.0-rc1"
        cs.isEmpty() -> false  // "1.2.0-rc1" is older than "1.2.0"
        else -> comparePrerelease(ls, cs) > 0
    }
}

/**
 * Compares two prerelease strings (the part after `-`) by splitting on
 * non-numeric/numeric boundaries and comparing each chunk: numeric chunks
 * compare as integers, non-numeric chunks compare lexicographically.
 * Example: "rc10" > "rc9" because the numeric chunk 10 > 9.
 */
private fun comparePrerelease(a: String, b: String): Int {
    fun chunks(s: String): List<String> = buildList {
        val sb = StringBuilder()
        var numeric = s.firstOrNull()?.isDigit() ?: false
        for (ch in s) {
            if (ch.isDigit() == numeric) {
                sb.append(ch)
            } else {
                if (sb.isNotEmpty()) add(sb.toString())
                sb.clear()
                sb.append(ch)
                numeric = ch.isDigit()
            }
        }
        if (sb.isNotEmpty()) add(sb.toString())
    }

    val ac = chunks(a)
    val bc = chunks(b)
    val len = maxOf(ac.size, bc.size)
    for (i in 0 until len) {
        val ac_ = ac.getOrElse(i) { "" }
        val bc_ = bc.getOrElse(i) { "" }
        val cmp = when {
            ac_.isEmpty() && bc_.isEmpty() -> 0
            ac_.isEmpty() -> -1
            bc_.isEmpty() -> 1
            ac_.all { it.isDigit() } && bc_.all { it.isDigit() } ->
                // Clamp oversized numeric chunks to lexicographic so a malformed
                // tag (>18 digits, > Long.MAX) can't throw NumberFormatException.
                if (ac_.length <= 18 && bc_.length <= 18) ac_.toLong().compareTo(bc_.toLong())
                else ac_.compareTo(bc_)
            else -> ac_.compareTo(bc_)
        }
        if (cmp != 0) return cmp
    }
    return 0
}

/**
 * Version comparison for *our own* release tags (`v1.3.0-<run_number>`).
 *
 * Differs from [isNewer] on exactly one point: for us a build suffix means "a
 * later build of the same version", so `1.3.0-9` is NEWER than `1.3.0` —
 * whereas upstream's `1.2.0-rc1` is older than `1.2.0`. Without this, the first
 * build of a version would rank below the very release it replaces and the
 * in-app updater would never fire.
 */
internal fun isNewerOwn(latest: String, current: String): Boolean {
    fun core(v: String) = v.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
    val l = core(latest)
    val c = core(current)
    for (i in 0 until maxOf(l.size, c.size)) {
        val lv = l.getOrElse(i) { 0 }
        val cv = c.getOrElse(i) { 0 }
        if (lv > cv) return true
        if (lv < cv) return false
    }
    val ls = latest.substringAfter("-", "")
    val cs = current.substringAfter("-", "")
    return when {
        ls == cs -> false
        ls.isEmpty() -> false  // a bare "1.3.0" is never newer than a later build of it
        cs.isEmpty() -> true   // "1.3.0-9" supersedes the bare "1.3.0"
        else -> comparePrerelease(ls, cs) > 0
    }
}

@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dismissedKey = stringPreferencesKey("dismissed_update_version")
    private val lastCheckKey = longPreferencesKey("update_check_timestamp")
    private val cacheValidityMs = 24 * 60 * 60 * 1000L
    // A transient (IOException) failure backs off only this long instead of the
    // full cacheValidityMs, so a single offline launch doesn't suppress the next
    // in-app update check for a full day.
    private val retryFloorMs = 20 * 60 * 1000L

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        // Wall-clock time so the 24h gate survives device reboots and deep sleep.
        val now = System.currentTimeMillis()
        var connection: java.net.HttpURLConnection? = null
        try {
            val lastCheck = context.dataStore.data
                .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
                .first()[lastCheckKey] ?: 0L

            if (!shouldCheck(now, lastCheck, cacheValidityMs)) {
                return@withContext null
            }

            connection = URL("https://api.github.com/repos/ExTV/Podroid/releases/latest")
                .openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/vnd.github+json")

            val code = connection.responseCode
            if (code !in 200..299) {
                // Consume error body so the connection can be reused, then back off.
                runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }
                context.dataStore.edit { it[lastCheckKey] = now }
                return@withContext null
            }

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            // removePrefix rather than trimStart — trimStart strips repeated 'v' chars.
            val tag = obj.optString("tag_name", "").removePrefix("v")
            val url = obj.optString("html_url", "")

            if (tag.isEmpty() || url.isEmpty()) {
                context.dataStore.edit { it[lastCheckKey] = now }
                return@withContext null
            }

            context.dataStore.edit { it[lastCheckKey] = now }

            // Build-type suffix (`-debug`) is decoration, not a prerelease — strip it so
            // 1.1.7-debug compares equal to the 1.1.7 release tag instead of "older".
            val normalizedCurrent = currentVersion.removeSuffix("-debug")
            if (isNewer(tag, normalizedCurrent)) UpdateInfo(tag, url) else null
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Coroutine cancellation is not a check failure — don't record a
            // timestamp (which would back off a check the user may retry) and
            // don't swallow it; let it propagate.
            throw c
        } catch (e: Exception) {
            // Network/DataStore/JSON error: log it (a changed GitHub response or
            // JSON shape was previously failing invisibly) and record a timestamp
            // to back off on the next launch. A transient network failure
            // (offline / DNS / timeout — all IOException) only earns the short
            // retry floor; a non-IO error means GitHub was reached but returned
            // something unusable, so it keeps the full 24h backoff.
            android.util.Log.w("UpdateRepository", "update check failed", e)
            val stamp = backoffStamp(now, transient = e is java.io.IOException, cacheValidityMs, retryFloorMs)
            runCatching { context.dataStore.edit { it[lastCheckKey] = stamp } }
            null
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun isDismissed(version: String): Boolean {
        // Shield the read so a corrupted store returns "not dismissed" instead of
        // throwing into the caller.
        val dismissed = context.dataStore.data
            .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
            .first()[dismissedKey]
        return dismissed == version
    }

    suspend fun dismissUpdate(version: String) {
        context.dataStore.edit { it[dismissedKey] = version }
    }

    // ── In-app update from OUR OWN releases ──────────────────────────────────
    // Kept strictly separate from [checkForUpdate]: that one asks upstream
    // "is there a newer Podroid?" and only ever opens a browser. This one asks
    // "is there a newer build of ours?" and is the only feed the installer may
    // use. Pointing the silent startup check at our fork would nag every user
    // of an upstream build, so this path is manual-only (Settings → Check for
    // updates) and is not gated by the 24h cache.

    private val ownReleaseApi = "https://api.github.com/repos/yydfys/Podroid/releases/latest"

    /**
     * Mirrors tried in order for the APK body; "" means github.com directly.
     * Release downloads are frequently unroutable from mainland China, and one
     * dead mirror must not cost the whole download — each attempt restarts at
     * byte 0 into a fresh `.part` file.
     */
    private val apkDownloadMirrors = listOf("https://gh-proxy.com/", "https://ghproxy.net/", "")

    private val userAgent = "Podroid-Android"

    /**
     * Manual check against our own release feed.
     *
     * Returns null when nothing newer is published *or* the request failed; the
     * caller renders "up to date", and a failed manual check must never look
     * like an available update.
     */
    suspend fun checkOwnRelease(currentVersion: String): ApkRelease? = withContext(Dispatchers.IO) {
        var connection: java.net.HttpURLConnection? = null
        try {
            connection = URL(ownReleaseApi).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", userAgent)

            if (connection.responseCode !in 200..299) {
                // Drain the error body so the socket can be closed cleanly.
                runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }
                return@withContext null
            }

            val obj = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (obj.optBoolean("prerelease", false) || obj.optBoolean("draft", false)) {
                return@withContext null
            }

            val tag = obj.optString("tag_name", "")
            val version = tag.removePrefix("v")
            if (version.isEmpty()) return@withContext null
            if (!isNewerOwn(version, currentVersion.removeSuffix("-debug"))) return@withContext null

            val assets = obj.optJSONArray("assets") ?: return@withContext null
            // Prefer the signed "-release.apk"; fall back to the first .apk so a
            // future rename in CI degrades to "still works" instead of "no update".
            var chosen: JSONObject? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                if (asset.optString("browser_download_url", "").isEmpty()) continue
                if (chosen == null) chosen = asset
                if (name.endsWith("-release.apk", ignoreCase = true)) {
                    chosen = asset
                    break
                }
            }
            val asset = chosen ?: return@withContext null
            return@withContext ApkRelease(
                version = version,
                tag = tag,
                downloadUrl = asset.optString("browser_download_url", ""),
                sizeBytes = asset.optLong("size", 0L),
            )
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Exception) {
            android.util.Log.w("UpdateRepository", "own release check failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Downloads [release] into `filesDir/updates` and returns the APK, ready to
     * be handed to the system package installer. Progress is 0..100.
     *
     * The body goes to a `.part` file and is renamed into place only after a zip
     * header check, so a truncated mirror response (or an HTML error page) can
     * never reach the installer looking like a valid APK.
     */
    suspend fun downloadApk(
        release: ApkRelease,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, "podroid-${release.version}.apk")
        var lastError: Exception? = null

        for (mirror in apkDownloadMirrors) {
            val partial = File(dir, "${target.name}.part")
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = URL(mirror + release.downloadUrl).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", userAgent)

                val code = connection.responseCode
                if (code !in 200..299) {
                    runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }
                    throw java.io.IOException("HTTP $code")
                }

                // Trust the response length when present, the asset metadata otherwise.
                val total = connection.contentLengthLong.takeIf { it > 0L } ?: release.sizeBytes
                var copied = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0L) {
                                val percent = (copied * 100 / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }

                // A release APK is ~300 MB; anything tiny, or not a zip at all,
                // is a failed download rather than something to install.
                if (copied < 1_000_000L) throw java.io.IOException("truncated download ($copied bytes)")
                val header = partial.inputStream().use { stream ->
                    val bytes = ByteArray(2)
                    stream.read(bytes)
                    bytes
                }
                if (header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte()) {
                    throw java.io.IOException("not an APK")
                }

                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) throw java.io.IOException("cannot move APK into place")
                onProgress(100)
                return@withContext target
            } catch (c: kotlinx.coroutines.CancellationException) {
                runCatching { partial.delete() }
                throw c
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w("UpdateRepository", "APK download from '$mirror' failed", e)
                runCatching { partial.delete() }
            } finally {
                connection?.disconnect()
            }
        }
        throw lastError ?: java.io.IOException("download failed")
    }
}
