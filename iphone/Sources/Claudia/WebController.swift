import UIKit
import WebKit

/// The whole app: the hub's page in a full-screen web view. The page draws under the status
/// bar and pads itself by the safe areas, as it does as a Home Screen app.
final class WebController: UIViewController, WKNavigationDelegate, WKUIDelegate {
    static weak var current: WebController?
    /// The icon's tile colour: shown until the page paints, so there is no white flash.
    static let background = UIColor(red: 0x2c / 255.0, green: 0x38 / 255.0, blue: 0x33 / 255.0, alpha: 1)

    private let webView: WKWebView
    private let first: URL?
    private let failure = UIView()
    private let failureText = UILabel()

    init(first: URL?) {
        self.first = first
        let config = WKWebViewConfiguration()
        // Persistent cookies and storage: the sign-in survives restarts.
        config.websiteDataStore = .default()
        config.allowsInlineMediaPlayback = true
        webView = WKWebView(frame: .zero, configuration: config)
        super.init(nibName: nil, bundle: nil)
        Self.current = self
        installScripts()
        Push.shared.onToken = { [weak self] in
            self?.installScripts()   // pages loaded from now on get it at document start
            self?.sendPushToken()    // the page on screen gets it now
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    override func loadView() {
        let root = UIView()
        root.backgroundColor = Self.background
        webView.isOpaque = false
        webView.backgroundColor = Self.background
        webView.scrollView.backgroundColor = Self.background
        // The page lays itself out by env(safe-area-inset-*); the scroll view adds no insets of its own.
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.scrollView.bounces = false
        if #available(iOS 26.0, *) {
            // No system blur at the edges: the page paints its own band under the status bar.
            webView.scrollView.topEdgeEffect.isHidden = true
            webView.scrollView.bottomEdgeEffect.isHidden = true
        }
        // The page pushes a history entry per screen, so the edge swipe goes from a chat back to the list.
        webView.allowsBackForwardNavigationGestures = true
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: root.topAnchor),
            webView.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: root.trailingAnchor),
        ])
        buildFailureView(in: root)
        view = root
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        guard let url = first ?? Config.hub else {
            return showFailure("This build has no hub address. Build it with iphone/tools/testflight.sh.", retry: false)
        }
        load(url)
    }

    func load(_ url: URL) {
        failure.isHidden = true
        webView.load(URLRequest(url: url))
    }

    /// Whether the chat a notification is about is the one open on screen.
    func isShowing(chat: String?, server: String?, path: String?) -> Bool {
        guard let url = webView.url, Config.isHub(url) else { return false }
        if let path, let target = Config.hubURL(path: path), target.path == url.path { return true }
        guard let chat, !chat.isEmpty else { return false }
        let parts = url.pathComponents
        return parts.last == chat && (server.map { parts.contains($0) } ?? true)
    }

    // MARK: What the page is told

    private func installScripts() {
        let controller = webView.configuration.userContentController
        controller.removeAllUserScripts()
        guard let origin = Config.hubOrigin else { return }
        var native: [String: Any] = ["platform": "ios", "version": Config.version, "build": Config.build]
        if let detail = Push.shared.detail { native["pushToken"] = detail }
        // Before the page's own scripts, and only on the hub (never on the sign-in pages).
        let source = "if (location.origin === \(json(origin))) window.claudiaNative = \(json(native));"
        controller.addUserScript(WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: true))
    }

    /// Sets `window.claudiaNative.pushToken` and fires `claudia-push-token`; after every page load
    /// and whenever one of its fields changes (the device token, the Live Activity start token).
    private func sendPushToken() {
        guard let detail = Push.shared.detail, let url = webView.url, Config.isHub(url) else { return }
        let source = """
        (function (d) {
          window.claudiaNative = window.claudiaNative || \(json(["platform": "ios", "version": Config.version, "build": Config.build]));
          window.claudiaNative.pushToken = d;
          window.dispatchEvent(new CustomEvent("claudia-push-token", { detail: d }));
        })(\(json(detail)));
        """
        webView.evaluateJavaScript(source)
    }

    private func json(_ value: Any) -> String {
        let data = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed])
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "null"
    }

    // MARK: Navigation

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url, let scheme = url.scheme?.lowercased() else { return decisionHandler(.cancel) }
        switch scheme {
        case "about", "blob", "data":
            decisionHandler(.allow)
        case "http", "https":
            // Frames inside the page load where they like; the page itself leaves only for the
            // hub and the sign-in, and every other site opens in the browser.
            let mainFrame = action.targetFrame?.isMainFrame ?? true
            if mainFrame && !Config.staysInApp(url) {
                UIApplication.shared.open(url)
                decisionHandler(.cancel)
            } else {
                decisionHandler(.allow)
            }
        default:
            UIApplication.shared.open(url)   // mailto:, tel:, other apps
            decisionHandler(.cancel)
        }
    }

    /// target="_blank" and window.open: the hub opens here, other sites in the browser.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = action.request.url {
            if Config.staysInApp(url) { webView.load(action.request) } else { UIApplication.shared.open(url) }
        }
        return nil
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        failure.isHidden = true
        sendPushToken()
        // A page load may follow a fresh sign-in: the cookies the native calls need.
        if let url = webView.url, Config.isHub(url) { LiveActivities.shared.sync() }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        failed(error)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        failed(error)
    }

    /// iOS ended the page's process (memory pressure, a long time in the background): load it again.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        if webView.url != nil { webView.reload() } else if let hub = Config.hub { load(hub) }
    }

    private func failed(_ error: Error) {
        let e = error as NSError
        // A navigation this app cancelled (another site sent to the browser), or one replaced by the next.
        if e.domain == NSURLErrorDomain && e.code == NSURLErrorCancelled { return }
        if e.domain == "WebKitErrorDomain" && e.code == 102 { return }
        showFailure("Claudia can't be reached.\n\(e.localizedDescription)", retry: true)
    }

    // MARK: JavaScript dialogs (alert, confirm, prompt)

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
        present(alert, animated: true)
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in completionHandler(false) })
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) })
        present(alert, animated: true)
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
        let alert = UIAlertController(title: nil, message: prompt, preferredStyle: .alert)
        alert.addTextField { $0.text = defaultText }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in completionHandler(nil) })
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(alert.textFields?.first?.text) })
        present(alert, animated: true)
    }

    // MARK: Can't load

    private func buildFailureView(in root: UIView) {
        failureText.numberOfLines = 0
        failureText.textAlignment = .center
        failureText.textColor = UIColor(white: 0.93, alpha: 1)
        failureText.font = .preferredFont(forTextStyle: .body)
        let retry = UIButton(type: .system)
        retry.setTitle("Try again", for: .normal)
        retry.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        retry.tintColor = UIColor(red: 0xdb / 255.0, green: 0x78 / 255.0, blue: 0x58 / 255.0, alpha: 1)
        retry.tag = 1
        retry.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            if let url = self.webView.url, url.absoluteString != "about:blank" { self.load(url) }
            else if let url = self.first ?? Config.hub { self.load(url) }
        }, for: .primaryActionTriggered)
        let stack = UIStackView(arrangedSubviews: [failureText, retry])
        stack.axis = .vertical
        stack.spacing = 16
        stack.alignment = .center
        stack.translatesAutoresizingMaskIntoConstraints = false
        failure.backgroundColor = Self.background
        failure.isHidden = true
        failure.translatesAutoresizingMaskIntoConstraints = false
        failure.addSubview(stack)
        root.addSubview(failure)
        NSLayoutConstraint.activate([
            failure.topAnchor.constraint(equalTo: root.topAnchor),
            failure.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            failure.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            failure.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            stack.centerYAnchor.constraint(equalTo: failure.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: failure.layoutMarginsGuide.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: failure.layoutMarginsGuide.trailingAnchor, constant: -16),
        ])
    }

    private func showFailure(_ text: String, retry: Bool) {
        failureText.text = text
        failure.viewWithTag(1)?.isHidden = !retry
        failure.isHidden = false
    }
}
