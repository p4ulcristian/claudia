import Accelerate
import AVFoundation
import UIKit
import WebKit

/// The composer's mic, recorded by the app instead of the page (claudia.web.mic-native talks to it
/// over `window.webkit.messageHandlers.claudiaMic`). In the web view, after a switch to another app,
/// WebKit's microphone could open live and still give no sound (the hub's mic log, iPad 1.3.4);
/// here every press checks the app's own audio session and engine and starts them again when they
/// stopped, so a press after an app switch always gets the microphone back.
///
/// Like the page's mic it stays warm after a press (`idle` seconds) and keeps the last second, so a
/// clip starts half a second before the press. Messages (each answered through the reply):
/// `{op: "start"}` -> {rate, mic} once samples flow (mic: which microphone iOS records from, so the
/// page can show where to speak: {port, name, source?, location?, orientation?}); `{op: "spectrum"}` -> 256 bytes, low to high, like
/// an AnalyserNode's getByteFrequencyData (fftSize 512); `{op: "stop"}` -> the clip as base64 16 kHz
/// WAV; `{op: "discard"}`; `{op: "close"}`. Only the hub's main frame is answered.
final class NativeMic: NSObject, WKScriptMessageHandlerWithReply {
    static let name = "claudiaMic"

    private let idle: TimeInterval = 10 * 60
    private let keepSeconds = 1.0
    private let preSeconds = 0.5
    private let firstSoundSeconds = 2.0
    private let fftSize = 512

    private let engine = AVAudioEngine()
    private let lock = NSLock()
    // Under `lock`: written by the audio thread, read by the main thread.
    private var ring: [Float] = []
    private var clip: [Float]?
    private var lastSample = Date.distantPast
    private var rate: Double = 48_000

    private var tapped = false
    private var closeTimer: Timer?
    private let analyser = Analyser(size: 512)

    override init() {
        super.init()
        NotificationCenter.default.addObserver(self, selector: #selector(hidden),
                                               name: UIApplication.didEnterBackgroundNotification, object: nil)
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage,
                               replyHandler: @escaping (Any?, String?) -> Void) {
        guard message.frameInfo.isMainFrame, Self.origin(of: message.frameInfo.securityOrigin) == Config.hubOrigin,
              let body = message.body as? [String: Any], let op = body["op"] as? String else {
            replyHandler(nil, "not the hub's page")
            return
        }
        switch op {
        case "start": start(replyHandler)
        case "spectrum": replyHandler(spectrum(), nil)
        case "stop": stop(replyHandler)
        case "discard": endClip(); replyHandler(nil, nil)
        case "close": close(); replyHandler(nil, nil)
        default: replyHandler(nil, "unknown op \(op)")
        }
    }

    static func origin(of o: WKSecurityOrigin) -> String {
        let port = o.port == 0 ? "" : ":\(o.port)"
        return "\(o.protocol.lowercased())://\(o.host.lowercased())\(port)"
    }

    // MARK: Opening

    /// The session for recording beside playback: the read-aloud voice and the bells keep playing
    /// from the speaker at the media volume (mode default: no call audio, no voice processing).
    private func prepareSession() throws {
        let session = AVAudioSession.sharedInstance()
        if session.category != .playAndRecord || session.mode != .default {
            try session.setCategory(.playAndRecord, mode: .default,
                                    options: [.defaultToSpeaker, .allowBluetoothA2DP, .mixWithOthers])
        }
        try session.setActive(true)
    }

    /// The input iOS records from now: its port (MicrophoneBuiltIn, a headset, AirPods), and for the
    /// built-in one the data source in use with where it sits (location Upper or Lower) and which way
    /// it listens (orientation Front, Back, Top, Bottom, Left or Right). A data source can be several
    /// microphones combined: it names a side of the device, not one hole.
    static func micInfo() -> [String: String] {
        let session = AVAudioSession.sharedInstance()
        guard let input = session.currentRoute.inputs.first else { return [:] }
        var m = ["port": input.portType.rawValue, "name": input.portName]
        if let ds = input.selectedDataSource ?? session.inputDataSource {
            m["source"] = ds.dataSourceName
            if let l = ds.location { m["location"] = l.rawValue }
            if let o = ds.orientation { m["orientation"] = o.rawValue }
        }
        return m
    }

