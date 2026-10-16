export type { Item, Ask, Permit, Question, ChatEvent } from "../src/chat";
export type { Scene, SceneNode, SceneEdge, SceneCard, SceneGroup } from "../src/scene";
import type { Scene } from "../src/scene";

export type ChatRow = { id: string; title: string; at: number; ours: boolean; running: boolean };
export type Picture = { turn: string; scene: Scene; changed: string[]; at: number };
