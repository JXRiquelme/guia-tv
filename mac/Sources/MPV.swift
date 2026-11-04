import Cocoa
import QuartzCore
import CoreVideo
import OpenGL.GL3

// end-file "reason" is an int or an enum depending on the libmpv version
private func code(_ v: Int32) -> Int { Int(v) }
private func code(_ v: mpv_end_file_reason) -> Int { Int(v.rawValue) }

/// Audio / subtitle track reported by mpv.
struct Track: Identifiable, Hashable {
    let id: Int
    let type: String
    let title: String
    let selected: Bool
}

/// Thin wrapper around libmpv. Everything is used from the main thread.
final class MPV: ObservableObject {
    static let shared = MPV()
    static let userAgent = "VLC/3.0.20 LibVLC/3.0.20"

    let handle: OpaquePointer

    @Published var position: Double = 0
    @Published var duration: Double = 0
    @Published var paused = false
    @Published var buffering = false

    /// error = playback failed, eof = reached the end of a movie.
    var onEndFile: ((_ error: Bool, _ eof: Bool) -> Void)?
    var onReady: (() -> Void)?
    var onFirstFrame: (() -> Void)?

    private init() {
        handle = mpv_create()
        let options: [(String, String)] = [
            ("vo", "libmpv"),
            ("hwdec", "auto-safe"),
            ("user-agent", MPV.userAgent),
            ("network-timeout", "15"),
            ("cache", "yes"),
            ("demuxer-max-bytes", "64MiB"),
            ("demuxer-readahead-secs", "20"),
            ("keep-open", "no"),
            ("idle", "yes"),
            ("input-default-bindings", "no"),
            ("input-vo-keyboard", "no"),
            ("osc", "no"),
            ("osd-level", "0"),
            ("terminal", "no"),
            ("alang", "es,spa,lat,es-419"),
            ("slang", "es,spa,lat,es-419"),
            ("sub-font-size", "46"),
            ("sub-border-size", "2.5"),
            ("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5"),
        ]
        for (k, v) in options { mpv_set_option_string(handle, k, v) }
        mpv_initialize(handle)
        mpv_observe_property(handle, 0, "time-pos", MPV_FORMAT_DOUBLE)
        mpv_observe_property(handle, 0, "duration", MPV_FORMAT_DOUBLE)
        mpv_observe_property(handle, 0, "pause", MPV_FORMAT_FLAG)
        mpv_observe_property(handle, 0, "paused-for-cache", MPV_FORMAT_FLAG)
        mpv_set_wakeup_callback(handle, { ctx in
            guard let ctx = ctx else { return }
            let me = Unmanaged<MPV>.fromOpaque(ctx).takeUnretainedValue()
            DispatchQueue.main.async { me.drainEvents() }
        }, Unmanaged.passUnretained(self).toOpaque())
    }

    // MARK: commands

    func command(_ args: [String]) {
        var cargs: [UnsafePointer<CChar>?] = args.map { UnsafePointer(strdup($0)) }
        cargs.append(nil)
        mpv_command(handle, &cargs)
        for p in cargs { if let p = p { free(UnsafeMutablePointer(mutating: p)) } }
    }

    func setProperty(_ name: String, _ value: String) {
        mpv_set_property_string(handle, name, value)
    }

    func getString(_ name: String) -> String? {
        guard let c = mpv_get_property_string(handle, name) else { return nil }
        defer { mpv_free(c) }
        return String(cString: c)
    }

    func load(_ url: String, start: Double = 0) {
        setProperty("start", start > 1 ? String(Int(start)) : "none")
        setProperty("pause", "no")
        position = 0
        duration = 0
        buffering = true
        command(["loadfile", url, "replace"])
    }

    func stop() {
        command(["stop"])
        buffering = false
    }

    func togglePause() { setProperty("pause", paused ? "no" : "yes") }
    func setPaused(_ p: Bool) { setProperty("pause", p ? "yes" : "no") }

    func seek(by seconds: Double) { command(["seek", String(seconds), "relative"]) }

    /// "16:9", "4:3" or "no" (original).
    func setAspect(_ value: String) { setProperty("video-aspect-override", value) }

