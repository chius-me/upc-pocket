import SwiftUI

struct FocusView: View {
    @Bindable var environment: AppEnvironment

    private var state: FocusUiState { environment.focusState }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 22) {
                        Picker("专注模式", selection: Binding(
                            get: { state.mode },
                            set: { newMode in
                                Task { await environment.selectFocusMode(newMode) }
                            }
                        )) {
                            ForEach(FocusMode.allCases) { mode in
                                Text(mode.label).tag(mode)
                            }
                        }
                        .pickerStyle(.segmented)

                        ZStack {
                            Circle()
                                .stroke(.quaternary, lineWidth: 12)

                            Circle()
                                .trim(from: 0, to: state.progress)
                                .stroke(
                                    UPCTheme.brandBlue,
                                    style: StrokeStyle(lineWidth: 12, lineCap: .round)
                                )
                                .rotationEffect(.degrees(-90))
                                .animation(.easeInOut(duration: 0.35), value: state.progress)

                            VStack(spacing: 8) {
                                Text(state.formattedTime)
                                    .font(.system(size: 46, weight: .semibold, design: .rounded))
                                    .monospacedDigit()

                                Text(statusBadgeText)
                                    .font(.caption.weight(.medium))
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 4)
                                    .upcGlassCapsule(
                                        tint: state.isRunning ? UPCTheme.campusJade.opacity(0.18) : nil
                                    )
                            }
                        }
                        .frame(width: 220, height: 220)

                        UPCGlassGroup(spacing: 12) {
                            HStack(spacing: 12) {
                                Button {
                                    Task { await environment.toggleFocusTimer() }
                                } label: {
                                    Label(
                                        primaryActionTitle,
                                        systemImage: state.isRunning ? "pause.fill" : "play.fill"
                                    )
                                    .font(.headline)
                                    .padding(.horizontal, 20)
                                    .padding(.vertical, 12)
                                }
                                .buttonStyle(.plain)
                                .upcGlassCapsule(tint: UPCTheme.brandBlue.opacity(0.22), interactive: true)

                                Button {
                                    Task { await environment.resetFocusTimer() }
                                } label: {
                                    Label("重置", systemImage: "arrow.counterclockwise")
                                        .font(.headline)
                                        .padding(.horizontal, 18)
                                        .padding(.vertical, 12)
                                }
                                .buttonStyle(.plain)
                                .upcGlassCapsule(interactive: true)
                            }
                        }

                        if !state.isIdle && state.mode.isFocus {
                            Button("提前结束并记录已学时长") {
                                Task { await environment.finishFocusEarly() }
                            }
                            .font(.subheadline)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                }

                Section {
                    statRow("今日（04:00 起）", state.summary.today)
                    statRow("本周（周一至今）", state.summary.week)
                    statRow("本月", state.summary.month)
                    statRow("累计", state.summary.allTime)
                } header: {
                    Text("本机专注统计")
                } footer: {
                    Text("墙钟快照持久化保存；时长累计所有学习段，轮数仅计完整番茄（不含短休）。")
                }

                if !state.recentRecords.isEmpty {
                    Section("最近记录（\(state.recentRecords.count)）") {
                        ForEach(state.recentRecords) { rec in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("\(rec.mode.label) · \(FocusStats.humanDuration(rec.actualSeconds))")
                                    Text(formatTimestamp(rec.startedAt))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text(rec.completed ? "完整完成" : "提前结束")
                                    .font(.caption.weight(.medium))
                                    .foregroundStyle(rec.completed ? UPCTheme.campusJade : .secondary)
                            }
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("专注")
            .toolbar {
                if !state.recentRecords.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("清空历史", role: .destructive) {
                            Task { await environment.clearFocusHistory() }
                        }
                    }
                }
            }
        }
    }

    private var statusBadgeText: String {
        if state.isRunning { return "进行中" }
        if state.isPaused { return "已暂停" }
        return "已就绪"
    }

    private var primaryActionTitle: String {
        if state.isRunning { return "暂停" }
        if state.isPaused { return "继续" }
        return "开始专注"
    }

    private func statRow(_ title: String, _ totals: FocusTotals) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text("\(FocusStats.humanDuration(totals.seconds)) · \(totals.sessions) 轮")
                .foregroundStyle(.secondary)
                .monospacedDigit()
        }
    }

    private func formatTimestamp(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000.0)
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm"
        return formatter.string(from: date)
    }
}
