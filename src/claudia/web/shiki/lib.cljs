(ns claudia.web.shiki.lib
  "Shiki's core, its regex engine (Oniguruma, WebAssembly inlined) and VS Code's Dark+ theme, from
  npm: the :shiki module, loaded the first time code is shown (claudia.web.shiki). The grammars are
  Shiki's own files, copied into js/shiki-langs/ at build (bin/shiki-langs.mjs), one per language."
  (:require ["shiki/core" :refer [createHighlighterCore]]
            ["shiki/engine/oniguruma" :refer [createOnigurumaEngine]]
            ["shiki/wasm" :as wasm]
            ["shiki/dist/themes/dark-plus.mjs" :default dark-plus]
            [claudia.web.shiki :as shiki]))

(defn highlighter
  "Promise of a highlighter with the Dark+ theme and no language yet."
  []
  (createHighlighterCore #js {:themes #js [dark-plus]
                              :langs #js []
                              :engine (createOnigurumaEngine (js/Promise.resolve wasm))}))

(shiki/provide! highlighter)
