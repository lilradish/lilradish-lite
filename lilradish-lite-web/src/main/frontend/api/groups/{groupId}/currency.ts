import { atSegment } from "../../../lib/request/address";
import { isUnchecked, listOf, optional } from "../../../lib/request/document";
import { get, putDocument } from "../../../lib/request/http";
import { GROUPS } from "../../groups";

/**
 * The currency a group reads its costs in, and, only to a reader who may
 * change it, the currencies it may be changed to. Once chosen it is only ever
 * changed, and it stays chosen after no model is priced in it any longer.
 */
export interface CurrencyChoice {
  /** Absent where none has been chosen. */
  readonly chosen?: string;
  /** In order of code; absent where the reader may not change it, and empty where no model is priced in any. */
  readonly offered?: readonly string[];
}

// Three capitals, as the server holds a code: anything else names no currency,
// and asking the reader's language for its name is refused as malformed.
const CODE = /^[A-Z]{3}$/;

/** The group's currency's address handed to `ask`, made as `atSegment` makes one. */
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

/** Chosen, or changed to; answered as a read is. Whether any model is priced in it is the server's to say. */
export function chooseCurrency(
  groupId: string,
  code: string,
  signal: AbortSignal,
): Promise<CurrencyChoice> {
  return atCurrency(groupId, (address) =>
    putDocument(address, { currency: code }, signal, choiceFrom),
  );
}

/** Built member by member; a code that is none fails the whole answer. */
function choiceFrom(body: unknown): CurrencyChoice | null {
  if (!isUnchecked(body)) {
    return null;
  }
  const chosen = optional(body, "chosen", codeFrom);
  const offered = optional(body, "offered", (listed) =>
    listOf(listed, codeFrom),
  );
  if (chosen === null || offered === null) {
    return null;
  }
  return {
    ...(chosen === undefined ? {} : { chosen }),
    ...(offered === undefined ? {} : { offered }),
  };
}

function codeFrom(value: unknown): string | null {
  return typeof value === "string" && CODE.test(value) ? value : null;
}
