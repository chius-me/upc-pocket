package me.chius.upcpocket.feature.library

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
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
import me.chius.upcpocket.core.auth.CasHttpResponse
import me.chius.upcpocket.core.auth.CasHttpTransport
import me.chius.upcpocket.core.auth.CasTransportException
import me.chius.upcpocket.core.auth.DefaultCasHttpTransport
import me.chius.upcpocket.core.auth.UpcChaoxingAuthEndpoints
import me.chius.upcpocket.core.auth.UpcChaoxingAuthFailure
import me.chius.upcpocket.core.auth.UpcChaoxingAuthResult
import me.chius.upcpocket.core.auth.UpcChaoxingAuthenticator
import me.chius.upcpocket.core.auth.UpcLibraryAuthEndpoints
import me.chius.upcpocket.core.auth.UpcLibraryAuthFailure
import me.chius.upcpocket.core.auth.UpcLibraryAuthResult
import me.chius.upcpocket.core.auth.UpcLibraryAuthenticator
import me.chius.upcpocket.core.auth.UpcLibrarySigning
import me.chius.upcpocket.core.auth.UpcTimetableAuthenticator
import me.chius.upcpocket.core.data.SchoolAccountRepository
import me.chius.upcpocket.core.model.AccountInvalidationReason
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.CampusPreference
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.model.SchoolLoginCredential
import me.chius.upcpocket.core.webview.ControlledWebViewContract
import me.chius.upcpocket.core.webview.clearSchoolWebViewCookies

internal interface LibraryCredentialAccess {
    suspend fun <T> withCredential(block: suspend (SchoolLoginCredential) -> T): T?
}

/**
 * Coordinates strictly read-only queries for both `libspace.upc.edu.cn` (`libseat`) and
 * `office.chaoxing.com` (`seat` / `seatengine`).
 *
 * Security invariants:
 * - Library tokens and Chaoxing cookies are held in RAM only and never written to disk or logs.
 * - Both in-memory sessions and UI state are invalidated immediately on account switch/clear.
 * - Late network completions checked against `accountId`, `generation`, and `businessEpoch` are dropped.
 */
