import Foundation
import Security

/// The device's 32-byte AES-256 key: the hub encrypts each notification's text with it (`enc`),
/// the Notification Service Extension decrypts. Made once, on the app's first launch; the page
/// hands it to the hub with the push token. Never logged.
///
/// It sits in the Keychain group both the app and the extension are signed with (the first entry
/// of their `keychain-access-groups`, `<team>.com.p4ulcristian.claudia.shared`, set by
/// tools/testflight.sh): with no group named, the app adds into that first group and the
/// extension's search covers it. Readable after the first unlock, so a locked phone still decrypts.
public enum DeviceKey {
    private static let base: [CFString: Any] = [
        kSecClass: kSecClassGenericPassword,
        kSecAttrService: "claudia.push",
        kSecAttrAccount: "device-key",
    ]

    /// The key, or nil when there is none yet or the Keychain can't be read now.
    public static func load() -> Data? {
        var query = base
        query[kSecReturnData] = true
        query[kSecMatchLimit] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data, data.count == 32 else { return nil }
        return data
    }

    /// The key, made and stored first when there is none. Nil when the Keychain can't be used now
    /// (then nothing is made either, so a stored key is never replaced).
    public static func loadOrCreate() -> Data? {
        var query = base
        query[kSecReturnData] = true
        query[kSecMatchLimit] = kSecMatchLimitOne
        var out: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &out)
        if status == errSecSuccess, let data = out as? Data, data.count == 32 { return data }
        guard status == errSecItemNotFound else { return nil }

        var bytes = Data(count: 32)
        let made = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard made == errSecSuccess else { return nil }
        var add = base
        add[kSecValueData] = bytes
        add[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        switch SecItemAdd(add as CFDictionary, nil) {
        case errSecSuccess: return bytes
        case errSecDuplicateItem: return load()   // made at the same moment elsewhere
        default: return nil
        }
    }
}
