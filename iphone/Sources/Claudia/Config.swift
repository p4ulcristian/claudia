import Foundation

/// What the build baked into Info.plist from the local config (tools/testflight.sh). The hub's
/// address is not in the repository, so a build without it shows a message instead of a page.
enum Config {
    /// The hub: the page every person opens (README decision 22).
    static let hub: URL? = (Bundle.main.object(forInfoDictionaryKey: "ClaudiaHubURL") as? String)
        .flatMap(URL.init(string:))

    /// Hosts the sign-in passes through (oauth2-proxy, Pocket ID): their pages stay in the app.
    static let loginHosts: Set<String> = Set(
        (Bundle.main.object(forInfoDictionaryKey: "ClaudiaLoginHosts") as? [String] ?? []).map { $0.lowercased() })

    static let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    static let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"

    /// "https://host" (with ":port" when not the scheme's default): what `location.origin` says on the hub.
    static let hubOrigin: String? = hub.flatMap(origin(of:))

    static func origin(of url: URL) -> String? {
        guard let scheme = url.scheme?.lowercased(), let host = url.host?.lowercased() else { return nil }
        let port = url.port.map { ":\($0)" } ?? ""
        return "\(scheme)://\(host)\(port)"
    }

    static func isHub(_ url: URL) -> Bool { hubOrigin != nil && origin(of: url) == hubOrigin }

    /// The hub and the sign-in hosts open inside the app; every other site opens in the browser.
    static func staysInApp(_ url: URL) -> Bool {
        if isHub(url) { return true }
        guard url.scheme?.lowercased() == "https", let host = url.host?.lowercased() else { return false }
        return loginHosts.contains(host)
    }

    /// A path on the hub ("/c/box/abc"), as a notification's `url` carries it; nil for anything else.
    static func hubURL(path: String) -> URL? {
        guard let hub, path.hasPrefix("/"), !path.hasPrefix("//") else { return nil }
        return URL(string: path, relativeTo: hub)?.absoluteURL
    }
}
