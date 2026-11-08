import Cocoa
import SwiftUI
import AVFoundation

enum GridEntry: Identifiable, Hashable {
    case item(VodItem)
    case watch(Watch)
    case download(DownloadRecord)
    var id: String {
        switch self {
        case .item(let v): return "i" + v.id
        case .watch(let w): return "w" + w.id
        case .download(let d): return "d" + d.id
        }
    }
}

enum GridMode { case category, continueAll, laterAll, downloadsAll, search }
enum Tab: Int { case live = 0, movies = 1, series = 2 }

/// All app state and behaviour. Lives on the main thread.
final class AppModel: ObservableObject {
    static let guideURL = "https://raw.githubusercontent.com/JXRiquelme/guia-tv/main/guia.xml"
    static let startChannel = "Canal 13"
    static let defaultServer = "https://reborned.cc:8443"

    let mpv = MPV.shared
    private let d = UserDefaults.standard

    // Live
    @Published var channels: [Channel] = []
    @Published var current = 0
    @Published var guide: [String: [Program]] = [:]
    @Published var status: String?
    @Published var shutter = true
    @Published var showInfo = false
    @Published var showPanel = false
    @Published var panelQuery = ""
    @Published var panelSelection = 0
    @Published var showEPG = false
    /// 0 = panel fully open; negative = pushed out to the left (follows the fingers).
    @Published var panelOffset: CGFloat = 0
    /// 0 = guide fully open; positive = pushed out to the right.
    @Published var epgOffset: CGFloat = 0
    static let panelWidth: CGFloat = 400
    var epgWidth: CGFloat { (NSApp.windows.first { $0.isVisible }?.contentView?.bounds.width ?? 1280) * 0.72 }
    @Published var epgRow = 0
    @Published var epgCursor = Date()
    @Published var epgWindowStart = Date()
    @Published var hiddenChannels: Set<String>
    @Published var now = Date()

    // Setup
    @Published var showSetup = false
    @Published var setupMessage = ""
    @Published var listURL: String
    @Published var showAccount = false
    @Published var accountMessage = ""
    var playlistAccount: XtreamAccount?

    // Touch / trackpad: vertical channel drag
    @Published var dragY: CGFloat = 0
    @Published var dragTarget: Int?
    @Published var cardChannel: Int?
    @Published var showTopButtons = false
    /// (i): resolution · fps over the picture
    @Published var showStats = UserDefaults.standard.bool(forKey: "show_stats") {
        didSet { UserDefaults.standard.set(showStats, forKey: "show_stats"); updateStats() }
    }
    @Published var statsText = ""
    @Published var volume: Double = 100 { didSet { mpv.setProperty("volume", String(Int(volume))) } }
    /// Option tried automatically when the chosen one stops working (not saved).
    @Published var altOverride: Int?
    @Published var showSettings = false
    @Published var audioLang = UserDefaults.standard.string(forKey: "audio_lang") ?? "es" {
        didSet { UserDefaults.standard.set(audioLang, forKey: "audio_lang"); applyLanguages() }
    }
    @Published var subLang = UserDefaults.standard.string(forKey: "sub_lang") ?? "off" {
        didSet { UserDefaults.standard.set(subLang, forKey: "sub_lang"); applyLanguages() }
    }
    /// Bumped when the chosen option of a channel changes, so views refresh.
    @Published var altVersion = 0
    @Published var mini = false
    @Published var airplayActive = false
    @Published var airplayNotice = false
    private var savedFrame: NSRect?
    private var scrollMonitor: Any?
    private var gesture = (dx: CGFloat(0), dy: CGFloat(0), axis: 0) // axis 0 undecided, 1 horizontal, 2 vertical
    var airplayPlayer: AVPlayer?
    private var airplayObserver: NSKeyValueObservation?

    // Movies / series playback
    @Published var vod: Watch?
    @Published var showVodBar = false
    @Published var aspect = "no"

    // Browser
    @Published var browserOpen = false
    @Published var browserSeries = false
    @Published var categories: [Bool: [Category]] = [:]
    @Published var selectedCat: [Bool: String] = [:]
    @Published var entries: [GridEntry] = []
    @Published var gridLabel = ""
    @Published var gridMessage: String?
    @Published var gridMode = GridMode.category
    @Published var topMode = 0
    @Published var searchOpen = false
    @Published var searchText = ""
    @Published var openSeries: VodItem?
    @Published var episodes: [Episode] = []
    @Published var season = 0
    @Published var seriesInfo = ""
    @Published var hiddenMovieCats: Set<String>
    @Published var hiddenSeriesCats: Set<String>
    @Published var filterKind: Tab?
    @Published var history: [Watch]
    @Published var later: [VodItem]

    private var itemCache: [String: [VodItem]] = [:]
    private var catalogCache: [Bool: [VodItem]] = [:]
    private var loadToken = 0
    private var playToken = 0
    private var infoTimer: Timer?
    private var vodBarTimer: Timer?
    private var zapTimer: Timer?
    private var saveTimer: Timer?
    private var clockTimer: Timer?
    private var keyMonitor: Any?
    private var searchDebounce: Timer?

