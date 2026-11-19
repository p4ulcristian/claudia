// Every npm package out/hub.js and out/broker.js load, and everything those load, must be copied into the hub
// image (deploy/hub.Dockerfile, and let into its build context by its .dockerignore), which has no node_modules of its own. A missing one only shows
// when the gate starts the image (2026-11-18: sshpk), so this runs in the check after the build.
import { readFileSync } from "node:fs";
import { builtinModules } from "node:module";

// The hub and the key broker (README decision 66) share the image.
const hub = readFileSync("out/hub.js", "utf8") + readFileSync("out/broker.js", "utf8");
const docker = readFileSync("deploy/hub.Dockerfile", "utf8");
// The build context lets in only what the dockerignore names (2026-11-18: a COPY without its line).
const ignore = readFileSync("deploy/hub.Dockerfile.dockerignore", "utf8");
const letIn = new Set([...ignore.matchAll(/^!node_modules\/(\S+)$/gm)].map((m) => m[1]));
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

const missing = [...closure(direct)].filter((n) => !copied.has(n) || !letIn.has(n));
if (missing.length) {
  console.error(`hub image lacks: ${missing.join(" ")} (each needs a COPY line in deploy/hub.Dockerfile and a !node_modules/<name> line in its .dockerignore)`);
  process.exit(1);
}
console.log(`hub image deps: ${[...closure(direct)].join(" ")}`);
