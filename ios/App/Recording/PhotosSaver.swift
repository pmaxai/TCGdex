import AVFoundation
import Photos

/**
 * A recording's videos into Photos (the Android app's GallerySaver): the screen video rewritten as
 * a plain MP4 (seekable everywhere), the camera video as it is. Their Photos ids are kept, so a
 * recording saved there can still be uploaded later (its videos are read back from Photos).
 */
enum PhotosSaver {
    struct Failure: Error, LocalizedError { let text: String; var errorDescription: String? { text } }

    /// Adding to Photos is allowed (asks the first time).
    static func mayAdd() async -> Bool {
        let s = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        return s == .authorized || s == .limited
    }

    /// The screen video as a plain MP4 next to it (the recorded one is written in fragments).
    static func plainCopy(_ src: URL) async throws -> URL {
        let out = src.deletingLastPathComponent().appendingPathComponent("photos-" + src.lastPathComponent)
        try? FileManager.default.removeItem(at: out)
        let asset = AVURLAsset(url: src)
        guard let ex = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetPassthrough) else {
            throw Failure(text: "the video can't be rewritten")
        }
        ex.outputURL = out
        ex.outputFileType = .mp4
        ex.shouldOptimizeForNetworkUse = true
        await ex.export()
        guard ex.status == .completed else { throw Failure(text: ex.error?.localizedDescription ?? "the video can't be rewritten") }
        return out
    }

    /// Moves the files into Photos, each as its own video; their Photos ids, in order.
    static func save(_ files: [URL], taken: Date) async throws -> [String] {
        final class Ids: @unchecked Sendable { var list: [String] = [] }
        let ids = Ids()
        try await PHPhotoLibrary.shared().performChanges {
            for f in files {
                let r = PHAssetCreationRequest.forAsset()
                let o = PHAssetResourceCreationOptions()
                o.shouldMoveFile = true
                r.addResource(with: .video, fileURL: f, options: o)
                r.creationDate = taken
                if let id = r.placeholderForCreatedAsset?.localIdentifier { ids.list.append(id) }
            }
        }
        return ids.list
    }

    /// For an upload of a recording saved in Photos: its videos are read back (asks for access to the library).
    static func restore(ids: [String], video: URL, camera: URL?) async throws {
        let s = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        guard s == .authorized || s == .limited else { throw Failure(text: "reading the videos back needs access to Photos") }
        let found = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
        var assets: [String: PHAsset] = [:]
        found.enumerateObjects { a, _, _ in assets[a.localIdentifier] = a }
        let targets: [URL] = [video] + (camera.map { [$0] } ?? [])
        for (i, target) in targets.enumerated() {
            guard i < ids.count, let a = assets[ids[i]] else {
                if i == 0 { throw Failure(text: "the video is no longer in Photos") }
                continue
            }
            guard let res = PHAssetResource.assetResources(for: a).first(where: { $0.type == .video || $0.type == .fullSizeVideo }) else {
                throw Failure(text: "the video is no longer in Photos")
            }
            try? FileManager.default.removeItem(at: target)
            let o = PHAssetResourceRequestOptions()
            o.isNetworkAccessAllowed = true
            try await PHAssetResourceManager.default().writeData(for: res, toFile: target, options: o)
        }
    }
}
