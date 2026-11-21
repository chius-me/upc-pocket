import SwiftUI

struct LibrarySeatView: View {
    @Bindable var environment: AppEnvironment

    @State private var showOnlySelectableLibseatSeats = false
    @State private var showOnlyAvailableChaoxingSeats = false

    private var state: LibrarySeatUiState {
        environment.librarySeatState
    }

    var body: some View {
        List {
            Section {
                Picker(
                    "馆区系统",
                    selection: Binding(
                        get: { state.activeTab },
                        set: { newTab in
                            Task { await environment.selectLibrarySystem(newTab) }
                        }
                    )
                ) {
                    ForEach(LibrarySystemTab.allCases) { tab in
                        Text(tab.shortLabel).tag(tab)
                    }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
            }

            Section {
                HStack(alignment: .center, spacing: 12) {
                    Image(systemName: "checkmark.shield")
                        .foregroundStyle(UPCTheme.campusJade)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("严格只读查询 · 会话仅存内存")
                            .font(.subheadline.weight(.medium))
                        Text("支持查看余座、座位分布与预约记录；如需预约或签到请前往官方页面。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Link(destination: state.activeTab.officialURL) {
                        Text("官方页")
                            .font(.caption.weight(.semibold))
                    }
                }
            }

            if environment.accountState.status != .savedUnverified {
                Section("学校账号") {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(
                            environment.accountState.status == .recoveryRequired
                                ? "学校账号需要重新保存"
                                : "尚未配置统一学校账号"
                        )
                        .font(.headline)
                        Text("图书馆与超星座位系统需要通过统一认证查询实时余座与本人预约状态。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        NavigationLink("前往学校账号页配置") {
                            SchoolAccountView(environment: environment)
                        }
                    }
                    .padding(.vertical, 4)
                }
            } else {
                if let errorMessage = state.errorMessage {
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(errorMessage)
                                .font(.subheadline)
                                .foregroundStyle(.red)
                            Button("重试") {
                                Task { await environment.refreshLibrarySeat() }
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }

                if let statusMessage = state.statusMessage {
                    Section {
                        HStack {
                            Text(statusMessage)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Spacer()
                            if state.connectionStatus == .ready {
                                Button("清空会话") {
                                    Task { await environment.clearLibrarySessions() }
                                }
                                .font(.caption)
                            }
                        }
                    }
                }

                switch state.activeTab {
                case .libseat:
                    libseatSections
                case .chaoxingSeat, .chaoxingSeatEngine:
                    chaoxingSections
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("图书馆座位")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if environment.accountState.status == .savedUnverified {
                    Button {
                        Task { await environment.refreshLibrarySeat() }
                    } label: {
                        if state.isLoading {
                            ProgressView()
                        } else {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                    .disabled(state.isLoading)
                    .accessibilityLabel("刷新座位数据")
                }
            }
        }
        .task(id: environment.preferences.campus) {
            await environment.syncLibraryCampus()
        }
        .sheet(
            isPresented: Binding(
                get: { state.libseatSelectedRoom != nil },
                set: { presented in
                    if !presented {
                        Task { await environment.dismissLibseatRoomDetail() }
                    }
                }
            )
        ) {
            if let room = state.libseatSelectedRoom {
                libseatRoomDetailSheet(room)
            }
        }
        .sheet(
            isPresented: Binding(
                get: { state.chaoxingSelectedRoom != nil },
                set: { presented in
                    if !presented {
                        Task { await environment.dismissChaoxingRoomDetail() }
                    }
                }
            )
        ) {
            if let room = state.chaoxingSelectedRoom {
                chaoxingRoomDetailSheet(room)
            }
        }
        .sheet(
            isPresented: Binding(
                get: { state.interactionRequest != nil },
                set: { presented in
                    if !presented {
                        Task { await environment.dismissLibraryInteraction() }
                    }
                }
            )
        ) {
            if let req = state.interactionRequest {
                let captureURLHandler: (@MainActor (URL) -> Void)? = req.usesURLTokenCapture
                    ? { @MainActor url in
                        Task { await environment.adoptLibseatWebViewToken(url, context: req.sessionContext) }
                    }
                    : nil
                ControlledSchoolWebViewSheet(
                    sessionContext: req.sessionContext,
                    accountState: environment.accountState,
                    scopeHosts: req.scopeHosts,
                    targetCookieHost: req.targetHost,
                    completionPathPrefixes: req.completionPathPrefixes,
                    targetCookieDomains: req.targetCookieDomains,
                    onCompletionURLCaptured: captureURLHandler,
                    onCookiesCaptured: { cookies in
                        if !req.usesURLTokenCapture {
                            Task { await environment.adoptChaoxingWebViewCookies(cookies, context: req.sessionContext) }
                        }
                    },
                    onDismiss: {
                        Task { await environment.dismissLibraryInteraction() }
                    }
                )
            }
        }
    }

    @ViewBuilder
    private var libseatSections: some View {
        if state.libseatDates.isEmpty && !state.isLoading && state.errorMessage == nil {
            Section {
                Button("同步唐岛湾 libseat 余座") {
                    Task { await environment.refreshLibrarySeat() }
                }
            }
        } else if !state.libseatDates.isEmpty {
            Section("筛选条件") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(state.libseatDates, id: \.self) { date in
                            filterPill(
                                title: date,
                                selected: state.libseatSelectedDate == date
                            ) {
                                Task { await environment.selectLibseatDate(date) }
                            }
                        }
                    }
                    .padding(.vertical, 2)
                }

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(state.libseatBuildings) { building in
                            filterPill(
                                title: building.name,
                                selected: state.libseatSelectedBuilding?.id == building.id
                            ) {
                                Task { await environment.selectLibseatBuilding(building) }
                            }
                        }
                    }
                    .padding(.vertical, 2)
                }

                if !state.libseatFloors.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            filterPill(
                                title: "全部楼层",
                                selected: state.libseatSelectedFloor == nil
                            ) {
                                Task { await environment.selectLibseatFloor(nil) }
                            }
                            ForEach(state.libseatFloors) { floor in
                                filterPill(
                                    title: floor.floorName,
                                    selected: state.libseatSelectedFloor?.floorNum == floor.floorNum
                                ) {
                                    Task { await environment.selectLibseatFloor(floor) }
                                }
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }
            }

            let visibleRooms: [LibraryRoom] = if let floor = state.libseatSelectedFloor {
                state.libseatRooms.filter { $0.floor == floor.floorNum }
            } else {
                state.libseatRooms
            }

            Section("阅览室余座（\(visibleRooms.count) 间）") {
                if visibleRooms.isEmpty {
                    Text("当前筛选条件下暂无房间数据")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(visibleRooms) { room in
                        Button {
                            showOnlySelectableLibseatSeats = false
                            Task { await environment.loadLibseatRoomLayout(room) }
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(room.name)
                                        .foregroundStyle(.primary)
                                    Text(
                                        "\(room.floorLabel) · \(room.remainingLabel)" +
                                            (room.maxDurationLabel.map { " · \($0)" } ?? "")
                                    )
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text(room.freeSeats.map { "余 \($0)" } ?? "查看")
                                    .font(.caption.weight(.semibold))
                                    .monospacedDigit()
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(
                                        (room.freeSeats ?? 0) > 0
                                            ? UPCTheme.brandBlue.opacity(0.16)
                                            : Color.secondary.opacity(0.12),
                                        in: Capsule()
                                    )
                                    .foregroundStyle((room.freeSeats ?? 0) > 0 ? UPCTheme.brandBlue : .secondary)
                            }
                        }
                    }
                }
            }

