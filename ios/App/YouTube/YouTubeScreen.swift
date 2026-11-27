import SwiftUI
import UIKit

/**
 * YouTube Mode (the Scanner tab's second mode, Creator plan): for unboxing and price-check videos,
 * phone on a tripod. Full screen, camera and card half and half, an animated reveal for every new
 * card, a HIT above the alert price, and the pack's running value. ⚙ beside the pack value opens
 * the menu (save pack, new pack, light, settings, exit …). The same screen as the Android app's.
 */
struct YouTubeScreen: View {
    @ObservedObject var model: ScannerModel
    @ObservedObject var rec: RecordingModel
    @EnvironmentObject var account: AccountModel
    @State private var torch = false
    @State private var search: SearchUse?
    @State private var shareFile: ShareFile?
    @State private var cameraAllowed: Bool?
    @State private var locked: LockedFeature?
    @State private var toast: String?
    @State private var startSheet = false
    @State private var recordings = false

    enum SearchUse: String, Identifiable { case lookUp, replace; var id: String { rawValue } }

    var body: some View {
        GeometryReader { outer in
            // while recording with Fit to 16:9: the screen in a centred 9:16 area, black around it; else inside the safe area
            let fit = rec.recording && rec.settings.fit169
            let inset = outer.safeAreaInsets
            let area = fit ? Self.fitted(outer.size)
                : CGSize(width: outer.size.width - inset.leading - inset.trailing, height: outer.size.height - inset.top - inset.bottom)
            let center = fit ? CGPoint(x: outer.size.width / 2, y: outer.size.height / 2)
                : CGPoint(x: inset.leading + area.width / 2, y: inset.top + area.height / 2)
            let band = (outer.size.height - area.height) / 2
            ZStack {
                Color.black
                content
                    .frame(width: area.width, height: area.height)
                    .background(Theme.ink)
                    .background(GeometryReader { g in
                        Color.clear.onChange(of: g.frame(in: .global), initial: true) { _, f in rec.contentRect = f }
                    })
                    .position(center)
                // the dot and the time below the 9:16 area, left out of the video (black there, on black)
                if fit && rec.settings.showTimer && band >= 28 {
                    RecIndicator(rec: rec)
                        .frame(width: outer.size.width, height: band)
                        .position(x: outer.size.width / 2, y: outer.size.height - band / 2)
                }
            }
            .frame(width: outer.size.width, height: outer.size.height)
        }
        .ignoresSafeArea()
        .cameraLifecycle(model: model, allowed: $cameraAllowed)
        .onDisappear { if torch { torch = false } }
        .onChange(of: torch) { _, on in model.camera.setTorch(on) }
        .sheet(item: $search) { use in
            CardSearchSheet(resolver: model.resolver) { c in
                search = nil
                if use == .replace && model.shown != nil { model.replaceWith(c) } else { model.lookUp(c) }
            }
        }
        .sheet(item: $shareFile) { f in
            ShareSheet(url: f.url) { done in
                shareFile = nil
                if done { toast = "Pack saved: \(f.cards) cards" }
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $startSheet) { StartRecordingSheet(rec: rec, scanner: model) }
        .sheet(isPresented: $recordings) { RecordingsSheet(rec: rec, account: account) }
        // a finished recording: submit, save to Photos, or delete
        .sheet(item: Binding(get: { rec.recording ? nil : rec.toDecide }, set: { if $0 == nil { rec.decideLater() } })) { r in
            RecordingDoneSheet(rec: rec, recording: r, account: account).interactiveDismissDisabled()
        }
        .lockedAlert($locked)
        .alert(model.message ?? "", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
            Button("OK", role: .cancel) {}
        }
        .alert(rec.error ?? "", isPresented: Binding(get: { rec.error != nil }, set: { if !$0 { rec.error = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    /// the largest 9:16 area in the screen
    nonisolated static func fitted(_ s: CGSize) -> CGSize {
        let h = min(s.height, s.width * 16 / 9)
        return CGSize(width: h * 9 / 16, height: h)
    }

    private var content: some View {
        VStack(spacing: 0) {
            TitleLine(title: model.settings.title)
            ZStack {
                if cameraAllowed == false {
                    VStack(spacing: 12) {
                        Text("YouTube Mode needs the camera.").foregroundStyle(.white)
                        Button("Open Settings") { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
                    }
                } else {
                    CameraArea(model: model, video: true)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipped()
            VStack(spacing: 0) {
                Reveal(model: model, psaPrices: account.has("psa_prices"), onSearch: { search = .replace },
                       onDrawn: { rec.revealDrawn($0) })
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                SessionBar(model: model, rec: { RecButton(rec: rec, onStart: startRecording) }, menu: {
                    YouTubeMenu(packEmpty: model.session.isEmpty, hasTorch: model.camera.hasTorch, torch: torch, recording: rec.recording,
                                act: menuAction)
                        .equatable()
                })
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        // after Stop: what was recorded and where it stands (gone at the next Start)
        .overlay(alignment: .top) {
            if !rec.recording, let line = rec.lastSummary {
                Button { recordings = true } label: {
                    Text(line).font(.system(size: 13)).foregroundStyle(.white).lineLimit(2)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain).padding(8)
            }
        }
        .overlay(alignment: .bottom) {
            if let t = toast {
                Text(t).font(.footnote).foregroundStyle(.white).padding(.horizontal, 14).padding(.vertical, 8)
                    .background(.black.opacity(0.8), in: Capsule()).padding(.bottom, 90)
                    .task(id: t) { try? await Task.sleep(nanoseconds: 2_200_000_000); toast = nil }
            }
        }
    }

    private func startRecording() {
        if !account.has("recording") { locked = .recording }
        else if rec.settings.readyToRecord { startSheet = true }
        else { NotificationCenter.default.post(name: .openSettingsPage, object: SettingsPage.recording) }
    }

    private func savePack() {
        let csv = PackCsv.build(model.session, threshold: model.settings.threshold, title: model.settings.title)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(PackCsv.fileName(title: model.settings.title))
        do {
            try csv.write(to: url, atomically: true, encoding: .utf8)
            shareFile = ShareFile(url: url, cards: model.session.count)
        } catch { model.message = "Could not save: \(error.localizedDescription)" }
    }

    /// what an entry of the ⚙ menu does
    private func menuAction(_ a: YouTubeMenu.Action) {
        switch a {
        case .savePack: savePack()
        case .newPack: model.resetSession()
        case .removeLast: model.undoLast()
        case .next: model.next()
        case .light: torch.toggle()
        case .search: search = .lookUp
        case .startRecording: startRecording()
        case .stopRecording: rec.stop(reason: "user")
        case .recordings: recordings = true
        case .settings: NotificationCenter.default.post(name: .openSettingsPage, object: SettingsPage.youtube)
        case .exit: model.settings.youtube = false
        }
    }
}

/**
 * ⚙: YouTube Mode's menu. It is drawn again only when what it shows changes – not with every
 * camera frame the scanner reports: an open menu that is rebuilt under the finger loses the tap.
 */
struct YouTubeMenu: View, Equatable {
    enum Action { case savePack, newPack, removeLast, next, light, search, startRecording, stopRecording, recordings, settings, exit }

    let packEmpty: Bool
    let hasTorch: Bool
    let torch: Bool
    let recording: Bool
    let act: (Action) -> Void

    /// the actions don't count: they stay the same
    static func == (a: YouTubeMenu, b: YouTubeMenu) -> Bool {
        a.packEmpty == b.packEmpty && a.hasTorch == b.hasTorch && a.torch == b.torch && a.recording == b.recording
    }

    var body: some View {
        Menu {
            Button("Save pack (CSV)") { act(.savePack) }.disabled(packEmpty)
            Button("New pack (reset value)") { act(.newPack) }
            Button("Remove last card") { act(.removeLast) }.disabled(packEmpty)
            Button("Next card") { act(.next) }
            if hasTorch { Button(torch ? "Light off" : "Light on") { act(.light) } }
            Divider()
            Button("Search a card…") { act(.search) }
            if recording { Button("Stop recording") { act(.stopRecording) } }
            else { Button("Start recording") { act(.startRecording) } }
            Button("Recordings…") { act(.recordings) }
            Button("Settings…") { act(.settings) }
            Button("Exit YouTube mode") { act(.exit) }.disabled(recording)
        } label: {
            Image(systemName: "gearshape.fill").font(.system(size: 20)).foregroundStyle(.white)
                .frame(width: 44, height: 44).background(Theme.panel, in: Circle())
        }
        .menuOrder(.fixed)
        .accessibilityLabel("YouTube Mode menu")
    }
}

/// A file for the share sheet (Save to Files, AirDrop, Mail, Numbers …).
struct ShareFile: Identifiable {
    let url: URL
    var cards = 0
    var id: String { url.path }
}

/// The system's share sheet for one file; done: it was saved or sent.
struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    var onDone: (Bool) -> Void = { _ in }

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let vc = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        vc.completionWithItemsHandler = { _, completed, _, _ in onDone(completed) }
        return vc
    }

    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

/// The title line at the top of the picture (Settings › YouTube Mode).
private struct TitleLine: View {
    let title: String
    var body: some View {
        if !title.trimmingCharacters(in: .whitespaces).isEmpty {
            Text(title).font(.system(size: 20, weight: .bold)).foregroundStyle(.white)
                .lineLimit(1).minimumScaleFactor(0.5)
                .frame(maxWidth: .infinity).padding(8)
        }
    }
}

/// The shown card, revealed: it pops in with every new card (each card has its own serial).
struct Reveal: View {
    @ObservedObject var model: ScannerModel
    let psaPrices: Bool
    let onSearch: () -> Void
    /// the recording's log takes the reveal's time from its first frame on screen
    var onDrawn: (Int) -> Void = { _ in }

    var body: some View {
        ZStack {
            if let s = model.shown {
                FitInside {
                    SwipeToRemove(onRemove: model.dismissShown) {
                        RevealCard(s: s, threshold: model.settings.threshold, showPsa: model.settings.showPsa, psaPrices: psaPrices,
                                   copies: model.session.filter { $0.group == s.group }.count, model: model, onSearch: onSearch)
                    }
                }
                .id(s.serial)
                .transition(.asymmetric(insertion: .scale(scale: 0.55).combined(with: .opacity.animation(.easeIn(duration: 0.12))),
                                        removal: .opacity.animation(.easeOut(duration: 0.12))))
                .onAppear { let serial = s.serial; DispatchQueue.main.async { onDrawn(serial) } }
            } else {
                Text("Show the next card…").font(.system(size: 22)).foregroundStyle(Theme.muted)
                    .lineLimit(1).minimumScaleFactor(0.5)
                    .transition(.opacity)
            }
        }
        .animation(.spring(response: 0.45, dampingFraction: 0.55), value: model.shown?.serial)
    }
}

/**
 * Lays the card out at a fixed width (designWidth) and scales it evenly to the space it has: the
 * same look on every phone, nothing wrapped, cut off or squeezed (a HIT card with English name,
 * PSA prices and printing chips just gets a bit smaller).
 */
struct FitInside<Content: View>: View {
    var designWidth: CGFloat = 440
    var maxScale: CGFloat = 1.25
    @ViewBuilder var content: Content
    @State private var height: CGFloat = 1

    var body: some View {
        GeometryReader { geo in
            let scale = max(0.2, min(maxScale, geo.size.width / designWidth, geo.size.height / max(1, height)))
            content
                .frame(width: designWidth)
                .fixedSize(horizontal: false, vertical: true)
                .background(GeometryReader { g in Color.clear.preference(key: FitHeightKey.self, value: g.size.height) })
                .onPreferenceChange(FitHeightKey.self) { height = max(1, $0) }
                .scaleEffect(scale)
                .frame(width: geo.size.width, height: geo.size.height)
        }
    }
}

/// the height of FitInside's content at its design width
private struct FitHeightKey: PreferenceKey {
    static var defaultValue: CGFloat { 1 }
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

/// Swipe to the left to take it out of the pack: a wrong match, or a card you don't count.
struct SwipeToRemove<Content: View>: View {
    let onRemove: () -> Void
    var label = "Remove from pack"
    @ViewBuilder var content: Content
    @State private var offset: CGFloat = 0
    @State private var width: CGFloat = 1
    static var removeAt: CGFloat { 0.3 }

    var body: some View {
        ZStack {
            if offset < -8 {
                // what letting go will do
                RoundedRectangle(cornerRadius: 20).fill(Color(red: 0x7A / 255, green: 0x1F / 255, blue: 0x1F / 255))
                    .overlay(alignment: .trailing) {
                        Text(offset < -width * Self.removeAt ? "Release to remove" : label)
                            .font(.system(size: 18, weight: .bold)).foregroundStyle(.white).padding(.trailing, 24)
                    }
            }
            content
                .offset(x: offset)
                .opacity(1 - min(0.6, max(0, -offset / width)))
        }
        .background(GeometryReader { g in
            Color.clear.onChange(of: g.size.width, initial: true) { _, w in width = max(1, w) }
        })
        .contentShape(Rectangle())
        .gesture(DragGesture(minimumDistance: 14)
            .onChanged { v in
                guard abs(v.translation.width) > abs(v.translation.height) || offset != 0 else { return }
                offset = min(0, max(-width * 1.1, v.translation.width))
            }
            .onEnded { _ in
                if offset < -width * Self.removeAt {
                    withAnimation(.easeIn(duration: 0.16)) { offset = -width * 1.1 }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.16) { onRemove() }
                } else {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.6)) { offset = 0 }
                }
            })
        .accessibilityAction(named: label) { onRemove() }
    }
}

/// The card as YouTube Mode reveals it: picture, names, version, the price counting up, PSA, printings.
struct RevealCard: View {
    let s: Shown
    let threshold: Double
    let showPsa: Bool
    let psaPrices: Bool
    /// the same card more than once in this pack (+1)
    let copies: Int
    @ObservedObject var model: ScannerModel
    let onSearch: () -> Void
    @State private var counted = 0.0

    static let countUpSeconds = 0.9
    static let burstSeconds = 1.6

    var body: some View {
        let value = s.value
        let hit = isHit(value, threshold)
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 14) {
                ShownPicture(s: s)
                    .frame(width: 120)
                    .overlay(alignment: .topLeading) {
                        if copies > 1 {
                            Text("×\(copies)").font(.system(size: 18, weight: .black)).foregroundStyle(Theme.ink).lineLimit(1)
                                .padding(.horizontal, 7)
                                .background(Theme.gold, in: RoundedRectangle(cornerRadius: 8)).padding(4)
                        }
                    }
                VStack(alignment: .leading, spacing: 0) {
                    fit(s.version.name, 30, .black, .white, min: 16)
                    if let e = s.english { fit(e.name, 16, .regular, .white.opacity(0.8)) }
                    fit(s.version.setLine(withLang: false), 15, .regular, Theme.muted)
                    VersionSwitcher(s: s, onStep: model.stepVersion, onVersion: model.chooseVersion, onOther: model.choose, onSearch: onSearch)
                        .equatable()
                    if s.priceCard.printings.count <= 1, let p = s.selected { fit(p.kind.rawValue, 15, .bold, Theme.gold) }
                    Spacer().frame(height: 4)
                    // sized for the final price, so it doesn't jump while counting up
                    Text(value.map { Money.eur($0) } ?? "–").font(.system(size: 46, weight: .black)).lineLimit(1).hidden()
                        .overlay(alignment: .leading) {
                            if value == nil {
                                Text("–").font(.system(size: 46, weight: .black)).foregroundStyle(.white)
                            } else {
                                CountUpText(value: counted, color: hit ? Theme.gold : .white)
                            }
                        }
                        .minimumScaleFactor(0.4)
                    // PSA prices are an add-on; the gem rate comes with every plan
                    if showPsa && psaPrices, let g = s.graded { PsaLine(g: g, big: true) }
                    if showPsa, let pop = s.population { GemLine(p: pop, big: true) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if s.priceCard.printings.count > 1 {
                PrintingChips(s: s, lock: model.printingLock, onPrinting: model.choosePrinting, onHold: model.togglePrintingLock).padding(.top, 8)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background { HitGlow(on: hit) }
        .overlay(RoundedRectangle(cornerRadius: 20).strokeBorder(hit ? Theme.gold : Color(red: 0x2A / 255, green: 0x2D / 255, blue: 0x34 / 255),
                                                                lineWidth: hit ? 3 : 1))
        .overlay { if hit { Burst(seed: s.serial) } }
        .overlay(alignment: .topTrailing) { if hit { HitBadge() } }
        .onAppear { countUp(to: value) }
        .onChange(of: value) { _, v in countUp(to: v) }
    }

    private func countUp(to v: Double?) {
        withAnimation(.timingCurve(0.4, 0, 0.2, 1, duration: Self.countUpSeconds)) { counted = v ?? 0 }
    }

    private func fit(_ t: String, _ size: CGFloat, _ weight: Font.Weight, _ color: Color, min: CGFloat = 10) -> some View {
        Text(t).font(.system(size: size, weight: weight)).foregroundStyle(color)
            .lineLimit(1).minimumScaleFactor(min / size)
    }
}

/// The price while it counts up from zero (the number is animated, not the text).
private struct CountUpText: View, Animatable {
    var value: Double
    let color: Color
    var animatableData: Double {
        get { value }
        set { value = newValue }
    }

    var body: some View {
        Text(Money.eur(value)).font(.system(size: 46, weight: .black)).foregroundStyle(color).lineLimit(1).minimumScaleFactor(0.4)
    }
}

/// Behind a HIT: a gold glow that pulses; else the plain panel.
private struct HitGlow: View {
    let on: Bool
    var body: some View {
        if on {
            TimelineView(.animation) { tl in
                let t = tl.date.timeIntervalSinceReferenceDate
                // 0.25 … 0.6, there and back in 1.4 s
                let a = 0.425 + 0.175 * sin(t * .pi / 0.7)
                GeometryReader { g in
                    RadialGradient(colors: [Theme.gold.opacity(a), .clear], center: .center, startRadius: 0,
                                   endRadius: min(g.size.width, g.size.height) / 2)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 20))
        } else {
            RoundedRectangle(cornerRadius: 20).fill(Theme.panel)
        }
    }
}

/// "HIT!", popping in.
private struct HitBadge: View {
    @State private var pop = false
    var body: some View {
        Text("HIT!").font(.system(size: 26, weight: .black)).foregroundStyle(Theme.ink)
            .padding(.horizontal, 12).padding(.vertical, 2)
            .background(Theme.gold, in: RoundedRectangle(cornerRadius: 10))
            .rotationEffect(.degrees(-12))
            .scaleEffect(pop ? 1 : 0.3)
            .onAppear { withAnimation(.spring(response: 0.55, dampingFraction: 0.4)) { pop = true } }
            .accessibilityLabel("Hit")
    }
}

/// The burst of a HIT: 48 sparks flying out from the card's picture, gone after 1.6 s.
private struct Burst: View {
    let seed: Int
    @State private var start = Date()
    @State private var done = false

    private struct Particle { let angle: Double, speed: Double, radius: Double; let color: Color }

    private var particles: [Particle] {
        var rnd = SplitMix(seed: UInt64(truncatingIfNeeded: seed))
        let colors = [Theme.gold, .white, Color(red: 1, green: 0xE0 / 255, blue: 0x8A / 255), Color(red: 1, green: 0xB3 / 255, blue: 0x47 / 255)]
        return (0..<48).map { i in
            Particle(angle: rnd.next() * 2 * .pi, speed: 0.35 + rnd.next() * 0.65, radius: 3 + rnd.next() * 6, color: colors[i % colors.count])
        }
    }

    var body: some View {
        let parts = particles
        TimelineView(.animation(minimumInterval: nil, paused: done)) { tl in
            Canvas { ctx, size in
                let t = tl.date.timeIntervalSince(start) / RevealCard.burstSeconds
                guard t < 1 else { return }
                // fast out, slow in
                let p = 1 - pow(1 - t, 3)
                let c = CGPoint(x: size.width * 0.3, y: size.height * 0.5)
                let reach = min(size.width, size.height) * 0.9
                for q in parts {
                    let d = reach * q.speed * p
                    let r = q.radius * (1 - 0.5 * p)
                    let at = CGPoint(x: c.x + cos(q.angle) * d, y: c.y + sin(q.angle) * d)
                    ctx.fill(Path(ellipseIn: CGRect(x: at.x - r, y: at.y - r, width: 2 * r, height: 2 * r)),
                             with: .color(q.color.opacity(max(0, 1 - p))))
                }
            }
        }
        .allowsHitTesting(false)
        .task { try? await Task.sleep(nanoseconds: UInt64(RevealCard.burstSeconds * 1_000_000_000)); done = true }
    }
}

/// A small seeded random number generator: the same burst for the same card.
struct SplitMix {
    private var state: UInt64
    init(seed: UInt64) { state = seed &+ 0x9E37_79B9_7F4A_7C15 }
    /// 0 ..< 1
    mutating func next() -> Double {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        z ^= z >> 31
        return Double(z >> 11) / Double(1 << 53)
    }
}

/**
 * Other versions of the same artwork (sets, languages): ‹ › step through them, the middle opens
 * the list, with the other candidate cards below it ("Not this card?") and the search. Drawn again
 * only when the card or its versions change, so the open list keeps its taps (as the ⚙ menu).
 */
private struct VersionSwitcher: View, Equatable {
    let s: Shown
    let onStep: (Int) -> Void
    let onVersion: (CardInfo) -> Void
    let onOther: (Candidate) -> Void
    let onSearch: () -> Void
    private let chip = Color(red: 0x2A / 255, green: 0x2D / 255, blue: 0x34 / 255)

    /// what the switcher shows; the actions don't count
    static func == (a: VersionSwitcher, b: VersionSwitcher) -> Bool {
        a.s.serial == b.s.serial && a.s.version.id == b.s.version.id && a.s.version.lang == b.s.version.lang
            && a.s.versionIndex == b.s.versionIndex && a.s.versions.map(\.versionKey) == b.s.versions.map(\.versionKey)
            && a.s.alternatives.map(\.id) == b.s.alternatives.map(\.id)
    }

    var body: some View {
        let n = s.versions.count
        if n >= 2 || !s.alternatives.isEmpty {
            HStack(spacing: 4) {
                if n > 1 { step("‹", -1, "Previous version") }
                Menu {
                    ForEach(s.versions, id: \.versionKey) { v in
                        let current = v.id == s.version.id && v.lang == s.version.lang
                        Button { if !current { onVersion(v) } } label: {
                            let t = "\(v.name) · \(v.setLine(withLang: false)) · \(languageNames[v.lang] ?? v.lang)  \(v.bestValue.map { Money.eur($0) } ?? "–")"
                            if current { Label(t, systemImage: "checkmark") } else { Text(t) }
                        }
                    }
                    if !s.alternatives.isEmpty {
                        Section("Not this card?") {
                            ForEach(s.alternatives) { c in
                                Button("\(c.info.name) · \(c.info.setLine(withLang: false))") { onOther(c) }
                            }
                        }
                    }
                    Button("🔍 Search for the right card…", action: onSearch)
                } label: {
                    Text(n > 1 ? "Version \(s.versionIndex + 1)/\(n) · \(s.version.lang.uppercased()) ▾" : "Other card ▾")
                        .font(.system(size: 14)).foregroundStyle(.white).lineLimit(1).fixedSize()
                        .padding(.horizontal, 10).padding(.vertical, 4)
                        .background(chip, in: RoundedRectangle(cornerRadius: 8))
                }
                .menuOrder(.fixed)
                if n > 1 { step("›", 1, "Next version") }
            }
            .padding(.top, 4)
        }
    }

    private func step(_ t: String, _ by: Int, _ label: String) -> some View {
        Button { onStep(by) } label: {
            Text(t).font(.system(size: 18, weight: .bold)).foregroundStyle(.white)
                .padding(.horizontal, 12).padding(.vertical, 1)
                .background(chip, in: RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// Every printing of the card with its price; tap to say which one is in front of the camera.
private struct PrintingChips: View {
    let s: Shown
    /// held for a second: kept for the next cards (the repeat symbol)
    let lock: Printing.Kind?
    let onPrinting: (Printing.Kind) -> Void
    let onHold: (Printing.Kind) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(s.priceCard.printings, id: \.kind) { p in
                    let sel = p.kind == s.selected?.kind
                    let repeats = lock == p.kind
                    Button { onPrinting(p.kind) } label: {
                        HStack(spacing: 5) {
                            if repeats { Image(systemName: "repeat").font(.system(size: 14, weight: .bold)) }
                            Text("\(p.kind.rawValue) \(p.priceText)")
                                .font(.system(size: 14, weight: sel ? .bold : .regular))
                                .lineLimit(1).fixedSize()
                        }
                        .foregroundStyle(sel ? Theme.ink : .white)
                        // 75 % taller than a chip (about 47 pt): easy to hit while holding a pack
                        .padding(.horizontal, 10).padding(.vertical, 15)
                        .background(sel ? Theme.gold : Color(red: 0x2A / 255, green: 0x2D / 255, blue: 0x34 / 255),
                                    in: RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(repeats ? Theme.gold : .clear, lineWidth: 2))
                    }
                    .buttonStyle(.plain)
                    .holdForASecond { onHold(p.kind) }
                    .accessibilityAddTraits(sel ? .isSelected : [])
                    .accessibilityHint("Hold for a second to keep this printing for the next cards")
                }
            }
        }
        .padding(.top, 4)
    }
}

/// The pack's value, ● REC, +1, ⚙ – and the last cards of the pack.
private struct SessionBar<Rec: View, MenuView: View>: View {
    @ObservedObject var model: ScannerModel
    @ViewBuilder var rec: Rec
    @ViewBuilder var menu: MenuView

    var body: some View {
        let threshold = model.settings.threshold
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                (Text("Pack value ").font(.system(size: 15)).foregroundColor(Theme.muted)
                 + Text(Money.eur(model.sessionValue)).font(.system(size: 22, weight: .bold)).foregroundColor(.white))
                    .lineLimit(1).minimumScaleFactor(0.5)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("packValue")
                rec
                // +1: the shown card once more (two of the same in a row)
                let can = model.shown != nil
                Button { model.addAgain() } label: {
                    Text("+1").font(.system(size: 17, weight: .black)).foregroundStyle(can ? Theme.gold : Theme.muted.opacity(0.4))
                        .frame(width: 44, height: 44)
                        .overlay(Circle().strokeBorder(can ? Theme.gold : Theme.muted.opacity(0.4), lineWidth: 2))
                }
                .buttonStyle(.plain)
                .disabled(!can)
                .padding(.trailing, 8)
                .accessibilityLabel("Add the card once more")
                menu
            }
            if !model.session.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(model.session.suffix(12).reversed()) { item in
                            let hit = isHit(item.value, threshold)
                            Text("\(item.name) \(Money.eur(item.value))").font(.system(size: 13)).foregroundStyle(hit ? Theme.ink : .white)
                                .lineLimit(1).fixedSize()
                                .padding(.horizontal, 10).padding(.vertical, 4)
                                .background(hit ? Theme.gold : Theme.panel, in: RoundedRectangle(cornerRadius: 10))
                        }
                    }
                }
                .padding(.top, 6)
            }
        }
        .padding(.top, 8)
    }
}
