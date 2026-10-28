export type { Item, Ask, Permit, Question, ChatEvent, Task, Context, Settings } from "../src/chat";
export type { Meta, ModelOption, Command, Limit } from "../src/meta";
export type { ChangeMeta } from "../src/changes";
export type { Block, Scene, SceneNode, SceneEdge, SceneCard, SceneGroup } from "../src/scene";
import type { Scene } from "../src/scene";

// started: when the chat began (the list's order); missing from servers before the hub.
export type ChatRow = { id: string; title: string; at: number; started?: number; ours: boolean; running: boolean; waiting: boolean; agents: number; color: string | null; archived: boolean; project: string | null; logo: boolean; word: string };
export type Picture = { turn: string; scene: Scene; changed: string[]; at: number };
export type { Timeline, Lane as TimelineLane, TimelineEvent, Detail as TimelineDetail } from "../src/timeline";

// /api/search: chats with their best hits; a snippet marks the matched words with \u0001 … \u0002.
export type SearchHit = { uuid: string; role: "user" | "claude"; snippet: string };
export type SearchFound = { id: string; hits: SearchHit[]; count: number; title: boolean };
