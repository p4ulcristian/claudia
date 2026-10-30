// The picture on its own, for src/render.ts: a headless browser opens this page, hands the scene to
// window.__draw and takes the screenshot Claude gets back with its draw. No chat, socket or sidebar.
import React from "react";
import { createRoot } from "react-dom/client";
import { PictureView } from "./picture";
import { bakeChalk } from "./chalk";
import type { Scene } from "./types";
import "./style.css";

declare global {
  interface Window { __draw: (scene: Scene) => void; __drawn: boolean }
}

bakeChalk();
const root = createRoot(document.getElementById("root")!);
window.__draw = (scene) => {
  window.__drawn = false;
  root.render(
    <div className="stage" style={{ paddingBottom: 18 }}>
      <PictureView scene={scene} changed={[]} seq={1} />
    </div>,
  );
  // Two frames: React has committed and the layout effects (graph layout, 3D) have started.
  requestAnimationFrame(() => requestAnimationFrame(() => { window.__drawn = true; }));
};
