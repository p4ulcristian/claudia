import Foundation
import WebKit

/// Calls to the hub from the app itself (not the page): signed in with the web view's cookies
/// (the gate's login), so they work with no page loaded, also in a background launch.
enum Hub {
    enum Outcome {
        case ok
        /// The hub answered no (not signed in, a bad request): again only on the next occasion.
        case refused(Int)
        /// No answer, or the hub failing: worth trying again soon.
        case failed
    }

    @MainActor
    static func send(_ method: String, _ path: String, json: [String: String]? = nil) async -> Outcome {
        guard let url = Config.hubURL(path: path) else { return .refused(0) }
        let cookies = await WKWebsiteDataStore.default().httpCookieStore.allCookies()

        // A private session holding a copy of the web view's cookies; Foundation picks the ones
        // that belong to the hub's address.
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieAcceptPolicy = .always
        cookies.forEach { config.httpCookieStorage?.setCookie($0) }
        let session = URLSession(configuration: config)
        defer { session.finishTasksAndInvalidate() }

        var request = URLRequest(url: url, timeoutInterval: 20)
        request.httpMethod = method
        if let json {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try? JSONSerialization.data(withJSONObject: json)
        }
        do {
            // A redirect means the sign-in page: not followed, counted as refused.
            let (_, response) = try await session.data(for: request, delegate: NoRedirects())
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            switch status {
            case 200..<300: return .ok
            case 500...: return .failed
            default: return .refused(status)
            }
        } catch {
            return .failed
        }
    }

    private final class NoRedirects: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)
        }
    }
}