    init() {
        listURL = UserDefaults.standard.string(forKey: "list") ?? ""
        hiddenChannels = Set(UserDefaults.standard.stringArray(forKey: "hide_ch") ?? [])
        hiddenMovieCats = Set(UserDefaults.standard.stringArray(forKey: "hide_vod") ?? [])
        hiddenSeriesCats = Set(UserDefaults.standard.stringArray(forKey: "hide_series") ?? [])
        history = Store.load("history", [Watch].self) ?? []
        later = Store.load("later", [VodItem].self) ?? []

        mpv.onReady = { [weak self] in self?.playbackReady() }
        mpv.onFirstFrame = { [weak self] in self?.shutter = false }
        mpv.onEndFile = { [weak self] error, eof in self?.playbackEnded(error: error, eof: eof) }

        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] e in
            guard let self = self else { return e }
            return self.handleKey(e) ? nil : e
        }
        scrollMonitor = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self] e in
            guard let self = self else { return e }
            return self.handleScroll(e) ? nil : e
        }
        clockTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            self.now = Date()
            if let f = self.guideFile, let age = try? f.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate,
               Date().timeIntervalSince(age) > 3 * 3600 { self.refreshGuide(force: true) }
        }
        if listURL.isEmpty { openSetup(nil) } else { start() }
    }

    // MARK: - Live data

    private var listFile: URL { Store.cacheDir.appendingPathComponent("list.m3u") }
    private var guideFile: URL? { Store.cacheDir.appendingPathComponent("guia.xml") }

    func start() {
        showSetup = false
        let text = (try? String(contentsOf: listFile, encoding: .utf8)) ?? ""
        let cached = Playlist.parse(text)
        playlistAccount = XtreamAccount.fromPlaylist(text)
        if !cached.isEmpty {
            channels = cached
            play(startIndex())
            refreshGuide(force: false)
            refreshList(silent: true)
        } else {
            status = "Cargando canales…"
            refreshList(silent: false)
        }
    }

    private func startIndex() -> Int {
        // Reopens on the last channel watched (its chosen option is remembered per channel).
        if let last = UserDefaults.standard.string(forKey: "last_channel"),
           let i = channels.firstIndex(where: { $0.name == last }) { return i }
        if let i = channels.firstIndex(where: { $0.name.caseInsensitiveCompare(Self.startChannel) == .orderedSame }) { return i }
        if let i = channels.firstIndex(where: { $0.name.localizedCaseInsensitiveContains(Self.startChannel) }) { return i }
        return 0
    }

    private func refreshList(silent: Bool) {
        Net.get(listURL) { [weak self] r in
            guard let self = self else { return }
            switch r {
            case .failure(let e):
                if !silent { self.openSetup("No se pudo descargar la lista (\(e.localizedDescription)).") }
            case .success(let data):
                let text = String(decoding: data, as: UTF8.self)
                let list = Playlist.parse(text)
                self.playlistAccount = XtreamAccount.fromPlaylist(text)
                if list.isEmpty {
                    if !silent { self.openSetup("La dirección no contiene una lista M3U válida.") }
                    return
                }
                try? data.write(to: self.listFile)
                if list == self.channels { return }
                let playing = self.channels.indices.contains(self.current) ? self.channels[self.current] : nil
                self.channels = list
                self.refreshGuide(force: false)
                if let p = playing, let same = list.firstIndex(where: { $0.url == p.url || $0.name == p.name }) {
                    self.current = same
                } else if self.vod == nil && !self.browserOpen {
                    self.status = nil
                    self.play(self.startIndex())
                } else {
                    self.current = self.startIndex()
                }
            }
        }
    }

    func refreshGuide(force: Bool) {
        let wanted = Set(channels.map { $0.tvgId }.filter { !$0.isEmpty })
        guard !wanted.isEmpty, let file = guideFile else { return }
        let age = (try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate).map { Date().timeIntervalSince($0) } ?? .infinity
        let parse: (Data) -> Void = { [weak self] data in
            DispatchQueue.global(qos: .utility).async {
                let map = GuideParser.parse(data: data, wanted: wanted)
                DispatchQueue.main.async { self?.guide = map }
            }
        }
        if force || age > 3 * 3600 {
            Net.get(Self.guideURL) { r in
                if case .success(let data) = r, !data.isEmpty {
                    try? data.write(to: file)
                    parse(data)
                } else if let data = try? Data(contentsOf: file) {
                    parse(data)
                }
            }
        } else if let data = try? Data(contentsOf: file) {
            parse(data)
        }
    }

    var visibleChannels: [Channel] {
        let q = Search.normalize(panelQuery.trimmingCharacters(in: .whitespaces))
        return channels.filter { !hiddenChannels.contains($0.name) && (q.isEmpty || Search.normalize($0.name).contains(q)) }
    }

    var currentChannel: Channel? { channels.indices.contains(current) ? channels[current] : nil }

    func programs(_ ch: Channel) -> [Program]? { guide[ch.tvgId] }

    // MARK: - Live playback

    func play(_ index: Int) {
        guard !channels.isEmpty else { return }
        zapTimer?.invalidate()
        saveVodProgress()
        vod = nil
        showVodBar = false
        shutter = true
        aspect = "no"
        mpv.setAspect("no")
        current = max(0, min(index, channels.count - 1))
        recoverAttempts = 0
        stallSince = nil
        altOverride = nil
        playToken += 1
        status = nil
        applyLanguages()
        UserDefaults.standard.set(channels[current].name, forKey: "last_channel")
        mpv.load(liveURL())
        startHealth()
        flashInfo()
    }

    // MARK: - Channel options (Opción 2, 3…)

    func altIndex(_ ch: Channel) -> Int {
        max(0, min(ch.alts.count, UserDefaults.standard.integer(forKey: "alt_" + ch.name)))
    }

    func streamURL(_ ch: Channel) -> String { ch.options[altIndex(ch)].url }

    func chooseOption(_ i: Int) {
        guard vod == nil, let ch = currentChannel, ch.options.indices.contains(i) else { return }
        if i != altIndex(ch) {
            UserDefaults.standard.set(i, forKey: "alt_" + ch.name)
            altVersion += 1
            play(current)
        } else { flashInfo(withButtons: true) }
    }

    /// "O" key: next option of the current channel.
    func nextOption() {
        guard let ch = currentChannel, !ch.alts.isEmpty else { return }
        chooseOption((altIndex(ch) + 1) % ch.options.count)
    }

    // MARK: - Settings

    func applyLanguages() { mpv.applyLanguages(audio: audioLang, subtitles: subLang) }

    func liveURL() -> String {
        guard let ch = currentChannel else { return "" }
        if let o = altOverride, ch.options.indices.contains(o) { return ch.options[o].url }
        return streamURL(ch)
    }

    // MARK: - Self-healing playback
    // Checked every second: if the picture is stuck (buffering / not moving) for a few seconds,
    // the stream is reopened quietly; every 3rd try a live channel moves to its next option.
    // It never gives up while you stay, and only says something if it takes long.

    private var healthTimer: Timer?
    private var stallSince: Date?
    private var lastPos: Double = -1
    private var lastCache: Double = -2
    private var recoverAttempts = 0
    private var recovering = false

    private func startHealth() {
        lastPos = -1
        guard healthTimer == nil else { return }
        healthTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.healthTick() }
    }

    private func healthTick() {
        updateStats()
        guard !airplayActive, !recovering, !showSetup, vod != nil || currentChannel != nil, !mpv.paused, !browserOpen || vod != nil else {
            stallSince = nil; return
        }
        let pos = mpv.position
        let moving = abs(pos - lastPos) > 0.05
        lastPos = pos
        if moving && !mpv.buffering { stallSince = nil; return }
        let now = Date()
        if stallSince == nil { stallSince = now }
        // movies: keep waiting while data still arrives (opening far into a film can be slow)
        let cache = Double(mpv.getString("demuxer-cache-time") ?? "") ?? -1
        if vod != nil && cache != lastCache { lastCache = cache; stallSince = nil; return }
        let limit: Double = vod != nil ? (recoverAttempts == 0 ? 25 : 15) : (recoverAttempts == 0 ? 10 : 6)
        if now.timeIntervalSince(stallSince!) >= limit { recover() }
    }

    private func recover() {
        recoverAttempts += 1
        stallSince = Date()
        recovering = true
        let token = playToken
        if let w = vod, let acc = xtream() {
            let pos = mpv.position > 1 ? mpv.position : w.position
            if recoverAttempts >= 2 { status = "Recuperando la conexión…" }
            mpv.stop()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                guard let self = self, token == self.playToken else { return }
                self.recovering = false
                self.mpv.load(w.episode ? acc.episodeURL(w.streamId, w.ext) : acc.movieURL(w.streamId, w.ext), start: pos)
            }
            return
        }
        guard let ch = currentChannel else { recovering = false; return }
        if recoverAttempts % 3 == 0 && !ch.alts.isEmpty {
            altOverride = ((altOverride ?? altIndex(ch)) + 1) % ch.options.count
        }
        if recoverAttempts >= 3 * ch.options.count + 3 {
            status = "Sin señal en \(ch.name).\nSigue intentando… o prueba otro canal."
        } else if recoverAttempts >= 3 {
            status = "Recuperando la señal…"
        }
        mpv.stop()   // frees the (single) connection
        DispatchQueue.main.asyncAfter(deadline: .now() + (recoverAttempts > 6 ? 4 : 0.8)) { [weak self] in
            guard let self = self, token == self.playToken, self.vod == nil else { return }
            self.recovering = false
            self.mpv.load(self.liveURL())
        }
    }

    func updateStats() {
        guard showStats else { if !statsText.isEmpty { statsText = "" }; return }
        let w = Int(mpv.getString("video-params/w") ?? "") ?? 0
        let h = Int(mpv.getString("video-params/h") ?? "") ?? 0
        guard w > 0, h > 0 else { statsText = "Sin video"; return }
        let fps = Double(mpv.getString("estimated-vf-fps") ?? "") ?? Double(mpv.getString("container-fps") ?? "") ?? 0
        let label = h >= 2000 ? "4K" : h >= 1000 ? "1080p" : h >= 700 ? "720p" : "\(h)p"
        statsText = "\(label)  ·  \(w)×\(h)" + (fps > 0 ? "  ·  \(Int(fps.rounded())) fps" : "")
    }

    private func playbackReady() {
        recoverAttempts = 0
        stallSince = nil
        status = nil
        shutter = false
        if cardChannel != nil { withAnimation(.easeOut(duration: 0.25)) { cardChannel = nil } }
    }

    private func playbackEnded(error: Bool, eof: Bool) {
        if let w = vod {
            if eof {
                var done = w
                done.position = mpv.duration > 0 ? mpv.duration : w.duration
                done.duration = done.position
                recordHistory(done)
                if w.episode, let s = openSeries, let i = episodes.firstIndex(where: { $0.id == w.streamId }), i + 1 < episodes.count {
                    playVod(episodeWatch(s, episodes[i + 1]))
                } else {
                    leaveVod()
                }
            } else if error {
                if !recovering { recover() }
            }
        } else if (error || eof) && !recovering {
            recover()
        }
    }

    private func neighbor(_ step: Int) -> Int {
        var order = channels.indices.filter { !hiddenChannels.contains(channels[$0].name) }
        if order.isEmpty { order = Array(channels.indices) }
        guard let pos = order.firstIndex(of: current) else { return order.first(where: { $0 > current }) ?? order[0] }
        return order[(pos + step + order.count) % order.count]
    }

    /// ↑↓ zapping: banner follows each press, the stream opens when presses stop.
    func zap(_ step: Int) {
        guard !channels.isEmpty, vod == nil else { return }
        current = neighbor(step)
        mpv.stop()
        shutter = true
        status = nil
        flashInfo()
        zapTimer?.invalidate()
        let target = current
        zapTimer = Timer.scheduledTimer(withTimeInterval: 0.6, repeats: false) { [weak self] _ in self?.play(target) }
    }

    func flashInfo(withButtons: Bool = false) {
        showInfo = true
        if withButtons { showTopButtons = true }
        infoTimer?.invalidate()
        infoTimer = Timer.scheduledTimer(withTimeInterval: withButtons ? 6 : 5, repeats: false) { [weak self] _ in
            withAnimation(.easeIn(duration: 0.2)) {
                self?.showInfo = false
                if self?.vod == nil { self?.showTopButtons = false }
            }
        }
    }

    private let slide = Animation.spring(response: 0.32, dampingFraction: 0.9)

    func togglePanel() {
        if showPanel { closePanel(); return }
        openPanel()
    }

    /// Puts the panel on screen at [offset] (used to start a finger-driven opening).
    private func preparePanel(offset: CGFloat) {
        if showEPG { closeEPG() }
        showInfo = false
        showTopButtons = false
        panelQuery = ""
        panelSelection = visibleChannels.firstIndex(where: { $0.number == currentChannel?.number }) ?? 0
        panelOffset = offset
        showPanel = true
    }

    func openPanel() {
        if !showPanel { preparePanel(offset: -Self.panelWidth) }
        withAnimation(slide) { panelOffset = 0 }
    }

    func closePanel() {
        guard showPanel else { return }
        withAnimation(slide) { panelOffset = -Self.panelWidth } completion: { [weak self] in
            guard let self = self, self.panelOffset <= -Self.panelWidth + 1 else { return }
            self.showPanel = false
            self.panelOffset = 0
        }
    }

    private func prepareEPG(offset: CGFloat) {
        if showPanel { closePanel() }
        showInfo = false
        showTopButtons = false
        let rows = epgRows
        epgRow = rows.firstIndex(where: { $0.number == currentChannel?.number }) ?? 0
        epgCursor = Date()
        let half: TimeInterval = 1800
        epgWindowStart = Date(timeIntervalSince1970: floor(Date().timeIntervalSince1970 / half) * half - half)
        if guide.isEmpty { refreshGuide(force: false) }
        epgOffset = offset
        showEPG = true
    }

    func openEPG() {
        guard vod == nil, !channels.isEmpty else { return }
        if !showEPG { prepareEPG(offset: epgWidth) }
        withAnimation(slide) { epgOffset = 0 }
    }

    func closeEPG() {
        guard showEPG else { return }
        let w = epgWidth
        withAnimation(slide) { epgOffset = w } completion: { [weak self] in
            guard let self = self, self.epgOffset >= w - 1 else { return }
            self.showEPG = false
            self.epgOffset = 0
        }
    }

    var epgRows: [Channel] {
        let r = channels.filter { !hiddenChannels.contains($0.name) }
        return r.isEmpty ? channels : r
    }

    func epgProgram(row: Int, at t: Date) -> Program? {
        guard epgRows.indices.contains(row) else { return nil }
        return programs(epgRows[row])?.first { $0.start <= t && $0.stop > t }
    }

    func epgMove(row step: Int) {
        epgRow = max(0, min(epgRows.count - 1, epgRow + step))
    }

    func epgMove(time dir: Int) {
        let list = epgRows.indices.contains(epgRow) ? programs(epgRows[epgRow]) : nil
        let cur = epgProgram(row: epgRow, at: epgCursor)
        var next: Date
        if dir > 0 {
            next = list?.first(where: { $0.start >= (cur?.stop ?? epgCursor.addingTimeInterval(1)) })?.start ?? epgCursor.addingTimeInterval(1800)
            next = next.addingTimeInterval(1)
        } else {
            next = list?.last(where: { $0.stop <= (cur?.start ?? epgCursor) })?.start ?? epgCursor.addingTimeInterval(-1800)
        }
        let nowD = Date()
        epgCursor = min(max(next, nowD.addingTimeInterval(-7200)), nowD.addingTimeInterval(36 * 3600))
        let span: TimeInterval = 3 * 3600
        if epgCursor < epgWindowStart { epgWindowStart = epgCursor.addingTimeInterval(-600) }
        if epgCursor > epgWindowStart.addingTimeInterval(span - 900) { epgWindowStart = epgCursor.addingTimeInterval(-span / 3) }
    }

    func epgShift(hours: Double) {
        epgWindowStart = epgWindowStart.addingTimeInterval(hours * 3600)
    }

    func playChannel(_ ch: Channel) {
        guard let i = channels.firstIndex(where: { $0.number == ch.number }) else { return }
        closePanel()
        closeEPG()
        if i != current || vod != nil { play(i) } else { flashInfo() }
    }

    func toggleHidden(channel name: String) {
        if hiddenChannels.contains(name) { hiddenChannels.remove(name) } else { hiddenChannels.insert(name) }
        d.set(Array(hiddenChannels), forKey: "hide_ch")
    }

    // MARK: - Setup

    func openSetup(_ message: String?) {
        setupMessage = message ?? "Escribe la dirección de tu lista de canales (la misma del Roku)."
        if listURL.isEmpty { listURL = "https://tinyurl.com/" }
        mpv.stop()
        showSetup = true
    }

    func saveSetup() {
        let url = listURL.trimmingCharacters(in: .whitespaces)
        guard url.hasPrefix("http"), url.count >= 12 else {
            setupMessage = "La dirección debe empezar con https://"
            return
        }
        listURL = url
        d.set(url, forKey: "list")
        try? FileManager.default.removeItem(at: listFile)
        channels = []
        start()
    }

    func xtream() -> XtreamAccount? {
        if let a = playlistAccount { return a }
        let a = Store.load("xtream", XtreamAccount.self)
        return (a?.valid ?? false) ? a : nil
    }

    func saveAccount(_ acc: XtreamAccount, then series: Bool) {
        guard acc.valid else {
            accountMessage = "Completa servidor (https://…), usuario y clave."
            return
        }
        Store.save("xtream", acc)
        categories = [:]
        itemCache = [:]
        catalogCache = [:]
        showAccount = false
        openBrowser(series: series)
    }

    // MARK: - Movies & series playback

    func playVod(_ w: Watch) {
        guard let acc = xtream() else { return }
        saveVodProgress()
        zapTimer?.invalidate()
        browserOpen = false
        showPanel = false
        showInfo = false
        shutter = true
        aspect = "no"
        mpv.setAspect("no")
        recoverAttempts = 0
        stallSince = nil
        playToken += 1
        let token = playToken
        let saved = history.first { $0.id == w.id }
        let resume = (saved.map { !$0.finished && $0.position > 10 } ?? false) ? saved!.position : 0
        var entry = w
        entry.position = resume
        entry.duration = saved?.duration ?? 0
        vod = entry
        recordHistory(entry)
        status = resume > 0 ? "Cargando…\nContinuando desde \(clock(resume))" : nil
        applyLanguages()
        // one connection at a time: close what was playing, then open straight at the resume point
        // (a downloaded copy plays from the disk, no waiting)
        let local = DownloadCenter.shared.localURL(w.id)
        let url = local?.path ?? (w.episode ? acc.episodeURL(w.streamId, w.ext) : acc.movieURL(w.streamId, w.ext))
        mpv.stop()
        recovering = true
        DispatchQueue.main.asyncAfter(deadline: .now() + (local != nil ? 0.05 : 0.6)) { [weak self] in
            guard let self = self, token == self.playToken else { return }
            self.recovering = false
            self.mpv.load(url, start: resume)
        }
        startHealth()
        flashVodBar()
        prepareAirPlay()
        saveTimer?.invalidate()
        saveTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            guard let self = self, self.vod != nil, !self.mpv.paused else { return }
            self.saveVodProgress()
        }
    }

    func saveVodProgress() {
        guard var w = vod else { return }
        w.position = max(0, mpv.position)
        if mpv.duration > 0 { w.duration = mpv.duration }
        vod = w
        recordHistory(w)
    }

    private func recordHistory(_ w: Watch) {
        var entry = w
        entry.updated = Date()
        history.removeAll { $0.id == w.id }
        history.insert(entry, at: 0)
        if history.count > 60 { history = Array(history.prefix(60)) }
        Store.save("history", history)
    }

    func leaveVod() {
        if airplayActive { airplayPlayer?.pause(); airplayActive = false }
        airplayObserver = nil
        airplayPlayer = nil
        saveVodProgress()
        saveTimer?.invalidate()
        vod = nil
        showVodBar = false
        status = nil
        mpv.stop()
        withAnimation(.easeOut(duration: 0.2)) { browserOpen = true }
    }

    func flashVodBar() {
        withAnimation(.easeOut(duration: 0.15)) { showVodBar = true }
        vodBarTimer?.invalidate()
        vodBarTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: false) { [weak self] _ in
            guard let self = self, !self.mpv.paused, !self.scrubbing else { return }
            withAnimation(.easeIn(duration: 0.2)) {
                self.showVodBar = false
                self.showTopButtons = false
            }
        }
    }

    var scrubbing = false

    func seek(_ seconds: Double) {
        mpv.seek(by: seconds)
        flashVodBar()
    }

    func togglePause() {
        mpv.togglePause()
        if !mpv.paused { saveVodProgress() }
        flashVodBar()
    }

    func setAspect(_ a: String) {
        aspect = a
        mpv.setAspect(a)
    }

    func clock(_ s: Double) -> String {
        let t = Int(max(0, s))
        return t >= 3600 ? String(format: "%d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60) : String(format: "%02d:%02d", t / 60, t % 60)
    }

    // MARK: - Browser

    func openBrowser(series: Bool) {
        guard xtream() != nil else {
            accountMessage = playlistAccount == nil
                ? "Para ver películas y series escribe tu cuenta Xtream (se guarda solo en este Mac)."
                : ""
            pendingSeries = series
            showAccount = true
            return
        }
        showPanel = false
        showInfo = false
        showEPG = false
        zapTimer?.invalidate()
        mpv.stop()
        if browserSeries != series {
            browserSeries = series
            openSeries = nil
            entries = []
            searchOpen = false
            searchText = ""
            gridMode = .category
        }
        withAnimation(.easeOut(duration: 0.2)) { browserOpen = true }
        if categories[series] == nil { loadCategories() } else if entries.isEmpty { selectCategory(selectedCat[series] ?? defaultCategory()?.id) }
    }

    var pendingSeries = false

    func closeBrowser() {
        browserOpen = false
        openSeries = nil
        if !channels.isEmpty { play(current) }
    }

    func tab(_ t: Tab) {
        switch t {
        case .live:
            if browserOpen { closeBrowser() }
            if !showPanel { togglePanel() }
        case .movies: openBrowser(series: false)
        case .series: openBrowser(series: true)
        }
    }

    var hiddenCats: Set<String> { browserSeries ? hiddenSeriesCats : hiddenMovieCats }

    var visibleCats: [Category] { (categories[browserSeries] ?? []).filter { !hiddenCats.contains($0.id) } }

    func defaultCategory() -> Category? {
        let list = visibleCats
        func key(_ c: Category) -> String { Search.normalize(c.name).trimmingCharacters(in: .whitespaces) }
        if browserSeries {
            return list.first { key($0) == "hbo" } ?? list.first { key($0).contains("hbo") } ?? list.first
        }
        return list.first { key($0) == "estrenos" } ?? list.first { key($0).contains("estreno") && !key($0).contains("cam") } ?? list.first
    }

    private func loadCategories() {
        guard let acc = xtream() else { return }
        let series = browserSeries
        loadToken += 1
        let token = loadToken
        gridMessage = "Cargando categorías…"
        Xtream.categories(acc, series: series) { [weak self] r in
            guard let self = self, token == self.loadToken, series == self.browserSeries else { return }
            switch r {
            case .success(let cats):
                self.categories[series] = cats
                self.selectCategory(self.selectedCat[series] ?? self.defaultCategory()?.id)
            case .failure(let e):
                self.gridMessage = "No se pudo cargar: \(e.localizedDescription)"
            }
        }
    }

    func selectCategory(_ id: String?) {
        searchOpen = false
        searchText = ""
        guard let id = id, let cat = visibleCats.first(where: { $0.id == id }) ?? defaultCategory() else {
            entries = []
            gridMessage = (categories[browserSeries] ?? []).isEmpty ? "El proveedor no tiene categorías aquí." : "Todas las categorías están ocultas (usa el filtro)."
            return
        }
        let series = browserSeries
        selectedCat[series] = cat.id
        gridMode = .category
        gridLabel = cat.name
        let key = "\(series):\(cat.id)"
        if let cached = itemCache[key] {
            entries = cached.map { .item($0) }
            gridMessage = cached.isEmpty ? "Categoría vacía." : nil
            return
        }
        guard let acc = xtream() else { return }
        loadToken += 1
        let token = loadToken
        entries = []
        gridMessage = "Cargando \(cat.name)…"
        Xtream.items(acc, series: series, category: cat.id) { [weak self] r in
            guard let self = self else { return }
            if case .success(let list) = r { self.itemCache[key] = list }
            guard token == self.loadToken, series == self.browserSeries else { return }
            switch r {
            case .success(let list):
                self.entries = list.map { .item($0) }
                self.gridMessage = list.isEmpty ? "Categoría vacía." : nil
            case .failure(let e):
                self.gridMessage = "No se pudo cargar: \(e.localizedDescription)"
            }
        }
    }

    var continueList: [Watch] {
        var seen = Set<String>()
        return history.filter { w in
            w.episode == browserSeries && !w.finished && (!w.episode || seen.insert(w.seriesId.isEmpty ? w.streamId : w.seriesId).inserted)
        }
    }

    var laterList: [VodItem] { later.filter { $0.series == browserSeries } }

    var downloadList: [DownloadRecord] { DownloadCenter.shared.list(series: browserSeries) }

    func showAllTop() {
        searchOpen = false
        if topMode == 2 {
            gridMode = .downloadsAll
            gridLabel = "Descargas"
            entries = downloadList.map { .download($0) }
        } else if topMode == 0 {
            gridMode = .continueAll
            gridLabel = "Seguir viendo · todo"
            entries = continueList.map { .watch($0) }
        } else {
            gridMode = .laterAll
            gridLabel = "Ver más tarde · todo"
            entries = laterList.map { .item($0) }
        }
        gridMessage = nil
    }

    func scheduleSearch() {
        searchDebounce?.invalidate()
        guard searchText.trimmingCharacters(in: .whitespaces).count >= 2 else { return }
        searchDebounce = Timer.scheduledTimer(withTimeInterval: 0.8, repeats: false) { [weak self] _ in self?.runSearch() }
    }

    func runSearch() {
        let query = searchText.trimmingCharacters(in: .whitespaces)
        guard query.count >= 2, let acc = xtream() else { return }
        let series = browserSeries
        gridMode = .search
        loadToken += 1
        let token = loadToken
        let show: ([VodItem]) -> Void = { [weak self] all in
            guard let self = self else { return }
            let q = Search.normalize(query)
            let hidden = self.hiddenCats
            let found = all.filter { !hidden.contains($0.categoryId) && $0.searchKey.contains(q) }
            self.entries = found.prefix(600).map { .item($0) }
            self.gridLabel = "Resultados para “\(query)” (\(found.count))"
            self.gridMessage = found.isEmpty ? "Sin resultados para “\(query)”." : nil
        }
        if let cached = catalogCache[series] { show(cached); return }
        entries = []
        gridLabel = "Buscando “\(query)”…"
        gridMessage = "Buscando en todo el catálogo…\nLa primera búsqueda tarda un poco."
        Xtream.items(acc, series: series, category: nil) { [weak self] r in
            guard let self = self else { return }
            if case .success(let list) = r { self.catalogCache[series] = list }
            guard token == self.loadToken, series == self.browserSeries else { return }
            switch r {
            case .success(let list): show(list)
            case .failure(let e): self.gridMessage = "No se pudo buscar: \(e.localizedDescription)"
            }
        }
    }

    func closeSearch() {
        searchOpen = false
        searchText = ""
        if gridMode == .search { selectCategory(selectedCat[browserSeries] ?? defaultCategory()?.id) }
    }

    func open(_ entry: GridEntry) {
        switch entry {
        case .item(let v):
            if v.series { openSeriesView(v) } else { playVod(Watch(episode: false, streamId: v.streamId, ext: v.ext, title: v.title, subtitle: v.year, poster: v.poster)) }
        case .download(let d):
            if DownloadCenter.shared.localURL(d.id) != nil { playVod(d.watch) }
        case .watch(let w):
            if w.episode, !w.seriesId.isEmpty, openSeries?.streamId != w.seriesId {
                let s = VodItem(series: true, streamId: w.seriesId, title: w.title, poster: w.poster, ext: w.ext, year: "", rating: "", categoryId: "")
                loadEpisodes(s, playAfter: w)
            } else {
                playVod(w)
            }
        }
    }

    func movieWatch(_ v: VodItem) -> Watch {
        Watch(episode: false, streamId: v.streamId, ext: v.ext, title: v.title, subtitle: v.year, poster: v.poster)
    }

    func isDownloaded(_ id: String) -> Bool { DownloadCenter.shared.record(id) != nil }

    /// Download (or delete the download of) a movie or episode.
    func toggleDownload(_ w: Watch) {
        let dl = DownloadCenter.shared
        if dl.record(w.id) != nil {
            dl.remove(w.id)
        } else {
            guard let acc = xtream(), w.ext.lowercased() != "m3u8" else { return }
            dl.start(w, url: w.episode ? acc.episodeURL(w.streamId, w.ext) : acc.movieURL(w.streamId, w.ext))
            topMode = 2
        }
        if gridMode == .downloadsAll { entries = downloadList.map { .download($0) } }
    }

    func isLater(_ v: VodItem) -> Bool { later.contains { $0.id == v.id } }

    func toggleLater(_ v: VodItem) {
        if isLater(v) { later.removeAll { $0.id == v.id } } else { later.insert(v, at: 0); topMode = 1 }
        Store.save("later", later)
        if gridMode == .laterAll { entries = laterList.map { .item($0) } }
    }

    func removeFromHistory(_ w: Watch) {
        if w.episode, !w.seriesId.isEmpty { history.removeAll { $0.seriesId == w.seriesId } } else { history.removeAll { $0.id == w.id } }
        Store.save("history", history)
        if gridMode == .continueAll { entries = continueList.map { .watch($0) } }
    }

    func watchFor(_ v: VodItem) -> Watch? { history.first { $0.id == "m:" + v.streamId } }

    // MARK: Series

    func openSeriesView(_ s: VodItem) {
        openSeries = s
        episodes = []
        seriesInfo = "Cargando episodios…"
        loadEpisodes(s, playAfter: nil)
    }

    func episodeWatch(_ s: VodItem, _ e: Episode) -> Watch {
        Watch(episode: true, streamId: e.id, ext: e.ext, title: s.title, subtitle: "T\(e.season) · E\(e.number)  \(e.title)",
              poster: s.poster, seriesId: s.streamId)
    }

    var seasons: [Int] { Array(Set(episodes.map { $0.season })).sorted() }
    var seasonEpisodes: [Episode] { episodes.filter { $0.season == season } }

    private func loadEpisodes(_ s: VodItem, playAfter: Watch?) {
        guard let acc = xtream() else { return }
        loadToken += 1
        let token = loadToken
        Xtream.episodes(acc, seriesId: s.streamId) { [weak self] r in
            guard let self = self, token == self.loadToken else { return }
            let list = (try? r.get()) ?? []
            self.openSeries = s
            self.episodes = list
            let resume = list.first { e in self.history.first { $0.id == "e:" + e.id }.map { !$0.finished } ?? false }
            self.season = list.first { $0.id == playAfter?.streamId }?.season ?? resume?.season ?? self.seasons.first ?? 0
            if case .failure(let e) = r {
                self.seriesInfo = "No se pudo cargar: \(e.localizedDescription)"
            } else {
                let n = self.seasons.count
                self.seriesInfo = list.isEmpty ? "Esta serie no tiene episodios." : "\(n) temporada\(n == 1 ? "" : "s")  ·  \(list.count) episodios"
            }
            if let w = playAfter { self.playVod(w) }
        }
    }

    func closeSeries() {
        loadToken += 1
        openSeries = nil
        episodes = []
    }

    func browserBack() {
        if openSeries != nil { closeSeries() }
        else if searchOpen || gridMode == .search { closeSearch() }
        else if gridMode == .continueAll || gridMode == .laterAll { selectCategory(selectedCat[browserSeries] ?? defaultCategory()?.id) }
        else { closeBrowser() }
    }

    // MARK: Hidden categories

    func toggleHidden(category id: String) {
        if browserSeries {
            if hiddenSeriesCats.contains(id) { hiddenSeriesCats.remove(id) } else { hiddenSeriesCats.insert(id) }
            d.set(Array(hiddenSeriesCats), forKey: "hide_series")
        } else {
            if hiddenMovieCats.contains(id) { hiddenMovieCats.remove(id) } else { hiddenMovieCats.insert(id) }
            d.set(Array(hiddenMovieCats), forKey: "hide_vod")
        }
    }

    func closeFilter() {
        filterKind = nil
        if browserOpen, let sel = selectedCat[browserSeries], hiddenCats.contains(sel) {
            selectCategory(defaultCategory()?.id)
        }
    }

    // MARK: - Keyboard

    private func textFieldFocused() -> Bool {
        NSApp.keyWindow?.firstResponder is NSTextView
    }

    /// Returns true when the key was handled.
    private func handleKey(_ e: NSEvent) -> Bool {
        let code = e.keyCode
        let esc: UInt16 = 53, enter: UInt16 = 36, space: UInt16 = 49
        let left: UInt16 = 123, right: UInt16 = 124, down: UInt16 = 125, up: UInt16 = 126
        if code == esc { back(); return true }
        if textFieldFocused() { return false }
        let ch = e.charactersIgnoringModifiers?.lowercased() ?? ""
        if ch == "f", e.modifierFlags.intersection([.command, .control, .option]).isEmpty {
            NSApp.keyWindow?.toggleFullScreen(nil)
            return true
        }
        if showSetup || showAccount || showSettings || filterKind != nil { return false }
        if showEPG {
            switch code {
            case up: epgMove(row: -1)
            case down: epgMove(row: 1)
            case left: epgMove(time: -1)
            case right: epgMove(time: 1)
            case enter: if epgRows.indices.contains(epgRow) { playChannel(epgRows[epgRow]) }
            default: return false
            }
            return true
        }
        if showPanel {
            let list = visibleChannels
            switch code {
            case up: panelSelection = max(0, panelSelection - 1)
            case down: panelSelection = min(list.count - 1, panelSelection + 1)
            case enter: if list.indices.contains(panelSelection) { playChannel(list[panelSelection]) }
            case left: closePanel()
            default: return false
            }
            return true
        }
        if browserOpen { return false }
        if vod != nil {
            switch code {
            case space, enter: togglePause()
            case left: seek(-10)
            case right: seek(10)
            case up, down: flashVodBar()
            default: return false
            }
            return true
        }
        switch code {
        case down: zap(1)
        case up: zap(-1)
        case left, enter: togglePanel()
        case right: openEPG()
        case space: flashInfo()
        default:
            if ch == "g" { openEPG(); return true }
            if ch == "i" { flashInfo(); return true }
            if ch == "o" { nextOption(); return true }
            if let n = Int(ch), (0...9).contains(n) { typeDigit(n); return true }
            return false
        }
        return true
    }

    @Published var digits = ""
    private var digitTimer: Timer?

    private func typeDigit(_ n: Int) {
        if digits.count >= 3 { digits = "" }
        digits += String(n)
        digitTimer?.invalidate()
        digitTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: false) { [weak self] _ in
            guard let self = self else { return }
            if let v = Int(self.digits), v >= 1, v <= self.channels.count { self.play(v - 1) }
            self.digits = ""
        }
    }

    func back() {
        if showSettings { showSettings = false }
        else if filterKind != nil { closeFilter() }
        else if showAccount { showAccount = false; if !browserOpen && vod == nil { play(current) } }
        else if showSetup, !channels.isEmpty { showSetup = false; play(current) }
        else if showEPG { closeEPG() }
        else if showPanel { closePanel() }
        else if browserOpen { browserBack() }
        else if vod != nil { leaveVod() }
        else if showInfo { showInfo = false }
    }

    /// Click on the video.
    func tapVideo() {
        if showPanel { closePanel(); return }
        if showEPG { closeEPG(); return }
        if mini { toggleMini(); return }
        if vod != nil {
            if showVodBar { withAnimation { showVodBar = false; showTopButtons = false } } else { showTopButtons = true; flashVodBar() }
            return
        }
        if showInfo { withAnimation { showInfo = false; showTopButtons = false } } else { flashInfo(withButtons: true) }
    }

    // MARK: - Trackpad (two fingers)

    private enum SwipeMode { case none, openPanel, closePanel, openEPG, closeEPG, seek, zap, pass }
    private var swipeMode = SwipeMode.none

    /// Two-finger swipe: → channel list, ← guide (from the right), ↑↓ change channel.
    /// Panels follow the fingers and settle (open or closed) when the fingers lift.
    private func handleScroll(_ e: NSEvent) -> Bool {
        guard e.hasPreciseScrollingDeltas else { return false }
        if !e.momentumPhase.isEmpty { return swipeMode != .pass && swipeMode != .none }
        if browserOpen || showSetup || showAccount || showSettings || filterKind != nil || mini { return false }
        let inverted = e.isDirectionInvertedFromDevice
        let fx = inverted ? e.scrollingDeltaX : -e.scrollingDeltaX   // + = fingers moving right
        let fy = inverted ? e.scrollingDeltaY : -e.scrollingDeltaY   // + = fingers moving down
        switch e.phase {
        case .began:
            gesture = (0, 0, 0)
            swipeMode = .none
            return !(showPanel || showEPG)
        case .changed:
            gesture.dx += fx
            gesture.dy += fy
            if swipeMode == .none, max(abs(gesture.dx), abs(gesture.dy)) > 10 {
                let horizontal = abs(gesture.dx) > abs(gesture.dy)
                if horizontal {
                    if vod != nil { swipeMode = .seek }
                    else if showEPG { swipeMode = gesture.dx > 0 ? .closeEPG : .pass }
                    else if showPanel { swipeMode = gesture.dx < 0 ? .closePanel : .pass }
                    else if gesture.dx > 0 { swipeMode = .openPanel; preparePanel(offset: -Self.panelWidth) }
                    else { swipeMode = .openEPG; prepareEPG(offset: epgWidth) }
                } else {
                    swipeMode = (vod == nil && !showPanel && !showEPG && channels.count > 1) ? .zap : .pass
                }
            }
            switch swipeMode {
            case .openPanel: panelOffset = min(0, -Self.panelWidth + max(0, gesture.dx))
            case .closePanel: panelOffset = max(-Self.panelWidth, min(0, gesture.dx))
            case .openEPG: epgOffset = max(0, epgWidth + min(0, gesture.dx))
            case .closeEPG: epgOffset = min(epgWidth, max(0, gesture.dx))
            case .zap: dragTo(gesture.dy)
            case .pass, .none: return false
            case .seek: break
            }
            return true
        case .ended, .cancelled:
            let g = gesture
            let mode = swipeMode
            let flick = abs(fx) > 6   // last movement still fast = a flick
            gesture = (0, 0, 0)
            swipeMode = .none
            switch mode {
            case .openPanel:
                if g.dx > Self.panelWidth * 0.3 || (flick && fx > 0) { openPanel() } else { closePanel() }
            case .closePanel:
                if -g.dx > Self.panelWidth * 0.3 || (flick && fx < 0) { closePanel() } else { openPanel() }
            case .openEPG:
                if -g.dx > epgWidth * 0.25 || (flick && fx < 0) { openEPG() } else { closeEPG() }
            case .closeEPG:
                if g.dx > epgWidth * 0.25 || (flick && fx > 0) { closeEPG() } else { openEPG() }
            case .seek:
                if abs(g.dx) > 60 { seek(g.dx > 0 ? 10 : -10) }
            case .zap:
                endDrag(g.dy)
            case .pass, .none:
                return false
            }
            return true
        default:
            return false
        }
    }

    private var screenHeight: CGFloat { NSApp.keyWindow?.contentView?.bounds.height ?? 700 }

    private func dragTo(_ dy: CGFloat) {
        let target = neighbor(dy < 0 ? 1 : -1)   // fingers up = next channel
        dragTarget = target
        showInfo = false
        dragY = dy
    }

    private func endDrag(_ dy: CGFloat) {
        let h = screenHeight
        guard let target = dragTarget, abs(dy) > h * 0.2 else {
            withAnimation(slide) { dragY = 0 }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in if self?.dragY == 0 { self?.dragTarget = nil } }
            return
        }
        withAnimation(.easeOut(duration: 0.22)) { dragY = dy < 0 ? -h : h }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.23) { [weak self] in
            guard let self = self else { return }
            self.cardChannel = target
            self.dragY = 0
            self.dragTarget = nil
            self.play(target)
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
                if self?.cardChannel == target { withAnimation { self?.cardChannel = nil } }
            }
        }
    }

    // MARK: - Picture in picture

    func togglePiP() {
        guard let w = NSApp.windows.first(where: { $0.isVisible }) ?? NSApp.windows.first else { return }
        let pip = PiPController.shared
        guard pip.supported else { toggleMini(); return }
        showTopButtons = false
        showInfo = false
        showVodBar = false
        pip.isLive = vod == nil
        pip.toggle(window: w)
    }

    // MARK: - Mini window (fallback when the system has no picture in picture)

    func toggleMini() {
        guard let w = NSApp.windows.first(where: { $0.isVisible }) else { return }
        if !mini {
            if w.styleMask.contains(.fullScreen) { w.toggleFullScreen(nil) }
            savedFrame = w.frame
            showPanel = false
            showEPG = false
            showInfo = false
            showVodBar = false
            showTopButtons = false
            mini = true
            let screen = w.screen?.visibleFrame ?? NSScreen.main?.visibleFrame ?? .zero
            let size = NSSize(width: 480, height: 270)
            w.level = .floating
            w.collectionBehavior.insert(.canJoinAllSpaces)
            w.setFrame(NSRect(x: screen.maxX - size.width - 24, y: screen.minY + 24, width: size.width, height: size.height), display: true, animate: true)
            w.minSize = NSSize(width: 320, height: 180)
        } else {
            mini = false
            w.level = .normal
            w.collectionBehavior.remove(.canJoinAllSpaces)
            w.minSize = NSSize(width: 960, height: 560)
            if let f = savedFrame { w.setFrame(f, display: true, animate: true) }
        }
    }

    // MARK: - AirPlay (Apple TV)

    /// Movies / series go to the Apple TV with AVPlayer; the live channels' format is not accepted by AirPlay.
    func prepareAirPlay() {
        airplayObserver = nil
        airplayPlayer?.pause()
        airplayPlayer = nil
        guard let w = vod, let acc = xtream(), let url = URL(string: w.episode ? acc.episodeURL(w.streamId, w.ext) : acc.movieURL(w.streamId, w.ext)) else { return }
        let p = AVPlayer(url: url)
        p.allowsExternalPlayback = true
        airplayObserver = p.observe(\.isExternalPlaybackActive, options: [.new]) { [weak self] player, _ in
            DispatchQueue.main.async { self?.airplayChanged(player.isExternalPlaybackActive) }
        }
        airplayPlayer = p
    }

    private func airplayChanged(_ active: Bool) {
        guard let p = airplayPlayer else { return }
        if active, !airplayActive {
            airplayActive = true
            let pos = mpv.position
            mpv.setPaused(true)
            p.seek(to: CMTime(seconds: pos, preferredTimescale: 600))
            p.play()
            status = "Transmitiendo al Apple TV\nUsa el botón AirPlay para volver a este Mac."
        } else if !active, airplayActive {
            airplayActive = false
            let pos = p.currentTime().seconds
            p.pause()
            status = nil
            if pos.isFinite, pos > 1 { mpv.command(["seek", String(pos), "absolute"]) }
            mpv.setPaused(false)
        }
    }
}
