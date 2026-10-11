import { useLayoutEffect, useRef, useState, type RefObject } from "react";

import { TYPING } from "./useDraftStatus";

export function useProseEdit(
  said: string | undefined,
  rewritten: ((text: string) => string) | undefined,
  editable: boolean,
  running: boolean,
  section: RefObject<HTMLElement | null>,
) {
  const [typed, setTyped] = useState(said ?? "");
  const [readAs, setReadAs] = useState({ said, rewritten });
  const [editing, setEditing] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const focusAfterClose = useRef(false);
  const previouslyEditing = useRef(false);
  const current = useRef({ readAs, typed });
  if (said !== readAs.said || rewritten !== readAs.rewritten) {
    setReadAs({ said, rewritten });
    if (
      rewritten !== undefined &&
      rewritten !== readAs.rewritten &&
      typed !== (readAs.said ?? "")
    ) {
      setTyped(rewritten(typed));
    } else if (said !== readAs.said) {
      setTyped(said ?? "");
    }
  }
  useLayoutEffect(() => {
    current.current = { readAs, typed };
  }, [readAs, typed]);
  useLayoutEffect(() => {
    if (editing && !previouslyEditing.current && editable) {
      section.current?.querySelector<HTMLElement>(TYPING)?.focus();
    } else if (!editing && focusAfterClose.current) {
      trigger.current?.focus();
      focusAfterClose.current = false;
    }
    previouslyEditing.current = editing;
  }, [editing, editable, section]);
  const close = () => {
    focusAfterClose.current =
      section.current?.contains(document.activeElement) ?? false;
    setEditing(false);
  };
  return {
    typed,
    editing,
    trigger,
    change: (text: string) => {
      if (!running) setTyped(text);
    },
    begin: () => {
      if (editable && (!running || editing)) {
        section.current?.scrollIntoView?.({ block: "nearest" });
        if (editing)
          section.current?.querySelector<HTMLElement>(TYPING)?.focus();
        else setEditing(true);
      }
    },
    cancel: () => {
      if (!running) {
        setTyped(said ?? "");
        close();
      }
    },
    saved: () => {
      if (current.current.readAs === readAs && current.current.typed === typed)
        close();
    },
  };
}
