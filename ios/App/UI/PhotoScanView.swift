import PhotosUI
import SwiftUI

/// Recognise a card on a photo from the library (the live camera follows in the scanner).
struct PhotoScanView: View {
    @EnvironmentObject var model: AppModel
    @State private var item: PhotosPickerItem?
    @State private var busy = false
    @State private var photo: UIImage?
    @State private var result: (card: CardInfo, match: Match, margin: Float, ms: Double)?
    @State private var message: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    PhotosPicker(selection: $item, matching: .images) {
                        Label("Choose a photo of a card", systemImage: "photo.on.rectangle")
                            .frame(maxWidth: .infinity).padding(.vertical, 12)
                    }
                    .buttonStyle(.borderedProminent)
                    .foregroundStyle(Theme.ink)
                    if busy { ProgressView() }
                    if let message { Text(message).foregroundStyle(Theme.muted) }
                    if let r = result {
                        NavigationLink { CardDetail(card: r.card, fallback: photo) } label: {
                            VStack(alignment: .leading, spacing: 8) {
                                CardRow(card: r.card)
                                Text(String(format: "score %.3f · margin %.3f · %@ · %.0f ms", r.match.score, r.margin, r.match.readingName, r.ms))
                                    .font(.caption2.monospacedDigit()).foregroundStyle(Theme.muted)
                            }
                            .padding(12)
                            .background(Theme.panel, in: RoundedRectangle(cornerRadius: 12))
                        }
                        .buttonStyle(.plain)
                    }
                    if let photo { Image(uiImage: photo).resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 8)) }
                }
                .padding(16)
            }
            .background(Theme.ink)
            .navigationTitle("Scan")
        }
        .onChange(of: item) { _, new in
            guard let new else { return }
            Task { await scan(new) }
        }
    }

    private func scan(_ item: PhotosPickerItem) async {
        busy = true; message = nil; result = nil
        defer { busy = false }
        guard let data = try? await item.loadTransferable(type: Data.self), let engine = model.engine, let cat = model.catalog else {
            message = "The photo couldn't be read."; return
        }
        let out: (UIImage?, (CardInfo, Match, Float, Double)?) = await Task.detached {
            guard let img = RGBImage(imageData: data) else { return (nil, nil) }
            let t0 = Date()
            let s = engine.recognize(img)
            let ms = Date().timeIntervalSince(t0) * 1000
            guard let best = s.best, let card = cat.card(best.group, "en") else { return (img.uiImage, nil) }
            let crop = engine.cardImage(img, s, best)?.uiImage
            return (crop ?? img.uiImage, (card, best, s.margin, ms))
        }.value
        photo = out.0
        if let r = out.1 { result = (r.0, r.1, r.2, r.3) } else { message = "No card recognised on this photo." }
    }
}
