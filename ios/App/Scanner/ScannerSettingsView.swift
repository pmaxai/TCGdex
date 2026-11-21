import SwiftUI

struct ScannerSettingsView: View {
    @Binding var settings: ScannerSettings
    /// the free version: price alerts are shown locked
    var alertsLocked = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Price alert") {
                    if alertsLocked {
                        Label("Vibrate at a price: needs a subscription", systemImage: "lock.fill").foregroundStyle(Theme.muted)
                    } else {
                        Picker("Vibrate at", selection: $settings.threshold) {
                            Text("off").tag(0.0)
                            ForEach([1.0, 2, 5, 10, 20, 50, 100], id: \.self) { Text("≥ \(Int($0)) €").tag($0) }
                        }
                    }
                    Toggle("Show PSA prices", isOn: $settings.showPsa)
                }
                Section {
                    Picker("Wait before the shot", selection: $settings.triggerDelayMs) {
                        ForEach([0, 300, 600, 1000, 1500, 2000], id: \.self) { Text($0 == 0 ? "instant" : String(format: "%.1f s", Double($0) / 1000)).tag($0) }
                    }
                    Picker("Capture frame", selection: $settings.guideSize) {
                        ForEach([50, 60, 70, 80, 90], id: \.self) { Text("\($0) %").tag($0) }
                    }
                    Picker("Energy cards", selection: $settings.energyFilter) {
                        Text("recognise").tag(0)
                        Text("skip common energy").tag(1)
                        Text("skip all energy").tag(2)
                    }
                } header: { Text("Scanner") } footer: {
                    Text("The frame turns green when the card is sure; it is taken once it stayed green this long.")
                }
                Section("Details") {
                    Toggle("Show what the scanner sees", isOn: $settings.showDebug)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
