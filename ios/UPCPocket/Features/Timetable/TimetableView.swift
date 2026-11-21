import SwiftUI

struct TimetableView: View {
    @Bindable var environment: AppEnvironment

    var body: some View {
        NavigationStack {
            List {
                Section("今日日程待办") {
                    Label {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("今日暂无本机待办")
                                .font(.headline)
                            Text("M2 起将在课表顶部同步展示本机待办与截止提醒")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "checklist")
                            .foregroundStyle(UPCTheme.campusJade)
                    }
                }

                Section("周课表（周日–周六 · 1–12 节）") {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("只读周视图骨架")
                                .font(.headline)
                            Spacer()
                            Text(environment.accountState.status.label)
                                .font(.caption.weight(.medium))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(UPCTheme.brandBlue.opacity(0.14), in: Capsule())
                                .foregroundStyle(UPCTheme.brandBlue)
                        }
                        Text(timetableHint)
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

                Section("官方作息时间参考") {
                    scheduleRow(title: "上午 1–5 节", time: "08:00 – 12:15")
                    scheduleRow(title: "下午 6–9 节", time: "14:00 – 17:45")
                    scheduleRow(title: "晚间 10–12 节", time: "19:00 – 21:35")
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("课表")
        }
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

    private var timetableHint: String {
        switch environment.accountState.status {
        case .unset:
            "尚未配置学校账号。M1 阶段接入正常 CAS 登录与受控 WKWebView 验证码回退。"
        case .savedUnverified:
            "已在本机钥匙串加密保存凭据（未验证），M1 接入后可拉取当前教学周并离线缓存。"
        case .recoveryRequired:
            "本机凭据状态异常，请先前往学校账号页重新保存。"
        }
    }
}
