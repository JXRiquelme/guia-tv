import Foundation

// MARK: - Live channels and guide

struct Channel: Identifiable, Hashable {
    let number: Int
    let name: String
    let tvgId: String
    let logo: String
    let url: String
    /// Other streams of the same channel (from #EXTALT lines or "(Op. N)" entries).
    var alts: [Alt] = []
    var id: Int { number }
    /// Main stream first, then the alternatives.
    var options: [Alt] { [Alt(label: "Opción 1", url: url)] + alts }
}

struct Alt: Hashable {
    let label: String
    let url: String
}

struct Program: Hashable {
    let start: Date
    let stop: Date
    let title: String
    let desc: String
}

enum Search {
    static func normalize(_ s: String) -> String {
        s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "es")).lowercased()
    }
}

enum Playlist {
    static func parse(_ text: String) -> [Channel] {
        var out: [Channel] = []
        var alts: [[Alt]] = []
        var target: Int? = nil
        var pending: String?
        let attrRegex = try! NSRegularExpression(pattern: "([\\w-]+)=\"([^\"]*)\"")
        let opRegex = try! NSRegularExpression(pattern: "\\s*\\((?:Op\\.?|Opci[oó]n)\\s*(\\d+)\\)\\s*$", options: .caseInsensitive)
        for raw in text.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { continue }
            if line.uppercased().hasPrefix("#EXTALT:") {
                // #EXTALT:Opción 2 (FHD)|http://… belongs to the channel above
                let body = String(line.dropFirst(8))
                if let t = target, let bar = body.lastIndex(of: "|") {
                    let u = body[body.index(after: bar)...].trimmingCharacters(in: .whitespaces)
                    if u.lowercased().hasPrefix("http") {
                        alts[t].append(Alt(label: body[..<bar].trimmingCharacters(in: .whitespaces), url: u))
                    }
                }
            } else if line.uppercased().hasPrefix("#EXTINF") {
                pending = line
            } else if !line.hasPrefix("#"), let info = pending {
                pending = nil
                guard line.lowercased().hasPrefix("http") else { continue }
                var attrs: [String: String] = [:]
                let ns = info as NSString
                for m in attrRegex.matches(in: info, range: NSRange(location: 0, length: ns.length)) {
                    attrs[ns.substring(with: m.range(at: 1)).lowercased()] = ns.substring(with: m.range(at: 2))
                }
                var name = displayName(info)
                if name.isEmpty { name = attrs["tvg-name"] ?? "Canal \(out.count + 1)" }
                // "DAZN LaLiga 1 (Op. 2)" is not listed: it becomes an option of "DAZN LaLiga 1"
                let nn = name as NSString
                if let m = opRegex.firstMatch(in: name, range: NSRange(location: 0, length: nn.length)) {
                    let base = nn.substring(to: m.range.location).trimmingCharacters(in: .whitespaces)
                    if let owner = out.firstIndex(where: { $0.name.caseInsensitiveCompare(base) == .orderedSame }) {
                        alts[owner].append(Alt(label: "Opción \(nn.substring(with: m.range(at: 1)))", url: line))
                        target = nil
                        continue
                    }
                }
                out.append(Channel(number: out.count + 1, name: name, tvgId: attrs["tvg-id"] ?? "",
                                   logo: attrs["tvg-logo"] ?? "", url: line))
                alts.append([])
                target = out.count - 1
            }
        }
        for i in out.indices where !alts[i].isEmpty {
            var seen: Set<String> = [out[i].url]
            var list: [Alt] = []
            for a in alts[i] where !seen.contains(a.url) {
                seen.insert(a.url)
                let tag = ["(FHD)", "(HD)", "(SD)", "(4K)"].first { a.label.uppercased().contains($0) }
                list.append(Alt(label: "Opción \(list.count + 2)" + (tag.map { " \($0)" } ?? ""), url: a.url))
            }
            out[i].alts = list
        }
        return out
    }

    private static func displayName(_ line: String) -> String {
        var quoted = false
        for (i, ch) in line.enumerated() {
            if ch == "\"" { quoted.toggle() }
            if ch == ",", !quoted { return String(line.dropFirst(i + 1)).trimmingCharacters(in: .whitespaces) }
        }
        return ""
    }
}

