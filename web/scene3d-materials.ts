// Material presets for a scene3d part (`material: "brushed-steel"`): what the surface is made of,
// as physically based parameters. The part's own colour, when it gives one, tints the preset.
import * as THREE from "three";

type Preset = Partial<THREE.MeshPhysicalMaterialParameters> & { color?: string; opacity?: number };

export const PRESETS: Record<string, Preset> = {
  steel: { color: "#c8ccd0", metalness: 1, roughness: 0.32 },
  "brushed-steel": { color: "#b9bdc1", metalness: 1, roughness: 0.42, anisotropy: 0.8 },
  aluminium: { color: "#d9dde0", metalness: 1, roughness: 0.38 },
  chrome: { color: "#ffffff", metalness: 1, roughness: 0.04 },
  copper: { color: "#c87533", metalness: 1, roughness: 0.3 },
  brass: { color: "#c9a24a", metalness: 1, roughness: 0.28 },
  iron: { color: "#585b60", metalness: 0.9, roughness: 0.62 },
  plastic: { roughness: 0.42, metalness: 0, clearcoat: 0.35, clearcoatRoughness: 0.3 },
  "matte-plastic": { roughness: 0.8, metalness: 0 },
  rubber: { color: "#222426", roughness: 0.96, metalness: 0 },
  enamel: { roughness: 0.28, metalness: 0.1, clearcoat: 1, clearcoatRoughness: 0.08 },
  ceramic: { color: "#f1f1ee", roughness: 0.14, metalness: 0, clearcoat: 1, clearcoatRoughness: 0.05 },
  wood: { color: "#8a5a2b", roughness: 0.68, metalness: 0 },
  glass: { color: "#cfe6f2", roughness: 0.03, metalness: 0, clearcoat: 0.5, clearcoatRoughness: 0.02, opacity: 0.14, ior: 1.5, envMapIntensity: 0.6 },
};

// Without a material: the plain look the form always had, now lit by the environment.
const PLAIN: Preset = { roughness: 0.55, metalness: 0.05 };

export const isGlass = (p: Record<string, any>) => p.material === "glass";

export function materialFor(color: THREE.Color | null, p: Record<string, any>, baseOpacity: number): THREE.MeshPhysicalMaterial {
  const preset = (typeof p.material === "string" && PRESETS[p.material]) || null;
  const { color: presetColor, opacity, ...params } = { ...PLAIN, ...(preset ?? {}) } as Preset;
  const tint = color ?? new THREE.Color(presetColor ?? "#8fc4ea");
  const op = typeof p.opacity === "number" ? p.opacity : (opacity ?? baseOpacity);
  const mat = new THREE.MeshPhysicalMaterial({
    ...params,
    color: tint,
    side: THREE.DoubleSide,
    transparent: op < 1,
    opacity: op,
    wireframe: !!p.wire,
  });
  mat.userData.op = op;
  return mat;
}
