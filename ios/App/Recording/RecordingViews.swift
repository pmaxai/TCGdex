import AVFoundation
import SwiftUI
import UIKit

let recRed = Color(red: 0xE5 / 255, green: 0x48 / 255, blue: 0x4D / 255)

/**
 * ● REC beside +1 and ⚙: opens the start sheet (or Settings › Recording while uploading isn't set
 * up). While recording it is gone from the bar (nothing covers the pack value, nothing of it is in
 * the video): RecIndicator shows the time outside the 16:9 area.
 */
struct RecButton: View {
    @ObservedObject var rec: RecordingModel
    @EnvironmentObject var account: AccountModel
    let onStart: () -> Void

    var body: some View {
        // recording is part of the Creator plan (the account's "recording" feature)
        if !rec.recording && account.has("recording") {
            Button(action: onStart) {
                Text("● REC").font(.system(size: 15, weight: .black)).foregroundStyle(recRed)
                    .padding(.horizontal, 12).frame(height: 44)
                    .overlay(Capsule().strokeBorder(recRed, lineWidth: 2))
            }
            .buttonStyle(.plain)
            .padding(.trailing, 8)
            .accessibilityLabel("Start recording")
        }
    }
}

/**
 * While recording: a red dot and the time (a tap stops) in the black border below the 16:9 area.
 * It is drawn in a layer that screen recordings leave out, so the video has black there.
 */
struct RecIndicator: View {
    @ObservedObject var rec: RecordingModel

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.25)) { tl in
            let (label, dotOn) = text(tl.date)
            Button { if case .recording = rec.phase { rec.stop() } } label: {
                (Text("●").foregroundColor(dotOn ? recRed : recRed.opacity(0.35)) + Text("  " + label).foregroundColor(.white))
                    .font(.system(size: 13, weight: .bold).monospacedDigit())
                    .padding(.horizontal, 10).padding(.vertical, 4)
                    .background(Theme.panel, in: RoundedRectangle(cornerRadius: 14))
                    .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(recRed, lineWidth: 1.5))
            }
            .buttonStyle(.plain)
        }
        .hiddenFromScreenCapture()
        .accessibilityLabel("Stop recording")
    }

    private func text(_ now: Date) -> (String, Bool) {
        switch rec.phase {
        case .idle: return ("", false)
        case .starting: return ("start", true)
        case .stopping: return ("saving", true)
        case .recording(let since):
            let secs = max(0, Int(now.timeIntervalSince(since)))
            let t = secs >= 3600 ? String(format: "%d:%02d:%02d", secs / 3600, secs / 60 % 60, secs % 60) : String(format: "%d:%02d", secs / 60, secs % 60)
            return (t, Int(now.timeIntervalSince1970 / 0.6) % 2 == 0)
        }
    }
}

extension View {
    /// Shown on the phone, left out of screenshots and screen recordings (the layer of a secure text field).
    func hiddenFromScreenCapture() -> some View { CaptureHidden { self } }
}

/// Puts its content into the layer iOS keeps out of screen captures (as a password field's text).
private struct CaptureHidden<Content: View>: UIViewRepresentable {
    @ViewBuilder var content: Content

    final class Holder: UIView {
        let field = UITextField()
        let host = UIHostingController<AnyView>(rootView: AnyView(EmptyView()))
        override init(frame: CGRect) {
            super.init(frame: frame)
            field.isSecureTextEntry = true
            field.isUserInteractionEnabled = false
            host.view.backgroundColor = .clear
            // the secure field's own canvas: what's in it isn't captured; without it, shown as it is
            let canvas = (field.layer.sublayers?.first?.delegate as? UIView) ?? field.subviews.first
            if let canvas {
                canvas.subviews.forEach { $0.removeFromSuperview() }
                canvas.isUserInteractionEnabled = true
                addSubview(canvas)
                canvas.addSubview(host.view)
            } else {
                addSubview(host.view)
            }
        }
        required init?(coder: NSCoder) { fatalError() }
        override func layoutSubviews() {
            super.layoutSubviews()
            host.view.superview?.frame = bounds
            host.view.frame = bounds
        }
        override var intrinsicContentSize: CGSize { host.view.intrinsicContentSize }
    }

    func makeUIView(context: Context) -> Holder { let h = Holder(); h.host.rootView = AnyView(content); return h }
    func updateUIView(_ h: Holder, context: Context) { h.host.rootView = AnyView(content); h.invalidateIntrinsicContentSize() }
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: Holder, context: Context) -> CGSize? {
        uiView.host.sizeThatFits(in: CGSize(width: proposal.width ?? 400, height: proposal.height ?? 100))
    }
}

