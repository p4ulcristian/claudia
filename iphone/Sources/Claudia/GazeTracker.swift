import ARKit
import AVFoundation
import UIKit
import WebKit

/// The gaze pointer's eyes (claudia.web.gaze talks to it over
/// `window.webkit.messageHandlers.claudiaGaze`): ARKit face tracking on the TrueDepth camera (Face ID
/// devices). Each frame, both eyes' rays are taken into the camera's space and met with the plane
/// the camera sits in, which is the screen's plane; that point (metres, camera axes), the head's turn
/// and place, the blinks and the screen's turn go to the page about 30 times a second as
/// `window.claudiaGaze({hx, hy, yaw, pitch, x, y, z, blink, blinkL, blinkR, turn})`. The page
/// calibrates (the camera's axes, the screen's size and the eyes' own offsets are all learned there,
/// one model per screen turn) and draws. Without a tracked face only `{turn}` is sent.
///
/// Messages (answered through the reply): `{op: "start"}` -> {ok} once the camera runs, refused on a
/// device without a TrueDepth camera or without camera access; `{op: "stop"}`. Only the hub's main
/// frame is answered. No camera picture is kept or shown.
final class GazeTracker: NSObject, WKScriptMessageHandlerWithReply, ARSessionDelegate {
    static let name = "claudiaGaze"

    weak var webView: WKWebView?
    private var session: ARSession?
    private var lastSent: TimeInterval = 0
    private let interval: TimeInterval = 1.0 / 30

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage,
                               replyHandler: @escaping (Any?, String?) -> Void) {
        guard message.frameInfo.isMainFrame,
              NativeMic.origin(of: message.frameInfo.securityOrigin) == Config.hubOrigin,
              let body = message.body as? [String: Any], let op = body["op"] as? String else {
            replyHandler(nil, "not the hub's page")
            return
        }
        switch op {
        case "start": start(replyHandler)
        case "stop": stop(); replyHandler(nil, nil)
        default: replyHandler(nil, "unknown op \(op)")
        }
    }

    // MARK: On and off

    private func start(_ reply: @escaping (Any?, String?) -> Void) {
        guard ARFaceTrackingConfiguration.isSupported else {
            reply(nil, "This device has no Face ID camera.")
            return
        }
        AVCaptureDevice.requestAccess(for: .video) { granted in
            DispatchQueue.main.async {
                guard granted else {
                    reply(nil, "Camera access is off for Claudia (Settings, Claudia, Camera).")
                    return
                }
                self.run()
                reply(["ok": true], nil)
            }
        }
    }

    private func run() {
        let s = session ?? ARSession()
        s.delegate = self   // called on the main queue
        let config = ARFaceTrackingConfiguration()
        config.maximumNumberOfTrackedFaces = 1
        config.isLightEstimationEnabled = false
        s.run(config, options: [.resetTracking, .removeExistingAnchors])
        session = s
    }

    func stop() {
        session?.pause()
        session = nil
    }

    // MARK: Frames

    func session(_ session: ARSession, didUpdate frame: ARFrame) {
        guard frame.timestamp - lastSent >= interval else { return }
        lastSent = frame.timestamp
        var reading: [String: Double] = ["turn": Double(NativeMic.screenTurn())]
        if let face = frame.anchors.compactMap({ $0 as? ARFaceAnchor }).first, face.isTracked {
            reading.merge(Self.reading(face: face, camera: frame.camera.transform)) { _, new in new }
        }
        send(reading)
    }

    /// One face's reading in the camera's space: where the eyes' mean ray meets the plane z = 0
    /// (the camera's, so the screen's, plane), the face's forward axis as yaw and pitch, its place,
    /// and the blinks (0 open to 1 shut).
    static func reading(face: ARFaceAnchor, camera: simd_float4x4) -> [String: Double] {
        let faceInCamera = simd_inverse(camera) * face.transform
        let left = faceInCamera * face.leftEyeTransform
        let right = faceInCamera * face.rightEyeTransform
        let origin = (position(left) + position(right)) / 2
        let direction = simd_normalize(axisZ(left) + axisZ(right))
        let forward = simd_normalize(axisZ(faceInCamera))
        let place = position(faceInCamera)
        let blinkL = Double(face.blendShapes[.eyeBlinkLeft]?.floatValue ?? 0)
        let blinkR = Double(face.blendShapes[.eyeBlinkRight]?.floatValue ?? 0)
        var r: [String: Double] = [
            "yaw": Double(atan2(forward.x, forward.z)), "pitch": Double(asin(max(-1, min(1, forward.y)))),
            "x": Double(place.x), "y": Double(place.y), "z": Double(place.z),
            "blink": max(blinkL, blinkR), "blinkL": blinkL, "blinkR": blinkR,
        ]
        // A ray along the plane never meets it: no point this frame.
        if abs(direction.z) > 1e-4 {
            let t = -origin.z / direction.z
            r["hx"] = Double(origin.x + t * direction.x)
            r["hy"] = Double(origin.y + t * direction.y)
        }
        return r
    }

    private static func position(_ m: simd_float4x4) -> simd_float3 {
        simd_float3(m.columns.3.x, m.columns.3.y, m.columns.3.z)
    }

    private static func axisZ(_ m: simd_float4x4) -> simd_float3 {
        simd_float3(m.columns.2.x, m.columns.2.y, m.columns.2.z)
    }

    private func send(_ reading: [String: Double]) {
        guard let webView, let data = try? JSONSerialization.data(withJSONObject: reading),
              let json = String(data: data, encoding: .utf8) else { return }
        webView.evaluateJavaScript("window.claudiaGaze && window.claudiaGaze(\(json))", completionHandler: nil)
    }
}
