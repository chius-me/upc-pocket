package me.chius.upcpocket.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import me.chius.upcpocket.core.designsystem.theme.GroupedCardPosition
import me.chius.upcpocket.feature.timetable.CourseMeeting
import me.chius.upcpocket.feature.timetable.TimetableWeek

private data class QuickSearchAction(
    val id: String,
    val title: String,
    val subtitle: String,
    val keywords: String,
    val icon: ImageVector,
    val onOpen: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSearchSheet(
    timetableWeek: TimetableWeek?,
    onDismiss: () -> Unit,
    onOpenTimetableTab: () -> Unit,
    onOpenGrades: () -> Unit,
    onOpenShiguang: () -> Unit,
    onOpenLibrarySeat: () -> Unit,
    onOpenFreeClassroom: () -> Unit,
    onOpenCardWallet: () -> Unit,
    onOpenPower: () -> Unit,
    onOpenAcademicCalendar: () -> Unit,
    onOpenSchoolAccount: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    val actions = remember(
        onOpenTimetableTab,
        onOpenGrades,
        onOpenShiguang,
        onOpenLibrarySeat,
        onOpenFreeClassroom,
        onOpenCardWallet,
        onOpenPower,
        onOpenAcademicCalendar,
        onOpenSchoolAccount,
        onOpenSettings,
    ) {
        listOf(
            QuickSearchAction("grades", "成绩与 GPA", "教务成绩单、绩点统计与模拟推演", "chengji gpa jidian 成绩 绩点 教务", Icons.Rounded.School, onOpenGrades),
            QuickSearchAction("card", "校园卡与充值", "余额、流水统计与微信充值", "xiaoyuanka fanka chongzhi 校园卡 饭卡 充值 流水", Icons.Rounded.CreditCard, onOpenCardWallet),
            QuickSearchAction("seat", "图书馆选座", "唐岛湾与古镇口/求是馆区预约与取消", "tushuguan xuanzuo libseat 图书馆 选座 预约", Icons.Rounded.EventSeat, onOpenLibrarySeat),
            QuickSearchAction("room", "空闲教室", "按节次、校区与教学楼检索实时空房", "kongjiaoshi 空闲教室 空房 自习", Icons.Rounded.MeetingRoom, onOpenFreeClassroom),
            QuickSearchAction("power", "宿舍电量", "唐岛湾常用宿舍电费绑定与古镇口查询", "dianliang dianfei sushe 电量 电费 宿舍", Icons.Rounded.Bolt, onOpenPower),
            QuickSearchAction("shiguang", "石光第二课堂", "活动列表与五大模块学分学时成绩单", "shiguang dekt erke 石光 二课 第二课堂 学时", Icons.Rounded.EmojiEvents, onOpenShiguang),
            QuickSearchAction("calendar", "官方校历", "教务处学年校历高清浏览与离线缓存", "xiaoli jwc 校历 假期 教学周", Icons.Rounded.CalendarMonth, onOpenAcademicCalendar),
            QuickSearchAction("timetable", "本周课表", "教学周网格与日程流视图", "kebiao kecheng 课表 课程 周课表", Icons.Rounded.CalendarToday, onOpenTimetableTab),
            QuickSearchAction("account", "学校账号", "配置或更新本机加密保存的统一身份凭据", "zhanghao cas 登录 学号 密码", Icons.Rounded.Person, onOpenSchoolAccount),
            QuickSearchAction("settings", "外观与设置", "配色主题、常用校区与版本检查", "shezhi zhuti 设置 主题 更新 校区", Icons.Rounded.Settings, onOpenSettings),
        )
    }

    val normalized = query.trim().lowercase()
    val filteredActions = remember(normalized, actions) {
        if (normalized.isEmpty()) {
            actions
        } else {
            actions.filter {
                it.title.lowercase().contains(normalized) ||
                    it.subtitle.lowercase().contains(normalized) ||
                    it.keywords.contains(normalized)
            }
        }
    }

    val uniqueCourses = remember(timetableWeek) {
        timetableWeek?.meetings
            .orEmpty()
            .distinctBy { "${it.courseName}|${it.weekday}|${it.startPeriod}|${it.location.orEmpty()}" }
    }
    val matchedCourses = remember(normalized, uniqueCourses) {
        if (normalized.isEmpty()) {
            emptyList()
        } else {
            uniqueCourses.filter { m ->
                m.courseName.lowercase().contains(normalized) ||
                    m.location.orEmpty().lowercase().contains(normalized) ||
                    m.teacher.orEmpty().lowercase().contains(normalized)
            }.take(8)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                singleLine = true,
                placeholder = { Text("搜索校园服务或本周课程…") },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空搜索")
                        }
                    }
                },
            )
            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (matchedCourses.isNotEmpty()) {
                    item {
                        ExpressiveSectionHeader(
                            title = "本周匹配课程",
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    items(
                        count = matchedCourses.size,
                        key = { idx ->
                            val c = matchedCourses[idx]
                            "course_${idx}_${c.courseName}_${c.weekday}_${c.startPeriod}"
                        },
                    ) { idx ->
                        val meeting = matchedCourses[idx]
                        ExpressiveListRow(
                            title = meeting.courseName,
                            subtitle = courseSubtitle(meeting),
                            leadingIcon = Icons.Rounded.Schedule,
                            leadingContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            leadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            position = GroupedCardPosition.forIndex(idx, matchedCourses.size),
                            trailingContent = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = {
                                onDismiss()
                                onOpenTimetableTab()
                            },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(12.dp)) }
                }

                if (filteredActions.isNotEmpty()) {
                    item {
                        ExpressiveSectionHeader(
                            title = if (normalized.isEmpty()) "全部功能直达" else "匹配功能",
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    items(
                        count = filteredActions.size,
                        key = { idx -> "action_${filteredActions[idx].id}" },
                    ) { idx ->
                        val item = filteredActions[idx]
                        ExpressiveListRow(
                            title = item.title,
                            subtitle = item.subtitle,
                            leadingIcon = item.icon,
                            position = GroupedCardPosition.forIndex(idx, filteredActions.size),
                            trailingContent = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = {
                                onDismiss()
                                item.onOpen()
                            },
                        )
                    }
                } else if (matchedCourses.isEmpty()) {
                    item {
                        Text(
                            text = "未找到匹配的课程或功能",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp, horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun courseSubtitle(meeting: CourseMeeting): String {
    val dayName = when (meeting.weekday) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }
    return listOfNotNull(
        "$dayName ${meeting.periodRangeLabel}",
        meeting.location,
        meeting.teacher,
    ).joinToString(" · ")
}
