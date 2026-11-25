import Foundation
import SwiftUI

/// The app's data: the data pack (loaded once, off the main thread), its catalog and the scanner; the account and the App Store.
@MainActor
final class AppModel: ObservableObject {
    enum State {
        case loading
        case ready
        case failed(String)
    }

    @Published private(set) var state: State = .loading
    private(set) var pack: Pack?
    private(set) var catalog: Catalog?
    private(set) var engine: ScannerEngine?
    private(set) var scanner: ScannerModel?
    /// Webshop Mode: the connected shop, the shown card in it, Stock in and Sell
    private(set) var webshop: WebshopModel?
    let account: AccountModel
    let store: StoreModel
    let trading = TradingModel()
    /// YouTube Mode's recordings (● REC), their uploads and the Recordings list
    let recording: RecordingModel

    init(account: AccountModel? = nil, billing: AppStoreBilling? = nil) {
        let account = account ?? AccountModel()
        self.account = account
        store = StoreModel(account: account, billing: billing ?? StoreKitBilling())
        recording = RecordingModel()
        let rec = recording
        rec.uploadAllowed = { [weak account] in account.map { $0.has("s3_upload") && $0.has("pipeline") } ?? false }
        account.onS3 = { [weak rec] o in rec?.applyManagedS3(o) }
        // a new answer may switch uploading on or off (the storage and Automatic Content Processing),
        // and bring the webshop connected to the account (its host fills in an empty connection)
        account.onStatus = { [weak rec, weak self, weak account] st in
            rec?.reload()
            self?.webshop?.applyAccount(has: account?.has("webshop") ?? false, host: st?.webshopHost)
        }
        account.onConnected = { [weak self] in self?.store.start() }
        account.onAffiliate = { [weak self] result, aff in self?.store.signedInWithCode(result, aff) }
        account.start()
        store.start()
        Task { await load() }
    }

    func load() async {
        state = .loading
        do {
            let (p, c, e) = try await Task.detached(priority: .userInitiated) { () throws -> (Pack, Catalog, ScannerEngine) in
                // a newer pack downloaded meanwhile takes over now (at the start, before anything is open)
                guard let dir = PackUpdate.choose(bundled: Pack.bundledDir) else {
                    throw NSError(domain: "Pack", code: 1, userInfo: [NSLocalizedDescriptionKey: "The card data is missing from the app."])
                }
                let pack = try Pack(dir: dir)
                return (pack, Catalog(db: pack.db), try ScannerEngine(pack: pack))
            }.value
            pack = p; catalog = c; engine = e
            let sc = ScannerModel(engine: e, catalog: c)
            scanner = sc
            let shop = WebshopModel(scanner: sc)
            shop.applyAccount(has: account.has("webshop"), host: account.status?.webshopHost)
            webshop = shop
            recording.packInfo = { [weak self] in (self?.pack?.version, self?.catalog?.pricesDate.map { String($0.prefix(10)) }) }
            recording.attach(sc)
            state = .ready
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    /// back in the app: the account check (after an hour), and the nightly prices
    func onForeground() {
        account.onForeground()
        Task.detached { try? await PackUpdate.runOnce() }
    }

    /// a link into the app: a creator code (overpricedcardboard.com/r/CODE) goes with the first sign-in
    func open(_ url: URL) {
        guard let code = Affiliate.fromLink(url) else { return }
        if account.store.token == nil { account.store.pendingAffiliate = code } else { store.applyCode(code) }
    }
}
