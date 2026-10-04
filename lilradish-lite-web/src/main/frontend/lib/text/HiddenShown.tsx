import { styled } from "@mui/material/styles";
import { createContext, useContext, useMemo, type ReactNode } from "react";

import type { LIB_EN } from "../../i18n/en";
import { say } from "../../i18n/lib";
import { drawingOf, type Drawing, type Unseen } from "./hidden";

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
 * Text as it came, every character in it that would show nothing or turn the order the rest is shown in drawn
 * where it stands as its code point, a run of one as one; or, past the most markers drawn, left out and counted
 * above it. The character itself is never put on the page: left there, it would still act. Within
 * `HiddenShownAcross`, the text is drawn as the value around it is, and nothing is said of it here.
 */
export function HiddenShown({ text }: { readonly text: string }) {
  const across = useContext(DrawnAcross);
  const drawing = useMemo(() => across ?? drawingOf([text]), [across, text]);
  if ("kept" in drawing) {
    const kept = judged(drawing.kept, text);
    return across === null ? (
      <>
        <LeftOutSaid count={drawing.leftOut} />
        {kept}
      </>
    ) : (
      kept
    );
  }
  return judged(drawing.pieces, text).map((piece, at) => {
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
 * `children`, which draw `value`, every text in it as JSON holds them drawn by `HiddenShown` as one: past the most
 * markers across them all, none is marked, and how many characters were left out of them all is said above. Within
 * another, it is drawn as that one is.
 */
export function HiddenShownAcross({
  value,
  children,
}: {
  readonly value: unknown;
  readonly children: ReactNode;
}) {
  const within = useContext(DrawnAcross);
  const drawing = useMemo(
    () => (within === null ? drawingOf(textsIn(value)) : null),
    [within, value],
  );
  if (drawing === null) {
    return children;
  }
  return (
    <DrawnAcross.Provider value={drawing}>
      {"kept" in drawing ? <LeftOutSaid count={drawing.leftOut} /> : null}
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

function textsIn(value: unknown): readonly string[] {
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
