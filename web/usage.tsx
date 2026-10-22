// Model and effort pickers (inside the input box) and the usage block at the foot of the sidebar.
import React, { useEffect, useState } from "react";
import { tipProps } from "./tip";
import type { Context, Limit, Meta, Settings } from "./types";

const tokens = (n: number) => (n >= 1_000_000 ? `${+(n / 1_000_000).toFixed(1)}M` : n >= 1000 ? `${Math.round(n / 1000)}k` : String(n));

// "claude-opus-5-5[1m]" → "Opus 5.5 1M"
export function prettyModel(id: string) {
  if (!id) return "";
  const long = /\[1m\]/i.test(id);
  const parts = id.replace(/\[.*\]/, "").replace(/^claude-/, "").split("-").filter((p) => !/^\d{8}$/.test(p));
  const family = parts.find((p) => /^[a-z]+$/i.test(p)) ?? parts[0];
  const version = parts.filter((p) => /^\d+$/.test(p)).join(".");
  return `${family[0].toUpperCase()}${family.slice(1)} ${version}${long ? " 1M" : ""}`.trim();
}

// When a limit resets: "today 14:30", "tomorrow 09:00", "Thu 23 Oct, 09:00".
function resetsAt(d: Date) {
  const time = d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  const day = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const days = Math.round((day(d) - day(new Date())) / 86_400_000);
  if (days === 0) return `today ${time}`;
  if (days === 1) return `tomorrow ${time}`;
  return `${d.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" })}, ${time}`;
}

// Time left: "2d 4h", "3h 12m", and seconds in the last hour ("14m 05s").
function countdown(ms: number) {
  if (ms <= 0) return "now";
  const s = Math.floor(ms / 1000);
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  if (d) return `${d}d ${h}h`;
  if (h) return `${h}h ${m}m`;
  return `${m}m ${String(sec).padStart(2, "0")}s`;
}

function useNow() {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  return now;
}

const level = (p: number) => (p >= 90 ? "red" : p >= 70 ? "amber" : "ok");

function Meter({ label, percent, title, children }: { label: string; percent: number; title: string; children?: React.ReactNode }) {
  return (
    <div className={`meter ${level(percent)}`} title={title}>
      <span className="m-label">{label}</span>
      <span className="m-bar"><span style={{ width: `${Math.min(100, percent)}%` }} /></span>
      <span className="m-num">{percent}%</span>
      {children}
    </div>
  );
}

export function Pickers(props: { meta: Meta; settings: Settings; model: string; onSettings: (s: Partial<Settings>) => void }) {
  const { meta, settings, model } = props;
  const chosen = meta.models.find((m) => m.value === (settings.model ?? "default")) ?? meta.models[0];
  const efforts = chosen?.efforts ?? [];
  const running = prettyModel(model || (settings.model ? "" : meta.defaultModel));
  return (
    <>
      <label className="pick model" title={running ? `Model: ${running}${settings.model ? "" : " (your default)"}` : "Model for this chat"}>
        <select value={settings.model ?? ""} onChange={(e) => props.onSettings({ model: e.target.value || null })}>
          <option value="">{!settings.model && running ? running : "Default model"}</option>
          {meta.models.filter((m) => m.value !== "default").map((m) => <option key={m.value} value={m.value}>{m.name}</option>)}
        </select>
      </label>
      {efforts.length > 0 && (
        <label className="pick effort" title={`Effort: ${settings.effort ?? "your default"}`}>
          <select value={settings.effort ?? ""} onChange={(e) => props.onSettings({ effort: e.target.value || null })}>
            <option value="">effort</option>
            {efforts.map((e) => <option key={e} value={e}>{e}</option>)}
          </select>
        </label>
      )}
    </>
  );
}

