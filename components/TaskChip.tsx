"use client";

import { useState } from "react";
import type { TaskRow } from "./fold";
import {
  FontAwesomeIcon,
  faCircle,
  faCircleCheck,
  faCircleXmark,
  faListCheck,
  faSpinner,
} from "./icons";

const TASK_ICON = {
  completed: faCircleCheck,
  in_progress: faSpinner,
  stopped: faCircleXmark,
  pending: faCircle,
} as const;

// Compact task indicator for the chat header: a circle with the list icon and a
// done/total badge; click to drop down the full list.
export default function TaskChip({ tasks }: { tasks: TaskRow[] }) {
  const [open, setOpen] = useState(false);
  const done = tasks.filter((t) => t.status === "completed").length;

  return (
    <div className="task-chip-wrap">
      <button
        className={`task-chip ${open ? "is-open" : ""}`}
        onClick={() => setOpen((v) => !v)}
        title="Tasks"
        aria-label={`Tasks ${done}/${tasks.length}`}
      >
        <FontAwesomeIcon icon={faListCheck} />
        <span className="task-chip-badge">
          {done}/{tasks.length}
        </span>
      </button>

      {open ? (
        <>
          <div className="task-pop-backdrop" onClick={() => setOpen(false)} />
          <div className="task-pop">
            <div className="task-pop-head">
              <FontAwesomeIcon icon={faListCheck} />
              <span>Tasks</span>
              <span className="task-pop-count">
                {done}/{tasks.length}
              </span>
            </div>
            <div className="task-pop-body">
              {tasks.map((t) => {
                const icon = TASK_ICON[t.status as keyof typeof TASK_ICON] ?? faCircle;
                const label =
                  t.status === "in_progress" && t.activeForm ? t.activeForm : t.subject;
                return (
                  <div key={t.id} className={`task-row is-${t.status}`}>
                    <span className="task-ic">
                      <FontAwesomeIcon icon={icon} spin={t.status === "in_progress"} />
                    </span>
                    <span className="task-subj">{label}</span>
                  </div>
                );
              })}
            </div>
          </div>
        </>
      ) : null}
    </div>
  );
}
