"use client";

import type { FolderMetaMap, FolderPath } from "@/lib/types";
import {
  FontAwesomeIcon,
  faChevronRight,
  faFolder,
  faFolderPlus,
  faPlus,
  faXmark,
} from "./icons";

function shortName(p: string): string {
  const clean = p.replace(/\/+$/, "");
  if (!clean) return "/";
  return clean.split("/").pop() || clean;
}

// The "+" on the tab strip opens this: a quick chooser over the folders you
// already watch, so starting a fresh session is one click. "Browse…" drops to
// the full filesystem picker for a folder you haven't added yet.
export default function NewSessionPicker({
  folders,
  meta,
  onPick,
  onBrowse,
  onClose,
}: {
  folders: FolderPath[];
  meta: FolderMetaMap;
  onPick: (path: string) => void;
  onBrowse: () => void;
  onClose: () => void;
}) {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <span className="modal-title">
            <FontAwesomeIcon icon={faPlus} /> New session
          </span>
          <button className="icon-btn" onClick={onClose} aria-label="Close">
            <FontAwesomeIcon icon={faXmark} />
          </button>
        </div>

        <div className="modal-list">
          {folders.length > 0 ? (
            folders.map((f) => (
              <div key={f} className="dir-row" onClick={() => onPick(f)}>
                <span
                  className={`dir-icon${
                    meta[f]?.color ? ` folder-color-${meta[f].color} tinted` : ""
                  }`}
                >
                  <FontAwesomeIcon icon={faFolder} />
                </span>
                <span className="dir-name">{shortName(f)}</span>
                <span className="dir-path ellipsis">{f}</span>
                <span className="chev">
                  <FontAwesomeIcon icon={faChevronRight} />
                </span>
              </div>
            ))
          ) : (
            <div className="muted center">No folders yet — browse for one.</div>
          )}
        </div>

        <div className="modal-foot">
          <span className="muted">Pick one of your folders</span>
          <div className="spacer" />
          <button className="btn ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn accent" onClick={onBrowse}>
            <FontAwesomeIcon icon={faFolderPlus} /> Browse…
          </button>
        </div>
      </div>
    </div>
  );
}
