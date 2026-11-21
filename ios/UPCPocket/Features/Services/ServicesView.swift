import SwiftUI

private enum ServicesDestination: Hashable {
    case cardBalance
    case power
    case freeClassroom
    case librarySeat
}

struct ServicesView: View {
    @Bindable var environment: AppEnvironment

    private var filteredQueries: [PlannedServiceEntry] {
        let campus = environment.preferences.campus
        return CampusServiceCatalog.queryServices.filter { entry in
            campus == .all || entry.campus == .all || entry.campus == campus
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("常用校区", selection: Binding(
                        get: { environment.preferences.campus },
                        set: { environment.updateCampus($0) }
                    )) {
                        ForEach(CampusPreference.allCases) { option in
                            Text(option.displayName).tag(option)
                        }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                }

                Section("校园基础查询") {
                    ForEach(filteredQueries) { item in
                        if let destination = destinationFor(item.id) {
                            NavigationLink(value: destination) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.title)
                                        .foregroundStyle(.primary)
                                    Text(item.subtitle)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        } else if let externalURL = item.externalURL {
                            Link(destination: externalURL) {
                                serviceRow(
                                    title: item.title,
                                    subtitle: item.subtitle,
                                    badge: item.milestone,
                                    isExternal: true
                                )
                            }
                        } else {
                            serviceRow(
                                title: item.title,
                                subtitle: item.subtitle,
                                badge: item.milestone,
                                isExternal: false
                            )
                        }
                    }
                }

                Section("校园官方网页") {
                    ForEach(CampusServiceCatalog.officialWebEntries) { entry in
                        Link(destination: entry.url) {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(entry.title)
                                        .foregroundStyle(.primary)
                                    Text(entry.subtitle)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: "arrow.up.right.square")
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                Section {
                    ForEach(CampusServiceCatalog.deferredReadOnlyEntries) { item in
                        serviceRow(
                            title: item.title,
                            subtitle: item.subtitle,
                            badge: item.milestone,
                            isExternal: false
                        )
                    }
                } header: {
                    Text("后续只读规划")
                } footer: {
                    Text("高风险写操作（如校园卡充值）当前明确后置。")
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("服务")
            .navigationDestination(for: ServicesDestination.self) { destination in
                switch destination {
                case .cardBalance:
                    CardBalanceView(environment: environment)
                case .power:
                    PowerView(environment: environment)
                case .freeClassroom:
                    FreeClassroomView(environment: environment)
                case .librarySeat:
                    LibrarySeatView(environment: environment)
                }
            }
        }
    }

    private func destinationFor(_ id: String) -> ServicesDestination? {
        switch id {
        case "free_classroom": .freeClassroom
        case "electricity_tangdaowan": .power
        case "campus_card_balance": .cardBalance
        case "library_seat": .librarySeat
        default: nil
        }
    }

    private func serviceRow(
        title: String,
        subtitle: String,
        badge: String,
        isExternal: Bool
    ) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .foregroundStyle(.primary)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if isExternal {
                Image(systemName: "arrow.up.right.square")
                    .foregroundStyle(UPCTheme.brandBlue)
            } else {
                Text(badge)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(.quaternary, in: Capsule())
            }
        }
    }
}
