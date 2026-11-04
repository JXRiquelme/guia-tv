import SwiftUI
import AppKit
import AVKit

// MARK: - Theme

enum Theme {
    static let panel = Color(red: 0.04, green: 0.07, blue: 0.22)
    static let deep = Color(red: 0.03, green: 0.06, blue: 0.19)
    static let card = Color(red: 0.08, green: 0.12, blue: 0.27)
    static let bar = Color(red: 0.04, green: 0.12, blue: 0.35).opacity(0.92)
    static let blue = Color(red: 0.18, green: 0.44, blue: 0.92)
    static let chip = Color(red: 0.23, green: 0.36, blue: 0.66)
    static let accent = Color(red: 0.5, green: 0.83, blue: 1.0)
    static let muted = Color(red: 0.72, green: 0.78, blue: 0.94)
    static let hint = Color(red: 0.56, green: 0.63, blue: 0.8)
}

let hmFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm"
    return f
}()

func hm(_ d: Date) -> String { hmFormatter.string(from: d) }

// MARK: - Video

struct VideoView: NSViewRepresentable {
    func makeNSView(context: Context) -> VideoNSView { VideoNSView(frame: .zero) }
    func updateNSView(_ nsView: VideoNSView, context: Context) {}
}

struct RemoteImage: View {
    let url: String
    var mode: ContentMode = .fit
    var body: some View {
        if let u = URL(string: url), !url.isEmpty {
            AsyncImage(url: u) { phase in
                if let img = phase.image { img.resizable().aspectRatio(contentMode: mode) } else { Color.clear }
            }
        } else {
            Color.clear
        }
    }
}

// MARK: - Root

struct RootView: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject var mpv = MPV.shared

    var body: some View {
        GeometryReader { geo in
            ZStack {
                let progress: Double = Double(min(1, abs(app.dragY) / max(1, geo.size.height)))
                VideoView()
                    .scaleEffect(CGFloat(1.0 - progress * 0.08))
                    .opacity(1.0 - progress * 0.6)
                    .offset(y: app.dragY * 0.35)
                if app.shutter && app.dragTarget == nil { Color.black }
                if let t = app.dragTarget {
                    ZapCard(index: t)
                        .shadow(color: .black.opacity(0.6), radius: 24, y: app.dragY < 0 ? -8 : 8)
                        .offset(y: app.dragY < 0 ? geo.size.height + app.dragY : -geo.size.height + app.dragY)
                }
                if let c = app.cardChannel { ZapCard(index: c).transition(.opacity) }
                Color.clear.contentShape(Rectangle()).onTapGesture { app.tapVideo() }

                if app.vod == nil && app.showInfo && !app.mini { InfoBanner().frame(maxHeight: .infinity, alignment: .bottom).transition(.opacity) }
                if app.vod != nil && (app.showVodBar || mpv.paused) && !app.mini {
                    VodBar().frame(maxHeight: .infinity, alignment: .bottom).transition(.move(edge: .bottom).combined(with: .opacity))
                }
                if let s = app.status {
                    Text(s).font(.system(size: 20)).multilineTextAlignment(.center).foregroundColor(.white)
                        .padding(.horizontal, 24).padding(.vertical, 14)
                        .background(Color.black.opacity(0.8)).cornerRadius(12)
                        .offset(y: app.vod != nil ? -geo.size.height * 0.22 : 0)
                        .allowsHitTesting(false)
                }
                if !app.digits.isEmpty {
                    Text(app.digits).font(.system(size: 44, weight: .bold)).foregroundColor(.white)
                        .padding(.horizontal, 18).padding(.vertical, 6).background(Color.black.opacity(0.8)).cornerRadius(10)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing).padding(30)
                }
                if app.showTopButtons && !app.mini {
                    TopButtons().frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top).transition(.opacity)
                }
                if app.showPanel {
                    SidePanel().frame(width: AppModel.panelWidth)
                        .shadow(color: .black.opacity(0.5), radius: 18)
                        .offset(x: app.panelOffset)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if app.showEPG {
                    EPGView().frame(width: geo.size.width * 0.72)
                        .shadow(color: .black.opacity(0.5), radius: 18)
                        .offset(x: app.epgOffset)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
                if app.airplayNotice { AirPlayNotice() }
                if app.browserOpen { BrowserView().transition(.opacity) }
                if app.filterKind != nil { FilterView() }
                if app.showAccount { AccountView() }
                if app.showSetup { SetupView() }
            }
            .animation(.easeOut(duration: 0.2), value: app.browserOpen)
            .animation(.easeOut(duration: 0.18), value: app.showInfo)
            .animation(.easeOut(duration: 0.18), value: app.showTopButtons)
        }
        .clipped()
        .background(Color.black)
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
    }
}

// MARK: - Live: info banner

