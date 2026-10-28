import Foundation

/// The APNs device token and what the hub needs to send to it, handed to the page
/// (WebController) as `window.claudiaNative.pushToken` + a `claudia-push-token` event.
final class Push {
    static let shared = Push()

    private(set) var token: String?
    /// Called on the main thread when a token arrives (the first one, or a changed one).
    var onToken: (() -> Void)?

    /// The APNs topic: the bundle id this build runs under.
    let topic = Bundle.main.bundleIdentifier ?? ""

    /// Which APNs environment issued the token: "development" for development-signed builds,
    /// "production" for TestFlight / App Store (and ad hoc) builds. Read from the embedded
    /// provisioning profile's aps-environment entitlement, which is what the app is signed for.
    /// App Store Connect strips the profile from TestFlight and App Store builds; those always
    /// get production tokens.
    let environment: String = {
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url) else { return "production" }
        // The profile is a signed CMS envelope around a plain XML property list.
        guard let start = data.range(of: Data("<?xml".utf8)),
              let end = data.range(of: Data("</plist>".utf8), in: start.lowerBound..<data.endIndex),
              let plist = try? PropertyListSerialization.propertyList(from: data[start.lowerBound..<end.upperBound],
                                                                      format: nil) as? [String: Any],
              let entitlements = plist["Entitlements"] as? [String: Any] else { return "development" }
        return entitlements["aps-environment"] as? String == "production" ? "production" : "development"
    }()

    /// `{token, env, topic}` for the page, or nil before APNs answered.
    var detail: [String: String]? {
        token.map { ["token": $0, "env": environment, "topic": topic] }
    }

    func received(token: String) {
        DispatchQueue.main.async {
            guard token != self.token else { return }
            self.token = token
            self.onToken?()
        }
    }
}
