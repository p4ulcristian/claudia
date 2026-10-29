import ActivityKit
import Foundation

/// The one summary Live Activity: which chats run, which wait on you, how many are done. The
/// hub starts, updates and ends it by push (apns-topic `<bundle id>.push-type.liveactivity`,
/// attributes-type `ClaudiaSummaryAttributes`); the field names are the hub's content-state keys.
public struct ClaudiaSummaryAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        public var running: [Item]
        public var waiting: [Item]
        public var done: Int
        /// Unix seconds.
        public var updatedAt: Int

        public init(running: [Item], waiting: [Item], done: Int, updatedAt: Int) {
            self.running = running
            self.waiting = waiting
            self.done = done
            self.updatedAt = updatedAt
        }
    }

    /// One chat.
    public struct Item: Codable, Hashable, Identifiable {
        public var title: String
        public var server: String
        public var chat: String
        /// Unix seconds: when it started running, or started waiting.
        public var since: Int
        /// Only for waiting chats: "ask" (a question) or "permission".
        public var kind: String?

        public var id: String { "\(server)/\(chat)" }

        public init(title: String, server: String, chat: String, since: Int, kind: String? = nil) {
            self.title = title
            self.server = server
            self.chat = chat
            self.since = since
            self.kind = kind
        }
    }

    public init() {}
}

/// `claudia://hub/<path>`: a path on the hub, as the widget links it. The app loads the hub's
/// origin plus that path.
public enum HubLink {
    public static let scheme = "claudia"
    public static let host = "hub"

    public static func url(path: String) -> URL? {
        var c = URLComponents()
        c.scheme = scheme
        c.host = host
        c.path = path.hasPrefix("/") ? path : "/" + path
        return c.url
    }

    /// The hub path a link carries ("/" for the chat list), or nil when it isn't one.
    public static func path(of url: URL) -> String? {
        guard url.scheme?.lowercased() == scheme, url.host?.lowercased() == host else { return nil }
        return url.path.isEmpty ? "/" : url.path
    }

    public static func chat(server: String, chat: String) -> URL? { url(path: "/c/\(server)/\(chat)") }
}