    private func sounding(within seconds: Double) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return Date().timeIntervalSince(lastSample) < seconds
    }

    /// Warm and giving sound now: the engine runs and its last samples are fresh.
    private var warm: Bool { engine.isRunning && sounding(within: 0.4) }

    private func open() throws {
        try prepareSession()
        if tapped { engine.inputNode.removeTap(onBus: 0); tapped = false }
        engine.stop()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            throw NSError(domain: "claudia.mic", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "No microphone input."])
        }
        lock.lock(); rate = format.sampleRate; ring = []; lastSample = .distantPast; lock.unlock()
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            self?.heard(buffer)
        }
        tapped = true
        engine.prepare()
        try engine.start()
    }

    private func heard(_ buffer: AVAudioPCMBuffer) {
        guard let data = buffer.floatChannelData else { return }
        let piece = Array(UnsafeBufferPointer(start: data[0], count: Int(buffer.frameLength)))
        lock.lock()
        ring.append(contentsOf: piece)
        let keep = Int(rate * keepSeconds)
        if ring.count > keep { ring.removeFirst(ring.count - keep) }
        clip?.append(contentsOf: piece)
        lastSample = Date()
        lock.unlock()
    }

    /// A press: the microphone opened again unless it is warm and sounding, then the clip begins
    /// half a second back once samples flow.
    private func start(_ reply: @escaping (Any?, String?) -> Void) {
        closeTimer?.invalidate()
        if !warm {
            do { try open() } catch {
                close()
                reply(nil, "The microphone didn't start: \(error.localizedDescription)")
                return
            }
        }
        waitForSound(until: Date().addingTimeInterval(firstSoundSeconds), reply)
    }

    private func waitForSound(until: Date, _ reply: @escaping (Any?, String?) -> Void) {
        if sounding(within: 0.4) {
            lock.lock()
            clip = Array(ring.suffix(Int(rate * preSeconds)))
            let r = rate
            lock.unlock()
            reply(["rate": r, "mic": Self.micInfo()], nil)
        } else if Date() < until {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { self.waitForSound(until: until, reply) }
        } else {
            close()
            reply(nil, "The microphone gives no sound (another app may hold it): try again.")
        }
    }

    // MARK: The clip

    private func endClip() {
        lock.lock(); clip = nil; lock.unlock()
        closeTimer?.invalidate()
        closeTimer = Timer.scheduledTimer(withTimeInterval: idle, repeats: false) { [weak self] _ in self?.close() }
    }

    /// The clip as base64 16 kHz WAV, after the samples still on their way (80 ms).
    private func stop(_ reply: @escaping (Any?, String?) -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) {
            self.lock.lock()
            let samples = self.clip ?? []
            let from = self.rate
            self.lock.unlock()
            self.endClip()
            guard !samples.isEmpty else {
                reply(nil, "The microphone gives no sound (another app may hold it): try again.")
                return
            }
            reply(Wav.base64(Wav.resampled(samples, from: from, to: 16_000), rate: 16_000), nil)
        }
    }

    private func spectrum() -> [Int] {
        lock.lock()
        let last = Array(ring.suffix(fftSize))
        lock.unlock()
        return analyser.bytes(last)
    }

    // MARK: Letting go

    @objc private func hidden() { close() }

    private func close() {
        closeTimer?.invalidate()
        if tapped { engine.inputNode.removeTap(onBus: 0); tapped = false }
        engine.stop()
        lock.lock(); clip = nil; ring = []; lastSample = .distantPast; lock.unlock()
        // The session stays active: the page may be playing the read-aloud voice through it.
    }
}

/// An AnalyserNode's byte spectrum: Blackman window, FFT, smoothing 0.5 over time, dB mapped from
/// -100..-30 to 0..255 (the Web Audio defaults the bars were tuned on).
final class Analyser {
    private let size: Int
    private let setup: FFTSetup
    private let log2n: vDSP_Length
    private var window: [Float]
    private var smoothed: [Float]

    init(size: Int) {
        self.size = size
        log2n = vDSP_Length(log2(Double(size)))
        setup = vDSP_create_fftsetup(log2n, FFTRadix(kFFTRadix2))!
        window = [Float](repeating: 0, count: size)
        vDSP_blkman_window(&window, vDSP_Length(size), 0)
        smoothed = [Float](repeating: 0, count: size / 2)
    }

    deinit { vDSP_destroy_fftsetup(setup) }

    func bytes(_ samples: [Float]) -> [Int] {
        let half = size / 2
        guard samples.count == size else { return [Int](repeating: 0, count: half) }
        var x = [Float](repeating: 0, count: size)
        vDSP_vmul(samples, 1, window, 1, &x, 1, vDSP_Length(size))
        var re = [Float](repeating: 0, count: half)
        var im = [Float](repeating: 0, count: half)
        var mags = [Float](repeating: 0, count: half)
        re.withUnsafeMutableBufferPointer { rp in
            im.withUnsafeMutableBufferPointer { ip in
                var split = DSPSplitComplex(realp: rp.baseAddress!, imagp: ip.baseAddress!)
                x.withUnsafeBufferPointer { xp in
                    xp.baseAddress!.withMemoryRebound(to: DSPComplex.self, capacity: half) {
                        vDSP_ctoz($0, 2, &split, 1, vDSP_Length(half))
                    }
                }
                vDSP_fft_zrip(setup, &split, 1, log2n, FFTDirection(FFT_FORWARD))
                vDSP_zvabs(&split, 1, &mags, 1, vDSP_Length(half))
            }
        }
        // vDSP's real FFT is twice the plain DFT; Web Audio scales the DFT by 1 / size.
        let scale = 1 / Float(2 * size)
        return (0..<half).map { i in
            smoothed[i] = 0.5 * smoothed[i] + 0.5 * mags[i] * scale
            let db = 20 * log10(max(smoothed[i], 1e-12))
            return Int(max(0, min(255, (db + 100) / 70 * 255)))
        }
    }
}

/// 16-bit mono WAV, as the page's own clips (claudia.web.pcm).
enum Wav {
    static func resampled(_ samples: [Float], from: Double, to: Double) -> [Float] {
        guard from != to, !samples.isEmpty else { return samples }
        let ratio = from / to
        let count = Int(Double(samples.count) / ratio)
        return (0..<count).map { i in
            let at = Double(i) * ratio
            let j = Int(at)
            let next = min(j + 1, samples.count - 1)
            let f = Float(at - Double(j))
            return samples[j] * (1 - f) + samples[next] * f
        }
    }

    static func base64(_ samples: [Float], rate: Int) -> String {
        var d = Data()
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        let bytes = UInt32(samples.count * 2)
        d.append(contentsOf: Array("RIFF".utf8)); u32(36 + bytes); d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8)); u32(16); u16(1); u16(1); u32(UInt32(rate)); u32(UInt32(rate * 2))
        u16(2); u16(16)
        d.append(contentsOf: Array("data".utf8)); u32(bytes)
        for s in samples {
            let v = Int16(max(-1, min(1, s)) * Float(Int16.max))
            withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) }
        }
        return d.base64EncodedString()
    }
}
