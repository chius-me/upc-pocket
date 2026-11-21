import SwiftUI

struct FocusView: View {
    @Bindable var environment: AppEnvironment

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    Picker("专注模式", selection: Binding(
                        get: { environment.focusMode },
                        set: { environment.selectFocusMode($0) }
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
                            .trim(from: 0, to: environment.focusProgress)
                            .stroke(
                                UPCTheme.brandBlue,
                                style: StrokeStyle(lineWidth: 12, lineCap: .round)
                            )
                            .rotationEffect(.degrees(-90))
                            .animation(.easeInOut(duration: 0.35), value: environment.focusProgress)

                        VStack(spacing: 8) {
                            Text(environment.formattedFocusTime)
                                .font(.system(size: 48, weight: .semibold, design: .rounded))
                                .monospacedDigit()

                            Text(environment.isFocusRunning ? "专注进行中" : "已就绪")
                                .font(.caption.weight(.medium))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 4)
                                .upcGlassCapsule(
                                    tint: environment.isFocusRunning ? UPCTheme.campusJade.opacity(0.18) : nil
                                )
                        }
                    }
                    .frame(width: 230, height: 230)
                    .padding(.top, 8)

                    UPCGlassGroup(spacing: 12) {
                        HStack(spacing: 12) {
                            Button {
                                environment.toggleFocusTimer()
                            } label: {
                                Label(
                                    environment.isFocusRunning ? "暂停" : "开始专注",
                                    systemImage: environment.isFocusRunning ? "pause.fill" : "play.fill"
                                )
                                .font(.headline)
                                .padding(.horizontal, 22)
                                .padding(.vertical, 12)
                            }
                            .buttonStyle(.plain)
                            .upcGlassCapsule(tint: UPCTheme.brandBlue.opacity(0.22), interactive: true)

                            Button {
                                environment.resetFocusTimer()
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

                    VStack(spacing: 6) {
                        Text("本次会话已完成 \(environment.completedFocusSessions) 轮")
                            .font(.subheadline.weight(.medium))
                        Text("纯本地计算，零网络请求；M2 起接入持久化专注统计。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 14)
                    .frame(maxWidth: .infinity)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: UPCTheme.cardCornerRadius))
                }
                .padding()
            }
            .navigationTitle("专注")
        }
    }
}
