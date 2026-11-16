import ClaudiaShared
import CryptoKit
import Foundation
import UserNotifications

/// The Notification Service Extension (PlugIns/NotificationService.appex): the hub sends a plain
/// fallback ("Claudia" / "New message") plus `enc`, the real title and body sealed with the
/// device key (AES-256-GCM, base64 of nonce 12 || ciphertext || tag 16, around UTF-8 JSON
/// `{"title", "body"}`). Opened here; on any failure the fallback shows. Everything else in the
/// notification (url, server, chat, kind) is passed on as it came, for the tap.
@objc(NotificationService)
final class NotificationService: UNNotificationServiceExtension {
    private var deliver: ((UNNotificationContent) -> Void)?
    private var fallback: UNNotificationContent?

    override func didReceive(_ request: UNNotificationRequest,
                             withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        deliver = contentHandler
        fallback = request.content
        guard let content = request.content.mutableCopy() as? UNMutableNotificationContent else {
            return finish(request.content)
        }
        if let enc = content.userInfo["enc"] as? String, let text = Self.open(enc) {
            if let title = text.title { content.title = title }
            if let body = text.body { content.body = body }
        }
        finish(content)
    }

    /// iOS is about to give up on the extension: show the fallback.
    override func serviceExtensionTimeWillExpire() {
        if let fallback { finish(fallback) }
    }

    private func finish(_ content: UNNotificationContent) {
        let deliver = self.deliver
        self.deliver = nil
        deliver?(content)
    }

    private struct Text: Decodable {
        var title: String?
        var body: String?
    }

    private static func open(_ enc: String) -> Text? {
        guard let sealed = Data(base64Encoded: enc), sealed.count > 28,
              let key = DeviceKey.load(),
              let box = try? AES.GCM.SealedBox(combined: sealed),
              let plain = try? AES.GCM.open(box, using: SymmetricKey(data: key)) else { return nil }
        return try? JSONDecoder().decode(Text.self, from: plain)
    }
}
