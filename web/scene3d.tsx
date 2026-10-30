// The scene3d form: parts, arrows and labels in a three.js scene the user can rotate (drag), zoom
// (wheel / pinch) and pan (right drag); a click on a part opens its properties, with Explain.
// three loads on first use, so pictures without a scene cost nothing.
import React, { Suspense, lazy } from "react";

const View = lazy(() => import("./scene3d-view"));

export function Scene3D({ data, chat, block, live }: { data: Record<string, any>; chat?: string; block?: string; live?: boolean }) {
  return (
    <Suspense fallback={<p className="f-note">Loading the 3D view…</p>}>
      <View data={data} chat={chat} block={block} live={live} />
    </Suspense>
  );
}
