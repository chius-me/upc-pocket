import SwiftUI

private enum PowerCampusTab: String, CaseIterable, Identifiable {
    case tangdaowan = "唐岛湾"
    case guzhenkou = "古镇口"

    var id: String { rawValue }
}

struct PowerView: View {
    @Bindable var environment: AppEnvironment

    @State private var selectedCampus: PowerCampusTab = .tangdaowan
    @State private var buildingInput = ""
    @State private var roomInput = ""
    @State private var showClearConfirmation = false
    @State private var didSeedCampus = false

    var body: some View {
        List {
            Section {
                Picker("校区", selection: $selectedCampus) {
                    ForEach(PowerCampusTab.allCases) { tab in
                        Text(tab.rawValue).tag(tab)
                    }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
            }

            if selectedCampus == .guzhenkou {
                Section("古镇口电费") {
                    Text("古镇口电费入口由学校外部 HTTPS 页面提供；应用坚持不绕过或忽略 TLS 证书校验。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    Link(destination: URL(string: "https://gzkdf.upc.edu.cn/acmswx/index.aspx")!) {
                        Label("在系统浏览器打开古镇口电费页", systemImage: "arrow.up.right.square")
                    }
                }
            } else {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(environment.powerState.lastReading.map { "剩余电量 \($0.kwhText) 度" } ?? "唐岛湾宿舍剩余电量")
                                .font(.title2.weight(.semibold))
                                .monospacedDigit()
                            Spacer()
                            if environment.powerState.isQuerying {
                                ProgressView()
                            }
                        }

                        if let reading = environment.powerState.lastReading {
                            Text("\(reading.building) · \(reading.room) · \(formattedDate(reading.fetchedAtEpochMillis))")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        } else {
                            Text("选择楼号并输入精确房间号；命中多个房间时交由你手动确认。")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }

                if let errorMessage = environment.powerState.errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }

                Section("房间查询") {
                    HStack {
                        TextField("楼号（例如 1号楼）", text: $buildingInput)
                        if !environment.powerState.buildings.isEmpty {
                            Menu("选择") {
                                ForEach(environment.powerState.buildings, id: \.self) { building in
                                    Button(building) {
                                        buildingInput = building
                                    }
                                }
                            }
                        }
                    }

                    Button {
                        Task { await environment.loadPowerBuildings() }
                    } label: {
                        HStack {
                            Label(
                                environment.powerState.buildings.isEmpty ? "从学校页面加载楼号列表" : "刷新楼号列表（已加载 \(environment.powerState.buildings.count) 栋）",
                                systemImage: "building.2"
                            )
                            Spacer()
                            if environment.powerState.isLoadingBuildings {
                                ProgressView()
                            }
                        }
                    }
                    .disabled(environment.powerState.isLoadingBuildings)

                    TextField("房间号（精确房间号）", text: $roomInput)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    Button {
                        Task { await environment.queryPower(building: buildingInput, room: roomInput) }
                    } label: {
                        Text(environment.powerState.isQuerying ? "查询中…" : "查询剩余电量")
                            .frame(maxWidth: .infinity)
                    }
                    .disabled(
                        environment.powerState.isQuerying ||
                            buildingInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                            roomInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    )

                    if environment.powerState.lastReading != nil || !environment.powerState.room.isEmpty {
                        Button(role: .destructive) {
                            showClearConfirmation = true
                        } label: {
                            Text("清除保存的楼号与电量")
                                .frame(maxWidth: .infinity)
                        }
                        .disabled(environment.powerState.isQuerying)
                    }
                }

                if !environment.powerState.candidates.isEmpty {
                    Section {
                        ForEach(environment.powerState.candidates) { candidate in
                            Button {
                                let targetBuilding = candidate.building.isEmpty ? buildingInput : candidate.building
                                buildingInput = targetBuilding
                                roomInput = candidate.room
                                Task { await environment.queryPower(building: targetBuilding, room: candidate.room) }
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("\(candidate.building) · \(candidate.room)")
                                            .foregroundStyle(.primary)
                                        Text("系统编号：\(candidate.systemNo)")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(.tertiary)
                                }
                            }
                        }
                    } header: {
                        Text("命中多个候选房间，请选择确切房间")
                    } footer: {
                        Text("学校页面按模糊匹配返回，应用不会替你猜测房间。")
                    }
                }

                Section("边界说明") {
                    Text("dfcz.upc.edu.cn 仅提供 HTTP 公开查询，每次仅提交楼号与房间号，不发送账号凭据。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("宿舍电量")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if !didSeedCampus {
                didSeedCampus = true
                selectedCampus = environment.preferences.campus == .guzhenkou ? .guzhenkou : .tangdaowan
            }
            if buildingInput.isEmpty && !environment.powerState.building.isEmpty {
                buildingInput = environment.powerState.building
            }
            if roomInput.isEmpty && !environment.powerState.room.isEmpty {
                roomInput = environment.powerState.room
            }
        }
        .confirmationDialog(
            "清除电量记录？",
            isPresented: $showClearConfirmation,
            titleVisibility: .visible
        ) {
            Button("确认清除", role: .destructive) {
                buildingInput = ""
                roomInput = ""
                Task { await environment.clearSavedPower() }
            }
            Button("取消", role: .cancel) {}
        }
    }

    private func formattedDate(_ epochMillis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochMillis) / 1000)
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
