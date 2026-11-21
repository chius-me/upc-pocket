import SwiftUI

struct TimetableView: View {
    @Bindable var environment: AppEnvironment
    @State private var showWebViewSheet = false
    @State private var showTodoEditor = false
    @State private var selectedMeeting: CourseMeeting?

    private var state: TimetableUiState { environment.timetableState }
    private var todoState: TodoUiState { environment.todoState }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if todoState.todayScheduleTasks.isEmpty {
                        Label {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("今日暂无未完成待办")
                                    .font(.headline)
                                Text("可设置独立截止时间与一次性系统提醒")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: "checklist")
                                .foregroundStyle(UPCTheme.campusJade)
                        }
                    } else {
                        let nowMillis = Int64(Date().timeIntervalSince1970 * 1000)
                        ForEach(todoState.todayScheduleTasks) { task in
                            let urgent = TodoOrdering.isOverdueOrToday(task, nowMillis: nowMillis)
                            HStack(spacing: 10) {
                                Button {
                                    Task { await environment.toggleTodoCompleted(task.id) }
                                } label: {
                                    Image(systemName: task.completed ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(task.completed ? UPCTheme.campusJade : .secondary)
                                }
                                .buttonStyle(.plain)

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(task.title)
                                        .lineLimit(1)
                                    if let due = task.dueAt {
                                        Text("截止 \(TodoView.formatMillis(due))")
                                            .font(.caption2)
                                            .foregroundStyle(urgent ? Color.red : .secondary)
                                    }
                                }
                            }
                        }
                    }

                    HStack {
                        Button("新建待办") {
                            showTodoEditor = true
                        }
                        Spacer()
                        NavigationLink("全部（\(todoState.incompleteTasks.count)）") {
                            TodoView(environment: environment)
                        }
                        .fixedSize()
                    }
                } header: {
                    Text("今日日程待办")
                }

                if environment.accountState.status != .savedUnverified {
                    Section("周课表（周日–周六 · 1–12 节）") {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text("只读周视图")
                                    .font(.headline)
                                Spacer()
                                Text(environment.accountState.status.label)
                                    .font(.caption.weight(.medium))
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 3)
                                    .background(UPCTheme.brandBlue.opacity(0.14), in: Capsule())
                                    .foregroundStyle(UPCTheme.brandBlue)
                            }
                            Text(accountBlockedHint)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 4)

                        NavigationLink {
                            SchoolAccountView(environment: environment)
                        } label: {
                            Label("配置或管理学校账号", systemImage: "person.badge.key")
                        }
                    }
                } else {
                    Section {
                        weekControlsRow
                    } header: {
                        Text("周课表（周日–周六 · 1–12 节）")
                    } footer: {
                        if let detail = state.statusDetail {
                            Text(detail)
                        }
                    }

                    if state.connectionStatus == .interactionRequired,
                       state.interactionSession != nil {
                        Section {
                            VStack(alignment: .leading, spacing: 10) {
                                Label("需要完成学校安全验证", systemImage: "lock.open.display")
                                    .font(.headline)
                                    .foregroundStyle(UPCTheme.noticeAmber)
                                Text("\(state.interactionReason ?? "需要验证码或短信验证")。客户端已停止密码提交并保留离线缓存，请使用唯一受控安全验证页完成登录。")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Button {
                                    showWebViewSheet = true
                                } label: {
                                    Text("打开受控安全验证页")
                                        .frame(maxWidth: .infinity)
                                }
                                .buttonStyle(.borderedProminent)
                            }
                            .padding(.vertical, 4)
                        }
                    } else if state.connectionStatus == .invalidCredentials {
                        Section {
                            VStack(alignment: .leading, spacing: 8) {
                                Label("学号或密码验证失败", systemImage: "exclamationmark.triangle.fill")
                                    .font(.headline)
                                    .foregroundStyle(.red)
                                Text(state.errorMessage ?? "请前往学校账号页核对凭据")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                NavigationLink("前往核对学校账号") {
                                    SchoolAccountView(environment: environment)
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    } else if let errorMessage = state.errorMessage {
                        Section {
                            HStack(spacing: 10) {
                                Image(systemName: "wifi.exclamationmark")
                                    .foregroundStyle(.red)
                                Text(state.isOfflineFallback ? "同步失败（\(errorMessage)）" : errorMessage)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Spacer()
                                Button("重试") {
                                    Task { await environment.refreshTimetable() }
                                }
                                .font(.caption.weight(.semibold))
                            }
                        }
                    }

                    if state.isOfflineFallback, state.weekData != nil {
                        Section {
                            Label {
                                Text(offlineBannerText)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            } icon: {
                                Image(systemName: "icloud.slash")
                                    .foregroundStyle(UPCTheme.noticeAmber)
                            }
                        }
                    }

                    if let weekData = state.weekData {
                        Section {
                            if weekData.isEmpty {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("\(weekData.term.label) · 第 \(weekData.week) 周暂无排课记录")
                                        .font(.headline)
                                    Text("学校服务端返回完整 7 天无课程安排，可切换上下周或点击刷新确认。")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                .padding(.vertical, 6)
                            } else {
                                TimetableWeekGridView(weekData: weekData) { meeting in
                                    selectedMeeting = meeting
                                }
                                .listRowInsets(EdgeInsets(top: 8, leading: 6, bottom: 8, trailing: 6))
                            }
                        }
                    } else if !state.isRefreshing {
                        Section {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("等待初次同步")
                                    .font(.headline)
                                Text("点击「同步课表」将仅向 cas.upc.edu.cn 提交一次统一认证（遇验证码自动转入受控安全页），成功后离线缓存本周课表。")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Button("同步课表") {
                                    Task { await environment.refreshTimetable() }
                                }
                                .buttonStyle(.borderedProminent)
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }

                Section("官方作息时间参考（2020-05-25 调整版）") {
                    scheduleRow(title: "上午 1–5 节", time: "08:00 – 12:20")
                    scheduleRow(title: "下午 6–9 节", time: "14:00 – 17:30")
                    scheduleRow(title: "晚间 10–12 节", time: "19:00 – 21:25")
                    scheduleRow(title: "第 13–14 节", time: "时间待确认")
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("课表")
            .toolbar {
                if state.weekData != nil || !state.terms.isEmpty {
                    ToolbarItem(placement: .topBarLeading) {
                        Button(role: .destructive) {
                            Task { await environment.clearTimetableCache() }
                        } label: {
                            Image(systemName: "trash")
                        }
                        .disabled(state.isRefreshing)
                        .accessibilityLabel("清除离线课表缓存")
                    }
                }
                if environment.accountState.status == .savedUnverified {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            Task { await environment.refreshTimetable() }
                        } label: {
                            if state.isRefreshing {
                                ProgressView()
                            } else {
                                Image(systemName: "arrow.clockwise")
                            }
                        }
                        .disabled(state.isRefreshing)
                        .accessibilityLabel("同步课表")
                    }
                }
            }
            .sheet(isPresented: $showWebViewSheet) {
                if let context = state.interactionSession,
                   context.isStillValid(for: environment.accountState) {
                    ControlledSchoolWebViewSheet(
                        sessionContext: context,
                        accountState: environment.accountState,
                        scopeHosts: ControlledWebViewPolicy.timetableScopeHosts,
                        targetCookieHost: UpcTimetableAuthEndpoints.appHost,
                        completionPathPrefixes: ["/site/weekschedule/"],
                        onCookiesCaptured: { cookies in
                            showWebViewSheet = false
                            Task {
                                await environment.adoptTimetableWebViewCookies(cookies, context: context)
                            }
                        },
                        onDismiss: {
                            showWebViewSheet = false
                            Task {
                                await environment.dismissTimetableInteractionPrompt()
                            }
                        }
                    )
                }
            }
            .sheet(item: $selectedMeeting) { meeting in
                CourseDetailSheet(meeting: meeting)
            }
            .sheet(isPresented: $showTodoEditor) {
                TodoEditorSheet(environment: environment, initialTask: nil)
            }
        }
    }

    @ViewBuilder
    private var weekControlsRow: some View {
        VStack(alignment: .leading, spacing: 10) {
            if !state.terms.isEmpty, let term = state.selectedTerm {
                HStack {
                    if state.terms.count > 1 {
                        Menu {
                            ForEach(state.terms) { item in
                                Button(item.label) {
                                    let targetWeek = min(max(1, state.selectedWeek ?? 1), item.totalWeeks)
                                    Task { await environment.selectTimetableWeek(term: item, week: targetWeek) }
                                }
                            }
                        } label: {
                            Label(term.label, systemImage: "chevron.up.chevron.down")
                                .font(.subheadline.weight(.medium))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                        }
                        .upcGlassCapsule(tint: UPCTheme.brandBlue.opacity(0.12), interactive: true)
                    } else {
                        Text(term.label)
                            .font(.subheadline.weight(.medium))
                    }

                    Spacer()

                    if let currentWeek = state.currentSchoolWeek {
                        Text("学校当前第 \(currentWeek) 周")
                            .font(.caption.weight(.medium))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(UPCTheme.campusJade.opacity(0.14), in: Capsule())
                            .foregroundStyle(UPCTheme.campusJade)
                    }
                }
            }

            if let term = state.selectedTerm, let week = state.selectedWeek {
                HStack(spacing: 8) {
                    Button {
                        Task { await environment.selectTimetableWeek(term: term, week: week - 1) }
                    } label: {
                        Image(systemName: "chevron.left")
                            .padding(8)
                    }
                    .buttonStyle(.bordered)
                    .disabled(week <= 1 || state.isRefreshing)

                    Menu {
                        ForEach(1...term.totalWeeks, id: \.self) { num in
                            Button(num == state.currentSchoolWeek ? "第 \(num) 周（本周）" : "第 \(num) 周") {
                                Task { await environment.selectTimetableWeek(term: term, week: num) }
                            }
                        }
                    } label: {
                        Text(week == state.currentSchoolWeek ? "第 \(week) 周 · 本周" : "第 \(week) 周 / 共 \(term.totalWeeks) 周")
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                    }
                    .upcGlassCapsule(interactive: true)
                    .disabled(state.isRefreshing)

                    Button {
                        Task { await environment.selectTimetableWeek(term: term, week: week + 1) }
                    } label: {
                        Image(systemName: "chevron.right")
                            .padding(8)
                    }
                    .buttonStyle(.bordered)
                    .disabled(week >= term.totalWeeks || state.isRefreshing)
                }
            }
        }
        .padding(.vertical, 2)
    }

    private func scheduleRow(title: String, time: String) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(time)
                .foregroundStyle(.secondary)
                .monospacedDigit()
        }
    }

    private var accountBlockedHint: String {
        switch environment.accountState.status {
        case .unset:
            "请先配置统一学校账号，方可同步周日–周六课表。"
        case .savedUnverified:
            ""
        case .recoveryRequired:
            "本机凭据状态异常，请先前往学校账号页重新保存。"
        }
    }

    private var offlineBannerText: String {
        if let ts = state.fetchedAtEpochMillis, ts > 0 {
            let date = Date(timeIntervalSince1970: TimeInterval(ts) / 1000.0)
            let formatter = DateFormatter()
            formatter.timeZone = TimeZone(identifier: "Asia/Shanghai")
            formatter.dateFormat = "MM-dd HH:mm"
            return "当前显示本机离线缓存（同步于 \(formatter.string(from: date))）"
        }
        return "当前显示本机离线缓存"
    }
}

