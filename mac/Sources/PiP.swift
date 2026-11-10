import Cocoa

/// Picture in picture the way QuickTime, Safari and IINA do it: the video view itself moves into
/// the system's floating PiP panel (macOS's PIP.framework) and back. mpv keeps rendering with the
/// same OpenGL renderer the whole time, so there is no black picture and no pause when it moves.
final class PiPController: NSObject {
    static let shared = PiPController()

    private var pip: NSViewController?          // PIPViewController (system)
    private var holder: NSViewController?       // hosts the video while it floats
    private weak var window: NSWindow?
    private var restored = true

    private(set) var active = false
    /// Floating (the main window is hidden meanwhile).
    var busy: Bool { active }
    var isLive = true
    var onStateChange: ((Bool) -> Void)?

    private static let pipClass: NSViewController.Type? = {
        _ = dlopen("/System/Library/PrivateFrameworks/PIP.framework/PIP", RTLD_LAZY)
        return NSClassFromString("PIPViewController") as? NSViewController.Type
    }()

    var supported: Bool { Self.pipClass != nil }

    func toggle(window: NSWindow) {
        if active { stop() } else { start(window: window) }
    }

    func start(window: NSWindow) {
        guard !active, let cls = Self.pipClass, let video = VideoNSView.current else { return }
        self.window = window
        let canvas = video.canvas

        let pip = cls.init(nibName: nil, bundle: nil)
        pip.setValue(self, forKey: "delegate")
        pip.setValue("AerioTV Lite", forKey: "name")
        pip.setValue(true, forKey: "userCanResize")
        pip.setValue(!MPV.shared.paused, forKey: "playing")
        pip.setValue(NSValue(size: aspect()), forKey: "aspectRatio")

        // the same video view (and its renderer) moves into the floating panel
        let holder = NSViewController()
        canvas.removeFromSuperview()
        canvas.frame = NSRect(origin: .zero, size: video.bounds.size)
        holder.view = canvas
        self.pip = pip
        self.holder = holder
        restored = false
        active = true
        pip.perform(NSSelectorFromString("presentViewControllerAsPictureInPicture:"), with: holder)
        onStateChange?(true)
        window.orderOut(nil)
    }

    /// Back to the window (Dock icon, menu, or the panel's own button).
    func stop() {
        guard active, let pip = pip, let holder = holder else { return }
        prepareReturn(pip)
        pip.dismiss(holder)
        // if the panel doesn't report its closing, bring the video back anyway
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in self?.restore() }
    }

    private func aspect() -> NSSize {
        let w = Double(MPV.shared.getString("width") ?? "") ?? 16
        let h = Double(MPV.shared.getString("height") ?? "") ?? 9
        return NSSize(width: max(w, 1), height: max(h, 1))
    }

    private func prepareReturn(_ pip: NSViewController) {
        guard let window = window else { return }
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        // the panel animates back into the video's place
        pip.setValue(window, forKey: "replacementWindow")
        if let video = VideoNSView.current {
            pip.setValue(NSValue(rect: video.convert(video.bounds, to: nil)), forKey: "replacementRect")
        }
    }

    private func restore() {
        guard !restored else { return }
        restored = true
        if let video = VideoNSView.current { video.attach() }
        pip = nil
        holder = nil
        window?.makeKeyAndOrderFront(nil)
        if active {
            active = false
            onStateChange?(false)
        }
    }

    // MARK: PIPViewControllerDelegate (informal protocol of the system panel)

    @objc func pipShouldClose(_ pip: NSViewController) -> Bool { true }

    @objc func pipWillClose(_ pip: NSViewController) { prepareReturn(pip) }

    @objc func pipDidClose(_ pip: NSViewController) { restore() }

    @objc func pipActionPlay(_ pip: NSViewController) {
        MPV.shared.setPaused(false)
        pip.setValue(true, forKey: "playing")
    }

    @objc func pipActionPause(_ pip: NSViewController) {
        MPV.shared.setPaused(true)
        pip.setValue(false, forKey: "playing")
    }

    @objc func pipActionStop(_ pip: NSViewController) {
        MPV.shared.setPaused(true)
        pip.setValue(false, forKey: "playing")
    }
}
