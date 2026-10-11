import { styled } from "@mui/material/styles";
import { createContext, useContext, useMemo, type ReactNode } from "react";

import type { LIB_EN } from "../../i18n/en";
import { say } from "../../i18n/lib";
import {
  drawingOf,
  withoutMarkedIn,
  type Drawing,
  type Piece,
  type Unseen,
} from "./hidden";

const SAID = {
  shows_nothing: "text.showsNothing",
  turns_order: "text.turnsOrder",
} as const satisfies Record<Unseen, keyof typeof LIB_EN>;

const Marker = styled("span")(({ theme }) => ({
  display: "inline-block",
  margin: theme.spacing(0, 0.25),
  padding: theme.spacing(0, 0.25),
  borderWidth: 1,
  borderStyle: "solid",
  borderColor: theme.palette.divider,
  borderRadius: Number(theme.shape.borderRadius) * 0.5,
  color: theme.palette.text.secondary,
  ...theme.typography.identifier,
  whiteSpace: "nowrap",
}));

const LeftOut = styled("span")(({ theme }) => ({
  display: "block",
  color: theme.palette.text.secondary,
}));

const DrawnAcross = createContext<Drawing | null>(null);

/**
 * Characters selected by the hidden text rules become code-point markers, one
 * per same-character run. Past the budget they are omitted and counted above.
 * Never render marked characters themselves: they would still act.
 * Within `HiddenShownAcross`, use the enclosing value's drawing and notice.
 */
export function HiddenShown({
  text,
  ending,
  part,
}: {
  readonly text: string;
  readonly ending?: string;
  readonly part?: "before-ending" | "ending";
}) {
  const across = useContext(DrawnAcross);
  const drawing = useMemo(() => across ?? drawingOf([text]), [across, text]);
  if ("kept" in drawing) {
    const whole = judged(drawing.kept, text);
    const tail = ending === undefined ? "" : withoutMarkedIn(ending).kept;
    const kept =
      part === "ending"
        ? whole.slice(whole.length - tail.length)
        : part === "before-ending"
          ? whole.slice(0, whole.length - tail.length)
          : whole;
    return across === null ? (
      <>
        <LeftOutSaid count={drawing.leftOut} />
        {kept}
      </>
    ) : (
      kept
    );
  }
  const whole = judged(drawing.pieces, text);
  const split =
    ending === undefined ? text.length : text.length - ending.length;
  let position = 0;
  const pieces: Piece[] = [];
  for (const piece of whole) {
    const length =
      "text" in piece
        ? piece.text.length
        : String.fromCodePoint(piece.codePoint).length * piece.count;
    if (part === undefined) pieces.push(piece);
    else if ("text" in piece) {
      const drawn =
        part === "ending"
          ? piece.text.slice(Math.max(0, split - position))
          : piece.text.slice(0, Math.max(0, split - position));
      if (drawn !== "") pieces.push({ text: drawn });
    } else if (
      (part === "ending" && position >= split) ||
      (part === "before-ending" && position < split)
    )
      pieces.push(piece);
    position += length;
  }
  return pieces.map((piece, at) => {
    if ("text" in piece) {
      return piece.text;
    }
    const code = piece.codePoint.toString(16).toUpperCase().padStart(4, "0");
    const values = { code, count: piece.count };
    return (
      // A direction of its own isolates it, so the code reads as written in a line running either way.
      <Marker
        key={at}
        role="img"
        title={say(SAID[piece.unseen], values)}
        dir="ltr"
      >
        {say("text.marker", values)}
      </Marker>
    );
  });
}

/**
 * Shares one marker budget across `value`'s JSON texts drawn by `children`.
 * Past it, none is marked and all omitted characters are counted above.
 * Nested values use the enclosing drawing.
 */
export function HiddenShownAcross({
  value,
  children,
  suppressNotice = false,
  asDrawing,
}: {
  readonly value: unknown;
  readonly children: ReactNode;
  readonly suppressNotice?: boolean;
  readonly asDrawing?: Drawing;
}) {
  const within = useContext(DrawnAcross);
  const drawing = useMemo(
    () => asDrawing ?? (within === null ? drawingOf(textsIn(value)) : null),
    [asDrawing, within, value],
  );
  if (drawing === null) {
    return children;
  }
  return (
    <DrawnAcross.Provider value={drawing}>
      {"kept" in drawing && !suppressNotice ? (
        <LeftOutSaid count={drawing.leftOut} />
      ) : null}
      {children}
    </DrawnAcross.Provider>
  );
}

function LeftOutSaid({ count }: { readonly count: number }) {
  return (
    // A direction of its own keeps it out of the one the value around it takes from its first letter.
    <LeftOut dir="auto">{say("text.leftOut", { count })}</LeftOut>
  );
}

function judged<Drawn>(each: ReadonlyMap<string, Drawn>, text: string): Drawn {
  const drawn = each.get(text);
  if (drawn === undefined) {
    throw new Error("a text was drawn within a value that does not hold it");
  }
  return drawn;
}

export function textsIn(value: unknown): readonly string[] {
  const texts: string[] = [];
  const gather = (each: unknown): void => {
    if (typeof each === "string") {
      texts.push(each);
    } else if (Array.isArray(each)) {
      each.forEach(gather);
    } else if (typeof each === "object" && each !== null) {
      Object.values(each).forEach(gather);
    }
  };
  gather(value);
  return texts;
}
