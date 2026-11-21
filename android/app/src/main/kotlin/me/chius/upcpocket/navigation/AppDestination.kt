package me.chius.upcpocket.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.ui.graphics.vector.ImageVector

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME(
        route = "home",
        label = "首页",
        selectedIcon = Icons.Rounded.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    TIMETABLE(
        route = "timetable",
        label = "课表",
        selectedIcon = Icons.Rounded.CalendarMonth,
        unselectedIcon = Icons.Outlined.CalendarMonth,
    ),
    SERVICES(
        route = "services",
        label = "服务",
        selectedIcon = Icons.Rounded.GridView,
        unselectedIcon = Icons.Outlined.GridView,
    ),
    FOCUS(
        route = "focus",
        label = "专注",
        selectedIcon = Icons.Rounded.Timer,
        unselectedIcon = Icons.Outlined.Timer,
    ),
}

object AppRoute {
    const val SETTINGS = "settings"
    const val SCHOOL_ACCOUNT = "school_account"
    const val ABOUT = "about"
    const val CARD_BALANCE = "card_balance"
    const val POWER = "power"
    const val FREE_CLASSROOM = "free_classroom"
    const val TODOS = "todos"
}
