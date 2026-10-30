// Runs the scene's script (the `script` field Claude sends) in QuickJS, a separate JavaScript engine
// compiled to WebAssembly: the script cannot see window, the DOM, cookies or the network, runs out of
// time rather than freezing the tab, and exchanges only JSON with the page. It defines
//   build()   -> { parts?, arrows? }   parts and arrows made by code (loops, maths)
//   frame(t, step) -> { [partId]: { pos?, rot?, size?, color?, opacity?, from?, to? } }   t = seconds, step = the story's current step (-1 before it starts, -1 without a story)
import variant from "@jitl/quickjs-singlefile-browser-release-sync";
import { newQuickJSWASMModuleFromVariant, type QuickJSContext, type QuickJSHandle, type QuickJSRuntime } from "quickjs-emscripten-core";

const BUILD_MS = 500;
const FRAME_MS = 40;
const MEMORY = 32 * 1024 * 1024;

export type SceneScript = {
  build: () => { parts?: any[]; arrows?: any[] };
  frame: ((t: number, step: number) => Record<string, any>) | null;
  close: () => void;
};

let engine: ReturnType<typeof newQuickJSWASMModuleFromVariant> | null = null;

export async function loadScript(source: string): Promise<SceneScript> {
  engine ??= newQuickJSWASMModuleFromVariant(variant);
  const QuickJS = await engine;
  const rt: QuickJSRuntime = QuickJS.newRuntime();
  rt.setMemoryLimit(MEMORY);
  rt.setMaxStackSize(512 * 1024);
  let deadline = Infinity;
  rt.setInterruptHandler(() => performance.now() > deadline);
  const ctx: QuickJSContext = rt.newContext();

  const failure = (err: QuickJSHandle): Error => {
    const e = ctx.dump(err);
    err.dispose();
    return new Error(typeof e === "object" && e ? `${e.name ?? "Error"}: ${e.message ?? ""}` : String(e));
  };
  const run = (code: string, ms: number) => {
    deadline = performance.now() + ms;
    const r = ctx.evalCode(code, "scene.js");
    deadline = Infinity;
    if (r.error) throw failure(r.error);
    r.value.dispose();
  };
  const fn = (name: string): QuickJSHandle | null => {
    const h = ctx.getProp(ctx.global, name);
    if (ctx.typeof(h) === "function") return h;
    h.dispose();
    return null;
  };
  const call = (h: QuickJSHandle, ms: number, ...args: number[]): any => {
    const a = args.map((n) => ctx.newNumber(n));
    deadline = performance.now() + ms;
    const r = ctx.callFunction(h, ctx.undefined, ...a);
    deadline = Infinity;
    a.forEach((x) => x.dispose());
    if (r.error) throw failure(r.error);
    const s = ctx.getString(r.value);
    r.value.dispose();
    return s ? JSON.parse(s) : {};
  };

  try {
    run(source, BUILD_MS);
    run(
      `globalThis.__build = typeof build === "function" ? () => JSON.stringify(build()) : null;
       globalThis.__frame = typeof frame === "function" ? (t, s) => JSON.stringify(frame(t, s)) : null;`,
      BUILD_MS,
    );
  } catch (e) {
    ctx.dispose();
    rt.dispose();
    throw e;
  }
  const b = fn("__build");
  const f = fn("__frame");
  return {
    build: () => (b ? call(b, BUILD_MS) ?? {} : {}),
    frame: f ? (t, step) => call(f, FRAME_MS, t, step) : null,
    close: () => {
      b?.dispose();
      f?.dispose();
      ctx.dispose();
      rt.dispose();
    },
  };
}
