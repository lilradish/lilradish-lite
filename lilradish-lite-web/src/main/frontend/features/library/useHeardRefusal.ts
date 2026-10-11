import { useEffect, useRef, useState, type RefObject } from "react";

import type { Problem } from "../../api/problem";
import type { Action } from "../../lib/request/useAction";

export type HeardRefusal = ReturnType<typeof useHeardRefusal>;

/**
 * The refusal of a part's own write, said where it was asked. One left once nothing is unsaved stays said, as
 * what happened, until the next change or until the reader lets go of the part.
 */
export function useHeardRefusal<T>(
  /** None where the part writes nothing of its own, handing its changes to a host. */
  action: Action<T> | undefined,
  unsaved: boolean,
  /** Where the refusal is said, focusable, which takes the keyboard from the Save it answers. */
  refusalAt?: RefObject<HTMLElement | null>,
) {
  const [asked, setAsked] = useState(false);
  const [unsavedSeen, setUnsavedSeen] = useState(unsaved);
  const pressed = useRef<Element | null>(null);
  if (unsaved !== unsavedSeen) {
    setUnsavedSeen(unsaved);
    if (unsaved && asked) setAsked(false);
  }
  const problem: Problem | null = asked ? (action?.problem ?? null) : null;
  useEffect(() => {
    if (problem === null) return;
    // Only while the keyboard is still on the Save pressed: a part the refusal
    // left read-only has already put it on its title.
    if (document.activeElement === pressed.current) refusalAt?.current?.focus();
    pressed.current = null;
  }, [problem, refusalAt]);
  return {
    /** The refusal to say, or none. */
    problem,
    /** Nothing is left to write: a refusal is said as what happened, never as a way on to writing it. */
    settled: !unsaved && action?.running !== true,
    /** Said as the write is sent. */
    sent: () => {
      pressed.current = document.activeElement;
      setAsked(true);
    },
    /** Said by Abandon, Cancel or leaving the part. */
    letGo: () => setAsked(false),
  };
}
