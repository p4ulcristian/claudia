import ActivityKit
import ClaudiaShared
import SwiftUI
import WidgetKit

/// The widget extension (PlugIns/Widget.appex): only the summary Live Activity.
@main
struct ClaudiaWidgets: WidgetBundle {
    var body: some Widget {
        SummaryActivity()
    }
}

/// The app's dark look: the icon's tile, Claudia's orange, the yellow of a question.
extension Color {
    static let tile = Color(red: 0x2c / 255, green: 0x38 / 255, blue: 0x33 / 255)
    static let accent = Color(red: 0xD9 / 255, green: 0x77 / 255, blue: 0x57 / 255)
    static let ask = Color(red: 0xF2 / 255, green: 0xE3 / 255, blue: 0xA0 / 255)
    static let alarm = Color(red: 0xE5 / 255, green: 0x53 / 255, blue: 0x4B / 255)
    static let ink = Color(red: 0xEE / 255, green: 0xEC / 255, blue: 0xE6 / 255)
    static let muted = Color(red: 0xA3 / 255, green: 0xAE / 255, blue: 0xA8 / 255)
}

private typealias State = ClaudiaSummaryAttributes.ContentState
private typealias Item = ClaudiaSummaryAttributes.Item

private extension State {
    /// The lock screen card's rows: the waiting chats first (up to two), then running ones,
    /// up to three running and four rows in all.
    var rows: (waiting: [Item], running: [Item]) {
        let w = Array(waiting.prefix(2))
        return (w, Array(running.prefix(min(3, 4 - w.count))))
    }

    /// A tap: the waiting chat when exactly one waits, else the chat list.
    var link: URL? {
        if waiting.count == 1, let one = waiting.first { return HubLink.chat(server: one.server, chat: one.chat) }
        return HubLink.url(path: "/")
    }

    var counts: String {
        var parts = ["\(running.count) running"]
        if !waiting.isEmpty { parts.append("\(waiting.count) waiting") }
        if done > 0 { parts.append("\(done) done") }
        return parts.joined(separator: " · ")
    }
}

private extension Item {
    var start: Date { Date(timeIntervalSince1970: TimeInterval(since)) }
    var isPermission: Bool { kind == "permission" }
    var symbol: String { isPermission ? "hand.raised.fill" : "questionmark.bubble.fill" }
    var tint: Color { isPermission ? .alarm : .ask }
    var waitLabel: String { isPermission ? "Permission" : "Question" }
}

/// Time since `since`, counting up by itself (no updates needed).
private struct Elapsed: View {
    let since: Date

    var body: some View {
        Text(timerInterval: since...Date.distantFuture, countsDown: false)
            .monospacedDigit()
            .multilineTextAlignment(.trailing)
            .frame(width: 58, alignment: .trailing)
    }
}

private struct WaitingRow: View {
    let item: Item

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: item.symbol)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(item.tint)
                .frame(width: 18)
            Text(item.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.ink)
                .lineLimit(1)
            Spacer(minLength: 4)
            Text(item.waitLabel)
                .font(.caption.weight(.semibold))
                .foregroundStyle(item.tint)
        }
    }
}

private struct RunningRow: View {
    let item: Item

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(Color.accent)
                .frame(width: 7, height: 7)
                .frame(width: 18)
            Text(item.title)
                .font(.subheadline)
                .foregroundStyle(Color.ink)
                .lineLimit(1)
            Spacer(minLength: 4)
            Elapsed(since: item.start)
                .font(.caption)
                .foregroundStyle(Color.muted)
        }
    }
}

/// The waiting and running rows, and "+N more" under them for the ones that don't fit.
private struct Rows: View {
    let state: State

    var body: some View {
        let rows = state.rows
        let hidden = state.waiting.count + state.running.count - rows.waiting.count - rows.running.count
        VStack(alignment: .leading, spacing: 5) {
            ForEach(rows.waiting) { WaitingRow(item: $0) }
            ForEach(rows.running) { RunningRow(item: $0) }
            if hidden > 0 {
                Text("+\(hidden) more")
                    .font(.caption)
                    .foregroundStyle(Color.muted)
                    .padding(.leading, 26)
            }
        }
    }
}

struct SummaryActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: ClaudiaSummaryAttributes.self) { context in
            let state = context.state
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 6) {
                    Text("CLAUDIA")
                        .font(.system(size: 11, weight: .bold))
                        .tracking(1.5)
                        .foregroundStyle(Color.accent)
                    Spacer(minLength: 4)
                    if context.isStale {
                        // The hub stopped updating it (gone, or the phone offline).
                        Text("updated \(Date(timeIntervalSince1970: TimeInterval(state.updatedAt)), style: .relative) ago")
                            .font(.caption2)
                            .foregroundStyle(Color.muted)
                    } else {
                        Text(state.counts)
                            .font(.caption)
                            .foregroundStyle(Color.muted)
                    }
                }
                if state.running.isEmpty && state.waiting.isEmpty {
                    Text("Nothing running")
                        .font(.subheadline)
                        .foregroundStyle(Color.ink)
                } else {
                    Rows(state: state)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .activityBackgroundTint(Color.tile)
            .activitySystemActionForegroundColor(Color.ink)
            .widgetURL(state.link)
        } dynamicIsland: { context in
            let state = context.state
            let waits = !state.waiting.isEmpty
            let mark = waits ? Color.alarm : Color.accent
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label {
                        Text("\(state.running.count)").monospacedDigit()
                    } icon: {
                        Image(systemName: "sparkle")
                    }
                    .font(.headline)
                    .foregroundStyle(Color.accent)
                    .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if waits {
                        Label("\(state.waiting.count)", systemImage: "exclamationmark.bubble.fill")
                            .font(.headline)
                            .foregroundStyle(Color.alarm)
                            .padding(.trailing, 4)
                    } else if state.done > 0 {
                        Label("\(state.done)", systemImage: "checkmark")
                            .font(.headline)
                            .foregroundStyle(Color.muted)
                            .padding(.trailing, 4)
                    }
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(state.counts)
                        .font(.caption)
                        .foregroundStyle(Color.muted)
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Rows(state: state).padding(.top, 4)
                }
            } compactLeading: {
                Image(systemName: waits ? "exclamationmark.bubble.fill" : "sparkle")
                    .foregroundStyle(mark)
            } compactTrailing: {
                Text("\(state.running.count)")
                    .monospacedDigit()
                    .fontWeight(.semibold)
                    .foregroundStyle(mark)
            } minimal: {
                Text("\(state.running.count)")
                    .monospacedDigit()
                    .fontWeight(.semibold)
                    .foregroundStyle(mark)
            }
            .keylineTint(mark)
            .widgetURL(state.link)
        }
    }
}
