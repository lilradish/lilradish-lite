import { atSegment } from "../../../lib/request/address";
import { isUnchecked, listOf, optional } from "../../../lib/request/document";
import { get, putDocument } from "../../../lib/request/http";
import { GROUPS } from "../../groups";

/**
 * The currency a group reads its costs in, and the catalog shown to every membership reader. Once chosen it is only ever
 * changed, and it stays chosen after no model is priced in it any longer.
 */
export interface CurrencyChoice {
  /** Absent where none has been chosen. */
  readonly chosen?: string;
  /** The catalog in order of code, empty where no model is priced in any. */
  readonly offered: readonly string[];
}

// Three capitals, as the server holds a code: anything else names no currency,
// and asking the reader's language for its name is refused as malformed.
const CODE = /^[A-Z]{3}$/;

function atCurrency<T>(
  groupId: string,
  ask: (address: string) => Promise<T>,
): Promise<T> {
  return atSegment(GROUPS, groupId, (group) => ask(`${group}/currency`));
}

export function readCurrency(
  groupId: string,
  signal: AbortSignal,
): Promise<CurrencyChoice> {
  return atCurrency(groupId, (address) => get(address, signal, choiceFrom));
}

/** Whether any model is priced in it is the server's to say. */
export function chooseCurrency(
  groupId: string,
  code: string,
  signal: AbortSignal,
): Promise<CurrencyChoice> {
  return atCurrency(groupId, (address) =>
    putDocument(address, { currency: code }, signal, choiceFrom),
  );
}

/** One invalid currency code fails the whole answer. */
function choiceFrom(body: unknown): CurrencyChoice | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const chosen = optional(body, "chosen", codeFrom);
  const offered = listOf(body.offered, codeFrom);
  if (chosen === null || offered === null) {
    return null;
  }
  return {
    ...(chosen === undefined ? {} : { chosen }),
    offered,
  };
}

function codeFrom(value: unknown): string | null {
  return typeof value === "string" && CODE.test(value) ? value : null;
}
