import ActivityKit
import ClaudiaShared
import UIKit

/// The summary Live Activity's tokens. The hub starts, updates and ends the activity by push;
/// the app only passes tokens on:
/// - the push-to-start token (iOS 17.2 and later) goes to the page with the device token (Push);
/// - each activity's update token goes to the hub natively, `POST /hub/push/live
///   {device, activity, token}`, and `DELETE /hub/push/live/<activity>` once it has ended.
///
/// iOS launches the app in the background when the hub starts an activity, so this runs from
/// launch on, with or without a page. What the hub was told is kept (UserDefaults), and `sync`
/// sends only the difference between that and the activities alive now: on launch, on every
/// token or activity change, on a new device token, on each page load (a fresh sign-in) and on
/// coming to the foreground.
@MainActor
final class LiveActivities {
    static let shared = LiveActivities()

    private typealias Summary = Activity<ClaudiaSummaryAttributes>
    private static let toldKey = "claudia.live.told"

    /// Activity id → "<device token>|<update token>" the hub has accepted.
    private var told: [String: String] = UserDefaults.standard.dictionary(forKey: toldKey) as? [String: String] ?? [:]
    private var watched = Set<String>()
    private var syncing = false
    private var again = false
    private var retries = 0
    private var started = false

    func start() {
        guard !started else { return }
        started = true
        if #available(iOS 17.2, *) {
            Task {
                for await token in Summary.pushToStartTokenUpdates {
                    Push.shared.received(liveStartToken: token.hex)
                }
            }
        }
        Task {
            for await activity in Summary.activityUpdates { watch(activity) }
        }
        Summary.activities.forEach(watch)
        sync()
    }

    private func watch(_ activity: Summary) {
        guard watched.insert(activity.id).inserted else { return }
        Task {
            for await _ in activity.pushTokenUpdates { sync() }
        }
        Task {
            for await state in activity.activityStateUpdates where state == .ended || state == .dismissed {
                sync()
            }
        }
        sync()
    }

    /// Tells the hub what changed since it was last told; one pass at a time, again after it
    /// when something changed during it.
    func sync() {
        guard started else { return }
        if syncing { again = true; return }
        syncing = true
        let app = UIApplication.shared
        var background = UIBackgroundTaskIdentifier.invalid
        background = app.beginBackgroundTask(withName: "claudia live tokens") {
            app.endBackgroundTask(background)
            background = .invalid
        }
        Task {
            var failed = false
            repeat {
                again = false
                failed = await pass()
            } while again
            syncing = false
            if background != .invalid { app.endBackgroundTask(background) }
            // The hub unreachable: a few more tries while the app runs.
            if failed && retries < 5 {
                retries += 1
                Task {
                    try? await Task.sleep(nanoseconds: 30_000_000_000)
                    sync()
                }
            } else if !failed {
                retries = 0
            }
        }
    }

    /// One pass; true when a call should be tried again soon.
    private func pass() async -> Bool {
        guard let device = Push.shared.token, Config.hub != nil else { return false }
        var failed = false
        let alive = Summary.activities.filter { $0.activityState == .active || $0.activityState == .stale }

        for activity in alive {
            guard let token = activity.pushToken?.hex else { continue }
            let value = "\(device)|\(token)"
            guard told[activity.id] != value else { continue }
            switch await Hub.send("POST", "/hub/push/live",
                                  json: ["device": device, "activity": activity.id, "token": token]) {
            case .ok: remember(activity.id, value)
            case .failed: failed = true
            case .refused(let status): NSLog("claudia live: the hub refused the update token (\(status))")
            }
        }

        let ids = Set(alive.map(\.id))
        for id in told.keys where !ids.contains(id) {
            let path = "/hub/push/live/" + (id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id)
            switch await Hub.send("DELETE", path) {
            case .ok, .refused(404): remember(id, nil)
            case .failed: failed = true
            case .refused(let status): NSLog("claudia live: the hub refused the end of an activity (\(status))")
            }
        }
        return failed
    }

    private func remember(_ id: String, _ value: String?) {
        told[id] = value
        UserDefaults.standard.set(told, forKey: Self.toldKey)
    }
}
