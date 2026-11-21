package me.chius.upcpocket.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import me.chius.upcpocket.AppContainer
import me.chius.upcpocket.feature.account.SchoolAccountScreen
import me.chius.upcpocket.feature.card.CardBalanceScreen
import me.chius.upcpocket.feature.classroom.FreeClassroomScreen
import me.chius.upcpocket.feature.focus.FocusScreen
import me.chius.upcpocket.feature.home.HomeScreen
import me.chius.upcpocket.feature.library.LibrarySeatScreen
import me.chius.upcpocket.feature.power.PowerScreen
import me.chius.upcpocket.feature.services.ServicesScreen
import me.chius.upcpocket.feature.settings.AboutScreen
import me.chius.upcpocket.feature.settings.SettingsScreen
import me.chius.upcpocket.feature.timetable.TimetableScreen
import me.chius.upcpocket.feature.todo.TodoScreen

@Composable
fun UpcPocketNavHost(
    container: AppContainer,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val preferences by container.preferencesRepository.state.collectAsStateWithLifecycle()
    val accountState by container.schoolAccountRepository.state.collectAsStateWithLifecycle()
    val cardState by container.cardBalanceRepository.uiState.collectAsStateWithLifecycle()
    val powerState by container.powerRepository.uiState.collectAsStateWithLifecycle()
    val timetableState by container.timetableRepository.uiState.collectAsStateWithLifecycle()
    val librarySeatState by container.librarySeatRepository.uiState.collectAsStateWithLifecycle()
    val todoState by container.todoRepository.uiState.collectAsStateWithLifecycle()
    val focusState by container.focusTimerController.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

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
                NavigationBar {
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
        val bottomNavPadding = if (isTopLevelRoute) {
            outerPadding.calculateBottomPadding()
        } else {
            0.dp
        }

        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(TopLevelDestination.HOME.route) {
                HomeScreen(
                    campus = preferences.campus,
                    accountState = accountState,
                    cardState = cardState,
                    powerState = powerState,
                    timetableState = timetableState,
                    todoState = todoState,
                    bottomNavPadding = bottomNavPadding,
                    onOpenSettings = { navController.navigate(AppRoute.SETTINGS) },
                    onOpenSchoolAccount = { navController.navigate(AppRoute.SCHOOL_ACCOUNT) },
                    onOpenTimetableTab = { navigateToTopLevel(TopLevelDestination.TIMETABLE) },
                    onOpenTodos = { navController.navigate(AppRoute.TODOS) },
                    onOpenCardBalance = { navController.navigate(AppRoute.CARD_BALANCE) },
                    onOpenPower = { navController.navigate(AppRoute.POWER) },
                    onOpenFreeClassroom = { navController.navigate(AppRoute.FREE_CLASSROOM) },
                    onOpenLibrarySeat = { navController.navigate(AppRoute.LIBRARY_SEAT) },
                )
            }

            composable(TopLevelDestination.TIMETABLE.route) {
                TimetableScreen(
                    accountState = accountState,
                    uiState = timetableState,
                    todoState = todoState,
                    bottomNavPadding = bottomNavPadding,
                    onOpenSchoolAccount = { navController.navigate(AppRoute.SCHOOL_ACCOUNT) },
                    onRefresh = {
                        scope.launch { container.timetableRepository.refresh() }
                    },
                    onSelectWeek = { term, week ->
                        scope.launch { container.timetableRepository.selectWeek(term, week) }
                    },
                    onAdoptWebViewCookies = { cookies, context ->
                        scope.launch {
                            container.timetableRepository.adoptWebViewCookies(cookies, context)
                        }
                    },
                    onDismissInteraction = container.timetableRepository::dismissInteractionPrompt,
                    onClearCache = {
                        scope.launch { container.timetableRepository.clearLocalCache() }
                    },
                    onOpenTodos = { navController.navigate(AppRoute.TODOS) },
                    onToggleTodoCompleted = { id ->
                        scope.launch { container.todoRepository.toggleCompleted(id) }
                    },
                    onUpsertTodo = { id, title, dueAt, reminderAt ->
                        scope.launch {
                            container.todoRepository.upsertTask(id, title, dueAt, reminderAt)
                        }
                    },
                )
            }

            composable(TopLevelDestination.SERVICES.route) {
                ServicesScreen(
                    campus = preferences.campus,
                    bottomNavPadding = bottomNavPadding,
                    onSelectCampus = { campus ->
                        scope.launch { container.preferencesRepository.setCampus(campus) }
                    },
                    onOpenCardBalance = { navController.navigate(AppRoute.CARD_BALANCE) },
                    onOpenPower = { navController.navigate(AppRoute.POWER) },
                    onOpenFreeClassroom = { navController.navigate(AppRoute.FREE_CLASSROOM) },
                    onOpenLibrarySeat = { navController.navigate(AppRoute.LIBRARY_SEAT) },
                )
            }

            composable(TopLevelDestination.FOCUS.route) {
                FocusScreen(
                    uiState = focusState,
                    bottomNavPadding = bottomNavPadding,
                    onSelectMode = { mode ->
                        scope.launch { container.focusTimerController.selectMode(mode) }
                    },
                    onToggleStartPause = {
                        scope.launch { container.focusTimerController.toggleStartPause() }
                    },
                    onReset = {
                        scope.launch { container.focusTimerController.reset() }
                    },
                    onFinishEarly = {
                        scope.launch { container.focusTimerController.finishEarly() }
                    },
                    onClearHistory = {
                        scope.launch { container.focusTimerController.clearHistory() }
                    },
                )
            }

            composable(AppRoute.TODOS) {
                TodoScreen(
                    uiState = todoState,
                    onBack = { navController.popBackStack() },
                    onUpsertTask = { id, title, dueAt, reminderAt ->
                        scope.launch {
                            container.todoRepository.upsertTask(id, title, dueAt, reminderAt)
                        }
                    },
                    onToggleCompleted = { id ->
                        scope.launch { container.todoRepository.toggleCompleted(id) }
                    },
                    onDeleteTask = { id ->
                        scope.launch { container.todoRepository.deleteTask(id) }
                    },
                    onClearCompleted = {
                        scope.launch { container.todoRepository.clearCompleted() }
                    },
                )
            }

            composable(AppRoute.CARD_BALANCE) {
                CardBalanceScreen(
                    accountState = accountState,
                    uiState = cardState,
                    onBack = { navController.popBackStack() },
                    onOpenSchoolAccount = { navController.navigate(AppRoute.SCHOOL_ACCOUNT) },
                    onRefresh = {
                        scope.launch { container.cardBalanceRepository.refresh() }
                    },
                    onClearCache = {
                        scope.launch { container.cardBalanceRepository.clearCache() }
                    },
                )
            }

            composable(AppRoute.POWER) {
                PowerScreen(
                    preferredCampus = preferences.campus,
                    uiState = powerState,
                    onBack = { navController.popBackStack() },
                    onLoadBuildings = {
                        scope.launch { container.powerRepository.loadBuildings() }
                    },
                    onQuery = { building, room ->
                        scope.launch { container.powerRepository.query(building, room) }
                    },
                    onClearSaved = {
                        scope.launch { container.powerRepository.clearSaved() }
                    },
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
                    onRefresh = {
                        scope.launch { container.librarySeatRepository.refreshActiveSystem() }
                    },
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
                    onDismissChaoxingRoom = container.librarySeatRepository::dismissChaoxingRoomDetail,
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

            composable(AppRoute.SETTINGS) {
                SettingsScreen(
                    preferences = preferences,
                    accountState = accountState,
                    onBack = { navController.popBackStack() },
                    onOpenSchoolAccount = { navController.navigate(AppRoute.SCHOOL_ACCOUNT) },
                    onOpenAbout = { navController.navigate(AppRoute.ABOUT) },
                    onSelectCampus = { campus ->
                        scope.launch { container.preferencesRepository.setCampus(campus) }
                    },
                    onSelectTheme = { theme ->
                        scope.launch { container.preferencesRepository.setTheme(theme) }
                    },
                    onToggleDynamicColor = { enabled ->
                        scope.launch { container.preferencesRepository.setDynamicColorEnabled(enabled) }
                    },
                )
            }

            composable(AppRoute.SCHOOL_ACCOUNT) {
                SchoolAccountScreen(
                    accountState = accountState,
                    onBack = { navController.popBackStack() },
                    onSaveAccount = { loginName, password ->
                        container.schoolAccountRepository.save(loginName, password)
                    },
                    onClearAccount = {
                        container.schoolAccountRepository.clear()
                    },
                )
            }

            composable(AppRoute.ABOUT) {
                AboutScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
