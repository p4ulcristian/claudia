"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import dynamic from "next/dynamic";
import { getFileTree, readFile, saveFile } from "./api";
import type { FileNode } from "@/lib/types";
import {
  FontAwesomeIcon,
  faCode,
  faXmark,
  faChevronRight,
  faChevronDown,
  faFolder,
  faFloppyDisk,
  faCircle,
} from "./icons";

// Monaco is browser-only; never server-render it.
const MonacoEditor = dynamic(() => import("@monaco-editor/react"), {
  ssr: false,
  loading: () => <div className="code-empty">Loading editor…</div>,
});

// One row in the recursive file tree. Dirs toggle; files open.
function TreeNode({
  node,
  depth,
  openPath,
  onOpen,
}: {
  node: FileNode;
  depth: number;
  openPath: string | null;
  onOpen: (path: string) => void;
}) {
  const [expanded, setExpanded] = useState(depth < 1);
  const pad = { paddingLeft: 8 + depth * 12 };

  if (!node.dir) {
    return (
      <button
        type="button"
        className={"code-file" + (openPath === node.path ? " active" : "")}
        style={pad}
        onClick={() => onOpen(node.path)}
        title={node.path}
      >
        {node.name}
      </button>
    );
  }

  return (
    <div className="code-dir">
      <button
        type="button"
        className="code-dir-row"
        style={pad}
        onClick={() => setExpanded((v) => !v)}
      >
        <FontAwesomeIcon icon={expanded ? faChevronDown : faChevronRight} />
        <FontAwesomeIcon icon={faFolder} />
        <span>{node.name}</span>
      </button>
      {expanded &&
        node.children?.map((c) => (
          <TreeNode
            key={c.path}
            node={c}
            depth={depth + 1}
            openPath={openPath}
            onOpen={onOpen}
          />
        ))}
    </div>
  );
}

export default function CodePanel({
  folder,
  onClose,
  initialPath,
}: {
  folder: string;
  onClose: () => void;
  /** Relative path to open immediately (e.g. from a Read/Edit tool card). */
  initialPath?: string;
}) {
  const [tree, setTree] = useState<FileNode[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [openPath, setOpenPath] = useState<string | null>(null);
  const [kind, setKind] = useState<"text" | "image">("text");
  const [language, setLanguage] = useState("plaintext");
  const [value, setValue] = useState("");
  // The last-saved contents; compared against `value` to derive the dirty flag.
  const [saved, setSaved] = useState("");
  const [saving, setSaving] = useState(false);

  // Images are view-only, so they're never dirty/savable.
  const dirty = kind === "text" && value !== saved;

  useEffect(() => {
    let alive = true;
    getFileTree(folder)
      .then((t) => alive && setTree(t))
      .catch((e) => alive && setError(String(e)));
    return () => {
      alive = false;
    };
  }, [folder]);

  const openFile = useCallback(
    (path: string) => {
      readFile(folder, path)
        .then((f) => {
          setOpenPath(f.path);
          setKind(f.kind);
          setLanguage(f.language);
          setValue(f.content);
          setSaved(f.content);
          setError(null);
        })
        .catch((e) => setError(String(e)));
    },
    [folder],
  );

  // Open the requested file when launched from a tool card (and re-open if the
  // user clicks a different file's icon while the panel is already up).
  useEffect(() => {
    if (initialPath) openFile(initialPath);
  }, [initialPath, openFile]);

  const save = useCallback(() => {
    if (!openPath || saving) return;
    setSaving(true);
    saveFile(folder, openPath, value)
      .then(() => setSaved(value))
      .catch((e) => setError(String(e)))
      .finally(() => setSaving(false));
  }, [folder, openPath, value, saving]);

  // Keep the latest save() in a ref so the keydown handler stays stable.
  const saveRef = useRef(save);
  saveRef.current = save;
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === "s") {
        e.preventDefault();
        saveRef.current();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    <div className="git-backdrop" onClick={onClose}>
      <div
        className="git-shelf code-shelf"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="git-shelf-head">
          <FontAwesomeIcon icon={faCode} />
          <span className="git-repo-name">
            {openPath ?? "Select a file"}
          </span>
          {dirty && (
            <span className="code-dirty" title="Unsaved changes">
              <FontAwesomeIcon icon={faCircle} />
            </span>
          )}
          <span className="spacer" />
          <button
            className={"btn" + (dirty ? " accent" : " ghost")}
            onClick={save}
            disabled={!openPath || !dirty || saving}
            title="Save (⌘/Ctrl+S)"
          >
            <FontAwesomeIcon icon={faFloppyDisk} />{" "}
            {saving ? "Saving…" : "Save"}
          </button>
          <button className="icon-btn" onClick={onClose} title="Close">
            <FontAwesomeIcon icon={faXmark} />
          </button>
        </div>

        <div className="code-body">
          <div className="code-tree">
            {error && <div className="error mono">error: {error}</div>}
            {!tree && !error && <div className="code-empty">Loading…</div>}
            {tree?.map((n) => (
              <TreeNode
                key={n.path}
                node={n}
                depth={0}
                openPath={openPath}
                onOpen={openFile}
              />
            ))}
          </div>
          <div className="code-editor">
            {!openPath ? (
              <div className="code-empty">Pick a file from the tree.</div>
            ) : kind === "image" ? (
              <div className="code-image">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={value} alt={openPath} />
              </div>
            ) : (
              <MonacoEditor
                theme="vs-dark"
                language={language}
                value={value}
                onChange={(v) => setValue(v ?? "")}
                options={{
                  fontSize: 13,
                  minimap: { enabled: false },
                  scrollBeyondLastLine: false,
                  automaticLayout: true,
                }}
              />
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
