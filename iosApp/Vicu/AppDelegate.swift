import UIKit
import WidgetKit
import shared

/// Starts the shared code before the first scene: iOS also launches the app without a scene
/// (a notification action, a background task), and those need it running too.
final class AppDelegate: NSObject, UIApplicationDelegate {
    /// Kept for the app's lifetime: the shared code holds the bridge, and CLMonitor reports to it.
    private var locationMonitor: LocationMonitor?
    /// Kept for the app's lifetime: the shared code holds the bridge for holding the + to talk.
    private let speechBridge = SpeechRecognizerBridge()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Before start: iOS may have launched the app for a region event, which the monitor delivers.
        let monitor = LocationMonitor()
        locationMonitor = monitor
        IosApp.shared.start(widgetBridge: WidgetBridge(), locationBridge: monitor, speechBridge: speechBridge)
        return true
    }

    /// Puts [SceneDelegate] next to SwiftUI's own, for the home-screen quick actions.
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}

/// The home-screen quick actions (Info.plist UIApplicationShortcutItems): Add task opens the
/// task entry and Dictate starts dictation, whether the app was running or not.
final class SceneDelegate: NSObject, UIWindowSceneDelegate {
    static let addTask = "com.rendyhd.vicu.add"
    static let dictate = "com.rendyhd.vicu.dictate"

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        if let item = connectionOptions.shortcutItem {
            _ = handle(item)
        }
    }

    func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        completionHandler(handle(shortcutItem))
    }

    private func handle(_ item: UIApplicationShortcutItem) -> Bool {
        switch item.type {
        case Self.addTask:
            IosApp.shared.openQuickAdd()
        case Self.dictate:
            IosApp.shared.startDictation()
        default:
            return false
        }
        return true
    }
}

/// WidgetKit for the shared code, which writes the widget snapshots and then asks for a reload.
private final class WidgetBridge: NSObject, AppleWidgetBridge {
    func reloadAllTimelines() {
        WidgetCenter.shared.reloadAllTimelines()
    }
}
