package app.phueber.trigly.ui

import app.phueber.trigly.actions.isSuccessfulStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * What one press of "Check for updates" on [AttributionScreen] finds out.
 *
 * A sealed result rather than a nullable version string: [CheckFailed]
 * carries why, so the screen can say something true when the phone is
 * offline or GitHub does not answer, rather than nothing at all. A silent
 * failure would be worse than no check, the same reasoning [checkForUpdate]
 * itself is built around.
 */
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class UpdateAvailable(val latestVersion: String) : UpdateCheckResult
    data class CheckFailed(val reason: String) : UpdateCheckResult
}

/**
 * Reads `tag_name` out of a GitHub "latest release" API response, and drops a
 * leading `v` if the tag has one: [isNewerVersion] compares bare version
 * numbers, and a tag is free to spell its own release either way.
 *
 * Null on anything this does not recognise, rather than throwing. This reads
 * a real network response, which is data from outside the app exactly the
 * same way an imported rule is; see `RuleJson` for the same posture toward
 * external JSON elsewhere in this codebase.
 */
fun parseLatestReleaseTag(json: String): String? =
    try {
        JSONObject(json).getString("tag_name").removePrefix("v")
    } catch (malformed: JSONException) {
        null
    }

/**
 * Whether [latest] is a newer release than [current], comparing each
 * dot-separated part as a number rather than as text. Plain string
 * comparison gets this backwards: `"0.10.0" < "0.9.0"` by text, the wrong way
 * round for version numbers.
 *
 * A part missing on either side counts as 0, so `"1.2"` compares equal to
 * `"1.2.0"` rather than failing to parse. A part that is not a number, on
 * either side, also counts as 0: a malformed version cannot silently compare
 * as "newer" and never trips an exception on what a person only pressed a
 * button to check.
 */
fun isNewerVersion(current: String, latest: String): Boolean {
    val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
    val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }

    for (index in 0 until maxOf(currentParts.size, latestParts.size)) {
        val currentPart = currentParts.getOrElse(index) { 0 }
        val latestPart = latestParts.getOrElse(index) { 0 }
        if (latestPart != currentPart) return latestPart > currentPart
    }
    return false
}

/**
 * Looks once, at GitHub's own release list, for a release newer than
 * [currentVersion].
 *
 * Nothing calls this but a button press: see `AttributionScreen`'s "Check for
 * updates" row, wired from `MainActivity.AttributionHost`. There is no
 * scheduler and no background worker anywhere in this codebase that calls
 * this function; a person presses a control and Trigly looks, once, and
 * nothing here runs on its own or reports anything to anyone but GitHub's own
 * public API, which is asked for nothing but the version tag every visitor to
 * the releases page can already see.
 *
 * `HttpURLConnection`, the same client `HttpRequestAction` uses and for the
 * same reason: one caller does not justify adding OkHttp.
 */
suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(GITHUB_LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }

        val code = connection.responseCode
        if (!isSuccessfulStatus(code)) {
            return@withContext UpdateCheckResult.CheckFailed("GitHub answered with HTTP $code.")
        }

        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val latestVersion = parseLatestReleaseTag(body)
            ?: return@withContext UpdateCheckResult.CheckFailed("Could not read GitHub's release list.")

        if (isNewerVersion(current = currentVersion, latest = latestVersion)) {
            UpdateCheckResult.UpdateAvailable(latestVersion)
        } else {
            UpdateCheckResult.UpToDate
        }
    } catch (io: IOException) {
        UpdateCheckResult.CheckFailed("Could not reach GitHub. ${io.message}")
    } finally {
        connection?.disconnect()
    }
}

private const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/philipphueber/Trigly/releases/latest"
