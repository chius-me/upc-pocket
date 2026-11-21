import SwiftUI

struct CardBalanceView: View {
    @Bindable var environment: AppEnvironment
    @State private var showClearConfirmation = false

    private var canQuery: Bool {
        environment.accountState.status == .savedUnverified && environment.accountState.isValidStudentId
    }

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(environment.cardBalanceState.snapshot.map { "¥\($0.totalBalanceText)" } ?? "暂无余额记录")
                            .font(.system(size: 34, weight: .semibold, design: .rounded))
                            .monospacedDigit()

                        Spacer()

                        Text(statusBadge)
                            .font(.caption.weight(.medium))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .upcGlassCapsule(tint: UPCTheme.campusJade.opacity(0.16))
                    }

                    Text(heroSubtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 6)
            }

            if let errorMessage = environment.cardBalanceState.errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }

            Section {
                if canQuery {
                    Button {
                        Task { await environment.refreshCardBalance() }
                    } label: {
                        HStack {
                            if environment.cardBalanceState.isRefreshing {
                                ProgressView()
                                    .padding(.trailing, 4)
                            } else {
                                Image(systemName: "arrow.clockwise")
                            }
                            Text(environment.cardBalanceState.isRefreshing ? "正在查询…" : "查询当前余额")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .disabled(environment.cardBalanceState.isRefreshing)
                } else {
                    NavigationLink {
                        SchoolAccountView(environment: environment)
                    } label: {
                        Label("前往配置学校账号", systemImage: "person.badge.key")
                    }
                }

                if environment.cardBalanceState.snapshot != nil {
                    Button(role: .destructive) {
                        showClearConfirmation = true
                    } label: {
                        Label("仅清除余额缓存", systemImage: "trash")
                            .frame(maxWidth: .infinity)
                    }
                    .disabled(environment.cardBalanceState.isRefreshing)
                }
            }

            if let snapshot = environment.cardBalanceState.snapshot {
                Section("余额明细") {
                    LabeledContent("卡内余额", value: "¥\(snapshot.balanceText)")
                    LabeledContent("过渡余额", value: "¥\(snapshot.transitionText)")
                    LabeledContent("合计总额", value: "¥\(snapshot.totalBalanceText)")
                    LabeledContent("查询时间", value: formattedDate(snapshot.fetchedAtEpochMillis))
                }
            }

            Section("安全与传输边界") {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("仅发送本人有效学号（不含密码）")
                        Text("当前账号：\(environment.accountState.maskedLoginName ?? "未配置") · 不触发 CAS 认证")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "person.text.rectangle")
                }

                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("最小化明文 HTTP 例外")
                        Text("学校接口 jhzf.upc.edu.cn:20081 仅提供 HTTP，请避免在不可信网络查询。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "network.badge.shield.half.filled")
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("校园卡余额")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(
            "仅清除余额缓存？",
            isPresented: $showClearConfirmation,
            titleVisibility: .visible
        ) {
            Button("确认清除", role: .destructive) {
                Task { await environment.clearCardBalanceCache() }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("只删除本机余额缓存，不会删除学校账号或钥匙串凭据。")
        }
    }

    private var statusBadge: String {
        if environment.cardBalanceState.isRefreshing {
            return "查询中"
        }
        if environment.cardBalanceState.snapshot != nil {
            return environment.cardBalanceState.isHistoricalGeneration ? "历史缓存" : "本机缓存"
        }
        return canQuery ? "可查询" : environment.accountState.status.label
    }

    private var heroSubtitle: String {
        if let snap = environment.cardBalanceState.snapshot {
            if environment.cardBalanceState.isHistoricalGeneration {
                return "同账号历史缓存（已更新过密码，建议重新查询）"
            }
            return "总额 = 卡内余额 ¥\(snap.balanceText) + 过渡余额 ¥\(snap.transitionText)"
        }
        if canQuery {
            return "点击下方查询，仅向学校接口发送当前绑定的本人学号"
        }
        return "请先配置整串符合学号格式的学校账号（仅查询本人校园卡）"
    }

    private func formattedDate(_ epochMillis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochMillis) / 1000)
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
