package me.chius.upcpocket.core.model

data class OfficialWebEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val url: String,
    val campus: CampusPreference = CampusPreference.ALL,
)

data class PlannedServiceEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val milestone: String,
    val campus: CampusPreference = CampusPreference.ALL,
    val externalUrl: String? = null,
)

object CampusServiceCatalog {
    val queryServices: List<PlannedServiceEntry> = listOf(
        PlannedServiceEntry(
            id = "free_classroom",
            title = "空闲教室",
            subtitle = "两校区教学楼与时段查询",
            milestone = "M1",
            campus = CampusPreference.ALL,
        ),
        PlannedServiceEntry(
            id = "electricity_tangdaowan",
            title = "唐岛湾剩余电量",
            subtitle = "楼号与寝室号只读查询",
            milestone = "M1",
            campus = CampusPreference.TANGDAOWAN,
        ),
        PlannedServiceEntry(
            id = "electricity_guzhenkou",
            title = "古镇口电费",
            subtitle = "官方 HTTPS 页面（不绕过证书校验）",
            milestone = "外部页面",
            campus = CampusPreference.GUZHENKOU,
            externalUrl = "https://gzkdf.upc.edu.cn/acmswx/index.aspx",
        ),
        PlannedServiceEntry(
            id = "campus_card_balance",
            title = "校园卡余额",
            subtitle = "仅发送本人有效学号，不发送密码",
            milestone = "M1",
            campus = CampusPreference.ALL,
        ),
        PlannedServiceEntry(
            id = "library_seat",
            title = "图书馆座位",
            subtitle = "唐岛湾 libseat / 古镇口东区 / 求是只读",
            milestone = "M2",
            campus = CampusPreference.ALL,
        ),
    )

    val officialWebEntries: List<OfficialWebEntry> = listOf(
        OfficialWebEntry(
            id = "academic_calendar",
            title = "校历（教务处）",
            subtitle = "jwc.upc.edu.cn · 官方公开栏目",
            url = "https://jwc.upc.edu.cn/603/list.htm",
        ),
        OfficialWebEntry(
            id = "academic_affairs_system",
            title = "教务系统",
            subtitle = "jwxt.upc.edu.cn · 外部浏览器打开",
            url = "https://jwxt.upc.edu.cn/jsxsd/framework/xsMainV.htmlx",
        ),
        OfficialWebEntry(
            id = "digital_upc",
            title = "数字石大",
            subtitle = "i.upc.edu.cn · 校内综合门户",
            url = "https://i.upc.edu.cn/",
        ),
        OfficialWebEntry(
            id = "academic_affairs_website",
            title = "教务处官网",
            subtitle = "jwc.upc.edu.cn · 公告与教学信息",
            url = "https://jwc.upc.edu.cn/",
        ),
        OfficialWebEntry(
            id = "colleges_directory",
            title = "学院官网",
            subtitle = "upc.edu.cn · 教学科研单位目录",
            url = "https://www.upc.edu.cn/jxkydw.htm",
        ),
    )

    val deferredReadOnlyEntries: List<PlannedServiceEntry> = listOf(
        PlannedServiceEntry(
            id = "shiguang",
            title = "石光（第二课堂）",
            subtitle = "活动列表与五模块学时只读（不报名/不签到）",
            milestone = "M3",
        ),
        PlannedServiceEntry(
            id = "service_portal",
            title = "e站通",
            subtitle = "事项目录与办理进度只读",
            milestone = "M3",
        ),
    )
}
