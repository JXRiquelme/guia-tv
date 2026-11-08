import Foundation

/// A movie or episode saved on this Mac (~/Movies/AerioTV Lite).
struct DownloadRecord: Codable, Identifiable, Hashable {
    var watch: Watch
    var file: String
    var done: Bool
    var id: String { watch.id }
}

final class DownloadCenter: NSObject, ObservableObject, URLSessionDownloadDelegate {
    static let shared = DownloadCenter()

    @Published var records: [DownloadRecord] = Store.load("downloads", [DownloadRecord].self) ?? []
    /// 0…1 for the ones downloading now
    @Published var progress: [String: Double] = [:]
    @Published var failed: Set<String> = []
    private var tasks: [Int: String] = [:]
    private var running: [String: URLSessionDownloadTask] = [:]
    private lazy var session = URLSession(configuration: .default, delegate: self, delegateQueue: .main)

    static var folder: URL {
        let dir = FileManager.default.urls(for: .moviesDirectory, in: .userDomainMask)[0].appendingPathComponent("AerioTV Lite")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    override init() {
        super.init()
        // downloads cut by quitting the app can't continue: show them as failed
        for r in records where !r.done { failed.insert(r.id) }
    }

    private func save() { Store.save("downloads", records) }

    func record(_ id: String) -> DownloadRecord? { records.first { $0.id == id } }

    func localURL(_ id: String) -> URL? {
        guard let r = record(id), r.done else { return nil }
        let url = Self.folder.appendingPathComponent(r.file)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    func list(series: Bool) -> [DownloadRecord] { records.filter { $0.watch.episode == series } }

    func start(_ w: Watch, url: String) {
        guard record(w.id) == nil, let u = URL(string: url) else { return }
        var req = URLRequest(url: u)
        req.setValue(MPV.userAgent, forHTTPHeaderField: "User-Agent")
        let task = session.downloadTask(with: req)
        tasks[task.taskIdentifier] = w.id
        running[w.id] = task
        var copy = w
        copy.position = 0
        copy.duration = 0
        let name = w.id.replacingOccurrences(of: ":", with: "_") + "." + (w.ext.isEmpty ? "mp4" : w.ext)
        records.insert(DownloadRecord(watch: copy, file: name, done: false), at: 0)
        progress[w.id] = 0
        failed.remove(w.id)
        save()
        task.resume()
    }

    func remove(_ id: String) {
        running[id]?.cancel()
        running[id] = nil
        if let r = record(id) { try? FileManager.default.removeItem(at: Self.folder.appendingPathComponent(r.file)) }
        records.removeAll { $0.id == id }
        progress[id] = nil
        failed.remove(id)
        save()
    }

    // MARK: URLSessionDownloadDelegate

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let id = tasks[downloadTask.taskIdentifier], totalBytesExpectedToWrite > 0 else { return }
        progress[id] = Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let id = tasks[downloadTask.taskIdentifier], let i = records.firstIndex(where: { $0.id == id }) else { return }
        if let http = downloadTask.response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            failed.insert(id); return
        }
        let dest = Self.folder.appendingPathComponent(records[i].file)
        try? FileManager.default.removeItem(at: dest)
        do {
            try FileManager.default.moveItem(at: location, to: dest)
            records[i].done = true
            progress[id] = nil
            save()
        } catch {
            failed.insert(id)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let id = tasks.removeValue(forKey: task.taskIdentifier) else { return }
        running[id] = nil
        if let e = error as NSError?, e.code != NSURLErrorCancelled { failed.insert(id); progress[id] = nil }
    }
}
