import UIKit
import UserNotifications

@main
final class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Set before launch ends, so a tap that launched the app is delivered here.
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        // Asks once (the first launch); later launches get the stored answer without a prompt.
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if let error { NSLog("claudia push: authorization failed: \(error.localizedDescription)") }
            else if !granted { NSLog("claudia push: notifications not allowed") }
        }
        application.registerForRemoteNotifications()
        return true
    }

    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        config.delegateClass = SceneDelegate.self
        return config
    }

    // MARK: Push token

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Push.shared.received(token: deviceToken.map { String(format: "%02x", $0) }.joined())
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        NSLog("claudia push: registration failed: \(error.localizedDescription)")
    }

    // MARK: Notifications

    /// In the foreground: no banner for the chat already on screen; every other one shows.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let info = notification.request.content.userInfo
        let onScreen = UIApplication.shared.applicationState == .active
            && WebController.current?.isShowing(chat: info["chat"] as? String, server: info["server"] as? String,
                                                 path: info["url"] as? String) == true
        completionHandler(onScreen ? [] : [.banner, .list, .sound, .badge])
    }

    /// A tap opens that chat: the hub's address plus the notification's `url`.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        Router.open(response)
        completionHandler()
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        // Launched by a tap on a notification: open that chat first instead of the hub's start page.
        if let response = connectionOptions.notificationResponse { Router.open(response) }
        let window = UIWindow(windowScene: scene)
        window.backgroundColor = WebController.background
        window.rootViewController = WebController(first: Router.takePending())
        window.makeKeyAndVisible()
        self.window = window
    }
}

/// Where a notification tap goes. A cold start can report the same tap twice (the scene's
/// connection options and the notification center), so each notification opens once.
enum Router {
    private static var pending: URL?
    private static var opened = Set<String>()

    static func open(_ response: UNNotificationResponse) {
        guard response.actionIdentifier == UNNotificationDefaultActionIdentifier,
              opened.insert(response.notification.request.identifier).inserted,
              let path = response.notification.request.content.userInfo["url"] as? String,
              let url = Config.hubURL(path: path) else { return }
        if let web = WebController.current { web.load(url) } else { pending = url }
    }

    static func takePending() -> URL? {
        defer { pending = nil }
        return pending
    }
}
