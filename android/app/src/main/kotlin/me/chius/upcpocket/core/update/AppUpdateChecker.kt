package me.chius.upcpocket.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.chius.upcpocket.BuildConfig
import me.chius.upcpocket.core.auth.CasHttpRequest
import me.chius.upcpocket.core.auth.CasHttpTransport
import me.chius.upcpocket.core.auth.DefaultCasHttpTransport

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class UpToDate(val currentVersion: String) : AppUpdateState
    data class UpdateAvailable(
        val versionName: String,
        val releaseNotes: String,
        val releasePageUrl: String,
        val apkDownloadUrl: String?,
    ) : AppUpdateState

    data class Error(val message: String) : AppUpdateState
}

/**
 * Checks public GitHub Releases for `chius-me/upc-pocket` and compares SemVer against [BuildConfig.VERSION_NAME].
 */
class AppUpdateChecker(
    private val transport: CasHttpTransport = DefaultCasHttpTransport,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    suspend fun checkForUpdates() = withContext(ioDispatcher) {
        _state.value = AppUpdateState.Checking
        try {
            val response = transport.execute(
                CasHttpRequest(
                    url = RELEASES_API_URL,
                    method = "GET",
                    headers = mapOf(
                        "Accept" to "application/vnd.github+json",
                        "User-Agent" to "UPCPocket-Android/$currentVersion",
                    ),
                ),
            )
            if (response.statusCode != 200) {
                _state.value = AppUpdateState.Error("检查更新失败（HTTP ${response.statusCode}）")
                return@withContext
            }
            _state.value = parseLatestRelease(response.body, currentVersion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = AppUpdateState.Error(e.message ?: "无法连接 GitHub Releases")
        }
    }

    fun dismiss() {
        _state.value = AppUpdateState.Idle
    }

    internal fun parseLatestRelease(body: String, installedVersion: String): AppUpdateState {
        val arr = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: return AppUpdateState.Error("更新元数据格式异常")
        val latest = arr.mapNotNull { it as? JsonObject }.firstOrNull { obj ->
            val draft = (obj["draft"] as? JsonPrimitive)?.booleanOrNull == true
            val tag = (obj["tag_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            !draft && tag.isNotBlank()
        } ?: return AppUpdateState.UpToDate(installedVersion)

        val rawTag = (latest["tag_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val remoteVersion = rawTag.trim().removePrefix("v").removePrefix("V")
        if (!isNewerVersion(remoteVersion, installedVersion)) {
            return AppUpdateState.UpToDate(installedVersion)
        }

        val pageUrl = (latest["html_url"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.startsWith(GITHUB_RELEASE_URL_PREFIX) }
            ?: RELEASES_WEB_URL
        val notes = (latest["body"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val assets = latest["assets"] as? JsonArray
        val apkUrl = assets?.mapNotNull { it as? JsonObject }?.firstNotNullOfOrNull { asset ->
            val name = (asset["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val url = (asset["browser_download_url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            url.takeIf {
                name.endsWith(".apk", ignoreCase = true) &&
                    url.startsWith(GITHUB_DOWNLOAD_URL_PREFIX)
            }
        }
        return AppUpdateState.UpdateAvailable(
            versionName = remoteVersion,
            releaseNotes = notes.ifBlank { "包含界面体验优化与稳定性修复。" },
            releasePageUrl = pageUrl,
            apkDownloadUrl = apkUrl,
        )
    }

    companion object {
        const val RELEASES_API_URL = "https://api.github.com/repos/chius-me/upc-pocket/releases?per_page=10"
        const val RELEASES_WEB_URL = "https://github.com/chius-me/upc-pocket/releases"
        private const val GITHUB_RELEASE_URL_PREFIX = "https://github.com/chius-me/upc-pocket/releases/"
        private const val GITHUB_DOWNLOAD_URL_PREFIX = "https://github.com/chius-me/upc-pocket/releases/download/"

        internal fun isNewerVersion(candidate: String, current: String): Boolean {
            val cParts = parseCoreSemVer(candidate) ?: return false
            val curParts = parseCoreSemVer(current) ?: return false
            for (i in 0..2) {
                if (cParts[i] != curParts[i]) return cParts[i] > curParts[i]
            }
            val cHasPre = '-' in candidate
            val curHasPre = '-' in current
            return !cHasPre && curHasPre
        }

        private fun parseCoreSemVer(raw: String): List<Int>? {
            val core = raw.substringBefore('-').trim()
            val parts = core.split('.')
            if (parts.size != 3) return null
            val nums = parts.map { it.toIntOrNull() ?: return null }
            return nums.takeIf { nums.all { n -> n >= 0 } }
        }
    }
}
