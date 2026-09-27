import { post } from "../../../../../../../lib/request/http";
import { atVersion } from "../../versions";
import { referenceListFrom, type ReferenceListVersion } from "../{versionId}";

/** A term and what it means, as they are written to a list. */
export interface Worded {
  readonly term: string;
  readonly meaning: string;
}

/** A term added after the last a draft holds, over the revision the page read; answered with the list as it then reads. */
export function addTerm(
  groupId: string,
  entryId: string,
  versionId: string,
  revision: number,
  worded: Worded,
  signal: AbortSignal,
): Promise<ReferenceListVersion> {
  const { term, meaning } = worded;
  return atVersion(groupId, "reference_list", entryId, versionId, (version) =>
    post(
      `${version}/terms`,
      { revision, term, meaning },
      signal,
      referenceListFrom,
    ),
  );
}
