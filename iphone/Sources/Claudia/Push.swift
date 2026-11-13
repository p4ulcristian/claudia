import ClaudiaShared
import Foundation

/// The APNs device token and what the hub needs to send to it, handed to the page
/// (WebController) as `window.claudiaNative.pushToken` + a `claudia-push-token` event.
final class Push {
    static let shared = Push()

    private static let tokenKey = "claudia.push.token"

    /// The last token APNs gave, kept so a background launch (a Live Activity started by push)
    /// can tell the hub about it before APNs answers again.
    private(set) var token: String? = UserDefaults.standard.string(forKey: tokenKey)
    /// The Live Activity push-to-start token (hex), once iOS gives one (iOS 17.2 and later).
    private(set) var liveStartToken: String?
    /// Called on the main thread whenever a field of `detail` changes.
    var onToken: (() -> Void)?

    /// The APNs topic: the bundle id this build runs under.
    let topic = Bundle.main.bundleIdentifier ?? ""

    /// base64 of the device key (DeviceKey), made on the first launch. Read again while the
    /// Keychain can't be (a background launch before the first unlock).
    private var key: String? {
        if cachedKey == nil { cachedKey = DeviceKey.loadOrCreate()?.base64EncodedString() }
        return cachedKey
    }
    private var cachedKey: String?

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

    /// `{token, env, topic, key, liveStartToken?}` for the page, or nil before APNs answered.
    var detail: [String: String]? {
        guard let token else { return nil }
        var d = ["token": token, "env": environment, "topic": topic]
        if let key { d["key"] = key }
        if let liveStartToken { d["liveStartToken"] = liveStartToken }
        return d
    }

    /// Makes the device key now (the first launch), so it is there when the page first asks.
    func prepare() { _ = key }

    func received(token: String) {
        DispatchQueue.main.async {
            guard token != self.token else { return }
            self.token = token
            UserDefaults.standard.set(token, forKey: Self.tokenKey)
            self.onToken?()
            Task { @MainActor in LiveActivities.shared.sync() }   // update tokens are registered under this device
        }
    }

    func received(liveStartToken: String) {
        DispatchQueue.main.async {
            guard liveStartToken != self.liveStartToken else { return }
            self.liveStartToken = liveStartToken
            self.onToken?()
        }
    }
}

extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