// saved = the Claude tokens saved in Settings; with two or more, the plan label switches between them.
export function Meters({ meta, context, saved, onSwitch }: { meta: Meta; context: Context | null; saved: string[]; onSwitch: (name: string) => void }) {
  const now = useNow();
  if (!context && !meta.limits.length) return null;
  // The token in use (src/account.ts), shared by every profile of this Claudia.
  const plan = meta.accountLabel ? `${meta.accountLabel}'s plan` : "Plan";
  return (
    <div className="meters">
      {context && <Meter label="Context" percent={context.percent} title={`This chat: ${tokens(context.used)} of ${tokens(context.max)} tokens (${prettyModel(context.model)})`} />}
      {meta.account && saved.length > 1 ? (
        <label className="pick m-account" title="Switch the Claude subscription for every chat here (applies on each chat's next message)">
          <select value={meta.accountLabel ?? ""} onChange={(e) => onSwitch(e.target.value)}>
            {!saved.includes(meta.accountLabel ?? "") && <option value="">{plan}</option>}
            {saved.map((n) => <option key={n} value={n}>{n}'s plan</option>)}
          </select>
        </label>
      ) : meta.account && <span className="m-account" title="Add or switch tokens in Settings">{plan}</span>}
      {meta.limits.map((l: Limit) => {
        const at = l.resetsAt ? new Date(l.resetsAt) : null;
        return (
          <Meter key={l.key} label={l.label} percent={l.percent} title={`${plan}, ${l.label} limit: ${l.percent}% used`}>
            {at && (
              <span className="m-reset">
                resets {resetsAt(at)} <b>in {countdown(at.getTime() - now)}</b>
              </span>
            )}
          </Meter>
        );
      })}
    </div>
  );
}

// The same meters for the closed sidebar: a chalk ring per meter with its percent, the details in the hover bubble.
const SHORT: Record<string, string> = { five_hour: "5h", seven_day: "wk", seven_day_opus: "op", seven_day_sonnet: "so" };

// With onClick the ring is a button: on hover its number turns into the action's icon.
function Ring({ short, percent, tip, icon, onClick }: { short: string; percent: number; tip: string; icon?: React.ReactNode; onClick?: () => void }) {
  const r = 14, len = 2 * Math.PI * r;
  const Tag = onClick ? "button" : "div";
  return (
    <Tag className={`ring ${level(percent)} ${onClick ? "action" : ""}`} onClick={onClick} {...tipProps(tip)}>
      <svg viewBox="0 0 34 34" aria-hidden="true">
        <circle className="r-track" cx="17" cy="17" r={r} />
        <circle className="r-fill" cx="17" cy="17" r={r} strokeDasharray={`${(Math.min(100, percent) / 100) * len} ${len}`} transform="rotate(-90 17 17)" />
        {onClick && <g className="r-icon" transform="translate(17 17) scale(0.7) translate(-17 -17)">{icon}</g>}
      </svg>
      <span className="r-num">{percent}</span>
      <span className="r-label">{short}</span>
    </Tag>
  );
}

// Two arrows pressing onto a line: compact.
const compactIcon = <path d="M17 7.5v6m-3.2-3.2 3.2 3.2 3.2-3.2M17 26.5v-6m-3.2 3.2 3.2-3.2 3.2 3.2M10.5 17h13" />;

// onCompact: runs /compact in the open chat (undefined while a turn runs or the chat is read only).
export function RailMeters({ meta, context, onCompact }: { meta: Meta; context: Context | null; onCompact?: () => void }) {
  const now = useNow();
  if (!context && !meta.limits.length) return null;
  return (
    <div className="rail-meters">
      {context && (
        <Ring
          short="ctx"
          percent={context.percent}
          tip={`Context ${context.percent}% · ${tokens(context.used)} of ${tokens(context.max)}${onCompact ? " · click to compact" : ""}`}
          icon={compactIcon}
          onClick={onCompact}
        />
      )}
      {meta.limits.map((l) => {
        const at = l.resetsAt ? new Date(l.resetsAt) : null;
        const reset = at ? ` · in ${countdown(at.getTime() - now)} (${resetsAt(at)})` : "";
        return <Ring key={l.key} short={SHORT[l.key] ?? l.label} percent={l.percent} tip={`${l.label} ${l.percent}%${reset}`} />;
      })}
    </div>
  );
}
