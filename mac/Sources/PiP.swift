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

    var supported: Bool {
        guard let cls = Self.pipClass else { return false }
        return cls.instancesRespond(to: NSSelectorFromString("presentViewControllerAsPictureInPicture:"))
    }

    /// Sets a property of the system panel only if this macOS has it (an unknown key would throw).
    private func set(_ pip: NSViewController, _ key: String, _ value: Any?) {
        let setter = "set" + key.prefix(1).uppercased() + key.dropFirst() + ":"
        if pip.responds(to: NSSelectorFromString(setter)) { pip.setValue(value, forKey: key) }
    }

    func toggle(window: NSWindow) {
        if active { stop() } else { start(window: window) }
    }

    func start(window: NSWindow) {
        guard !active, supported, let cls = Self.pipClass, let video = VideoNSView.current else { return }
        self.window = window
        let canvas = video.canvas

        let pip = cls.init(nibName: nil, bundle: nil)
        set(pip, "delegate", self)
        set(pip, "userCanResize", true)
        set(pip, "playing", !MPV.shared.paused)
        set(pip, "aspectRatio", NSValue(size: aspect()))

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
        // hide the window only if the video really went to the floating panel
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self, weak window] in
            guard let self = self, self.active else { return }
            if let w = canvas.window, w !== window { window?.orderOut(nil) } else { self.restore() }
        }
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
        set(pip, "replacementWindow", window)
        if let video = VideoNSView.current {
            set(pip, "replacementRect", NSValue(rect: video.convert(video.bounds, to: nil)))
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
        set(pip, "playing", true)
    }

    @objc func pipActionPause(_ pip: NSViewController) {
        MPV.shared.setPaused(true)
        set(pip, "playing", false)
    }

    @objc func pipActionStop(_ pip: NSViewController) {
        MPV.shared.setPaused(true)
        set(pip, "playing", false)
    }
}
