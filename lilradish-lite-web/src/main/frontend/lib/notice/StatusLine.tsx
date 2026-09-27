import Box from "@mui/material/Box";
import type { SxProps, Theme } from "@mui/material/styles";
import { useLayoutEffect, useRef, useState, type RefObject } from "react";

import { Notice, type Severity } from "./Notice";

/** What a screen says in its own line. */
export interface Line {
  readonly severity: Severity;
  /** Shown as given, so a name in it arrives isolated by `isolatedInText`. */
  readonly words: string;
}

/**
 * A screen's own line: a region that stands before its first word, and puts
 * each new line in only once nothing above it is aria-hidden, and has not been
 * for a frame — words written under a modal are never heard, and words that
 * arrive with their region are not reliably heard either.
 *
 * A line already said stays, and is not said again for being uncovered. One
 * that arrives while hidden takes the last one down and waits.
 */
export function StatusLine({
  said,
  sx,
}: {
  /** Said anew for every new object, the same words or not; one object is said once. */
  readonly said: Line | null;
  readonly sx?: SxProps<Theme>;
}) {
  const region = useRef<HTMLDivElement>(null);
  const heard = useHeard(region);
  const [shown, setShown] = useState<{
    readonly line: Line | null;
    readonly turn: number;
  }>({ line: null, turn: 0 });
  const wanted = heard || shown.line === said ? said : null;
  if (wanted !== shown.line) {
    setShown({ line: wanted, turn: shown.turn + 1 });
  }

  return (
    <Box ref={region} role="status" sx={sx}>
      {shown.line === null ? null : (
        // Keyed by turn: the same words a second time are no change to the
        // document, and a region only speaks for a change.
        <Notice key={shown.turn} severity={shown.line.severity}>
          {shown.line.words}
        </Notice>
      )}
    </Box>
  );
}

/**
 * Whether nothing above the element is aria-hidden, and has not been through
 * at least one rendered frame. The attribute itself is watched, not whatever
 * sets it: a modal can go without ever finishing its way out.
 */
function useHeard(ref: RefObject<HTMLElement | null>): boolean {
  const [heard, setHeard] = useState(false);
  useLayoutEffect(() => {
    const element = ref.current!;
    let frame: number | null = null;
    const cancel = () => {
      if (frame !== null) {
        cancelAnimationFrame(frame);
        frame = null;
      }
    };
    const judge = () => {
      if (element.closest('[aria-hidden="true"]') !== null) {
        cancel();
        setHeard(false);
      } else if (frame === null) {
        // Two: the first runs before the frame that draws the region uncovered.
        frame = requestAnimationFrame(() => {
          frame = requestAnimationFrame(() => {
            frame = null;
            setHeard(true);
          });
        });
      }
    };
    judge();
    const watcher = new MutationObserver(judge);
    watcher.observe(element.ownerDocument.documentElement, {
      subtree: true,
      attributeFilter: ["aria-hidden"],
    });
    return () => {
      watcher.disconnect();
      cancel();
    };
  }, [ref]);
  return heard;
}
