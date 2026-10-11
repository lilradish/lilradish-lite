import { atSegment } from "../../../../../../../../lib/request/address";
import { putDocument } from "../../../../../../../../lib/request/http";
import { atVersion } from "../../../versions";
import {
  referenceListFrom,
  type ReferenceListVersion,
} from "../../{versionId}";
import type { Worded } from "../terms";

/** Each identifier is escaped as `atSegment` escapes one. */
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
 * Replaces both term and meaning, guarded by the revision read.
 * Returns the list as it then reads.
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