class LibrarySeatRepository internal constructor(
    private val accountStateProvider: () -> SchoolAccountState,
    private val matchesAccountGeneration: (String, Long) -> Boolean,
    private val credentialAccess: LibraryCredentialAccess,
    private val transport: CasHttpTransport = DefaultCasHttpTransport,
    private val libseatAuthenticator: UpcLibraryAuthenticator = UpcLibraryAuthenticator(transport),
    private val chaoxingAuthenticator: UpcChaoxingAuthenticator = UpcChaoxingAuthenticator(transport),
    private val clearWebViewCookies: () -> Unit = ::clearSchoolWebViewCookies,
    private val todayProvider: () -> LocalDate = { LocalDate.now(ZoneId.of("Asia/Shanghai")) },
    private val uuidProvider: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        schoolAccountRepository: SchoolAccountRepository,
        transport: CasHttpTransport = DefaultCasHttpTransport,
    ) : this(
        accountStateProvider = { schoolAccountRepository.state.value },
        matchesAccountGeneration = schoolAccountRepository::matchesAccountGeneration,
        credentialAccess = object : LibraryCredentialAccess {
            override suspend fun <T> withCredential(block: suspend (SchoolLoginCredential) -> T): T? =
                schoolAccountRepository.withLoginCredential(block)
        },
        transport = transport,
    )

    private data class LibseatInMemorySession(
        val token: String,
        val user: LibraryUser,
        val accountId: String,
        val generation: Long,
        val epoch: Long,
    )

    private data class ChaoxingInMemorySession(
        val cookieHeader: String,
        val sno: String,
        val accountId: String,
        val generation: Long,
        val epoch: Long,
    )

    private val mutex = Mutex()
    private val businessEpoch = AtomicLong(0L)

    @Volatile
    private var libseatSession: LibseatInMemorySession? = null

    @Volatile
    private var chaoxingSession: ChaoxingInMemorySession? = null

    @Volatile
    private var userSelectedSystem = false

    private val _uiState = MutableStateFlow(LibrarySeatUiState())
    val uiState: StateFlow<LibrarySeatUiState> = _uiState.asStateFlow()

    fun syncAccountStatusAndCampus(preferredCampus: CampusPreference) {
        val state = accountStateProvider()
        _uiState.update { current ->
            val targetTab = if (!userSelectedSystem && current.connectionStatus != LibraryConnectionStatus.READY) {
                LibrarySystemTab.defaultForCampus(preferredCampus)
            } else {
                current.activeTab
            }
            val dates = chaoxingDateOptions()
            val selectedDate = current.chaoxingSelectedDate.takeIf { it in dates } ?: dates.first()
            val updatedStatus = when (state.status) {
                AccountStatus.UNSET -> LibraryConnectionStatus.UNCONFIGURED
                AccountStatus.RECOVERY_REQUIRED -> LibraryConnectionStatus.RECOVERY_REQUIRED
                AccountStatus.SAVED_UNVERIFIED ->
                    if (current.connectionStatus == LibraryConnectionStatus.UNCONFIGURED ||
                        current.connectionStatus == LibraryConnectionStatus.RECOVERY_REQUIRED
                    ) {
                        LibraryConnectionStatus.IDLE
                    } else {
                        current.connectionStatus
                    }
            }
            current.copy(
                activeTab = targetTab,
                connectionStatus = updatedStatus,
                chaoxingAvailableDates = dates,
                chaoxingSelectedDate = selectedDate,
            )
        }
    }

    suspend fun selectSystem(tab: LibrarySystemTab) = withContext(ioDispatcher) {
        userSelectedSystem = true
        val needsResetDetail = _uiState.value.activeTab != tab
        _uiState.update { current ->
            current.copy(
                activeTab = tab,
                errorMessage = null,
                interactionRequest = if (current.interactionRequest?.system == tab) current.interactionRequest else null,
                chaoxingSelectedRoom = if (needsResetDetail && tab != LibrarySystemTab.LIBSEAT) null else current.chaoxingSelectedRoom,
                chaoxingSelectedRoomDetail = if (needsResetDetail && tab != LibrarySystemTab.LIBSEAT) null else current.chaoxingSelectedRoomDetail,
                chaoxingSelectedSeatNum = if (needsResetDetail && tab != LibrarySystemTab.LIBSEAT) null else current.chaoxingSelectedSeatNum,
                chaoxingSelectedSeatUsedTimes = if (needsResetDetail && tab != LibrarySystemTab.LIBSEAT) emptyList() else current.chaoxingSelectedSeatUsedTimes,
                chaoxingUsedSeatNums = if (needsResetDetail && tab != LibrarySystemTab.LIBSEAT) emptySet() else current.chaoxingUsedSeatNums,
            )
        }
        val state = _uiState.value
        val hasCachedInMemoryData = when (tab) {
            LibrarySystemTab.LIBSEAT -> state.libseatRooms.isNotEmpty()
            LibrarySystemTab.CHAOXING_SEAT, LibrarySystemTab.CHAOXING_SEATENGINE ->
                false
        }
        if (!hasCachedInMemoryData && accountStateProvider().status == AccountStatus.SAVED_UNVERIFIED) {
            refreshActiveSystem()
        }
    }

    suspend fun refreshActiveSystem() = withContext(ioDispatcher) {
        mutex.withLock {
            val state = accountStateProvider()
            val epoch = businessEpoch.get()
            when (state.status) {
                AccountStatus.UNSET -> {
                    _uiState.update {
                        it.copy(
                            connectionStatus = LibraryConnectionStatus.UNCONFIGURED,
                            isLoading = false,
                            errorMessage = null,
                            statusMessage = "请先在「学校账号」配置统一认证凭据",
                            interactionRequest = null,
                        )
                    }
                    return@withLock
                }
                AccountStatus.RECOVERY_REQUIRED -> {
                    _uiState.update {
                        it.copy(
                            connectionStatus = LibraryConnectionStatus.RECOVERY_REQUIRED,
                            isLoading = false,
                            errorMessage = "学校账号加密状态异常，请重新保存后再试",
                            interactionRequest = null,
                        )
                    }
                    return@withLock
                }
                AccountStatus.SAVED_UNVERIFIED -> Unit
            }

            val accountId = state.accountId ?: return@withLock
            val generation = state.generation
            val activeTab = _uiState.value.activeTab
            when (activeTab) {
                LibrarySystemTab.LIBSEAT -> refreshLibseatLocked(accountId, generation, epoch)
                LibrarySystemTab.CHAOXING_SEAT -> refreshChaoxingLocked(ChaoxingSeatKind.SEAT, accountId, generation, epoch)
                LibrarySystemTab.CHAOXING_SEATENGINE -> refreshChaoxingLocked(ChaoxingSeatKind.SEATENGINE, accountId, generation, epoch)
            }
        }
    }

    suspend fun selectLibseatDate(date: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val state = accountStateProvider()
            val accountId = state.accountId ?: return@withLock
            val generation = state.generation
            val epoch = businessEpoch.get()
            if (state.status != AccountStatus.SAVED_UNVERIFIED) return@withLock

            _uiState.update {
                it.copy(
                    libseatSelectedDate = date,
                    libseatSelectedRoom = null,
                    libseatSelectedRoomLayout = null,
                    errorMessage = null,
                    isLoading = true,
                )
            }
            val building = _uiState.value.libseatSelectedBuilding ?: return@withLock
            try {
                val floors = withLibseatTokenLocked(accountId, generation, epoch) { token ->
                    fetchLibseatFloors(token, building.id)
                } ?: return@withLock
                val floorMap = floors.associate { it.floorNum to it.floorName }
                val rooms = withLibseatTokenLocked(accountId, generation, epoch) { token ->
                    fetchLibseatRooms(token, building, date, floorMap)
                } ?: return@withLock
                if (!stillValid(accountId, generation, epoch)) return@withLock
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        connectionStatus = LibraryConnectionStatus.READY,
                        libseatFloors = floors,
                        libseatRooms = rooms,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!stillValid(accountId, generation, epoch)) return@withLock
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        connectionStatus = LibraryConnectionStatus.ERROR,
                        errorMessage = e.message ?: "切换日期失败，请重试",
                    )
                }
            }
        }
    }

    suspend fun selectLibseatBuilding(building: LibraryBuilding) = withContext(ioDispatcher) {
        mutex.withLock {
            val state = accountStateProvider()
            val accountId = state.accountId ?: return@withLock
            val generation = state.generation
            val epoch = businessEpoch.get()
            if (state.status != AccountStatus.SAVED_UNVERIFIED) return@withLock

            val date = _uiState.value.libseatSelectedDate ?: _uiState.value.libseatDates.firstOrNull()
                ?: return@withLock
            _uiState.update {
                it.copy(
                    libseatSelectedBuilding = building,
                    libseatSelectedFloor = null,
                    libseatSelectedRoom = null,
                    libseatSelectedRoomLayout = null,
                    errorMessage = null,
                    isLoading = true,
                )
            }
            try {
                val floors = withLibseatTokenLocked(accountId, generation, epoch) { token ->
                    fetchLibseatFloors(token, building.id)
                } ?: return@withLock
                val floorMap = floors.associate { it.floorNum to it.floorName }
                val rooms = withLibseatTokenLocked(accountId, generation, epoch) { token ->
                    fetchLibseatRooms(token, building, date, floorMap)
                } ?: return@withLock
                if (!stillValid(accountId, generation, epoch)) return@withLock
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        connectionStatus = LibraryConnectionStatus.READY,
                        libseatFloors = floors,
                        libseatRooms = rooms,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!stillValid(accountId, generation, epoch)) return@withLock
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        connectionStatus = LibraryConnectionStatus.ERROR,
                        errorMessage = e.message ?: "加载馆区房间失败，请重试",
                    )
                }
            }
        }
    }

    fun selectLibseatFloor(floor: LibraryFloor?) {
        _uiState.update { it.copy(libseatSelectedFloor = floor) }
    }

    suspend fun loadLibseatRoomLayout(room: LibraryRoom) = withContext(ioDispatcher) {
        mutex.withLock {
            val state = accountStateProvider()
            val accountId = state.accountId ?: return@withLock
            val generation = state.generation
            val epoch = businessEpoch.get()
            if (state.status != AccountStatus.SAVED_UNVERIFIED) return@withLock

            val date = _uiState.value.libseatSelectedDate ?: return@withLock
            _uiState.update {
                it.copy(
                    libseatSelectedRoom = room,
                    libseatSelectedRoomLayout = null,
                    isLoadingRoomDetail = true,
                    errorMessage = null,
                )
            }
            try {
                val layout = withLibseatTokenLocked(accountId, generation, epoch) { token ->
                    fetchLibseatLayout(token, room.roomId, date)
                } ?: return@withLock
                if (!stillValid(accountId, generation, epoch)) return@withLock
                _uiState.update {
                    it.copy(
                        isLoadingRoomDetail = false,
                        libseatSelectedRoomLayout = layout,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!stillValid(accountId, generation, epoch)) return@withLock
                _uiState.update {
                    it.copy(
                        isLoadingRoomDetail = false,
                        errorMessage = e.message ?: "读取座位分布失败，请重试",
                    )
                }
            }
        }
    }

    fun dismissLibseatRoomDetail() {
        _uiState.update {
            it.copy(
                libseatSelectedRoom = null,
                libseatSelectedRoomLayout = null,
                isLoadingRoomDetail = false,
            )
        }
    }

    suspend fun selectChaoxingDate(date: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val state = accountStateProvider()
            val accountId = state.accountId ?: return@withLock
            val generation = state.generation
            val epoch = businessEpoch.get()
            val kind = _uiState.value.activeTab.toChaoxingKind() ?: return@withLock
            if (state.status != AccountStatus.SAVED_UNVERIFIED) return@withLock

            _uiState.update {
                it.copy(
                    chaoxingSelectedDate = date,
                    chaoxingSelectedRoom = null,
                    chaoxingSelectedRoomDetail = null,
                    chaoxingSelectedSeatNum = null,
                    chaoxingSelectedSeatUsedTimes = emptyList(),
                    chaoxingUsedSeatNums = emptySet(),
                    errorMessage = null,
                )
            }
            refreshChaoxingLocked(kind, accountId, generation, epoch)
        }
    }

    suspend fun loadChaoxingRoomDetail(room: ChaoxingSeatRoom) = withContext(ioDispatcher) {
        mutex.withLock {
            val state = accountStateProvider()
            val accountId = state.accountId ?: return@withLock
            val generation = state.generation
            val epoch = businessEpoch.get()
            val kind = _uiState.value.activeTab.toChaoxingKind() ?: return@withLock
            if (state.status != AccountStatus.SAVED_UNVERIFIED) return@withLock

            val date = _uiState.value.chaoxingSelectedDate.ifBlank { chaoxingDateOptions().first() }
            _uiState.update {
                it.copy(
                    chaoxingSelectedRoom = room,
                    chaoxingSelectedRoomDetail = null,
                    chaoxingSelectedSeatNum = null,
                    chaoxingSelectedSeatUsedTimes = emptyList(),
                    chaoxingUsedSeatNums = emptySet(),
                    isLoadingRoomDetail = true,
                    errorMessage = null,
                )
            }
            try {
                val detail = withChaoxingCookieLocked(accountId, generation, epoch) { cookie ->
                    fetchChaoxingRoomDetail(kind, cookie, room.id, date)
                } ?: return@withLock
                val defaultEnd = minOf(detail.startMinute + 240, detail.endMinute)
                val usedNums = withChaoxingCookieLocked(accountId, generation, epoch) { cookie ->
                    fetchChaoxingUsedSeatNums(kind, cookie, room.id, detail.startMinute, defaultEnd, date)
                } ?: return@withLock
                if (!stillValid(accountId, generation, epoch)) return@withLock
                _uiState.update {
                    it.copy(
                        isLoadingRoomDetail = false,
                        chaoxingSelectedRoomDetail = detail,
                        chaoxingSelectedStartMinute = detail.startMinute,
                        chaoxingSelectedEndMinute = defaultEnd,
                        chaoxingUsedSeatNums = usedNums,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!stillValid(accountId, generation, epoch)) return@withLock
                _uiState.update {
                    it.copy(
                        isLoadingRoomDetail = false,
                        errorMessage = e.message ?: "读取超星座位详情失败，请重试",
                    )
                }
            }
        }
    }

    suspend fun updateChaoxingWindow(startMinute: Int, endMinute: Int) = withContext(ioDispatcher) {
        mutex.withLock {
            val state = accountStateProvider()
            val accountId = state.accountId ?: return@withLock
            val generation = state.generation
            val epoch = businessEpoch.get()
            val kind = _uiState.value.activeTab.toChaoxingKind() ?: return@withLock
            val room = _uiState.value.chaoxingSelectedRoom ?: return@withLock
            if (state.status != AccountStatus.SAVED_UNVERIFIED) return@withLock
            if (endMinute <= startMinute) {
                _uiState.update { it.copy(errorMessage = "结束时间必须晚于开始时间") }
                return@withLock
            }
            val date = _uiState.value.chaoxingSelectedDate.ifBlank { chaoxingDateOptions().first() }
            _uiState.update {
                it.copy(
                    chaoxingSelectedStartMinute = startMinute,
                    chaoxingSelectedEndMinute = endMinute,
                    isLoadingRoomDetail = true,
                    errorMessage = null,
                )
            }
            try {
                val used = withChaoxingCookieLocked(accountId, generation, epoch) { cookie ->
                    fetchChaoxingUsedSeatNums(kind, cookie, room.id, startMinute, endMinute, date)
                } ?: return@withLock
                if (!stillValid(accountId, generation, epoch)) return@withLock
                _uiState.update {
                    it.copy(
                        isLoadingRoomDetail = false,
                        chaoxingUsedSeatNums = used,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!stillValid(accountId, generation, epoch)) return@withLock
                _uiState.update {
                    it.copy(
                        isLoadingRoomDetail = false,
                        errorMessage = e.message ?: "刷新时段占用失败，请重试",
                    )
                }
            }
        }
    }

    suspend fun loadChaoxingSeatUsedTimes(seatNum: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val state = accountStateProvider()
            val accountId = state.accountId ?: return@withLock
            val generation = state.generation
            val epoch = businessEpoch.get()
            val kind = _uiState.value.activeTab.toChaoxingKind() ?: return@withLock
            val room = _uiState.value.chaoxingSelectedRoom ?: return@withLock
            if (state.status != AccountStatus.SAVED_UNVERIFIED) return@withLock
            val date = _uiState.value.chaoxingSelectedDate.ifBlank { chaoxingDateOptions().first() }

            _uiState.update {
                it.copy(
                    chaoxingSelectedSeatNum = seatNum,
                    chaoxingSelectedSeatUsedTimes = emptyList(),
                    isLoadingRoomDetail = true,
                )
            }
            try {
                val intervals = withChaoxingCookieLocked(accountId, generation, epoch) { cookie ->
                    fetchChaoxingUsedTimes(kind, cookie, room.id, seatNum, date)
                } ?: return@withLock
                if (!stillValid(accountId, generation, epoch)) return@withLock
                _uiState.update {
                    it.copy(
                        isLoadingRoomDetail = false,
                        chaoxingSelectedSeatUsedTimes = intervals,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!stillValid(accountId, generation, epoch)) return@withLock
                _uiState.update {
                    it.copy(
                        isLoadingRoomDetail = false,
                        errorMessage = e.message ?: "读取座位占用时段失败",
                    )
                }
            }
        }
    }

    fun dismissChaoxingRoomDetail() {
        _uiState.update {
            it.copy(
                chaoxingSelectedRoom = null,
                chaoxingSelectedRoomDetail = null,
                chaoxingSelectedSeatNum = null,
                chaoxingSelectedSeatUsedTimes = emptyList(),
                chaoxingUsedSeatNums = emptySet(),
                isLoadingRoomDetail = false,
            )
        }
    }

    suspend fun adoptLibseatWebViewToken(
        rawUrl: String,
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

            val appToken = LibrarySeatProtocol.extractTokenFromUrl(rawUrl)
            if (appToken.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        connectionStatus = LibraryConnectionStatus.ERROR,
                        errorMessage = "未从图书馆页面检测到有效登录凭据，请重试",
                        interactionRequest = null,
                    )
                }
                return@withLock
            }

            val expectedLoginName = credentialAccess.withCredential { it.loginName.trim() }
            if (expectedLoginName.isNullOrBlank()) {
                clearWebViewCookies()
                return@withLock
            }

            _uiState.update { it.copy(isLoading = true, errorMessage = null, interactionRequest = null) }
            try {
                val seatToken = exchangeLibseatAppToken(appToken)
                val user = fetchLibseatUser(seatToken)
                if (user.username.trim() != expectedLoginName) {
                    clearWebViewCookies()
                    if (stillValid(accountId, state.generation, epoch)) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                connectionStatus = LibraryConnectionStatus.ERROR,
                                errorMessage = "网页登录学号与当前学校账号不一致，已拒绝加载",
                            )
                        }
                    }
                    return@withLock
                }
                if (!stillValid(accountId, state.generation, epoch)) {
                    clearWebViewCookies()
                    return@withLock
                }
                libseatSession = LibseatInMemorySession(
                    token = seatToken,
                    user = user,
                    accountId = accountId,
                    generation = state.generation,
                    epoch = epoch,
                )
                clearWebViewCookies()
                refreshLibseatLocked(accountId, state.generation, epoch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                clearWebViewCookies()
                if (stillValid(accountId, state.generation, epoch)) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            connectionStatus = LibraryConnectionStatus.ERROR,
                            errorMessage = e.message ?: "图书馆网页验证采纳失败，请重试",
                        )
                    }
                }
            }
        }
    }

    suspend fun adoptChaoxingWebViewCookies(
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

            val cookieHeader = UpcTimetableAuthenticator.formatCookieHeader(cookies)
            if (cookieHeader.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        connectionStatus = LibraryConnectionStatus.ERROR,
                        errorMessage = "未读取到有效的超星会话 Cookie，请重试",
                        interactionRequest = null,
                    )
                }
                return@withLock
            }

            val expectedLoginName = credentialAccess.withCredential { it.loginName.trim() }
            if (expectedLoginName.isNullOrBlank()) {
                clearWebViewCookies()
                return@withLock
            }

            _uiState.update { it.copy(isLoading = true, errorMessage = null, interactionRequest = null) }
            try {
                val html = readChaoxing(cookieHeader, UpcChaoxingAuthEndpoints.SEAT_ENTRY)
                val user = ChaoxingProtocol.parseIdentity(html)
                if (user.sno.trim() != expectedLoginName) {
                    clearWebViewCookies()
                    if (stillValid(accountId, state.generation, epoch)) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                connectionStatus = LibraryConnectionStatus.ERROR,
                                errorMessage = "网页登录学号与当前学校账号不一致，已拒绝加载",
                            )
                        }
                    }
                    return@withLock
                }
                if (!stillValid(accountId, state.generation, epoch)) {
                    clearWebViewCookies()
                    return@withLock
                }
                chaoxingSession = ChaoxingInMemorySession(
                    cookieHeader = cookieHeader,
                    sno = user.sno,
                    accountId = accountId,
                    generation = state.generation,
                    epoch = epoch,
                )
                clearWebViewCookies()
                val kind = _uiState.value.activeTab.toChaoxingKind() ?: ChaoxingSeatKind.SEAT
                refreshChaoxingLocked(kind, accountId, state.generation, epoch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                clearWebViewCookies()
                if (stillValid(accountId, state.generation, epoch)) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            connectionStatus = LibraryConnectionStatus.ERROR,
                            errorMessage = e.message ?: "超星网页验证采纳失败，请重试",
                        )
                    }
                }
            }
        }
    }

    fun dismissInteractionPrompt() {
        clearWebViewCookies()
        _uiState.update {
            it.copy(
                interactionRequest = null,
                connectionStatus = if (it.connectionStatus == LibraryConnectionStatus.INTERACTION_REQUIRED) {
                    LibraryConnectionStatus.IDLE
                } else {
                    it.connectionStatus
                },
            )
        }
    }

    fun clearInMemorySessions() {
        businessEpoch.incrementAndGet()
        libseatSession = null
        chaoxingSession = null
        clearWebViewCookies()
        _uiState.update { current ->
            val state = accountStateProvider()
            LibrarySeatUiState(
                activeTab = current.activeTab,
                connectionStatus = when (state.status) {
                    AccountStatus.UNSET -> LibraryConnectionStatus.UNCONFIGURED
                    AccountStatus.RECOVERY_REQUIRED -> LibraryConnectionStatus.RECOVERY_REQUIRED
                    AccountStatus.SAVED_UNVERIFIED -> LibraryConnectionStatus.IDLE
                },
                statusMessage = "已清空图书馆内存会话",
                chaoxingAvailableDates = chaoxingDateOptions(),
                chaoxingSelectedDate = chaoxingDateOptions().first(),
            )
        }
    }

    fun onAccountInvalidated(reason: AccountInvalidationReason) {
        businessEpoch.incrementAndGet()
        libseatSession = null
        chaoxingSession = null
        clearWebViewCookies()
        val state = accountStateProvider()
        val message = when (reason) {
            AccountInvalidationReason.ACCOUNT_CHANGED_OR_CLEARED -> "学校账号已切换或清空，图书馆内存会话已清除"
            AccountInvalidationReason.CREDENTIALS_UPDATED -> "账号凭据已更新，请重新拉取图书馆数据"
        }
        _uiState.update { current ->
            LibrarySeatUiState(
                activeTab = current.activeTab,
                connectionStatus = when (state.status) {
                    AccountStatus.UNSET -> LibraryConnectionStatus.UNCONFIGURED
                    AccountStatus.RECOVERY_REQUIRED -> LibraryConnectionStatus.RECOVERY_REQUIRED
                    AccountStatus.SAVED_UNVERIFIED -> LibraryConnectionStatus.IDLE
                },
                statusMessage = message,
                chaoxingAvailableDates = chaoxingDateOptions(),
                chaoxingSelectedDate = chaoxingDateOptions().first(),
            )
        }
    }

    private suspend fun refreshLibseatLocked(
        accountId: String,
        generation: Long,
        epoch: Long,
    ) {
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                statusMessage = null,
            )
        }
        try {
            val filters = withLibseatTokenLocked(accountId, generation, epoch) { token ->
                fetchLibseatFilters(token)
            } ?: return
            if (!stillValid(accountId, generation, epoch)) return

            val currentSelectedDate = _uiState.value.libseatSelectedDate
            val date = currentSelectedDate?.takeIf { it in filters.dates } ?: filters.dates.first()

            val currentSelectedBuilding = _uiState.value.libseatSelectedBuilding
            val building = currentSelectedBuilding?.let { prev ->
                filters.buildings.firstOrNull { it.id == prev.id }
            } ?: filters.buildings.first()

            val floors = withLibseatTokenLocked(accountId, generation, epoch) { token ->
                fetchLibseatFloors(token, building.id)
            } ?: return
            val floorMap = floors.associate { it.floorNum to it.floorName }

            val rooms = withLibseatTokenLocked(accountId, generation, epoch) { token ->
                fetchLibseatRooms(token, building, date, floorMap)
            } ?: return

            val currentRes = withLibseatTokenLocked(accountId, generation, epoch) { token ->
                fetchLibseatCurrentReservations(token)
            } ?: return

            val historyRes = withLibseatTokenLocked(accountId, generation, epoch) { token ->
                fetchLibseatHistoryReservations(token)
            } ?: return

            if (!stillValid(accountId, generation, epoch)) return
            val matchedFloor = _uiState.value.libseatSelectedFloor?.let { prev ->
                floors.firstOrNull { it.floorNum == prev.floorNum }
            }

            _uiState.update {
                it.copy(
                    connectionStatus = LibraryConnectionStatus.READY,
                    isLoading = false,
                    errorMessage = null,
                    statusMessage = "已同步 ${building.name}（只读）",
                    libseatDates = filters.dates,
                    libseatSelectedDate = date,
                    libseatBuildings = filters.buildings,
                    libseatSelectedBuilding = building,
                    libseatFloors = floors,
                    libseatSelectedFloor = matchedFloor,
                    libseatRooms = rooms,
                    libseatSelectedRoom = null,
                    libseatSelectedRoomLayout = null,
                    libseatCurrentReservations = currentRes,
                    libseatHistoryReservations = historyRes,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!stillValid(accountId, generation, epoch)) return
            _uiState.update {
                it.copy(
                    connectionStatus = LibraryConnectionStatus.ERROR,
                    isLoading = false,
                    errorMessage = e.message ?: "读取唐岛湾图书馆座位失败，请重试",
                )
            }
        }
    }

    private suspend fun refreshChaoxingLocked(
        kind: ChaoxingSeatKind,
        accountId: String,
        generation: Long,
        epoch: Long,
    ) {
        val dates = chaoxingDateOptions()
        val date = _uiState.value.chaoxingSelectedDate.takeIf { it in dates } ?: dates.first()
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                statusMessage = null,
                chaoxingAvailableDates = dates,
                chaoxingSelectedDate = date,
            )
        }
        try {
            val page = withChaoxingCookieLocked(accountId, generation, epoch) { cookie ->
                fetchChaoxingRooms(kind, cookie, date)
            } ?: return
            if (!stillValid(accountId, generation, epoch)) return
            _uiState.update {
                it.copy(
                    connectionStatus = LibraryConnectionStatus.READY,
                    isLoading = false,
                    errorMessage = null,
                    statusMessage = "已同步 ${kind.campusLabel} 房间列表（只读）",
                    chaoxingRooms = page.rooms,
                    chaoxingSelectedRoom = null,
                    chaoxingSelectedRoomDetail = null,
                    chaoxingSelectedSeatNum = null,
                    chaoxingSelectedSeatUsedTimes = emptyList(),
                    chaoxingUsedSeatNums = emptySet(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!stillValid(accountId, generation, epoch)) return
            _uiState.update {
                it.copy(
                    connectionStatus = LibraryConnectionStatus.ERROR,
                    isLoading = false,
                    errorMessage = e.message ?: "读取 ${kind.campusLabel} 房间失败，请重试",
                )
            }
        }
    }

    private suspend fun <T> withLibseatTokenLocked(
        accountId: String,
        generation: Long,
        epoch: Long,
        block: suspend (String) -> T,
    ): T? {
        val initialSession = libseatSession?.takeIf {
            it.accountId == accountId && it.generation == generation && it.epoch == epoch
        } ?: authenticateLibseatLocked(accountId, generation, epoch) ?: return null

        return try {
            block(initialSession.token)
        } catch (_: LibrarySessionExpiredException) {
            libseatSession = null
            if (!stillValid(accountId, generation, epoch)) return null
            val refreshed = authenticateLibseatLocked(accountId, generation, epoch) ?: return null
            block(refreshed.token)
        }
    }

    private suspend fun authenticateLibseatLocked(
        accountId: String,
        generation: Long,
        epoch: Long,
    ): LibseatInMemorySession? {
        _uiState.update { it.copy(connectionStatus = LibraryConnectionStatus.AUTHENTICATING) }
        val result = credentialAccess.withCredential { credential ->
            if (credential.accountId != accountId || credential.generation != generation) {
                null
            } else {
                libseatAuthenticator.authenticate(credential)
            }
        }
        if (!stillValid(accountId, generation, epoch) || result == null) {
            return null
        }

        return when (result) {
            is UpcLibraryAuthResult.Success -> {
                val created = LibseatInMemorySession(
                    token = result.seatToken,
                    user = LibraryUser(result.user.id, result.user.username, result.user.name),
                    accountId = accountId,
                    generation = generation,
                    epoch = epoch,
                )
                libseatSession = created
                created
            }
            is UpcLibraryAuthResult.Failure -> {
                when (result.reason) {
                    UpcLibraryAuthFailure.INVALID_CREDENTIALS -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                connectionStatus = LibraryConnectionStatus.INVALID_CREDENTIALS,
                                errorMessage = "学校统一认证用户名或密码错误，请核对后重试",
                            )
                        }
                    }
                    UpcLibraryAuthFailure.INTERACTION_REQUIRED -> {
                        val url = result.interactionUrl ?: UpcLibraryAuthEndpoints.ENTRY_URL
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                connectionStatus = LibraryConnectionStatus.INTERACTION_REQUIRED,
                                errorMessage = result.message ?: "需要完成学校安全验证后获取图书馆会话",
                                interactionRequest = LibraryInteractionRequest(
                                    system = LibrarySystemTab.LIBSEAT,
                                    sessionContext = ControlledWebViewContract.SessionContext(
                                        accountId = accountId,
                                        generation = generation,
                                        entryUrl = url,
                                    ),
                                    scopeHosts = ControlledWebViewContract.libraryScopeHosts,
                                    targetHost = UpcLibraryAuthEndpoints.LIBRARY_HOST,
                                    completionPathPrefixes = setOf("/libseat", "/remote/static/casAuth/"),
                                    allowCompletionWithoutCookies = true,
                                    reason = result.message ?: "需要完成学校安全验证",
                                ),
                            )
                        }
                    }
                    UpcLibraryAuthFailure.IDENTITY_MISMATCH -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                connectionStatus = LibraryConnectionStatus.ERROR,
                                errorMessage = "图书馆返回的学号与本机配置不一致，已终止会话",
                            )
                        }
                    }
                    UpcLibraryAuthFailure.NETWORK_UNAVAILABLE -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                connectionStatus = LibraryConnectionStatus.ERROR,
                                errorMessage = result.message ?: "无法连接图书馆座位系统，请检查网络",
                            )
                        }
                    }
                    UpcLibraryAuthFailure.TLS_ERROR -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                connectionStatus = LibraryConnectionStatus.ERROR,
                                errorMessage = "安全证书校验失败，已终止访问",
                            )
                        }
                    }
                    UpcLibraryAuthFailure.UPSTREAM_ERROR,
                    UpcLibraryAuthFailure.PROTOCOL_ERROR,
                    UpcLibraryAuthFailure.ACCESS_DENIED,
                    -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                connectionStatus = LibraryConnectionStatus.ERROR,
                                errorMessage = result.message ?: "图书馆认证失败，请稍后重试",
                            )
                        }
                    }
                }
                null
            }
        }
    }

    private suspend fun <T> withChaoxingCookieLocked(
        accountId: String,
        generation: Long,
        epoch: Long,
        block: suspend (String) -> T,
    ): T? {
        val initialSession = chaoxingSession?.takeIf {
            it.accountId == accountId && it.generation == generation && it.epoch == epoch
        } ?: authenticateChaoxingLocked(accountId, generation, epoch) ?: return null

        return try {
            block(initialSession.cookieHeader)
        } catch (_: ChaoxingSessionExpiredException) {
            chaoxingSession = null
            if (!stillValid(accountId, generation, epoch)) return null
            val refreshed = authenticateChaoxingLocked(accountId, generation, epoch) ?: return null
            block(refreshed.cookieHeader)
        }
    }

    private suspend fun authenticateChaoxingLocked(
        accountId: String,
        generation: Long,
        epoch: Long,
    ): ChaoxingInMemorySession? {
        _uiState.update { it.copy(connectionStatus = LibraryConnectionStatus.AUTHENTICATING) }
        var expectedLoginName = ""
        val result = credentialAccess.withCredential { credential ->
            if (credential.accountId != accountId || credential.generation != generation) {
                null
            } else {
                expectedLoginName = credential.loginName.trim()
                chaoxingAuthenticator.authenticate(credential)
            }
        }
        if (!stillValid(accountId, generation, epoch) || result == null) {
            return null
        }

        return when (result) {
            is UpcChaoxingAuthResult.Success -> {
                val parsedUser = runCatching { ChaoxingProtocol.parseIdentity(result.indexHtml) }.getOrNull()
                if (parsedUser == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            connectionStatus = LibraryConnectionStatus.ERROR,
                            errorMessage = "未识别到超星用户学号信息",
                        )
                    }
                    return null
                }
                if (parsedUser.sno.trim() != expectedLoginName) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            connectionStatus = LibraryConnectionStatus.ERROR,
                            errorMessage = "超星返回的学号与本机配置不一致，已拒绝加载",
                        )
                    }
                    return null
                }
                val created = ChaoxingInMemorySession(
                    cookieHeader = result.cookieHeader,
                    sno = parsedUser.sno,
                    accountId = accountId,
                    generation = generation,
                    epoch = epoch,
                )
                chaoxingSession = created
                created
            }
            is UpcChaoxingAuthResult.Failure -> {
                when (result.reason) {
                    UpcChaoxingAuthFailure.INVALID_CREDENTIALS -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                connectionStatus = LibraryConnectionStatus.INVALID_CREDENTIALS,
                                errorMessage = "学校统一认证用户名或密码错误，请核对后重试",
                            )
                        }
                    }
                    UpcChaoxingAuthFailure.INTERACTION_REQUIRED -> {
                        val entryUrl = _uiState.value.activeTab.officialUrl
                        val currentTab = _uiState.value.activeTab
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                connectionStatus = LibraryConnectionStatus.INTERACTION_REQUIRED,
                                errorMessage = result.message ?: "需要完成学校网页安全验证",
                                interactionRequest = LibraryInteractionRequest(
                                    system = currentTab,
                                    sessionContext = ControlledWebViewContract.SessionContext(
                                        accountId = accountId,
                                        generation = generation,
                                        entryUrl = entryUrl,
                                    ),
                                    scopeHosts = ControlledWebViewContract.chaoxingScopeHosts,
                                    targetHost = UpcChaoxingAuthEndpoints.OFFICE_HOST,
                                    completionPathPrefixes = setOf(
                                        "/front/third/apps/seat/",
                                        "/front/third/apps/seatengine/",
                                        "/front/apps/seat/",
                                    ),
                                    allowCompletionWithoutCookies = false,
                                    reason = result.message ?: "需要完成学校安全验证",
                                ),
                            )
                        }
                    }
                    UpcChaoxingAuthFailure.NETWORK_UNAVAILABLE -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                connectionStatus = LibraryConnectionStatus.ERROR,
                                errorMessage = result.message ?: "无法连接超星座位系统，请检查网络",
                            )
                        }
                    }
                    UpcChaoxingAuthFailure.TLS_ERROR -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                connectionStatus = LibraryConnectionStatus.ERROR,
                                errorMessage = "安全证书校验失败，已终止访问",
                            )
                        }
                    }
                    UpcChaoxingAuthFailure.UPSTREAM_ERROR,
                    UpcChaoxingAuthFailure.PROTOCOL_ERROR,
                    UpcChaoxingAuthFailure.ACCESS_DENIED,
                    -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                connectionStatus = LibraryConnectionStatus.ERROR,
                                errorMessage = result.message ?: "超星认证失败，请稍后重试",
                            )
                        }
                    }
                }
                null
            }
        }
    }

    private suspend fun exchangeLibseatAppToken(appToken: String): String {
        val body = requestLibseat(
            method = "POST",
            path = "/rest/ssoAuth",
            query = mapOf("token" to appToken),
            form = mapOf("token" to appToken),
            token = appToken,
        )
        return LibrarySeatProtocol.parseToken(body)
    }

    private suspend fun fetchLibseatUser(seatToken: String): LibraryUser {
        val body = requestLibseat(
            method = "GET",
            path = "/rest/v2/user",
            query = mapOf("token" to seatToken),
            token = seatToken,
        )
        return LibrarySeatProtocol.parseUser(body)
    }

    private suspend fun fetchLibseatFilters(token: String): LibraryFilters =
        LibrarySeatProtocol.parseFilters(
            requestLibseat("GET", "/rest/v2/free/filters", mapOf("token" to token), token = token),
        )

    private suspend fun fetchLibseatFloors(token: String, buildingId: String): List<LibraryFloor> =
        LibrarySeatProtocol.parseFloors(
            requestLibseat(
                "POST",
                "/rest/v2/room/floors/${pathSeg(buildingId)}",
                mapOf("token" to token),
                token = token,
            ),
        )

    private suspend fun fetchLibseatRooms(
        token: String,
        building: LibraryBuilding,
        date: String,
        floorMap: Map<Int, String>,
    ): List<LibraryRoom> = LibrarySeatProtocol.parseRooms(
        requestLibseat(
            "GET",
            "/rest/v2/room/stats2/${pathSeg(building.id)}/${pathSeg(date)}",
            mapOf("token" to token),
            token = token,
        ),
        building = building,
        floorNamesByNum = floorMap,
    )

    private suspend fun fetchLibseatLayout(token: String, roomId: String, date: String): LibrarySeatLayout =
        LibrarySeatProtocol.parseLayout(
            requestLibseat(
                "GET",
                "/rest/v2/room/layoutByDate/${pathSeg(roomId)}/${pathSeg(date)}",
                mapOf("token" to token),
                token = token,
            ),
        )

    private suspend fun fetchLibseatCurrentReservations(token: String): List<LibraryReservation> =
        LibrarySeatProtocol.parseCurrentReservations(
            requestLibseat("GET", "/rest/v2/user/reservations", mapOf("token" to token), token = token),
        )

    private suspend fun fetchLibseatHistoryReservations(token: String): List<LibraryReservation> =
        LibrarySeatProtocol.parseHistoryReservations(
            requestLibseat(
                "GET",
                "/rest/v2/history/1/50",
                mapOf("token" to token, "page" to "1", "pageSize" to "50"),
                token = token,
            ),
        )

    private suspend fun requestLibseat(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
        token: String,
    ): String {
        val queryString = if (query.isEmpty()) "" else "?" + LibrarySeatProtocol.queryEncode(query)
        val fullUrl = "${LibrarySeatProtocol.BASE_URL}$path$queryString"
        requireLibseatUrl(fullUrl)
        val headers = UpcLibrarySigning.signedHeaders(
            token = token,
            method = method,
            uuid = uuidProvider(),
            timestamp = clock(),
        ).toMutableMap()
        val encodedForm = form?.let {
            headers["Content-Type"] = "application/x-www-form-urlencoded;charset=UTF-8"
            LibrarySeatProtocol.formEncode(it)
        }
        val response: CasHttpResponse = try {
            transport.execute(
                CasHttpRequest(
                    url = fullUrl,
                    method = method,
                    headers = headers,
                    formBody = encodedForm,
                ),
            )
        } catch (failure: CasTransportException) {
            throw LibrarySeatException(failure.message ?: "无法连接图书馆座位系统", failure)
        }
        return when (response.statusCode) {
            in 200..299 -> response.body
            401, 403 -> throw LibrarySessionExpiredException()
            in 300..399 -> throw LibrarySessionExpiredException("图书馆会话已跳转，请重新验证")
            else -> throw LibrarySeatException("图书馆服务暂时不可用（HTTP ${response.statusCode}）")
        }
    }

    private suspend fun fetchChaoxingRooms(
        kind: ChaoxingSeatKind,
        cookieHeader: String,
        date: String,
    ): ChaoxingSeatRoomPage {
        val params = linkedMapOf(
            "time" to "",
            "cpage" to "1",
            "pageSize" to "100",
            "firstLevelName" to "",
            "secondLevelName" to "",
            "thirdLevelName" to "",
            "day" to date,
            "deptIdEnc" to UpcChaoxingAuthEndpoints.FID_ENC,
        )
        if (kind == ChaoxingSeatKind.SEATENGINE) {
            params["seatId"] = UpcChaoxingAuthEndpoints.SEATENGINE_SEAT_ID
        }
        val body = readChaoxing(
            cookieHeader = cookieHeader,
            url = "https://office.chaoxing.com/data/apps/${kind.api}/room/list?${queryString(params)}",
        )
        return ChaoxingProtocol.parseSeatRooms(body, kind, date)
    }

    private suspend fun fetchChaoxingRoomDetail(
        kind: ChaoxingSeatKind,
        cookieHeader: String,
        roomId: Int,
        date: String,
    ): ChaoxingSeatRoomDetail {
        val infoBody = readChaoxing(
            cookieHeader = cookieHeader,
            url = "https://office.chaoxing.com/data/apps/${kind.api}/room/info",
            formBody = queryString(
                linkedMapOf(
                    "id" to roomId.toString(),
                    "fidEnc" to UpcChaoxingAuthEndpoints.FID_ENC,
                ),
            ),
        )
        val detail = ChaoxingProtocol.parseSeatRoomDetail(infoBody, date, roomId)
        val drawBody = readChaoxing(
            cookieHeader = cookieHeader,
            url = "https://office.chaoxing.com/data/apps/${kind.api}/getdrawseat?id=$roomId",
        )
        return detail.copy(places = ChaoxingProtocol.parseSeatPlaces(drawBody))
    }

    private suspend fun fetchChaoxingUsedSeatNums(
        kind: ChaoxingSeatKind,
        cookieHeader: String,
        roomId: Int,
        startMinute: Int,
        endMinute: Int,
        date: String,
    ): Set<String> {
        val params = linkedMapOf(
            "roomId" to roomId.toString(),
            "startTime" to formatClockMinute(startMinute),
            "endTime" to formatClockMinute(endMinute),
            "day" to date,
        )
        if (kind == ChaoxingSeatKind.SEATENGINE) {
            params["seatId"] = UpcChaoxingAuthEndpoints.SEATENGINE_SEAT_ID
        } else {
            params["fidEnc"] = UpcChaoxingAuthEndpoints.FID_ENC
        }
        val body = readChaoxing(
            cookieHeader = cookieHeader,
            url = "https://office.chaoxing.com/data/apps/${kind.api}/getusedseatnums?${queryString(params)}",
        )
        return ChaoxingProtocol.parseUsedSeatNums(body)
    }

    private suspend fun fetchChaoxingUsedTimes(
        kind: ChaoxingSeatKind,
        cookieHeader: String,
        roomId: Int,
        seatNum: String,
        date: String,
    ): List<ChaoxingTimeInterval> {
        val params = linkedMapOf(
            "roomId" to roomId.toString(),
            "seatNum" to seatNum,
            "day" to date,
        )
        if (kind == ChaoxingSeatKind.SEATENGINE) {
            params["seatId"] = UpcChaoxingAuthEndpoints.SEATENGINE_SEAT_ID
        } else {
            params["fidEnc"] = UpcChaoxingAuthEndpoints.FID_ENC
        }
        val body = readChaoxing(
            cookieHeader = cookieHeader,
            url = "https://office.chaoxing.com/data/apps/${kind.api}/getusedtimes?${queryString(params)}",
        )
        return ChaoxingProtocol.parseUsedTimes(body)
    }

    private suspend fun readChaoxing(
        cookieHeader: String,
        url: String,
        formBody: String? = null,
    ): String {
        requireChaoxingReadUrl(url)
        val headers = linkedMapOf(
            "Accept" to "application/json, text/html;q=0.9",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36",
        )
        if (cookieHeader.isNotBlank()) {
            headers["Cookie"] = cookieHeader
        }
        if (formBody != null) {
            headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        }
        val response: CasHttpResponse = try {
            transport.execute(
                CasHttpRequest(
                    url = url,
                    method = if (formBody == null) "GET" else "POST",
                    headers = headers,
                    formBody = formBody,
                ),
            )
        } catch (failure: CasTransportException) {
            throw ChaoxingException(failure.message ?: "无法连接超星座位系统", "chaoxing_network", failure)
        }
        return when {
            response.statusCode in setOf(401, 403, 302, 303) -> throw ChaoxingSessionExpiredException()
            response.statusCode == 429 -> throw ChaoxingException("请求过于频繁，请稍后重试", "chaoxing_rate_limited")
            response.statusCode !in 200..299 ->
                throw ChaoxingException("学校超星服务返回 HTTP ${response.statusCode}", "chaoxing_upstream")
            else -> response.body
        }
    }

    private fun chaoxingDateOptions(): List<String> {
        val today = todayProvider()
        return listOf(today.toString(), today.plusDays(1).toString(), today.plusDays(2).toString())
    }

    private fun stillValid(accountId: String, generation: Long, epoch: Long): Boolean =
        businessEpoch.get() == epoch && matchesAccountGeneration(accountId, generation)

    private fun pathSeg(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun queryString(params: Map<String, String>): String =
        params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
        }

    private fun requireLibseatUrl(raw: String) {
        val uri = runCatching { URI(raw) }.getOrNull() ?: throw LibrarySeatException("非法图书馆 URL")
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host?.lowercase(Locale.ROOT) != UpcLibraryAuthEndpoints.LIBRARY_HOST ||
            uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443)
        ) {
            throw LibrarySeatException("未允许的图书馆请求地址")
        }
    }

    private fun requireChaoxingReadUrl(raw: String) {
        val uri = runCatching { URI(raw) }.getOrNull() ?: throw ChaoxingException("非法 Chaoxing URL")
        val host = uri.host?.lowercase(Locale.ROOT)
        val path = uri.rawPath.orEmpty()
        val allowed = uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            uri.port in setOf(-1, 443) &&
            host == UpcChaoxingAuthEndpoints.OFFICE_HOST &&
            (path == UpcChaoxingAuthEndpoints.OFFICE_SEAT_PATH ||
                path == "/front/third/apps/seatengine/index" ||
                path == "/data/apps/seat/room/list" ||
                path == "/data/apps/seat/room/info" ||
                path == "/data/apps/seat/getdrawseat" ||
                path == "/data/apps/seat/getusedseatnums" ||
                path == "/data/apps/seat/getusedtimes" ||
                path == "/data/apps/seatengine/room/list" ||
                path == "/data/apps/seatengine/room/info" ||
                path == "/data/apps/seatengine/getdrawseat" ||
                path == "/data/apps/seatengine/getusedseatnums" ||
                path == "/data/apps/seatengine/getusedtimes")
        if (!allowed) {
            throw ChaoxingException("未允许的超星只读路径", "chaoxing_protocol")
        }
    }
}
