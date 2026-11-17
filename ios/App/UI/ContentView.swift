import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        switch model.state {
        case .loading:
            VStack(spacing: 16) {
                ProgressView()
                Text("Loading the card data…").foregroundStyle(Theme.muted)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.ink)
        case .failed(let message):
            VStack(spacing: 16) {
                Text("The card data couldn't be loaded").font(.headline)
                Text(message).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
                Button("Try again") { Task { await model.load() } }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.ink)
        case .ready:
            TabView {
                PhotoScanView()
                    .tabItem { Label("Scan", systemImage: "camera.viewfinder") }
                SearchView()
                    .tabItem { Label("Search", systemImage: "magnifyingglass") }
            }
        }
    }
}
