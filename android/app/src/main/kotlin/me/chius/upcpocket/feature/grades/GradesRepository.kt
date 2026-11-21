package me.chius.upcpocket.feature.grades

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.chius.upcpocket.core.auth.CasHttpRequest
import me.chius.upcpocket.core.auth.CasHttpTransport
import me.chius.upcpocket.core.auth.CasTransportException
import me.chius.upcpocket.core.auth.DefaultCasHttpTransport
import me.chius.upcpocket.core.auth.JwxtAuthResult
import me.chius.upcpocket.core.auth.UpcJwxtAuthEndpoints
import me.chius.upcpocket.core.auth.UpcJwxtAuthenticator
import me.chius.upcpocket.core.data.SchoolAccountRepository
import me.chius.upcpocket.core.model.AccountInvalidationReason
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.model.SchoolLoginCredential
import me.chius.upcpocket.core.model.SchoolStudentIdentity
import me.chius.upcpocket.core.webview.ControlledWebViewContract
import me.chius.upcpocket.core.webview.clearSchoolWebViewCookies

class GradesRepository internal constructor(
    private val directory: File,
    private val accountStateProvider: () -> SchoolAccountState,
    private val identityProvider: () -> SchoolStudentIdentity?,
    private val matchesAccountGeneration: (String, Long) -> Boolean,
    private val credentialExecutor: suspend (suspend (SchoolLoginCredential) -> JwxtAuthResult) -> JwxtAuthResult?,
    private val authenticator: UpcJwxtAuthenticator,
    private val transport: CasHttpTransport = DefaultCasHttpTransport,
    private val clearWebViewCookies: () -> Unit = ::clearSchoolWebViewCookies,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        context: Context,
        schoolAccountRepository: SchoolAccountRepository,
        transport: CasHttpTransport = DefaultCasHttpTransport,
    ) : this(
        directory = File(context.applicationContext.noBackupFilesDir, DIR_NAME),
        accountStateProvider = { schoolAccountRepository.state.value },
        identityProvider = { schoolAccountRepository.studentIdentity.value },
        matchesAccountGeneration = schoolAccountRepository::matchesAccountGeneration,
        credentialExecutor = { block -> schoolAccountRepository.withLoginCredential(block) },
        authenticator = UpcJwxtAuthenticator(transport),
        transport = transport,
    )

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val mutex = Mutex()
    private val fileMutex = Mutex()
    private val businessEpoch = AtomicLong(0L)

    @Volatile
    private var inMemoryCookies: Map<String, String>? = null

    private val _uiState = MutableStateFlow(GradesUiState())
    val uiState: StateFlow<GradesUiState> = _uiState.asStateFlow()

    suspend fun restore() = withContext(ioDispatcher) {
        val state = accountStateProvider()
        val epoch = businessEpoch.get()
        when (state.status) {
            AccountStatus.UNSET -> _uiState.value = GradesUiState(connectionStatus = GradesConnectionStatus.UNCONFIGURED)
            AccountStatus.RECOVERY_REQUIRED -> _uiState.value = GradesUiState(connectionStatus = GradesConnectionStatus.RECOVERY_REQUIRED)
            AccountStatus.SAVED_UNVERIFIED -> {
                val accountId = state.accountId ?: return@withContext
                val cached = fileMutex.withLock { readCacheOrNull(accountId) }
                if (!stillValid(accountId, state.generation, epoch)) return@withContext
                _uiState.value = GradesUiState(
                    connectionStatus = if (cached != null) GradesConnectionStatus.OFFLINE_CACHE else GradesConnectionStatus.IDLE,
                    snapshot = cached,
                )
            }
        }
    }

    suspend fun refresh(): Result<GradesSnapshot> = withContext(ioDispatcher) {
        mutex.withLock {
            val state = accountStateProvider()
            val identity = identityProvider()
            val epoch = businessEpoch.get()
            if (state.status != AccountStatus.SAVED_UNVERIFIED || state.accountId == null || identity == null) {
                publishBlockedState(state)
                return@withLock Result.failure(IOException("请先配置包含有效学号的学校账号"))
            }
            val accountId = state.accountId
            val generation = state.generation

            _uiState.update {
                it.copy(
                    isRefreshing = true,
                    errorMessage = null,
                    interactionUrl = null,
                    interactionReason = null,
                )
            }

            var cookies = inMemoryCookies
            var freshAuth = false
            if (cookies == null) {
                when (val authRes = authenticateOnce(accountId, generation, epoch)) {
                    is JwxtAuthOutcome.Aborted -> {
                        _uiState.update { it.copy(isRefreshing = false) }
                        return@withLock Result.failure(IOException("账号状态已变化"))
                    }
                    is JwxtAuthOutcome.Failed -> return@withLock Result.failure(IOException(authRes.message))
                    is JwxtAuthOutcome.Ready -> {
                        cookies = authRes.cookies
                        inMemoryCookies = cookies
                        freshAuth = true
                    }
                }
            }

            return@withLock fetchAndCommit(
                cookies = cookies,
                identity = identity,
                epoch = epoch,
                allowReauth = !freshAuth,
            )
        }
    }

    suspend fun adoptWebViewCookies(
        cookies: Map<String, String>,
        sessionContext: ControlledWebViewContract.SessionContext,
    ) = withContext(ioDispatcher) {
        clearWebViewCookies()
        mutex.withLock {
            val state = accountStateProvider()
            val identity = identityProvider()
            val epoch = businessEpoch.get()
            if (!sessionContext.isStillValidFor(state) ||
                state.status != AccountStatus.SAVED_UNVERIFIED ||
                identity == null ||
                cookies.isEmpty()
            ) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        interactionUrl = null,
                        interactionReason = null,
                    )
                }
                return@withLock
            }
            inMemoryCookies = cookies
            _uiState.update {
                it.copy(
                    isRefreshing = true,
                    errorMessage = null,
                    interactionUrl = null,
                    interactionReason = null,
                )
            }
            fetchAndCommit(
                cookies = cookies,
                identity = identity,
                epoch = epoch,
                allowReauth = false,
            )
        }
    }

    fun dismissInteractionPrompt() {
        _uiState.update { it.copy(interactionUrl = null, interactionReason = null) }
    }

    suspend fun clearCache() = withContext(ioDispatcher) {
        businessEpoch.incrementAndGet()
        inMemoryCookies = null
        clearCacheFile()
        restore()
    }

    suspend fun onAccountInvalidated(reason: AccountInvalidationReason) = withContext(ioDispatcher) {
        businessEpoch.incrementAndGet()
        inMemoryCookies = null
        clearWebViewCookies()
        if (reason == AccountInvalidationReason.ACCOUNT_CHANGED_OR_CLEARED) {
            clearCacheFile()
            publishBlockedState(accountStateProvider())
        } else {
            _uiState.update {
                it.copy(
                    connectionStatus = if (it.snapshot != null) GradesConnectionStatus.OFFLINE_CACHE else GradesConnectionStatus.IDLE,
                    isRefreshing = false,
                    interactionUrl = null,
                    interactionReason = null,
                )
            }
        }
    }

    private sealed interface JwxtAuthOutcome {
        data object Aborted : JwxtAuthOutcome
        class Ready(val cookies: Map<String, String>) : JwxtAuthOutcome
        class Failed(val message: String) : JwxtAuthOutcome
    }

    private suspend fun authenticateOnce(
        accountId: String,
        generation: Long,
        epoch: Long,
    ): JwxtAuthOutcome {
        val result = credentialExecutor { cred -> authenticator.authenticate(cred) }
            ?: run {
                publishBlockedState(accountStateProvider())
                return JwxtAuthOutcome.Aborted
            }
        if (!stillValid(accountId, generation, epoch)) return JwxtAuthOutcome.Aborted

        return when (result) {
            is JwxtAuthResult.Success -> JwxtAuthOutcome.Ready(result.cookies)
            is JwxtAuthResult.InvalidCredentials -> {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        connectionStatus = GradesConnectionStatus.INVALID_CREDENTIALS,
                        errorMessage = "学号或统一认证密码错误",
                    )
                }
                JwxtAuthOutcome.Failed("学号或密码错误")
            }
            is JwxtAuthResult.InteractionRequired -> {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        connectionStatus = GradesConnectionStatus.INTERACTION_REQUIRED,
                        interactionUrl = result.interactionUrl,
                        interactionReason = result.reason,
                        errorMessage = result.reason,
                    )
                }
                JwxtAuthOutcome.Failed(result.reason)
            }
            is JwxtAuthResult.NetworkError -> {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        connectionStatus = if (it.snapshot != null) GradesConnectionStatus.OFFLINE_CACHE else GradesConnectionStatus.ERROR,
                        errorMessage = result.message,
                    )
                }
                JwxtAuthOutcome.Failed(result.message)
            }
            is JwxtAuthResult.UpstreamError -> {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        connectionStatus = if (it.snapshot != null) GradesConnectionStatus.OFFLINE_CACHE else GradesConnectionStatus.ERROR,
                        errorMessage = result.message,
                    )
                }
                JwxtAuthOutcome.Failed(result.message)
            }
        }
    }

    private suspend fun fetchAndCommit(
        cookies: Map<String, String>,
        identity: SchoolStudentIdentity,
        epoch: Long,
        allowReauth: Boolean,
    ): Result<GradesSnapshot> {
        var activeCookies = cookies
        for (attempt in 0..1) {
            val outcome = fetchOnce(activeCookies, identity.studentId)
            if (!stillValid(identity.accountId, identity.generation, epoch)) {
                _uiState.update { it.copy(isRefreshing = false) }
                return Result.failure(IOException("账号状态已变化"))
            }
            when (outcome) {
                is FetchOutcome.AuthRequired -> {
                    inMemoryCookies = null
                    if (attempt == 0 && allowReauth) {
                        when (val reauth = authenticateOnce(identity.accountId, identity.generation, epoch)) {
                            is JwxtAuthOutcome.Ready -> {
                                activeCookies = reauth.cookies
                                inMemoryCookies = activeCookies
                                continue
                            }
                            is JwxtAuthOutcome.Aborted -> {
                                _uiState.update { it.copy(isRefreshing = false) }
                                return Result.failure(IOException("账号已切换"))
                            }
                            is JwxtAuthOutcome.Failed -> return Result.failure(IOException(reauth.message))
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isRefreshing = false,
                                connectionStatus = GradesConnectionStatus.INTERACTION_REQUIRED,
                                interactionUrl = UpcJwxtAuthEndpoints.SSO_URL,
                                interactionReason = "教务会话已失效，请通过安全验证页登录",
                                errorMessage = "教务会话已失效",
                            )
                        }
                        return Result.failure(IOException("教务会话已失效"))
                    }
                }
                is FetchOutcome.Failure -> {
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            connectionStatus = if (it.snapshot != null) GradesConnectionStatus.OFFLINE_CACHE else GradesConnectionStatus.ERROR,
                            errorMessage = outcome.message,
                        )
                    }
                    return Result.failure(IOException(outcome.message))
                }
                is FetchOutcome.Success -> {
                    val snapshot = GradesSnapshot(
                        accountId = identity.accountId,
                        credentialGeneration = identity.generation,
                        records = outcome.records,
                        fetchedAtEpochMillis = clock().coerceAtLeast(1L),
                    )
                    fileMutex.withLock {
                        if (stillValid(identity.accountId, identity.generation, epoch)) {
                            writeCacheAtomically(snapshot)
                        }
                    }
                    if (!stillValid(identity.accountId, identity.generation, epoch)) {
                        return Result.failure(IOException("账号状态已变化"))
                    }
                    _uiState.update {
                        it.copy(
                            connectionStatus = GradesConnectionStatus.ONLINE,
                            snapshot = snapshot,
                            isRefreshing = false,
                            errorMessage = null,
                        )
                    }
                    return Result.success(snapshot)
                }
            }
        }
        return Result.failure(IOException("成绩同步未能完成"))
    }

    private sealed interface FetchOutcome {
        data class Success(val records: List<CourseGradeRecord>) : FetchOutcome
        data object AuthRequired : FetchOutcome
        data class Failure(val message: String) : FetchOutcome
    }

    private suspend fun fetchOnce(cookies: Map<String, String>, studentId: String): FetchOutcome {
        val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val response = try {
            transport.execute(
                CasHttpRequest(
                    url = UpcJwxtAuthEndpoints.GRADES_LIST_URL,
                    method = "POST",
                    headers = mapOf(
                        "Cookie" to cookieHeader,
                        "Referer" to UpcJwxtAuthEndpoints.GRADES_QUERY_URL,
                        "Accept" to "text/html,application/xhtml+xml",
                    ),
                    formBody = GradesProtocol.formBody(),
                ),
            )
        } catch (e: CasTransportException) {
            return FetchOutcome.Failure(e.message ?: "无法连接教务成绩接口")
        }

        if (response.statusCode in 300..399) {
            val loc = response.location.orEmpty()
            return if ("cas/login" in loc || "sso.jsp" in loc) {
                FetchOutcome.AuthRequired
            } else {
                FetchOutcome.Failure("教务接口重定向异常（HTTP ${response.statusCode}）")
            }
        }
        if (response.statusCode in setOf(401, 403)) return FetchOutcome.AuthRequired
        if (response.statusCode != 200) {
            return FetchOutcome.Failure("教务成绩接口返回 HTTP ${response.statusCode}")
        }

        return try {
            when (val parsed = GradesProtocol.parse(response.body, studentId)) {
                is GradesParseOutcome.Ok -> FetchOutcome.Success(parsed.records)
                is GradesParseOutcome.AuthRequired -> FetchOutcome.AuthRequired
                is GradesParseOutcome.IdentityMismatch -> FetchOutcome.Failure(parsed.message)
            }
        } catch (e: GradesProtocolException) {
            FetchOutcome.Failure(e.message ?: "成绩解析失败")
        }
    }

    private fun publishBlockedState(state: SchoolAccountState) {
        _uiState.value = when (state.status) {
            AccountStatus.UNSET -> GradesUiState(connectionStatus = GradesConnectionStatus.UNCONFIGURED)
            AccountStatus.RECOVERY_REQUIRED -> GradesUiState(connectionStatus = GradesConnectionStatus.RECOVERY_REQUIRED)
            AccountStatus.SAVED_UNVERIFIED -> GradesUiState(connectionStatus = GradesConnectionStatus.IDLE)
        }
    }

    private fun stillValid(accountId: String, generation: Long, epoch: Long): Boolean =
        matchesAccountGeneration(accountId, generation) && businessEpoch.get() == epoch

    private fun readCacheOrNull(accountId: String): GradesSnapshot? {
        val file = File(directory, CACHE_FILE)
        if (!file.isFile || file.length() > GradesProtocol.MAX_RESPONSE_BYTES) return null
        return runCatching {
            val decoded = json.decodeFromString<GradesSnapshot>(file.readText(Charsets.UTF_8))
            decoded.takeIf { it.accountId == accountId }
        }.getOrNull()
    }

    private fun writeCacheAtomically(snapshot: GradesSnapshot) {
        directory.mkdirs()
        val target = File(directory, CACHE_FILE)
        val tmp = File(directory, "$CACHE_FILE.tmp")
        val bytes = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        tmp.outputStream().use { fos ->
            fos.write(bytes)
            fos.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            throw IOException("Failed to replace grades cache")
        }
        if (!tmp.renameTo(target)) {
            throw IOException("Failed to commit grades cache")
        }
    }

    private suspend fun clearCacheFile() {
        fileMutex.withLock {
            File(directory, CACHE_FILE).delete()
            File(directory, "$CACHE_FILE.tmp").delete()
        }
    }

    private companion object {
        const val DIR_NAME = "grades"
        const val CACHE_FILE = "cache-v1.json"
    }
}
