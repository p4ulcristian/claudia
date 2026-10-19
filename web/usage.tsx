// Model and effort pickers (inside the input box) and the usage line under it.
import React from "react";
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

function resets(at: string | null) {
  if (!at) return "";
  const d = new Date(at);
  const hours = (d.getTime() - Date.now()) / 3_600_000;
  const time = d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  return hours < 20 ? time : `${d.toLocaleDateString(undefined, { weekday: "short" })} ${time}`;
}

const level = (p: number) => (p >= 90 ? "red" : p >= 70 ? "amber" : "ok");

function Meter({ label, percent, title }: { label: string; percent: number; title: string }) {
  return (
    <span className={`meter ${level(percent)}`} title={title}>
      <span className="m-label">{label}</span>
      <span className="m-bar"><span style={{ width: `${Math.min(100, percent)}%` }} /></span>
      <span className="m-num">{percent}%</span>
    </span>
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

export function Meters({ meta, context }: { meta: Meta; context: Context | null }) {
  if (!context && !meta.limits.length) return null;
  // The account comes from the desktop widget (claude-account); "me" is the user's own.
  const plan = meta.account && meta.account !== "me" ? `${meta.account[0].toUpperCase()}${meta.account.slice(1)}'s plan` : "Your plan";
  return (
    <div className="meters">
      {context && <Meter label="Context" percent={context.percent} title={`${tokens(context.used)} of ${tokens(context.max)} tokens (${prettyModel(context.model)})`} />}
      {meta.account && <span className="m-account" title="Switch in the Claude section of the desktop widget">{plan}</span>}
      {meta.limits.map((l: Limit) => (
        <Meter key={l.key} label={l.label} percent={l.percent} title={`${plan}, ${l.label} limit: ${l.percent}% used${l.resetsAt ? `, resets ${resets(l.resetsAt)}` : ""}`} />
      ))}
    </div>
  );
}
