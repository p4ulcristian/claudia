"use client";

import { useCallback, useEffect, useState } from "react";
import type { BrowseResult } from "@/lib/types";
import { browse } from "./api";
import {
  FontAwesomeIcon,
  faArrowUp,
  faChevronRight,
  faCircle,
  faFolder,
  faFolderOpen,
  faPlus,
  faXmark,
} from "./icons";

export default function FolderPicker({
  onAdd,
  onClose,
}: {
  onAdd: (path: string) => void;
  onClose: () => void;
}) {
  const [result, setResult] = useState<BrowseResult | null>(null);
  const [loading, setLoading] = useState(true);

  const browseTo = useCallback(async (path: string | null) => {
    setLoading(true);
    try {
      setResult(await browse(path));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void browseTo(null);
  }, [browseTo]);

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <span className="modal-title">
            <FontAwesomeIcon icon={faFolderOpen} /> Choose a folder
          </span>
          <button className="icon-btn" onClick={onClose} aria-label="Close">
            <FontAwesomeIcon icon={faXmark} />
          </button>
        </div>

        <div className="modal-pathbar">
          <button
            className="up-btn"
            disabled={!result?.parent}
            onClick={() => result?.parent && browseTo(result.parent)}
            title="Up one level"
          >
            <FontAwesomeIcon icon={faArrowUp} />
          </button>
          <span className="path">{result?.path ?? "…"}</span>
        </div>

        <div className="modal-list">
          {loading ? (
            <div className="muted center">Loading…</div>
          ) : result && result.dirs.length > 0 ? (
            result.dirs.map((d) => (
              <div key={d.path} className="dir-row" onClick={() => browseTo(d.path)}>
                <span className="dir-icon">
                  <FontAwesomeIcon icon={faFolder} />
                </span>
                <span className="dir-name">{d.name}</span>
                {d.hasSessions ? (
                  <span className="badge">
                    <FontAwesomeIcon icon={faCircle} /> sessions
                  </span>
                ) : null}
                <span className="chev">
                  <FontAwesomeIcon icon={faChevronRight} />
                </span>
              </div>
            ))
          ) : (
            <div className="muted center">No sub-folders here.</div>
          )}
        </div>

        <div className="modal-foot">
          <span className="muted">Add the folder shown above</span>
          <div className="spacer" />
          <button className="btn ghost" onClick={onClose}>
            Cancel
          </button>
          <button
            className="btn accent"
            disabled={!result?.path}
            onClick={() => result?.path && onAdd(result.path)}
          >
            <FontAwesomeIcon icon={faPlus} /> Add this folder
          </button>
        </div>
      </div>
    </div>
  );
}
