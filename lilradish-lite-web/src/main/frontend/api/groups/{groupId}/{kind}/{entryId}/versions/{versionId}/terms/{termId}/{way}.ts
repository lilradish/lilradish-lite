import { post } from "../../../../../../../../../lib/request/http";
import {
  referenceListFrom,
  type ReferenceListVersion,
} from "../../../{versionId}";
import { atTerm } from "../{termId}";

/** Which way a term moves, by one place, spelt as its address spells it. */
export type TermWay = "up" | "down";

/**
 * A term swapped with the one before it or after it, over the revision the
 * page read; answered with the list as it then reads.
 */
export function moveTerm(
  groupId: string,
  entryId: string,
  versionId: string,
  termId: string,
  way: TermWay,
  revision: number,
  signal: AbortSignal,
): Promise<ReferenceListVersion> {
  return atTerm(groupId, entryId, versionId, termId, (term) =>
    post(`${term}/${way}`, { revision }, signal, referenceListFrom),
  );
}