/// Before a recording: continue the pack or start a new one, the microphone, the space it needs; then iOS asks to record the screen.
struct StartRecordingSheet: View {
    @ObservedObject var rec: RecordingModel
    @ObservedObject var scanner: ScannerModel
    @Environment(\.dismiss) private var dismiss
    @State private var newPack = false
    @State private var mic = false
    @State private var micDenied = false

    var body: some View {
        NavigationStack {
            Form {
                if !scanner.session.isEmpty {
                    Section("The pack has \(scanner.session.count) cards") {
                        Picker("Pack", selection: $newPack) {
                            Text("Continue pack (\(scanner.session.count))").tag(false)
                            Text("New pack").tag(true)
                        }
                        .pickerStyle(.segmented)
                    }
                }
                Section {
                    Toggle(isOn: Binding(get: { mic }, set: { on in
                        if !on { mic = false; return }
                        AVAudioApplication.requestRecordPermission { ok in DispatchQueue.main.async { mic = ok; micDenied = !ok } }
                    })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Microphone")
                            Text(mic ? "your voice is in the video" : "no sound (the pipeline can add a voiceover)").font(.caption).foregroundStyle(Theme.muted)
                        }
                    }
                    if micDenied { Text("The microphone isn't allowed for the app (Settings › Privacy › Microphone).").font(.caption).foregroundStyle(Theme.warn) }
                } footer: {
                    let minutes = rec.minutesLeft()
                    VStack(alignment: .leading, spacing: 6) {
                        Text(minutes < 60 ? "⚠ Space for about \(minutes) minutes at these settings (an hour needs \(RecordingDir.sizeText(rec.bytesPerHour)))"
                             : "Space for more than an hour")
                            .foregroundStyle(minutes < 60 ? Theme.warn : Theme.muted)
                        Text("Portrait. iOS now asks to record the screen: choose Record Screen. Leaving the app stops the recording.")
                    }
                }
            }
            .navigationTitle("Start recording")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("● Start") { dismiss(); rec.start(newPack: newPack, mic: mic) }.disabled(rec.minutesLeft() <= 0)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear { mic = rec.settings.mic && AVAudioApplication.shared.recordPermission == .granted }
    }
}

/// ⚙ → Recordings…: every recording with its status; Submit or Save (not sent yet), Retry, Upload now, Share, Delete.
struct RecordingsSheet: View {
    @ObservedObject var rec: RecordingModel
    @ObservedObject var account: AccountModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirm: RecInfo?
    @State private var askUploadNow: RecInfo?
    @State private var submitting: RecInfo?
    @State private var share: ShareFile?

