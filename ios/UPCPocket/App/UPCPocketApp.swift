import SwiftUI

@main
struct UPCPocketApp: App {
    @State private var environment = AppEnvironment()

    var body: some Scene {
        WindowGroup {
            RootTabView(environment: environment)
                .preferredColorScheme(environment.preferences.theme.colorScheme)
                .task {
                    await environment.bootstrap()
                }
        }
    }
}
