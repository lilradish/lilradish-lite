import { post } from "../../../../../../../../../lib/request/http";
import {
  referenceListFrom,
  type ReferenceListVersion,
} from "../../../{versionId}";
import { atTerm } from "../{termId}";

/** A term taken out of a draft, over the revision the page read; answered with the list as it then reads. */
export function removeTerm(
  groupId: string,
  entryId: string,
  versionId: string,
  termId: string,
  revision: number,
  signal: AbortSignal,
): Promise<ReferenceListVersion> {
  return atTerm(groupId, entryId, versionId, termId, (term) =>
    post(`${term}/removal`, { revision }, signal, referenceListFrom),
  );
}
