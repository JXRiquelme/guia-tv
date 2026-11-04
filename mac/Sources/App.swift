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
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.appearance = NSAppearance(named: .darkAqua)
        if let w = NSApp.windows.first {
            w.backgroundColor = .black
            w.setContentSize(NSSize(width: 1280, height: 720))
            w.center()
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        MPV.shared.stop()
    }
}