    func tracks(_ type: String) -> [Track] {
        let count = Int(getString("track-list/count") ?? "0") ?? 0
        var out: [Track] = []
        for i in 0..<count {
            guard getString("track-list/\(i)/type") == type,
                  let id = Int(getString("track-list/\(i)/id") ?? "") else { continue }
            let lang = getString("track-list/\(i)/lang") ?? ""
            let title = getString("track-list/\(i)/title") ?? ""
            let channels = getString("track-list/\(i)/demux-channel-count") ?? ""
            var parts: [String] = []
            if !title.isEmpty { parts.append(title) }
            if !lang.isEmpty, lang != "und" {
                let name = Locale(identifier: "es").localizedString(forLanguageCode: lang) ?? lang
                parts.append(name.prefix(1).uppercased() + name.dropFirst())
            }
            if let ch = Int(channels), ch > 0 { parts.append(ch == 2 ? "estéreo" : ch == 6 ? "5.1" : ch == 8 ? "7.1" : "\(ch) canales") }
            var seen = Set<String>()
            let label = parts.filter { seen.insert($0).inserted }.joined(separator: " · ")
            out.append(Track(id: id, type: type, title: label.isEmpty ? "Pista \(out.count + 1)" : label,
                             selected: getString("track-list/\(i)/selected") == "yes"))
        }
        return out
    }

    func selectAudio(_ id: Int) { setProperty("aid", String(id)) }
    func selectSubtitle(_ id: Int?) { setProperty("sid", id.map(String.init) ?? "no") }

    /// General language settings, applied before each file (also drops the previous manual choice).
    func applyLanguages(audio: String, subtitles: String) {
        let langs = ["es": "es,spa,lat,es-419", "en": "en,eng"]
        setProperty("alang", langs[audio] ?? "")
        setProperty("aid", "auto")
        if subtitles == "off" {
            setProperty("sid", "no")
        } else {
            setProperty("slang", langs[subtitles] ?? "")
            setProperty("sid", "auto")
        }
    }

    // MARK: events

    private func drainEvents() {
        while true {
            guard let ev = mpv_wait_event(handle, 0)?.pointee else { break }
            if ev.event_id == MPV_EVENT_NONE { break }
            switch ev.event_id {
            case MPV_EVENT_PROPERTY_CHANGE:
                guard let data = ev.data else { break }
                let prop = data.assumingMemoryBound(to: mpv_event_property.self).pointee
                let name = String(cString: prop.name)
                if prop.format == MPV_FORMAT_DOUBLE, let v = prop.data?.assumingMemoryBound(to: Double.self).pointee {
                    if name == "time-pos" { position = v }
                    if name == "duration" { duration = v }
                } else if prop.format == MPV_FORMAT_FLAG, let v = prop.data?.assumingMemoryBound(to: Int32.self).pointee {
                    if name == "pause" { paused = v != 0 }
                    if name == "paused-for-cache" { buffering = v != 0 }
                }
            case MPV_EVENT_PLAYBACK_RESTART:
                buffering = false
                onReady?()
            case MPV_EVENT_END_FILE:
                guard let data = ev.data else { break }
                let end = data.assumingMemoryBound(to: mpv_event_end_file.self).pointee
                if code(end.reason) == code(MPV_END_FILE_REASON_ERROR) {
                    onEndFile?(true, false)
                } else if code(end.reason) == code(MPV_END_FILE_REASON_EOF) {
                    onEndFile?(false, true)
                }
            default:
                break
            }
        }
    }
}

// MARK: - Video output (OpenGL render API inside a CAOpenGLLayer)

private let glProcAddress: @convention(c) (UnsafeMutableRawPointer?, UnsafePointer<CChar>?) -> UnsafeMutableRawPointer? = { _, name in
    guard let name = name else { return nil }
    let symbol = CFStringCreateWithCString(kCFAllocatorDefault, name, CFStringBuiltInEncodings.ASCII.rawValue)
    let bundle = CFBundleGetBundleWithIdentifier("com.apple.opengl" as CFString)
    return CFBundleGetFunctionPointerForName(bundle, symbol)
}

final class VideoLayer: CAOpenGLLayer {
    /// The layer on screen (there is only one).
    static weak var current: VideoLayer?
    private var renderContext: OpaquePointer?
    private var glContext: CGLContextObj?
    private var firstFrameReported = false
    /// While picture-in-picture renders the video elsewhere, this layer stays black.
    private(set) var suspended = false

    override init() {
        super.init()
        isAsynchronous = false
        needsDisplayOnBoundsChange = true
        backgroundColor = NSColor.black.cgColor
    }