struct InfoBanner: View {
    @EnvironmentObject var app: AppModel
    var body: some View {
        if let ch = app.currentChannel {
            let nn = GuideParser.nowNext(app.programs(ch), at: app.now)
            let cur = nn.0
            let next = nn.1
            HStack(spacing: 18) {
                RemoteImage(url: ch.logo).frame(width: 120, height: 68)
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(ch.number)   \(ch.name)").font(.system(size: 24, weight: .bold)).lineLimit(1)
                    if let c = cur {
                        Text("Ahora  \(hm(c.start)) – \(hm(c.stop))   \(c.title)").font(.system(size: 17)).lineLimit(1)
                        ProgressView(value: max(0, min(1, app.now.timeIntervalSince(c.start) / c.stop.timeIntervalSince(c.start))))
                            .progressViewStyle(.linear).tint(.yellow)
                    } else {
                        Text("Sin información de guía").font(.system(size: 17))
                    }
                    if let n = next {
                        Text("Después  \(hm(n.start))   \(n.title)").font(.system(size: 15)).foregroundColor(Theme.muted).lineLimit(1)
                    }
                }
                Spacer()
            }
            .foregroundColor(.white)
            .padding(.horizontal, 22).padding(.vertical, 14)
            .background(Theme.bar).cornerRadius(14)
            .padding(.horizontal, 40).padding(.bottom, 28)
        }
    }
}

// MARK: - Tabs

struct TopTabs: View {
    @EnvironmentObject var app: AppModel
    let active: Tab
    var body: some View {
        HStack(spacing: 6) {
            ForEach([Tab.live, .movies, .series], id: \.rawValue) { t in
                Button { app.tab(t) } label: {
                    Text(["Canales", "Películas", "Series"][t.rawValue])
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(t == active ? Theme.accent : .white)
                        .frame(width: 96, height: 36)
                        .background(t == active ? Theme.blue.opacity(0.25) : Color.clear)
                        .cornerRadius(10)
                        .contentShape(Rectangle())
                }.buttonStyle(.plain)
            }
        }
    }
}

struct IconButton: View {
    let system: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: system).font(.system(size: 15, weight: .semibold)).foregroundColor(.white)
                .frame(width: 34, height: 34).background(Color.white.opacity(0.1)).clipShape(Circle())
        }.buttonStyle(.plain)
    }
}

// MARK: - Live: side panel

struct SidePanel: View {
    @EnvironmentObject var app: AppModel
    @State private var searching = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                TopTabs(active: .live)
                Spacer()
                IconButton(system: "magnifyingglass") { searching.toggle(); if !searching { app.panelQuery = "" } }
                IconButton(system: "line.3.horizontal.decrease") { app.filterKind = .live }
            }
            if searching {
                TextField("Buscar canal", text: $app.panelQuery).textFieldStyle(.roundedBorder).font(.system(size: 16))
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 2) {
                        let list = app.visibleChannels
                        ForEach(Array(list.enumerated()), id: \.element.id) { i, ch in
                            ChannelRow(ch: ch, selected: i == app.panelSelection,
                                       playing: app.vod == nil && ch.number == app.currentChannel?.number)
                                .id(i)
                                .onTapGesture { app.playChannel(ch) }
                                .contextMenu { Button("Ocultar canal") { app.toggleHidden(channel: ch.name) } }
                        }
                        SettingsRow(title: "Cuenta de películas y series") { app.pendingSeries = false; app.showAccount = true }
                        SettingsRow(title: "Cambiar dirección de la lista") { app.openSetup(nil) }
                    }
                }
                .onAppear { proxy.scrollTo(app.panelSelection, anchor: .center) }
                .onChange(of: app.panelSelection) { v in withAnimation { proxy.scrollTo(v, anchor: .center) } }
            }
            Text("↑↓ elegir · Enter ver · Esc cerrar · ↑↓ en pantalla cambia canal · → guía")
                .font(.system(size: 12)).foregroundColor(Theme.hint)
        }
        .padding(.horizontal, 14).padding(.bottom, 14).padding(.top, 40)
        .frame(maxHeight: .infinity)
        .background(Theme.panel.opacity(0.97))
    }
}

struct ChannelRow: View {
    @EnvironmentObject var app: AppModel
    let ch: Channel
    let selected: Bool
    let playing: Bool
    var body: some View {
        let now = GuideParser.nowNext(app.programs(ch), at: app.now).0
        HStack(spacing: 12) {
            Text(playing ? "▶" : "\(ch.number)").font(.system(size: 14)).foregroundColor(playing ? Theme.accent : Theme.hint)
                .frame(width: 28, alignment: .trailing)
            RemoteImage(url: ch.logo).frame(width: 52, height: 30)
            VStack(alignment: .leading, spacing: 1) {
                Text(ch.name).font(.system(size: 15, weight: .bold)).foregroundColor(playing ? Theme.accent : .white).lineLimit(1)
                if let p = now { Text(p.title).font(.system(size: 12)).foregroundColor(Theme.muted).lineLimit(1) }
            }
            Spacer()
        }
        .padding(.vertical, 4).padding(.horizontal, 4)
        .background(selected ? Theme.blue : Color.clear).cornerRadius(8)
        .contentShape(Rectangle())
    }
}

