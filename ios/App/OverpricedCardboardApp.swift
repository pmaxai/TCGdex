import SwiftUI

@main
struct OverpricedCardboardApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    init() {
        PackUpdate.register()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .environmentObject(model.account)
                .preferredColorScheme(.dark)
                .tint(Theme.gold)
                .onOpenURL { model.open($0) }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { a in if let u = a.webpageURL { model.open(u) } }
        }
        .onChange(of: phase) { _, p in
            if p == .active { model.onForeground() }
            if p == .background { PackUpdate.schedule() }
        }
    }
}