    override init(layer: Any) {
        super.init(layer: layer)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func copyCGLPixelFormat(forDisplayMask mask: UInt32) -> CGLPixelFormatObj {
        let attrs: [CGLPixelFormatAttribute] = [
            kCGLPFAOpenGLProfile, CGLPixelFormatAttribute(UInt32(kCGLOGLPVersion_3_2_Core.rawValue)),
            kCGLPFADoubleBuffer,
            kCGLPFAAccelerated,
            kCGLPFAAllowOfflineRenderers,
            CGLPixelFormatAttribute(0),
        ]
        var pix: CGLPixelFormatObj?
        var count: GLint = 0
        CGLChoosePixelFormat(attrs, &pix, &count)
        return pix!
    }

    override func copyCGLContext(forPixelFormat pf: CGLPixelFormatObj) -> CGLContextObj {
        let ctx = super.copyCGLContext(forPixelFormat: pf)
        glContext = ctx
        VideoLayer.current = self
        CGLSetCurrentContext(ctx)
        if !suspended { createRenderContext() }
        return ctx
    }

    private func createRenderContext() {
        guard renderContext == nil else { return }
        var initParams = mpv_opengl_init_params()
        initParams.get_proc_address = glProcAddress
        initParams.get_proc_address_ctx = nil
        let api = strdup("opengl")
        defer { free(api) }
        withUnsafeMutablePointer(to: &initParams) { ip in
            var params = [
                mpv_render_param(type: MPV_RENDER_PARAM_API_TYPE, data: UnsafeMutableRawPointer(api)),
                mpv_render_param(type: MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, data: UnsafeMutableRawPointer(ip)),
                mpv_render_param(type: MPV_RENDER_PARAM_INVALID, data: nil),
            ]
            mpv_render_context_create(&renderContext, MPV.shared.handle, &params)
        }
        mpv_render_context_set_update_callback(renderContext, { ctx in
            guard let ctx = ctx else { return }
            let layer = Unmanaged<VideoLayer>.fromOpaque(ctx).takeUnretainedValue()
            DispatchQueue.main.async { layer.setNeedsDisplay() }
        }, Unmanaged.passUnretained(self).toOpaque())
    }

    override func canDraw(inCGLContext ctx: CGLContextObj, pixelFormat pf: CGLPixelFormatObj,
                          forLayerTime t: CFTimeInterval, displayTime ts: UnsafePointer<CVTimeStamp>?) -> Bool {
        true
    }

    override func draw(inCGLContext ctx: CGLContextObj, pixelFormat pf: CGLPixelFormatObj,
                       forLayerTime t: CFTimeInterval, displayTime ts: UnsafePointer<CVTimeStamp>?) {
        glClearColor(0, 0, 0, 1)
        glClear(GLbitfield(GL_COLOR_BUFFER_BIT))
        guard let rc = renderContext else { return }
        var fbo: GLint = 0
        glGetIntegerv(GLenum(GL_DRAW_FRAMEBUFFER_BINDING), &fbo)
        var dims = [GLint](repeating: 0, count: 4)
        glGetIntegerv(GLenum(GL_VIEWPORT), &dims)
        var target = mpv_opengl_fbo()
        target.fbo = Int32(fbo)
        target.w = Int32(dims[2])
        target.h = Int32(dims[3])
        target.internal_format = 0
        var flip: CInt = 1
        withUnsafeMutablePointer(to: &target) { tp in
            withUnsafeMutablePointer(to: &flip) { fp in
                var params = [
                    mpv_render_param(type: MPV_RENDER_PARAM_OPENGL_FBO, data: UnsafeMutableRawPointer(tp)),
                    mpv_render_param(type: MPV_RENDER_PARAM_FLIP_Y, data: UnsafeMutableRawPointer(fp)),
                    mpv_render_param(type: MPV_RENDER_PARAM_INVALID, data: nil),
                ]
                mpv_render_context_render(rc, &params)
            }
        }
        glFlush()
        if MPV.shared.position > 0, !firstFrameReported {
            firstFrameReported = true
            DispatchQueue.main.async { MPV.shared.onFirstFrame?() }
        }
    }

    /// libmpv allows one renderer at a time: picture-in-picture takes it over.
    func releaseRenderer() {
        suspended = true
        guard let rc = renderContext else { return }
        if let ctx = glContext { CGLSetCurrentContext(ctx) }
        mpv_render_context_set_update_callback(rc, nil, nil)
        mpv_render_context_free(rc)
        renderContext = nil
        setNeedsDisplay()
    }

    func restoreRenderer() {
        suspended = false
        if let ctx = glContext {
            CGLSetCurrentContext(ctx)
            createRenderContext()
        }
        setNeedsDisplay()
    }

    /// Called when a new file starts so the next first frame is reported again.
    func resetFirstFrame() { firstFrameReported = false }
}

final class VideoNSView: NSView {
    let videoLayer = VideoLayer()

    override init(frame: NSRect) {
        super.init(frame: frame)
        layer = videoLayer
        wantsLayer = true
        videoLayer.contentsScale = NSScreen.main?.backingScaleFactor ?? 2
        layerContentsRedrawPolicy = .duringViewResize
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        videoLayer.contentsScale = window?.backingScaleFactor ?? 2
        videoLayer.setNeedsDisplay()
    }

    // Clicks go to the SwiftUI overlay above, not here.
    override func hitTest(_ point: NSPoint) -> NSView? { nil }
}
