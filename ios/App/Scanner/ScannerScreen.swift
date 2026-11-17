import PhotosUI
import SwiftUI

/// The scanner: camera and result share the screen half and half (as on Android).
struct ScannerScreen: View {
    @ObservedObject var model: ScannerModel
    @State private var torch = false
    @State private var photoItem: PhotosPickerItem?
    @State private var search: SearchMode?
    @State private var showSettings = false
    @State private var cameraAllowed: Bool?
    @Environment(\.scenePhase) private var phase

    enum SearchMode: Identifiable { case lookUp, replace; var id: Int { self == .lookUp ? 0 : 1 } }

    var body: some View {
        VStack(spacing: 0) {
            topBar
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
            .clipped()
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let s = model.shown {
                        ResultCard(s: s, model: model, onSearch: { search = .replace })
                    } else {
                        Text("Hold a card inside the frame. Its name and Cardmarket price appear here; the phone vibrates when any printing of it is worth at least your alert price. Not recognised? Double-tap the picture to take it anyway. Pinch to zoom.")
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
        .task {
            cameraAllowed = await Camera.authorized()
            if cameraAllowed == true { model.camera.start(); model.camera.setZoom(model.settings.zoom) }
        }
        .onChange(of: phase) { _, p in
            if p == .active, cameraAllowed == true { model.camera.start() } else if p == .background { model.camera.stop() }
        }
        .onDisappear { model.camera.stop() }
        .onChange(of: torch) { _, on in model.camera.setTorch(on) }
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self), let img = RGBImage(imageData: data) { model.onPhoto(img) }
                else { model.message = "The photo couldn't be read." }
                photoItem = nil
            }
        }
        .sheet(item: Binding(get: { model.picks.map { PickSheet.Items(list: $0) } }, set: { if $0 == nil { model.closePicks() } })) { items in
            PickSheet(list: items.list, shown: model.shown, onPick: model.pick(_:), onSearch: { model.closePicks(); search = .replace })
                .presentationDetents([.medium, .large])
        }
        .sheet(item: $search) { mode in
            CardSearchSheet(resolver: model.resolver) { c in
                search = nil
                if mode == .replace && model.shown != nil { model.replaceWith(c) } else { model.lookUp(c) }
            }
        }
        .sheet(isPresented: $showSettings) { ScannerSettingsView(settings: $model.settings) }
        .alert(model.message ?? "", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private var topBar: some View {
        HStack(spacing: 6) {
            Button { search = .lookUp } label: {
                Label("Search cards", systemImage: "magnifyingglass")
                    .lineLimit(1).foregroundStyle(Theme.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(Theme.panel, in: Capsule())
            }
            Button { showSettings = true } label: {
                Text(model.settings.threshold > 0 ? "Alert ≥ \(Int(model.settings.threshold)) €" : "Alert off")
                    .font(.footnote).lineLimit(1)
                    .padding(.horizontal, 10).padding(.vertical, 7)
                    .overlay(Capsule().stroke(Theme.muted.opacity(0.5)))
            }
            .foregroundStyle(.white)
            Button { torch.toggle() } label: { Image(systemName: torch ? "flashlight.on.fill" : "flashlight.off.fill") }
                .foregroundStyle(torch ? Theme.gold : .white)
                .accessibilityLabel("Light")
            PhotosPicker(selection: $photoItem, matching: .images) { Image(systemName: "photo") }
                .foregroundStyle(.white)
                .accessibilityLabel("Photo")
            Button { showSettings = true } label: { Image(systemName: "gearshape") }
                .foregroundStyle(.white)
                .accessibilityLabel("Settings")
        }
        .font(.body)
        .padding(.horizontal, 8).padding(.vertical, 6)
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
                        .font(.subheadline).foregroundStyle(.white).lineLimit(1)
                        .padding(.horizontal, 12).padding(.vertical, 4)
                        .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 12))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom).padding(10)
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
            let col: Color = switch model.liveConfidence { case .sure: Theme.sure; case .likely: Theme.likely; case .unsure: .white }
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