    var body: some View {
        NavigationStack {
            List {
                if let s = rec.lastSummary { Text(s).font(.footnote).foregroundStyle(Theme.gold) }
                if rec.recordings.isEmpty {
                    Text("No recordings yet. ● REC in YouTube Mode starts one.").foregroundStyle(Theme.muted)
                }
                ForEach(rec.recordings) { r in row(r) }
            }
            .navigationTitle("Recordings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { dismiss() } } }
            .task { while !Task.isCancelled { rec.refresh(); try? await Task.sleep(nanoseconds: 2_000_000_000) } }
            .alert("Delete this recording?", isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }), presenting: confirm) { r in
                Button("Delete", role: .destructive) { rec.delete(r.id) }
                Button("Keep", role: .cancel) {}
            } message: { r in
                Text(r.status == .SAVED ? "It leaves this list; its videos in Photos stay."
                     : "It is deleted on this phone; an upload in progress stops (files already in the bucket stay).")
            }
            .alert("Upload now?", isPresented: Binding(get: { askUploadNow != nil }, set: { if !$0 { askUploadNow = nil } }), presenting: askUploadNow) { r in
                Button("Upload") { rec.uploadNow(r.id) }
                Button("Cancel", role: .cancel) {}
            } message: { r in Text("\(RecordingDir.sizeText(r.bytes)) – also over mobile data, this one time.") }
            .sheet(item: $submitting) { r in
                SubmitView(rec: rec, id: r.id, title: rec.settings.uploadS3 ? "Submit recording" : "Save recording",
                           line: "\(r.date) · \(r.line)" + (r.status == .SAVED && rec.settings.uploadS3 ? " · the videos come from Photos" : ""),
                           onDone: { submitting = nil }, onLeave: { submitting = nil })
            }
            .sheet(item: $share) { f in ShareSheet(url: f.url) { _ in share = nil } }
        }
    }

    private func row(_ r: RecInfo) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(r.date).font(.headline)
            Text(r.line).font(.caption).foregroundStyle(Theme.muted)
            Text(r.statusText).font(.subheadline).foregroundStyle(statusColor(r.status))
            HStack(spacing: 14) {
                // not sent yet: the submit screen first (templates and inputs; Save into Photos with S3 off)
                if r.status == .LOCAL && !r.busy { action(rec.settings.uploadS3 ? "Submit…" : "Save…") { submitting = r } }
                // saved in Photos while Upload to S3 was off: it can still go up (its videos come back from Photos)
                if r.status == .SAVED && rec.settings.uploadS3 && !r.busy { action("Submit…") { submitting = r } }
                if r.status == .FAILED { action("Retry") { rec.retry(r.id) } }
                if r.status == .QUEUED || r.status == .FAILED { action("Upload now") { askUploadNow = r } }
                if r.status != .RECORDING, let u = rec.shareURL(r.id) { action("Share") { share = ShareFile(url: u) } }
                if r.status != .RECORDING && !r.busy { action("Delete", color: Theme.warn) { confirm = r } }
            }
            .padding(.top, 2)
        }
        .padding(.vertical, 4)
    }

    private func action(_ t: String, color: Color = Theme.gold, _ go: @escaping () -> Void) -> some View {
        Button(t, action: go).font(.subheadline).foregroundStyle(color).buttonStyle(.borderless)
    }

    private func statusColor(_ s: RecStatus) -> Color {
        switch s {
        case .UPLOADED, .SAVED: return Theme.sure
        case .FAILED: return Theme.warn
        case .UPLOADING: return Theme.gold
        default: return .white
        }
    }
}

/// After Stop (or a recording recovered after the app stopped): the submit screen; leaving keeps it on the phone, unsubmitted.
struct RecordingDoneSheet: View {
    @ObservedObject var rec: RecordingModel
    let recording: RecInfo
    @ObservedObject var account: AccountModel

    var body: some View {
        SubmitView(rec: rec, id: recording.id, title: "Recording finished", line: rec.toDecideText,
                   onDone: {}, onLeave: { rec.later(recording.id) })
    }
}

/**
 * Submit or delete a recording (R1): the video templates to make (R3) and their inputs (R4), from
 * the templates list (R2). Submit writes submission.json and queues the upload; Delete asks once
 * more; Later: the recording stays on the phone, unsubmitted. With uploading to S3 off, Save puts
 * the videos into Photos instead. preset…: given by tests instead of loading.
 */
struct SubmitView: View {
    @ObservedObject var rec: RecordingModel
    let id: String
    let title: String
    let line: String?
    let onDone: () -> Void
    let onLeave: () -> Void
    var preset: LoadedTemplates? = nil
    var presetPack: [PackCard]? = nil
    var presetMic: Bool? = nil
    @State private var loaded: LoadedTemplates?
    @State private var pack: [PackCard]?
    @State private var mic = false
    /// nil until the person ticks: the list's default templates (R3)
    @State private var ticked: Set<String>?
    @State private var values: [String: FieldValue] = [:]
    @State private var open: String?
    @State private var confirmDelete = false
    @State private var sent = false

    private var local: Bool { !rec.settings.uploadS3 }

