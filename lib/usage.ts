import os from "node:os";
import * as pty from "node-pty";
import { claudeBin } from "./claude-home";

// ---------------------------------------------------------------------------
// Reading `/usage` out of the Claude Code TUI.
//
// The TUI is a full-screen app that draws via cursor-positioning escapes, so it
// only renders against a real terminal. We spawn `claude` in a PTY, send
// `/usage`, feed the output through a tiny VT100 screen emulator (enough to
// reconstruct the visible grid), then parse the rendered text.
// ---------------------------------------------------------------------------

export interface UsageLimit {
  name: string;
  percentUsed: number;
  resets: string | null;
}
export interface UsageSession {
  totalCostUsd?: number;
  apiDuration?: string;
  wallDuration?: string;
  codeChanges?: { added: number; removed: number };
  tokens?: { input: number; output: number; cacheRead: number; cacheWrite: number };
}
export interface UsageData {
  limits: UsageLimit[];
  session: UsageSession;
  insights: string[];
  skills: { name: string; percent: number }[];
  subagents: { name: string; percent: number }[];
  capturedAt: string;
  raw?: string;
}

// ---- minimal VT100 screen ------------------------------------------------

class Screen {
  rows: number;
  cols: number;
  grid: string[][];
  r = 0;
  c = 0;

  constructor(cols: number, rows: number) {
    this.cols = cols;
    this.rows = rows;
    this.grid = Array.from({ length: rows }, () => Array(cols).fill(" "));
  }

  private clampR() {
    if (this.r < 0) this.r = 0;
    if (this.r >= this.rows) this.r = this.rows - 1;
  }
  private clampC() {
    if (this.c < 0) this.c = 0;
    if (this.c >= this.cols) this.c = this.cols - 1;
  }

  private put(ch: string) {
    if (this.c >= this.cols) {
      this.c = 0;
      this.r++;
    }
    this.clampR();
    this.grid[this.r][this.c] = ch;
    this.c++;
  }

  write(data: string) {
    for (let i = 0; i < data.length; i++) {
      const ch = data[i];
      if (ch === "\x1b") {
        const next = data[i + 1];
        if (next === "[") {
          // CSI: collect params until a final byte (0x40-0x7e)
          let j = i + 2;
          let seq = "";
          while (j < data.length) {
            const d = data[j];
            if (d >= "@" && d <= "~") break;
            seq += d;
            j++;
          }
          const final = data[j];
          this.csi(seq, final);
          i = j;
        } else if (next === "]") {
          // OSC: skip until BEL or ST (ESC \)
          let j = i + 2;
          while (j < data.length && data[j] !== "\x07") {
            if (data[j] === "\x1b" && data[j + 1] === "\\") {
              j++;
              break;
            }
            j++;
          }
          i = j;
        } else if (next === "(" || next === ")") {
          i += 2; // charset designation, skip the selector
        } else {
          i += 1; // other 2-byte escapes (=, >, M, ...)
        }
        continue;
      }
      if (ch === "\n") {
        this.r++;
        this.clampR();
      } else if (ch === "\r") {
        this.c = 0;
      } else if (ch === "\b") {
        this.c--;
        this.clampC();
      } else if (ch === "\t") {
        this.c = Math.min(this.cols - 1, (Math.floor(this.c / 8) + 1) * 8);
      } else if (ch >= " ") {
        this.put(ch);
      }
    }
  }

  private nums(seq: string): number[] {
    return seq
      .replace(/[?>]/g, "")
      .split(";")
      .map((x) => (x === "" ? NaN : Number(x)));
  }

  private csi(seq: string, final: string) {
    const p = this.nums(seq);
    const n = (i: number, d: number) => (Number.isNaN(p[i]) || p[i] === undefined ? d : p[i]);
    switch (final) {
      case "H":
      case "f":
        this.r = n(0, 1) - 1;
        this.c = n(1, 1) - 1;
        this.clampR();
        this.clampC();
        break;
      case "A": this.r -= n(0, 1); this.clampR(); break;
      case "B": this.r += n(0, 1); this.clampR(); break;
      case "C": this.c += n(0, 1); this.clampC(); break;
      case "D": this.c -= n(0, 1); this.clampC(); break;
      case "G": this.c = n(0, 1) - 1; this.clampC(); break;
      case "d": this.r = n(0, 1) - 1; this.clampR(); break;
      case "J": {
        const mode = n(0, 0);
        if (mode === 2 || mode === 3) {
          this.grid = Array.from({ length: this.rows }, () => Array(this.cols).fill(" "));
        } else if (mode === 0) {
          for (let x = this.c; x < this.cols; x++) this.grid[this.r][x] = " ";
          for (let y = this.r + 1; y < this.rows; y++) this.grid[y].fill(" ");
        } else if (mode === 1) {
          for (let y = 0; y < this.r; y++) this.grid[y].fill(" ");
          for (let x = 0; x <= this.c; x++) this.grid[this.r][x] = " ";
        }
        break;
      }
      case "K": {
        const mode = n(0, 0);
        if (mode === 0) for (let x = this.c; x < this.cols; x++) this.grid[this.r][x] = " ";
        else if (mode === 1) for (let x = 0; x <= this.c; x++) this.grid[this.r][x] = " ";
        else this.grid[this.r].fill(" ");
        break;
      }
      default:
        break; // SGR (m), mode set/reset (h/l), etc. — no effect on layout
    }
  }

