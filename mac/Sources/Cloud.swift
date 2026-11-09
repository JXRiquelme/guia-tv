import Cocoa
import AuthenticationServices

/// Google account + Supabase: "Seguir viendo" (history), "Ver más tarde" and the option chosen for
/// each channel, the same on the TV, the phone and this Mac.
///
/// Cloud row (public.user_data), shared with the Android apps:
///   history: [{episode,id,ext,title,subtitle,poster,seriesId,position ms,duration ms,updated epoch ms}]
///   later: [{series,id,title,poster,ext,year,rating,cat}]   channel_options: {channel name: option index}
final class Cloud: NSObject, ObservableObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = Cloud()

    @Published private(set) var email: String? = UserDefaults.standard.string(forKey: "cloud_email")
    @Published var busy = false
    var signedIn: Bool { UserDefaults.standard.string(forKey: "cloud_refresh") != nil }
    var configured: Bool { !CloudConfig.url.isEmpty && !CloudConfig.key.isEmpty }

    /// Called on the main thread after another device's copy replaced the local lists.
    var onApplied: (() -> Void)?

    private let d = UserDefaults.standard
    private let queue = DispatchQueue(label: "cloud")
    private var dirty: Bool { get { d.bool(forKey: "cloud_dirty") } set { d.set(newValue, forKey: "cloud_dirty") } }
    private var pushTimer: Timer?
    private var authSession: ASWebAuthenticationSession?

    override init() {
        super.init()
        NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.refresh()
        }
    }

    // MARK: Sign in / out

    func signIn(done: @escaping (String?) -> Void) {
        guard configured else { done("La sincronización no está configurada."); return }
        var c = URLComponents(string: CloudConfig.url + "/auth/v1/authorize")!
        c.queryItems = [URLQueryItem(name: "provider", value: "google"), URLQueryItem(name: "redirect_to", value: "aeriolite://login")]
        let session = ASWebAuthenticationSession(url: c.url!, callbackURLScheme: "aeriolite") { [weak self] url, error in
            guard let self = self else { return }
            if let e = error as? ASWebAuthenticationSessionError, e.code == .canceledLogin { done(nil); return }
            guard let url = url, let fragment = url.fragment ?? url.query else { done("Google no entregó una cuenta."); return }
            var p: [String: String] = [:]
            for pair in fragment.split(separator: "&") {
                let kv = pair.split(separator: "=", maxSplits: 1).map { String($0).removingPercentEncoding ?? String($0) }
                if kv.count == 2 { p[kv[0]] = kv[1] }
            }
            guard let access = p["access_token"], let refresh = p["refresh_token"] else {
                done(p["error_description"].map { "No se pudo iniciar sesión: \($0)" } ?? "No se pudo iniciar sesión."); return
            }
            self.saveSession(access: access, refresh: refresh, expiresIn: Double(p["expires_in"] ?? "") ?? 3600)
            self.busy = true
            self.queue.async {
                let err: String?
                do {
                    let user = try self.json(self.request("/auth/v1/user", method: "GET", auth: true)) as? [String: Any]
                    self.d.set(user?["id"] as? String, forKey: "cloud_user")
                    let mail = user?["email"] as? String
                    self.d.set(mail, forKey: "cloud_email")
                    DispatchQueue.main.async { self.email = mail }
                    try self.firstSync()
                    err = nil
                } catch { err = "No se pudo iniciar sesión: \(error.localizedDescription)" }
                DispatchQueue.main.async { self.busy = false; done(err) }
            }
        }
        session.presentationContextProvider = self
        session.prefersEphemeralWebBrowserSession = false
        authSession = session
        session.start()
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        NSApp.keyWindow ?? NSApp.windows.first ?? ASPresentationAnchor()
    }

    func signOut() {
        for k in ["cloud_access", "cloud_refresh", "cloud_expires", "cloud_email", "cloud_user", "cloud_stamp", "cloud_dirty"] { d.removeObject(forKey: k) }
        email = nil
    }

    // MARK: Sync

    /// A list or a channel option changed here: send it a moment later.
    func markDirty() {
        guard signedIn, configured else { return }
        dirty = true
        pushTimer?.invalidate()
        pushTimer = Timer.scheduledTimer(withTimeInterval: 4, repeats: false) { [weak self] _ in
            self?.queue.async { try? self?.push() }
        }
    }

    func refresh() {
        guard signedIn, configured else { return }
        queue.async { [weak self] in try? self?.pullOrPush() }
    }

    func syncNow(done: @escaping (String?) -> Void) {
        queue.async { [weak self] in
            var err: String?
            do { try self?.pullOrPush() } catch { err = error.localizedDescription }
            DispatchQueue.main.async { done(err) }
        }
    }

    private func pullOrPush() throws {
        let remote = try fetch()
        let stamp = d.string(forKey: "cloud_stamp") ?? ""
        guard let r = remote else { try push(); return }
        let changed = (r["updated_at"] as? String ?? "") != stamp
        if changed && !dirty { apply(r) }
        else if changed && dirty { apply(merge(r)); try push() }
        else if dirty { try push() }
    }

    private func firstSync() throws {
        if let r = try fetch() { apply(merge(r)) }
        try push()
    }

    private func fetch() throws -> [String: Any]? {
        let a = try json(request("/rest/v1/user_data?select=history,later,channel_options,updated_at", method: "GET", auth: true)) as? [[String: Any]]
        return a?.first
    }

    private func push() throws {
        guard let user = d.string(forKey: "cloud_user") else { return }
        var doc = local()
        doc["user_id"] = user
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        doc["updated_at"] = f.string(from: Date())
        let body = try JSONSerialization.data(withJSONObject: [doc])
        let saved = try json(request("/rest/v1/user_data?on_conflict=user_id", method: "POST", auth: true, body: body,
                                     prefer: "resolution=merge-duplicates,return=representation")) as? [[String: Any]]
        d.set(saved?.first?["updated_at"] as? String ?? doc["updated_at"] as? String, forKey: "cloud_stamp")
        dirty = false
    }

    // MARK: Local <-> cloud format

    private func local() -> [String: Any] {
        var history: [[String: Any]] = []
        for w in Store.load("history", [Watch].self) ?? [] {
            var o: [String: Any] = [:]
            o["episode"] = w.episode
            o["id"] = w.streamId
            o["ext"] = w.ext
            o["title"] = w.title
            o["subtitle"] = w.subtitle
            o["poster"] = w.poster
            o["seriesId"] = w.seriesId
            o["position"] = Int64(w.position * 1000)
            o["duration"] = Int64(w.duration * 1000)
            o["updated"] = Int64(w.updated.timeIntervalSince1970 * 1000)
            history.append(o)
        }
        var later: [[String: Any]] = []
        for v in Store.load("later", [VodItem].self) ?? [] {
            var o: [String: Any] = [:]
            o["series"] = v.series
            o["id"] = v.streamId
            o["title"] = v.title
            o["poster"] = v.poster
            o["ext"] = v.ext
            o["year"] = v.year
            o["rating"] = v.rating
            o["cat"] = v.categoryId
            later.append(o)
        }
        var options: [String: Int] = [:]
        for (k, v) in d.dictionaryRepresentation() where k.hasPrefix("alt_") {
            if let n = v as? Int { options[String(k.dropFirst(4))] = n }
        }
        return ["history": history, "later": later, "channel_options": options]
    }

    private func merge(_ remote: [String: Any]) -> [String: Any] {
        let mine = local()
        var byKey: [String: [String: Any]] = [:]
        var order: [String] = []
        func add(_ list: Any?) {
            for o in (list as? [[String: Any]]) ?? [] {
                let k = ((o["episode"] as? Bool ?? false) ? "e:" : "m:") + "\(o["id"] ?? "")"
                let new = (o["updated"] as? NSNumber)?.int64Value ?? 0
                if let old = byKey[k], ((old["updated"] as? NSNumber)?.int64Value ?? 0) >= new { continue }
                if byKey[k] == nil { order.append(k) }
                byKey[k] = o
            }
        }
        add(remote["history"]); add(mine["history"])
        let history = order.compactMap { byKey[$0] }
            .sorted { (($0["updated"] as? NSNumber)?.int64Value ?? 0) > (($1["updated"] as? NSNumber)?.int64Value ?? 0) }
            .prefix(60)
        var later: [[String: Any]] = []
        var seen = Set<String>()
        for list in [remote["later"], mine["later"]] {
            for o in (list as? [[String: Any]]) ?? [] where seen.insert("\(o["series"] ?? false)\(o["id"] ?? "")").inserted { later.append(o) }
        }
        var options = (mine["channel_options"] as? [String: Any]) ?? [:]
        for (k, v) in (remote["channel_options"] as? [String: Any]) ?? [:] { options[k] = v }
        return ["history": Array(history), "later": later, "channel_options": options, "updated_at": remote["updated_at"] ?? ""]
    }

    private func apply(_ doc: [String: Any]) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            func str(_ o: [String: Any], _ k: String) -> String {
                if let s = o[k] as? String { return s }
                if let n = o[k] as? NSNumber { return n.stringValue }
                return ""
            }
            func num(_ o: [String: Any], _ k: String) -> Double { (o[k] as? NSNumber)?.doubleValue ?? 0 }
            let history = ((doc["history"] as? [[String: Any]]) ?? []).map { o in
                Watch(episode: o["episode"] as? Bool ?? false, streamId: str(o, "id"), ext: str(o, "ext").isEmpty ? "mp4" : str(o, "ext"),
                      title: str(o, "title"), subtitle: str(o, "subtitle"), poster: str(o, "poster"), seriesId: str(o, "seriesId"),
                      position: num(o, "position") / 1000, duration: num(o, "duration") / 1000,
                      updated: Date(timeIntervalSince1970: num(o, "updated") / 1000))
            }
            let later = ((doc["later"] as? [[String: Any]]) ?? []).map { o in
                VodItem(series: o["series"] as? Bool ?? false, streamId: str(o, "id"), title: str(o, "title"), poster: str(o, "poster"),
                        ext: str(o, "ext"), year: str(o, "year"), rating: str(o, "rating"), categoryId: str(o, "cat"))
            }
            Store.save("history", history, sync: false)
            Store.save("later", later, sync: false)
            for k in self.d.dictionaryRepresentation().keys where k.hasPrefix("alt_") { self.d.removeObject(forKey: k) }
            for (k, v) in (doc["channel_options"] as? [String: Any]) ?? [:] {
                if let n = (v as? NSNumber)?.intValue { self.d.set(n, forKey: "alt_" + k) }
            }
            if let s = doc["updated_at"] as? String, !s.isEmpty { self.d.set(s, forKey: "cloud_stamp") }
            self.onApplied?()
        }
    }

    // MARK: Supabase REST

    private func saveSession(access: String, refresh: String, expiresIn: Double) {
        d.set(access, forKey: "cloud_access")
        d.set(refresh, forKey: "cloud_refresh")
        d.set(Date().timeIntervalSince1970 + expiresIn, forKey: "cloud_expires")
    }

    private func token() throws -> String {
        guard let refresh = d.string(forKey: "cloud_refresh") else { throw CloudError("Sin sesión") }
        if Date().timeIntervalSince1970 < d.double(forKey: "cloud_expires") - 60, let a = d.string(forKey: "cloud_access") { return a }
        let body = try JSONSerialization.data(withJSONObject: ["refresh_token": refresh])
        let r = try json(request("/auth/v1/token?grant_type=refresh_token", method: "POST", auth: false, body: body)) as? [String: Any]
        guard let access = r?["access_token"] as? String, let newRefresh = r?["refresh_token"] as? String else { throw CloudError("Sesión vencida") }
        saveSession(access: access, refresh: newRefresh, expiresIn: (r?["expires_in"] as? NSNumber)?.doubleValue ?? 3600)
        return access
    }

    /// Synchronous request (always called from the cloud queue).
    private func request(_ path: String, method: String, auth: Bool, body: Data? = nil, prefer: String? = nil) throws -> Data {
        var req = URLRequest(url: URL(string: CloudConfig.url + path)!, timeoutInterval: 20)
        req.httpMethod = method
        req.setValue(CloudConfig.key, forHTTPHeaderField: "apikey")
        req.setValue("Bearer " + (auth ? try token() : CloudConfig.key), forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let prefer = prefer { req.setValue(prefer, forHTTPHeaderField: "Prefer") }
        req.httpBody = body
        let sem = DispatchSemaphore(value: 0)
        var out: (Data?, URLResponse?, Error?) = (nil, nil, nil)
        URLSession.shared.dataTask(with: req) { a, b, c in out = (a, b, c); sem.signal() }.resume()
        sem.wait()
        if let e = out.2 { throw e }
        let code = (out.1 as? HTTPURLResponse)?.statusCode ?? 0
        let data = out.0 ?? Data()
        guard (200..<300).contains(code) else {
            let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            let msg = (o?["msg"] ?? o?["message"] ?? o?["error_description"]) as? String
            throw CloudError(msg ?? "HTTP \(code)")
        }
        return data
    }

    private func json(_ data: Data) throws -> Any {
        data.isEmpty ? [Any]() : try JSONSerialization.jsonObject(with: data)
    }
}

struct CloudError: LocalizedError {
    let message: String
    init(_ m: String) { message = m }
    var errorDescription: String? { message }
}
