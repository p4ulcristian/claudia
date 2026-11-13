import Foundation
#if canImport(AlarmKit)
import AlarmKit
import SwiftUI
#endif

/// An alarm the person asked Claude for (the hub's `kind: "alarm"` push): rung by AlarmKit
/// (iOS 26 and later), full screen on the lock screen and through the silent switch and Focus,
/// until stopped. The Notification Service Extension rings it when such a push arrives; the app
/// asks for the permission. On older iOS, or without the permission, the push's notification
/// is all that shows.
public enum Alarm {
    /// Asks for the alarm permission once (the first launch on iOS 26 and later); later calls
    /// do nothing.
    public static func askPermission() {
        #if canImport(AlarmKit)
        guard #available(iOS 26.0, *), AlarmManager.shared.authorizationState == .notDetermined else { return }
        Task {
            do { _ = try await AlarmManager.shared.requestAuthorization() }
            catch { NSLog("claudia alarm: permission not asked: \(error.localizedDescription)") }
        }
        #endif
    }

    /// Rings now with `title` on the alarm. Throws when AlarmKit refuses (no permission); does
    /// nothing before iOS 26.
    public static func ring(title: String) async throws {
        #if canImport(AlarmKit)
        guard #available(iOS 26.0, *) else { return }
        let attributes = AlarmAttributes<ClaudiaAlarm>(presentation: AlarmPresentation(alert: alert(title)),
                                                       tintColor: .orange)
        let configuration = AlarmManager.AlarmConfiguration<ClaudiaAlarm>
            .alarm(schedule: .fixed(Date().addingTimeInterval(1)), attributes: attributes)
        _ = try await AlarmManager.shared.schedule(id: UUID(), configuration: configuration)
        #endif
    }

    #if canImport(AlarmKit)
    /// iOS 26.1 draws its own stop button; 26.0 needs one given.
    @available(iOS 26.0, *)
    private static func alert(_ title: String) -> AlarmPresentation.Alert {
        let text = LocalizedStringResource(stringLiteral: title)
        if #available(iOS 26.1, *) { return AlarmPresentation.Alert(title: text) }
        return AlarmPresentation.Alert(title: text, stopButton: AlarmButton(text: "Stop", textColor: .white,
                                                                            systemImageName: "stop.circle"))
    }
    #endif
}

#if canImport(AlarmKit)
/// The alarm carries nothing beyond its title.
@available(iOS 26.0, *)
struct ClaudiaAlarm: AlarmMetadata {}
#endif