    var body: some View {
        let l = loaded ?? preset
        let chosen = ticked ?? Set(l?.index.templates.filter(\.isDefault).map(\.id) ?? [])
        let fields = l?.index.fields(for: chosen) ?? []
        let problems = fields.compactMap { SubmissionForm.problem($0, value($0)) }
        NavigationStack {
            Form {
                if let line { Section { Text(line).font(.subheadline).foregroundStyle(Theme.muted) } }
                if local {
                    Section {
                        Text("Save puts the video into Photos on this phone (and the camera video, if recorded). Uploading to S3 is off (Settings › Recording).")
                    }
                } else {
                    Section {
                        if let l {
                            ForEach(l.index.templates) { t in
                                let on = chosen.contains(t.id)
                                Button { ticked = on ? chosen.subtracting([t.id]) : chosen.union([t.id]) } label: {
                                    HStack(alignment: .top, spacing: 10) {
                                        Image(systemName: on ? "checkmark.square.fill" : "square").foregroundStyle(on ? Theme.gold : Theme.muted)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(t.name).font(.headline).foregroundStyle(.white)
                                            if !t.description.isEmpty { Text(t.description).font(.caption).foregroundStyle(Theme.muted) }
                                        }
                                    }
                                }
                                .accessibilityAddTraits(on ? .isSelected : [])
                            }
                        } else {
                            HStack(spacing: 10) { ProgressView(); Text("Loading the templates…").foregroundStyle(Theme.muted) }
                        }
                    } header: { Text("Videos to make") } footer: {
                        VStack(alignment: .leading, spacing: 4) {
                            if let l, let note = sourceNote(l) { Text(note.0).foregroundStyle(note.1 ? Theme.warn : Theme.muted) }
                            if l != nil && chosen.isEmpty { Text("Tick at least one.").foregroundStyle(Theme.warn) }
                        }
                    }
                    if !fields.isEmpty {
                        Section {
                            ForEach(fields, id: \.id) { f in field(f, fields) }
                        } header: { Text("Details (all optional)") } footer: {
                            if rec.settings.wifiOnly { Text("It goes up on Wi-Fi.") }
                        }
                    } else if rec.settings.wifiOnly {
                        Section { } footer: { Text("It goes up on Wi-Fi.") }
                    }
                }
                Section {
                    Button("Delete", role: .destructive) { confirmDelete = true }.disabled(sent)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Later", action: onLeave).disabled(sent) }
                ToolbarItem(placement: .confirmationAction) {
                    if local {
                        Button("Save") { sent = true; rec.saveToPhotos(id); onDone() }.disabled(sent)
                    } else {
                        Button("Submit") {
                            guard let l else { return }
                            sent = true
                            let order = l.index.templates.filter { chosen.contains($0.id) }.map(\.id)
                            rec.submit(id, templates: order, inputs: SubmissionForm.inputs(fields, current(fields)))
                            onDone()
                        }
                        .disabled(l == nil || chosen.isEmpty || !problems.isEmpty || sent)
                    }
                }
            }
            .alert("Delete this recording?", isPresented: $confirmDelete) {
                Button("Delete", role: .destructive) { sent = true; rec.discard(id); onDone() }
                Button("Keep", role: .cancel) {}
            } message: {
                Text(local ? "Its video and files are deleted on this phone. Nothing goes into Photos." : "Its video and files are deleted on this phone. Nothing is uploaded.")
            }
        }
        .task(id: id) {
            guard !local else { return }
            if preset == nil && loaded == nil { loaded = await rec.loadTemplates() }
            if pack == nil { pack = presetPack ?? rec.packCards(id) }
            mic = presetMic ?? rec.hasMicSound(id)
        }
    }

    private func value(_ f: TemplateInput) -> FieldValue? { values[f.id] ?? SubmissionForm.initial(f) }

    private func current(_ fields: [TemplateInput]) -> [String: FieldValue] {
        var out: [String: FieldValue] = [:]
        for f in fields { if let v = value(f) { out[f.id] = v } }
        return out
    }

    @ViewBuilder private func field(_ f: TemplateInput, _ fields: [TemplateInput]) -> some View {
        switch f.type {
        case "text":
            let max = f.max.map { Int($0) }
            let text = value(f)?.string ?? ""
            VStack(alignment: .leading, spacing: 2) {
                TextField(f.label, text: Binding(get: { text }, set: { v in values[f.id] = .text(max.map { String(v.prefix($0)) } ?? v) }),
                          axis: (max ?? Int.max) > 120 ? .vertical : .horizontal)
                let hint = [f.help, max.map { "\(text.count)/\($0)" }].compactMap { $0 }.joined(separator: " · ")
                if !hint.isEmpty { Text(hint).font(.caption).foregroundStyle(Theme.muted) }
            }
        case "number":
            let text = value(f)?.string ?? ""
            let problem = SubmissionForm.problem(f, value(f))
            VStack(alignment: .leading, spacing: 2) {
                TextField(f.label, text: Binding(get: { text }, set: { values[f.id] = .text($0) }),
                          prompt: f.defaultValue?.string.map { Text($0) })
                    .keyboardType(.decimalPad)
                if let hint = problem ?? SubmissionForm.range(f) { Text(hint).font(.caption).foregroundStyle(problem != nil ? Theme.warn : Theme.muted) }
            }
        case "toggle":
            Toggle(f.label, isOn: Binding(get: { if case .bool(let b)? = value(f) { return b }; return false }, set: { values[f.id] = .bool($0) }))
        case "choice":
            // "own" voice: only with the microphone's sound in the recording
            let options = f.options.filter { !(f.id == "voice" && $0 == "own" && !mic) }
            Picker(f.label, selection: Binding(get: { value(f)?.string ?? "" }, set: { values[f.id] = .text($0) })) {
                if value(f) == nil { Text("–").tag("") }
                ForEach(options, id: \.self) { Text($0).tag($0) }
            }
        case "cards":
            cards(f, fields)
        default:
            EmptyView()
        }
    }

