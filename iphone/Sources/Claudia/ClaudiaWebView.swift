import UIKit
import WebKit

/// The hub's web view, without the iOS edit menu outside text fields (README decision 42): iOS 27
/// opens that menu right under the selected text, over the lower handle, so a finger reaching for
/// the handle lands on the menu. The page shows its own Copy pill above the selection instead
/// (claudia.web.copy-pill) and copies through `claudiaText`. In a text field the menu stays
/// (Paste); the page says when one has the focus.
final class ClaudiaWebView: WKWebView {
    var editing = false

    // Only the menu goes: WebKit asks canPerformAction for its own selection steps too, so
    // refusing actions there (1.4.0) made selecting stall on an iPad.
    override func buildMenu(with builder: UIMenuBuilder) {
        super.buildMenu(with: builder)
        // Every item, WebKit's own too (Copy Link with Highlight): an empty menu is not shown.
        if !editing { builder.replaceChildren(ofMenu: .root) { _ in [] } }
    }
}

/// The page's Copy pill and text fields: `{op: "copy", text}` puts text on the pasteboard;
/// `{op: "editing", on}` turns the iOS menu on in a focused text field. Only the hub's main frame.
final class TextBridge: NSObject, WKScriptMessageHandlerWithReply {
    static let name = "claudiaText"
    weak var webView: ClaudiaWebView?

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage,
                               replyHandler: @escaping (Any?, String?) -> Void) {
        guard message.frameInfo.isMainFrame, NativeMic.origin(of: message.frameInfo.securityOrigin) == Config.hubOrigin,
              let body = message.body as? [String: Any], let op = body["op"] as? String else {
            replyHandler(nil, "not the hub's page")
            return
        }
        switch op {
        case "copy":
            UIPasteboard.general.string = body["text"] as? String ?? ""
            replyHandler(true, nil)
        case "editing":
            webView?.editing = body["on"] as? Bool ?? false
            replyHandler(nil, nil)
        default:
            replyHandler(nil, "unknown op \(op)")
        }
    }
}