private struct TimetableWeekGridView: View {
    let weekData: TimetableWeek
    let onSelectMeeting: (CourseMeeting) -> Void

    private static let dayTitles = ["日", "一", "二", "三", "四", "五", "六"]
    private static let slotColors: [Color] = [
        UPCTheme.brandBlue,
        UPCTheme.campusJade,
        UPCTheme.noticeAmber,
        .indigo
    ]

    private var maxPeriod: Int {
        let highest = weekData.meetings.map(\.endPeriod).max() ?? 12
        return min(14, max(12, highest))
    }

    private var todayIso: String {
        TimetableStore.defaultTodayISO()
    }

    var body: some View {
        let rowHeight: CGFloat = 46
        VStack(spacing: 4) {
            HStack(spacing: 2) {
                Color.clear
                    .frame(width: 24, height: 1)
                ForEach(Array(weekData.days.enumerated()), id: \.element.id) { idx, day in
                    let isToday = day.dateIso == todayIso
                    VStack(spacing: 1) {
                        Text(Self.dayTitles.indices.contains(idx) ? Self.dayTitles[idx] : "")
                            .font(.caption2.weight(isToday ? .bold : .medium))
                        Text(day.dateIso.dropFirst(5).replacingOccurrences(of: "-", with: "/"))
                            .font(.system(size: 9))
                            .monospacedDigit()
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
                    .background(
                        isToday ? UPCTheme.brandBlue.opacity(0.16) : Color.clear,
                        in: RoundedRectangle(cornerRadius: 8)
                    )
                    .foregroundStyle(isToday ? UPCTheme.brandBlue : .secondary)
                }
            }

            Divider()

            HStack(alignment: .top, spacing: 2) {
                VStack(spacing: 0) {
                    ForEach(1...maxPeriod, id: \.self) { period in
                        Text("\(period)")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                            .frame(width: 24, height: rowHeight)
                    }
                }

                ForEach(weekData.days) { day in
                    ZStack(alignment: .top) {
                        VStack(spacing: 0) {
                            ForEach(1...maxPeriod, id: \.self) { _ in
                                Rectangle()
                                    .fill(Color.clear)
                                    .frame(height: rowHeight)
                                    .overlay(
                                        Rectangle()
                                            .stroke(Color.primary.opacity(0.06), lineWidth: 0.5)
                                    )
                            }
                        }

                        ForEach(day.meetings) { meeting in
                            let span = max(1, meeting.endPeriod - meeting.startPeriod + 1)
                            let top = CGFloat(meeting.startPeriod - 1) * rowHeight + 1.5
                            let height = max(20, CGFloat(span) * rowHeight - 3)
                            let color = Self.slotColors[abs((meeting.courseId ?? meeting.courseName).hashValue) % Self.slotColors.count]

                            Button {
                                onSelectMeeting(meeting)
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(meeting.courseName)
                                        .font(.system(size: 10, weight: .semibold))
                                        .lineLimit(span >= 2 ? 3 : 2)
                                        .multilineTextAlignment(.leading)
                                    if let location = meeting.location {
                                        Text("@\(location)")
                                            .font(.system(size: 9))
                                            .opacity(0.85)
                                            .lineLimit(2)
                                            .multilineTextAlignment(.leading)
                                    }
                                    Spacer(minLength: 0)
                                }
                                .padding(4)
                                .frame(maxWidth: .infinity, alignment: .topLeading)
                                .frame(height: height)
                                .background(color.opacity(0.18), in: RoundedRectangle(cornerRadius: 8))
                                .foregroundStyle(color)
                            }
                            .buttonStyle(.plain)
                            .padding(.horizontal, 1.5)
                            .offset(y: top)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: rowHeight * CGFloat(maxPeriod), alignment: .top)
                }
            }
        }
    }
}

private struct CourseDetailSheet: View {
    let meeting: CourseMeeting
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("课程信息") {
                    detailRow("课程名称", meeting.courseName)
                    detailRow("上课日期", meeting.dateIso)
                    detailRow("节次与时间", "\(meeting.periodRangeLabel)（\(meeting.effectiveTimeText)）")
                    if meeting.sectionsSource == "lessArr_split_noncontiguous" {
                        detailRow(
                            "完整原始节次",
                            meeting.sourcePeriods.map { "第 \($0) 节" }.joined(separator: "、")
                        )
                    }
                    detailRow("上课地点", meeting.location ?? "未提供地点")
                    detailRow("任课教师", meeting.teacher ?? "未提供教师")
                    detailRow(
                        "周次",
                        "\(meeting.weekLabel)\(meeting.weekType.map { "（\($0)）" } ?? "")"
                    )
                    if let courseType = meeting.courseType {
                        detailRow("课程性质", courseType)
                    }
                    if let credit = meeting.credit {
                        detailRow("学分", credit)
                    }
                    if let examType = meeting.examType {
                        detailRow("考核方式", examType)
                    }
                }
            }
            .navigationTitle("课程详情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func detailRow(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .multilineTextAlignment(.trailing)
        }
    }
}
