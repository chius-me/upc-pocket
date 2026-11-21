import SwiftUI

enum HomeDestination: Hashable {
    case settings
    case schoolAccount
    case about
    case todos
    case cardBalance
    case power
    case freeClassroom
}

struct HomeView: View {
    @Bindable var environment: AppEnvironment
    @Binding var selectedTab: AppTab
    @State private var path: [HomeDestination] = []

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section {
                    UPCGlassGroup(spacing: 8) {
                        HStack(spacing: 8) {
                            Button {
                                path.append(.settings)
                            } label: {
                                Label("当前校区：\(environment.preferences.campus.displayName)", systemImage: "location")
                                    .font(.subheadline.weight(.medium))
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 7)
                            }
                            .buttonStyle(.plain)
                            .upcGlassCapsule(tint: UPCTheme.brandBlue.opacity(0.14), interactive: true)

                            Text("M2a 日程与专注")
                                .font(.caption.weight(.medium))
                                .foregroundStyle(.secondary)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .upcGlassCapsule()
                        }
                    }
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                }

                Section("学校账号") {
                    Button {
                        path.append(.schoolAccount)
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: "person.crop.circle.fill")
                                .font(.title2)
                                .foregroundStyle(UPCTheme.brandBlue)

                            VStack(alignment: .leading, spacing: 4) {
                                HStack(spacing: 8) {
                                    Text("统一学校账号")
                                        .font(.headline)
                                        .foregroundStyle(.primary)

                                    Text(environment.accountState.status.label)
                                        .font(.caption.weight(.medium))
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 3)
                                        .background(statusTint.opacity(0.16), in: Capsule())
                                        .foregroundStyle(statusTint)
                                }

                                Text(accountSummaryText)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }

                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.tertiary)
                        }
                        .padding(.vertical, 4)
                    }
                    .buttonStyle(.plain)
                }

                Section("今日概览") {
                    Button {
                        selectedTab = .timetable
                    } label: {
                        HStack {
                            Label {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("今日课表")
                                        .foregroundStyle(.primary)
                                    Text(timetableSubtitle)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            } icon: {
                                Image(systemName: "calendar")
                                    .foregroundStyle(UPCTheme.brandBlue)
                            }
                            Spacer()
                            if let todaySummary = environment.timetableState.todaySummary {
                                Text("\(todaySummary.courses.count) 节")
                                    .font(.subheadline.weight(.semibold))
                                    .monospacedDigit()
                                    .foregroundStyle(UPCTheme.brandBlue)
                            }
                        }
                    }

                    Button {
                        path.append(.todos)
                    } label: {
                        HStack {
                            Label {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("本机待办")
                                        .foregroundStyle(.primary)
                                    Text(todoSubtitle)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            } icon: {
                                Image(systemName: "checklist")
                                    .foregroundStyle(UPCTheme.campusJade)
                            }
                            Spacer()
                            if !environment.todoState.incompleteTasks.isEmpty {
                                Text("\(environment.todoState.incompleteTasks.count) 项")
                                    .font(.subheadline.weight(.semibold))
                                    .monospacedDigit()
                                    .foregroundStyle(UPCTheme.campusJade)
                            }
                        }
                    }

                    Button {
                        path.append(.cardBalance)
                    } label: {
                        HStack {
                            Label {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("校园卡余额")
                                        .foregroundStyle(.primary)
                                    Text(cardSubtitle)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            } icon: {
                                Image(systemName: "creditcard")
                                    .foregroundStyle(UPCTheme.campusJade)
                            }
                            Spacer()
                            if let snap = environment.cardBalanceState.snapshot {
                                Text("¥\(snap.totalBalanceText)")
                                    .font(.subheadline.weight(.semibold))
                                    .monospacedDigit()
                                    .foregroundStyle(UPCTheme.campusJade)
                            }
                        }
                    }

                    Button {
                        path.append(.power)
                    } label: {
                        HStack {
                            Label {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("宿舍电量")
                                        .foregroundStyle(.primary)
                                    Text(powerSubtitle)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            } icon: {
                                Image(systemName: "bolt.fill")
                                    .foregroundStyle(UPCTheme.noticeAmber)
                            }
                            Spacer()
                            if let reading = environment.powerState.lastReading {
                                Text("\(reading.kwhText) 度")
                                    .font(.subheadline.weight(.semibold))
                                    .monospacedDigit()
                                    .foregroundStyle(UPCTheme.noticeAmber)
                            }
                        }
                    }

                    Button {
                        path.append(.freeClassroom)
                    } label: {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("空闲教室")
                                    .foregroundStyle(.primary)
                                Text("两校区教学楼与 1–13 节交集空闲实时查询")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: "building.2")
                                .foregroundStyle(UPCTheme.brandBlue)
                        }
                    }
                }

                Section("安全基线") {
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("已禁用系统云备份与跨机迁移")
                            Text("Keychain ThisDeviceOnly · 无备份目录 · 最小化明文例外")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "checkmark.shield")
                            .foregroundStyle(UPCTheme.campusJade)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("UPC Pocket")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        path.append(.settings)
                    } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel("设置")
                }
            }
            .navigationDestination(for: HomeDestination.self) { destination in
                switch destination {
                case .settings:
                    SettingsView(environment: environment, path: $path)
                case .schoolAccount:
                    SchoolAccountView(environment: environment)
                case .about:
                    AboutView()
                case .todos:
                    TodoView(environment: environment)
                case .cardBalance:
                    CardBalanceView(environment: environment)
                case .power:
                    PowerView(environment: environment)
                case .freeClassroom:
                    FreeClassroomView(environment: environment)
                }
            }
        }
    }

    private var statusTint: Color {
        switch environment.accountState.status {
        case .unset: .secondary
        case .savedUnverified: UPCTheme.campusJade
        case .recoveryRequired: .red
        }
    }

    private var accountSummaryText: String {
        switch environment.accountState.status {
        case .unset:
            "默认不保存凭据；点击配置学校账号并加密保存到钥匙串"
        case .savedUnverified:
            "\(environment.accountState.maskedLoginName ?? "已保存") · 尚未向学校验证"
        case .recoveryRequired:
            "本机加密状态异常，请点击重新保存"
        }
    }

    private var todoSubtitle: String {
        guard let first = environment.todoState.incompleteTasks.first else {
            return "暂无待办事项，点击新建本机待办或提醒"
        }
        let dueSuffix = first.dueAt.map { " · 截止 \(TodoView.formatMillis($0))" } ?? ""
        return "下一项：\(first.title)\(dueSuffix)"
    }

    private var cardSubtitle: String {
        if let snap = environment.cardBalanceState.snapshot {
            return "卡内 ¥\(snap.balanceText) + 过渡 ¥\(snap.transitionText)"
        }
        switch environment.accountState.status {
        case .savedUnverified where environment.accountState.isValidStudentId:
            return "已识别有效本人学号，点击查询校园卡余额"
        case .savedUnverified:
            return "当前登录名非标准学号，余额查询将跳过"
        case .unset, .recoveryRequired:
            return "仅接受本人有效学号，不发送密码"
        }
    }

    private var timetableSubtitle: String {
        guard environment.accountState.status == .savedUnverified else {
            return "配置学校账号后可同步周日–周六课表"
        }
        if let today = environment.timetableState.todaySummary {
            if today.courses.isEmpty {
                let weekText = today.weekNumber.map(String.init) ?? "-"
                return "第 \(weekText) 周 · 今日暂无排课\(today.isOfflineCache ? "（缓存）" : "")"
            }
            let first = today.courses[0]
            let loc = first.location.map { " @ \($0)" } ?? ""
            return "今日 \(today.courses.count) 节课 · 首节 \(first.courseName)\(loc)"
        }
        switch environment.timetableState.connectionStatus {
        case .interactionRequired:
            return "需要前往课表页通过安全验证后同步"
        case .invalidCredentials:
            return "学校账号或密码错误，请核对后重试"
        default:
            return "点击前往课表同步当前教学周"
        }
    }

    private var powerSubtitle: String {
        if let reading = environment.powerState.lastReading {
            return "\(reading.building) · \(reading.room)"
        }
        switch environment.preferences.campus {
        case .tangdaowan: return "点击查询唐岛湾宿舍剩余电量"
        case .guzhenkou: return "古镇口电费使用官方 HTTPS 页面"
        case .all: return "唐岛湾原生查询 · 古镇口官方页面"
        }
    }
}
