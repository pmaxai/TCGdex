import Foundation
import SwiftUI

/// The app's data: the data pack (loaded once, off the main thread), its catalog and the scanner.
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

    init() {
        Task { await load() }
    }

    func load() async {
        state = .loading
        do {
            let (p, c, e) = try await Task.detached(priority: .userInitiated) { () throws -> (Pack, Catalog, ScannerEngine) in
                guard let dir = Pack.bundledDir else {
                    throw NSError(domain: "Pack", code: 1, userInfo: [NSLocalizedDescriptionKey: "The card data is missing from the app."])
                }
                let pack = try Pack(dir: dir)
                return (pack, Catalog(db: pack.db), try ScannerEngine(pack: pack))
            }.value
            pack = p; catalog = c; engine = e
            scanner = ScannerModel(engine: e, catalog: c)
            state = .ready
        } catch {
            state = .failed(error.localizedDescription)
        }
    }
}