/// Streams the XMLTV file keeping only now-2h .. now+36h for the wanted channels.
final class GuideParser: NSObject, XMLParserDelegate {
    private let wanted: Set<String>
    private let from: Date
    private let to: Date
    private(set) var result: [String: [Program]] = [:]
    private var channel: String?
    private var start: Date?
    private var stop: Date?
    private var title = ""
    private var desc = ""
    private var text = ""
    private var keep = false
    private let formatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMddHHmmss Z"
        return f
    }()

    init(wanted: Set<String>, now: Date) {
        self.wanted = wanted
        from = now.addingTimeInterval(-2 * 3600)
        to = now.addingTimeInterval(36 * 3600)
    }

    static func parse(data: Data, wanted: Set<String>) -> [String: [Program]] {
        let delegate = GuideParser(wanted: wanted, now: Date())
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.parse()
        var map = delegate.result
        for (k, v) in map { map[k] = v.sorted { $0.start < $1.start } }
        return map
    }

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?,
                attributes: [String: String] = [:]) {
        text = ""
        if name == "programme" {
            channel = attributes["channel"]
            keep = channel.map { wanted.contains($0) } ?? false
            title = ""
            desc = ""
            if keep {
                start = attributes["start"].flatMap { formatter.date(from: $0) }
                stop = attributes["stop"].flatMap { formatter.date(from: $0) }
                if let s = start, let e = stop { keep = e > from && s < to } else { keep = false }
            }
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if keep { text += string }
    }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        guard keep else { return }
        switch name {
        case "title": if title.isEmpty { title = text.trimmingCharacters(in: .whitespacesAndNewlines) }
        case "desc": if desc.isEmpty { desc = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(400)) }
        case "programme":
            if let c = channel, let s = start, let e = stop {
                result[c, default: []].append(Program(start: s, stop: e, title: title.isEmpty ? "Sin título" : title, desc: desc))
            }
            keep = false
        default: break
        }
    }

    static func nowNext(_ list: [Program]?, at now: Date = Date()) -> (Program?, Program?) {
        guard let list = list, !list.isEmpty else { return (nil, nil) }
        if let i = list.firstIndex(where: { $0.start <= now && $0.stop > now }) {
            return (list[i], i + 1 < list.count ? list[i + 1] : nil)
        }
        return (nil, list.first(where: { $0.start > now }))
    }
}

// MARK: - Network

enum Net {
    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 30
        c.httpAdditionalHeaders = ["User-Agent": MPV.userAgent]
        return URLSession(configuration: c)
    }()

    static func get(_ url: String, completion: @escaping (Result<Data, Error>) -> Void) {
        guard let u = URL(string: url) else {
            completion(.failure(NSError(domain: "Lite", code: 1, userInfo: [NSLocalizedDescriptionKey: "Dirección inválida"])))
            return
        }
        session.dataTask(with: u) { data, response, error in
            let result: Result<Data, Error>
            if let error = error {
                result = .failure(error)
            } else if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                result = .failure(NSError(domain: "Lite", code: http.statusCode,
                                          userInfo: [NSLocalizedDescriptionKey: "El servidor respondió \(http.statusCode)"]))
            } else {
                result = .success(data ?? Data())
            }
            DispatchQueue.main.async { completion(result) }
        }.resume()
    }
}

// MARK: - Movies & series (Xtream)

struct XtreamAccount: Codable, Equatable {
    var base: String
    var user: String
    var pass: String

    var valid: Bool { base.hasPrefix("http") && !user.isEmpty && !pass.isEmpty }
    private var root: String { base.hasSuffix("/") ? String(base.dropLast()) : base }
    private func enc(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? s }

    func api(_ action: String, _ extra: [(String, String)] = []) -> String {
        var s = "\(root)/player_api.php?username=\(enc(user))&password=\(enc(pass))&action=\(action)"
        for (k, v) in extra { s += "&\(k)=\(enc(v))" }
        return s
    }

    func movieURL(_ id: String, _ ext: String) -> String { "\(root)/movie/\(enc(user))/\(enc(pass))/\(id).\(ext.isEmpty ? "mp4" : ext)" }
    func episodeURL(_ id: String, _ ext: String) -> String { "\(root)/series/\(enc(user))/\(enc(pass))/\(id).\(ext.isEmpty ? "mp4" : ext)" }

    /// Optional line in the M3U:  #XTREAM https://server:port usuario clave
    static func fromPlaylist(_ text: String) -> XtreamAccount? {
        for raw in text.components(separatedBy: .newlines).prefix(50) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            guard line.uppercased().hasPrefix("#XTREAM") else { continue }
            let parts = line.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
            if parts.count >= 4 {
                let acc = XtreamAccount(base: parts[1], user: parts[2], pass: parts[3])
                return acc.valid ? acc : nil
            }
        }
        return nil
    }
}

struct Category: Identifiable, Hashable {
    let id: String
    let name: String
}

struct VodItem: Codable, Identifiable, Hashable {
    let series: Bool
    let streamId: String
    let title: String
    let poster: String
    let ext: String
    let year: String
    let rating: String
    let categoryId: String
    var id: String { (series ? "s:" : "m:") + streamId }
    var searchKey: String { Search.normalize(title) }
}

struct Episode: Identifiable, Hashable {
    let id: String
    let season: Int
    let number: Int
    let title: String
    let ext: String
    let image: String
}

/// Something played on demand; also what the history stores.
struct Watch: Codable, Identifiable, Hashable {
    var episode: Bool
    var streamId: String
    var ext: String
    var title: String
    var subtitle: String
    var poster: String
    var seriesId: String = ""
    var position: Double = 0
    var duration: Double = 0
    var updated: Date = Date()
    var id: String { (episode ? "e:" : "m:") + streamId }
    var finished: Bool { duration > 0 && position >= duration * 0.95 }
}

