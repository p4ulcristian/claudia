// Save the picture as a PNG, block by block onto one canvas the colour of the board:
// - graphs: each SVG copied with its computed styles written in (colours, chalk filter, the
//   CSS-set path shapes and positions) and the fonts embedded, then drawn as an image;
// - HTML blocks: they live in sandboxed frames the page can't read, so each frame draws itself
//   (pageDoc's snapshot script) and hands back a PNG;
// - forms, badges, and cards of older pictures: html-to-image.
import { toCanvas } from "html-to-image";

const RATIO = 2;
const PAD = 18;
const FONTS_CSS = "https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:ital,wght@0,400;0,700;1,400&family=Kalam:wght@400;700&family=JetBrains+Mono:wght@400;500&display=swap";

// The fonts as @font-face rules with the files inlined (latin subsets), fetched once.
let fontCss: Promise<string> | null = null;
function fonts() {
  fontCss ??= (async () => {
    const css = await (await fetch(FONTS_CSS)).text();
    const blocks = css.split(/(?=\/\* [\w-]+ \*\/)/).filter((b) => /\/\* latin(-ext)? \*\//.test(b));
    const out = await Promise.all(blocks.map(async (b) => {
      const url = /url\((https:[^)]+)\)/.exec(b)?.[1];
      if (!url) return "";
      const buf = await (await fetch(url)).arrayBuffer();
      let bin = "";
      for (const byte of new Uint8Array(buf)) bin += String.fromCharCode(byte);
      return b.replace(url, `data:font/woff2;base64,${btoa(bin)}`);
    }));
    return out.join("\n");
  })().catch(() => "");
  return fontCss;
}

const PROPS = ["fill", "fill-opacity", "stroke", "stroke-width", "stroke-dasharray", "stroke-linecap", "stroke-linejoin", "stroke-opacity", "opacity", "filter", "mask-image", "marker-end", "font-family", "font-size", "font-weight", "font-style", "text-anchor", "dominant-baseline", "visibility", "display"];

async function svgImage(svg: SVGSVGElement): Promise<HTMLImageElement> {
  const copy = svg.cloneNode(true) as SVGSVGElement;
  const from = [svg, ...svg.querySelectorAll("*")];
  const to = [copy, ...copy.querySelectorAll("*")];
  from.forEach((el, i) => {
    const cs = getComputedStyle(el);
    const t = to[i] as SVGElement;
    t.removeAttribute("class");
    t.setAttribute("style", PROPS.map((p) => `${p}:${cs.getPropertyValue(p)}`).join(";"));
    // Shapes and positions set through CSS (d: path(...), transform: translate) become attributes.
    const d = cs.getPropertyValue("d");
    if (d && d !== "none") t.setAttribute("d", d.replace(/^path\("(.*)"\)$/, "$1"));
    const tf = cs.getPropertyValue("transform");
    if (tf && tf !== "none" && el !== svg) t.setAttribute("transform", tf);
  });
  const r = svg.getBoundingClientRect();
  copy.setAttribute("width", String(r.width));
  copy.setAttribute("height", String(r.height));
  copy.setAttribute("xmlns", "http://www.w3.org/2000/svg");
  const style = document.createElementNS("http://www.w3.org/2000/svg", "style");
  style.textContent = await fonts();
  copy.insertBefore(style, copy.firstChild);
  const url = URL.createObjectURL(new Blob([new XMLSerializer().serializeToString(copy)], { type: "image/svg+xml" }));
  try {
    const img = new Image();
    await new Promise((ok, no) => { img.onload = ok; img.onerror = no; img.src = url; });
    return img;
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}

function frameImage(frame: HTMLIFrameElement): Promise<HTMLImageElement | null> {
  return new Promise((resolve) => {
    const id = Math.random().toString(36).slice(2);
    const done = (img: HTMLImageElement | null) => {
      clearTimeout(timer);
      removeEventListener("message", on);
      resolve(img);
    };
    const on = (e: MessageEvent) => {
      if (e.source !== frame.contentWindow || e.data?.claudiaSnapId !== id) return;
      if (!e.data.png) return done(null);
      const img = new Image();
      img.onload = () => done(img);
      img.onerror = () => done(null);
      img.src = e.data.png;
    };
    const timer = setTimeout(() => done(null), 10_000);
    addEventListener("message", on);
    frame.contentWindow?.postMessage({ claudiaSnap: id, ratio: RATIO }, "*");
  });
}

// Returns the file name it was saved under and whether the image is on the clipboard too.
export async function savePicture(el: HTMLElement, name: string): Promise<{ file: string; copied: boolean }> {
  const board = getComputedStyle(document.documentElement).getPropertyValue("--board").trim() || "#353535";
  const box = el.getBoundingClientRect();
  // As tall as the drawing reaches (the picture box itself may fill the whole pane).
  const bottom = Math.max(0, ...[...el.children].map((c) => c.getBoundingClientRect().bottom - box.top));
  const canvas = document.createElement("canvas");
  canvas.width = (el.scrollWidth + PAD * 2) * RATIO;
  canvas.height = (Math.ceil(bottom) + PAD * 2) * RATIO;
  const ctx = canvas.getContext("2d")!;
  ctx.fillStyle = board;
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  // Where an element sits on the canvas, clipped to the part of it that shows.
  const put = (img: CanvasImageSource, r: DOMRect, clip?: DOMRect) => {
    ctx.save();
    if (clip) {
      ctx.beginPath();
      ctx.rect((clip.left - box.left + PAD) * RATIO, (clip.top - box.top + PAD) * RATIO, clip.width * RATIO, clip.height * RATIO);
      ctx.clip();
    }
    ctx.drawImage(img, (r.left - box.left + PAD) * RATIO, (r.top - box.top + PAD) * RATIO, r.width * RATIO, r.height * RATIO);
    ctx.restore();
  };
  const jobs: Promise<void>[] = [];
  for (const svg of el.querySelectorAll<SVGSVGElement>(".diagram svg"))
    jobs.push(svgImage(svg).then((img) => put(img, svg.getBoundingClientRect(), svg.closest(".diagram")!.getBoundingClientRect())));
  for (const frame of el.querySelectorAll<HTMLIFrameElement>("iframe"))
    jobs.push(frameImage(frame).then((img) => { if (img) put(img, frame.getBoundingClientRect()); }));
  for (const card of el.querySelectorAll<HTMLElement>(".card:not(.k-page), .form-view, .block-head"))
    jobs.push(toCanvas(card, { pixelRatio: RATIO }).then((c) => put(c, card.getBoundingClientRect())));
  await Promise.all(jobs.map((j) => j.catch((e) => console.error("save picture: a part failed", e))));

  const blob = await new Promise<Blob | null>((ok) => canvas.toBlob(ok, "image/png"));
  if (!blob) throw new Error("no image");
  const file = `${(name || "picture").replace(/[^\p{L}\p{N}]+/gu, "-").replace(/^-|-$/g, "").slice(0, 60) || "picture"}.png`;
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = file;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  // Also on the clipboard, to paste straight into a chat or a message.
  const copied = await navigator.clipboard?.write([new ClipboardItem({ "image/png": blob })]).then(() => true, () => false) ?? false;
  return { file, copied };
}