struct SettingsRow: View {
    let title: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack {
                Image(systemName: "gearshape").frame(width: 34)
                Text(title).font(.system(size: 16, weight: .semibold))
                Spacer()
            }.foregroundColor(.white).padding(.vertical, 10).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

// MARK: - Live: program guide

struct EPGView: View {
    @EnvironmentObject var app: AppModel
    private let rowH: CGFloat = 54
    private let chanW: CGFloat = 210
    private let span: TimeInterval = 3 * 3600

    var body: some View {
        let rows = app.epgRows
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                details(rows)
                Spacer()
                HStack(spacing: 8) {
                    smallButton("◀ 1 h") { app.epgShift(hours: -1) }
                    smallButton("Ahora") { app.epgCursor = Date(); app.epgWindowStart = Date().addingTimeInterval(-1800) }
                    smallButton("1 h ▶") { app.epgShift(hours: 1) }
                    smallButton("Cerrar") { app.closeEPG() }
                }
            }
            GeometryReader { geo in
                let gridW = max(100, geo.size.width - chanW)
                let pps = gridW / span
                VStack(spacing: 0) {
                    ruler(pps: pps).frame(height: 26)
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(spacing: 2) {
                                ForEach(Array(rows.enumerated()), id: \.element.id) { i, ch in
                                    row(i, ch, pps: pps, gridW: gridW).id(i)
                                }
                            }
                        }
                        .onAppear { proxy.scrollTo(app.epgRow, anchor: .center) }
                        .onChange(of: app.epgRow) { v in withAnimation(.easeOut(duration: 0.15)) { proxy.scrollTo(v, anchor: .center) } }
                    }
                }
                .overlay(nowLine(pps: pps, height: geo.size.height), alignment: .topLeading)
            }
        }
        .padding(.horizontal, 20).padding(.top, 34).padding(.bottom, 8)
        .background(Theme.deep.opacity(0.97))
    }

    private func smallButton(_ t: String, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            Text(t).font(.system(size: 14, weight: .bold)).foregroundColor(.white)
                .padding(.horizontal, 14).padding(.vertical, 7).background(Color.white.opacity(0.14)).cornerRadius(16)
        }.buttonStyle(.plain)
    }

    private func details(_ rows: [Channel]) -> some View {
        let ch = rows.indices.contains(app.epgRow) ? rows[app.epgRow] : nil
        let p = app.epgProgram(row: app.epgRow, at: app.epgCursor)
        return VStack(alignment: .leading, spacing: 2) {
            Text(ch.map { "\($0.number)   \($0.name)" } ?? "").font(.system(size: 14, weight: .bold)).foregroundColor(Theme.accent)
            Text(p?.title ?? "Sin información de guía").font(.system(size: 20, weight: .bold)).foregroundColor(.white).lineLimit(1)
            if let p = p {
                let tag = p.start <= Date() && p.stop > Date() ? "  ·  Ahora" : (p.stop <= Date() ? "  ·  Ya emitido" : "")
                Text("\(hm(p.start)) – \(hm(p.stop))\(tag)").font(.system(size: 14)).foregroundColor(Theme.muted)
                Text(p.desc).font(.system(size: 13)).foregroundColor(Theme.muted).lineLimit(2)
            }
        }
    }

    private func ruler(pps: CGFloat) -> some View {
        let half: TimeInterval = 1800
        let first = floor(app.epgWindowStart.timeIntervalSince1970 / half) * half
        let marks = (0..<8).map { Date(timeIntervalSince1970: first + Double($0) * half) }
            .filter { $0 >= app.epgWindowStart && $0 < app.epgWindowStart.addingTimeInterval(span) }
        return ZStack(alignment: .topLeading) {
            ForEach(marks, id: \.self) { t in
                Text(hm(t)).font(.system(size: 12)).foregroundColor(Theme.muted)
                    .offset(x: chanW + CGFloat(t.timeIntervalSince(app.epgWindowStart)) * pps + 4, y: 4)
            }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }

    private func nowLine(pps: CGFloat, height: CGFloat) -> some View {
        let x = chanW + CGFloat(app.now.timeIntervalSince(app.epgWindowStart)) * pps
        return Group {
            if x > chanW { Rectangle().fill(Color.red.opacity(0.85)).frame(width: 2, height: height).offset(x: x) }
        }.allowsHitTesting(false)
    }

    private func row(_ i: Int, _ ch: Channel, pps: CGFloat, gridW: CGFloat) -> some View {
        let start = app.epgWindowStart
        let end = start.addingTimeInterval(span)
        let visible = (app.programs(ch) ?? []).filter { $0.stop > start && $0.start < end }
        let selectedRow = i == app.epgRow
        return HStack(spacing: 2) {
            HStack(spacing: 8) {
                Text("\(ch.number)").font(.system(size: 12)).foregroundColor(Theme.hint).frame(width: 26, alignment: .trailing)
                RemoteImage(url: ch.logo).frame(width: 48, height: 30)
                Text(ch.name).font(.system(size: 14, weight: .bold)).lineLimit(1)
                Spacer(minLength: 0)
            }
            .frame(width: chanW - 2, height: rowH)
            .background(selectedRow ? Color(red: 0.14, green: 0.23, blue: 0.5) : Color(red: 0.05, green: 0.09, blue: 0.26))
            .contentShape(Rectangle())
            .onTapGesture { app.playChannel(ch) }

            ZStack(alignment: .topLeading) {
                if visible.isEmpty {
                    cell(title: "Sin información de guía", time: "", selected: selectedRow, airing: false)
                        .frame(width: gridW - 2, height: rowH)
                        .onTapGesture { app.epgRow = i }
                }
                ForEach(visible, id: \.self) { p in
                    let x1 = max(0, CGFloat(p.start.timeIntervalSince(start)) * pps)
                    let x2 = min(gridW, CGFloat(p.stop.timeIntervalSince(start)) * pps)
                    let isSel = selectedRow && p.start <= app.epgCursor && p.stop > app.epgCursor
                    if x2 - x1 > 2 {
                        cell(title: p.title, time: "\(hm(p.start)) – \(hm(p.stop))", selected: isSel,
                             airing: p.start <= app.now && p.stop > app.now)
                            .frame(width: x2 - x1 - 2, height: rowH)
                            .offset(x: x1)
                            .onTapGesture(count: 2) { app.playChannel(ch) }
                            .onTapGesture {
                                app.epgRow = i
                                app.epgCursor = p.start.addingTimeInterval(1)
                            }
                    }
                }
            }
            .frame(width: gridW, height: rowH, alignment: .topLeading)
            .clipped()
        }
        .foregroundColor(.white)
    }

    private func cell(title: String, time: String, selected: Bool, airing: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.system(size: 14, weight: .bold)).lineLimit(1)
            if !time.isEmpty { Text(time).font(.system(size: 11)).foregroundColor(Theme.muted).lineLimit(1) }
        }
        .padding(.horizontal, 8)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(selected ? Theme.blue : (airing ? Color(red: 0.12, green: 0.17, blue: 0.37) : Color(red: 0.09, green: 0.13, blue: 0.29)))
        .cornerRadius(6)
        .contentShape(Rectangle())
    }
}