            Section("当前预约记录（只读）") {
                if state.libseatCurrentReservations.isEmpty {
                    Text("当前没有有效预约记录")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(state.libseatCurrentReservations) { item in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.location)
                                Text("\(item.date) · \(item.begin)–\(item.end)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(item.statusLabel)
                                .font(.caption.weight(.medium))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(.quaternary, in: Capsule())
                        }
                    }
                }
            }

            if !state.libseatHistoryReservations.isEmpty {
                Section("历史预约（最近 \(state.libseatHistoryReservations.count) 条）") {
                    ForEach(state.libseatHistoryReservations) { item in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.location)
                                Text("\(item.date) · \(item.begin)–\(item.end)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(item.statusLabel)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var chaoxingSections: some View {
        if !state.chaoxingAvailableDates.isEmpty {
            Section("日期选择（北京时间）") {
                Picker(
                    "日期",
                    selection: Binding(
                        get: { state.chaoxingSelectedDate },
                        set: { newDate in
                            Task { await environment.selectChaoxingDate(newDate) }
                        }
                    )
                ) {
                    ForEach(state.chaoxingAvailableDates, id: \.self) { date in
                        Text(date).tag(date)
                    }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
            }
        }

        if state.chaoxingRooms.isEmpty && !state.isLoading && state.errorMessage == nil {
            Section {
                Button("同步 \(state.activeTab.label) 区域") {
                    Task { await environment.refreshLibrarySeat() }
                }
            }
        } else if !state.chaoxingRooms.isEmpty {
            Section("\(state.activeTab.label) 开放区域（\(state.chaoxingRooms.count) 个）") {
                ForEach(state.chaoxingRooms) { room in
                    Button {
                        showOnlyAvailableChaoxingSeats = false
                        Task { await environment.loadChaoxingRoomDetail(room) }
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(room.name)
                                    .foregroundStyle(.primary)
                                Text(
                                    "开放 \(room.openHoursLabel) · 容量 \(room.capacity) 座" +
                                        (room.usedCount.map { " · 已用 \($0)" } ?? "")
                                )
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(room.remainingEstimate.map { "余约 \($0)" } ?? "查看")
                                .font(.caption.weight(.semibold))
                                .monospacedDigit()
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(UPCTheme.brandBlue.opacity(0.16), in: Capsule())
                                .foregroundStyle(UPCTheme.brandBlue)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func libseatRoomDetailSheet(_ room: LibraryRoom) -> some View {
        NavigationStack {
            List {
                Section {
                    Text(room.remainingLabel + (room.maxDurationLabel.map { " · \($0)" } ?? ""))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                if state.isLoadingRoomDetail {
                    Section {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("正在读取座位分布…")
                                .font(.subheadline)
                        }
                    }
                } else if let layout = state.libseatSelectedRoomLayout {
                    let seatItems = layout.seats.filter(\.isSeat)
                    let freeCount = seatItems.filter(\.selectable).count
                    let displayed = showOnlySelectableLibseatSeats ? seatItems.filter(\.selectable) : seatItems

                    Section {
                        Toggle("仅看当前可选座位（\(freeCount)/\(seatItems.count)）", isOn: $showOnlySelectableLibseatSeats)
                    }

                    Section("座位状态") {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 92), spacing: 8)], spacing: 8) {
                            ForEach(displayed.prefix(60)) { seat in
                                Text("\(seat.name.isEmpty ? seat.id : seat.name)·\(LibrarySeatStatus.label(for: seat.status))")
                                    .font(.caption.weight(.medium))
                                    .lineLimit(1)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 6)
                                    .padding(.horizontal, 6)
                                    .background(
                                        seat.selectable ? UPCTheme.campusJade.opacity(0.16) : Color.secondary.opacity(0.12),
                                        in: RoundedRectangle(cornerRadius: 8)
                                    )
                                    .foregroundStyle(seat.selectable ? UPCTheme.campusJade : .secondary)
                            }
                        }
                        .padding(.vertical, 4)

                        if displayed.count > 60 {
                            Text("已展示前 60 个座位（共 \(displayed.count) 个）")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("\(room.name)（\(room.floorLabel)）")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") {
                        Task { await environment.dismissLibseatRoomDetail() }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func chaoxingRoomDetailSheet(_ room: ChaoxingSeatRoom) -> some View {
        NavigationStack {
            List {
                Section {
                    Text("开放 \(room.openHoursLabel) · 日期 \(state.chaoxingSelectedDate)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                if state.isLoadingRoomDetail && state.chaoxingSelectedRoomDetail == nil {
                    Section {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("正在读取座位底图与占用状态…")
                                .font(.subheadline)
                        }
                    }
                } else if let detail = state.chaoxingSelectedRoomDetail {
                    let windows = chaoxingWindows(detail)
                    if windows.count > 1 {
                        Section("查询时段") {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(windows, id: \.0) { s, e in
                                        filterPill(
                                            title: "\(LibraryTimeFormatter.clockMinute(s))–\(LibraryTimeFormatter.clockMinute(e))",
                                            selected: state.chaoxingSelectedStartMinute == s && state.chaoxingSelectedEndMinute == e
                                        ) {
                                            Task { await environment.updateChaoxingWindow(startMinute: s, endMinute: e) }
                                        }
                                    }
                                }
                                .padding(.vertical, 2)
                            }
                        }
                    }

                    let availablePlaces = detail.places.filter {
                        !detail.disabledSeatNums.contains($0.seatNum) && !state.chaoxingUsedSeatNums.contains($0.seatNum)
                    }
                    let displayedPlaces = showOnlyAvailableChaoxingSeats ? availablePlaces : detail.places

                    Section {
                        Toggle(
                            "仅看当前时段空闲（\(availablePlaces.count)/\(detail.places.count)）",
                            isOn: $showOnlyAvailableChaoxingSeats
                        )
                    }

                    Section("座位状态（点击座号查当日已占时段）") {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 84), spacing: 8)], spacing: 8) {
                            ForEach(displayedPlaces.prefix(60)) { place in
                                let isDisabled = detail.disabledSeatNums.contains(place.seatNum)
                                let isUsed = state.chaoxingUsedSeatNums.contains(place.seatNum)
                                let isSelected = state.chaoxingSelectedSeatNum == place.seatNum
                                Button {
                                    Task { await environment.loadChaoxingSeatUsedTimes(seatNum: place.seatNum) }
                                } label: {
                                    Text(
                                        isDisabled ? "\(place.seatNum)·禁用" :
                                            (isUsed ? "\(place.seatNum)·已占" : "\(place.seatNum)·空闲")
                                    )
                                    .font(.caption.weight(.medium))
                                    .lineLimit(1)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 6)
                                    .padding(.horizontal, 6)
                                    .background(
                                        isSelected
                                            ? UPCTheme.brandBlue.opacity(0.24)
                                            : (!isDisabled && !isUsed ? UPCTheme.campusJade.opacity(0.16) : Color.secondary.opacity(0.12)),
                                        in: RoundedRectangle(cornerRadius: 8)
                                    )
                                    .foregroundStyle(
                                        isSelected
                                            ? UPCTheme.brandBlue
                                            : (!isDisabled && !isUsed ? UPCTheme.campusJade : .secondary)
                                    )
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.vertical, 4)
                    }

                    if let seatNum = state.chaoxingSelectedSeatNum {
                        Section("座位 \(seatNum) 当日已占时段") {
                            if state.isLoadingRoomDetail {
                                ProgressView()
                            } else if state.chaoxingSelectedSeatUsedTimes.isEmpty {
                                Text("全天暂未查询到已占时段")
                                    .foregroundStyle(.secondary)
                            } else {
                                Text(state.chaoxingSelectedSeatUsedTimes.map(\.label).joined(separator: "、"))
                            }
                        }
                    }
                }
            }
            .navigationTitle(room.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") {
                        Task { await environment.dismissChaoxingRoomDetail() }
                    }
                }
            }
        }
    }

    private func chaoxingWindows(_ detail: ChaoxingSeatRoomDetail) -> [(Int, Int)] {
        var result: [(Int, Int)] = []
        var s = detail.startMinute
        while s < detail.endMinute {
            let e = min(s + 240, detail.endMinute)
            result.append((s, e))
            s += 240
        }
        return result
    }

    private func filterPill(
        title: String,
        selected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Text(title)
                .font(.caption.weight(.medium))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    selected ? UPCTheme.brandBlue.opacity(0.18) : Color.secondary.opacity(0.12),
                    in: Capsule()
                )
                .foregroundStyle(selected ? UPCTheme.brandBlue : .primary)
        }
        .buttonStyle(.plain)
    }
}
