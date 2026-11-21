import SwiftUI

struct SettingsView: View {
    @Bindable var environment: AppEnvironment
    @Binding var path: [HomeDestination]

    var body: some View {
        Form {
            Section("账号与校园") {
                Button {
                    path.append(.schoolAccount)
                } label: {
                    HStack {
                        Label("学校账号", systemImage: "person.crop.circle")
                            .foregroundStyle(.primary)
                        Spacer()
                        Text(environment.accountState.status.label)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                }

                Picker("常用校区", selection: Binding(
                    get: { environment.preferences.campus },
                    set: { environment.updateCampus($0) }
                )) {
                    ForEach(CampusPreference.allCases) { option in
                        Text(option.displayName).tag(option)
                    }
                }
            }

            Section("外观") {
                Picker("主题模式", selection: Binding(
                    get: { environment.preferences.theme },
                    set: { environment.updateTheme($0) }
                )) {
                    ForEach(ThemePreference.allCases) { theme in
                        Text(theme.displayName).tag(theme)
                    }
                }
            }

            Section("安全与隐私基线") {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("已排除系统云备份")
                        Text("isExcludedFromBackup = true · Keychain ThisDeviceOnly")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "icloud.slash")
                }

                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("最小化 ATS 明文例外")
                        Text("仅放行 jhzf.upc.edu.cn 与 dfcz.upc.edu.cn")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "lock.shield")
                }

                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("唯一受控 WKWebView 契约")
                        Text("后续仅维护 1 个校方认证网页回退容器")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "safari")
                }
            }

            Section {
                Button {
                    path.append(.about)
                } label: {
                    HStack {
                        Label("关于 UPC Pocket", systemImage: "info.circle")
                            .foregroundStyle(.primary)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
    }
}
