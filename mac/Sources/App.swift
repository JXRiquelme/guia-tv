import SwiftUI
import AppKit

@main
struct AerioLiteApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @StateObject private var app = AppModel()

    init() {
        URLCache.shared = URLCache(memoryCapacity: 64 * 1024 * 1024, diskCapacity: 400 * 1024 * 1024)
    }

    var body: some Scene {
        WindowGroup("AerioTV Lite") {
            RootView()
                .environmentObject(app)
                .frame(minWidth: 960, minHeight: 560)
        }
        .windowStyle(.hiddenTitleBar)
        .commands {
            CommandGroup(replacing: .newItem) {}
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    /// The window is hidden while picture in picture plays: that must not quit the app.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        !PiPController.shared.busy
    }

    /// Clicking the Dock icon during picture in picture brings the video back to the window.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if PiPController.shared.busy { PiPController.shared.stop(); return false }
        return true
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.appearance = NSAppearance(named: .darkAqua)
        if let w = NSApp.windows.first {
            w.backgroundColor = .black
            w.titlebarAppearsTransparent = true
            w.styleMask.insert(.fullSizeContentView)
            w.setContentSize(NSSize(width: 1280, height: 720))
            w.center()
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        MPV.shared.stop()
    }
}