  render(): string {
    return this.grid
      .map((row) => row.join("").replace(/\s+$/, ""))
      .join("\n")
      .replace(/^\n+/, "")
      .replace(/\n+$/, "");
  }
}

// ---- capture --------------------------------------------------------------

const COLS = 150;
const ROWS = 60;

function delay(ms: number): Promise<void> {
  return new Promise((res) => setTimeout(res, ms));
}

/** Spawn the TUI, send /usage, and return the rendered screen text. */
export async function captureUsageScreen(signal?: AbortSignal): Promise<string> {
  const screen = new Screen(COLS, ROWS);
  const env: NodeJS.ProcessEnv = { ...process.env, TERM: "xterm-256color" };
  delete env.CLAUDECODE;
  delete env.CLAUDE_CODE_ENTRYPOINT;

  const term = pty.spawn(claudeBin(), [], {
    name: "xterm-256color",
    cols: COLS,
    rows: ROWS,
    cwd: os.homedir(), // a trusted dir → no folder-trust prompt
    env: env as Record<string, string>,
  });

  let lastData = Date.now();
  term.onData((d) => {
    screen.write(d);
    lastData = Date.now();
  });

  let exited = false;
  term.onExit(() => {
    exited = true;
  });

  const kill = () => {
    try {
      term.write("\x1b"); // close panel
      term.write("\x03"); // ctrl-c
    } catch {
      /* ignore */
    }
    setTimeout(() => {
      try {
        if (!exited) term.kill();
      } catch {
        /* ignore */
      }
    }, 150);
  };
  signal?.addEventListener("abort", kill, { once: true });

  try {
    // wait for the TUI to settle (idle) before sending the command
    const bootStart = Date.now();
    while (Date.now() - lastData < 2500 && Date.now() - bootStart < 18000) {
      if (signal?.aborted || exited) break;
      await delay(250);
    }
    if (!exited) term.write("/usage\r");
    // give the panel + its async "scanning local sessions" breakdown time to draw
    await delay(4500);
    return screen.render();
  } finally {
    kill();
    signal?.removeEventListener("abort", kill);
  }
}

// ---- parse ----------------------------------------------------------------

export function parseUsage(screen: string): Omit<UsageData, "capturedAt"> {
  const lines = screen.split("\n").map((l) => l.trim());
  const out: Omit<UsageData, "capturedAt"> = {
    limits: [],
    session: {},
    insights: [],
    skills: [],
    subagents: [],
  };

  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/(\d+)%\s*used$/);
    if (m) {
      const nameLine = (lines[i - 1] || "").replace(/[█▌▍▎▏▐].*$/, "").trim();
      const reset = (lines[i + 1] || "").match(/^Resets\s+(.*)$/);
      if (nameLine) {
        out.limits.push({
          name: nameLine,
          percentUsed: Number(m[1]),
          resets: reset ? reset[1].trim() : null,
        });
      }
    }
  }

  const grab = (re: RegExp) => {
    for (const l of lines) {
      const x = l.match(re);
      if (x) return x;
    }
    return null;
  };
  const cost = grab(/Total cost:\s*\$([\d.]+)/);
  if (cost) out.session.totalCostUsd = Number(cost[1]);
  const api = grab(/Total duration \(API\):\s*(.+)/);
  if (api) out.session.apiDuration = api[1].trim();
  const wall = grab(/Total duration \(wall\):\s*(.+)/);
  if (wall) out.session.wallDuration = wall[1].trim();
  const code = grab(/Total code changes:\s*(\d+)\s*lines added,\s*(\d+)\s*lines removed/);
  if (code) out.session.codeChanges = { added: +code[1], removed: +code[2] };
  const tok = grab(
    /Usage:\s*(\d+)\s*input,\s*(\d+)\s*output,\s*(\d+)\s*cache read,\s*(\d+)\s*cache write/,
  );
  if (tok)
    out.session.tokens = {
      input: +tok[1],
      output: +tok[2],
      cacheRead: +tok[3],
      cacheWrite: +tok[4],
    };

  for (const l of lines) {
    if (/^\d+% (of your usage|.*context|.*sessions active)/.test(l)) out.insights.push(l);
  }

  let section: "skills" | "subagents" | null = null;
  for (const l of lines) {
    if (/^Skills\b/.test(l)) { section = "skills"; continue; }
    if (/^Subagents\b/.test(l)) { section = "subagents"; continue; }
    if (/^(What's|Approximate|Last 24h|d to day|Esc|Current|Total|Usage:)/.test(l) || l === "Session") {
      section = null;
    }
    const m = l.match(/^(\S.*?)\s+(\d+)%$/);
    if (section && m) out[section].push({ name: m[1].trim(), percent: +m[2] });
  }

  return out;
}

/** Capture and parse `/usage` into structured data. */
export async function getUsage(opts?: { signal?: AbortSignal; raw?: boolean }): Promise<UsageData> {
  const screen = await captureUsageScreen(opts?.signal);
  const parsed = parseUsage(screen);
  return {
    ...parsed,
    capturedAt: new Date().toISOString(),
    ...(opts?.raw ? { raw: screen } : {}),
  };
}
