// Every npm package out/hub.js loads, and everything those load, must be copied into the hub
// image (deploy/hub.Dockerfile), which has no node_modules of its own. A missing one only shows
// when the gate starts the image (2026-11-18: sshpk), so this runs in the check after the build.
import { readFileSync } from "node:fs";
import { builtinModules } from "node:module";

const hub = readFileSync("out/hub.js", "utf8");
const docker = readFileSync("deploy/hub.Dockerfile", "utf8");
const builtin = new Set(builtinModules.flatMap((m) => [m, `node:${m}`]));
const copied = new Set([...docker.matchAll(/^COPY node_modules\/(\S+) /gm)].map((m) => m[1]));
const top = (spec) => (spec.startsWith("@") ? spec.split("/").slice(0, 2) : spec.split("/").slice(0, 1)).join("/");
const direct = [...hub.matchAll(/require\(["']([^"'.][^"']*)["']\)/g)].map((m) => top(m[1])).filter((n) => !builtin.has(n) && !n.startsWith("node:"));

function closure(names, seen = new Set()) {
  for (const n of names) {
    if (seen.has(n)) continue;
    seen.add(n);
    const pkg = JSON.parse(readFileSync(`node_modules/${n}/package.json`, "utf8"));
    closure(Object.keys(pkg.dependencies || {}), seen);
  }
  return seen;
}

const missing = [...closure(direct)].filter((n) => !copied.has(n));
if (missing.length) {
  console.error(`hub image lacks: ${missing.join(" ")} (add COPY lines to deploy/hub.Dockerfile)`);
  process.exit(1);
}
console.log(`hub image deps: ${[...closure(direct)].join(" ")}`);
