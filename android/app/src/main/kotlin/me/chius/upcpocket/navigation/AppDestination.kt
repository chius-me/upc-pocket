package me.chius.upcpocket.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Today
import androidx.compose.ui.graphics.vector.ImageVector

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    TODAY(
        route = "today",
        label = "今日",
        selectedIcon = Icons.Rounded.Today,
        unselectedIcon = Icons.Outlined.Today,
    ),
    TIMETABLE(
        route = "timetable",
        label = "课表",
        selectedIcon = Icons.Rounded.CalendarToday,
        unselectedIcon = Icons.Outlined.CalendarToday,
    ),
    CAMPUS(
        route = "campus",
        label = "校园",
        selectedIcon = Icons.Rounded.GridView,
        unselectedIcon = Icons.Outlined.GridView,
    ),
}

object AppRoute {
    const val SETTINGS = "settings"
    const val SCHOOL_ACCOUNT = "school_account"
    const val ABOUT = "about"
    const val GRADES = "grades"
    const val CARD_WALLET = "card_wallet"
    const val LIBRARY_SEAT = "library_seat"
    const val FREE_CLASSROOM = "free_classroom"
    const val POWER = "power"
    const val SHIGUANG = "shiguang"
    const val ACADEMIC_CALENDAR = "academic_calendar"
}