// MARK: - Movies / series bar

struct VodBar: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject var mpv = MPV.shared
    @State private var scrub: Double?

    var body: some View {
        if let w = app.vod {
            VStack(alignment: .leading, spacing: 6) {
                Text(w.title).font(.system(size: 24, weight: .bold)).lineLimit(1)
                if !w.subtitle.isEmpty { Text(w.subtitle).font(.system(size: 16)).foregroundColor(Theme.muted).lineLimit(1) }
                Scrubber(position: scrub ?? mpv.position, duration: mpv.duration) { value, done in
                    scrub = value
                    app.scrubbing = !done
                    if done {
                        mpv.command(["seek", String(value), "absolute"])
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { scrub = nil }
                    }
                    app.flashVodBar()
                }
                HStack(spacing: 8) {
                    Text((mpv.paused ? "Pausa   ·   " : "") + app.clock(scrub ?? mpv.position) + (mpv.duration > 0 ? " / " + app.clock(mpv.duration) : ""))
                        .font(.system(size: 16)).monospacedDigit()
                    Spacer()
                    pill("−15 s") { app.seek(-15) }
                    pill(mpv.paused ? "▶  Reproducir" : "❚❚  Pausa") { app.togglePause() }
                    pill("+15 s") { app.seek(15) }
                    Menu {
                        let tracks = mpv.tracks("audio")
                        if tracks.isEmpty { Text("Sin pistas") }
                        ForEach(tracks) { t in Button((t.selected ? "● " : "○ ") + t.title) { mpv.selectAudio(t.id) } }
                    } label: { pillLabel("Audio") }.menuStyle(.borderlessButton).fixedSize()
                    Menu {
                        let tracks = mpv.tracks("sub")
                        Button((tracks.contains { $0.selected } ? "○ " : "● ") + "Desactivados") { mpv.selectSubtitle(nil) }
                        ForEach(tracks) { t in Button((t.selected ? "● " : "○ ") + t.title) { mpv.selectSubtitle(t.id) } }
                    } label: { pillLabel("Subtítulos") }.menuStyle(.borderlessButton).fixedSize()
                    Menu {
                        ForEach([("Normal", "no"), ("16:9", "16:9"), ("4:3", "4:3")], id: \.1) { name, v in
                            Button((app.aspect == v ? "● " : "○ ") + name) { app.setAspect(v) }
                        }
                    } label: { pillLabel("Aspecto") }.menuStyle(.borderlessButton).fixedSize()
                }
            }
            .foregroundColor(.white)
            .padding(.horizontal, 22).padding(.vertical, 14)
            .background(Theme.bar).cornerRadius(14)
            .padding(.horizontal, 40).padding(.bottom, 28)
        }
    }

    private func pillLabel(_ t: String) -> some View {
        Text(t).font(.system(size: 15, weight: .bold)).foregroundColor(.white)
            .padding(.horizontal, 14).padding(.vertical, 8).background(Color.white.opacity(0.13)).cornerRadius(18)
    }

    private func pill(_ t: String, _ a: @escaping () -> Void) -> some View {
        Button(action: { a(); app.flashVodBar() }) { pillLabel(t) }.buttonStyle(.plain)
    }
}

