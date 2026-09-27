import { useState } from "react";

/** The two ways Work is drawn; the page is the same either way. */
export type WorkDrawing = "conversation" | "detail";

const KEPT_AS = "lilradish.work.drawing";

/**
 * The way this device last drew Work, and a way to change it this device keeps. A first visit, and a storage
 * that keeps nothing or refuses, open as a conversation: a lost choice is never a reason for the page not to open.
 */
export function useWorkDrawing(): readonly [
  WorkDrawing,
  (next: WorkDrawing) => void,
] {
  const [drawing, setDrawing] = useState<WorkDrawing>(kept);

  const choose = (next: WorkDrawing) => {
    setDrawing(next);
    try {
      localStorage.setItem(KEPT_AS, next);
    } catch {
      // setItem throws where storage is refused, as in private browsing, or its quota is full.
    }
  };

  return [drawing, choose];
}

function kept(): WorkDrawing {
  try {
    return localStorage.getItem(KEPT_AS) === "detail"
      ? "detail"
      : "conversation";
  } catch {
    return "conversation";
  }
}
