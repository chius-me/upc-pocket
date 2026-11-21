import SwiftUI

enum HomeDestination: Hashable {
    case settings
    case schoolAccount
    case about
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

                            Text("M0 原生骨架")
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
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("今日课表")
                                    .foregroundStyle(.primary)
                                Text(environment.accountState.status == .savedUnverified
                                     ? "M1 将接入正常 CAS 登录与离线周缓存"
                                     : "配置学校账号后可同步周日–周六课表")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: "calendar")
                                .foregroundStyle(UPCTheme.brandBlue)
                        }
                    }

                    Button {
                        selectedTab = .services
                    } label: {
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
                    }

                    Button {
                        selectedTab = .services
                    } label: {
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

    private var cardSubtitle: String {
        switch environment.accountState.status {
        case .savedUnverified where environment.accountState.isValidStudentId:
            "已识别有效本人学号，M1 只读查询仅发送学号"
        case .savedUnverified:
            "当前登录名非标准学号，余额查询将跳过"
        case .unset, .recoveryRequired:
            "仅接受本人有效学号，不发送密码"
        }
    }

    private var powerSubtitle: String {
        switch environment.preferences.campus {
        case .tangdaowan: "唐岛湾楼号与寝室号原生查询（M1）"
        case .guzhenkou: "古镇口电费使用官方 HTTPS 页面"
        case .all: "唐岛湾原生查询（M1）· 古镇口官方页面"
        }
    }
}