// MARK: - Browser (movies / series)

struct BrowserView: View {
    @EnvironmentObject var app: AppModel
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 14), count: 6)

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                TopTabs(active: app.browserSeries ? .series : .movies)
                Spacer()
                if app.openSeries != nil || app.gridMode != .category {
                    Button { app.browserBack() } label: {
                        Label("Volver", systemImage: "chevron.left").font(.system(size: 15, weight: .bold))
                    }.buttonStyle(.plain).foregroundColor(.white)
                }
            }
            if let s = app.openSeries {
                SeriesDetail(series: s).transition(.move(edge: .trailing).combined(with: .opacity))
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        topSection
                        chips
                        if app.searchOpen {
                            TextField("Buscar en todo el catálogo", text: $app.searchText)
                                .textFieldStyle(.roundedBorder).font(.system(size: 16))
                                .onSubmit { app.runSearch() }
                                .onChange(of: app.searchText) { _ in app.scheduleSearch() }
                        }
                        if !app.gridLabel.isEmpty { Text(app.gridLabel).font(.system(size: 15)).foregroundColor(Theme.muted) }
                        if let m = app.gridMessage {
                            Text(m).font(.system(size: 18)).foregroundColor(Theme.muted).multilineTextAlignment(.center)
                                .frame(maxWidth: .infinity).padding(.vertical, 30)
                        }
                        LazyVGrid(columns: columns, spacing: 18) {
                            ForEach(app.entries) { e in PosterTile(entry: e) }
                        }
                        .id(app.gridLabel)
                        .transition(.opacity)
                    }
                    .padding(.bottom, 30)
                }
            }
        }
        .padding(.horizontal, 26).padding(.top, 38)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Theme.deep)
        .animation(.easeOut(duration: 0.2), value: app.openSeries?.id)
    }

    @ViewBuilder private var topSection: some View {
        let cont = app.continueList
        let later = app.laterList
        if !cont.isEmpty || !later.isEmpty {
            let mode = (app.topMode == 0 && cont.isEmpty) ? 1 : ((app.topMode == 1 && later.isEmpty) ? 0 : app.topMode)
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    if !cont.isEmpty { toggle("Seguir viendo", active: mode == 0) { app.topMode = 0 } }
                    if !later.isEmpty { toggle("Ver más tarde", active: mode == 1) { app.topMode = 1 } }
                }
                LazyVGrid(columns: columns, spacing: 14) {
                    if mode == 0 {
                        ForEach(cont.prefix(5)) { w in PosterTile(entry: .watch(w)) }
                    } else {
                        ForEach(later.prefix(5)) { v in PosterTile(entry: .item(v)) }
                    }
                    Button { app.topMode = mode; app.showAllTop() } label: {
                        VStack {
                            Text("Ver todo\n(\(mode == 0 ? cont.count : later.count))").font(.system(size: 16, weight: .bold))
                                .multilineTextAlignment(.center).foregroundColor(.white)
                                .frame(maxWidth: .infinity).aspectRatio(2 / 3, contentMode: .fit)
                                .background(Theme.card).cornerRadius(8)
                            Spacer(minLength: 0)
                        }
                    }.buttonStyle(.plain)
                }
            }
            .animation(.easeOut(duration: 0.2), value: mode)
        }
    }

    private func toggle(_ t: String, active: Bool, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            Text(t).font(.system(size: 16, weight: .bold)).foregroundColor(active ? .white : Theme.muted)
                .padding(.horizontal, 14).padding(.vertical, 6)
                .background(active ? Theme.chip : Color.white.opacity(0.08)).cornerRadius(16)
        }.buttonStyle(.plain)
    }

    private var chips: some View {
        HStack(spacing: 10) {
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(app.visibleCats) { c in
                            let active = app.gridMode == .category && app.selectedCat[app.browserSeries] == c.id
                            Button { app.selectCategory(c.id) } label: {
                                Text(c.name).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                                    .foregroundColor(active ? .white : Theme.muted)
                                    .padding(.horizontal, 16).padding(.vertical, 7)
                                    .background(active ? Theme.chip : Color.white.opacity(0.08)).cornerRadius(18)
                            }.buttonStyle(.plain).id(c.id)
                        }
                    }
                }
                .onAppear { if let s = app.selectedCat[app.browserSeries] { proxy.scrollTo(s, anchor: .leading) } }
            }
            IconButton(system: "magnifyingglass") {
                if app.searchOpen { app.closeSearch() } else { app.searchOpen = true }
            }
            IconButton(system: "line.3.horizontal.decrease") { app.filterKind = app.browserSeries ? .series : .movies }
        }
    }
}

struct PosterTile: View {
    @EnvironmentObject var app: AppModel
    let entry: GridEntry
    @State private var hover = false

