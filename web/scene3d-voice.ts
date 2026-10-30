// Narration for a scene3d: Claudia's runner turns each line into speech (POST /api/narrate, raw 16-bit
// mono PCM at 24 kHz, kept on disk by text); this plays it with the Web Audio clock, so a clip's length
// is known exactly and pausing pauses the voice with the story.
const RATE = 24000;

export type Clip = { buffer: AudioBuffer; seconds: number };

// How the page reaches its server: plain fetch on a server's own page, /s/<server>/… through the hub.
export type Fetcher = (path: string, init?: RequestInit) => Promise<Response>;

// The voice lives in the hub when the page is served by one (every server then has it), else in the
// server's own runner. `hub` is the page's fetch to the hub itself, or null on a server's own page.
export class Voice {
  private base: Fetcher;
  constructor(private server: Fetcher, private hub: Fetcher | null) { this.base = server; }
  private ask = (path: string, init?: RequestInit) => this.base(path, init);
  private ctx: AudioContext | null = null;
  private clips = new Map<string, Promise<Clip | null>>();
  private source: AudioBufferSourceNode | null = null;
  error = "";

  // is a voice set up on this server
  async available(): Promise<boolean> {
    const tries: [Fetcher, string][] = [];
    if (this.hub) tries.push([this.hub, "/hub/narrate"]);
    tries.push([this.server, "/api/narrate"]);
    for (const [ask, path] of tries) {
      try {
        const r = await ask(path);
        if (r.ok && (await r.json()).voice) {
          this.base = (_p, init) => ask(path, init);
          return true;
        }
      } catch { /* try the next place */ }
    }
    return false;
  }

  private context() {
    return (this.ctx ??= new AudioContext({ sampleRate: RATE }));
  }

  load(text: string, instruction?: string): Promise<Clip | null> {
    const key = `${instruction ?? ""}\n${text}`;
    let p = this.clips.get(key);
    if (!p) {
      p = this.fetchClip(text, instruction);
      this.clips.set(key, p);
    }
    return p;
  }
  peek(text: string, instruction?: string) { return this.clips.get(`${instruction ?? ""}\n${text}`); }

  private async fetchClip(text: string, instruction?: string): Promise<Clip | null> {
    try {
      const r = await this.ask("/api/narrate", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ text, instruction }) });
      if (!r.ok) throw new Error((await r.json().catch(() => ({}))).error ?? `voice answered ${r.status}`);
      const bytes = new Uint8Array(await r.arrayBuffer());
      const pcm = new Int16Array(bytes.buffer, 0, Math.floor(bytes.byteLength / 2));
      const buffer = this.context().createBuffer(1, pcm.length, RATE);
      const out = buffer.getChannelData(0);
      for (let i = 0; i < pcm.length; i++) out[i] = pcm[i] / 32768;
      return { buffer, seconds: pcm.length / RATE };
    } catch (e) {
      this.error = (e as Error).message;
      return null;
    }
  }

  // starts the clip; a gesture-less page may start suspended, resume() asks for the clock to run
  async play(clip: Clip) {
    this.stop();
    const ctx = this.context();
    if (ctx.state === "suspended") await ctx.resume().catch(() => {});
    const src = ctx.createBufferSource();
    src.buffer = clip.buffer;
    src.connect(ctx.destination);
    src.start();
    this.source = src;
  }
  stop() {
    try { this.source?.stop(); } catch { /* already stopped */ }
    this.source = null;
  }
  pause() { void this.ctx?.suspend(); }
  resume() { void this.ctx?.resume(); }
  running() { return this.ctx?.state === "running"; }
  close() {
    this.stop();
    void this.ctx?.close();
    this.ctx = null;
  }
}
