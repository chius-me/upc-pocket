package me.chius.upcpocket.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import me.chius.upcpocket.AppContainer
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.feature.account.SchoolAccountScreen
import me.chius.upcpocket.feature.calendar.AcademicCalendarScreen
import me.chius.upcpocket.feature.card.CardWalletScreen
import me.chius.upcpocket.feature.classroom.FreeClassroomScreen
import me.chius.upcpocket.feature.grades.GradesScreen
import me.chius.upcpocket.feature.home.TodayScreen
import me.chius.upcpocket.feature.library.LibrarySeatScreen
import me.chius.upcpocket.feature.power.PowerScreen
import me.chius.upcpocket.feature.services.CampusScreen
import me.chius.upcpocket.feature.settings.AboutScreen
import me.chius.upcpocket.feature.settings.SettingsScreen
import me.chius.upcpocket.feature.shiguang.ShiguangScreen
import me.chius.upcpocket.feature.timetable.TimetableScreen

@Composable
fun UpcPocketNavHost(
    container: AppContainer,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val preferences by container.preferencesRepository.state.collectAsStateWithLifecycle()
    val accountState by container.schoolAccountRepository.state.collectAsStateWithLifecycle()
    val cardBalanceState by container.cardBalanceRepository.uiState.collectAsStateWithLifecycle()
    val cardWalletState by container.cardWalletRepository.uiState.collectAsStateWithLifecycle()
    val powerState by container.powerRepository.uiState.collectAsStateWithLifecycle()
    val timetableState by container.timetableRepository.uiState.collectAsStateWithLifecycle()
    val librarySeatState by container.librarySeatRepository.uiState.collectAsStateWithLifecycle()
    val gradesState by container.gradesRepository.uiState.collectAsStateWithLifecycle()
    val shiguangState by container.shiguangRepository.uiState.collectAsStateWithLifecycle()
    val calendarState by container.academicCalendarRepository.uiState.collectAsStateWithLifecycle()
    val appUpdateState by container.appUpdateChecker.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var isAutoSyncing by remember { mutableStateOf(false) }

    val triggerSmartSync: (Boolean) -> Unit = { force ->
        if (!isAutoSyncing) {
            scope.launch {
                isAutoSyncing = true
                try {
                    val jobs = buildList {
                        if (accountState.status == AccountStatus.SAVED_UNVERIFIED) {
                            add(async { container.timetableRepository.refresh() })
                            if (accountState.isValidStudentId) {
                                add(async { container.cardBalanceRepository.refresh() })
                            }
                            if (force) {
                                add(async { container.librarySeatRepository.refreshActiveSystem() })
                            }
                        }
                        if (powerState.hasBoundDormitory || powerState.lastReading != null) {
                            add(async { container.powerRepository.refreshBoundDormitory() })
                        }
                    }
                    jobs.awaitAll()
                    container.preferencesRepository.setLastAutoSyncEpochMillis(System.currentTimeMillis())
                } finally {
                    isAutoSyncing = false
                }
            }
        }
    }

    val isTopLevelRoute = TopLevelDestination.entries.any { it.route == currentRoute }

    val navigateToTopLevel: (TopLevelDestination) -> Unit = { target ->
        navController.navigate(target.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = isTopLevelRoute,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateToTopLevel(destination) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) {
                                        destination.selectedIcon
                                    } else {
                                        destination.unselectedIcon
                                    },
                                    contentDescription = destination.label,
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { outerPadding ->
        val bottomNavPadding = if (isTopLevelRoute) outerPadding.calculateBottomPadding() else 0.dp

        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.TODAY.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(TopLevelDestination.TODAY.route) {
                TodayScreen(
                    preferences = preferences,
                    accountState = accountState,
                    timetableState = timetableState,
                    cardBalanceState = cardBalanceState,
                    powerState = powerState,
                    librarySeatState = librarySeatState,
                    gradesState = gradesState,
                    isAutoSyncing = isAutoSyncing,
                    bottomNavPadding = bottomNavPadding,
                    onSmartAutoSync = triggerSmartSync,
                    onOpenSettings = { navController.navigate(AppRoute.SETTINGS) },
                    onOpenSchoolAccount = { navController.navigate(AppRoute.SCHOOL_ACCOUNT) },
                    onOpenTimetableTab = { navigateToTopLevel(TopLevelDestination.TIMETABLE) },
                    onOpenCardWallet = { navController.navigate(AppRoute.CARD_WALLET) },
                    onOpenPower = { navController.navigate(AppRoute.POWER) },
                    onOpenLibrarySeat = { navController.navigate(AppRoute.LIBRARY_SEAT) },
                    onOpenGrades = { navController.navigate(AppRoute.GRADES) },
                    onOpenFreeClassroom = { navController.navigate(AppRoute.FREE_CLASSROOM) },
                    onOpenShiguang = { navController.navigate(AppRoute.SHIGUANG) },
                    onOpenAcademicCalendar = { navController.navigate(AppRoute.ACADEMIC_CALENDAR) },
                )
            }

            composable(TopLevelDestination.TIMETABLE.route) {
                TimetableScreen(
                    accountState = accountState,
                    uiState = timetableState,
                    bottomNavPadding = bottomNavPadding,
                    onOpenSchoolAccount = { navController.navigate(AppRoute.SCHOOL_ACCOUNT) },
                    onRefresh = { scope.launch { container.timetableRepository.refresh() } },
                    onSelectWeek = { term, week ->
                        scope.launch { container.timetableRepository.selectWeek(term, week) }
                    },
                    onAdoptWebViewCookies = { cookies, context ->
                        scope.launch { container.timetableRepository.adoptWebViewCookies(cookies, context) }
                    },
                    onDismissInteraction = container.timetableRepository::dismissInteractionPrompt,
                )
            }

            composable(TopLevelDestination.CAMPUS.route) {
                CampusScreen(
                    campus = preferences.campus,
                    accountState = accountState,
                    timetableState = timetableState,
                    gradesState = gradesState,
                    shiguangState = shiguangState,
                    librarySeatState = librarySeatState,
                    cardBalanceState = cardBalanceState,
                    powerState = powerState,
                    bottomNavPadding = bottomNavPadding,
                    onSelectCampus = { campus ->
                        scope.launch { container.preferencesRepository.setCampus(campus) }
                    },
                    onOpenSettings = { navController.navigate(AppRoute.SETTINGS) },
                    onOpenSchoolAccount = { navController.navigate(AppRoute.SCHOOL_ACCOUNT) },
                    onOpenTimetableTab = { navigateToTopLevel(TopLevelDestination.TIMETABLE) },
                    onOpenGrades = { navController.navigate(AppRoute.GRADES) },
                    onOpenShiguang = { navController.navigate(AppRoute.SHIGUANG) },
                    onOpenLibrarySeat = { navController.navigate(AppRoute.LIBRARY_SEAT) },
                    onOpenFreeClassroom = { navController.navigate(AppRoute.FREE_CLASSROOM) },
                    onOpenCardWallet = { navController.navigate(AppRoute.CARD_WALLET) },
                    onOpenPower = { navController.navigate(AppRoute.POWER) },
                    onOpenAcademicCalendar = { navController.navigate(AppRoute.ACADEMIC_CALENDAR) },
                )
            }

            composable(AppRoute.GRADES) {
                GradesScreen(
                    accountState = accountState,
                    uiState = gradesState,
                    onBack = { navController.popBackStack() },
                    onOpenSchoolAccount = { navController.navigate(AppRoute.SCHOOL_ACCOUNT) },
                    onRefresh = { scope.launch { container.gradesRepository.refresh() } },
                    onClearCache = { scope.launch { container.gradesRepository.clearCache() } },
                    onAdoptWebViewCookies = { cookies, context ->
                        scope.launch { container.gradesRepository.adoptWebViewCookies(cookies, context) }
                    },
                    onDismissInteraction = container.gradesRepository::dismissInteractionPrompt,
                )
            }

            composable(AppRoute.CARD_WALLET) {
                CardWalletScreen(
                    accountState = accountState,
                    balanceState = cardBalanceState,
                    walletState = cardWalletState,
                    onBack = { navController.popBackStack() },
                    onOpenSchoolAccount = { navController.navigate(AppRoute.SCHOOL_ACCOUNT) },
                    onRefreshBalance = { scope.launch { container.cardBalanceRepository.refresh() } },
                    onLoadHistory = { month, nextPage ->
                        scope.launch { container.cardWalletRepository.loadHistory(month, nextPage) }
                    },
                    onPrepareRecharge = {
                        scope.launch { container.cardWalletRepository.prepareRecharge() }
                    },
                    onSubmitRecharge = { yuan ->
                        scope.launch { container.cardWalletRepository.submitRecharge(yuan) }
                    },
                    onRefreshPendingOrder = {
                        scope.launch { container.cardWalletRepository.refreshPendingOrderStatus() }
                    },
                    onAdoptWebViewUrl = { url, ctx ->
                        scope.launch { container.cardWalletRepository.adoptWebViewUrl(url, ctx) }
                    },
                    onDismissInteraction = container.cardWalletRepository::dismissInteractionPrompt,
                )
            }

            composable(AppRoute.POWER) {
                PowerScreen(
                    preferredCampus = preferences.campus,
                    uiState = powerState,
                    onBack = { navController.popBackStack() },
                    onLoadBuildings = { scope.launch { container.powerRepository.loadBuildings() } },
                    onQuery = { building, room ->
                        scope.launch { container.powerRepository.query(building, room) }
                    },
                    onBindDormitory = { building, room ->
                        scope.launch { container.powerRepository.bindDormitory(building, room) }
                    },
                    onClearBoundDormitory = {
                        scope.launch { container.powerRepository.clearBoundDormitory() }
                    },
                    onClearSaved = { scope.launch { container.powerRepository.clearSaved() } },
                )
            }

            composable(AppRoute.FREE_CLASSROOM) {
                FreeClassroomScreen(
                    preferredCampus = preferences.campus,
                    repository = container.freeClassroomRepository,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(AppRoute.LIBRARY_SEAT) {
                LibrarySeatScreen(
                    preferredCampus = preferences.campus,
                    accountState = accountState,
                    uiState = librarySeatState,
                    onBack = { navController.popBackStack() },
                    onOpenSchoolAccount = { navController.navigate(AppRoute.SCHOOL_ACCOUNT) },
                    onInitialize = container.librarySeatRepository::syncAccountStatusAndCampus,
                    onSelectSystem = { tab ->
                        scope.launch { container.librarySeatRepository.selectSystem(tab) }
                    },
                    onRefresh = { scope.launch { container.librarySeatRepository.refreshActiveSystem() } },
                    onSelectLibseatDate = { date ->
                        scope.launch { container.librarySeatRepository.selectLibseatDate(date) }
                    },
                    onSelectLibseatBuilding = { building ->
                        scope.launch { container.librarySeatRepository.selectLibseatBuilding(building) }
                    },
                    onSelectLibseatFloor = container.librarySeatRepository::selectLibseatFloor,
                    onOpenLibseatRoom = { room ->
                        scope.launch { container.librarySeatRepository.loadLibseatRoomLayout(room) }
                    },
                    onDismissLibseatRoom = container.librarySeatRepository::dismissLibseatRoomDetail,
                    onSelectLibseatSeat = { seat ->
                        scope.launch { container.librarySeatRepository.selectLibseatSeat(seat) }
                    },
                    onSelectLibseatStartTime = { option ->
                        scope.launch { container.librarySeatRepository.selectLibseatStartTime(option) }
                    },
                    onSelectLibseatEndTime = container.librarySeatRepository::selectLibseatEndTime,
                    onDismissLibseatSeatSheet = container.librarySeatRepository::dismissLibseatSeatSheet,
                    onReserveLibseatSeat = {
                        scope.launch { container.librarySeatRepository.reserveSelectedLibseatSeat() }
                    },
                    onCancelLibseatReservation = { reservation ->
                        scope.launch { container.librarySeatRepository.cancelLibseatReservation(reservation) }
                    },
                    onSelectChaoxingDate = { date ->
                        scope.launch { container.librarySeatRepository.selectChaoxingDate(date) }
                    },
                    onOpenChaoxingRoom = { room ->
                        scope.launch { container.librarySeatRepository.loadChaoxingRoomDetail(room) }
                    },
                    onUpdateChaoxingWindow = { start, end ->
                        scope.launch { container.librarySeatRepository.updateChaoxingWindow(start, end) }
                    },
                    onSelectChaoxingSeat = { seatNum ->
                        scope.launch { container.librarySeatRepository.loadChaoxingSeatUsedTimes(seatNum) }
                    },
                    onReserveChaoxingSeat = {
                        scope.launch { container.librarySeatRepository.reserveChaoxingSeat() }
                    },
                    onDismissChaoxingRoom = container.librarySeatRepository::dismissChaoxingRoomDetail,
                    onDismissReservationMessage = container.librarySeatRepository::dismissReservationMessage,
                    onAdoptLibseatUrl = { rawUrl, ctx ->
                        scope.launch { container.librarySeatRepository.adoptLibseatWebViewToken(rawUrl, ctx) }
                    },
                    onAdoptChaoxingCookies = { cookies, ctx ->
                        scope.launch { container.librarySeatRepository.adoptChaoxingWebViewCookies(cookies, ctx) }
                    },
                    onDismissInteraction = container.librarySeatRepository::dismissInteractionPrompt,
                    onClearSessions = container.librarySeatRepository::clearInMemorySessions,
                )
            }

            composable(AppRoute.SHIGUANG) {
                ShiguangScreen(
                    accountState = accountState,
                    uiState = shiguangState,
                    onBack = { navController.popBackStack() },
                    onOpenSchoolAccount = { navController.navigate(AppRoute.SCHOOL_ACCOUNT) },
                    onSelectTab = container.shiguangRepository::selectTab,
                    onRefresh = { keyword ->
                        scope.launch { container.shiguangRepository.refresh(keyword) }
                    },
                    onLoadPage = { page, mine ->
                        scope.launch { container.shiguangRepository.loadPage(page, mine) }
                    },
                    onOpenDetail = { activity ->
                        scope.launch { container.shiguangRepository.loadDetail(activity) }
                    },
                    onDismissDetail = container.shiguangRepository::dismissDetail,
                    onAdoptWebViewCookies = { cookies, ctx ->
                        scope.launch { container.shiguangRepository.adoptWebViewCookies(cookies, ctx) }
                    },
                    onDismissInteraction = container.shiguangRepository::dismissInteractionPrompt,
                )
            }

            composable(AppRoute.ACADEMIC_CALENDAR) {
                AcademicCalendarScreen(
                    uiState = calendarState,
                    onBack = { navController.popBackStack() },
                    onLoadLatest = {
                        scope.launch { container.academicCalendarRepository.loadLatestIfEmpty() }
                    },
                    onRefresh = {
                        scope.launch { container.academicCalendarRepository.refresh() }
                    },
                    onSelectArticle = { article ->
                        scope.launch { container.academicCalendarRepository.selectArticle(article) }
                    },
                )
            }

            composable(AppRoute.SETTINGS) {
                SettingsScreen(
                    preferences = preferences,
                    accountState = accountState,
                    powerState = powerState,
                    appUpdateState = appUpdateState,
                    onBack = { navController.popBackStack() },
                    onOpenSchoolAccount = { navController.navigate(AppRoute.SCHOOL_ACCOUNT) },
                    onOpenPower = { navController.navigate(AppRoute.POWER) },
                    onOpenAbout = { navController.navigate(AppRoute.ABOUT) },
                    onSelectCampus = { campus ->
                        scope.launch { container.preferencesRepository.setCampus(campus) }
                    },
                    onSelectTheme = { theme ->
                        scope.launch { container.preferencesRepository.setTheme(theme) }
                    },
                    onSelectColorSeed = { seed ->
                        scope.launch { container.preferencesRepository.setColorSeed(seed) }
                    },
                    onToggleDynamicColor = { enabled ->
                        scope.launch { container.preferencesRepository.setDynamicColorEnabled(enabled) }
                    },
                    onCheckForUpdates = {
                        scope.launch { container.appUpdateChecker.checkForUpdates() }
                    },
                    onDismissUpdate = container.appUpdateChecker::dismiss,
                )
            }

            composable(AppRoute.SCHOOL_ACCOUNT) {
                SchoolAccountScreen(
                    accountState = accountState,
                    onBack = { navController.popBackStack() },
                    onSaveAccount = { loginName, password ->
                        container.schoolAccountRepository.save(loginName, password)
                    },
                    onClearAccount = { container.schoolAccountRepository.clear() },
                )
            }

            composable(AppRoute.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