    var body: some View {
        let info = describe()
        VStack(spacing: 5) {
            ZStack(alignment: .bottomTrailing) {
                Theme.card
                if info.poster.isEmpty {
                    Text(info.title).font(.system(size: 13)).foregroundColor(Theme.muted).multilineTextAlignment(.center).padding(6)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    RemoteImage(url: info.poster, mode: .fill)
                }
                if !info.rating.isEmpty {
                    Text(info.rating).font(.system(size: 11, weight: .bold)).foregroundColor(.white)
                        .padding(.horizontal, 6).padding(.vertical, 2).background(Color.black.opacity(0.8)).cornerRadius(5).padding(5)
                }
                if let p = info.progress {
                    GeometryReader { g in
                        Rectangle().fill(Color.yellow).frame(width: g.size.width * p, height: 4)
                            .frame(maxHeight: .infinity, alignment: .bottom)
                    }
                }
            }
            .aspectRatio(2 / 3, contentMode: .fit)
            .clipped()
            .cornerRadius(8)
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(hover ? Theme.blue : Color.clear, lineWidth: 3))
            Text(info.title).font(.system(size: 13, weight: .bold)).foregroundColor(.white).lineLimit(2).multilineTextAlignment(.center)
            if !info.sub.isEmpty { Text(info.sub).font(.system(size: 12)).foregroundColor(Theme.muted).lineLimit(1) }
            Spacer(minLength: 0)
        }
        .scaleEffect(hover ? 1.03 : 1)
        .animation(.easeOut(duration: 0.12), value: hover)
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture { app.open(entry) }
        .contextMenu {
            switch entry {
            case .item(let v):
                Button(v.series ? "Abrir serie" : "Reproducir") { app.open(entry) }
                Button(app.isLater(v) ? "Quitar de Ver más tarde" : "Agregar a Ver más tarde") { app.toggleLater(v) }
            case .watch(let w):
                Button("Continuar") { app.open(entry) }
                Button("Quitar de Seguir viendo") { app.removeFromHistory(w) }
            }
        }
    }

    private func describe() -> (title: String, poster: String, rating: String, sub: String, progress: Double?) {
        switch entry {
        case .item(let v):
            let w = v.series ? nil : app.watchFor(v)
            var sub = v.year
            if w?.finished == true { sub = "Vista" } else if app.isLater(v) { sub = [v.year, "Ver más tarde"].filter { !$0.isEmpty }.joined(separator: " · ") }
            let prog = w.flatMap { $0.duration > 0 && !$0.finished && $0.position > 0 ? $0.position / $0.duration : nil }
            return (v.title, v.poster, String(v.rating.prefix(3)), sub, prog)
        case .watch(let w):
            let sub = w.episode ? String(w.subtitle.components(separatedBy: "  ").first ?? "") : "Quedaste en \(app.clock(w.position))"
            return (w.title, w.poster, "", sub, w.duration > 0 ? w.position / w.duration : nil)
        }
    }
}

struct SeriesDetail: View {
    @EnvironmentObject var app: AppModel
    let series: VodItem

    var body: some View {
        HStack(alignment: .top, spacing: 24) {
            VStack(alignment: .leading, spacing: 8) {
                RemoteImage(url: series.poster, mode: .fill).frame(width: 190, height: 285).background(Theme.card).clipped().cornerRadius(8)
                Text(series.title).font(.system(size: 20, weight: .bold)).foregroundColor(.white).frame(width: 190, alignment: .leading)
                Text(app.seriesInfo).font(.system(size: 14)).foregroundColor(Theme.muted).frame(width: 190, alignment: .leading)
                Button(app.isLater(series) ? "Quitar de Ver más tarde" : "Agregar a Ver más tarde") { app.toggleLater(series) }
                    .buttonStyle(.plain).font(.system(size: 13, weight: .bold)).foregroundColor(Theme.accent)
            }
            VStack(alignment: .leading, spacing: 10) {
                if app.seasons.count > 1 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(app.seasons, id: \.self) { n in
                                Button { withAnimation(.easeOut(duration: 0.15)) { app.season = n } } label: {
                                    Text(n > 0 ? "T\(n)" : "Extras").font(.system(size: 16, weight: .bold))
                                        .foregroundColor(app.season == n ? .white : Theme.muted)
                                        .frame(minWidth: 56).padding(.vertical, 7).padding(.horizontal, 10)
                                        .background(app.season == n ? Theme.chip : Color.white.opacity(0.08)).cornerRadius(16)
                                }.buttonStyle(.plain)
                            }
                        }
                    }
                }
                ScrollView {
                    LazyVStack(spacing: 4) {
                        ForEach(app.seasonEpisodes) { e in episodeRow(e) }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private func episodeRow(_ e: Episode) -> some View {
        let w = app.history.first { $0.id == "e:" + e.id }
        let sub = w.map { $0.finished ? "Visto" : ($0.position > 30 ? "Quedaste en \(app.clock($0.position))" : "") } ?? ""
        return HStack(spacing: 12) {
            Text("\(e.number)").font(.system(size: 16)).foregroundColor(Theme.hint).frame(width: 34, alignment: .trailing)
            RemoteImage(url: e.image, mode: .fill).frame(width: 110, height: 62).background(Theme.card).clipped().cornerRadius(6)
            VStack(alignment: .leading, spacing: 3) {
                Text(e.title).font(.system(size: 16, weight: .bold)).foregroundColor(.white).lineLimit(1)
                if !sub.isEmpty { Text(sub).font(.system(size: 13)).foregroundColor(Theme.muted) }
            }
            Spacer()
        }
        .padding(6)
        .background(Color.white.opacity(0.04)).cornerRadius(8)
        .contentShape(Rectangle())
        .onTapGesture { app.playVod(app.episodeWatch(series, e)) }
    }
}

// MARK: - Dialogs

struct FilterView: View {
    @EnvironmentObject var app: AppModel
    var body: some View {
        ZStack {
            Color.black.opacity(0.45).onTapGesture { app.closeFilter() }
            VStack(alignment: .leading, spacing: 8) {
                Text(title).font(.system(size: 20, weight: .bold))
                Text("Haz clic para mostrar u ocultar. Lo oculto no aparece en listas ni búsquedas.")
                    .font(.system(size: 13)).foregroundColor(Theme.muted)
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(entries, id: \.0) { id, name in
                            let hidden = isHidden(id)
                            Button { toggle(id) } label: {
                                HStack {
                                    Image(systemName: hidden ? "square" : "checkmark.square.fill").foregroundColor(hidden ? Theme.hint : Theme.accent)
                                    Text(name + (hidden ? "   (oculto)" : "")).foregroundColor(hidden ? Theme.hint : .white)
                                    Spacer()
                                }.font(.system(size: 16)).padding(.vertical, 6).contentShape(Rectangle())
                            }.buttonStyle(.plain)
                        }
                    }
                }
                HStack { Spacer(); Button("Listo") { app.closeFilter() }.keyboardShortcut(.defaultAction) }
            }
            .foregroundColor(.white)
            .padding(22).frame(width: 520, height: 560)
            .background(Theme.panel).cornerRadius(16)
        }
    }

