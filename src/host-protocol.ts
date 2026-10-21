// What the runner and the session host (host.ts) say to each other over the host's unix socket, one
// JSON object per line. The host owns the Claude Code processes, so the runner can restart without
// stopping chats; everything the SDK would call back into (tools, hooks, permission asks) comes to
// the runner as an rpc and waits while the runner is away.
import type { SDKMessage, SDKUserMessage } from "@anthropic-ai/claude-agent-sdk";

// A tool the host serves on the runner's behalf; schema is the input's JSON Schema.
export type ToolDef = { name: string; description: string; schema: Record<string, unknown> };
export type McpDef = { version: string; alwaysLoad?: boolean; tools: ToolDef[] };

export type StartSpec = {
  // Plain SDK query options (no functions).
  options: Record<string, unknown>;
  mcp: Record<string, McpDef>;
  // Hook event → matchers; each match is asked of the runner.
  hooks: Record<string, { matcher?: string }[]>;
};

export type RpcKind = "mcp" | "hook" | "canUseTool";
export type Rpc = { req: number; kind: RpcKind; args: any };

// A session that is alive in the host, as the runner gets it when it connects.
export type LiveSession = {
  id: string;
  busy: boolean; // a turn is running (a message was pushed and its result hasn't come)
  kv: Record<string, unknown>; // what the runner keeps there for itself (turn, files' before-copies, …)
  buffer: SDKMessage[]; // this turn's messages, plus what still describes background tasks
  pending: Rpc[]; // rpcs nobody answered yet
};

export type CallMethod = "interrupt" | "setModel" | "applyFlagSettings" | "stopTask" | "getContextUsage";

export type ToHost =
  | { op: "hello" }
  | { op: "start"; id: string; spec: StartSpec }
  | { op: "push"; id: string; message: SDKUserMessage }
  | { op: "call"; id: string; req: number; method: CallMethod; args: unknown[] }
  | { op: "close"; id: string }
  | { op: "kv"; id: string; key: string; value?: unknown } // no value: delete
  | { op: "reply"; req: number; result?: unknown; error?: string };

export type FromHost =
  | { op: "sessions"; sessions: LiveSession[] }
  | { op: "msg"; id: string; m: SDKMessage }
  | { op: "end"; id: string; error?: string }
  | ({ op: "rpc"; id: string } & Rpc)
  | { op: "cancel"; req: number }
  | { op: "result"; req: number; result?: unknown; error?: string };

export const HOST_SOCKET = process.env.CLAUDIA_HOST_SOCKET ?? `${process.env.XDG_RUNTIME_DIR ?? "/tmp"}/claudia-host.sock`;
