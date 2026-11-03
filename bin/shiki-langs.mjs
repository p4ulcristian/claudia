#!/usr/bin/env node
// Shiki's grammars for the page (claudia.web.shiki): every grammar file of the installed Shiki
// (the exact version in package-lock.json) copied into out/web/js/shiki-langs/, and index.json there
// naming the file of every language name and alias Shiki knows ({"js": "javascript", ...}). The page
// imports one file the first time code in that language is shown, so it never loads code from a CDN.
//
//   node bin/shiki-langs.mjs      (npm run build:web-files runs it)

import { bundledLanguagesInfo } from "shiki/langs";
import { cpSync, mkdirSync, readdirSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";

const root = new URL("..", import.meta.url).pathname;
const out = join(root, "out", "web", "js", "shiki-langs");
// Shiki's grammar files are @shikijs/langs, resolved as Shiki itself resolves it.
const shiki = dirname(createRequire(import.meta.url).resolve("shiki/package.json"));
const langs = dirname(createRequire(join(shiki, "package.json")).resolve("@shikijs/langs/javascript"));

const index = {};
for (const { id, aliases } of bundledLanguagesInfo) {
  for (const name of [id, ...(aliases ?? [])]) index[name] = id;
}

mkdirSync(out, { recursive: true });
for (const file of readdirSync(langs).filter((f) => f.endsWith(".mjs"))) {
  cpSync(join(langs, file), join(out, file));
}
writeFileSync(join(out, "index.json"), JSON.stringify(index));
console.log(`shiki-langs: ${Object.keys(index).length} names, ${bundledLanguagesInfo.length} languages -> ${out}`);
