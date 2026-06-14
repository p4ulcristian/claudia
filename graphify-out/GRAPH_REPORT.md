# Graph Report - claudia  (2026-06-14)

## Corpus Check
- 45 files · ~35,866 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 347 nodes · 664 edges · 18 communities (14 shown, 4 thin omitted)
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `e6e66313`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_Community 0|Community 0]]
- [[_COMMUNITY_Community 1|Community 1]]
- [[_COMMUNITY_Community 2|Community 2]]
- [[_COMMUNITY_Community 3|Community 3]]
- [[_COMMUNITY_Community 4|Community 4]]
- [[_COMMUNITY_Community 5|Community 5]]
- [[_COMMUNITY_Community 6|Community 6]]
- [[_COMMUNITY_Community 7|Community 7]]
- [[_COMMUNITY_Community 8|Community 8]]
- [[_COMMUNITY_Community 9|Community 9]]
- [[_COMMUNITY_Community 10|Community 10]]
- [[_COMMUNITY_Community 11|Community 11]]
- [[_COMMUNITY_Community 12|Community 12]]
- [[_COMMUNITY_Community 13|Community 13]]
- [[_COMMUNITY_Community 14|Community 14]]
- [[_COMMUNITY_Community 15|Community 15]]
- [[_COMMUNITY_Community 16|Community 16]]
- [[_COMMUNITY_Community 17|Community 17]]

## God Nodes (most connected - your core abstractions)
1. `compilerOptions` - 16 edges
2. `ClaudeEvent` - 12 edges
3. `claudeHome()` - 10 edges
4. `sessionDir()` - 10 edges
5. `git()` - 9 edges
6. `Screen` - 9 edges
7. `GET()` - 8 edges
8. `json()` - 8 edges
9. `startJob()` - 7 edges
10. `setActive()` - 7 edges

## Surprising Connections (you probably didn't know these)
- `POST()` --calls--> `stopJob()`  [EXTRACTED]
  app/api/chat/stop/route.ts → lib/jobs.ts
- `POST()` --calls--> `setFolderColor()`  [EXTRACTED]
  app/api/folder-meta/route.ts → lib/folder-meta.ts
- `POST()` --calls--> `addFolder()`  [EXTRACTED]
  app/api/folders/route.ts → lib/folders.ts
- `DELETE()` --calls--> `removeFolder()`  [EXTRACTED]
  app/api/folders/route.ts → lib/folders.ts
- `POST()` --calls--> `setTitle()`  [EXTRACTED]
  app/api/session-title/route.ts → lib/session-title.ts

## Import Cycles
- None detected.

## Communities (18 total, 4 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.06
Nodes (42): addFolder(), browse(), deleteSession(), getCommit(), getCommitFileDiff(), getFolderMeta(), getFolders(), getGit() (+34 more)

### Community 1 - "Community 1"
Cohesion: 0.11
Nodes (29): codeUrl(), GitPanel(), LANE_COLORS, GET(), assertHash(), assignLanes(), commitDetail(), diffFiles() (+21 more)

### Community 2 - "Community 2"
Cohesion: 0.08
Nodes (23): AssistantBlock, asText(), DisplayItem, foldEvents(), isSpecialTool(), QuestionOption, QuestionSpec, TASK_TOOLS (+15 more)

### Community 3 - "Community 3"
Cohesion: 0.11
Nodes (23): GET(), DELETE(), GET(), hasSessions(), listDir(), normalize(), claudeModel(), encodePath() (+15 more)

### Community 4 - "Community 4"
Cohesion: 0.11
Nodes (26): ChatBody, GET(), Job, POST(), SSE_HEADERS, streamJob(), broadcast(), bySession (+18 more)

### Community 5 - "Community 5"
Cohesion: 0.09
Nodes (22): dependencies, @fortawesome/fontawesome-svg-core, @fortawesome/free-solid-svg-icons, @fortawesome/react-fontawesome, next, node-pty, react, react-dom (+14 more)

### Community 6 - "Community 6"
Cohesion: 0.10
Nodes (19): compilerOptions, allowJs, esModuleInterop, incremental, isolatedModules, jsx, lib, module (+11 more)

### Community 7 - "Community 7"
Cohesion: 0.20
Nodes (9): claudeBin(), captureUsageScreen(), delay(), getUsage(), parseUsage(), Screen, UsageLimit, UsageSession (+1 more)

### Community 8 - "Community 8"
Cohesion: 0.25
Nodes (14): clearActive(), getActive(), readAll(), setActive(), storeFile(), writeAll(), getTitles(), readAll() (+6 more)

### Community 9 - "Community 9"
Cohesion: 0.19
Nodes (12): CachedTranscript, deleteCachedTranscript(), evict(), getCachedTranscript(), openDB(), putCachedTranscript(), warmTranscriptCache(), ContextInfo (+4 more)

### Community 10 - "Community 10"
Cohesion: 0.20
Nodes (13): Align, Block, INLINE, InlinePattern, isBlockStart(), isDelimRow(), isTableStart(), parseBlocks() (+5 more)

### Community 11 - "Community 11"
Cohesion: 0.33
Nodes (10): GET(), POST(), getFolderMeta(), isColor(), readAll(), setFolderColor(), storeFile(), writeAll() (+2 more)

### Community 12 - "Community 12"
Cohesion: 0.38
Nodes (10): DELETE(), GET(), POST(), claudeHome(), addFolder(), listFolders(), readAll(), removeFolder() (+2 more)

### Community 13 - "Community 13"
Cohesion: 0.22
Nodes (8): Configuration (environment), Deploy as a service (optional), How it works, License, Production, Quick start, Requirements, Security

## Knowledge Gaps
- **79 isolated node(s):** `ChatBody`, `SSE_HEADERS`, `Job`, `StopBody`, `metadata` (+74 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **4 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ClaudeEvent` connect `Community 9` to `Community 0`, `Community 1`, `Community 2`, `Community 3`, `Community 4`?**
  _High betweenness centrality (0.039) - this node is a cross-community bridge._
- **Why does `ChatStreamMessage` connect `Community 4` to `Community 0`, `Community 1`, `Community 3`?**
  _High betweenness centrality (0.014) - this node is a cross-community bridge._
- **What connects `ChatBody`, `SSE_HEADERS`, `Job` to the rest of the system?**
  _79 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.06328320802005012 - nodes in this community are weakly interconnected._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.1106612685560054 - nodes in this community are weakly interconnected._
- **Should `Community 2` be split into smaller, more focused modules?**
  _Cohesion score 0.08021390374331551 - nodes in this community are weakly interconnected._
- **Should `Community 3` be split into smaller, more focused modules?**
  _Cohesion score 0.11491935483870967 - nodes in this community are weakly interconnected._