    /// A `cards` input: the recording's pack, each card ticked or not; a card ticked here leaves the other cards inputs.
    @ViewBuilder private func cards(_ f: TemplateInput, _ fields: [TemplateInput]) -> some View {
        let mine: [Int] = { if case .cards(let l)? = value(f) { return l }; return [] }()
        DisclosureGroup(isExpanded: Binding(get: { open == f.id }, set: { open = $0 ? f.id : nil })) {
            let list = pack ?? []
            if list.isEmpty { Text("No cards in this recording's pack.").font(.caption).foregroundStyle(Theme.muted) }
            ForEach(list) { c in
                let on = mine.contains(c.entry)
                let elsewhere = fields.first { g in
                    g.type == "cards" && g.id != f.id && { if case .cards(let l)? = value(g) { return l.contains(c.entry) }; return false }()
                }
                Button {
                    values = SubmissionForm.tickCard(fields, current(fields), fieldId: f.id, entry: c.entry, on: !on)
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: on ? "checkmark.square.fill" : "square").foregroundStyle(on ? Theme.gold : Theme.muted)
                        VStack(alignment: .leading, spacing: 1) {
                            Text((c.hit ? "★ " : "") + c.name).foregroundStyle(.white).lineLimit(1)
                            Text(["\(c.set) \(c.number)".trimmingCharacters(in: .whitespaces), price(c), elsewhere?.label ?? ""]
                                .filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(Theme.muted).lineLimit(1)
                        }
                    }
                }
            }
        } label: {
            HStack {
                Text(f.label)
                Spacer()
                Text(mine.isEmpty ? "none" : "\(mine.count) selected").font(.footnote).foregroundStyle(mine.isEmpty ? Theme.muted : Theme.gold)
            }
        }
    }

    private func price(_ c: PackCard) -> String {
        guard let v = c.value else { return "" }
        return c.currency == "USD" ? String(format: "$%.2f", v) : Money.eur(v)
    }

    /// Where the list came from, when it isn't the bucket's: (text, a warning).
    private func sourceNote(_ l: LoadedTemplates) -> (String, Bool)? {
        let why = l.error.map { $0 == "offline" ? "offline" : "the template list couldn't be loaded: \($0)" } ?? "the template list couldn't be loaded"
        let cap = why.prefix(1).uppercased() + why.dropFirst()
        switch l.source {
        case .bucket: return nil
        case .saved: return ("\(cap) – the list saved \(l.savedAt.map { $0.formatted(date: .abbreviated, time: .omitted) } ?? "earlier").", false)
        case .builtIn: return ("\(cap) – only the default video for now.", true)
        }
    }
}

/// Settings › YouTube Mode: the title line; the alert price sets off the HIT.
struct YouTubeSettingsView: View {
    @ObservedObject var model: ScannerModel

    var body: some View {
        Form {
            Section {
                TextField("e.g. Surging Sparks booster box", text: $model.settings.title)
            } header: { Text("Title line") } footer: {
                Text("Shown at the top of the picture in YouTube Mode (optional).")
            }
            Section {
                Toggle("YouTube Mode", isOn: $model.settings.youtube)
            } footer: {
                Text("Full screen for unboxing and price-check videos: an animated price reveal for every card, a HIT at your alert price (Settings › Prices), the pack's value. Hold Scanner in the bottom bar to switch modes.")
            }
        }
    }
}

/// Settings › Recording: how YouTube Mode records, and where the recordings go.
struct RecordingSettingsView: View {
    @ObservedObject var rec: RecordingModel
    @ObservedObject var account: AccountModel
    @State private var micDenied = false