    private var title: String {
        switch app.filterKind {
        case .live?: return "Mostrar u ocultar canales"
        case .series?: return "Mostrar u ocultar categorías de series"
        default: return "Mostrar u ocultar categorías de películas"
        }
    }

    private var entries: [(String, String)] {
        if app.filterKind == .live { return app.channels.map { ($0.name, $0.name) } }
        return (app.categories[app.browserSeries] ?? []).map { ($0.id, $0.name) }
    }

    private func isHidden(_ id: String) -> Bool {
        app.filterKind == .live ? app.hiddenChannels.contains(id) : app.hiddenCats.contains(id)
    }

    private func toggle(_ id: String) {
        if app.filterKind == .live { app.toggleHidden(channel: id) } else { app.toggleHidden(category: id) }
    }
}

struct SetupView: View {
    @EnvironmentObject var app: AppModel
    var body: some View {
        ZStack {
            Color.black.opacity(0.6)
            VStack(spacing: 14) {
                Text("AerioTV Lite").font(.system(size: 30, weight: .bold))
                Text(app.setupMessage).font(.system(size: 16)).foregroundColor(Theme.muted).multilineTextAlignment(.center)
                TextField("https://…", text: $app.listURL).textFieldStyle(.roundedBorder).font(.system(size: 18)).frame(width: 520)
                    .onSubmit { app.saveSetup() }
                Button("Guardar y conectar") { app.saveSetup() }.keyboardShortcut(.defaultAction)
            }
            .foregroundColor(.white)
            .padding(32).background(Theme.panel).cornerRadius(16)
        }
    }
}

struct AccountView: View {
    @EnvironmentObject var app: AppModel
    @State private var server = AppModel.defaultServer
    @State private var user = ""
    @State private var pass = ""

    var body: some View {
        ZStack {
            Color.black.opacity(0.6).onTapGesture { app.back() }
            VStack(spacing: 12) {
                Text("Películas y series").font(.system(size: 26, weight: .bold))
                if !app.accountMessage.isEmpty {
                    Text(app.accountMessage).font(.system(size: 15)).foregroundColor(Theme.muted).multilineTextAlignment(.center)
                } else if app.playlistAccount != nil {
                    Text("Tu lista ya trae la cuenta; lo que escribas aquí solo se usa si la quitas de la lista.")
                        .font(.system(size: 15)).foregroundColor(Theme.muted).multilineTextAlignment(.center)
                }
                TextField("Servidor (https://…)", text: $server).textFieldStyle(.roundedBorder)
                TextField("Usuario", text: $user).textFieldStyle(.roundedBorder)
                SecureField("Clave", text: $pass).textFieldStyle(.roundedBorder).onSubmit { save() }
                HStack {
                    Button("Cancelar") { app.back() }
                    Button("Guardar") { save() }.keyboardShortcut(.defaultAction)
                }
            }
            .font(.system(size: 17))
            .foregroundColor(.white)
            .frame(width: 520)
            .padding(30).background(Theme.panel).cornerRadius(16)
        }
        .onAppear {
            if let a = Store.load("xtream", XtreamAccount.self) { server = a.base; user = a.user; pass = a.pass }
        }
    }

