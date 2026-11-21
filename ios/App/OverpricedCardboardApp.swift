import SwiftUI

@main
struct OverpricedCardboardApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    init() {
        PackUpdate.register()
        UploadQueue.register()
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
                // the screen stays on while the app is open (scanning at a show, a long trade)
                .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        }
        .onChange(of: phase) { _, p in
            UIApplication.shared.isIdleTimerDisabled = p == .active
            if p == .active { model.onForeground(); UploadQueue.shared.kick() }
            if p == .background { PackUpdate.schedule(); UploadQueue.schedule() }
        }
    }
}
