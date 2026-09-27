import { atSegment } from "../../../../../../../../lib/request/address";
import { putDocument } from "../../../../../../../../lib/request/http";
import { atVersion } from "../../../versions";
import {
  referenceListFrom,
  type ReferenceListVersion,
} from "../../{versionId}";
import type { Worded } from "../terms";

/** One term's address in a list's version handed to `ask`, each identifier escaped as `atSegment` escapes one. */
export function atTerm<T>(
  groupId: string,
  entryId: string,
  versionId: string,
  termId: string,
  ask: (address: string) => Promise<T>,
): Promise<T> {
  return atVersion(groupId, "reference_list", entryId, versionId, (version) =>
    atSegment(`${version}/terms`, termId, ask),
  );
}

/**
 * A term and what it means, both written in place of what it held, over the
 * revision the page read; answered with the list as it then reads.
 */
export function rewordTerm(
  groupId: string,
  entryId: string,
  versionId: string,
  termId: string,
  revision: number,
  worded: Worded,
  signal: AbortSignal,
): Promise<ReferenceListVersion> {
  const { term, meaning } = worded;
  return atTerm(groupId, entryId, versionId, termId, (address) =>
    putDocument(
      address,
      { revision, term, meaning },
      signal,
      referenceListFrom,
    ),
  );
}
