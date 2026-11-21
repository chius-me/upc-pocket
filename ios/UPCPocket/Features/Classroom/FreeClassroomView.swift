import SwiftUI

struct FreeClassroomView: View {
    @Bindable var environment: AppEnvironment

    @State private var selectedCampus: FreeClassroomCampus = .tangdaowan
    @State private var dateOffsetDays: Int = 0
    @State private var selectedBuilding: String = "全部"
    @State private var selectedPeriods: Set<Int> = ClassroomPeriodGroup.morning.periods
    @State private var showCustomPeriods = false
    @State private var zhuoyueFilter: ZhuoyueWingFilter = .all
    @State private var onlyAvailable = true

    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var result: FreeClassroomResult?
    @State private var queryTask: Task<Void, Never>?
    @State private var didAppear = false

    private var currentDateString: String {
        let targetDate = Calendar.current.date(byAdding: .day, value: dateOffsetDays, to: Date()) ?? Date()
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: targetDate)
    }

    private var currentQuery: FreeClassroomQuery {
        FreeClassroomQuery(
            campus: selectedCampus,
            dateString: currentDateString,
            building: selectedBuilding,
            periods: selectedPeriods
        )
    }

    private var buildingChoices: [String] {
        guard let result, result.query.campus == selectedCampus else {
            return ["全部"]
        }
        return result.buildings
    }

    private var displayedResult: FreeClassroomResult? {
        guard let result, result.query == currentQuery else {
            return nil
        }
        return result.filterZhuoyueWing(zhuoyueFilter)
    }

    var body: some View {
        List {
            Section("筛选条件") {
                Picker("校区", selection: $selectedCampus) {
                    ForEach(FreeClassroomCampus.allCases) { campus in
                        Text(campus.shortLabel).tag(campus)
                    }
                }
                .pickerStyle(.segmented)
                .onChange(of: selectedCampus) {
                    selectedBuilding = "全部"
                    zhuoyueFilter = .all
                    if !showCustomPeriods {
                        runQuery()
                    }
                }

                Picker("日期", selection: $dateOffsetDays) {
                    Text("今天").tag(0)
                    Text("明天").tag(1)
                    Text("后天").tag(2)
                }
                .pickerStyle(.segmented)
                .onChange(of: dateOffsetDays) {
                    if !showCustomPeriods {
                        runQuery()
                    }
                }

                HStack(spacing: 8) {
                    ForEach(ClassroomPeriodGroup.allCases) { group in
                        let groupState = ClassroomPeriodHelpers.groupSelectionState(
                            selected: selectedPeriods,
                            group: group
                        )
                        Button {
                            if showCustomPeriods {
                                selectedPeriods = ClassroomPeriodHelpers.toggleGroup(
                                    selected: selectedPeriods,
                                    group: group
                                )
                            } else {
                                selectedPeriods = group.periods
                                runQuery()
                            }
                        } label: {
                            Text("\(group.label) \(group.rangeLabel)")
                                .font(.caption.weight(.medium))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .background(
                                    groupState != .none
                                        ? UPCTheme.brandBlue.opacity(0.18)
                                        : Color.secondary.opacity(0.12),
                                    in: Capsule()
                                )
                                .foregroundStyle(groupState != .none ? UPCTheme.brandBlue : .primary)
                        }
                        .buttonStyle(.plain)
                    }

                    Spacer()

                    Button(showCustomPeriods ? "收起" : "自选") {
                        showCustomPeriods.toggle()
                    }
                    .font(.caption.weight(.medium))
                }

                if showCustomPeriods {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(selectedPeriods.isEmpty ? "请至少选择 1 个节次" : "第 \(ClassroomPeriodHelpers.formatPeriods(selectedPeriods)) 节")
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 42), spacing: 6)], spacing: 6) {
                            ForEach(1...13, id: \.self) { period in
                                let selected = selectedPeriods.contains(period)
                                Button {
                                    if selected {
                                        selectedPeriods.remove(period)
                                    } else {
                                        selectedPeriods.insert(period)
                                    }
                                } label: {
                                    Text("\(period)")
                                        .font(.caption.weight(.semibold))
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 6)
                                        .background(
                                            selected ? UPCTheme.brandBlue : Color.secondary.opacity(0.12),
                                            in: RoundedRectangle(cornerRadius: 8)
                                        )
                                        .foregroundStyle(selected ? .white : .primary)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    .padding(.vertical, 4)
                }

                if buildingChoices.count > 1 {
                    Picker("教学楼", selection: $selectedBuilding) {
                        ForEach(buildingChoices, id: \.self) { building in
                            Text(building).tag(building)
                        }
                    }
                    .onChange(of: selectedBuilding) {
                        if !showCustomPeriods {
                            runQuery()
                        }
                    }
                }

                if selectedCampus == .guzhenkou {
                    Picker("卓越分区", selection: $zhuoyueFilter) {
                        ForEach(ZhuoyueWingFilter.allCases) { wing in
                            Text(wing.label).tag(wing)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Toggle("仅看空闲教室", isOn: $onlyAvailable)

                Button {
                    runQuery()
                } label: {
                    HStack {
                        if isLoading {
                            ProgressView()
                                .padding(.trailing, 4)
                        }
                        Text(isLoading ? "查询中…" : "查询空闲教室")
                    }
                    .frame(maxWidth: .infinity)
                }
                .disabled(isLoading || selectedPeriods.isEmpty)
            }

            if let errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }

            if let displayedResult {
                let groups = displayedResult.displayGroups(onlyAvailable: onlyAvailable)
                if groups.isEmpty {
                    Section("查询结果") {
                        Label(
                            onlyAvailable ? "当前条件暂无空闲教室" : "当前条件暂无教室记录",
                            systemImage: "building.2"
                        )
                        .foregroundStyle(.secondary)
                    }
                } else {
                    ForEach(groups) { group in
                        Section {
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 98), spacing: 8)], spacing: 8) {
                                ForEach(group.rooms) { room in
                                    VStack(spacing: 2) {
                                        Text(room.name)
                                            .font(.subheadline.weight(.medium))
                                            .lineLimit(1)
                                        if let capacity = room.capacity {
                                            Text("\(capacity)座")
                                                .font(.caption2)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 6)
                                    .padding(.horizontal, 4)
                                    .background(
                                        room.available
                                            ? UPCTheme.campusJade.opacity(0.16)
                                            : Color.secondary.opacity(0.12),
                                        in: RoundedRectangle(cornerRadius: 10)
                                    )
                                    .foregroundStyle(room.available ? UPCTheme.campusJade : .secondary)
                                }
                            }
                            .padding(.vertical, 4)
                        } header: {
                            HStack {
                                Text(group.building)
                                Spacer()
                                Text("空闲 \(group.freeCount) / \(group.totalCount)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("空闲教室")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if !didAppear {
                didAppear = true
                selectedCampus = FreeClassroomCampus.fromPreferred(environment.preferences.campus)
                runQuery()
            }
        }
    }

    private func runQuery() {
        let query = currentQuery
        guard query.isValid else {
            errorMessage = "请至少选择 1 个节次"
            return
        }
        queryTask?.cancel()
        isLoading = true
        errorMessage = nil

        queryTask = Task {
            do {
                let fetched = try await environment.freeClassroomService.query(query)
                guard !Task.isCancelled else { return }
                result = fetched
                isLoading = false
            } catch {
                guard !Task.isCancelled else { return }
                errorMessage = error.localizedDescription
                isLoading = false
            }
        }
    }
}
