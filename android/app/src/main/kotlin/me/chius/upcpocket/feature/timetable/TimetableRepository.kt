package me.chius.upcpocket.feature.timetable

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
import me.chius.upcpocket.core.auth.CasHttpRequest
import me.chius.upcpocket.core.auth.CasHttpTransport
import me.chius.upcpocket.core.auth.CasTransportException
import me.chius.upcpocket.core.auth.CasTransportFailure
import me.chius.upcpocket.core.auth.DefaultCasHttpTransport
import me.chius.upcpocket.core.auth.TimetableAuthResult
import me.chius.upcpocket.core.auth.TimetableVerifyResult
import me.chius.upcpocket.core.auth.UpcTimetableAuthEndpoints
import me.chius.upcpocket.core.auth.UpcTimetableAuthenticator
import me.chius.upcpocket.core.data.SchoolAccountRepository
import me.chius.upcpocket.core.model.AccountInvalidationReason
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.model.SchoolLoginCredential
import me.chius.upcpocket.core.webview.ControlledWebViewContract
import me.chius.upcpocket.core.webview.clearSchoolWebViewCookies

/**
 * Orchestrates read-only CAS authentication, verified `app.upc.edu.cn` session cookies,
 * single-shot auth recovery on `e == 10013`, controlled WebView adoption, and per-account
 * offline timetable caching.
 */
