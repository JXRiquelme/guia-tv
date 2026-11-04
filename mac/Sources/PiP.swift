import Cocoa
import AVKit
import CoreMedia
import CoreVideo

/// Real picture-in-picture for the mpv video: while active, mpv renders in software into
/// pixel buffers that feed an AVSampleBufferDisplayLayer driven by AVPictureInPictureController.
final class PiPController: NSObject, AVPictureInPictureControllerDelegate, AVPictureInPictureSampleBufferPlaybackDelegate {
    static let shared = PiPController()

    private let displayLayer = AVSampleBufferDisplayLayer()
    private var hostView: NSView?
    private var pip: AVPictureInPictureController?
    private var possibleObservation: NSKeyValueObservation?
    private var swContext: OpaquePointer?
    private var timer: Timer?
    private weak var window: NSWindow?
    private var renderSize = CGSize(width: 960, height: 540)
    private var startTimeout: DispatchWorkItem?

    private(set) var active = false
    /// Starting or running (the window may be hidden).
    var busy: Bool { active || pip != nil }
    var isLive = true
    var onStateChange: ((Bool) -> Void)?

    var supported: Bool { AVPictureInPictureController.isPictureInPictureSupported() }

    func toggle(window: NSWindow) {
        if active || pip != nil { stop() } else { start(window: window) }
    }

