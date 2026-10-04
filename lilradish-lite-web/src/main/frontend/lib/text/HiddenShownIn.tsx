import Box from "@mui/material/Box";
import { Fragment } from "react";
import type { ReactNode } from "react";

import { HiddenShown } from "./HiddenShown";

// No sentence in a catalogue holds it, so splitting on it finds only where the text was put.
const PLACE = String.fromCodePoint(0);

export function SystemHint({ children }: { readonly children: ReactNode }) {
  return (
    <Box component="span" sx={{ typography: "systemHint" }}>
      {children}
    </Box>
  );
}

function HintedPart({ part }: { readonly part: string }) {
  const words = part.trim();
  if (words === "") {
    return part;
  }
  const start = part.indexOf(words);
  return (
    <>
      {part.slice(0, start)}
      <SystemHint>{words}</SystemHint>
      {part.slice(start + words.length)}
    </>
  );
}

/**
 * A sentence with text set into it, the text isolated and drawn as `HiddenShown` draws it where the sentence puts
 * it: `sentence` is handed what to put there, and says the rest.
 */
export function HiddenShownIn({
  sentence,
  text,
  hint = false,
}: {
  readonly sentence: (place: string) => string;
  readonly text: string;
  readonly hint?: boolean;
}) {
  return sentence(PLACE)
    .split(PLACE)
    .map((part, at) => (
      <Fragment key={at}>
        {at === 0 ? null : (
          <bdi>
            <HiddenShown text={text} />
          </bdi>
        )}
        {hint ? <HintedPart part={part} /> : part}
      </Fragment>
    ));
}