class TimetableRepository internal constructor(
    private val accountStateProvider: () -> SchoolAccountState,
    private val matchesAccountGeneration: (String, Long) -> Boolean,
    private val credentialExecutor: suspend (suspend (SchoolLoginCredential) -> TimetableAuthResult) -> TimetableAuthResult?,
    private val sessionStore: TimetableSessionStore,
    private val cacheStore: TimetableCacheStore,
    private val authenticator: UpcTimetableAuthenticator,
    private val transport: CasHttpTransport = DefaultCasHttpTransport,
    private val clearWebViewCookies: () -> Unit = ::clearSchoolWebViewCookies,
    private val clock: () -> Long = System::currentTimeMillis,
    private val todayProvider: () -> LocalDate = { LocalDate.now(ZoneId.of("Asia/Shanghai")) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        context: Context,
        schoolAccountRepository: SchoolAccountRepository,
        transport: CasHttpTransport = DefaultCasHttpTransport,
    ) : this(
        accountStateProvider = { schoolAccountRepository.state.value },
        matchesAccountGeneration = schoolAccountRepository::matchesAccountGeneration,
        credentialExecutor = { block -> schoolAccountRepository.withLoginCredential(block) },
        sessionStore = TimetableSessionStore(context),
        cacheStore = TimetableCacheStore(context),
        authenticator = UpcTimetableAuthenticator(transport),
        transport = transport,
    )

    private val refreshMutex = Mutex()
    private val businessEpoch = AtomicLong(0L)
    @Volatile
    private var explicitSelection = false

    private val _uiState = MutableStateFlow(TimetableUiState())
    val uiState: StateFlow<TimetableUiState> = _uiState.asStateFlow()

    suspend fun restore() = withContext(ioDispatcher) {
        explicitSelection = false
        val state = accountStateProvider()
        val epoch = businessEpoch.get()
        when (state.status) {
            AccountStatus.UNSET -> {
                _uiState.value = TimetableUiState(
                    connectionStatus = TimetableConnectionStatus.UNCONFIGURED,
                    statusDetail = "请先前往「学校账号」保存统一认证凭据",
                )
            }
            AccountStatus.RECOVERY_REQUIRED -> {
                _uiState.value = TimetableUiState(
                    connectionStatus = TimetableConnectionStatus.RECOVERY_REQUIRED,
                    statusDetail = "学校账号加密状态需要重新保存，暂不可同步课表",
                )
            }
            AccountStatus.SAVED_UNVERIFIED -> {
                val accountId = state.accountId ?: return@withContext
                val cache = cacheStore.load(accountId)
                if (!matchesAccountGeneration(accountId, state.generation) ||
                    businessEpoch.get() != epoch
                ) {
                    return@withContext
                }
                val terms = cache?.index?.terms.orEmpty()
                val preferredTerm = cache?.index?.currentTermOrNull()
                    ?: terms.firstOrNull()
                    ?: cache?.weeks?.firstOrNull()?.term
                val preferredWeek = (cache?.index?.currentWeek ?: cache?.weeks?.firstOrNull()?.week)
                    ?.let { w -> preferredTerm?.let { w.coerceIn(1, it.totalWeeks) } ?: w }
                val cachedWeek = findCachedWeek(cache, preferredTerm, preferredWeek)
                val todaySummary = computeTodaySummary(cache, isOffline = true)

                _uiState.value = TimetableUiState(
                    connectionStatus = if (cachedWeek != null || cache?.index != null) {
                        TimetableConnectionStatus.OFFLINE_CACHE
                    } else {
                        TimetableConnectionStatus.IDLE
                    },
                    terms = terms,
                    selectedTerm = preferredTerm,
                    selectedWeek = preferredWeek,
                    currentSchoolWeek = cache?.index?.currentWeek,
                    weekData = cachedWeek,
                    todaySummary = todaySummary,
                    isOfflineFallback = cachedWeek != null,
                    fetchedAtEpochMillis = cachedWeek?.fetchedAtEpochMillis ?: cache?.cachedAtEpochMillis,
                    statusDetail = if (cachedWeek != null) {
                        "已加载本机离线课表缓存，点击刷新可从学校同步最新数据"
                    } else {
                        "已保存学校账号（未验证），点击「同步课表」获取当前教学周"
                    },
                )
            }
        }
    }

    suspend fun refresh() = withContext(ioDispatcher) {
        refreshMutex.withLock {
            val state = accountStateProvider()
            val epoch = businessEpoch.get()
            if (state.status != AccountStatus.SAVED_UNVERIFIED || state.accountId == null) {
                publishAccountBlockedState(state)
                return@withLock
            }
            val accountId = state.accountId
            val generation = state.generation
            val existingCache = cacheStore.load(accountId)
            val currentUi = _uiState.value

            _uiState.update {
                it.copy(
                    isRefreshing = true,
                    errorMessage = null,
                    statusDetail = "正在同步学校课表...",
                )
            }

            when (val ensureResult = ensureIndexAndSession(accountId, generation, epoch)) {
                is EnsureSessionOutcome.AbortedForAccountChange -> {
                    _uiState.update { it.copy(isRefreshing = false) }
                }
                is EnsureSessionOutcome.Failed -> {
                    if (!stillValid(accountId, generation, epoch)) return@withLock
                    applyAuthOrFetchFailure(
                        cache = existingCache,
                        failure = ensureResult,
                        accountId = accountId,
                        generation = generation,
                    )
                }
                is EnsureSessionOutcome.Ready -> {
                    if (!stillValid(accountId, generation, epoch)) return@withLock
                    val index = ensureResult.index
                    val targetTerm = resolveTargetTerm(
                        index = index,
                        preferredTerm = if (explicitSelection) currentUi.selectedTerm else null,
                    )
                    val targetWeek = resolveTargetWeek(
                        index = index,
                        term = targetTerm,
                        preferredWeek = if (explicitSelection) currentUi.selectedWeek else null,
                    )
                    val now = clock().coerceAtLeast(1L)
                    cacheStore.save(accountId, index = index, week = null, nowEpochMillis = now)

                    fetchAndCommitWeek(
                        accountId = accountId,
                        generation = generation,
                        epoch = epoch,
                        initialCookies = ensureResult.cookies,
                        index = index,
                        term = targetTerm,
                        week = targetWeek,
                        allowReauth = !ensureResult.freshlyAuthenticated,
                    )
                }
            }
        }
    }

    suspend fun selectWeek(term: TimetableTermInfo, week: Int) = withContext(ioDispatcher) {
        if (week !in 1..term.totalWeeks) return@withContext
        refreshMutex.withLock {
            explicitSelection = true
            val state = accountStateProvider()
            val epoch = businessEpoch.get()
            if (state.status != AccountStatus.SAVED_UNVERIFIED || state.accountId == null) {
                publishAccountBlockedState(state)
                return@withLock
            }
            val accountId = state.accountId
            val generation = state.generation
            val cache = cacheStore.load(accountId)
            val cachedWeek = findCachedWeek(cache, term, week)

            _uiState.update {
                it.copy(
                    selectedTerm = term,
                    selectedWeek = week,
                    weekData = cachedWeek,
                    isRefreshing = true,
                    isOfflineFallback = cachedWeek != null,
                    errorMessage = null,
                    fetchedAtEpochMillis = cachedWeek?.fetchedAtEpochMillis,
                    statusDetail = "正在加载 ${term.label} 第 $week 周...",
                )
            }

            val existingSession = sessionStore.load(accountId, generation)
            var cookies = existingSession?.cookies
            var freshlyAuthenticated = false

            if (cookies == null) {
                when (val ensure = ensureIndexAndSession(accountId, generation, epoch)) {
                    is EnsureSessionOutcome.AbortedForAccountChange -> {
                        _uiState.update { it.copy(isRefreshing = false) }
                        return@withLock
                    }
                    is EnsureSessionOutcome.Failed -> {
                        if (!stillValid(accountId, generation, epoch)) return@withLock
                        applyAuthOrFetchFailure(
                            cache = cache,
                            failure = ensure,
                            accountId = accountId,
                            generation = generation,
                            selectedTermOverride = term,
                            selectedWeekOverride = week,
                        )
                        return@withLock
                    }
                    is EnsureSessionOutcome.Ready -> {
                        cookies = ensure.cookies
                        freshlyAuthenticated = ensure.freshlyAuthenticated
                    }
                }
            }

            if (!stillValid(accountId, generation, epoch)) return@withLock
            val indexForState = cache?.index ?: TimetableIndex(terms = _uiState.value.terms.ifEmpty { listOf(term) })
            fetchAndCommitWeek(
                accountId = accountId,
                generation = generation,
                epoch = epoch,
                initialCookies = cookies,
                index = indexForState,
                term = term,
                week = week,
                allowReauth = !freshlyAuthenticated,
            )
        }
    }

    suspend fun adoptWebViewCookies(
        cookies: Map<String, String>,
        sessionContext: ControlledWebViewContract.SessionContext,
    ) = withContext(ioDispatcher) {
        clearWebViewCookies()
        refreshMutex.withLock {
            val state = accountStateProvider()
            val epoch = businessEpoch.get()
            if (!sessionContext.isStillValidFor(state) ||
                state.status != AccountStatus.SAVED_UNVERIFIED ||
                state.accountId == null
            ) {
                _uiState.update {
                    it.copy(
                        interactionSession = null,
                        interactionReason = null,
                        isRefreshing = false,
                    )
                }
                return@withLock
            }

            val accountId = state.accountId
            val generation = state.generation
            val cache = cacheStore.load(accountId)

            _uiState.update {
                it.copy(
                    interactionSession = null,
                    interactionReason = null,
                    isRefreshing = true,
                    errorMessage = null,
                    statusDetail = "正在验证交互登录会话...",
                )
            }

            when (val verified = authenticator.verifySessionCookies(cookies)) {
                is TimetableVerifyResult.Valid -> {
                    if (!stillValid(accountId, generation, epoch)) return@withLock
                    when (val indexParsed = TimetableProtocol.parseIndex(verified.indexBody)) {
                        is TimetableParseResult.Ok -> {
                            val now = clock().coerceAtLeast(1L)
                            sessionStore.save(
                                TimetableSession(
                                    accountId = accountId,
                                    credentialGeneration = generation,
                                    savedAtEpochMillis = now,
                                    cookies = cookies,
                                ),
                            )
                            val index = indexParsed.value
                            cacheStore.save(accountId, index = index, week = null, nowEpochMillis = now)
                            val currentUi = _uiState.value
                            val targetTerm = resolveTargetTerm(index, currentUi.selectedTerm)
                            val targetWeek = resolveTargetWeek(index, targetTerm, currentUi.selectedWeek)
                            fetchAndCommitWeek(
                                accountId = accountId,
                                generation = generation,
                                epoch = epoch,
                                initialCookies = cookies,
                                index = index,
                                term = targetTerm,
                                week = targetWeek,
                                allowReauth = false,
                            )
                        }
                        TimetableParseResult.AuthRequired -> {
                            applyAuthOrFetchFailure(
                                cache = cache,
                                failure = EnsureSessionOutcome.Failed(
                                    status = TimetableConnectionStatus.INTERACTION_REQUIRED,
                                    message = "网页会话尚未建立，请重新验证",
                                    interactionUrl = UpcTimetableAuthEndpoints.LOGIN_URL,
                                    interactionReason = "网页登录未生效",
                                ),
                                accountId = accountId,
                                generation = generation,
                            )
                        }
                        is TimetableParseResult.UpstreamError -> {
                            applyAuthOrFetchFailure(
                                cache = cache,
                                failure = EnsureSessionOutcome.Failed(
                                    status = TimetableConnectionStatus.ERROR,
                                    message = indexParsed.message,
                                ),
                                accountId = accountId,
                                generation = generation,
                            )
                        }
                    }
                }
                TimetableVerifyResult.AuthRequired -> {
                    if (!stillValid(accountId, generation, epoch)) return@withLock
                    applyAuthOrFetchFailure(
                        cache = cache,
                        failure = EnsureSessionOutcome.Failed(
                            status = TimetableConnectionStatus.INTERACTION_REQUIRED,
                            message = "交互登录后尚未取得有效的课表会话",
                            interactionUrl = UpcTimetableAuthEndpoints.LOGIN_URL,
                            interactionReason = "需要完成学校身份验证",
                        ),
                        accountId = accountId,
                        generation = generation,
                    )
                }
                is TimetableVerifyResult.NetworkError -> {
                    if (!stillValid(accountId, generation, epoch)) return@withLock
                    applyAuthOrFetchFailure(
                        cache = cache,
                        failure = EnsureSessionOutcome.Failed(
                            status = TimetableConnectionStatus.ERROR,
                            message = verified.message,
                        ),
                        accountId = accountId,
                        generation = generation,
                    )
                }
                is TimetableVerifyResult.UpstreamError -> {
                    if (!stillValid(accountId, generation, epoch)) return@withLock
                    applyAuthOrFetchFailure(
                        cache = cache,
                        failure = EnsureSessionOutcome.Failed(
                            status = TimetableConnectionStatus.ERROR,
                            message = verified.message,
                        ),
                        accountId = accountId,
                        generation = generation,
                    )
                }
            }
        }
    }

    fun dismissInteractionPrompt() {
        _uiState.update {
            it.copy(
                interactionSession = null,
                interactionReason = null,
            )
        }
    }

    suspend fun onAccountInvalidated(reason: AccountInvalidationReason) = withContext(ioDispatcher) {
        businessEpoch.incrementAndGet()
        explicitSelection = false
        clearWebViewCookies()
        sessionStore.clear()
        when (reason) {
            AccountInvalidationReason.ACCOUNT_CHANGED_OR_CLEARED -> {
                cacheStore.clear()
                val state = accountStateProvider()
                publishAccountBlockedState(state)
            }
            AccountInvalidationReason.CREDENTIALS_UPDATED -> {
                val state = accountStateProvider()
                val accountId = state.accountId
                val cache = if (accountId != null) cacheStore.load(accountId) else null
                val terms = cache?.index?.terms.orEmpty()
                val selectedTerm = _uiState.value.selectedTerm ?: cache?.index?.currentTermOrNull() ?: terms.firstOrNull()
                val selectedWeek = _uiState.value.selectedWeek ?: cache?.index?.currentWeek
                val cachedWeek = findCachedWeek(cache, selectedTerm, selectedWeek)
                _uiState.value = TimetableUiState(
                    connectionStatus = if (cachedWeek != null || cache?.index != null) {
                        TimetableConnectionStatus.OFFLINE_CACHE
                    } else {
                        TimetableConnectionStatus.IDLE
                    },
                    terms = terms,
                    selectedTerm = selectedTerm,
                    selectedWeek = selectedWeek,
                    currentSchoolWeek = cache?.index?.currentWeek,
                    weekData = cachedWeek,
                    todaySummary = computeTodaySummary(cache, isOffline = true),
                    isOfflineFallback = cachedWeek != null,
                    fetchedAtEpochMillis = cachedWeek?.fetchedAtEpochMillis ?: cache?.cachedAtEpochMillis,
                    statusDetail = "密码已更新，原学校会话已清除；可点击刷新重新建立会话",
                )
            }
        }
    }

    suspend fun clearLocalCache() = withContext(ioDispatcher) {
        businessEpoch.incrementAndGet()
        cacheStore.clear()
        val state = accountStateProvider()
        if (state.status != AccountStatus.SAVED_UNVERIFIED) {
            publishAccountBlockedState(state)
            return@withContext
        }
        _uiState.update {
            it.copy(
                connectionStatus = TimetableConnectionStatus.IDLE,
                terms = emptyList(),
                selectedTerm = null,
                selectedWeek = null,
                currentSchoolWeek = null,
                weekData = null,
                todaySummary = null,
                isOfflineFallback = false,
                fetchedAtEpochMillis = null,
                errorMessage = null,
                statusDetail = "已清除本机课表离线缓存",
            )
        }
    }

    private suspend fun ensureIndexAndSession(
        accountId: String,
        generation: Long,
        epoch: Long,
    ): EnsureSessionOutcome {
        val existingSession = sessionStore.load(accountId, generation)
        if (existingSession != null) {
            when (val verified = authenticator.verifySessionCookies(existingSession.cookies)) {
                is TimetableVerifyResult.Valid -> {
                    if (!stillValid(accountId, generation, epoch)) {
                        return EnsureSessionOutcome.AbortedForAccountChange
                    }
                    return when (val parsed = TimetableProtocol.parseIndex(verified.indexBody)) {
                        is TimetableParseResult.Ok -> EnsureSessionOutcome.Ready(
                            cookies = existingSession.cookies,
                            index = parsed.value,
                            freshlyAuthenticated = false,
                        )
                        TimetableParseResult.AuthRequired -> {
                            sessionStore.clear()
                            authenticateOnceForIndex(accountId, generation, epoch)
                        }
                        is TimetableParseResult.UpstreamError -> EnsureSessionOutcome.Failed(
                            status = TimetableConnectionStatus.ERROR,
                            message = parsed.message,
                        )
                    }
                }
                TimetableVerifyResult.AuthRequired -> {
                    sessionStore.clear()
                }
                is TimetableVerifyResult.NetworkError -> {
                    return EnsureSessionOutcome.Failed(
                        status = TimetableConnectionStatus.ERROR,
                        message = verified.message,
                    )
                }
                is TimetableVerifyResult.UpstreamError -> {
                    return EnsureSessionOutcome.Failed(
                        status = TimetableConnectionStatus.ERROR,
                        message = verified.message,
                    )
                }
            }
        }
        return authenticateOnceForIndex(accountId, generation, epoch)
    }

    private suspend fun authenticateOnceForIndex(
        accountId: String,
        generation: Long,
        epoch: Long,
    ): EnsureSessionOutcome {
        if (!stillValid(accountId, generation, epoch)) {
            return EnsureSessionOutcome.AbortedForAccountChange
        }
        val authResult = credentialExecutor { credential ->
            if (credential.accountId != accountId || credential.generation != generation) {
                TimetableAuthResult.UpstreamError("学校账号已发生变更")
            } else {
                authenticator.authenticate(credential)
            }
        } ?: return EnsureSessionOutcome.AbortedForAccountChange

        if (!stillValid(accountId, generation, epoch)) {
            return EnsureSessionOutcome.AbortedForAccountChange
        }

        return when (authResult) {
            TimetableAuthResult.InvalidCredentials -> {
                sessionStore.clear()
                EnsureSessionOutcome.Failed(
                    status = TimetableConnectionStatus.INVALID_CREDENTIALS,
                    message = "学号或统一认证密码错误，请前往「学校账号」核对并重新保存",
                )
            }
            is TimetableAuthResult.InteractionRequired -> {
                EnsureSessionOutcome.Failed(
                    status = TimetableConnectionStatus.INTERACTION_REQUIRED,
                    message = "学校统一认证要求交互验证：${authResult.reason}",
                    interactionUrl = authResult.interactionUrl,
                    interactionReason = authResult.reason,
                )
            }
            is TimetableAuthResult.NetworkError -> EnsureSessionOutcome.Failed(
                status = TimetableConnectionStatus.ERROR,
                message = authResult.message,
            )
            is TimetableAuthResult.UpstreamError -> EnsureSessionOutcome.Failed(
                status = TimetableConnectionStatus.ERROR,
                message = authResult.message,
            )
            is TimetableAuthResult.Success -> {
                when (val parsed = TimetableProtocol.parseIndex(authResult.indexBody)) {
                    is TimetableParseResult.Ok -> {
                        val now = clock().coerceAtLeast(1L)
                        sessionStore.save(
                            TimetableSession(
                                accountId = accountId,
                                credentialGeneration = generation,
                                savedAtEpochMillis = now,
                                cookies = authResult.cookies,
                            ),
                        )
                        EnsureSessionOutcome.Ready(
                            cookies = authResult.cookies,
                            index = parsed.value,
                            freshlyAuthenticated = true,
                        )
                    }
                    TimetableParseResult.AuthRequired -> EnsureSessionOutcome.Failed(
                        status = TimetableConnectionStatus.INTERACTION_REQUIRED,
                        message = "登录回调未生效，请尝试通过安全页面完成登录",
                        interactionUrl = UpcTimetableAuthEndpoints.LOGIN_URL,
                        interactionReason = "需要交互完成认证",
                    )
                    is TimetableParseResult.UpstreamError -> EnsureSessionOutcome.Failed(
                        status = TimetableConnectionStatus.ERROR,
                        message = parsed.message,
                    )
                }
            }
        }
    }

    private suspend fun fetchAndCommitWeek(
        accountId: String,
        generation: Long,
        epoch: Long,
        initialCookies: Map<String, String>,
        index: TimetableIndex,
        term: TimetableTermInfo,
        week: Int,
        allowReauth: Boolean,
    ) {
        var activeCookies = initialCookies
        var reauthAttempted = !allowReauth

        while (true) {
            val cookieHeader = UpcTimetableAuthenticator.formatCookieHeader(activeCookies)
            if (cookieHeader == null) {
                sessionStore.clear()
                if (!reauthAttempted) {
                    reauthAttempted = true
                    when (val reauth = authenticateOnceForIndex(accountId, generation, epoch)) {
                        is EnsureSessionOutcome.AbortedForAccountChange -> return
                        is EnsureSessionOutcome.Failed -> {
                            val cache = cacheStore.load(accountId)
                            applyAuthOrFetchFailure(cache, reauth, accountId, generation, index, term, week)
                            return
                        }
                        is EnsureSessionOutcome.Ready -> {
                            activeCookies = reauth.cookies
                            continue
                        }
                    }
                }
                val cache = cacheStore.load(accountId)
                applyAuthOrFetchFailure(
                    cache = cache,
                    failure = EnsureSessionOutcome.Failed(
                        status = TimetableConnectionStatus.ERROR,
                        message = "课表会话失效，请重试",
                    ),
                    accountId = accountId,
                    generation = generation,
                    indexOverride = index,
                    selectedTermOverride = term,
                    selectedWeekOverride = week,
                )
                return
            }

            val formBody = DefaultCasHttpTransport.formEncode(
                listOf(
                    "year" to term.year,
                    "term" to term.term,
                    "week" to week.toString(),
                    "type" to "2",
                ),
            )
            val response = try {
                transport.execute(
                    CasHttpRequest(
                        url = UpcTimetableAuthEndpoints.WEEK_URL,
                        method = "POST",
                        headers = mapOf(
                            "Accept" to "application/json, text/plain, */*",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to UpcTimetableAuthEndpoints.PAGE_URL,
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "Cookie" to cookieHeader,
                        ),
                        formBody = formBody,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CasTransportException) {
                if (!stillValid(accountId, generation, epoch)) return
                val cache = cacheStore.load(accountId)
                applyAuthOrFetchFailure(
                    cache = cache,
                    failure = EnsureSessionOutcome.Failed(
                        status = TimetableConnectionStatus.ERROR,
                        message = failure.message ?: "网络请求失败，请检查校园网络",
                    ),
                    accountId = accountId,
                    generation = generation,
                    indexOverride = index,
                    selectedTermOverride = term,
                    selectedWeekOverride = week,
                )
                return
            }

            if (response.statusCode in 300..399 || response.statusCode == 401) {
                sessionStore.clear()
                if (!reauthAttempted) {
                    reauthAttempted = true
                    when (val reauth = authenticateOnceForIndex(accountId, generation, epoch)) {
                        is EnsureSessionOutcome.AbortedForAccountChange -> return
                        is EnsureSessionOutcome.Failed -> {
                            val cache = cacheStore.load(accountId)
                            applyAuthOrFetchFailure(cache, reauth, accountId, generation, index, term, week)
                            return
                        }
                        is EnsureSessionOutcome.Ready -> {
                            activeCookies = reauth.cookies
                            continue
                        }
                    }
                }
                val cache = cacheStore.load(accountId)
                applyAuthOrFetchFailure(
                    cache = cache,
                    failure = EnsureSessionOutcome.Failed(
                        status = TimetableConnectionStatus.INTERACTION_REQUIRED,
                        message = "学校会话已过期，请通过交互页面重新认证",
                        interactionUrl = UpcTimetableAuthEndpoints.LOGIN_URL,
                        interactionReason = "会话已过期",
                    ),
                    accountId = accountId,
                    generation = generation,
                    indexOverride = index,
                    selectedTermOverride = term,
                    selectedWeekOverride = week,
                )
                return
            }

            if (response.statusCode != 200) {
                if (!stillValid(accountId, generation, epoch)) return
                val cache = cacheStore.load(accountId)
                applyAuthOrFetchFailure(
                    cache = cache,
                    failure = EnsureSessionOutcome.Failed(
                        status = TimetableConnectionStatus.ERROR,
                        message = "周课表接口返回 HTTP ${response.statusCode}",
                    ),
                    accountId = accountId,
                    generation = generation,
                    indexOverride = index,
                    selectedTermOverride = term,
                    selectedWeekOverride = week,
                )
                return
            }

            val now = clock().coerceAtLeast(1L)
            when (val parsed = TimetableProtocol.parseWeek(response.body, term, week, fetchedAtEpochMillis = now)) {
                is TimetableParseResult.Ok -> {
                    if (!stillValid(accountId, generation, epoch)) return
                    cacheStore.save(
                        accountId = accountId,
                        index = index,
                        week = parsed.value,
                        nowEpochMillis = now,
                    )
                    val refreshedCache = cacheStore.load(accountId)
                    if (!stillValid(accountId, generation, epoch)) return
                    val todaySummary = computeTodaySummary(refreshedCache, isOffline = false)
                    _uiState.value = TimetableUiState(
                        connectionStatus = TimetableConnectionStatus.ONLINE,
                        terms = index.terms,
                        selectedTerm = term,
                        selectedWeek = week,
                        currentSchoolWeek = index.currentWeek,
                        weekData = parsed.value,
                        todaySummary = todaySummary,
                        isRefreshing = false,
                        isOfflineFallback = false,
                        fetchedAtEpochMillis = now,
                        statusDetail = "已同步 ${term.label} · 第 $week 周",
                    )
                    return
                }
                TimetableParseResult.AuthRequired -> {
                    sessionStore.clear()
                    if (!reauthAttempted) {
                        reauthAttempted = true
                        when (val reauth = authenticateOnceForIndex(accountId, generation, epoch)) {
                            is EnsureSessionOutcome.AbortedForAccountChange -> return
                            is EnsureSessionOutcome.Failed -> {
                                val cache = cacheStore.load(accountId)
                                applyAuthOrFetchFailure(cache, reauth, accountId, generation, index, term, week)
                                return
                            }
                            is EnsureSessionOutcome.Ready -> {
                                activeCookies = reauth.cookies
                                continue
                            }
                        }
                    }
                    val cache = cacheStore.load(accountId)
                    applyAuthOrFetchFailure(
                        cache = cache,
                        failure = EnsureSessionOutcome.Failed(
                            status = TimetableConnectionStatus.INTERACTION_REQUIRED,
                            message = "需要重新通过学校认证页面获取有效会话",
                            interactionUrl = UpcTimetableAuthEndpoints.LOGIN_URL,
                            interactionReason = "会话已失效（10013）",
                        ),
                        accountId = accountId,
                        generation = generation,
                        indexOverride = index,
                        selectedTermOverride = term,
                        selectedWeekOverride = week,
                    )
                    return
                }
                is TimetableParseResult.UpstreamError -> {
                    if (!stillValid(accountId, generation, epoch)) return
                    val cache = cacheStore.load(accountId)
                    applyAuthOrFetchFailure(
                        cache = cache,
                        failure = EnsureSessionOutcome.Failed(
                            status = TimetableConnectionStatus.ERROR,
                            message = parsed.message,
                        ),
                        accountId = accountId,
                        generation = generation,
                        indexOverride = index,
                        selectedTermOverride = term,
                        selectedWeekOverride = week,
                    )
                    return
                }
            }
        }
    }

    private fun applyAuthOrFetchFailure(
        cache: TimetableOfflineCache?,
        failure: EnsureSessionOutcome.Failed,
        accountId: String,
        generation: Long,
        indexOverride: TimetableIndex? = null,
        selectedTermOverride: TimetableTermInfo? = null,
        selectedWeekOverride: Int? = null,
    ) {
        val effectiveIndex = indexOverride ?: cache?.index
        val terms = effectiveIndex?.terms ?: _uiState.value.terms
        val selectedTerm = selectedTermOverride
            ?: _uiState.value.selectedTerm
            ?: effectiveIndex?.currentTermOrNull()
            ?: terms.firstOrNull()
        val selectedWeek = selectedWeekOverride
            ?: _uiState.value.selectedWeek
            ?: effectiveIndex?.currentWeek
            ?: selectedTerm?.let { 1 }
        val cachedWeek = findCachedWeek(cache, selectedTerm, selectedWeek)
        val hasCachedWeek = cachedWeek != null

        val interactionSession = if (failure.status == TimetableConnectionStatus.INTERACTION_REQUIRED &&
            failure.interactionUrl != null
        ) {
            ControlledWebViewContract.SessionContext(
                accountId = accountId,
                generation = generation,
                entryUrl = failure.interactionUrl,
            )
        } else {
            null
        }

        val resolvedStatus = when {
            failure.status == TimetableConnectionStatus.INTERACTION_REQUIRED ->
                TimetableConnectionStatus.INTERACTION_REQUIRED
            failure.status == TimetableConnectionStatus.INVALID_CREDENTIALS ->
                TimetableConnectionStatus.INVALID_CREDENTIALS
            hasCachedWeek -> TimetableConnectionStatus.OFFLINE_CACHE
            else -> TimetableConnectionStatus.ERROR
        }

        _uiState.value = TimetableUiState(
            connectionStatus = resolvedStatus,
            terms = terms,
            selectedTerm = selectedTerm,
            selectedWeek = selectedWeek,
            currentSchoolWeek = effectiveIndex?.currentWeek,
            weekData = cachedWeek,
            todaySummary = computeTodaySummary(cache, isOffline = true),
            isRefreshing = false,
            isOfflineFallback = hasCachedWeek,
            fetchedAtEpochMillis = cachedWeek?.fetchedAtEpochMillis ?: cache?.cachedAtEpochMillis,
            statusDetail = if (hasCachedWeek) "当前显示本机缓存" else null,
            errorMessage = failure.message,
            interactionSession = interactionSession,
            interactionReason = failure.interactionReason,
        )
    }

    private fun publishAccountBlockedState(state: SchoolAccountState) {
        _uiState.value = when (state.status) {
            AccountStatus.UNSET -> TimetableUiState(
                connectionStatus = TimetableConnectionStatus.UNCONFIGURED,
                statusDetail = "请先前往「学校账号」配置统一认证凭据",
            )
            AccountStatus.RECOVERY_REQUIRED -> TimetableUiState(
                connectionStatus = TimetableConnectionStatus.RECOVERY_REQUIRED,
                statusDetail = "学校账号加密状态异常，请重新保存后再同步课表",
            )
            AccountStatus.SAVED_UNVERIFIED -> TimetableUiState(
                connectionStatus = TimetableConnectionStatus.IDLE,
                statusDetail = "点击「同步课表」获取当前教学周",
            )
        }
    }

    private fun stillValid(accountId: String, generation: Long, epoch: Long): Boolean =
        matchesAccountGeneration(accountId, generation) && businessEpoch.get() == epoch

    private fun resolveTargetTerm(
        index: TimetableIndex,
        preferredTerm: TimetableTermInfo?,
    ): TimetableTermInfo {
        if (preferredTerm != null) {
            index.findTerm(preferredTerm.year, preferredTerm.term)?.let { return it }
        }
        return index.currentTermOrNull() ?: index.terms.first()
    }

    private fun resolveTargetWeek(
        index: TimetableIndex,
        term: TimetableTermInfo,
        preferredWeek: Int?,
    ): Int {
        val isCurrentTerm = index.currentYear == term.year && index.currentTerm == term.term
        val candidate = when {
            preferredWeek != null && (!isCurrentTerm || preferredWeek != 1 || index.currentWeek == null) ->
                preferredWeek
            isCurrentTerm && index.currentWeek != null -> index.currentWeek
            preferredWeek != null -> preferredWeek
            else -> 1
        }
        return candidate.coerceIn(1, term.totalWeeks)
    }

    private fun findCachedWeek(
        cache: TimetableOfflineCache?,
        term: TimetableTermInfo?,
        week: Int?,
    ): TimetableWeek? {
        if (cache == null || term == null || week == null) return null
        return cache.weeks.firstOrNull {
            it.term.year == term.year && it.term.term == term.term && it.week == week
        }
    }

    private fun computeTodaySummary(
        cache: TimetableOfflineCache?,
        isOffline: Boolean,
    ): TodayCourseSummary? {
        if (cache == null) return null
        val todayIso = todayProvider().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val matchedWeek = cache.weeks
            .sortedByDescending { it.fetchedAtEpochMillis }
            .firstOrNull { week -> todayIso in week.datesIso }
            ?: return null
        val day = matchedWeek.days.firstOrNull { it.dateIso == todayIso }
        return TodayCourseSummary(
            dateIso = todayIso,
            weekNumber = matchedWeek.week,
            courses = day?.meetings.orEmpty(),
            isOfflineCache = isOffline,
            fetchedAtEpochMillis = matchedWeek.fetchedAtEpochMillis,
        )
    }

    private sealed interface EnsureSessionOutcome {
        data object AbortedForAccountChange : EnsureSessionOutcome
        data class Ready(
            val cookies: Map<String, String>,
            val index: TimetableIndex,
            val freshlyAuthenticated: Boolean,
        ) : EnsureSessionOutcome

        data class Failed(
            val status: TimetableConnectionStatus,
            val message: String,
            val interactionUrl: String? = null,
            val interactionReason: String? = null,
        ) : EnsureSessionOutcome
    }
}
