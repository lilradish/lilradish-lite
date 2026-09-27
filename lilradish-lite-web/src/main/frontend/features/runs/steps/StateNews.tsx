import Box from "@mui/material/Box";
import { useEffect, useState } from "react";

import { say } from "../../../i18n/app";

const HEARD_FOR_MILLISECONDS = 5000;

// In pixels: `sx` reads a bare 1 on a size as a fraction and emits 100%.
const UNSEEN_SX = {
  position: "absolute",
  height: "1px",
  width: "1px",
  clipPath: "inset(50%)",
  overflow: "hidden",
  whiteSpace: "nowrap",
};

/**
 * One thing a page keeps up: what identifies it, the name it is drawn by, and the words its state is said in, null
 * where this build has none.
 */
export interface Stated {
  readonly key: string;
  readonly name: string;
  readonly state: string | null;
}

/**
 * An unseen polite region, heard only where a read finds something in another state than the read before; one
 * finding nothing changed empties it, so the next change is new words, and so does a while gone by since it was
 * heard, so nobody comes on words gone stale once reads stop. Nothing drawn is read out here.
 */
export function StateNews<T>({
  reading,
  statesOf,
}: {
  readonly reading: T | null;
  readonly statesOf: (reading: T) => readonly Stated[];
}) {
  const [last, setLast] = useState({ reading });
  const [news, setNews] = useState("");
  if (reading !== last.reading) {
    setLast({ reading });
    setNews(
      last.reading === null || reading === null
        ? ""
        : newsOf(statesOf(last.reading), statesOf(reading)),
    );
  }
  useEffect(() => {
    if (news === "") {
      return;
    }
    const timer = setTimeout(() => setNews(""), HEARD_FOR_MILLISECONDS);
    return () => clearTimeout(timer);
  }, [news]);
  return (
    <Box role="status" sx={UNSEEN_SX}>
      {news}
    </Box>
  );
}

function newsOf(before: readonly Stated[], after: readonly Stated[]): string {
  const was = new Map(before.map((each) => [each.key, each.state]));
  return after
    .filter((each) => was.has(each.key) && was.get(each.key) !== each.state)
    .map((each) =>
      each.state === null
        ? say("step.nowUnknown", { name: each.name })
        : say("step.nowIn", { name: each.name, state: each.state }),
    )
    .join(" ");
}
