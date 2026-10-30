// The scene3d form: parts, arrows and labels in a three.js scene the user can rotate (drag), zoom
// (wheel / pinch) and pan (right drag). The scene is rebuilt whenever the data changes; the camera
// stays where the user put it. three loads on first use, so pictures without a scene cost nothing.
import React, { Suspense, lazy } from "react";

const View = lazy(() => import("./scene3d-view"));

export function Scene3D({ data }: { data: Record<string, any> }) {
  return (
    <Suspense fallback={<p className="f-note">Loading the 3D view…</p>}>
      <View data={data} />
    </Suspense>
  );
}
