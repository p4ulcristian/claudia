// The diagram style of a scene3d: flat colour in three bands (cel shading) and a thin light outline
// round every part, like chalk on the board. The outline is the part's own shape pushed out along its
// normals and drawn from behind, a fixed number of screen pixels wide at any distance.
import * as THREE from "three";

// three bands: shade, body, light
const bands = new Uint8Array([120, 120, 120, 255, 200, 200, 200, 255, 255, 255, 255, 255]);
const gradient = new THREE.DataTexture(bands, 3, 1, THREE.RGBAFormat);
gradient.minFilter = gradient.magFilter = THREE.NearestFilter;
gradient.needsUpdate = true;

export const INK = new THREE.Color("#f3efe6");
export const FOCUS_INK = new THREE.Color("#ffc247");
export const OUTLINE_PX = 1.7;
export const FOCUS_PX = 3.4;

// The viewport size in CSS pixels, shared by every outline (set when the stage resizes).
export const viewport = { value: new THREE.Vector2(800, 500) };

export function toonMaterial(color: THREE.Color, op: number, wire: boolean): THREE.MeshToonMaterial {
  const mat = new THREE.MeshToonMaterial({ color, gradientMap: gradient, side: THREE.DoubleSide, transparent: op < 1, opacity: op, wireframe: wire });
  mat.userData.op = op;
  return mat;
}

const vertex = /* glsl */ `
  uniform float thickness;
  uniform vec2 viewport;
  void main() {
    vec4 clip = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
    vec3 n = normalize(normalMatrix * normal);
    vec2 dir = (projectionMatrix * vec4(n, 0.0)).xy;
    float len = length(dir);
    dir = len > 1e-5 ? dir / len : vec2(0.0);
    clip.xy += dir * (thickness * 2.0 / viewport) * clip.w;
    gl_Position = clip;
  }
`;
const fragment = /* glsl */ `
  uniform vec3 color;
  uniform float opacity;
  void main() { gl_FragColor = vec4(color, opacity); }
`;

export function outlineMaterial(): THREE.ShaderMaterial {
  return new THREE.ShaderMaterial({
    uniforms: { color: { value: INK.clone() }, opacity: { value: 1 }, thickness: { value: OUTLINE_PX }, viewport },
    vertexShader: vertex,
    fragmentShader: fragment,
    side: THREE.BackSide,
    transparent: true,
    depthWrite: false,
    toneMapped: false,
  });
}