    var body: some View {
        let s = rec.settings
        Form {
            Section {
                Text("YouTube Mode records its own screen with ● REC and logs every card with its time in the video. The video is saved in Photos on this phone, or, with Upload to S3, uploaded with the card log to your storage by itself.")
                    .font(.footnote).foregroundStyle(Theme.muted)
            }
            Section("Recording") {
                Picker("Video quality", selection: bind(\.hd)) { Text("720p").tag(false); Text("1080p").tag(true) }
                Picker("Bitrate", selection: bind(\.bitrateMbps)) { ForEach([6, 8, 12], id: \.self) { Text("\($0) Mbit/s").tag($0) } }
                toggle("Record camera too", "a clean camera.mp4 without overlays, for tight crops and re-rendered overlays", \.recordCamera)
                toggle("Fit to 16:9", "lays YouTube Mode out in a 9:16 area while recording, black around it", \.fit169)
                Toggle(isOn: Binding(get: { s.mic }, set: { on in
                    if !on { set(\.mic, false); return }
                    AVAudioApplication.requestRecordPermission { ok in DispatchQueue.main.async { if ok { set(\.mic, true) }; micDenied = !ok } }
                })) { labeled("Microphone", "your voice in the video (off: the pipeline can add a voiceover)") }
                if micDenied { Text("The microphone isn't allowed for the app (Settings › Privacy › Microphone).").font(.caption).foregroundStyle(Theme.warn) }
                toggle("Show timer", "the red dot and the time below the 16:9 area while recording, never in the video", \.showTimer)
            }
            Section("Where recordings go") {
                if !account.has("s3_upload") {
                    Text("Recordings are saved in Photos on this phone. Uploading to S3 for the video pipeline is a service of the Creator plan, switched on by Overpriced Cardboard.")
                        .font(.footnote).foregroundStyle(Theme.muted)
                } else if !account.has("pipeline") {
                    Text("Your S3 storage is set up, but uploads go with Automatic Content Processing, which isn't on for your account yet: recordings are saved in Photos.")
                        .font(.footnote).foregroundStyle(Theme.muted)
                } else {
                    // off (the default): saved on this phone; the storage stays for when it is on again
                    toggle("Upload to S3", s.uploadS3 ? "to your storage, with the card log, for the video pipeline" : "off: Save puts the videos into Photos", \.uploadS3)
                }
            }
            if s.uploadS3 && account.has("s3_upload") && account.has("pipeline") { managed(s) }
        }
    }

    /// The account's storage: set up by Overpriced Cardboard (GET /services/s3), shown, not edited.
    @ViewBuilder private func managed(_ s: RecordingSettings) -> some View {
        Section {
            if let note = account.s3Note { Text("⚠ \(note)").font(.footnote).foregroundStyle(Theme.warn) }
            if s.managed {
                LabeledContent("Provider", value: s.provider.label)
                LabeledContent("Endpoint", value: s.endpoint)
                LabeledContent("Bucket", value: s.bucket)
                LabeledContent("Path prefix", value: s.prefix.isEmpty ? "–" : s.prefix)
                LabeledContent("Access key ID", value: s.accessKeyId)
                LabeledContent("Secret access key", value: s.hasSecret ? "in the Keychain" : "–")
                Button(rec.testing ? "Testing…" : "Test the connection") { rec.testConnection() }.disabled(rec.testing)
                if let c = rec.connection {
                    Text((rec.connectionOk == true ? "✓ " : "⚠ ") + c).font(.footnote).foregroundStyle(rec.connectionOk == true ? Theme.sure : Theme.warn)
                }
            } else if account.s3Note == nil {
                Text("Reading the storage settings…").font(.footnote).foregroundStyle(Theme.muted)
            }
        } header: { Text("Upload (S3)") } footer: { Text("Set up by Overpriced Cardboard") }
        Section {
            toggle("Only on Wi-Fi", "Upload now (in Recordings…) sends one over mobile data", \.wifiOnly)
            toggle("Delete local copy after upload", "only once the upload is complete and checked", \.deleteAfterUpload)
        }
    }

    private func set<T>(_ k: WritableKeyPath<RecordingSettings, T>, _ v: T) {
        var s = rec.settings
        s[keyPath: k] = v
        rec.saveSettings(s)
    }

    private func bind<T>(_ k: WritableKeyPath<RecordingSettings, T>) -> Binding<T> {
        Binding(get: { rec.settings[keyPath: k] }, set: { set(k, $0) })
    }

    private func toggle(_ title: String, _ about: String, _ k: WritableKeyPath<RecordingSettings, Bool>) -> some View {
        Toggle(isOn: bind(k)) { labeled(title, about) }
    }

    private func labeled(_ title: String, _ about: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            Text(about).font(.caption).foregroundStyle(Theme.muted)
        }
    }
}
