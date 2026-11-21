import PhotosUI
import SwiftUI

/**
 * The scanner: camera and result share the screen half and half (as on Android). The camera's
 * corners hold the cart (top right) and a photo from the library (bottom right); Add to cart on the
 * result opens the popup that prices the card for a deal. Search, alert and settings live in their tabs.
 */
struct ScannerScreen: View {
    @ObservedObject var model: ScannerModel
    @ObservedObject var trading: TradingModel
    @EnvironmentObject var account: AccountModel
    @State private var photoItem: PhotosPickerItem?
    @State private var locked: LockedFeature?
    @State private var replacing = false
    @State private var deal: DealCard?
    @State private var cartOpen = false
    @State private var cameraAllowed: Bool?

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                if cameraAllowed == false {
                    VStack(spacing: 12) {
                        Text("The scanner needs the camera.").foregroundStyle(.white)
                        Button("Open Settings") { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
                    }
                } else {
                    CameraArea(model: model)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .overlay(alignment: .topTrailing) { cartButton.padding(10) }
            .overlay(alignment: .bottomTrailing) { photoButton.padding(10) }
            .clipped()
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let s = model.shown {
                        ResultCard(s: s, model: model, onSearch: { replacing = true }, onAddToCart: { openDeal(s) })
                    } else if account.isFree {
                        Text("Hold a card inside the frame. Its name appears here – reveal its price with a tap (\(AccountModel.freeRevealsPerHour) per hour in the free version). Not recognised? Double-tap the picture to take it anyway. Pinch to zoom.")
                            .foregroundStyle(Theme.muted)
                    } else {
                        Text("Hold a card inside the frame. Its name and prices appear here – Add to cart prices it for a deal. The phone vibrates when a card is worth at least your alert price (Settings). Not recognised? Double-tap the picture to take it anyway. Pinch to zoom.")
                            .foregroundStyle(Theme.muted)
                    }
                    if model.settings.showDebug || (model.shown?.fromPhoto == true && model.shown?.fromSearch == false), !model.debugLines.isEmpty {
                        DebugPanel(lines: model.debugLines, scan: model.shown?.scan)
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Theme.ink)
        .cameraLifecycle(model: model, allowed: $cameraAllowed)
        // the free version: no price alerts (they would tell the price)
        .task(id: account.isFree) { model.alertsAllowed = !account.isFree }
        .lockedAlert($locked)
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self), let img = RGBImage(imageData: data) { model.onPhoto(img) }
                else { model.message = "The photo couldn't be read." }
                photoItem = nil
            }
        }
        .sheet(item: Binding(get: { model.picks.map { PickSheet.Items(list: $0) } }, set: { if $0 == nil { model.closePicks() } })) { items in
            PickSheet(list: items.list, shown: model.shown, showPrices: !account.isFree, searchLocked: account.isFree, onPick: model.pick(_:),
                      onSearch: {
                          model.closePicks()
                          // after the sheet has gone (an alert can't come up while it's leaving)
                          if account.isFree { DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { locked = .search } } else { replacing = true }
                      })
                .presentationDetents([.medium, .large])
        }
        // "Not this card? Search…": the card found replaces the one shown
        .sheet(isPresented: $replacing) {
            CardSearchSheet(resolver: model.resolver) { c in
                replacing = false
                if model.shown != nil { model.replaceWith(c) } else { model.lookUp(c) }
            }
        }
        .sheet(item: $deal) { d in DealSheet(d: d, trading: trading).presentationDetents([.large]) }
        .sheet(isPresented: $cartOpen) { CartSheet(trading: trading) }
        .alert(model.message ?? "", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
            Button("OK", role: .cancel) {}
        }
        .alert("Add the cards to your collection?", isPresented: Binding(get: { trading.offerCart != nil }, set: { if !$0 { trading.offerCart = nil } })) {
            Button("Add") { if let id = trading.offerCart { trading.addCartToCollection(id, account: account) } }
            Button("Not now", role: .cancel) { trading.offerCart = nil }
        } message: { Text("Each with its condition and the price you paid per copy.") }
        .overlay(alignment: .bottom) {
            if let m = trading.message {
                Text(m).font(.footnote).foregroundStyle(.white).padding(.horizontal, 14).padding(.vertical, 8)
                    .background(.black.opacity(0.8), in: Capsule()).padding(.bottom, 12)
                    .task(id: m) { try? await Task.sleep(nanoseconds: 1_800_000_000); trading.message = nil }
            }
        }
    }

    private func openDeal(_ s: Shown) {
        trading.startDeal()
        deal = DealCard(s)
    }

    /// the active cart, with its number of cards; locked in the free version
    private var cartButton: some View {
        let open = account.has("trading")
        let count = open ? (trading.book.active.map { $0.cards + $0.giveCards } ?? 0) : 0
        return Button { if open { cartOpen = true } else { locked = .trading } } label: {
            CornerIcon(icon: "cart", locked: !open)
                .overlay(alignment: .topTrailing) {
                    if count > 0 {
                        Text("\(count)").font(.caption2.bold().monospacedDigit()).foregroundStyle(Theme.ink)
                            .padding(.horizontal, 5).frame(minWidth: 18, minHeight: 18)
                            .background(Theme.gold, in: Capsule()).offset(x: 6, y: -6)
                    }
                }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(open ? "Cart" + (count > 0 ? ", \(count) cards" : "") : "Cart, needs a subscription")
    }

    /// a card on a photo from the library; locked in the free version
    @ViewBuilder private var photoButton: some View {
        if account.isFree {
            Button { locked = .photo } label: { CornerIcon(icon: "photo", locked: true) }
                .buttonStyle(.plain)
                .accessibilityLabel("Photo, needs a subscription")
        } else {
            PhotosPicker(selection: $photoItem, matching: .images) { CornerIcon(icon: "photo", locked: false) }
                .accessibilityLabel("Scan a photo")
        }
    }
}