enum Xtream {
    private static func str(_ v: Any?) -> String {
        switch v {
        case let s as String: return s.trimmingCharacters(in: .whitespaces)
        case let n as NSNumber: return n.stringValue
        default: return ""
        }
    }

    private static func json(_ data: Data) throws -> Any {
        do { return try JSONSerialization.jsonObject(with: data) } catch {
            throw NSError(domain: "Lite", code: 2, userInfo: [NSLocalizedDescriptionKey: "Respuesta inesperada del servidor"])
        }
    }

    private static func array(_ data: Data) throws -> [[String: Any]] {
        let obj = try json(data)
        guard let arr = obj as? [Any] else {
            throw NSError(domain: "Lite", code: 3, userInfo: [NSLocalizedDescriptionKey: "Usuario o clave Xtream incorrectos"])
        }
        return arr.compactMap { $0 as? [String: Any] }
    }

    static func categories(_ acc: XtreamAccount, series: Bool, completion: @escaping (Result<[Category], Error>) -> Void) {
        Net.get(acc.api(series ? "get_series_categories" : "get_vod_categories")) { r in
            completion(r.flatMap { data -> Result<[Category], Error> in
                Result { try array(data).compactMap { o -> Category? in
                    let id = str(o["category_id"])
                    if id.isEmpty { return nil }
                    let name = str(o["category_name"])
                    return Category(id: id, name: name.isEmpty ? id : name)
                } }
            })
        }
    }

    static func items(_ acc: XtreamAccount, series: Bool, category: String?, completion: @escaping (Result<[VodItem], Error>) -> Void) {
        let action = series ? "get_series" : "get_vod_streams"
        let url = category.map { acc.api(action, [("category_id", $0)]) } ?? acc.api(action)
        Net.get(url) { r in
            completion(r.flatMap { data -> Result<[VodItem], Error> in
                Result { try array(data).compactMap { o -> VodItem? in
                    let id = str(o[series ? "series_id" : "stream_id"])
                    if id.isEmpty { return nil }
                    let date = [o["releaseDate"], o["release_date"], o["releasedate"], o["year"]].map(str).first { !$0.isEmpty } ?? ""
                    let yearMatch = date.range(of: "(19|20)\\d\\d", options: .regularExpression).map { String(date[$0]) } ?? ""
                    var rating = str(o["rating"])
                    if rating == "0" || rating == "0.0" { rating = "" }
                    let ext = str(o["container_extension"])
                    return VodItem(series: series, streamId: id, title: str(o["name"]).isEmpty ? "Sin título" : str(o["name"]),
                                   poster: str(o[series ? "cover" : "stream_icon"]), ext: ext.isEmpty ? "mp4" : ext,
                                   year: yearMatch, rating: rating, categoryId: str(o["category_id"]))
                } }
            })
        }
    }

    static func episodes(_ acc: XtreamAccount, seriesId: String, completion: @escaping (Result<[Episode], Error>) -> Void) {
        Net.get(acc.api("get_series_info", [("series_id", seriesId)])) { r in
            completion(r.flatMap { data -> Result<[Episode], Error> in
                Result {
                    guard let root = try json(data) as? [String: Any] else { return [] }
                    var out: [Episode] = []
                    func add(_ season: Int, _ list: [Any]) {
                        for (i, el) in list.enumerated() {
                            guard let o = el as? [String: Any] else { continue }
                            let id = str(o["id"])
                            if id.isEmpty { continue }
                            let info = o["info"] as? [String: Any]
                            let num = Int(str(o["episode_num"])) ?? (i + 1)
                            let s = Int(str(o["season"])) ?? season
                            let title = str(o["title"])
                            let ext = str(o["container_extension"])
                            let img = str(info?["movie_image"]).isEmpty ? str(info?["cover_big"]) : str(info?["movie_image"])
                            out.append(Episode(id: id, season: s, number: num, title: title.isEmpty ? "Episodio \(num)" : title,
                                               ext: ext.isEmpty ? "mp4" : ext, image: img))
                        }
                    }
                    if let dict = root["episodes"] as? [String: Any] {
                        for (k, v) in dict { if let l = v as? [Any] { add(Int(k) ?? 0, l) } }
                    } else if let arr = root["episodes"] as? [Any] {
                        for (i, v) in arr.enumerated() { if let l = v as? [Any] { add(i + 1, l) } }
                    }
                    return out.sorted { ($0.season, $0.number) < ($1.season, $1.number) }
                }
            })
        }
    }
}

// MARK: - Local storage (only on this Mac)

enum Store {
    static let d = UserDefaults.standard

    static func load<T: Decodable>(_ key: String, _ type: T.Type) -> T? {
        guard let data = d.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    static func save<T: Encodable>(_ key: String, _ value: T) {
        if let data = try? JSONEncoder().encode(value) { d.set(data, forKey: key) }
    }

    static var cacheDir: URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("AerioLite")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
}