    private func save() {
        app.saveAccount(XtreamAccount(base: server.trimmingCharacters(in: .whitespaces),
                                      user: user.trimmingCharacters(in: .whitespaces),
                                      pass: pass.trimmingCharacters(in: .whitespaces)), then: app.pendingSeries)
    }
}


// MARK: - Timeline, zapping card, top buttons

/// Draggable timeline: click or drag anywhere on it; the seek happens when the mouse is released.
struct Scrubber: View {
    let position: Double
    let duration: Double
    let onChange: (_ value: Double, _ done: Bool) -> Void

    var body: some View {
        GeometryReader { g in
            let frac = duration > 0 ? max(0, min(1, position / duration)) : 0
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.25)).frame(height: 6)
                Capsule().fill(Color.yellow).frame(width: g.size.width * frac, height: 6)
                Circle().fill(Color.white).frame(width: 16, height: 16)
                    .offset(x: g.size.width * frac - 8)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { v in
                    guard duration > 0 else { return }
                    onChange(max(0, min(1, v.location.x / g.size.width)) * duration, false)
                }
                .onEnded { v in
                    guard duration > 0 else { return }
                    onChange(max(0, min(1, v.location.x / g.size.width)) * duration, true)
                })
        }
        .frame(height: 22)
    }
}

/// Channel card shown while dragging to the next/previous channel and until it starts.
struct ZapCard: View {
    @EnvironmentObject var app: AppModel
    let index: Int
    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.06, green: 0.1, blue: 0.27), Color(red: 0.02, green: 0.04, blue: 0.12)],
                           startPoint: .top, endPoint: .bottom)
            if app.channels.indices.contains(index) {
                let ch = app.channels[index]
                let now = GuideParser.nowNext(app.programs(ch), at: app.now).0
                VStack(spacing: 10) {
                    RemoteImage(url: ch.logo).frame(width: 220, height: 124)
                    Text("\(ch.number)   \(ch.name)").font(.system(size: 30, weight: .bold)).foregroundColor(.white)
                    if let p = now {
                        Text("\(hm(p.start)) – \(hm(p.stop))   \(p.title)").font(.system(size: 18)).foregroundColor(Theme.muted)
                    }
                }
            }
        }
        .allowsHitTesting(false)
    }
}

struct AirPlayButton: NSViewRepresentable {
    let player: AVPlayer?
    func makeNSView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.isRoutePickerButtonBordered = false
        v.setRoutePickerButtonColor(.white, for: .normal)
        v.setRoutePickerButtonColor(NSColor(Theme.accent), for: .active)
        v.player = player
        return v
    }
    func updateNSView(_ v: AVRoutePickerView, context: Context) { v.player = player }
}

struct TopButtons: View {
    @EnvironmentObject var app: AppModel
    var body: some View {
        HStack {
            if app.vod == nil {
                Button { app.togglePanel() } label: {
                    Label("Canales", systemImage: "line.3.horizontal").font(.system(size: 16, weight: .bold))
                        .foregroundColor(.white).padding(.horizontal, 16).padding(.vertical, 9)
                        .background(Theme.bar).clipShape(Capsule())
                }.buttonStyle(.plain)
            }
            Spacer()
            Group {
                if app.vod != nil, let p = app.airplayPlayer {
                    AirPlayButton(player: p).frame(width: 26, height: 26)
                } else {
                    Button { app.airplayNotice = true } label: {
                        Image(systemName: "airplayvideo").font(.system(size: 18, weight: .semibold)).foregroundColor(.white)
                    }.buttonStyle(.plain)
                }
            }
            .frame(width: 46, height: 46).background(Theme.bar).clipShape(Circle())
            Button { app.togglePiP() } label: {
                Image(systemName: "pip.enter").font(.system(size: 18, weight: .semibold)).foregroundColor(.white)
                    .frame(width: 46, height: 46).background(Theme.bar).clipShape(Circle())
            }.buttonStyle(.plain)
        }
        .padding(.horizontal, 32).padding(.top, 40)
    }
}

struct AirPlayNotice: View {
    @EnvironmentObject var app: AppModel
    var body: some View {
        ZStack {
            Color.black.opacity(0.45).onTapGesture { app.airplayNotice = false }
            VStack(alignment: .leading, spacing: 12) {
                Text("Ver en el Apple TV").font(.system(size: 20, weight: .bold))
                Text("Las películas y series se envían al Apple TV con el botón AirPlay que aparece mientras se reproducen.\n\nLos canales en vivo usan un formato que AirPlay no acepta. Para verlos en el Apple TV, abre el Centro de control del Mac → Duplicar pantalla → Apple TV, y pon AerioTV Lite en pantalla completa.")
                    .font(.system(size: 15)).foregroundColor(Theme.muted).fixedSize(horizontal: false, vertical: true)
                HStack { Spacer(); Button("Entendido") { app.airplayNotice = false }.keyboardShortcut(.defaultAction) }
            }
            .foregroundColor(.white)
            .padding(24).frame(width: 480)
            .background(Theme.panel).cornerRadius(16)
        }
    }
}