/// A round button in a corner of the camera picture (a small lock when the plan lacks it).
struct CornerIcon: View {
    let icon: String
    var locked = false

    var body: some View {
        Image(systemName: icon)
            .font(.system(size: 19, weight: .semibold))
            .foregroundStyle(locked ? Theme.muted : .white)
            .frame(width: 44, height: 44)
            .background(.black.opacity(0.55), in: Circle())
            .overlay(alignment: .bottomTrailing) {
                if locked { Image(systemName: "lock.fill").font(.system(size: 10)).foregroundStyle(Theme.muted).offset(x: -4, y: -4) }
            }
            .contentShape(Circle())
    }
}

/// The camera picture with the guide frame. Double tap takes the card by hand, pinch zooms.
struct CameraArea: View {
    @ObservedObject var model: ScannerModel
    @State private var flash = 0.0
    @State private var pinchStart: Double?
    @State private var showZoom = false

    var body: some View {
        GeometryReader { geo in
            ZStack {
                CameraPreview(camera: model.camera)
                GuideOverlay(model: model, size: geo.size)
                Color.white.opacity(flash).allowsHitTesting(false)
                if showZoom || model.settings.zoom > 1.01 {
                    Text(zoomLabel(model.settings.zoom))
                        .font(.footnote.bold()).foregroundStyle(.white)
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 10))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).padding(10)
                }
                if !model.paused {
                    Text(hint)
                        .font(.subheadline).foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.75)
                        .padding(.horizontal, 12).padding(.vertical, 4)
                        .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 12))
                        // clear of the photo button in the corner
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom).padding(.vertical, 10).padding(.horizontal, 60)
                } else {
                    Color.black.opacity(0.66)
                        .overlay(Text(model.shown?.fromSearch == true ? "Looked up · tap to scan again" : "Tap to scan again")
                            .font(.title3).foregroundStyle(.white))
                        .onTapGesture { model.resume() }
                }
            }
            .contentShape(Rectangle())
            .onTapGesture(count: 2) { model.manualTrigger() }
            .gesture(MagnifyGesture()
                .onChanged { v in
                    if pinchStart == nil { pinchStart = model.settings.zoom }
                    showZoom = true
                    model.setZoom((pinchStart ?? 1) * v.magnification)
                }
                .onEnded { _ in
                    pinchStart = nil
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { showZoom = false }
                })
            .onAppear { model.setViewAspect(Double(geo.size.width / max(1, geo.size.height))) }
            .onChange(of: geo.size) { _, s in model.setViewAspect(Double(s.width / max(1, s.height))) }
            .onChange(of: model.flash) { _, _ in
                flash = 0.75
                withAnimation(.easeOut(duration: 0.38)) { flash = 0 }
            }
        }
        .background(.black)
    }

    private var hint: String {
        if model.shown?.locked == true { return "Kept – tap “Next card” for the next one" }
        switch model.liveConfidence {
        case .sure: return "Got it"
        case .likely: return "Probably…"
        case .unsure: return "Fit the card into the frame"
        }
    }
}

/// false while the page with a camera screen is hidden behind another page (the camera then stops)
private struct CameraActiveKey: EnvironmentKey { static let defaultValue = true }

