package me.chius.upcpocket.feature.shiguang

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.chius.upcpocket.core.auth.CasHttpRequest
import me.chius.upcpocket.core.auth.CasHttpTransport
import me.chius.upcpocket.core.auth.CasTransportException
import me.chius.upcpocket.core.auth.DefaultCasHttpTransport
import me.chius.upcpocket.core.auth.ShiguangAuthResult
import me.chius.upcpocket.core.auth.UpcShiguangAuthEndpoints
import me.chius.upcpocket.core.auth.UpcShiguangAuthenticator
import me.chius.upcpocket.core.data.SchoolAccountRepository
import me.chius.upcpocket.core.model.AccountInvalidationReason
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.model.SchoolLoginCredential
import me.chius.upcpocket.core.webview.ControlledWebViewContract
import me.chius.upcpocket.core.webview.clearSchoolWebViewCookies

/**
 * Strictly read-only repository for UPC Shiguang Second Classroom (`dekt.upc.edu.cn`).
 * Sessions live in RAM only and enforce personal identity verification before displaying "我的成绩单".
 */
class ShiguangRepository internal constructor(
    private val accountStateProvider: () -> SchoolAccountState,
    private val matchesAccountGeneration: (String, Long) -> Boolean,
    private val credentialExecutor: suspend (suspend (SchoolLoginCredential) -> ShiguangAuthResult) -> ShiguangAuthResult?,
    private val readLoginName: suspend () -> String?,
    private val authenticator: UpcShiguangAuthenticator,
    private val transport: CasHttpTransport = DefaultCasHttpTransport,
    private val clearWebViewCookies: () -> Unit = ::clearSchoolWebViewCookies,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        schoolAccountRepository: SchoolAccountRepository,
        transport: CasHttpTransport = DefaultCasHttpTransport,
    ) : this(
        accountStateProvider = { schoolAccountRepository.state.value },
        matchesAccountGeneration = schoolAccountRepository::matchesAccountGeneration,
        credentialExecutor = { block -> schoolAccountRepository.withLoginCredential(block) },
        readLoginName = { schoolAccountRepository.withLoginCredential { it.loginName.trim() } },
        authenticator = UpcShiguangAuthenticator(transport),
        transport = transport,
    )

    private data class InMemorySession(
        val cookies: Map<String, String>,
        val identityVerified: Boolean,
        val accountId: String,
        val generation: Long,
        val epoch: Long,
    ) {
        fun cookieHeader(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private val mutex = Mutex()
    private val businessEpoch = AtomicLong(0L)

    @Volatile
    private var session: InMemorySession? = null

    private val _uiState = MutableStateFlow(ShiguangUiState())
    val uiState: StateFlow<ShiguangUiState> = _uiState.asStateFlow()

    fun syncAccountStatus() {
        val state = accountStateProvider()
        val currentStatus = _uiState.value.connectionStatus
        when (state.status) {
            AccountStatus.UNSET -> _uiState.update {
                ShiguangUiState(connectionStatus = ShiguangConnectionStatus.UNCONFIGURED)
            }
            AccountStatus.RECOVERY_REQUIRED -> _uiState.update {
                ShiguangUiState(connectionStatus = ShiguangConnectionStatus.RECOVERY_REQUIRED)
            }
            AccountStatus.SAVED_UNVERIFIED -> {
                if (currentStatus in setOf(
                        ShiguangConnectionStatus.UNCONFIGURED,
                        ShiguangConnectionStatus.RECOVERY_REQUIRED,
                    )
                ) {
                    _uiState.update { it.copy(connectionStatus = ShiguangConnectionStatus.IDLE) }
                }
            }
        }
    }

    fun selectTab(tab: ShiguangTab) {
        _uiState.update { it.copy(activeTab = tab, errorMessage = null) }
    }

    suspend fun refresh(keyword: String = _uiState.value.keyword) = withContext(ioDispatcher) {
        val trimmed = keyword.trim().take(40)
        mutex.withLock {
            val state = accountStateProvider()
            val accountId = state.accountId ?: run {
                syncAccountStatus()
                return@withLock
            }
            val generation = state.generation
            val epoch = businessEpoch.get()
            if (state.status != AccountStatus.SAVED_UNVERIFIED) {
                syncAccountStatus()
                return@withLock
            }

            _uiState.update {
                it.copy(
                    isLoading = true,
                    keyword = trimmed,
                    errorMessage = null,
                    statusMessage = null,
                )
            }
            try {
                val active = ensureSessionLocked(accountId, generation, epoch) ?: return@withLock
                val activities = withSessionRetry(active, accountId, generation, epoch) { s ->
                    fetchPage(s, page = 1, keyword = trimmed, mine = false)
                } ?: return@withLock

                val latest = session ?: active
                val summary = if (latest.identityVerified) {
                    runCatching {
                        withSessionRetry(latest, accountId, generation, epoch) { s ->
                            fetchSummary(s)
                        }
                    }.getOrNull()
                } else {
                    null
                }
                val participations = if (latest.identityVerified) {
                    runCatching {
                        withSessionRetry(latest, accountId, generation, epoch) { s ->
                            fetchPage(s, page = 1, keyword = "", mine = true)
                        }
                    }.getOrNull()
                } else {
                    null
                }
                if (!stillValid(accountId, generation, epoch)) return@withLock
                _uiState.update {
                    it.copy(
                        connectionStatus = ShiguangConnectionStatus.READY,
                        isLoading = false,
                        identityVerified = (session ?: latest).identityVerified,
                        activitiesPage = activities,
                        summary = summary ?: it.summary,
                        participationsPage = participations ?: it.participationsPage,
                        errorMessage = null,
                        statusMessage = if (latest.identityVerified) "已同步石光活动与个人学时" else "已同步石光活动列表",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!stillValid(accountId, generation, epoch)) return@withLock
                _uiState.update {
                    it.copy(
                        connectionStatus = ShiguangConnectionStatus.ERROR,
                        isLoading = false,
                        errorMessage = e.message ?: "读取石光二课数据失败，请重试",
                    )
                }
            }
        }
    }

    suspend fun loadPage(page: Int, mine: Boolean, keyword: String = _uiState.value.keyword) =
        withContext(ioDispatcher) {
            val safePage = page.coerceIn(1, if (mine) 500 else 5000)
            val trimmed = keyword.trim().take(40)
            mutex.withLock {
                val state = accountStateProvider()
                val accountId = state.accountId ?: return@withLock
                val generation = state.generation
                val epoch = businessEpoch.get()
                if (state.status != AccountStatus.SAVED_UNVERIFIED) return@withLock

                _uiState.update { it.copy(isLoading = true, keyword = trimmed, errorMessage = null) }
                try {
                    val active = ensureSessionLocked(accountId, generation, epoch) ?: return@withLock
                    if (mine && !active.identityVerified) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = "学校暂未确认本人学号，仅可浏览公开活动列表",
                            )
                        }
                        return@withLock
                    }
                    val pageResult = withSessionRetry(active, accountId, generation, epoch) { s ->
                        fetchPage(s, page = safePage, keyword = trimmed, mine = mine)
                    } ?: return@withLock
                    if (!stillValid(accountId, generation, epoch)) return@withLock
                    _uiState.update {
                        it.copy(
                            connectionStatus = ShiguangConnectionStatus.READY,
                            isLoading = false,
                            activitiesPage = if (mine) it.activitiesPage else pageResult,
                            participationsPage = if (mine) pageResult else it.participationsPage,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!stillValid(accountId, generation, epoch)) return@withLock
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = e.message ?: "加载分页失败",
                        )
                    }
                }
            }
        }

    suspend fun loadDetail(activity: ShiguangActivity) = withContext(ioDispatcher) {
        mutex.withLock {
            val state = accountStateProvider()
            val accountId = state.accountId ?: return@withLock
            val generation = state.generation
            val epoch = businessEpoch.get()
            if (state.status != AccountStatus.SAVED_UNVERIFIED) return@withLock

            _uiState.update {
                it.copy(
                    selectedActivity = activity,
                    selectedDetail = null,
                    isLoadingDetail = true,
                    errorMessage = null,
                )
            }
            try {
                val active = ensureSessionLocked(accountId, generation, epoch) ?: return@withLock
                val detail = withSessionRetry(active, accountId, generation, epoch) { s ->
                    fetchDetail(s, activity)
                } ?: return@withLock
                if (!stillValid(accountId, generation, epoch)) return@withLock
                _uiState.update {
                    it.copy(
                        isLoadingDetail = false,
                        selectedDetail = detail,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!stillValid(accountId, generation, epoch)) return@withLock
                _uiState.update {
                    it.copy(
                        isLoadingDetail = false,
                        errorMessage = e.message ?: "读取活动详情失败",
                    )
                }
            }
        }
    }

    fun dismissDetail() {
        _uiState.update {
            it.copy(selectedActivity = null, selectedDetail = null, isLoadingDetail = false)
        }
    }

    suspend fun adoptWebViewCookies(
        cookies: Map<String, String>,
        context: ControlledWebViewContract.SessionContext,
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            val epoch = businessEpoch.get()
            val state = accountStateProvider()
            val accountId = state.accountId
            if (state.status != AccountStatus.SAVED_UNVERIFIED ||
                accountId == null ||
                !context.isStillValidFor(state) ||
                !matchesAccountGeneration(accountId, state.generation)
            ) {
                clearWebViewCookies()
                return@withLock
            }
            if (cookies.isEmpty()) {
                _uiState.update {
                    it.copy(
                        connectionStatus = ShiguangConnectionStatus.ERROR,
                        errorMessage = "未从页面获取到石光会话 Cookie，请重试",
                        interactionRequest = null,
                    )
                }
                return@withLock
            }
            val expectedLogin = readLoginName()
            if (expectedLogin.isNullOrBlank()) {
                clearWebViewCookies()
                return@withLock
            }
            val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val outcome = runCatching {
                authenticator.verifyIdentityWithCookieHeader(cookieHeader, expectedLogin)
            }.getOrDefault(UpcShiguangAuthenticator.IdentityCheckOutcome.Unverified)

            if (outcome == UpcShiguangAuthenticator.IdentityCheckOutcome.Mismatch) {
                clearWebViewCookies()
                _uiState.update {
                    it.copy(
                        connectionStatus = ShiguangConnectionStatus.ERROR,
                        errorMessage = "页面登录学号与当前学校账号不一致，已拒绝采纳",
                        interactionRequest = null,
                    )
                }
                return@withLock
            }

            session = InMemorySession(
                cookies = cookies,
                identityVerified = outcome == UpcShiguangAuthenticator.IdentityCheckOutcome.Verified,
                accountId = accountId,
                generation = state.generation,
                epoch = epoch,
            )
            clearWebViewCookies()
            _uiState.update { it.copy(interactionRequest = null) }
        }
        refresh()
    }

    fun dismissInteractionPrompt() {
        clearWebViewCookies()
        _uiState.update {
            it.copy(
                interactionRequest = null,
                connectionStatus = if (it.connectionStatus == ShiguangConnectionStatus.INTERACTION_REQUIRED) {
                    ShiguangConnectionStatus.IDLE
                } else {
                    it.connectionStatus
                },
            )
        }
    }

    fun onAccountInvalidated(reason: AccountInvalidationReason) {
        businessEpoch.incrementAndGet()
        session = null
        clearWebViewCookies()
        val state = accountStateProvider()
        val status = when (state.status) {
            AccountStatus.UNSET -> ShiguangConnectionStatus.UNCONFIGURED
            AccountStatus.RECOVERY_REQUIRED -> ShiguangConnectionStatus.RECOVERY_REQUIRED
            AccountStatus.SAVED_UNVERIFIED -> ShiguangConnectionStatus.IDLE
        }
        val msg = when (reason) {
            AccountInvalidationReason.ACCOUNT_CHANGED_OR_CLEARED -> "学校账号已切换或清空，石光二课会话已清除"
            AccountInvalidationReason.CREDENTIALS_UPDATED -> "账号凭据已更新，请重新同步石光二课"
        }
        _uiState.value = ShiguangUiState(connectionStatus = status, statusMessage = msg)
    }

    private suspend fun ensureSessionLocked(
        accountId: String,
        generation: Long,
        epoch: Long,
    ): InMemorySession? {
        val current = session
        if (current != null &&
            current.accountId == accountId &&
            current.generation == generation &&
            current.epoch == epoch
        ) {
            return current
        }
        _uiState.update { it.copy(connectionStatus = ShiguangConnectionStatus.AUTHENTICATING) }
        val authResult = credentialExecutor { cred -> authenticator.authenticate(cred) }
        if (!stillValid(accountId, generation, epoch)) return null

        return when (authResult) {
            null -> {
                syncAccountStatus()
                _uiState.update { it.copy(isLoading = false) }
                null
            }
            is ShiguangAuthResult.Success -> {
                InMemorySession(
                    cookies = authResult.cookies,
                    identityVerified = authResult.identityVerified,
                    accountId = accountId,
                    generation = generation,
                    epoch = epoch,
                ).also { session = it }
            }
            is ShiguangAuthResult.InvalidCredentials -> {
                _uiState.update {
                    it.copy(
                        connectionStatus = ShiguangConnectionStatus.INVALID_CREDENTIALS,
                        isLoading = false,
                        errorMessage = "学校账号或密码有误，请前往「学校账号」检查",
                    )
                }
                null
            }
            is ShiguangAuthResult.IdentityMismatch -> {
                _uiState.update {
                    it.copy(
                        connectionStatus = ShiguangConnectionStatus.ERROR,
                        isLoading = false,
                        errorMessage = "石光登录身份与当前学号不一致",
                    )
                }
                null
            }
            is ShiguangAuthResult.InteractionRequired -> {
                _uiState.update {
                    it.copy(
                        connectionStatus = ShiguangConnectionStatus.INTERACTION_REQUIRED,
                        isLoading = false,
                        interactionRequest = ShiguangInteractionRequest(
                            sessionContext = ControlledWebViewContract.SessionContext(
                                accountId = accountId,
                                generation = generation,
                                entryUrl = UpcShiguangAuthEndpoints.LOGIN_URL,
                            ),
                            reason = authResult.reason,
                        ),
                    )
                }
                null
            }
            is ShiguangAuthResult.NetworkError -> {
                _uiState.update {
                    it.copy(
                        connectionStatus = ShiguangConnectionStatus.ERROR,
                        isLoading = false,
                        errorMessage = authResult.message,
                    )
                }
                null
            }
            is ShiguangAuthResult.UpstreamError -> {
                _uiState.update {
                    it.copy(
                        connectionStatus = ShiguangConnectionStatus.ERROR,
                        isLoading = false,
                        errorMessage = authResult.message,
                    )
                }
                null
            }
        }
    }

    private suspend fun <T> withSessionRetry(
        initial: InMemorySession,
        accountId: String,
        generation: Long,
        epoch: Long,
        block: suspend (InMemorySession) -> T,
    ): T? {
        return try {
            block(initial)
        } catch (expired: ShiguangException) {
            if (!expired.authRequired) throw expired
            session = null
            if (!stillValid(accountId, generation, epoch)) return null
            val fresh = ensureSessionLocked(accountId, generation, epoch) ?: return null
            block(fresh)
        }
    }

    private suspend fun fetchPage(
        active: InMemorySession,
        page: Int,
        keyword: String,
        mine: Boolean,
    ): ShiguangPage {
        val path = if (mine) ShiguangProtocol.PARTICIPATIONS_PATH else ShiguangProtocol.ACTIVITIES_PATH
        val params = linkedMapOf("page" to page.toString())
        if (!mine) {
            params["oper"] = "1"
            if (keyword.isNotEmpty()) params["searchWord"] = keyword
        }
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(v, StandardCharsets.UTF_8.name())}"
        }
        val html = getHtml(active, "${ShiguangProtocol.BASE_URL}$path?$query")
        return ShiguangProtocol.parseList(html, page)
    }

    private suspend fun fetchDetail(
        active: InMemorySession,
        activity: ShiguangActivity,
    ): ShiguangDetail {
        val path = when (activity.kind) {
            ShiguangKind.ACTIVITY -> ShiguangProtocol.ACTIVITY_DETAIL_PATH
            ShiguangKind.SOCIAL_PRACTICE -> ShiguangProtocol.PRACTICE_DETAIL_PATH
        }
        val encodedId = URLEncoder.encode(activity.id, StandardCharsets.UTF_8.name())
        val html = getHtml(active, "${ShiguangProtocol.BASE_URL}$path?actvModel.activityId=$encodedId")
        return ShiguangProtocol.parseDetail(html)
    }

    private suspend fun fetchSummary(active: InMemorySession): ShiguangSummary {
        val html = getHtml(active, "${ShiguangProtocol.BASE_URL}${ShiguangProtocol.SUMMARY_PATH}")
        return ShiguangProtocol.parseSummary(html)
    }

    private suspend fun getHtml(active: InMemorySession, url: String): String {
        val response = try {
            transport.execute(
                CasHttpRequest(
                    url = url,
                    method = "GET",
                    headers = mapOf(
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Cookie" to active.cookieHeader(),
                    ),
                ),
            )
        } catch (failure: CasTransportException) {
            throw ShiguangException(failure.message ?: "无法连接石光二课平台", cause = failure)
        }
        when {
            response.statusCode == 401 -> throw ShiguangException("石光二课登录已失效", authRequired = true)
            response.statusCode in 300..399 -> {
                val loc = response.location.orEmpty()
                if ("/cas/login" in loc || "caslogin.jsp" in loc) {
                    throw ShiguangException("石光二课登录已失效", authRequired = true)
                }
                throw ShiguangException("石光服务返回异常跳转")
            }
            response.statusCode != 200 -> throw ShiguangException("石光服务返回 HTTP ${response.statusCode}")
        }
        val ct = response.contentType.orEmpty().lowercase()
        if ("json" in ct) {
            val root = runCatching { Json.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
            val out = (root?.get("sessionOutFlag") as? JsonPrimitive)?.contentOrNull
            throw ShiguangException(
                message = "石光二课登录已失效，请刷新",
                authRequired = out.equals("true", ignoreCase = true),
            )
        }
        return response.body
    }

    private fun stillValid(accountId: String, generation: Long, epoch: Long): Boolean =
        businessEpoch.get() == epoch && matchesAccountGeneration(accountId, generation)
}
