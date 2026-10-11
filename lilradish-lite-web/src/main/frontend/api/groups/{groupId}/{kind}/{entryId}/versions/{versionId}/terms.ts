import { post } from "../../../../../../../lib/request/http";
import { atVersion } from "../../versions";
import { referenceListFrom, type ReferenceListVersion } from "../{versionId}";

export interface Worded {
  readonly term: string;
  readonly meaning: string;
}

/** Appends after the draft's last term, guarded by the revision read; returns the list as it then reads. */
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
