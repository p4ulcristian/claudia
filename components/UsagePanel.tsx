"use client";

import type { UsageData } from "@/lib/usage";

function Bar({ pct }: { pct: number }) {
  const hot = pct >= 80;
  const warm = pct >= 50;
  return (
    <div className="usage-bar">
      <div
        className={`usage-bar-fill ${hot ? "hot" : warm ? "warm" : ""}`}
        style={{ width: `${Math.min(100, Math.max(2, pct))}%` }}
      />
    </div>
  );
}

export default function UsagePanel({
  data,
  loading,
  error,
  onRefresh,
  onClose,
}: {
  data: UsageData | null;
  loading: boolean;
  error: string | null;
  onRefresh: () => void;
  onClose: () => void;
}) {
  const s = data?.session;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal usage-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <div className="modal-title">📊 Usage</div>
          <button className="btn ghost" disabled={loading} onClick={onRefresh}>
            {loading ? "…" : "↻ Refresh"}
          </button>
          <button className="icon-btn" onClick={onClose}>
            ✕
          </button>
        </div>

        <div className="usage-body">
          {loading && !data ? (
            <div className="muted center pad">
              <span className="working-dots">
                <i />
                <i />
                <i />
              </span>
              <div style={{ marginTop: 12 }}>Reading /usage from the Claude TUI…</div>
            </div>
          ) : error ? (
            <div className="error mono">error: {error}</div>
          ) : data ? (
            <>
              <div className="usage-section-title">Limits</div>
              {data.limits.length === 0 ? (
                <div className="muted">No limit data found.</div>
              ) : (
                data.limits.map((l) => (
                  <div key={l.name} className="usage-limit">
                    <div className="usage-limit-head">
                      <span className="usage-limit-name">{l.name}</span>
                      <span className="usage-limit-pct">{l.percentUsed}%</span>
                    </div>
                    <Bar pct={l.percentUsed} />
                    {l.resets ? <div className="usage-reset">resets {l.resets}</div> : null}
                  </div>
                ))
              )}

              {s && (s.totalCostUsd !== undefined || s.tokens) ? (
                <>
                  <div className="usage-section-title">This session</div>
                  <div className="usage-grid">
                    {s.totalCostUsd !== undefined && (
                      <div className="usage-stat">
                        <div className="usage-stat-k">cost</div>
                        <div className="usage-stat-v">${s.totalCostUsd.toFixed(4)}</div>
                      </div>
                    )}
                    {s.wallDuration && (
                      <div className="usage-stat">
                        <div className="usage-stat-k">wall</div>
                        <div className="usage-stat-v">{s.wallDuration}</div>
                      </div>
                    )}
                    {s.codeChanges && (
                      <div className="usage-stat">
                        <div className="usage-stat-k">code</div>
                        <div className="usage-stat-v">
                          +{s.codeChanges.added} / -{s.codeChanges.removed}
                        </div>
                      </div>
                    )}
                    {s.tokens && (
                      <div className="usage-stat">
                        <div className="usage-stat-k">tokens (in/out)</div>
                        <div className="usage-stat-v">
                          {s.tokens.input} / {s.tokens.output}
                        </div>
                      </div>
                    )}
                  </div>
                </>
              ) : null}

              {data.insights.length > 0 && (
                <>
                  <div className="usage-section-title">Contributing to your limits</div>
                  <ul className="usage-insights">
                    {data.insights.map((t, i) => (
                      <li key={i}>{t}</li>
                    ))}
                  </ul>
                </>
              )}

              {(data.skills.length > 0 || data.subagents.length > 0) && (
                <div className="usage-grid">
                  {data.skills.length > 0 && (
                    <div>
                      <div className="usage-section-title">Skills</div>
                      {data.skills.map((x) => (
                        <div key={x.name} className="usage-kv">
                          <span>{x.name}</span>
                          <span className="badge">{x.percent}%</span>
                        </div>
                      ))}
                    </div>
                  )}
                  {data.subagents.length > 0 && (
                    <div>
                      <div className="usage-section-title">Subagents</div>
                      {data.subagents.map((x) => (
                        <div key={x.name} className="usage-kv">
                          <span>{x.name}</span>
                          <span className="badge">{x.percent}%</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}

              {data.capturedAt && (
                <div className="usage-foot muted">
                  captured {new Date(data.capturedAt).toLocaleTimeString()} ·
                  auto-refreshes every 10 min · approximate, from local sessions on this
                  machine
                </div>
              )}
            </>
          ) : null}
        </div>
      </div>
    </div>
  );
}
