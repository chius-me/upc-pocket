import SwiftUI

enum AppTab: Hashable, Sendable {
    case home
    case timetable
    case services
    case focus
}

struct RootTabView: View {
    @Bindable var environment: AppEnvironment
    @State private var selectedTab: AppTab = .home

    var body: some View {
        TabView(selection: $selectedTab) {
            Tab("首页", systemImage: "house", value: AppTab.home) {
                HomeView(environment: environment, selectedTab: $selectedTab)
            }

            Tab("课表", systemImage: "calendar", value: AppTab.timetable) {
                TimetableView(environment: environment)
            }

            Tab("服务", systemImage: "square.grid.2x2", value: AppTab.services) {
                ServicesView(environment: environment)
            }

            Tab("专注", systemImage: "timer", value: AppTab.focus) {
                FocusView(environment: environment)
            }
        }
        .upcTabBarMinimizeOnScroll()
    }
}