extension EnvironmentValues {
    var cameraActive: Bool {
        get { self[CameraActiveKey.self] }
        set { self[CameraActiveKey.self] = newValue }
    }
}

extension View {
    /// The camera runs while this screen is shown and the app is in front.
    func cameraLifecycle(model: ScannerModel, allowed: Binding<Bool?>) -> some View {
        modifier(CameraLifecycle(model: model, allowed: allowed))
    }
}

private struct CameraLifecycle: ViewModifier {
    let model: ScannerModel
    @Binding var allowed: Bool?
    @State private var acquired = false
    @State private var onScreen = false
    @Environment(\.scenePhase) private var phase
    @Environment(\.cameraActive) private var active

    /// the camera is used while the screen is there, its page is the one shown, and access is allowed
    private func update() {
        let use = onScreen && active && allowed == true
        if use && !acquired { acquired = true; model.camera.acquire(); model.camera.setZoom(model.settings.zoom) }
        else if !use && acquired { acquired = false; model.camera.release() }
    }

    func body(content: Content) -> some View {
        content
            .task {
                onScreen = true
                allowed = await Camera.authorized()
                update()
            }
            .onDisappear { onScreen = false; update() }
            .onChange(of: active) { _, _ in update() }
            .onChange(of: phase) { _, p in
                if p == .active { model.camera.resume() } else if p == .background { model.camera.pause() }
            }
    }
}

func zoomLabel(_ z: Double) -> String {
    abs(z - z.rounded()) < 0.05 ? "\(Int(z.rounded()))×" : String(format: "%.1f×", z)
}

/// The guide frame (green when sure, gold when likely) and the outline the scanner read.
struct GuideOverlay: View {
    @ObservedObject var model: ScannerModel
    let size: CGSize

    var body: some View {
        Canvas { ctx, sz in
            let w = max(1, Int(sz.width)), h = max(1, Int(sz.height))
            let g = ScannerEngine.guideQuad(width: w, height: h, prior: ScannerEngine.guidePrior(width: w, height: h, size: model.settings.guideFraction))
            let rect = CGRect(x: g[0].x, y: g[0].y, width: g[1].x - g[0].x, height: g[3].y - g[0].y)
            let col: Color
            switch model.liveConfidence {
            case .sure: col = Theme.sure
            case .likely: col = Theme.likely
            case .unsure: col = .white
            }
            let frame = Path(roundedRect: rect, cornerRadius: 9)
            var dim = Path(CGRect(origin: .zero, size: sz))
            dim.addPath(frame)
            ctx.fill(dim, with: .color(.black.opacity(0.4)), style: FillStyle(eoFill: true))
            ctx.stroke(frame, with: .color(col), lineWidth: 2)
            // hold still: the green bar under the frame fills up until the card is taken
            if let p = model.liveProgress, p > 0, model.liveConfidence == .sure {
                let y = min(rect.maxY + 7, sz.height - 4)
                var bg = Path(); bg.move(to: CGPoint(x: rect.minX, y: y)); bg.addLine(to: CGPoint(x: rect.maxX, y: y))
                ctx.stroke(bg, with: .color(.white.opacity(0.25)), style: StrokeStyle(lineWidth: 4, lineCap: .round))
                var fg = Path(); fg.move(to: CGPoint(x: rect.minX, y: y)); fg.addLine(to: CGPoint(x: rect.minX + rect.width * p, y: y))
                ctx.stroke(fg, with: .color(Theme.sure), style: StrokeStyle(lineWidth: 4, lineCap: .round))
            }
            if let q = model.liveQuad, q.count == 4, !model.paused {
                let sx = sz.width / model.liveFrame.width, sy = sz.height / model.liveFrame.height
                var path = Path()
                path.move(to: CGPoint(x: q[0].x * sx, y: q[0].y * sy))
                for i in 1..<4 { path.addLine(to: CGPoint(x: q[i].x * sx, y: q[i].y * sy)) }
                path.closeSubpath()
                ctx.stroke(path, with: .color(col.opacity(0.9)), lineWidth: 1.5)
            }
        }
        .allowsHitTesting(false)
    }
}

struct DebugPanel: View {
    let lines: [String]
    let scan: UIImage?

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if let scan { Image(uiImage: scan).resizable().scaledToFit().frame(width: 80) }
            VStack(alignment: .leading, spacing: 2) {
                ForEach(Array(lines.enumerated()), id: \.offset) { _, l in
                    Text(l).font(.system(size: 11, design: .monospaced)).foregroundStyle(Theme.muted)
                }
            }
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.panel, in: RoundedRectangle(cornerRadius: 8))
        .padding(.top, 12)
    }
}