    func start(window: NSWindow) {
        guard supported, pip == nil, let content = window.contentView else { return }
        self.window = window

        // Host the display layer inside the window, under the normal video.
        let host = NSView(frame: NSRect(x: 0, y: 0, width: 320, height: 180))
        host.wantsLayer = true
        displayLayer.videoGravity = .resizeAspect
        displayLayer.frame = host.bounds
        displayLayer.backgroundColor = NSColor.black.cgColor
        host.layer?.addSublayer(displayLayer)
        content.addSubview(host, positioned: .below, relativeTo: nil)
        hostView = host

        // Hand the renderer over from the window to the software renderer.
        let mpv = MPV.shared
        VideoLayer.current?.releaseRenderer()
        mpv.setProperty("hwdec", "auto-copy-safe")
        let api = strdup("sw")
        var params = [
            mpv_render_param(type: MPV_RENDER_PARAM_API_TYPE, data: UnsafeMutableRawPointer(api)),
            mpv_render_param(type: MPV_RENDER_PARAM_INVALID, data: nil),
        ]
        mpv_render_context_create(&swContext, mpv.handle, &params)
        free(api)
        guard swContext != nil else { cleanup(); return }

        timer = Timer.scheduledTimer(withTimeInterval: 1.0 / 30.0, repeats: true) { [weak self] _ in self?.renderFrame(force: false) }
        renderFrame(force: true)

        let source = AVPictureInPictureController.ContentSource(sampleBufferDisplayLayer: displayLayer, playbackDelegate: self)
        let controller = AVPictureInPictureController(contentSource: source)
        controller.delegate = self
        pip = controller
        possibleObservation = controller.observe(\.isPictureInPicturePossible, options: [.initial, .new]) { [weak self] c, _ in
            DispatchQueue.main.async {
                guard let self = self, c.isPictureInPicturePossible, !self.active, !c.isPictureInPictureActive else { return }
                c.startPictureInPicture()
            }
        }
        // If the system never allows it, give the video back to the window.
        let timeout = DispatchWorkItem { [weak self] in
            guard let self = self, !self.active else { return }
            self.cleanup()
        }
        startTimeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 4, execute: timeout)
    }

    func stop() {
        if let p = pip, p.isPictureInPictureActive { p.stopPictureInPicture() } else { cleanup() }
    }

    private func renderFrame(force: Bool) {
        guard let ctx = swContext else { return }
        let flags = mpv_render_context_update(ctx)
        guard force || (flags & UInt64(MPV_RENDER_UPDATE_FRAME.rawValue)) != 0 else { return }

        let w = Int(renderSize.width), h = Int(renderSize.height)
        var pixelBuffer: CVPixelBuffer?
        let attrs: [String: Any] = [kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any]()]
        CVPixelBufferCreate(kCFAllocatorDefault, w, h, kCVPixelFormatType_32BGRA, attrs as CFDictionary, &pixelBuffer)
        guard let pb = pixelBuffer else { return }
        CVPixelBufferLockBaseAddress(pb, [])
        if let base = CVPixelBufferGetBaseAddress(pb) {
            var size: [Int32] = [Int32(w), Int32(h)]
            var stride = CVPixelBufferGetBytesPerRow(pb)
            let format = strdup("bgr0")
            size.withUnsafeMutableBufferPointer { sp in
                withUnsafeMutablePointer(to: &stride) { stp in
                    var params = [
                        mpv_render_param(type: MPV_RENDER_PARAM_SW_SIZE, data: UnsafeMutableRawPointer(sp.baseAddress)),
                        mpv_render_param(type: MPV_RENDER_PARAM_SW_FORMAT, data: UnsafeMutableRawPointer(format)),
                        mpv_render_param(type: MPV_RENDER_PARAM_SW_STRIDE, data: UnsafeMutableRawPointer(stp)),
                        mpv_render_param(type: MPV_RENDER_PARAM_SW_POINTER, data: base),
                        mpv_render_param(type: MPV_RENDER_PARAM_INVALID, data: nil),
                    ]
                    mpv_render_context_render(ctx, &params)
                }
            }
            free(format)
        }
        CVPixelBufferUnlockBaseAddress(pb, [])

        var formatDesc: CMVideoFormatDescription?
        CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: pb, formatDescriptionOut: &formatDesc)
        guard let fd = formatDesc else { return }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
                                        decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: pb, formatDescription: fd,
                                                 sampleTiming: &timing, sampleBufferOut: &sample)
        guard let sb = sample else { return }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: true), CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        if displayLayer.status == .failed { displayLayer.flush() }
        displayLayer.enqueue(sb)
    }

    private func cleanup() {
        startTimeout?.cancel()
        timer?.invalidate()
        timer = nil
        possibleObservation = nil
        pip = nil
        if let ctx = swContext {
            mpv_render_context_free(ctx)
            swContext = nil
        }
        displayLayer.flushAndRemoveImage()
        hostView?.removeFromSuperview()
        hostView = nil
        MPV.shared.setProperty("hwdec", "auto-safe")
        VideoLayer.current?.restoreRenderer()
        if active {
            active = false
            onStateChange?(false)
        }
    }

    // MARK: AVPictureInPictureControllerDelegate

    func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
        startTimeout?.cancel()
        active = true
        onStateChange?(true)
        window?.orderOut(nil)
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController,
                                    restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        completionHandler(true)
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        window?.makeKeyAndOrderFront(nil)
        cleanup()
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        cleanup()
    }

    // MARK: AVPictureInPictureSampleBufferPlaybackDelegate

    func pictureInPictureController(_ controller: AVPictureInPictureController, setPlaying playing: Bool) {
        MPV.shared.setPaused(!playing)
    }

    func pictureInPictureControllerTimeRangeForPlayback(_ controller: AVPictureInPictureController) -> CMTimeRange {
        let d = MPV.shared.duration
        if isLive || d <= 0 { return CMTimeRange(start: .negativeInfinity, duration: .positiveInfinity) }
        return CMTimeRange(start: .zero, duration: CMTime(seconds: d, preferredTimescale: 600))
    }

    func pictureInPictureControllerIsPlaybackPaused(_ controller: AVPictureInPictureController) -> Bool {
        MPV.shared.paused
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController, didTransitionToRenderSize newRenderSize: CMVideoDimensions) {
        let w = max(320, min(1280, Int(newRenderSize.width)))
        let h = max(180, min(720, Int(newRenderSize.height)))
        renderSize = CGSize(width: w - w % 2, height: h - h % 2)
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController, skipByInterval skipInterval: CMTime,
                                    completion completionHandler: @escaping () -> Void) {
        MPV.shared.seek(by: skipInterval.seconds)
        completionHandler()
    }
}
