import Box from "@mui/material/Box";
import { useEffect, useState } from "react";

import { say } from "../../../i18n/app";
import { UNSEEN_SX } from "../../../lib/layout/parts";

const HEARD_FOR_MILLISECONDS = 5000;

export interface Stated {
  readonly key: string;
  readonly name: string;
  readonly state: string | null;
}

// Clear old announcements so a later change is heard once, even after reads stop.
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
