import { optional, trueFrom, type Unchecked } from "../lib/request/document";
import { isShownValue, type ShownValue } from "./filling";

/** Withheld is never none: it is a value the reader may not read, and is said so. */
export type Shown =
  { readonly value: ShownValue } | { readonly withheld: true };

/**
 * A value of a shape its field no longer has, or of a field no longer declared, given as it was kept: its JSON
 * text, none as null, under the name it was kept under.
 */
type AsKept = { readonly asKept: string | null };

/** A step's own value as shown, which only a code step's release can have left in an earlier shape. */
export type ShownOrKept = Shown | AsKept;

/** A value, or its being withheld: exactly one of the two, where neither or both is no value this side can say. */
export function shownFrom(
  body: Unchecked,
  outer: readonly string[] = [],
): Shown | null {
  const withheld = optional(body, "withheld", trueFrom);
  if (withheld === null) {
    return null;
  }
  if (!("value" in body)) {
    return withheld === undefined ||
      Object.keys(body).some(
        (key) => key !== "withheld" && !outer.includes(key),
      )
      ? null
      : { withheld };
  }
  return withheld === undefined && isShownValue(body.value)
    ? { value: body.value }
    : null;
}

/**
 * As `shownFrom`, or a value flagged as given in an earlier shape: text or none, never withheld, and never read as
 * a value of any field.
 */
export function shownOrKeptFrom(
  body: Unchecked,
  outer: readonly string[] = [],
): ShownOrKept | null {
  const earlierShape = optional(body, "earlierShape", trueFrom);
  if (earlierShape === undefined) {
    return shownFrom(body, outer);
  }
  if (earlierShape === null || "withheld" in body) {
    return null;
  }
  const { value } = body;
  return value === null || typeof value === "string" ? { asKept: value } : null;
}
